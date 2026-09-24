package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** JSON persistence for explicit monitor schedules and bounded results. */
public final class MonitorStore {
    static final int SCHEMA_VERSION = 1;
    static final int MAX_RUNS = 100;
    static final int MAX_SUMMARIES = 50;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int RUNTIME_LOCK_ATTEMPTS = 3;
    private static FileKeyReader fileKeyReader = MonitorStore::readFileKey;
    private static Runnable runtimeLockDeleteBarrierForTests;

    private final Path file;
    private final Path lockFile;

    public MonitorStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        Path parent = this.file.getParent();
        if (parent == null) {
            throw new MonitorException("Для monitor store нужен абсолютный путь с каталогом.");
        }
        try {
            Files.createDirectories(parent);
            ownerPermissions(parent, true);
        } catch (IOException e) {
            throw new MonitorException("Не удалось создать каталог monitor store.");
        }
        this.lockFile = parent.resolve(this.file.getFileName() + ".lock");
    }

    public static MonitorStore openDefault() {
        return new MonitorStore(Path.of(System.getProperty("user.home"), ".ai-advent-agent",
                "git-monitor.json"));
    }

    public Path file() {
        return file;
    }

    public List<MonitorSchedule> schedules() {
        return withLock(state -> List.copyOf(state.schedules.values()));
    }

    public MonitorSchedule schedule(String id) {
        return withLock(state -> state.schedules.get(id));
    }

    public MonitorSchedule create(String repositoryRoot, java.time.Duration interval,
                                  java.time.Duration summaryInterval) {
        return withLock(state -> {
            if (state.schedules.values().stream()
                    .anyMatch(item -> item.repositoryRoot().equals(repositoryRoot))) {
                throw new MonitorException("Расписание для этого репозитория уже существует.");
            }
            MonitorSchedule schedule = MonitorSchedule.create(repositoryRoot, interval, summaryInterval);
            state.schedules.put(schedule.id(), schedule);
            return schedule;
        });
    }

    public MonitorSchedule setEnabled(String id, boolean enabled) {
        return withLock(state -> {
            MonitorSchedule current = requireSchedule(state, id);
            MonitorSchedule next = current.withEnabled(enabled);
            state.schedules.put(id, next);
            return next;
        });
    }

    public MonitorSchedule setTiming(String id, long missedCount, String nextRunAt) {
        return withLock(state -> {
            MonitorSchedule current = requireSchedule(state, id);
            MonitorSchedule next = current.withTiming(missedCount, nextRunAt);
            state.schedules.put(id, next);
            return next;
        });
    }

    public void remove(String id) {
        withLock(state -> {
            requireSchedule(state, id);
            state.schedules.remove(id);
            state.runs.remove(id);
            state.summaries.remove(id);
            state.snapshots.remove(id);
            return null;
        });
    }

    public List<MonitorRun> runs(String id) {
        return withLock(state -> List.copyOf(state.runs.getOrDefault(id, List.of())));
    }

    public List<MonitorSummary> summaries(String id) {
        return withLock(state -> List.copyOf(state.summaries.getOrDefault(id, List.of())));
    }

    public GitRepositoryStatus lastSnapshot(String id) {
        return withLock(state -> state.snapshots.get(id));
    }

    public void record(MonitorRun run, GitRepositoryStatus snapshot, MonitorSummary summary) {
        withLock(state -> {
            requireSchedule(state, run.scheduleId());
            List<MonitorRun> runs = new ArrayList<>(state.runs.getOrDefault(run.scheduleId(), List.of()));
            runs.add(run);
            if (runs.size() > MAX_RUNS) runs = new ArrayList<>(runs.subList(runs.size() - MAX_RUNS, runs.size()));
            state.runs.put(run.scheduleId(), List.copyOf(runs));
            if (snapshot != null && run.success()) state.snapshots.put(run.scheduleId(), snapshot);
            if (summary != null) {
                List<MonitorSummary> summaries = new ArrayList<>(
                        state.summaries.getOrDefault(run.scheduleId(), List.of()));
                summaries.add(summary);
                if (summaries.size() > MAX_SUMMARIES) {
                    summaries = new ArrayList<>(summaries.subList(summaries.size() - MAX_SUMMARIES,
                            summaries.size()));
                }
                state.summaries.put(run.scheduleId(), List.copyOf(summaries));
            }
            return null;
        });
    }

    public void recordSummary(MonitorSummary summary) {
        withLock(state -> {
            if (!state.schedules.containsKey(summary.scheduleId())) {
                throw new MonitorException("Расписание не найдено: " + summary.scheduleId());
            }
            List<MonitorSummary> summaries = new ArrayList<>(
                    state.summaries.getOrDefault(summary.scheduleId(), List.of()));
            summaries.add(summary);
            if (summaries.size() > MAX_SUMMARIES) {
                summaries = new ArrayList<>(summaries.subList(summaries.size() - MAX_SUMMARIES,
                        summaries.size()));
            }
            state.summaries.put(summary.scheduleId(), List.copyOf(summaries));
            return null;
        });
    }

    /** Acquires a per-schedule runtime lock for the duration of one MCP run. */
    public RuntimeLease tryRuntimeLock(String scheduleId) {
        Path runtimeFile = file.resolveSibling(file.getFileName() + "." + scheduleId + ".run.lock");
        for (int attempt = 0; attempt < RUNTIME_LOCK_ATTEMPTS; attempt++) {
            FileChannel channel = null;
            FileLock lock = null;
            try {
                channel = FileChannel.open(runtimeFile, StandardOpenOption.CREATE,
                        StandardOpenOption.READ, StandardOpenOption.WRITE);
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException e) {
                    lock = null;
                }
                if (lock == null) {
                    close(channel);
                    return null;
                }
                Object ownerKey = fileKey(runtimeFile);
                Object pathKey = fileKey(runtimeFile);
                if (ownerKey != null && !Objects.equals(ownerKey, pathKey)) {
                    release(lock, channel);
                    continue;
                }
                ownerPermissions(runtimeFile, false);
                return new RuntimeLease(runtimeFile, channel, lock, ownerKey, ownerKey != null);
            } catch (IOException e) {
                release(lock, channel);
                throw new MonitorException("Не удалось открыть runtime-lock расписания.");
            }
        }
        throw new MonitorException("Не удалось подтвердить runtime-lock расписания.");
    }

    @FunctionalInterface
    interface FileKeyReader {
        Object read(Path path) throws IOException;
    }

    static void setFileKeyReaderForTests(FileKeyReader reader) {
        fileKeyReader = reader == null ? MonitorStore::readFileKey : reader;
    }

    static void setRuntimeLockDeleteBarrierForTests(Runnable barrier) {
        runtimeLockDeleteBarrierForTests = barrier;
    }

    private static Object fileKey(Path path) throws IOException {
        return fileKeyReader.read(path);
    }

    private static Object readFileKey(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    private static void close(FileChannel channel) {
        if (channel == null) return;
        try { channel.close(); } catch (IOException ignored) { }
    }

    private static void release(FileLock lock, FileChannel channel) {
        if (lock != null) {
            try { lock.release(); } catch (IOException ignored) { }
        }
        close(channel);
    }

    private MonitorSchedule requireSchedule(State state, String id) {
        MonitorSchedule schedule = state.schedules.get(id);
        if (schedule == null) throw new MonitorException("Расписание не найдено: " + id);
        return schedule;
    }

    private <T> T withLock(java.util.function.Function<State, T> operation) {
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ownerPermissions(lockFile, false);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) throw new MonitorException("Monitor store занят другим процессом.");
            FileLock heldLock = lock;
            try (heldLock) {
                State state = load();
                T result = operation.apply(state);
                save(state);
                return result;
            }
        } catch (MonitorException e) {
            throw e;
        } catch (IOException e) {
            throw new MonitorException("Не удалось заблокировать monitor store.");
        }
    }

    private State load() {
        if (!Files.exists(file)) return new State();
        try {
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (root == null || root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION) {
                throw new MonitorException("Неизвестная версия git-monitor.json.");
            }
            State state = new State();
            for (JsonNode node : root.path("schedules")) {
                MonitorSchedule schedule = JSON.treeToValue(node, MonitorSchedule.class);
                state.schedules.put(schedule.id(), schedule);
            }
            readMap(root.path("runs"), MonitorRun.class, state.runs);
            readMap(root.path("summaries"), MonitorSummary.class, state.summaries);
            JsonNode snapshots = root.path("lastSnapshots");
            snapshots.fields().forEachRemaining(entry -> {
                try {
                    state.snapshots.put(entry.getKey(), JSON.treeToValue(entry.getValue(), GitRepositoryStatus.class));
                } catch (Exception e) {
                    throw new MonitorException("Повреждён lastSnapshots в git-monitor.json.");
                }
            });
            return state;
        } catch (MonitorException e) {
            throw e;
        } catch (Exception e) {
            throw new MonitorException("Не удалось прочитать git-monitor.json.");
        }
    }

    private <T> void readMap(JsonNode node, Class<T> type, Map<String, List<T>> target) {
        node.fields().forEachRemaining(entry -> {
            List<T> values = new ArrayList<>();
            for (JsonNode item : entry.getValue()) {
                try {
                    values.add(JSON.treeToValue(item, type));
                } catch (Exception e) {
                    throw new MonitorException("Повреждён git-monitor.json.");
                }
            }
            target.put(entry.getKey(), List.copyOf(values));
        });
    }

    private void save(State state) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("schemaVersion", SCHEMA_VERSION);
            root.set("schedules", JSON.valueToTree(state.schedules.values()));
            root.set("runs", JSON.valueToTree(state.runs));
            root.set("summaries", JSON.valueToTree(state.summaries));
            root.set("lastSnapshots", JSON.valueToTree(state.snapshots));
            String content = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
            Path temp = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".tmp");
            try {
                try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                ownerPermissions(temp, false);
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    throw new MonitorException("Файловая система не поддерживает атомарную запись monitor store.");
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (MonitorException e) {
            throw e;
        } catch (IOException e) {
            throw new MonitorException("Не удалось сохранить git-monitor.json.");
        }
    }

    private static void ownerPermissions(Path path, boolean directory) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                    directory ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems use their normal permission model.
        }
    }

    private static final class State {
        final LinkedHashMap<String, MonitorSchedule> schedules = new LinkedHashMap<>();
        final LinkedHashMap<String, List<MonitorRun>> runs = new LinkedHashMap<>();
        final LinkedHashMap<String, List<MonitorSummary>> summaries = new LinkedHashMap<>();
        final LinkedHashMap<String, GitRepositoryStatus> snapshots = new LinkedHashMap<>();
    }

    public static final class RuntimeLease implements AutoCloseable {
        private final Path runtimeFile;
        private final FileChannel channel;
        private final FileLock lock;
        private final Object ownerKey;
        private final boolean deleteFile;

        private RuntimeLease(Path runtimeFile, FileChannel channel, FileLock lock,
                             Object ownerKey, boolean deleteFile) {
            this.runtimeFile = runtimeFile;
            this.channel = channel;
            this.lock = lock;
            this.ownerKey = ownerKey;
            this.deleteFile = deleteFile;
        }

        @Override
        public void close() {
            if (deleteFile) {
                try {
                    Object currentKey = fileKey(runtimeFile);
                    if (currentKey != null && Objects.equals(currentKey, ownerKey)) {
                        Runnable barrier = runtimeLockDeleteBarrierForTests;
                        if (barrier != null) barrier.run();
                        Files.deleteIfExists(runtimeFile);
                    }
                } catch (IOException | RuntimeException e) {
                    System.err.println("Не удалось удалить monitor runtime-lock; запуск продолжен.");
                }
            }
            try { lock.release(); } catch (IOException ignored) { }
            try { channel.close(); } catch (IOException ignored) { }
        }
    }
}
