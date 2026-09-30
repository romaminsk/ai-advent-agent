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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Fixed-question comparison, retrieval recall, and deterministic lexical metrics. */
public final class RagEval {
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
                              double averageMs, int errors, int empty) {
        public double recallAt5() {
            return answerable == 0 ? 0 : (double) sourceHits / answerable;
        }
    }

    public record ComparisonReport(List<ComparisonRow> rows, List<ModeSummary> summaries,
                                  String markdown) {
        public ComparisonReport {
            rows = List.copyOf(rows);
            summaries = List.copyOf(summaries);
        }

        public List<String> summaryLines() {
            return summaries.stream().map(summary -> String.format(Locale.ROOT,
                    "%s: факты %d, recall@5 %d/%d (%.0f%%), precision %.2f, cited %d, "
                            + "filtered %d, refusals %d, среднее %.1f с, error/empty %d/%d",
                    summary.mode(), summary.facts(), summary.sourceHits(), summary.answerable(),
                    summary.recallAt5() * 100, summary.precision(), summary.cited(),
                    summary.filtered(), summary.refusals(), summary.averageMs() / 1000.0,
                    summary.errors(), summary.empty())).toList();
        }
    }

    private record Calibration(String name, List<Question> questions,
                               Map<String, List<RagRetriever.Chunk>> chunks) {
    }

    private record ThresholdMetrics(double threshold, int neededKept, int neededTotal,
                                    int junkDropped, int noContext, boolean trapFiltered) {
    }

    public record ThresholdScanReport(String markdown, double selectedThreshold,
                                      String selectedStrategy, boolean calibrated) {
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
            RagService.Result on = service.ask(question.question(), RagService.Mode.ON);
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
                settings.minScore(), true, false);
        RagSettings fullSettings = new RagSettings(Math.max(5, settings.topKBefore()), 5,
                settings.minScore(), true, true);
        Map<String, RagService.Result> noRagCache = new LinkedHashMap<>();
        Map<String, RagQueryRewriter.Result> rewriteCache = new LinkedHashMap<>();
        List<ComparisonRow> rows = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            RagService.Result noRag = noRagCache.computeIfAbsent(question.id(), ignored ->
                    service.ask(question.question(), RagService.Mode.OFF));
            RagService.Result baseline = service.ask(question.question(), RagService.Mode.ON,
                    baselineSettings, question.question());
            RagService.Result filter = service.ask(question.question(), RagService.Mode.ON,
                    filterSettings, question.question());
            RagQueryRewriter.Result rewrite = rewriteCache.computeIfAbsent(question.id(), ignored ->
                    fullSettings.rewriteEnabled() ? service.rewriteQuery(question.question())
                            : new RagQueryRewriter.Result(question.question(), false, 0));
            RagService.Result full = service.ask(question.question(), RagService.Mode.ON,
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
                rewrite.query(), rewrite.rewriteMs());
    }

    private static ComparisonReport comparisonReport(List<ComparisonRow> rows) {
        List<ModeSummary> summaries = List.of(
                summarize("A no-rag", rows, ComparisonRow::noRag, false),
                summarize("B baseline", rows, ComparisonRow::baseline, true),
                summarize("C filter", rows, ComparisonRow::filter, true),
                summarize("D full", rows, ComparisonRow::full, true));
        StringBuilder out = new StringBuilder("# RAG comparison eval — ").append(LocalDate.now())
                .append("\n\nВопросов: ").append(rows.size())
                .append("/10. Поиск D объединяет исходный и переписанный запрос в одну строку; "
                        + "ответная модель всегда получает исходный вопрос.\n\n")
                .append("| Режим | факты | recall@5/sourceHit | precision чанков | cited | filtered | refusals на ловушке | среднее, с | error/empty |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ModeSummary summary : summaries) {
            out.append("| ").append(summary.mode()).append(" | ").append(summary.facts())
                    .append(" | ").append(summary.sourceHits()).append('/').append(summary.answerable())
                    .append(" | ").append(String.format(Locale.ROOT, "%.2f", summary.precision()))
                    .append(" | ").append(summary.cited()).append(" | ").append(summary.filtered())
                    .append(" | ").append(summary.refusals()).append(" | ")
                    .append(String.format(Locale.ROOT, "%.2f", summary.averageMs() / 1000.0))
                    .append(" | ").append(summary.errors()).append('/').append(summary.empty())
                    .append(" |\n");
        }
        out.append("\n| # | вопрос | A факты/status | B факты/sourceHit/precision/cited | "
                + "C факты/sourceHit/precision/cited/filtered | D факты/sourceHit/precision/cited/filtered |\n")
                .append("|---:|---|---|---|---|---|\n");
        for (int i = 0; i < rows.size(); i++) {
            ComparisonRow row = rows.get(i);
            out.append("| ").append(i + 1).append(" | ").append(table(row.question().question()))
                    .append(" | ").append(row.noRag().facts()).append('/').append(row.noRag().status())
                    .append(" | ").append(cellText(row.baseline()))
                    .append(" | ").append(cellText(row.filter()))
                    .append(" | ").append(cellText(row.full())).append(" |\n");
        }
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
        return new ComparisonReport(rows, summaries, out.toString());
    }

    private static ModeSummary summarize(String name, List<ComparisonRow> rows,
                                         java.util.function.Function<ComparisonRow, ModeCell> get,
                                         boolean withRag) {
        List<ModeCell> cells = rows.stream().map(get).toList();
        int answerable = withRag ? (int) rows.stream()
                .filter(row -> !row.question().noAnswerExpected()).count() : 0;
        int sourceHits = (int) rows.stream().filter(row -> !row.question().noAnswerExpected())
                .filter(row -> get.apply(row).sourceHit()).count();
        long relevantChunks = withRag ? rows.stream()
                .filter(row -> !row.question().noAnswerExpected())
                .mapToLong(row -> get.apply(row).result().chunks().stream().filter(chunk ->
                        matchesAnySource(chunk.source(), row.question().expectedSources())).count())
                .sum() : 0;
        long totalChunks = withRag ? rows.stream()
                .filter(row -> !row.question().noAnswerExpected())
                .mapToLong(row -> get.apply(row).result().chunks().size()).sum() : 0;
        double precision = totalChunks == 0 ? 0 : (double) relevantChunks / totalChunks;
        int errors = (int) cells.stream().filter(cell -> "error".equals(cell.status())).count();
        int empty = (int) cells.stream().filter(cell -> "empty".equals(cell.status())).count();
        return new ModeSummary(name, cells.stream().mapToInt(ModeCell::facts).sum(), sourceHits,
                answerable, precision, (int) cells.stream().filter(ModeCell::cited).count(),
                cells.stream().mapToInt(ModeCell::filteredCandidates).sum(),
                (int) cells.stream().filter(ModeCell::refusal).count(),
                cells.stream().mapToLong(cell -> cell.result().retrieveMs()
                        + cell.result().rewriteMs() + cell.result().llmMs()).average().orElse(0),
                errors, empty);
    }

    private static String cellText(ModeCell cell) {
        return cell.facts() + "/" + (cell.sourceHit() ? "hit" : "miss") + "/"
                + String.format(Locale.ROOT, "%.2f", cell.chunkPrecision()) + "/"
                + (cell.cited() ? "cited" : "uncited") + "/f=" + cell.filteredCandidates()
                + "/" + cell.status();
    }

    private static void appendModeAnswer(StringBuilder out, String title, ModeCell cell) {
        RagService.Result result = cell.result();
        out.append("### ").append(title).append("\n\n")
                .append(result.status() == RagService.Status.OK ? fenced(result.answer())
                        : "Статус: " + cell.status() + " — " + fenced(result.error()))
                .append("\n\nМетрики: facts=").append(cell.facts()).append(", sourceHit=")
                .append(cell.sourceHit()).append(", cited=").append(cell.cited())
                .append(", precision=").append(String.format(Locale.ROOT, "%.2f", cell.chunkPrecision()))
                .append(", filtered=").append(cell.filteredCandidates())
                .append(", rewriteFallback=").append(result.rewriteFallback())
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
                .append("| Индекс | вопрос | best score | expected-source score |\n")
                .append("|---|---|---:|---:|\n");
        appendCalibrationRows(out, fixedData);
        appendCalibrationRows(out, structureData);
        List<ThresholdMetrics> fixedMetrics = scanMetrics(fixedData);
        List<ThresholdMetrics> structureMetrics = scanMetrics(structureData);
        out.append("\n| Индекс | порог | нужные сохранены | мусор отсечён | вопросов без контекста | ловушка отсечена |\n")
                .append("|---|---:|---:|---:|---:|:---:|\n");
        appendMetrics(out, "fixed", fixedMetrics);
        appendMetrics(out, "structure", structureMetrics);
        ThresholdMetrics selectedFixed = selectThreshold(fixedMetrics);
        ThresholdMetrics selectedStructure = selectThreshold(structureMetrics);
        boolean calibrated = selectedFixed != null || selectedStructure != null;
        boolean structureWins = selectedFixed == null || (selectedStructure != null
                && selectedStructure.junkDropped() >= selectedFixed.junkDropped());
        ThresholdMetrics selected = structureWins ? selectedStructure : selectedFixed;
        String selectedStrategy = structureWins ? "structure" : "fixed";
        double threshold = selected == null ? RagSettings.DEFAULT_MIN_SCORE : selected.threshold();
        out.append("\nВыбор: порог ").append(String.format(Locale.ROOT, "%.2f", threshold))
                .append(", индекс по умолчанию — ").append(selectedStrategy).append(". ");
        if (selected == null) {
            out.append("Для сетки не нашлось порога, одновременно удовлетворяющего правилам; "
                            + "оставлено стартовое значение ")
                    .append(String.format(Locale.ROOT, "%.2f", threshold)).append(".\n");
        } else {
            out.append("Правило: максимум отсечённого мусора при потере не более одного "
                            + "нужного чанка и обязательном отсечении ловушки. ")
                    .append("Выбрано отсечение мусора ").append(selected.junkDropped())
                    .append("; потеря нужных — ").append(selected.neededTotal() - selected.neededKept())
                    .append(" из ").append(selected.neededTotal()).append(".\n");
        }
        return new ThresholdScanReport(out.toString(), threshold, selectedStrategy, calibrated);
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
            double expected = chunks.stream().filter(chunk -> !question.noAnswerExpected()
                    && matchesAnySource(chunk.source(), question.expectedSources()))
                    .mapToDouble(RagRetriever.Chunk::score).max().orElse(0);
            out.append("| ").append(calibration.name()).append(" | ").append(table(question.id()))
                    .append(" | ").append(String.format(Locale.ROOT, "%.6f", best))
                    .append(" | ").append(question.noAnswerExpected() ? "—"
                            : String.format(Locale.ROOT, "%.6f", expected)).append(" |\n");
        }
    }

    private static List<ThresholdMetrics> scanMetrics(Calibration calibration) {
        List<ThresholdMetrics> result = new ArrayList<>();
        int neededTotal = (int) calibration.questions().stream()
                .filter(question -> !question.noAnswerExpected()).count();
        for (double threshold : List.of(0.25, 0.30, 0.35, 0.40, 0.45, 0.50)) {
            int neededKept = 0;
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
                boolean expectedKept = kept.stream().anyMatch(chunk ->
                        matchesAnySource(chunk.source(), question.expectedSources()));
                if (expectedKept) neededKept++;
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
