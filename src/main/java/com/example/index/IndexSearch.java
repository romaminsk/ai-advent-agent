package com.example.index;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Поиск по индексу: косинусное сходство, top-k. */
public final class IndexSearch {

    private IndexSearch() {
    }

    /** Результат поиска. */
    public record Hit(IndexStore.IndexedChunk chunk, double score) {
    }

    /** Топ-k по косинусу между запросом и векторами чанков. */
    public static List<Hit> topK(IndexStore.Index index, float[] query, int k) {
        List<Hit> hits = new ArrayList<>();
        if (query == null || query.length == 0) {
            return List.of();
        }
        for (IndexStore.IndexedChunk chunk : index.chunks()) {
            if (chunk.vector() == null) {
                continue;
            }
            double score = cosine(query, chunk.vector());
            hits.add(new Hit(chunk, score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.size() > k ? hits.subList(0, k) : hits;
    }

    /** Косинус двух векторов; нулевые векторы дают 0. */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += (double) a[i] * (double) a[i];
            normB += (double) b[i] * (double) b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        double similarity = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        return new BigDecimal(similarity).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    /** Метка времени для сравнения (не используется для сортировки). */
    public static Instant instant() {
        return Instant.now();
    }
}
