package com.example.rag;

import com.example.index.Embedder;
import com.example.index.IndexSearch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Resumable quality report for source links, verbatim citations, and the no-answer trap. */
public final class RagCitationEval {
    public record Report(List<RagCitationEvalCheckpointStore.Row> rows, String markdown,
                         int answersWithSources, int answersWithQuotes,
                         int answeredCount, int answerableTotal,
                         double confirmedQuoteFraction, double averageCosine,
                         boolean trapHandled, long totalDurationMs, Path checkpoint) {
        public Report { rows = List.copyOf(rows); }
    }

    private RagCitationEval() { }

    public static Path reportPath(Path home, java.time.LocalDate date) {
        return home.resolve(".ai-advent-agent").resolve("rag-results")
                .resolve("rag-citations-eval-" + date + ".md");
    }

    public static Report run(RagService service, Embedder embedder, RagSettings settings,
                             Path checkpointPath, String runParameters, Consumer<String> progress,
                             Consumer<Report> snapshot) throws IOException {
        List<RagEval.Question> questions = RagEval.loadQuestions();
        RagCitationEvalCheckpointStore checkpoint = new RagCitationEvalCheckpointStore(checkpointPath);
        if (checkpoint.exists()) checkpoint.load();
        RagSettings citeSettings = settings.withRewrite(false);
        for (int i = 0; i < questions.size(); i++) {
            RagEval.Question question = questions.get(i);
            if (checkpoint.row(question.id()) != null) {
                progress.accept(question.id() + " — уже в checkpoint, пропуск");
                continue;
            }
            long started = System.nanoTime();
            RagService.Result result = service.ask(question.question(), RagService.Mode.ON,
                    citeSettings, null);
            long durationMs = RagService.elapsedMs(started);
            RagCitationEvalCheckpointStore.Row row = row(question, result, embedder, durationMs);
            checkpoint.put(row);
            Report report = report(questions, checkpoint, checkpointPath, runParameters);
            snapshot.accept(report);
            logQuestion(progress, i + 1, questions.size(), result, row);
        }
        return report(questions, checkpoint, checkpointPath, runParameters);
    }

    /** Per-question log: status, threshold-comparable top-1, raw answer, confirmed/rejected quotes. */
    private static void logQuestion(Consumer<String> progress, int number, int total,
                                    RagService.Result result,
                                    RagCitationEvalCheckpointStore.Row row) {
        progress.accept("[" + number + "/" + total + "] " + row.id() + " | " + row.status()
                + " | top-1 " + String.format(Locale.ROOT, "%.6f", row.top1Score())
                + " | цитаты " + row.confirmedCount() + "/" + row.citationCount()
                + " | " + String.format(Locale.ROOT, "%.1f с", row.durationMs() / 1000.0));
        String raw = result.answer();
        if (result.status() != RagService.Status.OK || raw == null || raw.isBlank()) {
            progress.accept("  сырой ответ: нет (" + result.status().name().toLowerCase(Locale.ROOT)
                    + (result.error() == null ? "" : ": " + result.error()) + ")");
        } else {
            String preview = raw.strip();
            if (preview.length() > 400) preview = preview.substring(0, 400) + "…";
            progress.accept("  сырой ответ: " + preview.replaceAll("\\n", " ⏎ "));
        }
        if (row.citationCount() == 0 && row.rejectedCitations().isEmpty()) {
            progress.accept("  цитат нет");
        } else {
            progress.accept("  подтверждены: " + (row.confirmedCount() == 0 ? "нет" : row.confirmedCount())
                    + "; отброшены: " + (row.rejectedCitations().isEmpty() ? "нет"
                    : row.rejectedCitations().stream().map(citation -> "[" + citation.number() + "] "
                    + citation.reason()).collect(java.util.stream.Collectors.joining(", "))));
        }
    }

