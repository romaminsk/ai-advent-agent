package com.example;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Единые правила песочницы файловых инструментов: корень проекта, запреты путей, размеры, секреты. */
final class FilesSandbox {
    static final long MAX_READ_BYTES = 200L * 1024;
    static final long MAX_WRITE_BYTES = 100L * 1024;
    static final String MASK = "<скрыто>";
    static final List<String> WRITE_EXTENSIONS = List.of(".md", ".txt", ".java", ".json", ".yml",
            ".yaml", ".xml", ".properties");
    private static final Set<String> DENIED_SEGMENTS = Set.of(".git", ".idea", "artifacts", "target", ".github");
    private static final Set<String> DENIED_WRITE_NAMES = Set.of("pom.xml", ".gitignore");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)((?:api[_-]?key|token|secret|password)\\s*[=:]\\s*)\\S+");
    private static final Pattern BEARER = Pattern.compile("Bearer\\s+\\S+");
    private static final Pattern SK_KEY = Pattern.compile("sk-[A-Za-z0-9]{16,}");

    enum Access { READ, WRITE }

    private final Path root;

    FilesSandbox(Path projectRoot) {
        Path absolute = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IllegalArgumentException("корень проекта не является каталогом");
        }
        try {
            this.root = absolute.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("корень проекта недоступен");
        }
    }

    Path root() { return root; }

    /** Маскирует значения, похожие на секреты; ключ в key=value сохраняется. */
    static String mask(String text) {
        if (text == null) return "";
        String masked = KEY_VALUE.matcher(text).replaceAll("$1" + Matcher.quoteReplacement(MASK));
        masked = BEARER.matcher(masked).replaceAll("Bearer " + Matcher.quoteReplacement(MASK));
        return SK_KEY.matcher(masked).replaceAll(Matcher.quoteReplacement(MASK));
    }

    static boolean looksSecret(String text) {
        if (text == null || text.isEmpty()) return false;
        return KEY_VALUE.matcher(text).find() || BEARER.matcher(text).find() || SK_KEY.matcher(text).find();
    }

    /** Относительный путь с разделителем «/»; путь вне корня недопустим. */
    String relative(Path absolute) {
        return root.relativize(absolute).toString().replace('\\', '/');
    }

    /** Имена и сегменты, которые не читаются, не ищутся и не пишутся. */
    static boolean deniedRelative(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            if (name.startsWith(".") || DENIED_SEGMENTS.contains(name)) return true;
        }
        return false;
    }

    /** Нормализует путь и проверяет корень, запреты сегментов и симлинки (для существующих частей). */
    Path resolve(String rawPath, Access access) {
        String value = rawPath == null ? "" : rawPath.strip();
        if (value.isEmpty()) value = ".";
        if (value.indexOf('\0') >= 0) throw new FilesToolException("недопустимый путь");
        Path candidate = Path.of(value).isAbsolute() ? Path.of(value) : root.resolve(value);
        Path normalized = candidate.normalize();
        if (!normalized.startsWith(root)) {
            throw new FilesToolException("путь вне корня проекта: " + value);
        }
        Path relative = root.relativize(normalized);
        if (deniedRelative(relative)) {
            throw new FilesToolException("путь запрещён правилами песочницы: " + relative(normalized));
        }
        Path existing = normalized;
        while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing != null) {
            Path real;
            try {
                real = existing.toRealPath();
            } catch (IOException e) {
                throw new FilesToolException("путь недоступен: " + value);
            }
            if (!real.startsWith(root)) {
                throw new FilesToolException("симлинк ведёт за пределы проекта: " + value);
            }
            Path realRelative = root.relativize(real);
            if (deniedRelative(realRelative)) {
                throw new FilesToolException("путь запрещён правилами песочницы: " + value);
            }
        }
        if (access == Access.WRITE) {
            checkWritable(normalized);
        }
        return normalized;
    }

    private void checkWritable(Path normalized) {
        String name = normalized.getFileName() == null ? "" : normalized.getFileName().toString();
        if (DENIED_WRITE_NAMES.contains(name) || name.endsWith(".sh")) {
            throw new FilesToolException("запись в " + name + " запрещена");
        }
        if (WRITE_EXTENSIONS.stream().noneMatch(name::endsWith)) {
            throw new FilesToolException("запись разрешена только для текстовых расширений: "
                    + String.join(" ", WRITE_EXTENSIONS));
        }
    }

    /** Существующий обычный файл для чтения: размер, отсутствие бинарных данных, UTF-8. */
    Path readableFile(String rawPath) {
        Path path = resolve(rawPath, Access.READ);
        if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path)) throw new FilesToolException("симлинк не читается: " + rawPath);
            throw new FilesToolException("файл не найден: " + rawPath);
        }
        return path;
    }

    static void checkReadableSize(long size) {
        if (size > MAX_READ_BYTES) {
            throw new FilesToolException("файл больше 200 КБ: используй поиск или диапазон строк");
        }
    }

    /** Текст UTF-8 без нулевых байтов; null — бинарный или не UTF-8 файл. */
    static String decodeText(byte[] bytes) {
        int sniff = Math.min(bytes.length, 8 * 1024);
        for (int i = 0; i < sniff; i++) {
            if (bytes[i] == 0) return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
