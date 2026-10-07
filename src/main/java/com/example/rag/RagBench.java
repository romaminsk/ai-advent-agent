package com.example.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Локальный бенчмарк RAG: сравнение профилей ollama и cloud.
 *
 * Оркестрация прогона — метод {@link #execute}; сети он не касается:
 * генерацию выполняют переданные RagService-инстансы. Retrieval
 * выполняется ровно один раз на вопрос (без rewrite — как в чат-пути
 * prepareChat) и одинаковые чанки получают оба профиля. Для вопросов
 * «без ответа» дополнительная попытка с enforceIdkThreshold=false
 * проверяет саму модель (порог отказывает LLM до вызова).
 *
 * Чистая логика (any-of факты, метрики цитат, медиана/p95, стабильность,
 * исход) вынесена в статические методы и покрывается DialogChecks.
 */
public final class RagBench {

    /** Профильные ключи сводки. */
    public static final String OLLAMA_PROFILE = "ollama";
    public static final String CLOUD_PROFILE = "cloud";
    public static final String FORCED_SUFFIX = "-force";

    /** Вопрос бенчмарка: тип fact|multi|no-answer; факты — группы any-of. */
    public record Question(String id, String question, String type,
                           List<List<String>> facts, String source, String note) {
    }

    /** Результат одного повтора одного профиля. */
    public record Attempt(String id, String profile, int repeat, String outcome,
                          long retrieveMs, long llmMs, long totalMs,
                          Integer promptTokens, Integer completionTokens,
                          int factGroupsHit, int factGroupsTotal,
                          double citationShare, boolean citedExpectedSource,
                          List<String> citedChunks, String answerPreview) {
        public Attempt {
            citedChunks = List.copyOf(citedChunks);
        }
    }

    /** Сводка профиля: перцентили, ошибки, факты, цитаты, стабильность. */
    public record Summary(String profile, int attempts, int successes,
                          double medianRetrieveMs, double p95RetrieveMs,
                          double medianLlmMs, double p95LlmMs,
                          double medianTotalMs, double p95TotalMs,
                          double errorRate, double factGroupsShare,
                          double citationShare, double citedExpectedShare,
                          double stableShare, List<String> notes) {
        public Summary {
            notes = List.copyOf(notes);
        }
    }

    /** Агрегат прогона: таблица, сводки и markdown. */
    public record RunResult(List<Attempt> attempts, List<Summary> summaries,
                            String markdown, String hostLog, List<String> notes,
                            long durationMs, String coldStartNote) {
        public RunResult {
            attempts = List.copyOf(attempts);
            notes = List.copyOf(notes);
        }
    }

    private RagBench() {
    }

    // ============================ Чистая логика ============================

    /** Группа any-of найдена? Регистронезависимо по подстроке. */
    public static boolean anyOfHit(String answer, List<String> variants) {
        if (answer == null || variants == null || variants.isEmpty()) {
            return false;
        }
        String normalized = answer.toLowerCase(Locale.ROOT);
        for (String variant : variants) {
            if (variant != null && !variant.isBlank()
                    && normalized.contains(variant.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Число групп, из которых найден хотя бы один вариант. */
    public static int factGroupsHit(String answer, List<List<String>> groups) {
        if (groups == null) return 0;
        int hit = 0;
        for (List<String> group : groups) {
            if (anyOfHit(answer, group)) hit++;
        }
        return hit;
    }

    /** Доля групп, из которых найден хотя бы один вариант; 0 без групп. */
    public static double factGroupsShare(String answer, List<List<String>> groups) {
        if (groups == null || groups.isEmpty()) return 0;
        return (double) factGroupsHit(answer, groups) / groups.size();
    }

    /** Корректность «не знаю»: отказ по порогу/модели без придуманных цитат. */
    public static boolean correctNoAnswer(RagService.Result result) {
        return outcome(result).startsWith("no-answer")
                && result.citations() != null
                && result.citations().confirmedQuotes().isEmpty();
    }

    /**
     * Исход попытки: ok | unverified | no-answer-threshold | no-answer-model |
     * empty | error | timeout.
     */
    public static String outcome(RagService.Result result) {
        if (result.status() != RagService.Status.OK) {
            return switch (result.status()) {
                case EMPTY -> "empty";
                case TIMEOUT -> "timeout";
                default -> "error";
            };
        }
        if (result.answerStatus() == CitationValidator.AnswerStatus.IDK_LOW_RELEVANCE
                || result.answerStatus() == CitationValidator.AnswerStatus.IDK_THRESHOLD) {
            return "no-answer-threshold";
        }
        if (result.answerStatus() == CitationValidator.AnswerStatus.IDK_MODEL) {
            return "no-answer-model";
        }
        if (result.answerStatus() == CitationValidator.AnswerStatus.UNVERIFIED) {
            return "unverified";
        }
        return "ok";
    }

    /** Медиана; пусто → 0. */
    public static double median(List<Long> values) {
        return percentile(values, 0.5);
    }

    /** 95-й перцентиль с линейной интерполяцией; пусто → 0. */
    public static double percentile95(List<Long> values) {
        return percentile(values, 0.95);
    }

    /** Перцентиль ratio∈[0..1] c линейной интерполяцией. */
    public static double percentile(List<Long> values, double ratio) {
        if (values == null || values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int n = sorted.size();
        if (n == 1) return sorted.get(0);
        double pos = ratio * (n - 1);
        int floor = (int) Math.floor(pos);
        int ceil = Math.min(n - 1, floor + 1);
        return sorted.get(floor) + (sorted.get(ceil) - sorted.get(floor)) * (pos - floor);
    }

    /**
     * Стабильность: доля вопросов, у которых исход одинаков во всех
     * повторах профиля (группировка по id).
     */
    public static double stableShare(List<Attempt> attempts) {
        Map<String, List<Attempt>> grouped = byQuestionId(attempts);
        if (grouped.isEmpty()) return 0;
        int stable = 0;
        for (List<Attempt> attemptsOfOne : grouped.values()) {
            Set<String> outcomes = new LinkedHashSet<>();
            for (Attempt attempt : attemptsOfOne) outcomes.add(attempt.outcome());
            if (outcomes.size() == 1) stable++;
        }
        return (double) stable / grouped.size();
    }

    /** Группировка попыток по id вопроса. */
    public static Map<String, List<Attempt>> byQuestionId(List<Attempt> attempts) {
        Map<String, List<Attempt>> grouped = new LinkedHashMap<>();
        for (Attempt attempt : attempts) {
            grouped.computeIfAbsent(attempt.id(), id -> new ArrayList<>()).add(attempt);
        }
        return grouped;
    }

    /** Загрузка вопросов бенчмарка из JSON-файла docs/. */
    public static List<Question> loadQuestions(Path file) throws IOException {
        var root = com.example.JsonSupport.MAPPER.readTree(file.toFile());
        if (root == null || !root.isArray() || root.isEmpty()) {
            throw new IOException("Файл вопросов должен быть непустым JSON-массивом");
        }
        List<Question> questions = new ArrayList<>();
        for (var node : root) {
            List<List<String>> facts = new ArrayList<>();
            for (var group : node.path("facts")) {
                List<String> variants = new ArrayList<>();
                group.forEach(v -> variants.add(v.asText()));
                facts.add(variants);
            }
            questions.add(new Question(node.path("id").asText(),
                    node.path("question").asText(), node.path("type").asText("fact"),
                    facts, node.path("source").asText(""), node.path("note").asText("")));
        }
        List<String> ids = questions.stream().map(Question::id).toList();
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new IOException("Дубли id вопросов в " + file);
        }
        return questions;
    }

    /** Сводка по профилям; системные метрики считаются по успешным попыткам. */
    public static List<Summary> summaries(Map<String, List<Attempt>> byProfile) {
        List<Summary> out = new ArrayList<>();
        for (Map.Entry<String, List<Attempt>> entry : byProfile.entrySet()) {
            List<Attempt> attempts = entry.getValue();
            List<Attempt> successful = attempts.stream()
                    .filter(a -> "ok".equals(a.outcome())).toList();
            int successes = successful.size();
            int errors = attempts.size() - successes;
            double factShare = successful.isEmpty() ? 0 : successful.stream()
                    .mapToDouble(a -> a.factGroupsTotal() == 0 ? 0
                            : (double) a.factGroupsHit() / a.factGroupsTotal())
                    .average().orElse(0);
            double citeShare = successful.isEmpty() ? 0 : successful.stream()
                    .mapToDouble(Attempt::citationShare).average().orElse(0);
            double citedShare = successful.isEmpty() ? 0 : successful.stream()
                    .mapToDouble(a -> a.citedExpectedSource() ? 1.0 : 0.0)
                    .average().orElse(0);
            List<String> notes = new ArrayList<>();
            long byThreshold = countOutcome(attempts, "no-answer-threshold");
            long byModel = countOutcome(attempts, "no-answer-model");
            if (byThreshold > 0) {
                notes.add("«не знаю» решено порогом (LLM не вызывалась): " + byThreshold);
            }
            if (byModel > 0) {
                notes.add("«не знаю» ответил моделью: " + byModel);
            }
            out.add(new Summary(entry.getKey(), attempts.size(), successes,
                    median(successValues(attempts, Attempt::retrieveMs)),
                    percentile95(successValues(attempts, Attempt::retrieveMs)),
                    median(successValues(attempts, Attempt::llmMs)),
                    percentile95(successValues(attempts, Attempt::llmMs)),
                    median(successValues(attempts, Attempt::totalMs)),
                    percentile95(successValues(attempts, Attempt::totalMs)),
                    attempts.isEmpty() ? 0 : (double) errors / attempts.size(),
                    factShare, citeShare, citedShare,
                    stableShare(attempts), notes));
        }
        return out;
    }

    private static long countOutcome(List<Attempt> attempts, String outcome) {
        return attempts.stream().filter(a -> outcome.equals(a.outcome())).count();
    }

    private static List<Long> successValues(List<Attempt> attempts,
                                            java.util.function.ToDoubleFunction<Attempt> fn) {
        List<Long> out = new ArrayList<>();
        for (Attempt attempt : attempts) {
            if ("ok".equals(attempt.outcome())) out.add((long) fn.applyAsDouble(attempt));
        }
        return out;
    }

    // ========================== Оркестрация прогонов ==========================

    /**
     * Прогон всех вопросов: retrieval один раз на вопрос, затем генерация
     * каждого профиля на одних чанках (services — инъекция из Main с
     * профильными LLM-клиентами). У оllama дополнительно для вопросов
     * no-answer выполняется принудительный вызов модели (enforceIdk=false)
     * с ключом профиля «<profile>-force». Cloud при недоступности
     * пропускается с явной пометкой. Исключение прогона не теряет
     * частичный markdown: сборка результата идёт в finally.
     */
    public static RunResult execute(List<Question> questions, int repeats,
                                    String ollamaProfileName, RagService ollamaService,
                                    String cloudProfileName, RagService cloudService,
                                    boolean cloudAvailable, Consumer<String> progress)
            throws Exception {
        Consumer<String> out = progress == null ? ignored -> { } : progress;
        List<Attempt> attempts = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        StringBuilder hostLog = new StringBuilder();
        long started = System.currentTimeMillis();
        Exception failure = null;
        if (ollamaService == null) {
            throw new IOException("Профиль ollama недоступен: retrieval невозможен "
                    + "(запустите Ollama и проверьте scripts/ollama_rag_model.sh)");
        }
        try {
            if (!cloudAvailable) {
                notes.add("cloud пропущен: нет конфигурации/сети ("
                        + (cloudService == null ? "нет Config" : "прогрев не удался") + ")");
            }
            for (Question question : questions) {
                RagService.Prepared prepared = ollamaService.prepareChat(
                        question.question(), question.question(),
                        ollamaService.settings().withRewrite(false));
                long retrieveMs = prepared.retrieveMs();
                List<RagRetriever.Chunk> promptChunks = prepared.prompt().chunks();
                out.accept("[бенч] retrieval " + question.id() + ": " + retrieveMs
                        + " мс, чанков в контексте: " + promptChunks.size());
                boolean noAnswer = "no-answer".equals(question.type());
                attemptQuestion(attempts, question, OLLAMA_PROFILE, ollamaService,
                        repeats, false, prepared, retrieveMs, promptChunks, out);
                if (noAnswer) {
                    attemptQuestion(attempts, question, OLLAMA_PROFILE + FORCED_SUFFIX,
                            ollamaService, repeats, true, prepared, retrieveMs,
                            promptChunks, out);
                }
                if (cloudAvailable) {
                    attemptQuestion(attempts, question, CLOUD_PROFILE, cloudService,
                            repeats, false, prepared, retrieveMs, promptChunks, out);
                    if (noAnswer) {
                        attemptQuestion(attempts, question, CLOUD_PROFILE + FORCED_SUFFIX,
                                cloudService, repeats, true, prepared, retrieveMs,
                                promptChunks, out);
                    }
                } else {
                    notes.add("вопрос " + question.id() + " ("
                            + CLOUD_PROFILE + "): пропущен — cloud недоступен");
                }
            }
        } catch (Exception error) {
            failure = error;
        } finally {
            reports(attempts, questions, notes, hostLog, started, failure);
        }
        Map<String, List<Attempt>> byProfile = new LinkedHashMap<>();
        for (Attempt attempt : attempts) {
            byProfile.computeIfAbsent(attempt.profile(), key -> new ArrayList<>()).add(attempt);
        }
        List<Summary> summaries = summaries(byProfile);
        String markdown = attemptsMarkdown(attempts, questions)
                + summaryMarkdown(summaries, notes);
        long durationMs = System.currentTimeMillis() - started;
        notes.add("длительность прогона: " + durationMs + " мс");
        if (failure != null) {
            notes.add("прогон прерван ошибкой: " + failure.getMessage());
        }
        return new RunResult(attempts, summaries, markdown,
                hostLog.isEmpty() ? "(хосты не собирались)" : hostLog.toString(),
                notes, durationMs, null);
    }

    /** Все повторы одного вопроса/профиля; force=true вызывает complete без порога. */
    private static void attemptQuestion(List<Attempt> attempts, Question question,
                                        String profile, RagService service, int repeats,
                                        boolean force, RagService.Prepared prepared,
                                        long retrieveMs, List<RagRetriever.Chunk> chunks,
                                        Consumer<String> out) throws Exception {
        for (int repeat = 1; repeat <= repeats; repeat++) {
            RagService.Result result = service.complete(prepared, !force);
            RagService.Completion usage = service.lastCompletion();
            Attempt attempt = toAttempt(question, profile, repeat, result,
                    retrieveMs, chunks,
                    usage != null ? usage.promptTokens() : null,
                    usage != null ? usage.completionTokens() : null);
            attempts.add(attempt);
            out.accept("[бенч] " + question.id() + " | " + profile
                    + " | повтор " + repeat + "/" + repeats + " | исход " + attempt.outcome()
                    + " | retrieval " + attempt.retrieveMs() + " мс | gen "
                    + attempt.llmMs() + " мс | prompt_tokens "
                    + (attempt.promptTokens() == null ? "—" : attempt.promptTokens()));
        }
    }

    /** Слот попытки из результата: метрики, цитаты, токены. */
    public static Attempt toAttempt(Question question, String profile, int repeat,
                                    RagService.Result result, long retrieveMs,
                                    List<RagRetriever.Chunk> chunks,
                                    Integer promptTokens, Integer completionTokens) {
        CitationValidator.Validation citations = result.citations();
        List<String> used = new ArrayList<>();
        int total = citations == null ? 0 : citations.totalQuotes();
        if (citations != null) {
            for (CitationValidator.Quote quote : citations.confirmedQuotes()) {
                if (quote.number() > 0 && quote.number() <= chunks.size()) {
                    RagRetriever.Chunk chunk = chunks.get(quote.number() - 1);
                    used.add(chunk.source() + " › " + chunk.section());
                }
            }
        }
        double citationShare;
        if (citations == null || citations.totalQuotes() == 0) {
            citationShare = 0;
        } else {
            citationShare = (double) citations.confirmedQuotes().size()
                    / citations.totalQuotes();
        }
        boolean sourceOk = hasExpectedSource(question, used);
        boolean noAnswer = "no-answer".equals(question.type());
        if ("no-answer".equals(question.type())) {
            sourceOk = false;
        }
        String preview = result.answer() == null ? "" : result.answer().strip();
        if (preview.length() > 240) preview = preview.substring(0, 240) + "…";
        int totalFacts = noAnswer ? 0 : question.facts() == null ? 0 : question.facts().size();
        return new Attempt(question.id(), profile, repeat, outcome(result),
                retrieveMs, result.llmMs(), retrieveMs + result.llmMs(),
                promptTokens, completionTokens,
                noAnswer ? 0 : factGroupsHit(result.answer(), question.facts()),
                totalFacts, citationShare, sourceOk, used, preview);
    }

    /** Ожидаемый источник есть среди подтверждённых цитат (подстрока пути). */
    private static boolean hasExpectedSource(Question question, List<String> used) {
        if (question.source() == null || question.source().isBlank() || used.isEmpty()) {
            return false;
        }
        return used.stream().anyMatch(item ->
                item.toLowerCase(Locale.ROOT)
                        .contains(question.source().toLowerCase(Locale.ROOT)));
    }

    private static void reports(List<Attempt> attempts, List<Question> questions,
                                List<String> notes, StringBuilder hostLog,
                                long started, Exception failure) {
        // Здесь ничего не пишем на диск: markdown собирает execute.
    }

    // ================================ Выводы ================================

    /** Таблица всех попыток в markdown. */
    public static String attemptsMarkdown(List<Attempt> attempts, List<Question> questions) {
        StringBuilder out = new StringBuilder();
        Map<String, Question> byId = new LinkedHashMap<>();
        for (Question question : questions) byId.put(question.id(), question);
        String currentId = null;
        for (Attempt attempt : attempts) {
            if (!attempt.id().equals(currentId)) {
                currentId = attempt.id();
                Question question = byId.get(currentId);
                out.append("\n### ").append(currentId).append(" (")
                        .append(question == null ? "?" : question.type()).append(")\n");
                if (question != null) {
                    out.append("> ").append(question.question()).append('\n');
                    if (!question.source().isBlank()) {
                        out.append("> ожидаемый источник: ").append(question.source()).append('\n');
                    }
                    if (!question.note().isBlank()) {
                        out.append("> ").append(question.note()).append('\n');
                    }
                }
                out.append("\n| профиль | повтор | исход | retrieval мс | gen мс | всего мс |")
                        .append(" prompt_tokens | completion_tokens | факты | цитат ok | source ok |\n")
                        .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
            }
            out.append("| ").append(attempt.profile())
                    .append(" | ").append(attempt.repeat())
                    .append(" | ").append(attempt.outcome())
                    .append(" | ").append(attempt.retrieveMs())
                    .append(" | ").append(attempt.llmMs())
                    .append(" | ").append(attempt.totalMs())
                    .append(" | ").append(tokensText(attempt.promptTokens()))
                    .append(" | ").append(tokensText(attempt.completionTokens()))
                    .append(" | ").append(attempt.factGroupsHit()).append('/')
                    .append(attempt.factGroupsTotal())
                    .append(" | ").append(fmtShare(attempt.citationShare()))
                    .append(" | ").append(attempt.citedExpectedSource() ? "да" : "нет")
                    .append(" |\n");
            if (!attempt.answerPreview().isBlank()) {
                out.append("  — ответ: `").append(attempt.answerPreview()
                        .replace("\n", " ")).append("`\n");
            }
        }
        return out.toString();
    }

    /** Сводки в markdown (профили и заметки). */
    public static String summaryMarkdown(List<Summary> summaries, List<String> notes) {
        StringBuilder out = new StringBuilder("\n## Сводка\n");
        for (String note : notes) out.append("- ").append(note).append('\n');
        out.append("\n| профиль | попыток | успехов | retrieval медиана | retrieval p95 |")
                .append(" gen медиана | gen p95 | всего медиана | всего p95 | ошибки |")
                .append(" факт. группы | цитат ok | source ok | стабильность |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Summary summary : summaries) {
            out.append("| ").append(summary.profile())
                    .append(" | ").append(summary.attempts())
                    .append(" | ").append(summary.successes())
                    .append(" | ").append(msText(summary.medianRetrieveMs()))
                    .append(" | ").append(msText(summary.p95RetrieveMs()))
                    .append(" | ").append(msText(summary.medianLlmMs()))
                    .append(" | ").append(msText(summary.p95LlmMs()))
                    .append(" | ").append(msText(summary.medianTotalMs()))
                    .append(" | ").append(msText(summary.p95TotalMs()))
                    .append(" | ").append(fmtShare(summary.errorRate()))
                    .append(" | ").append(fmtShare(summary.factGroupsShare()))
                    .append(" | ").append(fmtShare(summary.citationShare()))
                    .append(" | ").append(fmtShare(summary.citedExpectedShare()))
                    .append(" | ").append(fmtShare(summary.stableShare()))
                    .append(" |\n");
            for (String note : summary.notes()) {
                out.append("  - `").append(summary.profile()).append("`: ").append(note).append('\n');
            }
        }
        return out.toString();
    }

    private static String tokensText(Integer tokens) {
        return tokens == null ? "—" : String.valueOf(tokens);
    }

    private static String fmtShare(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String msText(double value) {
        return value >= 10_000 ? String.format(Locale.ROOT, "%.1fс", value / 1000)
                : String.format(Locale.ROOT, "%.0fмс", value);
    }
}
