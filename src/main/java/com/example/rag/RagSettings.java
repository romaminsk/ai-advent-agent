package com.example.rag;

/** Immutable retrieval and query processing settings. */
public record RagSettings(int topKBefore, int topKAfter, double minScore,
                          boolean rerankEnabled, boolean rewriteEnabled) {
    public static final double DEFAULT_MIN_SCORE = 0.35;
    public static final RagSettings DEFAULT = new RagSettings(20, 5, DEFAULT_MIN_SCORE,
            true, true);

    public RagSettings {
        if (topKBefore < 1 || topKAfter < 1 || topKAfter > topKBefore) {
            throw new IllegalArgumentException("Требуется 1 <= topKAfter <= topKBefore");
        }
        if (!Double.isFinite(minScore) || minScore < 0 || minScore > 1) {
            throw new IllegalArgumentException("minScore должен быть в диапазоне [0..1]");
        }
    }

    public RagSettings withTopK(int before, int after) {
        return new RagSettings(before, after, minScore, rerankEnabled, rewriteEnabled);
    }

    public RagSettings withMinScore(double value) {
        return new RagSettings(topKBefore, topKAfter, value, rerankEnabled, rewriteEnabled);
    }

    public RagSettings withRerank(boolean enabled) {
        return new RagSettings(topKBefore, topKAfter, minScore, enabled, rewriteEnabled);
    }

    public RagSettings withRewrite(boolean enabled) {
        return new RagSettings(topKBefore, topKAfter, minScore, rerankEnabled, enabled);
    }
}
