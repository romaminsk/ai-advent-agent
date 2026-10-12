package com.example.assistant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Только чтение Git-репозитория: git вызывается через ProcessBuilder без shell. */
public final class GitReadTools {

    static final int TIMEOUT_SECONDS = 5;
    static final int DIFF_LIMIT_BYTES = 8 * 1024;
    static final int LIST_LIMIT = 200;
    static final String MASKED = "[скрыто]";
    private static final Pattern SECRET_LINE = Pattern.compile("(?i).*(KEY|TOKEN|SECRET|PASSWORD).*");

    private final Path root;

    public GitReadTools(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** Корень репозитория или понятная ошибка, если каталог не под Git. */
    public static Path repositoryRoot(Path start) throws IOException, InterruptedException {
        Result result = git(start.toAbsolutePath().normalize(), List.of("rev-parse", "--show-toplevel"));
        if (result.exit() != 0) {
            throw new IOException("Каталог не является Git-репозиторием: " + start);
        }
        return Path.of(result.output().strip());
    }

    public String branch() throws IOException, InterruptedException {
        Result named = git(root, List.of("branch", "--show-current"));
        String name = named.output().strip();
        if (named.exit() == 0 && !name.isEmpty()) {
            return "ветка: " + name;
        }
        Result hash = git(root, List.of("rev-parse", "--short", "HEAD"));
        if (hash.exit() != 0) {
            throw new IOException("Не удалось определить ветку: в репозитории нет коммитов.");
        }
        return "detached HEAD: " + hash.output().strip();
    }

    public String status() throws IOException, InterruptedException {
        Result result = git(root, List.of("status", "--short"));
        requireOk(result, "status");
        String text = result.output().lines()
                .filter(line -> line.length() > 3 && !isSecretPath(line.substring(3).strip()))
                .collect(Collectors.joining("\n"));
        return text.isBlank() ? "Изменений нет: рабочее дерево чистое." : text;
    }

    public String listFiles(String prefix) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>(List.of("ls-files"));
        String relative = relativePrefix(prefix);
        if (!relative.isEmpty()) {
            args.add("--");
            args.add(relative);
        }
        Result result = git(root, args);
        requireOk(result, "ls-files");
        List<String> files = result.output().lines()
                .filter(line -> !line.isBlank() && !isSecretPath(line))
                .toList();
        String body = files.stream().limit(LIST_LIMIT).collect(Collectors.joining("\n"));
        if (files.size() > LIST_LIMIT) {
            body += "\n… показаны первые " + LIST_LIMIT + " из " + files.size() + " файлов";
        }
        return body.isBlank() ? "Файлы не найдены." : body;
    }

    public String diff(boolean staged) throws IOException, InterruptedException {
        List<String> pathspec = List.of("--", ".", ":(exclude,glob).env*", ":(exclude,glob)**/.env*");
        List<String> stat = new ArrayList<>(List.of("diff", "--stat"));
        List<String> full = new ArrayList<>(List.of("diff"));
        if (staged) {
            stat.add("--cached");
            full.add("--cached");
        }
        stat.addAll(pathspec);
        full.addAll(pathspec);
        Result statResult = git(root, stat);
        requireOk(statResult, "diff --stat");
        Result diffResult = git(root, full);
        requireOk(diffResult, "diff");
        String text = mask(statResult.output() + diffResult.output());
        return truncate(text.strip());
    }

    /** Строки с KEY/TOKEN/SECRET/PASSWORD (без учёта регистра) заменяются маркером. */
    public static String mask(String text) {
        return text.lines()
                .map(line -> SECRET_LINE.matcher(line).matches() ? MASKED : line)
                .collect(Collectors.joining("\n"));
    }

    public static String truncate(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= DIFF_LIMIT_BYTES) {
            return text.isBlank() ? "Изменений нет." : text;
        }
        String head = new String(bytes, 0, DIFF_LIMIT_BYTES, StandardCharsets.UTF_8);
        return head + "\n… [вывод обрезан до 8 КБ]";
    }

    /** Префикс нормализуется и не может выйти за корень репозитория. */
    String relativePrefix(String prefix) throws IOException {
        String value = prefix == null ? "" : prefix.strip();
        if (value.isEmpty()) {
            return "";
        }
        Path resolved = root.resolve(value).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Путь выходит за пределы репозитория: " + prefix);
        }
        String relative = root.relativize(resolved).toString().replace('\\', '/');
        if (isSecretPath(relative)) {
            throw new IOException("Файлы .env не показываются.");
        }
        return relative;
    }

    public static boolean isSecretPath(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        return name.startsWith(".env") || normalized.contains("/.env");
    }

    private static void requireOk(Result result, String command) throws IOException {
        if (result.exit() != 0) {
            throw new IOException("git " + command + " завершился с кодом " + result.exit() + ".");
        }
    }

    record Result(int exit, String output) {
    }

    static Result git(Path directory, List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(args);
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(false)
                .start();
        process.getOutputStream().close();
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try {
                return process.getInputStream().readAllBytes();
            } catch (IOException e) {
                return new byte[0];
            }
        });
        CompletableFuture<byte[]> errors = CompletableFuture.supplyAsync(() -> {
            try {
                return process.getErrorStream().readAllBytes();
            } catch (IOException e) {
                return new byte[0];
            }
        });
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("git не ответил за " + TIMEOUT_SECONDS + " с.");
        }
        byte[] bytes = output.join();
        errors.join();
        return new Result(process.exitValue(), new String(bytes, StandardCharsets.UTF_8));
    }
}
