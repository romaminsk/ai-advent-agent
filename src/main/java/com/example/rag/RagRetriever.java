package com.example.rag;

import com.example.index.Embedder;
import com.example.index.IndexSearch;
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
    private final int topK;
    private IndexStore.Index cachedIndex;
    private FileTime cachedIndexTime;

    public RagRetriever(IndexStore store, Embedder embedder) {
        this(store, embedder, RagConstants.DEFAULT_TOP_K);
    }

    public RagRetriever(IndexStore store, Embedder embedder, int topK) {
        this.store = Objects.requireNonNull(store, "store");
        this.embedder = Objects.requireNonNull(embedder, "embedder");
        if (topK < 1) {
            throw new IllegalArgumentException("topK должен быть положительным");
        }
        this.topK = topK;
    }

    public List<Chunk> retrieve(String question) throws IOException, InterruptedException {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Вопрос не должен быть пустым");
        }
        IndexStore.Index index = loadIndex();
        if (index == null || index.chunks().isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = embedder.embed(List.of(question));
        if (vectors.isEmpty()) {
            return List.of();
        }
        return IndexSearch.topK(index, vectors.get(0), topK).stream()
                .map(hit -> new Chunk(hit.chunk().meta().source(),
                        hit.chunk().meta().section(), hit.chunk().meta().chunkId(),
                        hit.score(), hit.chunk().text()))
                .toList();
    }

    private synchronized IndexStore.Index loadIndex() throws IOException {
        if (!store.exists(RagConstants.INDEX_STRATEGY)) {
            cachedIndex = null;
            cachedIndexTime = null;
            return null;
        }
        FileTime modified = Files.getLastModifiedTime(store.file(RagConstants.INDEX_STRATEGY));
        if (cachedIndex == null || !modified.equals(cachedIndexTime)) {
            cachedIndex = store.load(RagConstants.INDEX_STRATEGY);
            cachedIndexTime = modified;
        }
        return cachedIndex;
    }
}