    private static RagCitationEvalCheckpointStore.Row row(RagEval.Question question,
                                                           RagService.Result result,
                                                           Embedder embedder,
                                                           long durationMs) throws IOException {
        String answer = result.answerStatus() == CitationValidator.AnswerStatus.UNVERIFIED
                ? CitationValidator.answerText(result.answer()) : result.answer();
        List<String> quotes = result.citations().confirmedQuotes().stream()
                .map(CitationValidator.Quote::text).toList();
        Double cosine = null;
        if (!answer.isBlank() && !quotes.isEmpty()) {
            try {
                List<float[]> vectors = embedder.embed(List.of(answer, String.join("\n", quotes)));
                if (vectors.size() == 2) cosine = IndexSearch.cosine(vectors.get(0), vectors.get(1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Прервано получение эмбеддингов citation eval", interrupted);
            }
        }
        List<String> sources = new ArrayList<>();
        for (int number : result.citations().validReferences().stream().sorted().toList()) {
            if (number <= result.chunks().size()) {
                RagRetriever.Chunk chunk = result.chunks().get(number - 1);
                String value = chunk.source() + " — " + chunk.section() + " (chunk: " + chunk.chunkId() + ")";
                sources.add(RagQueryRewriter.looksSecret(value) ? "[скрытый источник]"
                        : RagQueryRewriter.redactSecrets(value));
            }
        }
        boolean refusal = result.answerStatus() == CitationValidator.AnswerStatus.IDK_MODEL
                || result.answerStatus() == CitationValidator.AnswerStatus.IDK_LOW_RELEVANCE
                || result.answerStatus() == CitationValidator.AnswerStatus.IDK_THRESHOLD;
        boolean idkCorrect = question.noAnswerExpected() == refusal;
        double top1Score = result.retrievedChunks().stream()
                .mapToDouble(RagRetriever.Chunk::score).max().orElse(0);
        return new RagCitationEvalCheckpointStore.Row(question.id(), result.answerStatus(), sources,
                result.citations().totalQuotes(), result.citations().confirmedQuotes().size(),
                cosine, RagEval.factsHit(answer, question.expected()), question.expected().size(),
                idkCorrect, top1Score, durationMs, result.citations().rejectedQuoteDetails());
    }

    private static Report report(List<RagEval.Question> questions,
                                 RagCitationEvalCheckpointStore checkpoint, Path checkpointPath,
                                 String runParameters) {
        List<RagCitationEvalCheckpointStore.Row> rows = questions.stream()
                .map(question -> checkpoint.row(question.id())).filter(java.util.Objects::nonNull).toList();
        int withSources = (int) rows.stream().filter(row -> !row.sources().isEmpty()).count();
        int withQuotes = (int) rows.stream().filter(row -> row.citationCount() > 0).count();
        int totalQuotes = rows.stream().mapToInt(RagCitationEvalCheckpointStore.Row::citationCount).sum();
        int confirmed = rows.stream().mapToInt(RagCitationEvalCheckpointStore.Row::confirmedCount).sum();
        double quoteFraction = totalQuotes == 0 ? 0 : (double) confirmed / totalQuotes;
        List<Double> cosines = rows.stream().map(RagCitationEvalCheckpointStore.Row::cosine)
                .filter(java.util.Objects::nonNull).toList();
        double averageCosine = cosines.isEmpty() ? 0 : cosines.stream().mapToDouble(Double::doubleValue)
                .average().orElse(0);
        int answerableTotal = (int) questions.stream()
                .filter(question -> !question.noAnswerExpected()).count();
        int answeredCount = (int) questions.stream()
                .filter(question -> !question.noAnswerExpected())
                .filter(question -> checkpoint.row(question.id()) != null && checkpoint.row(question.id())
                        .status() == CitationValidator.AnswerStatus.ANSWERED).count();
        RagEval.Question trap = questions.stream().filter(RagEval.Question::noAnswerExpected)
                .findFirst().orElseThrow();
        RagCitationEvalCheckpointStore.Row trapRow = checkpoint.row(trap.id());
        boolean trapHandled = trapRow != null && trapRow.idkCorrect();
        long totalDurationMs = rows.stream()
                .mapToLong(RagCitationEvalCheckpointStore.Row::durationMs).sum();
        Map<CitationValidator.AnswerStatus, Long> statusCounts = new java.util.LinkedHashMap<>();
        for (CitationValidator.AnswerStatus status : CitationValidator.AnswerStatus.values()) {
            statusCounts.put(status, rows.stream().filter(row -> row.status() == status).count());
        }
        StringBuilder out = new StringBuilder("# RAG citation eval — ")
                .append(java.time.LocalDate.now()).append("\n\n")
                .append("Checkpoint: ").append(checkpointPath).append("\n")
                .append("Параметры прогона: ").append(runParameters == null ? "н/д" : runParameters)
                .append("\n")
                .append("Cosine — эвристика текстового сходства; смысловое совпадение проверяет человек.\n\n")
                .append("| id | статус | источники | цитат всего | подтверждено | доля | cosine (ответ/цитаты) | факты найдено | top-1 | длительность, с | «не знаю» корректно? | смысл совпадает (ручная) |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|:---:|---|");
        for (int i = 0; i < questions.size(); i++) {
            RagEval.Question question = questions.get(i);
            RagCitationEvalCheckpointStore.Row row = checkpoint.row(question.id());
            out.append("\n| ").append(question.id()).append(" | ");
            if (row == null) {
                out.append("pending | — | 0 | 0 | — | — | 0/").append(question.expected().size())
                        .append(" | — | — | — |  | ");
                continue;
            }
            out.append(row.status()).append(" | ")
                    .append(row.sources().isEmpty() ? "—" : row.sources().stream()
                            .map(source -> source.replace("|", "\\|"))
                            .collect(java.util.stream.Collectors.joining("<br>")))
                    .append(" | ").append(row.citationCount()).append(" | ")
                    .append(row.confirmedCount()).append(" | ")
                    .append(row.citationCount() == 0 ? "—" : String.format(Locale.ROOT, "%.1f%%",
                            100.0 * row.confirmedCount() / row.citationCount()))
                    .append(" | ").append(row.cosine() == null ? "—"
                            : String.format(Locale.ROOT, "%.4f", row.cosine()))
                    .append(" | ").append(row.expectedFactsFound()).append('/')
                    .append(row.expectedFactsTotal())
                    .append(" | ").append(String.format(Locale.ROOT, "%.6f", row.top1Score()))
                    .append(" | ").append(String.format(Locale.ROOT, "%.1f", row.durationMs() / 1000.0))
                    .append(" | ")
                    .append(row.idkCorrect() ? "да" : "нет").append(" |  | ");
        }
        out.append("\n\n## Сводка статусов (из ").append(rows.size()).append(")\n\n");
        statusCounts.forEach((status, count) -> out.append("- ").append(status).append(": ")
                .append(count).append('\n'));
        out.append("\n## Отброшенные цитаты\n\n");
        boolean anyRejected = rows.stream().anyMatch(row -> !row.rejectedCitations().isEmpty());
        if (anyRejected) {
            out.append("| id | цитата | причина | текст (до 200 символов) |\n|---|---|---|---|\n");
            for (RagCitationEvalCheckpointStore.Row row : rows) {
                for (CitationValidator.RejectedQuote citation : row.rejectedCitations()) {
                    String text = citation.text().replace("|", "\\|").replace("\n", " ");
                    if (text.length() > 200) text = text.substring(0, 200) + "…";
                    out.append("| ").append(row.id()).append(" | ").append(citation.number())
                            .append(" | ").append(citation.reason()).append(" | ")
                            .append(text).append(" |\n");
                }
            }
        } else {
            out.append("Отброшенных цитат нет.\n");
        }
        out.append("\n## Итог\n\n")
                .append("- Отвечено: ").append(answeredCount).append('/').append(answerableTotal)
                .append(" из имеющих ответ.\n")
                .append("- Ответов с источниками: ").append(withSources).append('/').append(rows.size()).append(".\n")
                .append("- Ответов с цитатами: ").append(withQuotes).append('/').append(rows.size()).append(".\n")
                .append("- Средняя доля подтверждённых цитат: ")
                .append(String.format(Locale.ROOT, "%.1f%%", quoteFraction * 100)).append(" (")
                .append(confirmed).append('/').append(totalQuotes).append(").\n")
                .append("- Средний cosine: ").append(cosines.isEmpty() ? "н/д"
                        : String.format(Locale.ROOT, "%.4f", averageCosine)).append(".\n")
                .append("- Ловушка: ").append(trapHandled ? "да" : "нет")
                .append(" (корректный отказ на вопросе без ответа).\n")
                .append("- Длительность: всего ")
                .append(String.format(Locale.ROOT, "%.1f с", totalDurationMs / 1000.0))
                .append("; по вопросам — в таблице.\n")
                .append("\nВ таблице оставлена пустой колонка ручной проверки смыслового совпадения.");
        return new Report(rows, out.toString(), withSources, withQuotes, answeredCount,
                answerableTotal, quoteFraction, averageCosine, trapHandled, totalDurationMs,
                checkpointPath);
    }
}
