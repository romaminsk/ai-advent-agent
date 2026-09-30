package com.example.rag;

import com.example.index.Embedder;
import com.example.index.IndexSearch;
import com.example.index.IndexService;
import com.example.index.IndexStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Objects;

/** Embeds a question and retrieves the highest-scoring chunks from one index. */
public final class RagRetriever {
    public record Chunk(String source, String section, String chunkId, double score, String text) {
    }

    private final IndexStore store;
    private final Embedder embedder;
    private final String strategy;
    private final int topK;
    private IndexStore.Index cachedIndex;
    private FileTime cachedIndexTime;

    public RagRetriever(IndexStore store, Embedder embedder) {
        this(store, embedder, RagConstants.INDEX_STRATEGY, RagConstants.DEFAULT_TOP_K);
    }

    public RagRetriever(IndexStore store, Embedder embedder, int topK) {
        this(store, embedder, RagConstants.INDEX_STRATEGY, topK);
    }

    public RagRetriever(IndexStore store, Embedder embedder, String strategy) {
        this(store, embedder, strategy, RagConstants.DEFAULT_TOP_K);
    }

    public RagRetriever(IndexStore store, Embedder embedder, String strategy, int topK) {
        this.store = Objects.requireNonNull(store, "store");
        this.embedder = Objects.requireNonNull(embedder, "embedder");
        if (!IndexService.STRATEGY_FIXED.equals(strategy)
                && !IndexService.STRATEGY_STRUCTURE.equals(strategy)) {
            throw new IllegalArgumentException("Неизвестная стратегия индекса: " + strategy);
        }
        this.strategy = strategy;
        if (topK < 1) {
            throw new IllegalArgumentException("topK должен быть положительным");
        }
        this.topK = topK;
    }

    public List<Chunk> retrieve(String question) throws IOException, InterruptedException {
        return retrieve(question, topK);
    }

    public List<Chunk> retrieve(String question, int requestedTopK)
            throws IOException, InterruptedException {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Вопрос не должен быть пустым");
        }
        if (requestedTopK < 1) {
            throw new IllegalArgumentException("topK должен быть положительным");
        }
        IndexStore.Index index = loadIndex();
        if (index == null || index.chunks().isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = embedder.embed(List.of(question));
        if (vectors.isEmpty()) {
            return List.of();
        }
        return IndexSearch.topK(index, vectors.get(0), requestedTopK).stream()
                .map(hit -> new Chunk(hit.chunk().meta().source(),
                        hit.chunk().meta().section(), hit.chunk().meta().chunkId(),
                        hit.score(), hit.chunk().text()))
                .toList();
    }

    private synchronized IndexStore.Index loadIndex() throws IOException {
        if (!store.exists(strategy)) {
            cachedIndex = null;
            cachedIndexTime = null;
            return null;
        }
        FileTime modified = Files.getLastModifiedTime(store.file(strategy));
        if (cachedIndex == null || !modified.equals(cachedIndexTime)) {
            cachedIndex = store.load(strategy);
            cachedIndexTime = modified;
        }
        return cachedIndex;
    }
}
