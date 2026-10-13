package com.example.review;

import com.example.rag.RagRetriever;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Запросы к RAG по каждому анализируемому файлу: имя класса, методы и идентификаторы из diff. */
public final class ReviewRetrieval {

    public static final int PER_FILE_CHUNKS = 4;
    public static final int TOTAL_CHUNKS = 12;
    public static final int CHUNK_CHARS = 1200;
    /** Фрагмент короче этого не добавляется: остаток лимита слишком мал, чтобы он что-то дал. */
    static final int MIN_CHUNK_CHARS = 200;

    private static final int MAX_METHODS = 8;
    private static final int MAX_IDENTIFIERS = 8;
    private static final Set<String> KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "return", "new", "synchronized",
            "super", "this", "throw", "assert", "else");
    private static final Pattern CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern TYPE_NAME = Pattern.compile("\\b[A-Z][A-Za-z0-9]{3,}\\b");

    /** Результат поиска: фрагменты в порядке нумерации [n] и причина, если RAG не сработал. */
    public record Retrieval(List<RagRetriever.Chunk> chunks, String error) {
    }

    private ReviewRetrieval() {
    }

    /**
     * Фрагменты по каждому файлу: до 4 на файл, до 12 всего, каждый до 1200 символов;
     * суммарно не больше limitChars (остаток окна модели).
     */
    public static Retrieval retrieve(List<ReviewRunner.Entry> files, ReviewRunner.Search search, int limitChars) {
        if (search == null) {
            return new Retrieval(List.of(), "RAG не используется");
        }
        List<RagRetriever.Chunk> chunks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int used = 0;
        for (ReviewRunner.Entry file : files) {
            if (chunks.size() >= TOTAL_CHUNKS) {
                break;
            }
            List<RagRetriever.Chunk> found;
            try {
                found = search.search(query(file.path(), file.text()), PER_FILE_CHUNKS);
            } catch (Exception e) {
                return new Retrieval(chunks, "RAG недоступен: " + ReviewRunner.describe(e));
            }
            int taken = 0;
            for (RagRetriever.Chunk chunk : found) {
                if (taken >= PER_FILE_CHUNKS || chunks.size() >= TOTAL_CHUNKS) {
                    break;
                }
                if (!seen.add(chunk.source() + "#" + chunk.chunkId())) {
                    continue;
                }
                RagRetriever.Chunk trimmed = truncated(chunk, Math.min(CHUNK_CHARS, limitChars - used));
                if (trimmed.text().length() < MIN_CHUNK_CHARS) {
                    return new Retrieval(chunks, null);
                }
                chunks.add(trimmed);
                used += trimmed.text().length();
                taken++;
            }
        }
        return new Retrieval(chunks, null);
    }

    /** Запрос: имя файла и класса, имена вызываемых методов и CamelCase-идентификаторы из добавленных строк. */
    static String query(String path, String annotated) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        Set<String> terms = new LinkedHashSet<>();
        terms.add(stem);
        terms.add(path);
        Matcher call = CALL.matcher(annotated);
        int methods = 0;
        while (call.find() && methods < MAX_METHODS) {
            String method = call.group(1);
            if (!KEYWORDS.contains(method) && terms.add(method)) {
                methods++;
            }
        }
        Matcher type = TYPE_NAME.matcher(annotated);
        int identifiers = 0;
        while (type.find() && identifiers < MAX_IDENTIFIERS) {
            if (terms.add(type.group())) {
                identifiers++;
            }
        }
        return String.join(" ", terms);
    }

    private static RagRetriever.Chunk truncated(RagRetriever.Chunk chunk, int maxChars) {
        String text = chunk.text().length() > maxChars
                ? chunk.text().substring(0, Math.max(0, maxChars)) : chunk.text();
        return new RagRetriever.Chunk(chunk.source(), chunk.section(), chunk.chunkId(), chunk.score(), text);
    }
}
