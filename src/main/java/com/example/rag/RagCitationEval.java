package com.example.rag;

import com.example.index.Embedder;
import com.example.index.IndexSearch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Resumable quality report for source links, verbatim citations, and the no-answer trap. */
public final class RagCitationEval {
    public record Report(List<RagCitationEvalCheckpointStore.Row> rows, String markdown,
                         int answersWithSources, int answersWithQuotes,
                         double confirmedQuoteFraction, double averageCosine,
                         boolean trapHandled, Path checkpoint) {
        public Report { rows = List.copyOf(rows); }
    }

    private RagCitationEval() { }

    public static Path reportPath(Path home, java.time.LocalDate date) {
        return home.resolve(".ai-advent-agent").resolve("rag-results")
                .resolve("rag-citations-eval-" + date + ".md");
    }

    public static Report run(RagService service, Embedder embedder, RagSettings settings,
                             Path checkpointPath, Consumer<String> progress,
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
            RagService.Result result = service.ask(question.question(), RagService.Mode.ON,
                    citeSettings, null);
            RagCitationEvalCheckpointStore.Row row = row(question, result, embedder);
            checkpoint.put(row);
            Report report = report(questions, checkpoint, checkpointPath);
            snapshot.accept(report);
            progress.accept("[" + (i + 1) + "/" + questions.size() + "] " + question.id()
                    + " | " + row.status() + " | цитаты " + row.confirmedCount() + "/"
                    + row.citationCount());
        }
        return report(questions, checkpoint, checkpointPath);
    }

    private static RagCitationEvalCheckpointStore.Row row(RagEval.Question question,
                                                           RagService.Result result,
                                                           Embedder embedder) throws IOException {
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
                || result.answerStatus() == CitationValidator.AnswerStatus.IDK_LOW_RELEVANCE;
        boolean idkCorrect = question.noAnswerExpected() == refusal;
        return new RagCitationEvalCheckpointStore.Row(question.id(), result.answerStatus(), sources,
                result.citations().totalQuotes(), result.citations().confirmedQuotes().size(),
                cosine, RagEval.factsHit(answer, question.expected()), idkCorrect);
    }

    private static Report report(List<RagEval.Question> questions,
                                 RagCitationEvalCheckpointStore checkpoint, Path checkpointPath) {
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
        RagEval.Question trap = questions.stream().filter(RagEval.Question::noAnswerExpected)
                .findFirst().orElseThrow();
        RagCitationEvalCheckpointStore.Row trapRow = checkpoint.row(trap.id());
        boolean trapHandled = trapRow != null && trapRow.idkCorrect();
        StringBuilder out = new StringBuilder("# RAG citation eval — ")
                .append(java.time.LocalDate.now()).append("\n\n")
                .append("Checkpoint: ").append(checkpointPath).append("\n")
                .append("Cosine — эвристика текстового сходства; смысловое совпадение проверяет человек.\n\n")
                .append("| id | статус | источники | цитат всего | подтверждено | доля | cosine (ответ/цитаты) | ожидаемых фактов найдено | «не знаю» корректно? | смысл совпадает (ручная) |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|:---:|---|");
        for (int i = 0; i < questions.size(); i++) {
            RagEval.Question question = questions.get(i);
            RagCitationEvalCheckpointStore.Row row = checkpoint.row(question.id());
            out.append("\n| ").append(question.id()).append(" | ");
            if (row == null) {
                out.append("pending | — | 0 | 0 | — | — | 0 | — |  | ");
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
                    .append(" | ").append(row.expectedFactsFound()).append(" | ")
                    .append(row.idkCorrect() ? "да" : "нет").append(" |  | ");
        }
        out.append("\n\n## Итог\n\n")
                .append("- Ответов с источниками: ").append(withSources).append('/').append(rows.size()).append(".\n")
                .append("- Ответов с цитатами: ").append(withQuotes).append('/').append(rows.size()).append(".\n")
                .append("- Средняя доля подтверждённых цитат: ")
                .append(String.format(Locale.ROOT, "%.1f%%", quoteFraction * 100)).append(" (")
                .append(confirmed).append('/').append(totalQuotes).append(").\n")
                .append("- Средний cosine: ").append(cosines.isEmpty() ? "н/д"
                        : String.format(Locale.ROOT, "%.4f", averageCosine)).append(".\n")
                .append("- Ловушка обработана: ").append(trapHandled ? "да" : "нет").append(".\n")
                .append("\nВ таблице оставлена пустой колонка ручной проверки смыслового совпадения.");
        return new Report(rows, out.toString(), withSources, withQuotes, quoteFraction,
                averageCosine, trapHandled, checkpointPath);
    }
}
