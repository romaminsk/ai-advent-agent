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
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Fixed-question comparison and deterministic lexical metrics. */
public final class RagEval {
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
                      boolean offInvented, boolean onDeclined, String verdict) {
    }

    public record Report(List<Row> rows, int factsOff, int factsOn,
                         int sourceHits, int sourceQuestions, int wins, String markdown) {
        public Report {
            rows = List.copyOf(rows);
        }

        public double recallAt5() {
            return sourceQuestions == 0 ? 0 : (double) sourceHits / sourceQuestions;
        }

        public List<String> summaryLines() {
            long retrievalMisses = rows.stream().filter(row -> !row.question().noAnswerExpected()
                    && !row.sourceHit()).count();
            long ignored = rows.stream().filter(row -> !row.question().noAnswerExpected()
                    && row.sourceHit() && row.factsOn() <= row.factsOff()).count();
            return List.of(
                    "Факты: без RAG " + factsOff + ", с RAG " + factsOn
                            + "; RAG выиграл " + wins + "/" + rows.size() + " вопросов.",
                    String.format(Locale.ROOT, "Retrieval recall@5: %d/%d (%.0f%%).",
                            sourceHits, sourceQuestions, recallAt5() * 100),
                    "Промахи поиска: " + retrievalMisses
                            + "; найденный контекст не улучшил факты: " + ignored + ".",
                    "Для вопроса без ответа: on "
                            + (rows.stream().anyMatch(row -> row.question().noAnswerExpected()
                            && row.onDeclined()) ? "отказал" : "не отказал")
                            + ", off " + (rows.stream().anyMatch(row -> row.question().noAnswerExpected()
                            && row.offInvented()) ? "выдал ответ" : "не выдумал") + ".");
        }
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

    public static Report run(RagService service) throws Exception {
        List<Row> rows = new ArrayList<>();
        for (Question question : loadQuestions()) {
            RagService.Result off = service.ask(question.question(), RagService.Mode.OFF);
            RagService.Result on = service.ask(question.question(), RagService.Mode.ON);
            int factsOff = factsHit(off.answer(), question.expected());
            int factsOn = factsHit(on.answer(), question.expected());
            boolean sourceHit = question.noAnswerExpected()
                    || sourceMatch(on.retrievedChunks(), question.expectedSources());
            boolean cited = question.noAnswerExpected()
                    || citedExpectedSource(on.answer(), question.expectedSources());
            boolean offInvented = question.noAnswerExpected() && !saysNoAnswer(off.answer());
            boolean onDeclined = question.noAnswerExpected() && saysNoAnswer(on.answer());
            String verdict;
            if (question.noAnswerExpected()) {
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
            rows.add(new Row(question, off, on, factsOff, factsOn, sourceHit, cited,
                    offInvented, onDeclined, verdict));
        }
        int factsOff = rows.stream().mapToInt(Row::factsOff).sum();
        int factsOn = rows.stream().mapToInt(Row::factsOn).sum();
        int sourceQuestions = (int) rows.stream().filter(row -> !row.question().noAnswerExpected()).count();
        int sourceHits = (int) rows.stream().filter(row -> !row.question().noAnswerExpected()
                && row.sourceHit()).count();
        int wins = (int) rows.stream().filter(row -> row.verdict().startsWith("RAG помог")).count();
        Report report = new Report(rows, factsOff, factsOn, sourceHits, sourceQuestions, wins, "");
        return new Report(rows, factsOff, factsOn, sourceHits, sourceQuestions, wins,
                markdown(report));
    }

    public static int factsHit(String answer, List<String> expected) {
        String normalized = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        return (int) expected.stream().filter(fact -> !fact.isBlank()
                && normalized.contains(fact.toLowerCase(Locale.ROOT))).count();
    }

    public static boolean sourceMatch(List<RagRetriever.Chunk> chunks, String expectedSources) {
        if (expectedSources == null || expectedSources.isBlank()) {
            return false;
        }
        return chunks.stream().anyMatch(chunk -> matchesAnySource(chunk.source(), expectedSources));
    }

    public static boolean citedExpectedSource(String answer, String expectedSources) {
        if (answer == null || expectedSources == null || expectedSources.isBlank()) {
            return false;
        }
        String normalized = answer.toLowerCase(Locale.ROOT);
        for (String source : expectedSources.split("\\|")) {
            String candidate = source.trim().toLowerCase(Locale.ROOT);
            String basename = candidate.substring(candidate.lastIndexOf('/') + 1);
            if (!candidate.isEmpty() && (normalized.contains(candidate)
                    || normalized.contains(basename))) {
                return true;
            }
        }
        return false;
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
        StringBuilder out = new StringBuilder("# RAG eval — ").append(LocalDate.now()).append("\n\n");
        out.append("| # | вопрос | ожидание | источники ожидаемые/найденные | факты off | факты on | cited | вердикт |\n")
                .append("|---:|---|---|---|---:|---:|:---:|---|\n");
        for (int i = 0; i < report.rows().size(); i++) {
            Row row = report.rows().get(i);
            String found = row.on().retrievedChunks().stream().map(RagRetriever.Chunk::source)
                    .distinct().collect(Collectors.joining(", "));
            out.append("| ").append(i + 1).append(" | ").append(table(row.question().question()))
                    .append(" | ").append(table(String.join(", ", row.question().expected())))
                    .append(" | ").append(table((row.question().expectedSources().isBlank()
                            ? "нет" : row.question().expectedSources()) + " / "
                            + (found.isBlank() ? "нет" : found)))
                    .append(" | ").append(row.factsOff()).append(" | ").append(row.factsOn())
                    .append(" | ").append(row.question().noAnswerExpected() ? "—" : row.cited() ? "да" : "нет")
                    .append(" | ").append(table(row.verdict())).append(" |\n");
        }
        out.append("\nИтого: факты off — ").append(report.factsOff())
                .append(", on — ").append(report.factsOn()).append("; retrieval recall@5 — ")
                .append(report.sourceHits()).append('/').append(report.sourceQuestions())
                .append(String.format(Locale.ROOT, " (%.0f%%); вопросов, выигранных RAG — %d/%d.\n",
                        report.recallAt5() * 100, report.wins(), report.rows().size()));
        out.append("\n## Вывод\n\n");
        report.summaryLines().forEach(line -> out.append("- ").append(line).append('\n'));
        for (int i = 0; i < report.rows().size(); i++) {
            Row row = report.rows().get(i);
            out.append("\n## ").append(i + 1).append(". ").append(row.question().id())
                    .append(" — ").append(row.question().question()).append("\n\n")
                    .append("Ожидание: ").append(row.question().note()).append("\n\n")
                    .append("Найденные источники: ")
                    .append(row.on().retrievedChunks().stream().map(RagRetriever.Chunk::source)
                            .distinct().collect(Collectors.joining(", "))).append("\n\n")
                    .append("### Без RAG\n\n").append(fenced(row.off().answer()))
                    .append("\n\n### С RAG\n\n").append(fenced(row.on().answer()))
                    .append("\n\nВремя retrieve/LLM: ").append(row.on().retrieveMs())
                    .append("/").append(row.on().llmMs()).append(" мс.\n");
        }
        return out.toString();
    }

    private static String table(String value) {
        return value.replace("|", "\\|").replace('\n', ' ');
    }

    private static String fenced(String value) {
        return "```text\n" + (value == null ? "" : value.replace("```", "'''")) + "\n```";
    }

    public static Path reportPath(Path home, LocalDate date) {
        return home.resolve(".ai-advent-agent").resolve("rag-results")
                .resolve("rag-eval-" + date + ".md");
    }

    public static Path artifactPath(Path project, LocalDate date) {
        return project.resolve("artifacts").resolve("rag-eval-" + date + ".md");
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
