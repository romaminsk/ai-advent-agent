package com.example;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Шаг 3 цепочки: сохраняет проверенную сводку в Markdown в каталог результатов.
 * Атомарная запись как в MonitorStore: temp-файл в том же каталоге → force →
 * ATOMIC_MOVE; существующий файл не перезаписывается и не повреждается.
 */
public final class ResultFileWriter {

    static final String TOOL_NAME = "saveToFile";
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
    private static final DateTimeFormatter DEFAULT_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    /** Результат записи: путь, размер, SHA-256 сохранённых байтов и привязки к шагам. */
    public record SaveResult(String path, int bytes, String fileSha256, String sourceSha256,
                             String summarySha256) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("path", path);
            map.put("bytes", bytes);
            map.put("fileSha256", fileSha256);
            map.put("sourceSha256", sourceSha256);
            map.put("summarySha256", summarySha256);
            return map;
        }
    }

    private final Path resultsDir;

    /** Каталог результатов (создаётся при первой записи). */
    public ResultFileWriter(Path resultsDir) {
        this.resultsDir = resultsDir;
    }

    /**
     * Записывает Markdown. summary — карта ровно как вернул summarize;
     * несовпадение summarySha256 или существующий файл → ошибка инструмента,
     * файл не создаётся. Сбои на границе с файловой системой — тоже ошибка инструмента.
     */
    public SaveResult save(Map<String, Object> summary, String fileName)
            throws PipelineToolException {
        if (summary == null) {
            throw new PipelineToolException("summary обязателен, файл не создаётся.");
        }
        Map<String, Object> input = new java.util.LinkedHashMap<>(summary);
        String claimed = SearchSummarizer.string(input.remove("summarySha256"));
        if (claimed == null || !claimed.equals(PipelineCanonicalJson.sha256Hex(
                PipelineCanonicalJson.canonical(input)))) {
            throw new PipelineToolException("input integrity check failed, файл не создаётся.");
        }
        if (fileName != null && fileName.isBlank()) {
            throw new PipelineToolException("fileName не должен быть пустым;"
                    + " не указывайте поле, чтобы использовать имя по умолчанию.");
        }
        String name = fileName == null ? null : fileName.trim();
        if (name != null && !validName(name)) {
            throw new PipelineToolException("fileName должен быть 1..100 символов из [A-Za-z0-9._-],"
                    + " без «..», и обязательно с расширением .md — файл не создаётся.");
        }
        String query = SearchSummarizer.string(input.get("query"));
        String root = SearchSummarizer.string(input.get("root"));
        Integer totalMatches = SearchSummarizer.integer(input.get("totalMatches"));
        Integer shownMatches = SearchSummarizer.integer(input.get("shownMatches"));
        Integer filesWithMatches = SearchSummarizer.integer(input.get("filesWithMatches"));
        Boolean truncated = SearchSummarizer.bool(input.get("truncated"));
        String summaryText = SearchSummarizer.string(input.get("summaryText"));
        String sourceSha256 = SearchSummarizer.string(input.get("sourceSha256"));
        if (query == null || root == null || totalMatches == null || shownMatches == null
                || filesWithMatches == null || truncated == null || summaryText == null
                || sourceSha256 == null) {
            throw new PipelineToolException("summary не содержит ожидаемых полей summarize,"
                    + " файл не создаётся.");
        }
        List<Map<String, Object>> topFiles = stringMapsList(input.get("topFiles"));
        List<Map<String, Object>> sampleLines = stringMapsList(input.get("sampleLines"));
        if (topFiles == null || sampleLines == null) {
            throw new PipelineToolException("topFiles и sampleLines должны быть массивами объектов,"
                    + " файл не создаётся.");
        }
        Path target = resultsDir.resolve(name == null ? defaultName(claimed) : name);
        String content = markdown(query, root, totalMatches, shownMatches, filesWithMatches,
                truncated, topFiles, sampleLines, summaryText, sourceSha256, claimed);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            if (!Files.isDirectory(resultsDir)) {
                Files.createDirectories(resultsDir);
                ownerPermissions(resultsDir, true);
            }
            if (Files.exists(target)) {
                throw new PipelineToolException("file already exists: " + target + " — файл не перезаписывается.");
            }
            Path temp = Files.createTempFile(resultsDir, target.getFileName() + ".", ".tmp");
            try {
                try (FileChannel channel = FileChannel.open(temp,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                ownerPermissions(temp, false);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    throw new PipelineToolException(
                            "Файловая система не поддерживает атомарную запись результатов.");
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (PipelineToolException e) {
            throw e;
        } catch (FileAlreadyExistsException e) {
            throw new PipelineToolException("file already exists: " + target + " — файл не перезаписывается.");
        } catch (IOException e) {
            throw new PipelineToolException("Не удалось записать файл результата: " + target + ".");
        }
        return new SaveResult(target.toString(), bytes.length,
                PipelineCanonicalJson.sha256Hex(bytes), sourceSha256, claimed);
    }

    /** Имя по умолчанию: pipeline-yyyyMMdd-HHmmss-<первые 8 hex summarySha256>.md. */
    public static String defaultName(String summarySha256) {
        return "pipeline-" + DEFAULT_STAMP.format(LocalDateTime.now())
                + "-" + summarySha256.substring(0, 8) + ".md";
    }

    /** Имя допустимо: 1..100 символов [A-Za-z0-9._-], без «..», обязательно .md. */
    static boolean validName(String name) {
        return name.length() <= 100 && name.length() >= ".md".length()
                && !name.contains("..")
                && FILE_NAME_PATTERN.matcher(name).matches()
                && name.endsWith(".md");
    }

    private static List<Map<String, Object>> stringMapsList(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> entry)) return null;
            out.add(new java.util.LinkedHashMap<>((Map<String, Object>) entry));
        }
        return out;
    }

    private static void ownerPermissions(Path path, boolean directory) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                    directory ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems use their normal permission model.
        }
    }

    private static String markdown(String query, String root, int totalMatches, int shownMatches,
                                   int filesWithMatches, boolean truncated,
                                   List<Map<String, Object>> topFiles,
                                   List<Map<String, Object>> sampleLines, String summaryText,
                                   String sourceSha256, String summarySha256) {
        StringBuilder out = new StringBuilder("# Результат pipeline search → summarize\n\n");
        out.append("- Запрос: ").append(query).append('\n');
        out.append("- Корень: ").append(root).append('\n');
        out.append("- Совпадений всего: ").append(totalMatches).append('\n');
        out.append("- Совпадений в сводке: ").append(shownMatches).append('\n');
        out.append("- Файлов с совпадениями: ").append(filesWithMatches).append('\n');
        out.append("- Обрезано лимитом: ").append(truncated).append('\n');
        out.append("\n## Файлы с наибольшим числом совпадений\n");
        if (topFiles.isEmpty()) {
            out.append("(нет совпадений)\n");
        } else {
            for (Map<String, Object> item : topFiles) {
                out.append("- ").append(item.get("file")).append(" — ")
                        .append(item.get("matches")).append('\n');
            }
        }
        out.append("\n## Примеры строк\n");
        if (sampleLines.isEmpty()) {
            out.append("(нет)\n");
        } else {
            for (Map<String, Object> item : sampleLines) {
                out.append("- ").append(item.get("file")).append(':').append(item.get("line"))
                        .append(" — ").append(item.get("text")).append('\n');
            }
        }
        out.append("\n## Сводка\n").append(summaryText).append('\n');
        out.append("\n- sourceSha256: ").append(sourceSha256).append('\n');
        out.append("- summarySha256: ").append(summarySha256).append('\n');
        return out.toString();
    }
}
