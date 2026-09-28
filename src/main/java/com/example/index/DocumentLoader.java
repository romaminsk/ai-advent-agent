package com.example.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Обход корпуса документов: README*, *.md, *.txt и исходники java в src.
 * Исключаются: .env*, .git, target, artifacts, .idea, скрытые файлы и
 * каталоги, бинарники по расширению и файлы больше 1 МБ.
 * Тип документа определяет структурную стратегию chunking.
 */
public final class DocumentLoader {

    /** Ограничение размера одного файла: больше не читаем. */
    public static final long MAX_FILE_BYTES = 1024 * 1024;

    private static final List<String> SKIP_DIR_NAMES = List.of(
            ".git", "target", "artifacts", ".idea", "node_modules", "build", "out");

    /** Считанный документ. */
    public record Document(Path path, String relativePath, String content, DocType type) {
    }

    /** Тип документа — определяет стратегию структурного chunking. */
    public enum DocType { MD, JAVA, TXT }

    /** Сбор документов относительно corpusRoot (по умолчанию корень проекта). */
    public List<Document> load(Path corpusRoot) throws IOException {
        List<Document> documents = new ArrayList<>();
        Path root = corpusRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("Корпус не найден: " + root);
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                if (name.startsWith(".") || SKIP_DIR_NAMES.contains(name.toLowerCase())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName() == null ? "" : file.getFileName().toString();
                DocType type = docType(name);
                if (type == null || attrs.size() > MAX_FILE_BYTES) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = root.relativize(file).toString();
                if (isExcluded(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                boolean inSrc = file.startsWith(root.resolve("src"));
                boolean keep = name.toLowerCase().startsWith("readme")
                        || name.toLowerCase().endsWith(".md")
                        || (type == DocType.JAVA && inSrc);
                if (!keep) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    if (!looksBinary(content)) {
                        documents.add(new Document(file, relative, content, type));
                    }
                } catch (IOException ignored) {
                    // Нечитаемый файл пропускаем — индекс собирается по остальным.
                }
                return FileVisitResult.CONTINUE;
            }
        });
        documents.sort(java.util.Comparator.comparing(Document::relativePath));
        return documents;
    }

    /** .env* и прочие запрещённые относительные пути не попадают в корпус. */
    static boolean isExcluded(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        String lower = name.toLowerCase();
        String lowerPath = relativePath.toLowerCase();
        return lower.startsWith(".env") || lowerPath.contains("/.env");
    }

    /** Тип по расширению: md, java, txt; null — нетекстовый файл. */
    static DocType docType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.startsWith(".")) {
            return null;
        }
        if (lower.endsWith(".java")) {
            return DocType.JAVA;
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return DocType.MD;
        }
        if (lower.endsWith(".txt")) {
            return DocType.TXT;
        }
        if (lower.startsWith("readme")) {
            return DocType.MD;
        }
        return null;
    }

    /** Простая эвристика бинарности: нулевые символы — не текст. */
    static boolean looksBinary(String content) {
        int check = Math.min(content.length(), 4096);
        for (int i = 0; i < check; i++) {
            if (content.charAt(i) == 0) {
                return true;
            }
        }
        return false;
    }
}
