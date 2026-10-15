package com.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Файловые инструменты files_*: выполняются только внутри песочницы. */
final class FilesTools {
    static final int DEFAULT_LIST_RESULTS = 200;
    static final int MAX_LIST_RESULTS = 1000;
    static final int DEFAULT_READ_CHARS = 12_000;
    static final int MAX_READ_CHARS = 60_000;
    static final int DEFAULT_SEARCH_RESULTS = 50;
    static final int MAX_SEARCH_RESULTS = 500;
    static final int MAX_QUERY_CHARS = 200;
    static final int MAX_LINE_CHARS = 200;

    /** Снимок файла для цикла правок: текущее содержимое и sha256 исходных байтов. */
    record Snapshot(String path, boolean exists, String content, String sha256) {}

    private final FilesSandbox sandbox;

    FilesTools(FilesSandbox sandbox) {
        this.sandbox = sandbox;
    }

    Map<String, Object> list(String path, String glob, Integer maxResults) {
        int limit = bounded(maxResults, DEFAULT_LIST_RESULTS, 1, MAX_LIST_RESULTS, "maxResults");
        Path start = sandbox.resolve(path, FilesSandbox.Access.READ);
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
            throw new FilesToolException("каталог не найден: " + (path == null ? "." : path));
        }
        PathMatcher matcher = matcher(glob);
        List<Map<String, Object>> files = new ArrayList<>();
        int total = 0;
        for (Path file : walkFiles(start)) {
            Path relative = sandbox.root().relativize(file);
            if (matcher != null && !matches(matcher, glob, relative)) continue;
            total++;
            if (files.size() < limit) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", sandbox.relative(file));
                entry.put("size", sizeOf(file));
                files.add(entry);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", files);
        result.put("totalFiles", total);
        result.put("truncated", total > files.size());
        return result;
    }

