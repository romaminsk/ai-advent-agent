package com.example.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Оркестрация построения индексов: корпус → чанки → эмбеддинги
 * (кэш по sha256 текста) → JSON-индекс. Стратегии: fixed и structure.
 */
public final class IndexService {

    public static final String STRATEGY_FIXED = "fixed";
    public static final String STRATEGY_STRUCTURE = "structure";

    private final IndexStore store;

    public IndexService(IndexStore store) {
        this.store = store;
    }

    /** Отчёт о сборке одной стратегии. */
    public record BuildReport(String strategy, int files, int chunks,
                              long buildMs, int apiRequests, int embeddedFresh) {
    }

    /** Собирает индекс одной стратегии. */
    public BuildReport build(String strategy, String corpusRootPath,
                             DocumentLoader loader, Embedder embedder, String model,
                             Consumer<String> progress) throws IOException, InterruptedException {
        long started = System.currentTimeMillis();
        List<DocumentLoader.Document> documents = loader.load(java.nio.file.Path.of(corpusRootPath));
        if (progress != null) {
            progress.accept("[" + strategy + "] документов: " + documents.size());
        }
        // Чанки всех документов с метаданными.
        List<ChunkMeta.Chunk> allChunks = new ArrayList<>();
        for (DocumentLoader.Document document : documents) {
            String title = titleOf(document);
            List<StructureChunker.SectionPiece> pieces;
            if (STRATEGY_FIXED.equals(strategy)) {
                pieces = StructureChunker.unwrap(FixedChunker.chunk(document.content()), title);
            } else {
                pieces = StructureChunker.chunk(document.content(), document.type());
            }
            ChunkMeta.Builder builder = new ChunkMeta.Builder(strategy);
            for (StructureChunker.SectionPiece piece : pieces) {
                String section = piece.section() == null || piece.section().isBlank()
                        ? title : piece.section();
                builder.add(document.relativePath(), title, section, piece.piece());
            }
            allChunks.addAll(builder.build());
        }
        // Кэш: неизменённые тексты берутся без запроса к API.
        EmbeddingCache cache = new EmbeddingCache(cacheFile());
        Map<Integer, float[]> fresh = new HashMap<>();
        List<Integer> pending = new ArrayList<>();
        int cachedChunks = 0;
        for (int i = 0; i < allChunks.size(); i++) {
            float[] cached = cache.get(sha256(allChunks.get(i).text()));
            if (cached != null) {
                fresh.put(i, cached);
                cachedChunks++;
            } else {
                pending.add(i);
            }
        }
        int apiRequests = 0;
        int embeddedFresh = 0;
        int sent = 0;
        int batchSize = Math.max(1, embedder.batchSize());
        // Лимит батча — 16 входов; дополнительно ограничиваем суммарный
        // объём батча ~6000 символов: у локального контекста модели
        // (bge-m3, 4096 токенов) большlerio батчи вызывают контекстный сдвиг,
        // что в разы медленнее. Это сжатие числа запросов не нарушает.
        while (sent < pending.size()) {
            int until = sent;
            int chars = 0;
            while (until < pending.size() && until - sent < batchSize
                    && chars + allChunks.get(pending.get(until)).text().length() <= 6000) {
                chars += allChunks.get(pending.get(until)).text().length();
                until++;
            }
            if (until == sent) {
                until = sent + 1;
            }
            List<String> batch = new ArrayList<>();
            for (int i = sent; i < until; i++) {
                batch.add(allChunks.get(pending.get(i)).text());
            }
            List<float[]> vectors = embedder.embed(batch);
            apiRequests++;
            for (int v = 0; v < vectors.size(); v++) {
                int chunkIndex = pending.get(sent + v);
                fresh.put(chunkIndex, vectors.get(v));
                cache.put(sha256(allChunks.get(chunkIndex).text()), vectors.get(v));
                embeddedFresh++;
            }
            sent = until;
            if (progress != null && apiRequests % 20 == 0) {
                progress.accept("[" + strategy + "] запросов к API: " + apiRequests
                        + ", чанков готово: " + fresh.size() + "/" + allChunks.size());
            }
            try {
                if (apiRequests % 50 == 0) {
                    cache.save();
                }
            } catch (IOException saveError) {
                throw new IOException("[" + strategy + "] кэш: " + saveError.getMessage(), saveError);
            }
        }
        cache.save();
        List<IndexStore.IndexedChunk> indexed = new ArrayList<>();
        for (int i = 0; i < allChunks.size(); i++) {
            ChunkMeta.Chunk chunk = allChunks.get(i);
            float[] vector = fresh.get(i);
            if (vector == null) {
                throw new IOException("[" + strategy + "] вектор не получен: " + i);
            }
            indexed.add(new IndexStore.IndexedChunk(chunk.meta(), chunk.text(), vector));
        }
        int dim = indexed.isEmpty() ? 0 : indexed.get(0).vector().length;
        long buildMs = System.currentTimeMillis() - started;
        IndexStore.Index previous = store.load(strategy);
        int coldRequests = previous == null ? 0 : previous.coldRequests();
        if (cachedChunks == 0 && apiRequests > 0) {
            coldRequests = apiRequests;
        } else if (coldRequests == 0 && previous != null) {
            coldRequests = previous.apiRequests();
        }
        store.save(new IndexStore.Index(strategy, model, dim, Instant.now(),
                corpusRootPath, buildMs, apiRequests, coldRequests, indexed));
        return new BuildReport(strategy, documents.size(), indexed.size(), buildMs,
                apiRequests, embeddedFresh);
    }

    /** Файл кэша эмбеддингов рядом с индексами. */
    public java.nio.file.Path cacheFile() {
        return store.directory().resolve("embed-cache.json");
    }

    /** Заголовок документа: первое md-направление или имя файла. */
    static String titleOf(DocumentLoader.Document document) {
        if (document.type() == DocumentLoader.DocType.MD) {
            String heading = firstMdHeading(document.content());
            if (heading != null && !heading.isBlank()) {
                return heading;
            }
        }
        String name = document.relativePath();
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private static String firstMdHeading(String content) {
        String[] head = content.split("\n", 80);
        for (String line : head) {
            if (line.startsWith("#")) {
                int i = 0;
                while (i < line.length() && line.charAt(i) == '#') {
                    i++;
                }
                while (i < line.length() && line.charAt(i) == ' ') {
                    i++;
                }
                String value = line.substring(i).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    /** sha256 хекс текста — ключ кэша эмбеддингов. */
    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return ChunkMeta.sha256Hex(digest.digest(
                    text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
