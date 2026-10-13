package com.example.review;

import com.example.rag.RagConstants;
import com.example.rag.RagQueryRewriter;
import com.example.rag.RagRetriever;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Оркестрация ревью: план файлов под лимит diff, RAG, вызов LLM с повторами и запасной вывод. */
public final class ReviewRunner {

    public static final int MAX_RETRIES = 2;
    public static final long LLM_TIMEOUT_SECONDS = 60;
    /** Общий потолок всего шага ревью (индекс, RAG и все попытки LLM). */
    public static final long STEP_LIMIT_SECONDS = 600;
    /** Оценка символов на токен: консервативно для смеси русского текста и кода. */
    static final int CHARS_PER_TOKEN = 2;
    /** Запас от окна контекста на служебные накладные расходы. */
    static final double SAFETY_FACTOR = 0.85;
    /** Доля доступного окна, которую может занять RAG-контекст (остальное — diff). */
    static final double RAG_SHARE = 0.4;
    /** Лимит diff, если окно модели не задано (LLM_CONTEXT_WINDOW_TOKENS). */
    public static final int UNKNOWN_WINDOW_DIFF_CHARS = 60_000;

    private static final ExecutorService LLM_POOL = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "review-llm");
        thread.setDaemon(true);
        return thread;
    });

    /** Вызов LLM: системная инструкция, пользовательский текст, лимит выхода. */
    @FunctionalInterface
    public interface Llm {
        String complete(String system, String user, int maxOutputTokens) throws Exception;
    }

    /** Модель в порядке попыток: режим (облако|локально), имя, вызов и ключ (вычищается из причин сбоя). */
    public record Model(String mode, String name, Llm llm, String secret, long timeoutSeconds) {
        public Model(String mode, String name, Llm llm) {
            this(mode, name, llm, null, LLM_TIMEOUT_SECONDS);
        }

        public Model(String mode, String name, Llm llm, String secret) {
            this(mode, name, llm, secret, LLM_TIMEOUT_SECONDS);
        }

        /** Попыток на модель: облако — 1 + повторы, локальная запасная — одна (CPU медленный). */
        public int attempts() {
            return ReviewLlmConfig.MODE_LOCAL.equals(mode) ? 1 : MAX_RETRIES + 1;
        }

        /** Лимит ответа: локальной модели — не больше ReviewLlmConfig.LOCAL_OUTPUT_TOKENS. */
        public int maxOutput(int requested) {
            return ReviewLlmConfig.MODE_LOCAL.equals(mode)
                    ? Math.min(requested, ReviewLlmConfig.LOCAL_OUTPUT_TOKENS) : requested;
        }
    }

    /** Поиск фрагментов по индексу; null вместо поиска — RAG отключён. */
    @FunctionalInterface
    public interface Search {
        List<RagRetriever.Chunk> search(String query, int topK) throws Exception;
    }

    public enum Status { ANALYZED, PARTIAL, NOT_ANALYZED, SKIPPED }

    /** Файл в плане ревью; text — аннотированный diff (с маскированием), пуст для пропущенных. */
    public record Entry(String path, int added, int removed, Status status, String reason, String text) {
        public boolean analyzed() {
            return status == Status.ANALYZED || status == Status.PARTIAL;
        }
    }

    /** Вход: refs, патчи git, окно модели (токены, null — неизвестно) и явный лимит diff в символах. */
    public record Input(String base, String head, List<GitDiff.FilePatch> patches,
                        Integer windowTokens, Integer maxDiffChars) {
    }

    /** Результат: markdown для комментария, причина недоступности LLM (null — ревью получено). */
    public record Result(String markdown, String unavailable, int retries, int chunks,
                         int budgetChars, long diffChars, long elapsedMs, List<Entry> entries) {
    }

    private ReviewRunner() {
    }

    /** Лимит ответа LLM: 4096, но не больше четверти окна (для маленьких моделей). */
    public static int outputTokens(Integer windowTokens) {
        if (windowTokens == null) {
            return RagConstants.RAG_MAX_OUTPUT_TOKENS;
        }
        return (int) Math.max(256, Math.min(RagConstants.RAG_MAX_OUTPUT_TOKENS, windowTokens / 4));
    }

    /** Символы под вход (diff + RAG + системная инструкция) при заданном окне. */
    static long inputChars(int windowTokens) {
        long usable = (long) (windowTokens * SAFETY_FACTOR);
        long promptTokens = ReviewPrompt.SYSTEM.length() / CHARS_PER_TOKEN;
        long tokens = usable - outputTokens(windowTokens) - promptTokens;
        return Math.max(0, tokens * CHARS_PER_TOKEN);
    }

    /** Лимит символов RAG-фрагментов: максимум 12×1200, но не больше доли входа; без окна — полный. */
    public static int ragCharLimit(Integer windowTokens) {
        int full = ReviewRetrieval.TOTAL_CHUNKS * ReviewRetrieval.CHUNK_CHARS;
        if (windowTokens == null) {
            return full;
        }
        return (int) Math.min(full, (long) (inputChars(windowTokens) * RAG_SHARE));
    }

    /** Лимит символов diff: остаток входа после фактического RAG; без окна — UNKNOWN_WINDOW_DIFF_CHARS. */
    public static int diffCharBudget(Integer windowTokens, int ragChars) {
        if (windowTokens == null) {
            return UNKNOWN_WINDOW_DIFF_CHARS;
        }
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, inputChars(windowTokens) - ragChars));
    }

    /** Файлы в порядке git: пропуски с причиной, затем заполнение бюджета; последний файл может быть обрезан. */
    public static List<Entry> plan(List<GitDiff.FilePatch> patches, int budgetChars) {
        List<Entry> entries = new ArrayList<>();
        int remaining = budgetChars;
        boolean cut = false;
        for (GitDiff.FilePatch patch : patches) {
            String reason = ReviewFilter.skipReason(patch.path(), patch.binary());
            if (reason != null) {
                entries.add(new Entry(patch.path(), patch.added(), patch.removed(), Status.SKIPPED, reason, ""));
                continue;
            }
            String text = RagQueryRewriter.redactSecrets(patch.annotated());
            if (text.isBlank()) {
                entries.add(new Entry(patch.path(), patch.added(), patch.removed(), Status.SKIPPED,
                        "нет текстовых изменений", ""));
                continue;
            }
            if (cut || remaining <= 0) {
                entries.add(new Entry(patch.path(), patch.added(), patch.removed(), Status.NOT_ANALYZED,
                        "не влезло в лимит diff", ""));
                continue;
            }
            if (text.length() <= remaining) {
                entries.add(new Entry(patch.path(), patch.added(), patch.removed(), Status.ANALYZED, "", text));
                remaining -= text.length();
                continue;
            }
            String prefix = cutAtLine(text, remaining);
            cut = true;
            remaining = 0;
            if (prefix.isBlank()) {
                entries.add(new Entry(patch.path(), patch.added(), patch.removed(), Status.NOT_ANALYZED,
                        "не влезло в лимит diff", ""));
            } else {
                entries.add(new Entry(patch.path(), patch.added(), patch.removed(), Status.PARTIAL,
                        "обрезано по лимиту diff", prefix));
            }
        }
        return entries;
    }

    static String cutAtLine(String text, int limit) {
        if (limit >= text.length()) {
            return text;
        }
        int newline = text.lastIndexOf('\n', limit);
        return newline <= 0 ? "" : text.substring(0, newline + 1);
    }

    public static Result run(Input input, Llm llm, Search search) {
        return run(input, List.of(new Model(ReviewLlmConfig.MODE_LOCAL, "stub", llm)), search);
    }

    public static Result run(Input input, List<Model> models, Search search) {
        long started = System.nanoTime();
        Integer window = input.windowTokens();
        if (input.patches().isEmpty()) {
            return new Result(ReviewReport.noChanges(input.base(), input.head()), null, 0, 0,
                    0, 0, elapsed(started), List.of());
        }
        List<Entry> candidates = plan(input.patches(), Integer.MAX_VALUE).stream()
                .filter(entry -> entry.status() == Status.ANALYZED).toList();
        ReviewRetrieval.Retrieval retrieval = ReviewRetrieval.retrieve(candidates, search, ragCharLimit(window));
        int ragChars = retrieval.chunks().stream().mapToInt(chunk -> chunk.text().length()).sum();
        int budget = input.maxDiffChars() != null ? input.maxDiffChars() : diffCharBudget(window, ragChars);
        List<Entry> entries = plan(input.patches(), budget);
        List<Entry> analyzed = entries.stream().filter(Entry::analyzed).toList();
        long diffChars = analyzed.stream().mapToLong(entry -> entry.text().length()).sum();
        if (analyzed.isEmpty()) {
            return new Result(ReviewReport.nothingToAnalyze(input.base(), input.head(), entries), null,
                    0, 0, budget, 0, elapsed(started), entries);
        }
        String user = ReviewPrompt.user(analyzed, retrieval.chunks());
        int maxOutput = outputTokens(window);
        String answer = null;
        Model used = null;
        String reason = null;
        String fallbackNote = null;
        int attempts = 0;
        long deadline = started + STEP_LIMIT_SECONDS * 1_000_000_000L;
        for (Model model : models) {
            for (int attempt = 0; attempt < model.attempts() && answer == null; attempt++) {
                long remaining = (deadline - System.nanoTime()) / 1_000_000_000L;
                if (remaining < 1) {
                    reason = "общий лимит шага ревью (" + STEP_LIMIT_SECONDS / 60 + " мин) исчерпан";
                    break;
                }
                attempts++;
                try {
                    String text = complete(model.llm(), ReviewPrompt.SYSTEM, user,
                            model.maxOutput(maxOutput), Math.min(model.timeoutSeconds(), remaining));
                    if (ReviewReport.hasSections(text)) {
                        answer = text;
                        used = model;
                    } else {
                        reason = "ответ не в требуемом формате";
                    }
                } catch (Exception e) {
                    reason = ReviewLlmConfig.scrub(describe(e), model.secret());
                }
            }
            if (answer != null) {
                break;
            }
            if (ReviewLlmConfig.MODE_CLOUD.equals(model.mode())) {
                fallbackNote = "Облако недоступно: " + reason + "; ревью выполнено локально.";
            }
        }
        int retries = attempts - 1;
        if (answer == null) {
            String text = reason + " (попыток: " + attempts + ")";
            return new Result(ReviewReport.unavailable(input.base(), input.head(), entries, text),
                    text, retries, retrieval.chunks().size(), budget, diffChars, elapsed(started), entries);
        }
        String markdown = ReviewReport.success(input.base(), input.head(), entries, answer,
                retrieval.chunks(), retrieval.error(), used.name(), used.mode(), fallbackNote);
        return new Result(RagQueryRewriter.redactSecrets(markdown), null, retries,
                retrieval.chunks().size(), budget, diffChars, elapsed(started), entries);
    }

    private static String complete(Llm llm, String system, String user, int maxOutput,
                                   long timeoutSeconds) throws Exception {
        Future<String> future = LLM_POOL.submit(() -> llm.complete(system, user, maxOutput));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("таймаут LLM (" + timeoutSeconds + " с)");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** Причина для пользователя без секретов и без пустых сообщений. */
    public static String describe(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return RagQueryRewriter.redactSecrets(message.strip());
    }

    private static long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