    Map<String, Object> read(String path, Integer fromLine, Integer toLine, Integer maxChars) {
        int limitChars = bounded(maxChars, DEFAULT_READ_CHARS, 1, MAX_READ_CHARS, "maxChars");
        Path file = sandbox.readableFile(path);
        long size = sizeOf(file);
        FilesSandbox.checkReadableSize(size);
        String text = readText(file);
        List<String> lines = splitLines(text);
        int total = lines.size();
        int from = fromLine == null ? 1 : fromLine;
        if (from < 1) throw new FilesToolException("fromLine должен быть не меньше 1");
        if (total > 0 && from > total) {
            throw new FilesToolException("fromLine " + from + " больше числа строк " + total);
        }
        int to = toLine == null ? total : Math.min(toLine, total);
        if (toLine != null && toLine < from) throw new FilesToolException("toLine меньше fromLine");
        StringBuilder out = new StringBuilder();
        boolean truncated = false;
        int lastIncluded = from - 1;
        for (int line = from; line <= to; line++) {
            String current = lines.get(line - 1);
            int needed = out.length() + current.length() + (out.length() == 0 ? 0 : 1);
            if (needed > limitChars) {
                truncated = true;
                if (out.length() == 0) {
                    out.append(current, 0, Math.min(current.length(), limitChars));
                    lastIncluded = line;
                }
                break;
            }
            if (out.length() > 0) out.append('\n');
            out.append(current);
            lastIncluded = line;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", sandbox.relative(file));
        result.put("totalLines", total);
        result.put("fromLine", from);
        result.put("toLine", lastIncluded);
        result.put("truncated", truncated);
        result.put("sha256", FilesSandbox.sha256(readBytes(file)));
        result.put("text", FilesSandbox.mask(out.toString()));
        return result;
    }

    Map<String, Object> search(String query, Boolean regex, Boolean caseSensitive, String glob,
                               Integer maxResults) {
        if (query == null || query.isEmpty()) throw new FilesToolException("query обязателен");
        if (query.length() > MAX_QUERY_CHARS) throw new FilesToolException("query длиннее 200 символов");
        int limit = bounded(maxResults, DEFAULT_SEARCH_RESULTS, 1, MAX_SEARCH_RESULTS, "maxResults");
        int flags = Boolean.TRUE.equals(caseSensitive) ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        Pattern pattern;
        try {
            pattern = Pattern.compile(Boolean.TRUE.equals(regex) ? query : Pattern.quote(query), flags);
        } catch (PatternSyntaxException e) {
            throw new FilesToolException("неверное регулярное выражение: " + e.getDescription());
        }
        PathMatcher matcher = matcher(glob);
        List<Map<String, Object>> matches = new ArrayList<>();
        Map<String, Integer> perFile = new LinkedHashMap<>();
        int total = 0;
        int scanned = 0;
        int skipped = 0;
        for (Path file : walkFiles(sandbox.root())) {
            Path relative = sandbox.root().relativize(file);
            if (matcher != null && !matches(matcher, glob, relative)) continue;
            if (sizeOf(file) > FilesSandbox.MAX_READ_BYTES) {
                skipped++;
                continue;
            }
            String text = FilesSandbox.decodeText(readBytes(file));
            if (text == null) {
                skipped++;
                continue;
            }
            scanned++;
            List<String> lines = splitLines(text);
            for (int i = 0; i < lines.size(); i++) {
                if (!pattern.matcher(lines.get(i)).find()) continue;
                total++;
                perFile.merge(sandbox.relative(file), 1, Integer::sum);
                if (matches.size() < limit) {
                    Map<String, Object> match = new LinkedHashMap<>();
                    match.put("path", sandbox.relative(file));
                    match.put("line", i + 1);
                    match.put("text", shorten(FilesSandbox.mask(lines.get(i))));
                    matches.add(match);
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matches", matches);
        result.put("perFile", perFile);
        result.put("totalMatches", total);
        result.put("truncated", total > matches.size());
        result.put("filesScanned", scanned);
        result.put("filesSkipped", skipped);
        return result;
    }

    Map<String, Object> diff(String path, String newContent) {
        if (newContent == null) throw new FilesToolException("newContent обязателен");
        Path target = sandbox.resolve(path, FilesSandbox.Access.WRITE);
        boolean exists = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
        String oldText = exists ? readText(readableTarget(target, path)) : "";
        String normalized = normalizeEndings(newContent, exists ? oldText : null);
        LineDiff.Result diff = LineDiff.diff(sandbox.relative(target), oldText, normalized, !exists);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", sandbox.relative(target));
        result.put("exists", exists);
        result.put("changed", diff.changed());
        result.put("diff", FilesSandbox.mask(diff.diff()));
        result.put("added", diff.added());
        result.put("removed", diff.removed());
        return result;
    }

    Map<String, Object> write(String path, String content, String expectedSha256, Boolean createOnly) {
        if (content == null) throw new FilesToolException("content обязателен");
        Path target = sandbox.resolve(path, FilesSandbox.Access.WRITE);
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (exists && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FilesToolException("цель записи не является обычным файлом");
        }
        if (Boolean.TRUE.equals(createOnly) && exists) {
            throw new FilesToolException("createOnly: файл уже существует");
        }
        byte[] current = exists ? readBytes(target) : null;
        if (expectedSha256 != null && !expectedSha256.isBlank()) {
            if (!exists) throw new FilesToolException("expectedSha256 задан, но файла нет");
            if (!FilesSandbox.sha256(current).equalsIgnoreCase(expectedSha256.strip())) {
                throw new FilesToolException("файл изменился: sha256 не совпадает с expectedSha256");
            }
        }
        if (FilesSandbox.looksSecret(content)) {
            throw new FilesToolException("содержимое похоже на секрет: запись отклонена");
        }
        String oldText = exists ? readText(target) : null;
        String text = normalizeEndings(content, oldText);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > FilesSandbox.MAX_WRITE_BYTES) {
            throw new FilesToolException("содержимое больше 100 КБ");
        }
        Set<PosixFilePermission> permissions = exists ? permissionsOf(target) : null;
        atomicWrite(target, bytes, permissions);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", sandbox.relative(target));
        result.put("created", !exists);
        result.put("sha256", FilesSandbox.sha256(bytes));
        result.put("bytes", bytes.length);
        return result;
    }

    /** Текущее содержимое для цикла правок; внутри процесса, модели не возвращается напрямую. */
    Snapshot snapshot(String path) {
        Path target = sandbox.resolve(path, FilesSandbox.Access.WRITE);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return new Snapshot(sandbox.relative(target), false, "", null);
        }
        Path file = readableTarget(target, path);
        byte[] bytes = readBytes(file);
        return new Snapshot(sandbox.relative(target), true, readText(file), FilesSandbox.sha256(bytes));
    }

    /** Нормализация окончаний строк и финального перевода строки как в исходном файле. */
    static String normalizeEndings(String content, String original) {
        boolean crlf = original != null && original.contains("\r\n");
        boolean finalNewline = original == null || original.endsWith("\n");
        String body = content.replace("\r\n", "\n");
        while (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
        if (finalNewline) body = body + "\n";
        return crlf ? body.replace("\n", "\r\n") : body;
    }

    private Path readableTarget(Path target, String rawPath) {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FilesToolException("файл не найден: " + rawPath);
        }
        FilesSandbox.checkReadableSize(sizeOf(target));
        return target;
    }

    private String readText(Path file) {
        String text = FilesSandbox.decodeText(readBytes(file));
        if (text == null) throw new FilesToolException("файл не является текстом UTF-8: " + sandbox.relative(file));
        return text;
    }

    private byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new FilesToolException("не удалось прочитать файл: " + sandbox.relative(file));
        }
    }

