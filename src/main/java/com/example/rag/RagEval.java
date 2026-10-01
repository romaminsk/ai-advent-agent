package com.example.rag;

import com.example.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Fixed-question comparison, retrieval recall, and deterministic lexical metrics. */
public final class RagEval {
    private static final double RERANK_PRECISION_FLOOR = 0.43;
    private static final Pattern BRACKET_CITATION = Pattern.compile("\\[([^\\]]+)]");
    private static final Pattern NUMBERED_CITATION = Pattern.compile("(?i)(?:source\\s+)?(\\d+)");

    public record Question(String id, String question, List<String> expected,
                           String expectedSources, String note) {
        public Question {
            expected = List.copyOf(expected);
        }

        public boolean noAnswerExpected() {
            return expectedSources.isBlank();
        }
    }

    public record Row(Question question, RagService.Result off, RagService.Result on,
                      int factsOff, int factsOn, boolean sourceHit, boolean cited,
                      boolean offInvented, boolean onDeclined, String status, String verdict) {
        public boolean successful() {
            return off.status() == RagService.Status.OK && on.status() == RagService.Status.OK;
        }
    }

    public record Report(List<Row> rows, int factsOff, int factsOn,
                         int sourceHits, int sourceQuestions, int wins,
                         int successfulQuestions, int errorResults, int emptyResults,
                         String markdown) {
        public Report {
            rows = List.copyOf(rows);
        }

        public double recallAt5() {
            return sourceQuestions == 0 ? 0 : (double) sourceHits / sourceQuestions;
        }

        public int failedQuestions() {
            return rows.size() - successfulQuestions;
        }

        public List<String> summaryLines() {
            long retrievalMisses = rows.stream().filter(Row::successful)
                    .filter(row -> !row.question().noAnswerExpected() && !row.sourceHit()).count();
            long uncited = rows.stream().filter(Row::successful)
                    .filter(row -> !row.question().noAnswerExpected() && !row.cited()).count();
            return List.of(
                    "Успешно: " + successfulQuestions + "/" + rows.size()
                            + "; error/empty вопросов: " + failedQuestions()
                            + "; error results: " + errorResults
                            + "; empty results: " + emptyResults + ".",
                    "Факты на успешных вопросах: off " + factsOff + ", on " + factsOn
                            + "; RAG выиграл " + wins + "/" + successfulQuestions + ".",
                    String.format(Locale.ROOT, "Retrieval recall@5: %d/%d (%.0f%%).",
                            sourceHits, sourceQuestions, recallAt5() * 100),
                    "Промахи поиска: " + retrievalMisses + "; без ожидаемой ссылки: "
                            + uncited + ".");
        }
    }

    public record RetrievalRow(Question question, List<RagRetriever.Chunk> fixed,
                               List<RagRetriever.Chunk> structure,
                               int fixedRank, int structureRank) {
        public RetrievalRow {
            fixed = List.copyOf(fixed);
            structure = List.copyOf(structure);
        }
    }

    public record RetrievalReport(List<RetrievalRow> rows, int fixedHits,
                                  int structureHits, int expectedQuestions) {
        public RetrievalReport {
            rows = List.copyOf(rows);
        }

        public double fixedRecallAt5() {
            return expectedQuestions == 0 ? 0 : (double) fixedHits / expectedQuestions;
        }

        public double structureRecallAt5() {
            return expectedQuestions == 0 ? 0 : (double) structureHits / expectedQuestions;
        }

        public String format() {
            StringBuilder out = new StringBuilder("| # | вопрос | ожидаемый source | fixed top-5 | hit | rank | structure top-5 | hit | rank |\n")
                    .append("|---:|---|---|---|:---:|---:|---|:---:|---:|\n");
            for (int i = 0; i < rows.size(); i++) {
                RetrievalRow row = rows.get(i);
                out.append("| ").append(i + 1).append(" | ")
                        .append(table(row.question().question())).append(" | ")
                        .append(table(row.question().expectedSources())).append(" | ")
                        .append(table(formatSources(row.fixed()))).append(" | ")
                        .append(row.fixedRank() > 0 ? "да" : "нет").append(" | ")
                        .append(rank(row.fixedRank())).append(" | ")
                        .append(table(formatSources(row.structure()))).append(" | ")
                        .append(row.structureRank() > 0 ? "да" : "нет").append(" | ")
                        .append(rank(row.structureRank())).append(" |\n");
            }
            out.append(String.format(Locale.ROOT,
                    "\nRecall@5 (из %d вопросов): structure %d/%d (%.0f%%), fixed %d/%d (%.0f%%).\n",
                    expectedQuestions, structureHits, expectedQuestions,
                    structureRecallAt5() * 100, fixedHits, expectedQuestions,
                    fixedRecallAt5() * 100));
            out.append("Вопрос без ответа в базе исключён из recall@5.\n");
            return out.toString();
        }
    }

    public record ModeCell(RagService.Result result, int facts, boolean sourceHit,
                           boolean cited, double chunkPrecision, int filteredCandidates,
                           boolean refusal, String status) {
    }

    public record ComparisonRow(Question question, ModeCell noRag, ModeCell baseline,
                                ModeCell filter, ModeCell full) {
    }

    public record ModeSummary(String mode, int facts, int sourceHits, int answerable,
                              double precision, int cited, int filtered, int refusals,
                              double averageMs, int errors, int timeouts, int empty,
                              int missingPairs) {
        public double recallAt5() {
            return answerable == 0 ? 0 : (double) sourceHits / answerable;
        }
    }

    public record ComparisonReport(List<ComparisonRow> rows, List<ModeSummary> summaries,
                                  String markdown, Map<String, Integer> missingPairs,
                                  Path checkpointPath) {
        public ComparisonReport(List<ComparisonRow> rows, List<ModeSummary> summaries,
                                String markdown) {
            this(rows, summaries, markdown, Map.of(), null);
        }

        public ComparisonReport {
            rows = List.copyOf(rows);
            summaries = List.copyOf(summaries);
            missingPairs = Map.copyOf(missingPairs);
        }

        public List<String> summaryLines() {
            return summaries.stream().map(summary -> String.format(Locale.ROOT,
                    "%s: факты %d, recall@5 %d/%d (%.0f%%), precision %.2f, cited %d, "
                            + "filtered %d, refusals %d, среднее %.1f с, error/timeout/empty %d/%d/%d, missing %d",
                    summary.mode(), summary.facts(), summary.sourceHits(), summary.answerable(),
                    summary.recallAt5() * 100, summary.precision(), summary.cited(),
                    summary.filtered(), summary.refusals(), summary.averageMs() / 1000.0,
                    summary.errors(), summary.timeouts(), summary.empty(), summary.missingPairs())).toList();
        }
    }

    private record Calibration(String name, List<Question> questions,
                               Map<String, List<RagRetriever.Chunk>> chunks) {
    }

    private record ThresholdMetrics(double threshold, int neededKept, int neededTotal,
                                    int junkDropped, int noContext, boolean trapFiltered) {
    }

    private record RelativeMetrics(double floor, double delta, int neededKept, int neededTotal,
                                   int junkDropped, int noContext, boolean trapFiltered) {
    }

    public record ThresholdScanReport(String markdown, double selectedThreshold,
                                      String selectedStrategy, boolean calibrated,
                                      Double relativeDelta) {
    }

    public record RerankAnalysisReport(String markdown, RagReranker.Weights selectedWeights,
                                       boolean selectedDiversity,
                                       double baselinePrecision, double selectedPrecision,
                                       int baselineSourceHits, int selectedSourceHits,
                                       int answerableQuestions, boolean constraintsMet) {
    }

    public record IdkScoreRow(String id, double top1, double top5, boolean trap) { }
    public record IdkScoreReport(List<IdkScoreRow> rows, double selectedThreshold,
                                boolean calibrated, int answerableLost, String markdown) {
        public IdkScoreReport { rows = List.copyOf(rows); }
    }

    private record AnalysisQuestion(String id, String question, String expectedSources) {
    }

    private record WeightMetric(RagReranker.Weights weights, int sourceHits,
                                int noWorseQuestions, double precision, boolean valid,
                                boolean diversity) {
    }

    private RagEval() {
    }

    public static List<Question> loadQuestions() throws IOException {
        try (InputStream input = RagEval.class.getResourceAsStream(RagConstants.QUESTIONS_RESOURCE)) {
            if (input == null) {
                throw new IOException("Ресурс вопросов не найден: " + RagConstants.QUESTIONS_RESOURCE);
            }
            JsonNode root = JsonSupport.MAPPER.readTree(input);
            if (root == null || !root.isArray()) {
                throw new IOException("questions.json должен содержать массив");
            }
            List<Question> questions = new ArrayList<>();
            for (JsonNode node : root) {
                List<String> expected = new ArrayList<>();
                JsonNode expectedNode = node.path("expected");
                if (expectedNode.isArray()) {
                    expectedNode.forEach(item -> expected.add(item.asText()));
                }
                questions.add(new Question(node.path("id").asText(),
                        node.path("question").asText(), expected,
                        node.path("expectedSources").asText(), node.path("note").asText()));
            }
            validateQuestions(questions);
            return List.copyOf(questions);
        }
    }

