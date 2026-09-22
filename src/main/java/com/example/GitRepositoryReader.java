package com.example;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Читает только состояние Git через машинный porcelain-формат. */
public final class GitRepositoryReader {
    static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;

    private final Path allowedRoot;

    public record RepositoryLocation(Path requestedPath, Path repositoryRoot) {
    }

    public GitRepositoryReader(Path allowedRoot) throws GitRepositoryException {
        this.allowedRoot = canonicalDirectory(allowedRoot, "Разрешённый корень репозитория");
        ensureRepository(this.allowedRoot);
    }

    public GitRepositoryStatus read(Path repoPath) throws GitRepositoryException {
        Path requested = canonicalDirectory(repoPath, "Путь репозитория");
        if (!requested.startsWith(allowedRoot)) {
            throw new GitRepositoryException("Путь находится вне разрешённого репозитория.");
        }
        RepositoryLocation location = resolveLocation(requested);
        Path actualRoot = location.repositoryRoot();
        if (!allowedRoot.equals(actualRoot)) {
            throw new GitRepositoryException("Путь относится к другому или вложенному репозиторию.");
        }

        CommandResult status = run(List.of("git", "--no-optional-locks", "-C", allowedRoot.toString(),
                "status", "--porcelain=v1", "-z", "--untracked-files=all"));
        ParsedStatus parsed = parseStatus(status.stdout());
        String branch = symbolicBranch();
        CommandResult head = runAllowFailure(List.of("git", "-C", allowedRoot.toString(),
                "rev-parse", "--verify", "HEAD"));
        String headCommit = head.exitCode() == 0 ? text(head.stdout()) : null;
        boolean detached = branch == null;
        return new GitRepositoryStatus(allowedRoot.toString(), branch, detached, headCommit,
                parsed.staged().isEmpty() && parsed.unstaged().isEmpty()
                        && parsed.untracked().isEmpty() && parsed.conflicts().isEmpty(),
                parsed.staged(), parsed.unstaged(), parsed.untracked(), parsed.conflicts());
    }

    Path allowedRoot() {
        return allowedRoot;
    }

    /** Разрешает пользовательский путь и возвращает его корень Git без чтения status. */
    public static RepositoryLocation resolveLocation(Path repoPath) throws GitRepositoryException {
        Path requested = canonicalDirectory(repoPath, "Путь репозитория");
        validateGitDirectory(requested);
        Path root = repositoryRoot(requested);
        validateGitDirectory(root);
        return new RepositoryLocation(requested, root);
    }

    private void ensureRepository(Path path) throws GitRepositoryException {
        validateGitDirectory(path);
        if (!path.equals(repositoryRoot(path))) {
            throw new GitRepositoryException("Разрешённый путь не совпадает с корнем Git-репозитория.");
        }
    }

    private static void validateGitDirectory(Path path) throws GitRepositoryException {
        CommandResult bare = runAllowFailure(List.of("git", "-C", path.toString(),
                "rev-parse", "--is-bare-repository"));
        if (bare.exitCode() != 0) {
            throw new GitRepositoryException("Путь не является Git-репозиторием.");
        }
        if ("true".equals(text(bare.stdout()))) {
            throw new GitRepositoryException("Bare-репозитории не поддерживаются.");
        }
        if (!"true".equals(text(runAllowFailure(List.of("git", "-C", path.toString(),
                "rev-parse", "--is-inside-work-tree")).stdout()))) {
            throw new GitRepositoryException("Путь не является рабочим Git-репозиторием.");
        }
    }

    private static Path repositoryRoot(Path path) throws GitRepositoryException {
        CommandResult root = run(List.of("git", "-C", path.toString(), "rev-parse", "--show-toplevel"));
        try {
            return Path.of(text(root.stdout())).toRealPath();
        } catch (IOException | RuntimeException e) {
            throw new GitRepositoryException("Git вернул некорректный корень репозитория.");
        }
    }

    private String symbolicBranch() throws GitRepositoryException {
        CommandResult result = runAllowFailure(List.of("git", "-C", allowedRoot.toString(),
                "symbolic-ref", "--short", "-q", "HEAD"));
        return result.exitCode() == 0 && !text(result.stdout()).isBlank() ? text(result.stdout()) : null;
    }

