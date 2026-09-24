package com.example;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Шаг 1 цепочки: литеральный регистронезависимый поиск по текстовым файлам
 * каталога. Детерминированный порядок, симлинки не следуются, за пределы
 * root не выходит; .git, target, node_modules, .ssh, .gnupg, .aws, .kube,
 * .docker, .env*, личные ключи (id_rsa, id_dsa, id_ecdsa, id_ed25519
 * с любым суффиксом), *.pem, *.key, *.p12, *.pfx, .netrc, .pgpass, большие
 * и бинарные файлы пропускаются (имена без учёта регистра). Результат
 * содержит SHA-256 канонического JSON полей (payloadSha256), по которому
 * следующий шаг проверяет целостность входа.
 */
public final class FileSearcher {

    static final String TOOL_NAME = "search";
    static final int DEFAULT_MAX_RESULTS = 50;
    static final int MIN_MAX_RESULTS = 1;
    static final int MAX_MAX_RESULTS = 200;
    static final int MAX_QUERY_LENGTH = 200;
    static final int MAX_MATCH_TEXT = 300;
    static final long MAX_FILE_BYTES = 1024L * 1024L;
    static final int BINARY_SNIFF_BYTES = 8 * 1024;
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", "target", "node_modules",
            ".ssh", ".gnupg", ".aws", ".kube", ".docker");
    /** Имена (в нижнем регистре), безусловно пропускаемые по соображениям секретов. */
    private static final Set<String> SKIPPED_FILE_NAMES = Set.of(".netrc", ".pgpass");
    /** Расширения (в нижнем регистре) файлов с ключами и сертификатами. */
    private static final List<String> SKIPPED_SUFFIXES =
            List.of(".pem", ".key", ".p12", ".pfx");
    private static final List<String> SKIPPED_NAME_PREFIXES =
            List.of("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519");

    /** Имя пропускается: личные ключи, сертификаты и известные secret-файлы (без регистра). */
    static boolean isSkippedFileName(String rawName) {
        String name = rawName.toLowerCase(Locale.ROOT);
        if (SKIPPED_FILE_NAMES.contains(name)) {
            return true;
        }
        for (String suffix : SKIPPED_SUFFIXES) {
            if (name.endsWith(suffix)) return true;
        }
        for (String prefix : SKIPPED_NAME_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Одно совпадение: файл с разделителями «/», номер строки с 1, обрезанный текст. */
    public record Match(String file, int line, String text) {
    }

    /**
     * Результат search. toMap() строит поля в фиксированном порядке,
     * payloadSha256 считается над каноническим JSON без самого поля.
     */
    public record SearchResult(String root, String query, int filesScanned, int filesSkipped,
                               int totalMatches, boolean truncated, List<Match> matches,
                               String payloadSha256) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = payloadMap(root, query, filesScanned, filesSkipped,
                    totalMatches, truncated, matches);
            map.put("payloadSha256", payloadSha256);
            return map;
        }

        /** Хеш канонического JSON результата без самого payloadSha256. */
        public String integritySha256() {
            return integrity(root, query, filesScanned, filesSkipped, totalMatches, truncated, matches);
        }
    }

    private FileSearcher() {
    }

    /** Поиск. Ошибки аргументов и доступа дают PipelineToolException (ошибка инструмента). */
    public static SearchResult search(Path rootInput, String queryInput, Integer maxResultsInput)
            throws PipelineToolException {
        String query = validateQuery(queryInput);
        int maxResults = validateMaxResults(maxResultsInput);
        Path root = validateRoot(rootInput);
        List<Path> files = new ArrayList<>();
        int[] skipped = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.normalize().startsWith(root)) return FileVisitResult.SKIP_SUBTREE;
                    String dirName = dir.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!dir.equals(root) && SKIPPED_DIRECTORIES.contains(dirName)) {
                        skipped[0]++;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink()) {
                        skipped[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                    if (!file.normalize().startsWith(root)) return FileVisitResult.CONTINUE;
                    String name = file.getFileName().toString();
                    if (name.startsWith(".env") || isSkippedFileName(name)) {
                        skipped[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                    if (attributes.size() > MAX_FILE_BYTES) {
                        skipped[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                    files.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new PipelineToolException("Не удалось обойти каталог " + root + ".");
        }
        // Детерминированный порядок: файлы по относительному пути.
        files.sort(Comparator.comparing(path -> root.relativize(path).toString()));

        List<Match> matches = new ArrayList<>();
        int totalMatches = 0;
        int filesScanned = 0;
        String lowered = query.toLowerCase(Locale.ROOT);
        for (Path file : files) {
            List<String> lines = readTextLines(file);
            if (lines == null) {
                skipped[0]++;
                continue;
            }
            filesScanned++;
            String relative = root.relativize(file).toString().replace('\\', '/');
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.toLowerCase(Locale.ROOT).contains(lowered)) {
                    totalMatches++;
                    if (matches.size() < maxResults) {
                        matches.add(new Match(relative, i + 1, truncate(line)));
                    }
                }
            }
        }
        boolean truncated = totalMatches > maxResults;
        return new SearchResult(root.toString(), query, filesScanned, skipped[0], totalMatches,
                truncated, List.copyOf(matches),
                PipelineCanonicalJson.sha256Hex(PipelineCanonicalJson.canonical(
                        payloadMap(root.toString(), query, filesScanned, skipped[0],
                                totalMatches, truncated, matches))));
    }

    /** Хеш канонического JSON полезной нагрузки без полей-хешей. */
    static String integrity(String root, String query, int filesScanned, int filesSkipped,
                            int totalMatches, boolean truncated, List<Match> matches) {
        return PipelineCanonicalJson.sha256Hex(PipelineCanonicalJson.canonical(
                payloadMap(root, query, filesScanned, filesSkipped, totalMatches, truncated, matches)));
    }

    /** Фиксированный порядок полей; поле-хеш добавляет сам вызывающий. */
    static Map<String, Object> payloadMap(String root, String query, int filesScanned,
                                          int filesSkipped, int totalMatches, boolean truncated,
                                          List<Match> matches) {
        List<Map<String, Object>> matchMaps = new ArrayList<>();
        for (Match match : matches) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("file", match.file());
            item.put("line", match.line());
            item.put("text", match.text());
            matchMaps.add(item);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("root", root);
        map.put("query", query);
        map.put("filesScanned", filesScanned);
        map.put("filesSkipped", filesSkipped);
        map.put("totalMatches", totalMatches);
        map.put("truncated", truncated);
        map.put("matches", matchMaps);
        return map;
    }

    static String validateQuery(String query) throws PipelineToolException {
        if (query == null) throw new PipelineToolException("query обязателен.");
        String trimmed = query.trim();
        if (trimmed.isEmpty()) throw new PipelineToolException("query не может быть пустым.");
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            throw new PipelineToolException("query не может быть длиннее "
                    + MAX_QUERY_LENGTH + " символов.");
        }
        return trimmed;
    }

    static int validateMaxResults(Integer maxResults) throws PipelineToolException {
        if (maxResults == null) return DEFAULT_MAX_RESULTS;
        if (maxResults < MIN_MAX_RESULTS || maxResults > MAX_MAX_RESULTS) {
            throw new PipelineToolException("maxResults должен быть в диапазоне "
                    + MIN_MAX_RESULTS + ".." + MAX_MAX_RESULTS + ".");
        }
        return maxResults;
    }

    static Path validateRoot(Path rootInput) throws PipelineToolException {
        if (rootInput == null) throw new PipelineToolException("root обязателен.");
        Path root = rootInput.normalize().toAbsolutePath();
        if (!Files.isDirectory(root)) {
            throw new PipelineToolException("root должен существовать и быть каталогом: " + root + ".");
        }
        try {
            return root.toRealPath();
        } catch (IOException e) {
            throw new PipelineToolException("Нет доступа к root: " + root + ".");
        }
    }

    /** Строгое чтение UTF-8; нулевой байт или ошибка декодирования → бинарный файл (null). */
    static List<String> readTextLines(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        int sniff = Math.min(bytes.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < sniff; i++) {
            if (bytes[i] == 0) return null;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            String content = decoder.decode(ByteBuffer.wrap(bytes)).toString();
            return List.of(content.split("\\R", -1));
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    static String truncate(String line) {
        return line.length() > MAX_MATCH_TEXT ? line.substring(0, MAX_MATCH_TEXT) : line;
    }
}