    public static void validateQuestions(List<Question> questions) throws IOException {
        if (questions.size() != 10) {
            throw new IOException("Ожидалось ровно 10 контрольных вопросов, получено: "
                    + questions.size());
        }
        for (Question question : questions) {
            if (question.id().isBlank() || question.question().isBlank()
                    || question.expected().isEmpty()
                    || question.expected().stream().anyMatch(String::isBlank)) {
                throw new IOException("У каждого вопроса должны быть непустые id, question и expected");
            }
        }
    }

    /** Measures raw top-1/top-5 cosine scores without calling either the chat model or rewrite. */
    public static IdkScoreReport idkScoreScan(List<Question> questions, RagRetriever retriever,
                                              RagSettings settings) throws Exception {
        List<IdkScoreRow> rows = new ArrayList<>();
        RagSettings noRewrite = settings.withRewrite(false);
        for (Question question : questions) {
            List<RagRetriever.Chunk> chunks = retriever.retrieve(question.question(), noRewrite.topKBefore());
            double top1 = chunks.isEmpty() ? 0 : chunks.get(0).score();
            double top5 = chunks.isEmpty() ? 0 : chunks.get(Math.min(4, chunks.size() - 1)).score();
            rows.add(new IdkScoreRow(question.id(), top1, top5, question.noAnswerExpected()));
        }
        List<Double> answerable = rows.stream().filter(row -> !row.trap())
                .map(IdkScoreRow::top1).toList();
        List<Double> traps = rows.stream().filter(IdkScoreRow::trap)
                .map(IdkScoreRow::top1).toList();
        double trapMax = traps.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double neededMin = answerable.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        boolean calibrated = !traps.isEmpty() && !answerable.isEmpty() && trapMax < neededMin;
        double selected;
        int lost;
        if (calibrated) {
            selected = (trapMax + neededMin) / 2.0;
            lost = 0;
        } else {
            double bestLoss = Double.POSITIVE_INFINITY;
            boolean bestTrap = false;
            selected = 0;
            for (int step = 0; step <= 1000; step++) {
                double candidate = step / 1000.0;
                int candidateLoss = (int) answerable.stream().filter(score -> score < candidate).count();
                boolean catchesTrap = traps.stream().allMatch(score -> score < candidate);
                if (candidateLoss < bestLoss || (candidateLoss == bestLoss && catchesTrap && !bestTrap)) {
                    bestLoss = candidateLoss;
                    bestTrap = catchesTrap;
                    selected = candidate;
                }
            }
            double chosen = selected;
            lost = (int) answerable.stream().filter(score -> score < chosen).count();
        }
        StringBuilder markdown = new StringBuilder("| id | top-1 | top-5 | ловушка? |\n|---|---:|---:|:---:|");
        for (IdkScoreRow row : rows) markdown.append("\n| ").append(row.id()).append(" | ")
                .append(String.format(Locale.ROOT, "%.6f", row.top1())).append(" | ")
                .append(String.format(Locale.ROOT, "%.6f", row.top5())).append(" | ")
                .append(row.trap() ? "да" : "нет").append(" |");
        markdown.append("\n\nidkThreshold=").append(String.format(Locale.ROOT, "%.6f", selected))
                .append(calibrated ? "; разделяет ловушку и все ответные вопросы."
                        : "; идеального разделения нет; потеря ответных вопросов: " + lost + "/"
                        + answerable.size() + ". Порог выбран с минимальной потерей.");
        return new IdkScoreReport(rows, selected, calibrated, lost, markdown.toString());
    }

    public static Report run(RagService service) throws IOException {
        return run(service, ignored -> { }, ignored -> { });
    }

