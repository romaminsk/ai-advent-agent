package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Long-lived interval worker. It deliberately does not create the chat agent. */
public final class MonitorWorker {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long POLL_MILLIS = 2000;
    private static final long HEARTBEAT_MILLIS = 5000;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Clock clock;
    private final Path dir = Path.of(System.getProperty("user.home"), ".ai-advent-agent");
    private final Path lockPath = dir.resolve("monitor-worker.lock");
    private final Path heartbeatPath = dir.resolve("monitor-worker.json");
    private String startedAt;

    /** Продакшен-конструктор: системные часы. */
    public MonitorWorker() {
        this(Clock.systemUTC());
    }

    /** Тестируемость: подставные часы не меняют поведение планировщика. */
    MonitorWorker(Clock clock) {
        this.clock = clock;
    }

    public int run() {
        installSignalHandler(Thread.currentThread());
        try {
            Files.createDirectories(dir);
            if (!running.get()) {
                writeHeartbeat("stopped", 0);
                return 0;
            }
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                FileLock lock;
                try { lock = channel.tryLock(); }
                catch (OverlappingFileLockException e) { lock = null; }
                if (lock == null) {
                    System.err.println("Monitor worker уже запущен.");
                    return 2;
                }
                if (!running.get()) {
                    writeHeartbeat("stopped", 0);
                    return 0;
                }
                ownerOnly(lockPath);
                FileLock heldLock = lock;
                try (heldLock) { return loop(); }
            }
        } catch (IOException e) {
            System.err.println("Monitor worker: не удалось открыть lock.");
            return 2;
        }
    }

    private int loop() {
        Thread workerThread = Thread.currentThread();
        Thread shutdown = new Thread(() -> {
            running.set(false);
            workerThread.interrupt();
            writeHeartbeat("stopped", activeSchedules());
        }, "monitor-worker-stop");
        Runtime.getRuntime().addShutdownHook(shutdown);
        long lastHeartbeat = 0;
        startedAt = Instant.now(clock).toString();
        writeHeartbeat("running", 0);
        System.out.println("monitor-worker started");
        try {
            while (running.get()) {
                long now = System.currentTimeMillis();
                if (now - lastHeartbeat >= HEARTBEAT_MILLIS) {
                    writeHeartbeat("running", activeSchedules());
                    lastHeartbeat = now;
                }
                try {
                    tick(MonitorStore.openDefault());
                } catch (RuntimeException e) {
                    if (running.get()) throw e;
                }
                try { Thread.sleep(POLL_MILLIS); }
                catch (InterruptedException e) { running.set(false); Thread.currentThread().interrupt(); }
            }
            writeHeartbeat("stopped", activeSchedules());
            System.out.println("monitor-worker stopped");
            return 0;
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException ignored) { }
        }
    }

    /**
     * SIGTERM/SIGINT переводят worker в штатное завершение: цикл выходит сам,
     * пишет heartbeat "stopped" и возвращает 0. Без своего обработчика JVM
     * прерывается сигналом (код 143) и код выхода терял смысл.
     */
    private void installSignalHandler(Thread workerThread) {
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("TERM"), signal -> {
                running.set(false);
                workerThread.interrupt();
            });
            sun.misc.Signal.handle(new sun.misc.Signal("INT"), signal -> {
                running.set(false);
                workerThread.interrupt();
            });
        } catch (Throwable ignored) {
            // Без доступа к sun.misc сигнал обрабатывают стандартные shutdown hooks.
        }
    }

    /** Один проход планировщика; store передаётся для тестируемости. */
    void tick(MonitorStore store) {
        List<MonitorSchedule> schedules = store.schedules();
        Instant now = Instant.now(clock);
        for (MonitorSchedule schedule : schedules) {
            if (!running.get() || !schedule.enabled()) continue;
            Instant next = parse(schedule.nextRunAt());
            if (next == null) {
                store.setTiming(schedule.id(), schedule.missedCount(), now.toString());
                next = now;
            }
            if (!now.isBefore(next)) {
                long missed = schedule.missedCount();
                if (schedule.intervalSeconds() > 0) {
                    missed += Math.max(0, (now.getEpochSecond() - next.getEpochSecond())
                            / schedule.intervalSeconds());
                }
                store.setTiming(schedule.id(), missed, now.plusSeconds(schedule.intervalSeconds()).toString());
                System.out.println("monitor-worker run " + schedule.id());
                try {
                    MonitorRunner.Result result = new MonitorRunner(store).run(schedule.id());
                    System.out.println("monitor-worker result " + schedule.id() + " "
                            + (result.run().success() ? "success" : result.run().errorCode()));
                } catch (MonitorException e) {
                    System.err.println("monitor-worker error " + schedule.id() + " " + safe(e));
                }
            }
        }
    }

    private int activeSchedules() {
        try { return (int) MonitorStore.openDefault().schedules().stream().filter(MonitorSchedule::enabled).count(); }
        catch (RuntimeException e) { return 0; }
    }

    private void writeHeartbeat(String state, int active) {
        try {
            ObjectNode node = JSON.createObjectNode();
            node.put("pid", ProcessHandle.current().pid());
            node.put("startedAt", startedAt == null ? Instant.now(clock).toString() : startedAt);
            node.put("lastHeartbeatAt", Instant.now(clock).toString());
            node.put("version", "1");
            node.put("state", state);
            node.put("activeSchedules", active);
            Path temp = Files.createTempFile(dir, "monitor-worker.", ".tmp");
            Files.writeString(temp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            ownerOnly(temp);
            try { Files.move(temp, heartbeatPath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, heartbeatPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) { System.err.println("monitor-worker heartbeat error"); }
    }

    private static Instant parse(String value) {
        try { return value == null ? null : Instant.parse(value); }
        catch (RuntimeException e) { return null; }
    }

    private static String safe(Exception e) {
        String value = e.getMessage();
        return value == null || value.isBlank() ? "ошибка" : value.replaceAll("\\s+", " ");
    }

    private static void ownerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions
                    .fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    static String formatStatus() {
        Path file = Path.of(System.getProperty("user.home"), ".ai-advent-agent", "monitor-worker.json");
        if (!Files.exists(file)) return "Monitor worker: stopped (heartbeat отсутствует).";
        try {
            var node = JSON.readTree(Files.readString(file));
            String heartbeat = node.path("lastHeartbeatAt").asText(null);
            Instant at = parse(heartbeat);
            boolean stale = at == null || at.plusSeconds(60).isBefore(Instant.now());
            return "Monitor worker: " + (stale ? "stale" : node.path("state").asText("unknown"))
                    + " · pid=" + node.path("pid").asLong(-1)
                    + " · startedAt=" + node.path("startedAt").asText("нет")
                    + " · lastHeartbeatAt=" + (heartbeat == null ? "нет" : heartbeat)
                    + " · activeSchedules=" + node.path("activeSchedules").asInt(0);
        } catch (Exception e) {
            return "Monitor worker: heartbeat повреждён.";
        }
    }
}