    private static ParsedStatus parseStatus(byte[] bytes) throws GitRepositoryException {
        Set<String> staged = new TreeSet<>();
        Set<String> unstaged = new TreeSet<>();
        Set<String> untracked = new TreeSet<>();
        Set<String> conflicts = new TreeSet<>();
        int offset = 0;
        while (offset < bytes.length) {
            int end = indexOfZero(bytes, offset);
            if (end < 0 || end - offset < 4) {
                throw new GitRepositoryException("Git вернул некорректный формат status.");
            }
            char x = (char) bytes[offset];
            char y = (char) bytes[offset + 1];
            if (bytes[offset + 2] != ' ') {
                throw new GitRepositoryException("Git вернул некорректный формат status.");
            }
            String path = decode(bytes, offset + 3, end);
            offset = end + 1;
            if (x == 'R' || x == 'C') {
                int oldEnd = indexOfZero(bytes, offset);
                if (oldEnd < 0) {
                    throw new GitRepositoryException("Git вернул неполное переименование.");
                }
                // With -z porcelain, the first path is the new path; consume the old path.
                offset = oldEnd + 1;
            }
            boolean conflict = x == 'U' || y == 'U' || (x == 'A' && y == 'A')
                    || (x == 'D' && y == 'D');
            if (conflict) {
                conflicts.add(path);
            } else {
                if (x != ' ' && x != '?') staged.add(path);
                if (y != ' ' && y != '?') unstaged.add(path);
                if (x == '?' && y == '?') untracked.add(path);
            }
        }
        return new ParsedStatus(List.copyOf(staged), List.copyOf(unstaged),
                List.copyOf(untracked), List.copyOf(conflicts));
    }

    private static CommandResult run(List<String> command) throws GitRepositoryException {
        CommandResult result = runAllowFailure(command);
        if (result.exitCode() != 0) {
            throw new GitRepositoryException("Git не смог прочитать состояние репозитория.");
        }
        return result;
    }

    private static CommandResult runAllowFailure(List<String> command) throws GitRepositoryException {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new GitRepositoryException("Git не установлен или недоступен.");
        }
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<byte[]> stdout = executor.submit(() -> readLimited(process.getInputStream()));
        Future<byte[]> stderr = executor.submit(() -> readLimited(process.getErrorStream()));
        try {
            if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new GitRepositoryException("Истекло время ожидания Git.");
            }
            byte[] out = future(stdout);
            future(stderr); // stderr is deliberately never shown to the user.
            return new CommandResult(process.exitValue(), out);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new GitRepositoryException("Чтение Git прервано.");
        } catch (ExecutionException | TimeoutException e) {
            process.destroyForcibly();
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            if (cause instanceof OutputLimitException) {
                throw new GitRepositoryException("Ответ Git превысил допустимый размер.");
            }
            throw new GitRepositoryException("Не удалось прочитать ответ Git.");
        } finally {
            executor.shutdownNow();
            process.destroy();
        }
    }

    private static byte[] future(Future<byte[]> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(2, TimeUnit.SECONDS);
    }

    private static byte[] readLimited(InputStream input) throws IOException, OutputLimitException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > MAX_OUTPUT_BYTES) {
                throw new OutputLimitException();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static Path canonicalDirectory(Path path, String label) throws GitRepositoryException {
        if (path == null || !path.isAbsolute()) {
            throw new GitRepositoryException(label + " должен быть абсолютным путём.");
        }
        try {
            if (!Files.isDirectory(path)) {
                throw new GitRepositoryException(label + " не существует или не является каталогом.");
            }
            return path.toRealPath();
        } catch (IOException e) {
            throw new GitRepositoryException("Нет доступа к " + label.toLowerCase() + ".");
        }
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).strip();
    }

    private static String decode(byte[] bytes, int start, int end) {
        return new String(bytes, start, end - start, StandardCharsets.UTF_8);
    }

    private static int indexOfZero(byte[] bytes, int start) {
        for (int i = start; i < bytes.length; i++) {
            if (bytes[i] == 0) return i;
        }
        return -1;
    }

    private record CommandResult(int exitCode, byte[] stdout) {
    }

    private record ParsedStatus(List<String> staged, List<String> unstaged,
                                List<String> untracked, List<String> conflicts) {
    }

    private static final class OutputLimitException extends Exception {
    }

    public static final class GitRepositoryException extends Exception {
        public GitRepositoryException(String message) {
            super(message);
        }
    }
}
