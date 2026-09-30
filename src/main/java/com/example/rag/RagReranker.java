package com.example.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic score filter, lexical reranker, and per-file diversity limit. */
public final class RagReranker {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+(?:[._-][\\p{L}\\p{N}]+)*");
    private static final Pattern CAMEL = Pattern.compile("[a-zа-яё0-9]+|[A-ZА-ЯЁ]?[a-zа-яё0-9]+|[A-ZА-ЯЁ]+(?=[A-ZА-ЯЁ][a-zа-яё]|$)");
    private static final Set<String> STOP_WORDS = Set.of("и", "в", "во", "на", "по", "для", "как",
            "что", "это", "из", "к", "с", "со", "а", "но", "или", "при", "где", "когда",
            "какой", "какие", "какая", "какое", "ли", "не", "the", "a", "an", "and", "or",
            "to", "of", "in", "on", "for", "with", "is", "are", "how", "what", "when", "which");

    public record ScoredChunk(RagRetriever.Chunk chunk, double vectorScore, double lexicalScore,
                              double finalScore, boolean kept, String reason) {
    }

    public record Result(List<ScoredChunk> candidates, List<ScoredChunk> selected,
                         int filteredCount, boolean filteredAll) {
        public Result {
            candidates = List.copyOf(candidates);
            selected = List.copyOf(selected);
        }
    }

    public Result process(String question, List<RagRetriever.Chunk> chunks, RagSettings settings) {
        List<ScoredChunk> scored = new ArrayList<>();
        boolean filterEnabled = settings.minScore() > 0 || settings.rerankEnabled();
        for (RagRetriever.Chunk chunk : chunks) {
            double vector = chunk.score();
            if (filterEnabled && vector < settings.minScore()) {
                scored.add(new ScoredChunk(chunk, vector, 0, vector, false,
                        "score ниже порога " + format(settings.minScore())));
                continue;
            }
            double lexical = lexicalScore(question, chunk);
            double score = settings.rerankEnabled() ? 0.7 * vector + 0.3 * lexical : vector;
            scored.add(new ScoredChunk(chunk, vector, lexical, score, true, "проходит порог"));
        }

        List<ScoredChunk> passing = scored.stream().filter(ScoredChunk::kept)
                .sorted(Comparator.comparingDouble(ScoredChunk::finalScore).reversed())
                .toList();
        List<ScoredChunk> selected = new ArrayList<>();
        Map<String, Integer> perFile = new LinkedHashMap<>();
        long sourceCount = passing.stream().map(item -> item.chunk().source()).distinct().count();
        for (ScoredChunk candidate : passing) {
            int count = perFile.getOrDefault(candidate.chunk().source(), 0);
            if (settings.rerankEnabled() && sourceCount > 1 && count >= 2) continue;
            selected.add(candidate);
            perFile.put(candidate.chunk().source(), count + 1);
            if (selected.size() == settings.topKAfter()) break;
        }
        Set<String> selectedIds = new HashSet<>();
        selected.forEach(item -> selectedIds.add(item.chunk().chunkId()));
        List<ScoredChunk> diagnostics = scored.stream().map(item -> item.kept()
                && !selectedIds.contains(item.chunk().chunkId())
                ? new ScoredChunk(item.chunk(), item.vectorScore(), item.lexicalScore(),
                item.finalScore(), false, "не вошёл в итоговый top-" + settings.topKAfter())
                : item).toList();
        int filtered = (int) diagnostics.stream().filter(item -> item.reason()
                .startsWith("score ниже порога")).count();
        return new Result(diagnostics, selected, filtered, passing.isEmpty());
    }

    public static double lexicalScore(String question, RagRetriever.Chunk chunk) {
        Map<String, Integer> terms = weightedTerms(question);
        if (terms.isEmpty()) return 0;
        Map<String, Integer> text = weightedTerms(chunk.text());
        Map<String, Integer> metadata = weightedTerms(chunk.source() + " " + chunk.section());
        int total = 0;
        int found = 0;
        boolean metadataMatch = false;
        for (Map.Entry<String, Integer> term : terms.entrySet()) {
            total += term.getValue();
            if (text.containsKey(term.getKey())) found += term.getValue();
            if (metadata.containsKey(term.getKey())) metadataMatch = true;
        }
        double score = total == 0 ? 0 : (double) found / total;
        if (metadataMatch) score += 0.05;
        return Math.min(1.0, score);
    }

    private static Map<String, Integer> weightedTerms(String value) {
        Map<String, Integer> terms = new LinkedHashMap<>();
        Matcher matcher = TOKEN.matcher(value == null ? "" : value);
        while (matcher.find()) {
            String token = matcher.group();
            boolean identifier = token.matches(".*[._-].*")
                    || (token.matches(".*[a-z][A-Z].*") && token.length() > 1);
            int weight = identifier ? 2 : 1;
            addToken(terms, token, weight);
            Matcher parts = CAMEL.matcher(token);
            while (parts.find()) addToken(terms, parts.group(), weight);
        }
        return terms;
    }

    private static void addToken(Map<String, Integer> terms, String token, int weight) {
        String normalized = normalize(token);
        if (normalized.length() < 2 || STOP_WORDS.contains(normalized)) return;
        terms.merge(normalized, weight, Math::max);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
