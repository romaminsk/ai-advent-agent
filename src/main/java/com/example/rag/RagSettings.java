package com.example.rag;

/** Immutable retrieval and query processing settings. */
public record RagSettings(int topKBefore, int topKAfter, double minScore,
                          boolean rerankEnabled, boolean rewriteEnabled,
                          double rerankVectorWeight, double rerankLexicalWeight,
                          Double relativeDelta, boolean diversityEnabled) {
    public static final double DEFAULT_MIN_SCORE = 0.55;
    public static final double DEFAULT_VECTOR_WEIGHT = 1.00;
    public static final double DEFAULT_LEXICAL_WEIGHT = 0.00;
    public static final boolean DEFAULT_DIVERSITY_ENABLED = false;
    public static final RagSettings DEFAULT = new RagSettings(20, 5, DEFAULT_MIN_SCORE,
            true, true, DEFAULT_VECTOR_WEIGHT, DEFAULT_LEXICAL_WEIGHT, null,
            DEFAULT_DIVERSITY_ENABLED);

    public RagSettings(int topKBefore, int topKAfter, double minScore,
                       boolean rerankEnabled, boolean rewriteEnabled) {
        this(topKBefore, topKAfter, minScore, rerankEnabled, rewriteEnabled,
                DEFAULT_VECTOR_WEIGHT, DEFAULT_LEXICAL_WEIGHT, null, DEFAULT_DIVERSITY_ENABLED);
    }

    public RagSettings(int topKBefore, int topKAfter, double minScore,
                       boolean rerankEnabled, boolean rewriteEnabled,
                       double rerankVectorWeight, double rerankLexicalWeight) {
        this(topKBefore, topKAfter, minScore, rerankEnabled, rewriteEnabled,
                rerankVectorWeight, rerankLexicalWeight, null, DEFAULT_DIVERSITY_ENABLED);
    }

    public RagSettings(int topKBefore, int topKAfter, double minScore,
                       boolean rerankEnabled, boolean rewriteEnabled,
                       double rerankVectorWeight, double rerankLexicalWeight,
                       Double relativeDelta) {
        this(topKBefore, topKAfter, minScore, rerankEnabled, rewriteEnabled,
                rerankVectorWeight, rerankLexicalWeight, relativeDelta, DEFAULT_DIVERSITY_ENABLED);
    }

    public RagSettings {
        if (topKBefore < 1 || topKAfter < 1 || topKAfter > topKBefore) {
            throw new IllegalArgumentException("Требуется 1 <= topKAfter <= topKBefore");
        }
        if (!Double.isFinite(minScore) || minScore < 0 || minScore > 1) {
            throw new IllegalArgumentException("minScore должен быть в диапазоне [0..1]");
        }
        if (!Double.isFinite(rerankVectorWeight) || !Double.isFinite(rerankLexicalWeight)
                || rerankVectorWeight < 0 || rerankLexicalWeight < 0
                || Math.abs(rerankVectorWeight + rerankLexicalWeight - 1.0) > 0.000001) {
            throw new IllegalArgumentException("Вес vector и lexical должен суммироваться в 1");
        }
        if (relativeDelta != null && (!Double.isFinite(relativeDelta)
                || relativeDelta < 0 || relativeDelta > 1)) {
            throw new IllegalArgumentException("relativeDelta должен быть в диапазоне [0..1]");
        }
    }

    public RagSettings withTopK(int before, int after) {
        return new RagSettings(before, after, minScore, rerankEnabled, rewriteEnabled,
                rerankVectorWeight, rerankLexicalWeight, relativeDelta, diversityEnabled);
    }

    public RagSettings withMinScore(double value) {
        return new RagSettings(topKBefore, topKAfter, value, rerankEnabled, rewriteEnabled,
                rerankVectorWeight, rerankLexicalWeight, null, diversityEnabled);
    }

    public RagSettings withRerank(boolean enabled) {
        return new RagSettings(topKBefore, topKAfter, minScore, enabled, rewriteEnabled,
                rerankVectorWeight, rerankLexicalWeight, relativeDelta, diversityEnabled);
    }

    public RagSettings withRewrite(boolean enabled) {
        return new RagSettings(topKBefore, topKAfter, minScore, rerankEnabled, enabled,
                rerankVectorWeight, rerankLexicalWeight, relativeDelta, diversityEnabled);
    }

    public RagSettings withWeights(double vectorWeight, double lexicalWeight) {
        return new RagSettings(topKBefore, topKAfter, minScore, rerankEnabled, rewriteEnabled,
                vectorWeight, lexicalWeight, relativeDelta, diversityEnabled);
    }

    public RagSettings withRelativeDelta(Double delta) {
        return new RagSettings(topKBefore, topKAfter, minScore, rerankEnabled, rewriteEnabled,
                rerankVectorWeight, rerankLexicalWeight, delta, diversityEnabled);
    }

    public RagSettings withDiversity(boolean enabled) {
        return new RagSettings(topKBefore, topKAfter, minScore, rerankEnabled, rewriteEnabled,
                rerankVectorWeight, rerankLexicalWeight, relativeDelta, enabled);
    }

    public RagReranker.Weights rerankWeights() {
        return new RagReranker.Weights(rerankVectorWeight, rerankLexicalWeight);
    }
}