    private long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private Set<PosixFilePermission> permissionsOf(Path file) {
        try {
            return Files.getPosixFilePermissions(file);
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    private void atomicWrite(Path target, byte[] bytes, Set<PosixFilePermission> permissions) {
        Path tmp = target.getParent().resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.write(tmp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (permissions != null) Files.setPosixFilePermissions(tmp, permissions);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new FilesToolException("запись не выполнена: " + sandbox.relative(target));
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // временный файл удаляется по возможности; целевой файл уже в согласованном состоянии
            }
        }
    }

    /** Обычные файлы под start в порядке пути; симлинки и запрещённые сегменты пропускаются. */
    private List<Path> walkFiles(Path start) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(start, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (dir.equals(start)) return FileVisitResult.CONTINUE;
                            if (attrs.isSymbolicLink() || FilesSandbox.deniedRelative(
                                    sandbox.root().relativize(dir))) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (attrs.isRegularFile() && !attrs.isSymbolicLink()
                                    && !FilesSandbox.deniedRelative(sandbox.root().relativize(file))) {
                                files.add(file);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw new FilesToolException("не удалось обойти каталог");
        }
        files.sort((a, b) -> sandbox.relative(a).compareTo(sandbox.relative(b)));
        return files;
    }

    private static PathMatcher matcher(String glob) {
        if (glob == null || glob.isBlank()) return null;
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + glob.strip());
        } catch (IllegalArgumentException e) {
            throw new FilesToolException("неверный glob: " + glob);
        }
    }

    private static boolean matches(PathMatcher matcher, String glob, Path relative) {
        Path subject = glob.contains("/") ? relative : relative.getFileName();
        return matcher.matches(subject);
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text.isEmpty()) return lines;
        String[] parts = text.split("\n", -1);
        int count = text.endsWith("\n") ? parts.length - 1 : parts.length;
        for (int i = 0; i < count; i++) lines.add(parts[i]);
        return lines;
    }

    private static String shorten(String line) {
        return line.length() <= MAX_LINE_CHARS ? line : line.substring(0, MAX_LINE_CHARS);
    }

    private static int bounded(Integer value, int fallback, int min, int max, String name) {
        int result = value == null ? fallback : value;
        if (result < min || result > max) {
            throw new FilesToolException(name + " должен быть от " + min + " до " + max);
        }
        return result;
    }
}
