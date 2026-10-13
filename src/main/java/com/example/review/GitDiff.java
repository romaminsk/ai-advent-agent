package com.example.review;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Чтение diff base...head напрямую через git (без shell) и разбор по файлам. */
public final class GitDiff {

    static final long TIMEOUT_SECONDS = 120;
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

    /**
     * Патч одного файла. annotated — hunk'и с номерами строк нового файла слева:
     * «   12 + код» для добавленных, «   12   код» для контекста, «      - код» для удалённых.
     * addedText — только содержимое добавленных строк (для запросов к RAG).
     */
    public record FilePatch(String path, int added, int removed, boolean binary,
                            String annotated, String addedText) {
    }

    private GitDiff() {
    }

    public static List<FilePatch> patches(Path repository, String base, String head)
            throws IOException, InterruptedException {
        requireRef(base, "--base");
        requireRef(head, "--head");
        String text = git(repository, List.of("diff", "--no-color", "--no-ext-diff", "--no-renames",
                base + "..." + head));
        return parse(text);
    }

    static void requireRef(String ref, String option) {
        if (ref == null || ref.isBlank() || ref.startsWith("-")) {
            throw new IllegalArgumentException("Ошибка: " + option + " должен быть git-ссылкой, получено: " + ref);
        }
    }

    /** Разбор полного unified diff на файлы. */
    public static List<FilePatch> parse(String patch) {
        List<FilePatch> result = new ArrayList<>();
        if (patch == null || patch.isBlank()) {
            return result;
        }
        Section section = null;
        for (String line : patch.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                if (section != null) {
                    result.add(section.finish());
                }
                section = new Section(pathOf(line));
                continue;
            }
            if (section == null) {
                continue;
            }
            section.accept(line);
        }
        if (section != null) {
            result.add(section.finish());
        }
        return result;
    }

    private static String pathOf(String header) {
        int index = header.lastIndexOf(" b/");
        return index < 0 ? header : header.substring(index + 3);
    }

    /** Накопитель одного файла при разборе. */
    private static final class Section {
        private final String path;
        private final StringBuilder annotated = new StringBuilder();
        private final StringBuilder added = new StringBuilder();
        private int addedCount;
        private int removedCount;
        private boolean binary;
        private boolean inHunk;
        private int newLine;

        Section(String path) {
            this.path = path;
        }

        void accept(String line) {
            if (line.startsWith("Binary files ") || line.startsWith("GIT binary patch")) {
                binary = true;
                return;
            }
            Matcher hunk = HUNK_HEADER.matcher(line);
            if (line.startsWith("@@") && hunk.matches()) {
                inHunk = true;
                newLine = Integer.parseInt(hunk.group(1));
                annotated.append(line).append('\n');
                return;
            }
            if (!inHunk || line.startsWith("\\") || line.isEmpty()) {
                return;
            }
            char marker = line.charAt(0);
            String content = line.substring(1);
            switch (marker) {
                case '+' -> {
                    annotated.append(String.format("%6d + %s", newLine, content)).append('\n');
                    added.append(content).append('\n');
                    addedCount++;
                    newLine++;
                }
                case '-' -> {
                    annotated.append(String.format("%6s - %s", "", content)).append('\n');
                    removedCount++;
                }
                case ' ' -> {
                    annotated.append(String.format("%6d   %s", newLine, content)).append('\n');
                    newLine++;
                }
                default -> {
                }
            }
        }

        FilePatch finish() {
            return new FilePatch(path, addedCount, removedCount, binary,
                    annotated.toString(), added.toString());
        }
    }

    static String git(Path directory, List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("core.quotePath=false");
        command.addAll(args);
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .start();
        process.getOutputStream().close();
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
        CompletableFuture<byte[]> errors = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("git не ответил за " + TIMEOUT_SECONDS + " с.");
        }
        String stdout = new String(output.join(), StandardCharsets.UTF_8);
        String stderr = new String(errors.join(), StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0) {
            String firstLine = stderr.isEmpty() ? "" : ": " + stderr.lines().findFirst().orElse("");
            throw new IOException("git " + args.get(0) + " завершился с кодом "
                    + process.exitValue() + firstLine);
        }
        return stdout;
    }

    private static byte[] read(InputStream stream) {
        try {
            return stream.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