    /** Saves a snapshot after each completed question and reports progress immediately. */
    public static Report run(RagService service, Consumer<String> progress,
                             Consumer<Report> checkpoint) throws IOException {
        List<Question> questions = loadQuestions();
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            RagService.Result off = service.ask(question.question(), RagService.Mode.OFF);
            RagService.Result on = service.askForEvaluation(question.question());
            Row row = evaluate(question, off, on);
            rows.add(row);
            Report snapshot = report(rows);
            checkpoint.accept(snapshot);
            progress.accept(progressLine(i + 1, questions.size(), off, on, row));
        }
        return report(rows);
    }

    /** Runs all requested modes; no-RAG answers and full-mode rewrites are cached per question. */
    public static ComparisonReport runModes(RagService service, RagSettings settings,
                                           Consumer<String> progress,
                                           Consumer<ComparisonReport> checkpoint)
            throws IOException {
        List<Question> questions = loadQuestions();
        RagSettings baselineSettings = new RagSettings(5, 5, 0, false, false);
        RagSettings filterSettings = new RagSettings(Math.max(5, settings.topKBefore()), 5,
                settings.minScore(), true, false, settings.rerankVectorWeight(),
                settings.rerankLexicalWeight(), settings.relativeDelta(), settings.diversityEnabled());
        RagSettings fullSettings = new RagSettings(Math.max(5, settings.topKBefore()), 5,
                settings.minScore(), true, true, settings.rerankVectorWeight(),
                settings.rerankLexicalWeight(), settings.relativeDelta(), settings.diversityEnabled());
        Map<String, RagService.Result> noRagCache = new LinkedHashMap<>();
        Map<String, RagQueryRewriter.Result> rewriteCache = new LinkedHashMap<>();
        List<ComparisonRow> rows = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            RagService.Result noRag = noRagCache.computeIfAbsent(question.id(), ignored ->
                    service.ask(question.question(), RagService.Mode.OFF));
            RagService.Result baseline = service.askForEvaluation(question.question(),
                    baselineSettings, question.question());
            RagService.Result filter = service.askForEvaluation(question.question(),
                    filterSettings, question.question());
            RagQueryRewriter.Result rewrite = rewriteCache.computeIfAbsent(question.id(), ignored ->
                    fullSettings.rewriteEnabled() ? service.rewriteQuery(question.question())
                            : new RagQueryRewriter.Result(question.question(), false, 0, "off"));
            RagService.Result full = service.askForEvaluation(question.question(),
                    fullSettings, rewrite.query());
            full = withRewrite(full, rewrite);
            ComparisonRow row = new ComparisonRow(question,
                    cell(question, noRag, false), cell(question, baseline, true),
                    cell(question, filter, true), cell(question, full, true));
            rows.add(row);
            ComparisonReport snapshot = comparisonReport(rows);
            checkpoint.accept(snapshot);
            progress.accept("[" + (i + 1) + "/" + questions.size() + "] A "
                    + formatMs(noRag.llmMs()) + " | B " + formatMs(baseline.retrieveMs()
                    + baseline.llmMs()) + " | C " + formatMs(filter.retrieveMs() + filter.llmMs())
                    + " | D " + formatMs(full.retrieveMs() + full.rewriteMs() + full.llmMs())
                    + " | filtered " + full.filteredCount() + " | "
                    + question.id() + " " + full.status().name().toLowerCase(Locale.ROOT));
        }
        return comparisonReport(rows);
    }

    /** Resumable A/B/C/D run with one durable result per question/mode pair. */
    public static ComparisonReport runResumable(RagService service, RagSettings settings,
                                                List<String> requestedModes,
                                                List<String> requestedQuestionIds,
                                                boolean resume, Path checkpointPath,
                                                Consumer<String> progress,
                                                Consumer<ComparisonReport> checkpoint)
            throws IOException {
        List<String> modes = validateModes(requestedModes);
        List<Question> allQuestions = loadQuestions();
        List<Question> questions = requestedQuestionIds == null || requestedQuestionIds.isEmpty()
                ? allQuestions : selectQuestions(allQuestions, requestedQuestionIds);
        RagEvalCheckpointStore store = new RagEvalCheckpointStore(checkpointPath);
        if (resume && store.exists()) {
            store.load();
        } else {
            store.clear();
            store.initialize();
        }

        RagSettings baseline = new RagSettings(5, 5, 0, false, false);
        RagSettings filter = new RagSettings(Math.max(5, settings.topKBefore()), 5,
                settings.minScore(), true, false, settings.rerankVectorWeight(),
                settings.rerankLexicalWeight(), settings.relativeDelta(), settings.diversityEnabled());
        RagSettings full = new RagSettings(Math.max(5, settings.topKBefore()), 5,
                settings.minScore(), true, true, settings.rerankVectorWeight(),
                settings.rerankLexicalWeight(), settings.relativeDelta(), settings.diversityEnabled());
        for (Question question : questions) {
            for (String mode : modes) {
                if (resume && store.hasResult(question.id(), mode)) {
                    progress.accept(question.id() + "/" + mode + " — уже в checkpoint, пропуск");
                    continue;
                }
                RagService.Result result;
                switch (mode) {
                    case "A" -> result = service.ask(question.question(), RagService.Mode.OFF);
                    case "B" -> result = service.askForEvaluation(question.question(),
                            baseline, question.question());
                    case "C" -> result = service.askForEvaluation(question.question(),
                            filter, question.question());
                    case "D" -> {
                        RagQueryRewriter.Result rewrite = store.rewrite(question.id());
                        if (rewrite == null) {
                            rewrite = service.rewriteQuery(question.question());
                            store.putRewrite(question.id(), rewrite);
                        }
                        result = withRewrite(service.askForEvaluation(question.question(),
                                full, rewrite.query()), rewrite);
                    }
                    default -> throw new IllegalStateException("Неизвестный режим: " + mode);
                }
                store.putResult(question.id(), mode, result);
                progress.accept(question.id() + "/" + mode + " — "
                        + result.status().name().toLowerCase(Locale.ROOT)
                        + ", search=" + formatMs(result.retrieveMs())
                        + ", rewrite=" + formatMs(result.rewriteMs())
                        + ", llm=" + formatMs(result.llmMs())
                        + ", total=" + formatMs(result.retrieveMs() + result.rewriteMs()
                        + result.llmMs()));
                ComparisonReport snapshot = checkpointReport(questions, store, checkpointPath);
                checkpoint.accept(snapshot);
            }
        }
        return checkpointReport(questions, store, checkpointPath);
    }

    private static List<String> validateModes(List<String> modes) throws IOException {
        if (modes == null || modes.isEmpty()) throw new IOException("Укажите режимы A,B,C,D");
        List<String> normalized = modes.stream().map(value -> value.toUpperCase(Locale.ROOT)).toList();
        if (normalized.stream().anyMatch(mode -> !Set.of("A", "B", "C", "D").contains(mode))
                || normalized.stream().distinct().count() != normalized.size()) {
            throw new IOException("Режимы задаются без повторов из A,B,C,D");
        }
        return normalized;
    }

    private static List<Question> selectQuestions(List<Question> all, List<String> ids)
            throws IOException {
        Map<String, Question> byId = all.stream().collect(Collectors.toMap(
                Question::id, question -> question, (left, right) -> left, LinkedHashMap::new));
        List<Question> selected = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) throw new IOException("Повтор question id: " + id);
            Question question = byId.get(id);
            if (question == null) throw new IOException("Неизвестный question id: " + id);
            selected.add(question);
        }
        return List.copyOf(selected);
    }

    private static ComparisonReport checkpointReport(List<Question> questions,
                                                      RagEvalCheckpointStore store,
                                                      Path checkpointPath) {
        List<ComparisonRow> rows = new ArrayList<>();
        for (Question question : questions) {
            rows.add(new ComparisonRow(question,
                    checkpointCell(question, store.result(question.id(), "A"), false),
                    checkpointCell(question, store.result(question.id(), "B"), true),
                    checkpointCell(question, store.result(question.id(), "C"), true),
                    checkpointCell(question, store.result(question.id(), "D"), true)));
        }
        return comparisonReport(rows, questions.size(), checkpointPath);
    }

    private static ModeCell checkpointCell(Question question, RagService.Result result,
                                           boolean withRag) {
        return result == null ? null : cell(question, result, withRag);
    }

    private static ModeCell cell(Question question, RagService.Result result, boolean withRag) {
        int facts = result.status() == RagService.Status.OK
                ? factsHit(result.answer(), question.expected()) : 0;
        boolean source = withRag && !question.noAnswerExpected()
                && sourceMatch(result.chunks(), question.expectedSources());
        boolean cited = withRag && !question.noAnswerExpected()
                && result.status() == RagService.Status.OK
                && citedExpectedSource(result.answer(), question.expectedSources(), result.chunks());
        double precision = 0;
        if (withRag && !question.noAnswerExpected() && !result.chunks().isEmpty()) {
            long matched = result.chunks().stream().filter(chunk ->
                    matchesAnySource(chunk.source(), question.expectedSources())).count();
            precision = (double) matched / result.chunks().size();
        }
        String status = result.status().name().toLowerCase(Locale.ROOT);
        return new ModeCell(result, facts, source, cited, precision,
                withRag ? result.filteredCount() : 0,
                question.noAnswerExpected() && result.status() == RagService.Status.OK
                        && saysNoAnswer(result.answer()), status);
    }

    private static RagService.Result withRewrite(RagService.Result result,
                                                 RagQueryRewriter.Result rewrite) {
        return new RagService.Result(result.answer(), result.chunks(), result.retrievedChunks(),
                result.retrieveMs(), result.llmMs(), result.status(), result.error(),
                result.filteredCount(), result.filteredAll(), rewrite.rewriteFallback(),
                rewrite.query(), rewrite.rewriteMs(), rewrite.rewriteStatus());
    }

    private static ComparisonReport comparisonReport(List<ComparisonRow> rows) {
        return comparisonReport(rows, 10, null);
    }

    private static ComparisonReport comparisonReport(List<ComparisonRow> rows,
                                                      int expectedQuestionCount,
                                                      Path checkpointPath) {
        Map<String, java.util.function.Function<ComparisonRow, ModeCell>> selectors = Map.of(
                "A", ComparisonRow::noRag, "B", ComparisonRow::baseline,
                "C", ComparisonRow::filter, "D", ComparisonRow::full);
        Map<String, Integer> missing = new LinkedHashMap<>();
        selectors.forEach((mode, selector) -> missing.put(mode,
                (int) rows.stream().filter(row -> selector.apply(row) == null).count()));
        List<ModeSummary> summaries = List.of(
                summarize("A no-rag", rows, ComparisonRow::noRag, false, missing.get("A")),
                summarize("B baseline", rows, ComparisonRow::baseline, true, missing.get("B")),
                summarize("C filter", rows, ComparisonRow::filter, true, missing.get("C")),
                summarize("D full", rows, ComparisonRow::full, true, missing.get("D")));
        StringBuilder out = new StringBuilder("# RAG comparison eval — ").append(LocalDate.now())
                .append("\n\nВопросов: ").append(rows.size())
                .append("/10. Поиск D объединяет исходный и переписанный запрос в одну строку; "
                        + "ответная модель всегда получает исходный вопрос.\n")
                .append("Чекпойнт: ").append(checkpointPath == null ? "in-memory" : checkpointPath)
                .append("\nВопросов в текущем срезе / questions.json: ").append(rows.size())
                .append('/').append(expectedQuestionCount)
                .append("\nНедостающие пары A/B/C/D: ").append(missing.get("A")).append('/')
                .append(missing.get("B")).append('/').append(missing.get("C")).append('/')
                .append(missing.get("D")).append(".\n\n")
                .append("| Режим | факты | recall@5/sourceHit | precision чанков | cited | filtered | refusals на ловушке | среднее, с | error/timeout/empty | missing |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ModeSummary summary : summaries) {
            out.append("| ").append(summary.mode()).append(" | ").append(summary.facts())
                    .append(" | ").append(summary.sourceHits()).append('/').append(summary.answerable())
                    .append(" | ").append(String.format(Locale.ROOT, "%.2f", summary.precision()))
                    .append(" | ").append(summary.cited()).append(" | ").append(summary.filtered())
                    .append(" | ").append(summary.refusals()).append(" | ")
                    .append(String.format(Locale.ROOT, "%.2f", summary.averageMs() / 1000.0))
                    .append(" | ").append(summary.errors()).append('/').append(summary.timeouts())
                    .append('/').append(summary.empty())
                    .append(" | ").append(summary.missingPairs())
                    .append(" |\n");
        }
        out.append("\n| # | вопрос | A факты/status | B факты/sourceHit/precision/cited | "
                + "C факты/sourceHit/precision/cited/filtered | D факты/sourceHit/precision/cited/filtered | D rewriteStatus |\n")
                .append("|---:|---|---|---|---|---|---|\n");
        for (int i = 0; i < rows.size(); i++) {
            ComparisonRow row = rows.get(i);
            out.append("| ").append(i + 1).append(" | ").append(table(row.question().question()))
                    .append(" | ").append(cellText(row.noRag()))
                    .append(" | ").append(cellText(row.baseline()))
                    .append(" | ").append(cellText(row.filter()))
                    .append(" | ").append(cellText(row.full()))
                    .append(" | ").append(row.full() == null ? "missing"
                            : table(row.full().result().rewriteStatus())).append(" |\n");
        }
        rows.stream().filter(row -> row.question().noAnswerExpected()).findFirst().ifPresent(trap ->
                out.append("\nТелефонная ловушка: B отказ модели=")
                        .append(trap.baseline() != null && trap.baseline().refusal() ? "да" : "нет")
                        .append(", C=")
                        .append(trap.filter() != null && trap.filter().refusal() ? "да" : "нет")
                        .append(", D=")
                        .append(trap.full() != null && trap.full().refusal() ? "да" : "нет")
                        .append(". Threshold-фильтр сам по себе не считается отказом; "
                                + "ловушка пройдена только при отказе модели.\n"));
        for (int i = 0; i < rows.size(); i++) {
            ComparisonRow row = rows.get(i);
            out.append("\n## ").append(i + 1).append(". ").append(row.question().id())
                    .append(" — ").append(row.question().question()).append("\n\n")
                    .append("Expected: ").append(String.join(", ", row.question().expected()))
                    .append("; sources: ").append(row.question().expectedSources().isBlank()
                            ? "нет" : row.question().expectedSources()).append(".\n\n");
            appendModeAnswer(out, "A no-rag", row.noRag());
            appendModeAnswer(out, "B baseline", row.baseline());
            appendModeAnswer(out, "C filter", row.filter());
            appendModeAnswer(out, "D full", row.full());
        }
        out.append("\n## Недостающие пары\n\n");
        for (ComparisonRow row : rows) {
            String[] modes = {"A", "B", "C", "D"};
            ModeCell[] cells = {row.noRag(), row.baseline(), row.filter(), row.full()};
            for (int i = 0; i < modes.length; i++) {
                if (cells[i] == null) out.append("- ").append(row.question().id())
                        .append('/').append(modes[i]).append('\n');
            }
        }
        return new ComparisonReport(rows, summaries, out.toString(), missing, checkpointPath);
    }

    private static ModeSummary summarize(String name, List<ComparisonRow> rows,
                                         java.util.function.Function<ComparisonRow, ModeCell> get,
                                         boolean withRag, int missingPairs) {
        List<ModeCell> cells = rows.stream().map(get).filter(java.util.Objects::nonNull).toList();
        int answerable = withRag ? (int) rows.stream()
                .filter(row -> !row.question().noAnswerExpected()).count() : 0;
        int sourceHits = (int) rows.stream().filter(row -> !row.question().noAnswerExpected())
                .map(get).filter(java.util.Objects::nonNull).filter(ModeCell::sourceHit).count();
        long relevantChunks = 0;
        long totalChunks = 0;
        if (withRag) {
            for (ComparisonRow row : rows) {
                if (row.question().noAnswerExpected()) continue;
                ModeCell cell = get.apply(row);
                if (cell == null) continue;
                totalChunks += cell.result().chunks().size();
                relevantChunks += cell.result().chunks().stream().filter(chunk ->
                        matchesAnySource(chunk.source(), row.question().expectedSources())).count();
            }
        }
        double precision = totalChunks == 0 ? 0 : (double) relevantChunks / totalChunks;
        int errors = (int) cells.stream().filter(cell -> "error".equals(cell.status())).count();
        int timeouts = (int) cells.stream().filter(cell -> "timeout".equals(cell.status())).count();
        int empty = (int) cells.stream().filter(cell -> "empty".equals(cell.status())).count();
        return new ModeSummary(name, cells.stream().mapToInt(ModeCell::facts).sum(), sourceHits,
                answerable, precision, (int) cells.stream().filter(ModeCell::cited).count(),
                cells.stream().mapToInt(ModeCell::filteredCandidates).sum(),
                (int) cells.stream().filter(ModeCell::refusal).count(),
                cells.stream().mapToLong(cell -> cell.result().retrieveMs()
                        + cell.result().rewriteMs() + cell.result().llmMs()).average().orElse(0),
                errors, timeouts, empty, missingPairs);
    }

    private static String cellText(ModeCell cell) {
        if (cell == null) return "missing";
        return cell.facts() + "/" + (cell.sourceHit() ? "hit" : "miss") + "/"
                + String.format(Locale.ROOT, "%.2f", cell.chunkPrecision()) + "/"
                + (cell.cited() ? "cited" : "uncited") + "/f=" + cell.filteredCandidates()
                + "/" + cell.status();
    }

    private static void appendModeAnswer(StringBuilder out, String title, ModeCell cell) {
        if (cell == null) {
            out.append("### ").append(title).append("\n\nРезультат отсутствует (пара не выполнена).\n\n");
            return;
        }
        RagService.Result result = cell.result();
        out.append("### ").append(title).append("\n\n")
                .append(result.status() == RagService.Status.OK ? fenced(result.answer())
                        : "Статус: " + cell.status() + " — " + fenced(result.error()))
                .append("\n\nМетрики: facts=").append(cell.facts()).append(", sourceHit=")
                .append(cell.sourceHit()).append(", cited=").append(cell.cited())
                .append(", precision=").append(String.format(Locale.ROOT, "%.2f", cell.chunkPrecision()))
                .append(", filtered=").append(cell.filteredCandidates())
                .append(", rewriteFallback=").append(result.rewriteFallback())
                .append(", rewriteStatus=").append(table(result.rewriteStatus()))
                .append(", время=").append(formatMs(result.retrieveMs() + result.rewriteMs()
                        + result.llmMs()))
                .append(".\n\n");
    }

    /** Deterministic threshold calibration over both local indexes; no LLM is called. */
    public static String thresholdScan(List<Question> questions, RagRetriever fixed,
                                       RagRetriever structure)
            throws IOException, InterruptedException {
        return thresholdScanDetails(questions, fixed, structure).markdown();
    }

    public static ThresholdScanReport thresholdScanDetails(List<Question> questions,
                                                            RagRetriever fixed,
                                                            RagRetriever structure)
            throws IOException, InterruptedException {
        Calibration fixedData = calibrate("fixed", questions, fixed);
        Calibration structureData = calibrate("structure", questions, structure);
        StringBuilder out = new StringBuilder("# Калибровка порога cosine similarity\n\n")
                .append("| Индекс | вопрос | лучший score | expected-source score | ранг expected | "
                        + "кандидатов | min | median | max |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|---:|\n");
        appendCalibrationRows(out, fixedData);
        appendCalibrationRows(out, structureData);
        List<ThresholdMetrics> fixedMetrics = scanMetrics(fixedData);
        List<ThresholdMetrics> structureMetrics = scanMetrics(structureData);
        out.append("\nМетрики считают чанки top-20: нужные — из expectedSources, мусорные — остальные; "
                + "потеря по правилу выбора не более одного нужного чанка на весь набор.\n\n")
                .append("| Индекс | порог | нужных сохранено | мусорных отсечено | вопросов без контекста | ловушка отсечена |\n")
                .append("|---|---:|---:|---:|---:|:---:|\n");
        appendMetrics(out, "fixed", fixedMetrics);
        appendMetrics(out, "structure", structureMetrics);
        ThresholdMetrics selectedFixed = selectThreshold(fixedMetrics);
        ThresholdMetrics selectedStructure = selectThreshold(structureMetrics);
        String selectedStrategy;
        double threshold;
        Double relativeDelta = null;
        int selectedJunk;
        int selectedLoss;
        int selectedNeededKept;
        int selectedNeededTotal;
        boolean selectedTrapFiltered;
        boolean calibrated;
        if (selectedFixed != null || selectedStructure != null) {
            boolean structureWins = selectedFixed == null || (selectedStructure != null
                    && selectedStructure.junkDropped() >= selectedFixed.junkDropped());
            ThresholdMetrics selected = structureWins ? selectedStructure : selectedFixed;
            selectedStrategy = structureWins ? "structure" : "fixed";
            threshold = selected.threshold();
            selectedJunk = selected.junkDropped();
            selectedLoss = selected.neededTotal() - selected.neededKept();
            selectedNeededKept = selected.neededKept();
            selectedNeededTotal = selected.neededTotal();
            selectedTrapFiltered = selected.trapFiltered();
            calibrated = true;
        } else {
            List<RelativeMetrics> fixedRelative = scanRelativeMetrics(fixedData);
            List<RelativeMetrics> structureRelative = scanRelativeMetrics(structureData);
            out.append("\nАбсолютный порог не удовлетворил одновременно сохранению нужных чанков "
                    + "и отсечению ловушки; проверяю relative score >= max_score − delta.\n\n")
                    .append("| Индекс | floor | delta | нужных сохранено | мусорных отсечено | "
                            + "вопросов без контекста | ловушка отсечена |\n")
                    .append("|---|---:|---:|---:|---:|---:|:---:|\n");
            appendRelativeMetrics(out, "fixed", fixedRelative);
            appendRelativeMetrics(out, "structure", structureRelative);
            RelativeMetrics fixedChoice = selectRelative(fixedRelative);
            RelativeMetrics structureChoice = selectRelative(structureRelative);
            calibrated = fixedChoice != null || structureChoice != null;
            boolean fixedStrict = fixedChoice != null;
            boolean structureStrict = structureChoice != null;
            if (fixedChoice == null) fixedChoice = selectRelativeCompromise(fixedRelative);
            if (structureChoice == null) structureChoice = selectRelativeCompromise(structureRelative);
            int comparison = fixedStrict != structureStrict ? Boolean.compare(structureStrict, fixedStrict)
                    : fixedStrict ? compareRelativeValid(structureChoice, fixedChoice)
                    : compareRelativeCompromise(structureChoice, fixedChoice);
            boolean structureWins = comparison >= 0;
            RelativeMetrics choice = structureWins ? structureChoice : fixedChoice;
            selectedStrategy = structureWins ? "structure" : "fixed";
            threshold = choice.floor();
            relativeDelta = choice.delta();
            selectedJunk = choice.junkDropped();
            selectedLoss = choice.neededTotal() - choice.neededKept();
            selectedNeededKept = choice.neededKept();
            selectedNeededTotal = choice.neededTotal();
            selectedTrapFiltered = choice.trapFiltered();
        }
        out.append("\nВыбор: порог ").append(String.format(Locale.ROOT, "%.2f", threshold))
                .append(", индекс по умолчанию — ").append(selectedStrategy);
        if (relativeDelta != null) out.append(", relativeDelta=")
                .append(String.format(Locale.ROOT, "%.2f", relativeDelta));
        out.append(". ");
        if (!calibrated) {
            out.append("Ни один абсолютный и относительный порог не удовлетворяет правилу. "
                    + "Лучший компромисс (он будет применён): сохранено нужных чанков ")
                    .append(selectedNeededKept).append('/').append(selectedNeededTotal)
                    .append(", потеря нужных — ").append(selectedLoss)
                    .append(", ловушка отсечена — ")
                    .append(selectedTrapFiltered ? "да" : "нет")
                    .append(", отсечено мусорных — ").append(selectedJunk)
                    .append(". Применён relative score >= max_score − delta с floor=")
                    .append(String.format(Locale.ROOT, "%.2f", threshold))
                    .append(", delta=").append(String.format(Locale.ROOT, "%.2f", relativeDelta))
                    .append(", index=").append(selectedStrategy).append(".\n");
        } else {
            out.append(relativeDelta == null ? "Абсолютное правило" : "Относительный компромисс")
                    .append(": максимум отсечённого мусора при потере не более одного "
                            + "нужного чанка и обязательном отсечении ловушки. ")
                    .append("Отсечено мусорных ").append(selectedJunk)
                    .append("; потеря нужных — ").append(selectedLoss).append(".\n");
        }
        return new ThresholdScanReport(out.toString(), threshold, selectedStrategy,
                calibrated, relativeDelta);
    }

    private static Calibration calibrate(String name, List<Question> questions,
                                         RagRetriever retriever)
            throws IOException, InterruptedException {
        Map<String, List<RagRetriever.Chunk>> chunks = new LinkedHashMap<>();
        for (Question question : questions) chunks.put(question.id(),
                retriever.retrieve(question.question(), 20));
        return new Calibration(name, questions, chunks);
    }

    private static void appendCalibrationRows(StringBuilder out, Calibration calibration) {
        for (Question question : calibration.questions()) {
            List<RagRetriever.Chunk> chunks = calibration.chunks().get(question.id());
            double best = chunks.stream().mapToDouble(RagRetriever.Chunk::score).max().orElse(0);
            int expectedRank = 0;
            double expected = 0;
            for (int i = 0; i < chunks.size(); i++) {
                if (!question.noAnswerExpected()
                        && matchesAnySource(chunks.get(i).source(), question.expectedSources())) {
                    expectedRank = i + 1;
                    expected = chunks.get(i).score();
                    break;
                }
            }
            List<Double> scores = chunks.stream().map(RagRetriever.Chunk::score).sorted().toList();
            String minimum = scores.isEmpty() ? "—" : score(scores.get(0));
            String median = scores.isEmpty() ? "—" : score(median(scores));
            String maximum = scores.isEmpty() ? "—" : score(scores.get(scores.size() - 1));
            out.append("| ").append(calibration.name()).append(" | ").append(table(question.id()))
                    .append(" | ").append(score(best))
                    .append(" | ").append(question.noAnswerExpected() || expectedRank == 0 ? "—"
                            : score(expected)).append(" | ").append(expectedRank == 0 ? "—" : expectedRank)
                    .append(" | ").append(chunks.size()).append(" | ").append(minimum)
                    .append(" | ").append(median).append(" | ").append(maximum).append(" |\n");
        }
    }

    private static List<ThresholdMetrics> scanMetrics(Calibration calibration) {
        List<ThresholdMetrics> result = new ArrayList<>();
        for (int step = 25; step <= 70; step += 5) {
            double threshold = step / 100.0;
            int neededKept = 0;
            int neededTotal = 0;
            int junkDropped = 0;
            int noContext = 0;
            boolean trapFiltered = false;
            for (Question question : calibration.questions()) {
                List<RagRetriever.Chunk> chunks = calibration.chunks().get(question.id());
                List<RagRetriever.Chunk> kept = chunks.stream().filter(chunk ->
                        chunk.score() >= threshold).toList();
                if (kept.isEmpty()) noContext++;
                if (question.noAnswerExpected()) {
                    trapFiltered = kept.isEmpty();
                    junkDropped += (int) chunks.stream().filter(chunk -> chunk.score() < threshold).count();
                    continue;
                }
                int expectedChunks = (int) chunks.stream().filter(chunk ->
                        matchesAnySource(chunk.source(), question.expectedSources())).count();
                int expectedKept = (int) kept.stream().filter(chunk ->
                        matchesAnySource(chunk.source(), question.expectedSources())).count();
                neededTotal += expectedChunks;
                neededKept += expectedKept;
                junkDropped += (int) chunks.stream().filter(chunk -> chunk.score() < threshold)
                        .filter(chunk -> !matchesAnySource(chunk.source(), question.expectedSources())).count();
            }
            result.add(new ThresholdMetrics(threshold, neededKept, neededTotal,
                    junkDropped, noContext, trapFiltered));
        }
        return result;
    }

    private static void appendMetrics(StringBuilder out, String name,
                                      List<ThresholdMetrics> metrics) {
        for (ThresholdMetrics metric : metrics) {
            out.append("| ").append(name).append(" | ")
                    .append(String.format(Locale.ROOT, "%.2f", metric.threshold())).append(" | ")
                    .append(metric.neededKept()).append('/').append(metric.neededTotal()).append(" | ")
                    .append(metric.junkDropped()).append(" | ").append(metric.noContext()).append(" | ")
                    .append(metric.trapFiltered() ? "да" : "нет").append(" |\n");
        }
    }

    private static ThresholdMetrics selectThreshold(List<ThresholdMetrics> metrics) {
        return metrics.stream().filter(metric -> metric.neededTotal() - metric.neededKept() <= 1
                        && metric.trapFiltered())
                .max(java.util.Comparator.comparingInt(ThresholdMetrics::junkDropped)
                        .thenComparingDouble(ThresholdMetrics::threshold)).orElse(null);
    }

    private static List<RelativeMetrics> scanRelativeMetrics(Calibration calibration) {
        List<RelativeMetrics> metrics = new ArrayList<>();
        for (int floorStep = 25; floorStep <= 70; floorStep += 5) {
            double floor = floorStep / 100.0;
            for (int deltaStep = 5; deltaStep <= 50; deltaStep += 5) {
                double delta = deltaStep / 100.0;
                int neededKept = 0;
                int neededTotal = 0;
                int junkDropped = 0;
                int noContext = 0;
                boolean trapFiltered = false;
                for (Question question : calibration.questions()) {
                    List<RagRetriever.Chunk> chunks = calibration.chunks().get(question.id());
                    double best = chunks.stream().mapToDouble(RagRetriever.Chunk::score).max().orElse(0);
                    double cutoff = Math.max(floor, best - delta);
                    List<RagRetriever.Chunk> kept = best < floor ? List.of()
                            : chunks.stream().filter(chunk -> chunk.score() >= cutoff).toList();
                    if (kept.isEmpty()) noContext++;
                    if (question.noAnswerExpected()) {
                        trapFiltered = kept.isEmpty();
                        junkDropped += chunks.size() - kept.size();
                        continue;
                    }
                    int expectedChunks = (int) chunks.stream().filter(chunk ->
                            matchesAnySource(chunk.source(), question.expectedSources())).count();
                    int expectedKept = (int) kept.stream().filter(chunk ->
                            matchesAnySource(chunk.source(), question.expectedSources())).count();
                    neededTotal += expectedChunks;
                    neededKept += expectedKept;
                    junkDropped += (int) chunks.stream().filter(chunk -> !kept.contains(chunk))
                            .filter(chunk -> !matchesAnySource(chunk.source(), question.expectedSources()))
                            .count();
                }
                metrics.add(new RelativeMetrics(floor, delta, neededKept, neededTotal,
                        junkDropped, noContext, trapFiltered));
            }
        }
        return metrics;
    }

    private static void appendRelativeMetrics(StringBuilder out, String name,
                                              List<RelativeMetrics> metrics) {
        for (RelativeMetrics metric : metrics) {
            out.append("| ").append(name).append(" | ")
                    .append(String.format(Locale.ROOT, "%.2f", metric.floor())).append(" | ")
                    .append(String.format(Locale.ROOT, "%.2f", metric.delta())).append(" | ")
                    .append(metric.neededKept()).append('/').append(metric.neededTotal()).append(" | ")
                    .append(metric.junkDropped()).append(" | ").append(metric.noContext()).append(" | ")
                    .append(metric.trapFiltered() ? "да" : "нет").append(" |\n");
        }
    }

    private static RelativeMetrics selectRelative(List<RelativeMetrics> metrics) {
        return metrics.stream().filter(metric ->
                        metric.neededTotal() - metric.neededKept() <= 1 && metric.trapFiltered())
                .max(Comparator.comparingInt(RelativeMetrics::junkDropped)
                        .thenComparingDouble(RelativeMetrics::floor)
                        .thenComparing(metric -> -metric.delta())).orElse(null);
    }

    private static RelativeMetrics selectRelativeCompromise(List<RelativeMetrics> metrics) {
        return metrics.stream().max(Comparator
                .comparing(RelativeMetrics::trapFiltered)
                .thenComparingInt(metric -> -(metric.neededTotal() - metric.neededKept()))
                .thenComparingInt(RelativeMetrics::junkDropped)
                .thenComparingInt(metric -> -metric.noContext())
                .thenComparingDouble(RelativeMetrics::floor)
                .thenComparing(metric -> -metric.delta())).orElse(null);
    }

    private static int compareRelativeValid(RelativeMetrics left, RelativeMetrics right) {
        int compare = Integer.compare(left.junkDropped(), right.junkDropped());
        if (compare != 0) return compare;
        compare = Double.compare(left.floor(), right.floor());
        return compare != 0 ? compare : Double.compare(right.delta(), left.delta());
    }

    private static int compareRelativeCompromise(RelativeMetrics left, RelativeMetrics right) {
        int compare = Boolean.compare(left.trapFiltered(), right.trapFiltered());
        if (compare != 0) return compare;
        compare = Integer.compare(right.neededTotal() - right.neededKept(),
                left.neededTotal() - left.neededKept());
        if (compare != 0) return compare;
        compare = Integer.compare(left.junkDropped(), right.junkDropped());
        if (compare != 0) return compare;
        compare = Integer.compare(right.noContext(), left.noContext());
        if (compare != 0) return compare;
        compare = Double.compare(left.floor(), right.floor());
        return compare != 0 ? compare : Double.compare(right.delta(), left.delta());
    }

    private static double median(List<Double> sorted) {
        int size = sorted.size();
        if (size % 2 == 1) return sorted.get(size / 2);
        return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
    }

    private static String score(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    /** Retrieves once per question, then compares general rerank weights without LLM calls. */
    public static RerankAnalysisReport rerankAnalysis(List<Question> questions,
                                                       RagRetriever retriever,
                                                       RagSettings settings)
            throws IOException, InterruptedException {
        List<AnalysisQuestion> benchmark = questions.stream().map(question ->
                new AnalysisQuestion(question.id(), question.question(), question.expectedSources()))
                .collect(Collectors.toCollection(ArrayList::new));
        benchmark.add(new AnalysisQuestion("filter-rewrite",
                "Как фильтр релевантности и query rewrite выбирают чанки для RAG?",
                "src/main/java/com/example/rag/RagReranker.java"
                        + "|src/main/java/com/example/rag/RagQueryRewriter.java"));

        Map<String, List<RagRetriever.Chunk>> candidatesById = new LinkedHashMap<>();
        for (AnalysisQuestion question : benchmark) {
            candidatesById.put(question.id(), retriever.retrieve(question.question(), 20));
        }

        List<RagReranker.Weights> weightGrid = List.of(
                new RagReranker.Weights(0.70, 0.30),
                new RagReranker.Weights(0.80, 0.20),
                new RagReranker.Weights(0.85, 0.15),
                new RagReranker.Weights(0.90, 0.10),
                new RagReranker.Weights(0.95, 0.05),
                new RagReranker.Weights(1.00, 0.00));
        RagSettings compareSettings = new RagSettings(Math.max(20, settings.topKBefore()), 5,
                settings.minScore(), true, false, settings.rerankVectorWeight(),
                settings.rerankLexicalWeight(), settings.relativeDelta(), settings.diversityEnabled());
        List<Question> answerable = questions.stream().filter(q -> !q.noAnswerExpected()).toList();
        int baselineHits = 0;
        long baselineRelevant = 0;
        long baselineTotal = 0;
        Map<String, Boolean> baselineHitByQuestion = new LinkedHashMap<>();
        for (Question question : answerable) {
            List<RagRetriever.Chunk> baseline = candidatesById.get(question.id()).stream()
                    .limit(5).toList();
            boolean hit = sourceMatch(baseline, question.expectedSources());
            baselineHitByQuestion.put(question.id(), hit);
            if (hit) baselineHits++;
            baselineRelevant += baseline.stream().filter(chunk ->
                    matchesAnySource(chunk.source(), question.expectedSources())).count();
            baselineTotal += baseline.size();
        }
        double baselinePrecision = baselineTotal == 0 ? 0
                : (double) baselineRelevant / baselineTotal;

        RagReranker reranker = new RagReranker();
        List<WeightMetric> metrics = new ArrayList<>();
        for (RagReranker.Weights weights : weightGrid) {
            for (boolean diversity : List.of(true, false)) {
                int hits = 0;
                int noWorse = 0;
                long relevant = 0;
                long total = 0;
                for (Question question : answerable) {
                    RagReranker.Result reranked = reranker.process(question.question(),
                            candidatesById.get(question.id()), compareSettings, weights, diversity);
                    boolean hit = sourceMatch(reranked.selected().stream()
                            .map(RagReranker.ScoredChunk::chunk).toList(), question.expectedSources());
                    if (hit) hits++;
                    if (hit || !baselineHitByQuestion.get(question.id())) noWorse++;
                    relevant += reranked.selected().stream().filter(item ->
                            matchesAnySource(item.chunk().source(), question.expectedSources())).count();
                    total += reranked.selected().size();
                }
                double precision = total == 0 ? 0 : (double) relevant / total;
                boolean valid = precision + 1e-12 >= Math.max(RERANK_PRECISION_FLOOR, baselinePrecision)
                        && noWorse == answerable.size();
                metrics.add(new WeightMetric(weights, hits, noWorse, precision, valid, diversity));
            }
        }
        WeightMetric chosen = metrics.stream().filter(WeightMetric::valid)
                .max(Comparator.comparingDouble((WeightMetric metric) ->
                        metric.weights().lexical())
                        .thenComparing(WeightMetric::diversity)).orElse(null);
        boolean constraintsMet = chosen != null;
        if (chosen == null) chosen = metrics.stream().max(Comparator
                .comparingInt(WeightMetric::noWorseQuestions)
                .thenComparingDouble(WeightMetric::precision)
                .thenComparingDouble(metric -> metric.weights().lexical())
                .thenComparing(WeightMetric::diversity)).orElseThrow();

        StringBuilder out = new StringBuilder("# Rerank weight analysis — ")
                .append(LocalDate.now()).append("\n\n")
                .append("Vector top-5 baseline: sourceHit ").append(baselineHits).append('/')
                .append(answerable.size()).append(", precision ")
                .append(String.format(Locale.ROOT, "%.3f", baselinePrecision))
                .append("; required precision floor ")
                .append(String.format(Locale.ROOT, "%.3f",
                        Math.max(RERANK_PRECISION_FLOOR, baselinePrecision))).append(".\n\n")
                .append("| vectorWeight | lexicalWeight | diversity cap | sourceHit | no-worse questions | precision | satisfies constraints |\n")
                .append("|---:|---:|:---:|---:|---:|---:|:---:|\n");
        for (WeightMetric metric : metrics) {
            out.append("| ").append(score(metric.weights().vector())).append(" | ")
                    .append(score(metric.weights().lexical())).append(" | ")
                    .append(metric.diversity() ? "on" : "off").append(" | ")
                    .append(metric.sourceHits()).append('/').append(answerable.size()).append(" | ")
                    .append(metric.noWorseQuestions()).append('/').append(answerable.size()).append(" | ")
                    .append(String.format(Locale.ROOT, "%.3f", metric.precision())).append(" | ")
                    .append(metric.valid() ? "да" : "нет").append(" |\n");
        }
        out.append("\nВес для рабочего default: vector=")
                .append(score(chosen.weights().vector())).append(", lexical=")
                .append(score(chosen.weights().lexical())).append(", diversityCap=")
                .append(chosen.diversity() ? "on" : "off").append("; ограничения ")
                .append(constraintsMet ? "соблюдены" : "не удалось выполнить одновременно")
                .append(".\n\n## Правила реранкера\n\n")
                .append("- Итоговый score = vectorWeight × cosine + lexicalWeight × lexicalScore.\n")
                .append("- Идентификаторы и camelCase-компоненты получают вес термина 2; "
                        + "стоп-слова исключаются.\n")
                .append("- Одно совпадение в пути/заголовке добавляет metadata bonus ")
                .append(String.format(Locale.ROOT, "%.2f", RagReranker.METADATA_BONUS)).append(".\n")
                .append("- Diversity cap ограничивает до двух чанков на файл при наличии "
                        + "альтернативных файлов.\n\n## Ранги кандидатов\n");
        Set<String> detailIds = Set.of("history-lock", "context-layers", "mcp-agent-flow",
                "fixed-limits", "filter-rewrite");
        for (AnalysisQuestion question : benchmark) {
            if (!detailIds.contains(question.id())) continue;
            List<RagRetriever.Chunk> candidates = candidatesById.get(question.id());
            RagReranker.Result reranked = reranker.process(question.question(), candidates,
                    compareSettings, chosen.weights(), chosen.diversity());
            Map<String, Integer> finalRanks = new LinkedHashMap<>();
            for (int i = 0; i < reranked.selected().size(); i++) {
                finalRanks.put(reranked.selected().get(i).chunk().chunkId(), i + 1);
            }
            Map<String, Integer> scoreRanks = new LinkedHashMap<>();
            List<RagReranker.ScoredChunk> sorted = reranked.candidates().stream()
                    .sorted(Comparator.comparingDouble(RagReranker.ScoredChunk::finalScore).reversed())
                    .toList();
            for (int i = 0; i < sorted.size(); i++) {
                scoreRanks.put(sorted.get(i).chunk().chunkId(), i + 1);
            }
            out.append("\n### ").append(question.id()).append(" — ")
                    .append(question.question()).append("\n\n")
                    .append("| Чанк | vector rank | rerank rank | lexicalScore | reason |\n")
                    .append("|---|---:|---:|---:|---|\n");
            for (int i = 0; i < candidates.size(); i++) {
                RagRetriever.Chunk chunk = candidates.get(i);
                RagReranker.ScoredChunk scored = reranked.candidates().stream()
                        .filter(item -> item.chunk().chunkId().equals(chunk.chunkId()))
                        .findFirst().orElseThrow();
                boolean expected = !question.expectedSources().isBlank()
                        && matchesAnySource(chunk.source(), question.expectedSources());
                out.append("| ").append(table(chunk.source() + " › " + chunk.section()))
                        .append(" | ").append(i + 1).append(" | ")
                        .append(finalRanks.getOrDefault(chunk.chunkId(),
                                scoreRanks.getOrDefault(chunk.chunkId(), 0)))
                        .append(" | ").append(score(scored.lexicalScore())).append(" | ")
                        .append(expected ? "expectedSources" : "нет")
                        .append(finalRanks.containsKey(chunk.chunkId()) ? "; selected" : "; вне top-5")
                        .append("; ").append(scored.reason()).append(" |\n");
            }
        }
        return new RerankAnalysisReport(out.toString(), chosen.weights(), chosen.diversity(), baselinePrecision,
                chosen.precision(), baselineHits, chosen.sourceHits(), answerable.size(), constraintsMet);
    }

    private static Row evaluate(Question question, RagService.Result off, RagService.Result on) {
        int factsOff = off.status() == RagService.Status.OK
                ? factsHit(off.answer(), question.expected()) : 0;
        int factsOn = on.status() == RagService.Status.OK
                ? factsHit(on.answer(), question.expected()) : 0;
        boolean sourceHit = !question.noAnswerExpected()
                && sourceMatch(on.retrievedChunks(), question.expectedSources());
        boolean cited = !question.noAnswerExpected() && on.status() == RagService.Status.OK
                && citedExpectedSource(on.answer(), question.expectedSources(), on.chunks());
        boolean offInvented = question.noAnswerExpected() && off.status() == RagService.Status.OK
                && !saysNoAnswer(off.answer());
        boolean onDeclined = question.noAnswerExpected() && on.status() == RagService.Status.OK
                && saysNoAnswer(on.answer());
        String status = questionStatus(off, on);
        String verdict;
        if (off.status() == RagService.Status.ERROR || on.status() == RagService.Status.ERROR) {
            verdict = "запрос завершился ошибкой";
        } else if (off.status() == RagService.Status.EMPTY || on.status() == RagService.Status.EMPTY) {
            verdict = "пустой ответ после повтора";
        } else if (question.noAnswerExpected()) {
            verdict = onDeclined && offInvented ? "RAG помог: не выдумал"
                    : onDeclined ? "RAG отказал; off тоже не выдумал"
                    : "RAG не удержал отказ";
        } else if (factsOn > factsOff) {
            verdict = "RAG помог";
        } else if (factsOn < factsOff) {
            verdict = "RAG хуже";
        } else if (!sourceHit) {
            verdict = "без улучшения: промах поиска";
        } else if (!cited) {
            verdict = "контекст найден, но источник не процитирован";
        } else {
            verdict = "без изменения числа фактов";
        }
        return new Row(question, off, on, factsOff, factsOn, sourceHit, cited,
                offInvented, onDeclined, status, verdict);
    }

    private static Report report(List<Row> rows) {
        List<Row> successful = rows.stream().filter(Row::successful).toList();
        int factsOff = successful.stream().mapToInt(Row::factsOff).sum();
        int factsOn = successful.stream().mapToInt(Row::factsOn).sum();
        int sourceQuestions = (int) successful.stream()
                .filter(row -> !row.question().noAnswerExpected()).count();
        int sourceHits = (int) successful.stream().filter(row -> !row.question().noAnswerExpected()
                && row.sourceHit()).count();
        int wins = (int) successful.stream().filter(row -> row.verdict().startsWith("RAG помог")).count();
        int errors = (int) rows.stream().mapToLong(row ->
                (row.off().status() == RagService.Status.ERROR ? 1 : 0)
                        + (row.on().status() == RagService.Status.ERROR ? 1 : 0)).sum();
        int empty = (int) rows.stream().mapToLong(row ->
                (row.off().status() == RagService.Status.EMPTY ? 1 : 0)
                        + (row.on().status() == RagService.Status.EMPTY ? 1 : 0)).sum();
        Report partial = new Report(rows, factsOff, factsOn, sourceHits, sourceQuestions,
                wins, successful.size(), errors, empty, "");
        return new Report(rows, factsOff, factsOn, sourceHits, sourceQuestions, wins,
                successful.size(), errors, empty, markdown(partial));
    }

    private static String questionStatus(RagService.Result off, RagService.Result on) {
        return "off=" + off.status().name().toLowerCase(Locale.ROOT)
                + ",on=" + on.status().name().toLowerCase(Locale.ROOT);
    }

    private static String progressLine(int number, int total, RagService.Result off,
                                       RagService.Result on, Row row) {
        String sourceHit = row.question().noAnswerExpected() ? "—"
                : row.on().status() == RagService.Status.ERROR ? "error"
                : row.sourceHit() ? "да" : "нет";
        return String.format(Locale.ROOT, "[%d/%d] off %.1fс | on %.1fс | sourceHit %s%s",
                number, total, durationSeconds(off), durationSeconds(on), sourceHit,
                row.successful() ? "" : " | status=" + row.status());
    }

    private static double durationSeconds(RagService.Result result) {
        return (result.retrieveMs() + result.rewriteMs() + result.llmMs()) / 1000.0;
    }

    public static RetrievalReport retrieval(List<Question> questions,
                                            RagRetriever fixed, RagRetriever structure)
            throws IOException, InterruptedException {
        List<Question> answerable = questions.stream().filter(question ->
                !question.noAnswerExpected()).toList();
        List<RetrievalRow> rows = new ArrayList<>();
        int fixedHits = 0;
        int structureHits = 0;
        for (Question question : answerable) {
            List<RagRetriever.Chunk> fixedHitsForQuestion = fixed.retrieve(question.question());
            List<RagRetriever.Chunk> structureHitsForQuestion = structure.retrieve(question.question());
            int fixedRank = sourceRank(fixedHitsForQuestion, question.expectedSources());
            int structureRank = sourceRank(structureHitsForQuestion, question.expectedSources());
            if (fixedRank > 0) {
                fixedHits++;
            }
            if (structureRank > 0) {
                structureHits++;
            }
            rows.add(new RetrievalRow(question, fixedHitsForQuestion,
                    structureHitsForQuestion, fixedRank, structureRank));
        }
        return new RetrievalReport(rows, fixedHits, structureHits, answerable.size());
    }

    public static int factsHit(String answer, List<String> expected) {
        String normalized = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        return (int) expected.stream().filter(fact -> !fact.isBlank()
                && normalized.contains(fact.toLowerCase(Locale.ROOT))).count();
    }

    public static boolean sourceMatch(List<RagRetriever.Chunk> chunks, String expectedSources) {
        return sourceRank(chunks, expectedSources) > 0;
    }

    public static int sourceRank(List<RagRetriever.Chunk> chunks, String expectedSources) {
        if (expectedSources == null || expectedSources.isBlank()) {
            return 0;
        }
        for (int i = 0; i < chunks.size(); i++) {
            if (matchesAnySource(chunks.get(i).source(), expectedSources)) {
                return i + 1;
            }
        }
        return 0;
    }

    public static boolean citedExpectedSource(String answer, String expectedSources) {
        return citedExpectedSource(answer, expectedSources, List.of());
    }

    /** Numeric references resolve against the exact ordered chunks sent in this request. */
    public static boolean citedExpectedSource(String answer, String expectedSources,
                                              List<RagRetriever.Chunk> usedChunks) {
        if (answer == null || expectedSources == null || expectedSources.isBlank()) {
            return false;
        }
        Matcher citations = BRACKET_CITATION.matcher(answer);
        while (citations.find()) {
            String citation = citations.group(1).trim();
            Matcher numbered = NUMBERED_CITATION.matcher(citation);
            if (numbered.matches()) {
                try {
                    int index = Integer.parseInt(numbered.group(1)) - 1;
                    if (index >= 0 && index < usedChunks.size()
                            && matchesAnySource(usedChunks.get(index).source(), expectedSources)) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                    // An oversized numeric reference is not a valid citation.
                }
            } else {
                String source = citation.replaceFirst("(?i)^source\\s+", "");
                if (matchesAnySourceReference(source, expectedSources)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesAnySourceReference(String reference, String expectedSources) {
        String actual = normalizeSource(reference);
        String actualName = basename(actual);
        for (String expected : expectedSources.split("\\|")) {
            String candidate = normalizeSource(expected);
            if (!candidate.isEmpty() && (actual.equals(candidate)
                    || actualName.equals(basename(candidate)))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeSource(String source) {
        return source.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static String basename(String source) {
        return source.substring(source.lastIndexOf('/') + 1);
    }

    public static boolean saysNoAnswer(String answer) {
        String normalized = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        return normalized.contains(RagConstants.NO_ANSWER.toLowerCase(Locale.ROOT))
                || normalized.contains("нет ответа");
    }

    private static boolean matchesAnySource(String actual, String expectedSources) {
        String normalized = actual.toLowerCase(Locale.ROOT);
        for (String expected : expectedSources.split("\\|")) {
            String candidate = expected.trim().toLowerCase(Locale.ROOT);
            if (!candidate.isEmpty() && normalized.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String markdown(Report report) {
        StringBuilder out = new StringBuilder("# RAG eval — ").append(LocalDate.now()).append("\n\n")
                .append("Прогресс: ").append(report.rows().size()).append("/10 вопросов.\n\n")
                .append("| # | вопрос | ожидание | источники ожидаемые/найденные | факты off | факты on | cited | status | вердикт |\n")
                .append("|---:|---|---|---|---:|---:|:---:|---|---|\n");
        for (int i = 0; i < report.rows().size(); i++) {
            Row row = report.rows().get(i);
            String found = row.on().retrievedChunks().stream().map(RagRetriever.Chunk::source)
                    .distinct().collect(Collectors.joining(", "));
            out.append("| ").append(i + 1).append(" | ").append(table(row.question().question()))
                    .append(" | ").append(table(String.join(", ", row.question().expected())))
                    .append(" | ").append(table((row.question().expectedSources().isBlank()
                            ? "нет" : row.question().expectedSources()) + " / "
                            + (found.isBlank() ? "нет" : found)))
                    .append(" | ").append(row.successful() ? row.factsOff() : "—")
                    .append(" | ").append(row.successful() ? row.factsOn() : "—")
                    .append(" | ").append(row.question().noAnswerExpected() ? "—"
                            : row.successful() ? row.cited() ? "да" : "нет" : "—")
                    .append(" | ").append(row.status()).append(" | ")
                    .append(table(row.verdict())).append(" |\n");
        }
        out.append("\nИтого только по полностью успешным вопросам: факты off — ")
                .append(report.factsOff()).append(", on — ").append(report.factsOn())
                .append("; retrieval recall@5 — ").append(report.sourceHits()).append('/')
                .append(report.sourceQuestions())
                .append(String.format(Locale.ROOT, " (%.0f%%); RAG wins — %d/%d.\n",
                        report.recallAt5() * 100, report.wins(), report.successfulQuestions()));
        out.append("\nНеуспешных вопросов: ").append(report.failedQuestions())
                .append("; ошибок результатов: ").append(report.errorResults())
                .append("; пустых ответов после повтора: ").append(report.emptyResults()).append(".\n\n")
                .append("## Вывод\n\n");
        report.summaryLines().forEach(line -> out.append("- ").append(line).append('\n'));
        for (int i = 0; i < report.rows().size(); i++) {
            Row row = report.rows().get(i);
            out.append("\n## ").append(i + 1).append(". ").append(row.question().id())
                    .append(" — ").append(row.question().question()).append("\n\n")
                    .append("Ожидание: ").append(row.question().note()).append("\n\n")
                    .append("Найденные источники: ")
                    .append(row.on().retrievedChunks().stream().map(RagRetriever.Chunk::source)
                            .distinct().collect(Collectors.joining(", "))).append("\n\n")
                    .append("### Без RAG\n\n").append(answerOrError(row.off()))
                    .append("\n\n### С RAG\n\n").append(answerOrError(row.on()))
                    .append("\n\nВремя off/on: ").append(formatMs(row.off().llmMs()))
                    .append(" / ").append(formatMs(row.on().retrieveMs() + row.on().rewriteMs()
                            + row.on().llmMs()))
                    .append(".\n");
        }
        return out.toString();
    }

    private static String answerOrError(RagService.Result result) {
        if (result.status() == RagService.Status.OK) {
            return fenced(result.answer());
        }
        return "Статус: " + result.status().name().toLowerCase(Locale.ROOT)
                + "\n\n" + fenced(result.error());
    }

    private static String formatSources(List<RagRetriever.Chunk> chunks) {
        List<String> ranked = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            ranked.add((i + 1) + ":" + chunks.get(i).source());
        }
        return String.join(", ", ranked);
    }

    private static String rank(int value) {
        return value == 0 ? "—" : Integer.toString(value);
    }

    private static String formatMs(long millis) {
        return String.format(Locale.ROOT, "%.1fс", millis / 1000.0);
    }

    private static String table(String value) {
        return value.replace("|", "\\|").replace('\n', ' ');
    }

    private static String fenced(String value) {
        return "```text\n" + (value == null ? "" : RagQueryRewriter.redactSecrets(
                value.replace("```", "'''"))) + "\n```";
    }

    public static Path reportPath(Path home, LocalDate date) {
        return home.resolve(".ai-advent-agent").resolve("rag-results")
                .resolve("rag-eval-" + date + ".md");
    }

    public static Path artifactPath(Path project, LocalDate date) {
        return project.resolve("artifacts").resolve("rag-eval-" + date + ".md");
    }

    public static Path liveLogPath(Path home, LocalDate date) {
        return home.resolve(".ai-advent-agent").resolve("rag-results")
                .resolve("rag-live-" + date + ".log");
    }

    public static Path thresholdScanPath(Path home, LocalDate date) {
        return home.resolve(".ai-advent-agent").resolve("rag-results")
                .resolve("threshold-scan-" + date + ".md");
    }

    public static Path rerankAnalysisPath(Path home, LocalDate date) {
        return home.resolve(".ai-advent-agent").resolve("rag-results")
                .resolve("rerank-analysis-" + date + ".md");
    }

    public static Path checkpointPath(Path home, LocalDate date) {
        return home.resolve(".ai-advent-agent").resolve("rag-results")
                .resolve("rag-eval-checkpoint-" + date + ".json");
    }

    public static void writeReport(Path target, String contents) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(),
                target.getFileName().toString() + ".", ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
