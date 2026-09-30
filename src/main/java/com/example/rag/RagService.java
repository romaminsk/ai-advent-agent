package com.example.rag;

import com.example.EmptyLlmAnswerException;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Stateless RAG requests; neither mode includes chat history. */
public final class RagService {
    public enum Mode { OFF, ON }
    public enum Status { OK, EMPTY, ERROR, TIMEOUT }
    public static final int LLM_CALL_TIMEOUT_SECONDS = 120;
    private static final ExecutorService LLM_EXECUTOR = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "rag-eval-llm");
        thread.setDaemon(true);
        return thread;
    });

    @FunctionalInterface
    public interface LlmClient {
        String complete(String system, String user, int maxOutputTokens) throws Exception;
    }

    public record Prepared(String question, RagPromptBuilder.Prompt prompt,
                           List<RagRetriever.Chunk> retrievedChunks, long retrieveMs,
                           RagReranker.Result ranking, RagSettings settings,
                           String rewriteQuery, boolean rewriteFallback, long rewriteMs) {
        public Prepared {
            retrievedChunks = List.copyOf(retrievedChunks);
        }
    }

    public record Retrieval(List<RagRetriever.Chunk> chunks, long retrieveMs,
                            RagReranker.Result ranking, String rewriteQuery,
                            boolean rewriteFallback, long rewriteMs) {
        public Retrieval {
            chunks = List.copyOf(chunks);
        }
    }

    public record Result(String answer, List<RagRetriever.Chunk> chunks,
                         List<RagRetriever.Chunk> retrievedChunks,
                         long retrieveMs, long llmMs, Status status, String error,
                         int filteredCount, boolean filteredAll, boolean rewriteFallback,
                         String rewriteQuery, long rewriteMs) {
        public Result {
            chunks = List.copyOf(chunks);
            retrievedChunks = List.copyOf(retrievedChunks);
        }
    }

    private final RagRetriever retriever;
    private final RagPromptBuilder promptBuilder;
    private final LlmClient llm;
    private final String offSystemPrompt;
    private final RagReranker reranker = new RagReranker();
    private final RagQueryRewriter rewriter;
    private final RagSettings settings;
    private final long llmCallTimeoutSeconds;

    public RagService(RagRetriever retriever, RagPromptBuilder promptBuilder,
                      String offSystemPrompt, LlmClient llm) {
        this(retriever, promptBuilder, offSystemPrompt, llm, RagSettings.DEFAULT, null);
    }

    public RagService(RagRetriever retriever, RagPromptBuilder promptBuilder,
                      String offSystemPrompt, LlmClient llm, RagSettings settings,
                      RagQueryRewriter rewriter) {
        this(retriever, promptBuilder, offSystemPrompt, llm, settings, rewriter,
                LLM_CALL_TIMEOUT_SECONDS);
    }

    public RagService(RagRetriever retriever, RagPromptBuilder promptBuilder,
                      String offSystemPrompt, LlmClient llm, RagSettings settings,
                      RagQueryRewriter rewriter, long llmCallTimeoutSeconds) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.offSystemPrompt = Objects.requireNonNull(offSystemPrompt, "offSystemPrompt");
        this.llm = Objects.requireNonNull(llm, "llm");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.rewriter = rewriter;
        if (llmCallTimeoutSeconds < 1) throw new IllegalArgumentException("timeout должен быть > 0");
        this.llmCallTimeoutSeconds = llmCallTimeoutSeconds;
    }

    public Prepared prepare(String question) throws Exception {
        return prepare(question, settings, null);
    }

    /** Optional rewriteOverride lets eval reuse one rewritten query across modes. */
    public Prepared prepare(String question, RagSettings requestSettings,
                            String rewriteOverride) throws Exception {
        Rewrite rewrite = rewrite(question, requestSettings, rewriteOverride);
        long searchStart = System.nanoTime();
        String searchQuery = rewrite.query().equals(question) ? question
                : question + "\n" + rewrite.query();
        List<RagRetriever.Chunk> chunks = retriever.retrieve(searchQuery,
                requestSettings.topKBefore());
        RagReranker.Result ranking = reranker.process(question, chunks, requestSettings);
        List<RagRetriever.Chunk> selected = ranking.selected().stream()
                .map(RagReranker.ScoredChunk::chunk).toList();
        RagPromptBuilder.Prompt prompt = promptBuilder.build(question, selected);
        return new Prepared(question, prompt, chunks, elapsedMs(searchStart), ranking,
                requestSettings, rewrite.query(), rewrite.fallback(), rewrite.ms());
    }

    public Retrieval retrieveOnly(String question) throws Exception {
        return retrieveOnly(question, settings);
    }

    public Retrieval retrieveOnly(String question, RagSettings requestSettings) throws Exception {
        Rewrite rewrite = rewrite(question, requestSettings, null);
        long searchStart = System.nanoTime();
        String searchQuery = rewrite.query().equals(question) ? question
                : question + "\n" + rewrite.query();
        List<RagRetriever.Chunk> chunks = retriever.retrieve(searchQuery,
                requestSettings.topKBefore());
        return new Retrieval(chunks, elapsedMs(searchStart),
                reranker.process(question, chunks, requestSettings), rewrite.query(),
                rewrite.fallback(), rewrite.ms());
    }

    public RagQueryRewriter.Result rewriteQuery(String question) {
        if (rewriter == null) return new RagQueryRewriter.Result(question, false, 0);
        return rewriter.rewrite(question);
    }

    public Result complete(Prepared prepared) {
        if (prepared.prompt().chunks().isEmpty()) {
            return new Result(RagConstants.NO_ANSWER, List.of(), prepared.retrievedChunks(),
                    prepared.retrieveMs(), 0, Status.OK, null,
                    prepared.ranking().filteredCount(), prepared.ranking().filteredAll(),
                    prepared.rewriteFallback(), prepared.rewriteQuery(), prepared.rewriteMs());
        }
        long start = System.nanoTime();
        try {
            String answer = completeWithRetry(prepared.prompt().system(), prepared.prompt().user());
            return new Result(answer, prepared.prompt().chunks(), prepared.retrievedChunks(),
                    prepared.retrieveMs(), elapsedMs(start), Status.OK, null,
                    prepared.ranking().filteredCount(), prepared.ranking().filteredAll(),
                    prepared.rewriteFallback(), prepared.rewriteQuery(), prepared.rewriteMs());
        } catch (EmptyLlmAnswerException empty) {
            return new Result("", prepared.prompt().chunks(), prepared.retrievedChunks(),
                    prepared.retrieveMs(), elapsedMs(start), Status.EMPTY, errorText(empty),
                    prepared.ranking().filteredCount(), prepared.ranking().filteredAll(),
                    prepared.rewriteFallback(), prepared.rewriteQuery(), prepared.rewriteMs());
        } catch (LlmCallTimeoutException timeout) {
            return new Result("", prepared.prompt().chunks(), prepared.retrievedChunks(),
                    prepared.retrieveMs(), elapsedMs(start), Status.TIMEOUT, errorText(timeout),
                    prepared.ranking().filteredCount(), prepared.ranking().filteredAll(),
                    prepared.rewriteFallback(), prepared.rewriteQuery(), prepared.rewriteMs());
        } catch (Exception error) {
            return new Result("", prepared.prompt().chunks(), prepared.retrievedChunks(),
                    prepared.retrieveMs(), elapsedMs(start), Status.ERROR, errorText(error),
                    prepared.ranking().filteredCount(), prepared.ranking().filteredAll(),
                    prepared.rewriteFallback(), prepared.rewriteQuery(), prepared.rewriteMs());
        }
    }

    public Result ask(String question, Mode mode) {
        return ask(question, mode, settings, null);
    }

    public Result ask(String question, Mode mode, RagSettings requestSettings,
                      String rewriteOverride) {
        if (mode == Mode.OFF) {
            long start = System.nanoTime();
            try {
                String answer = completeWithRetry(offSystemPrompt, question);
                return new Result(answer, List.of(), List.of(), 0, elapsedMs(start),
                        Status.OK, null, 0, false, false, question, 0);
            } catch (EmptyLlmAnswerException empty) {
                return new Result("", List.of(), List.of(), 0, elapsedMs(start),
                        Status.EMPTY, errorText(empty), 0, false, false, question, 0);
            } catch (LlmCallTimeoutException timeout) {
                return new Result("", List.of(), List.of(), 0, elapsedMs(start),
                        Status.TIMEOUT, errorText(timeout), 0, false, false, question, 0);
            } catch (Exception error) {
                return new Result("", List.of(), List.of(), 0, elapsedMs(start),
                        Status.ERROR, errorText(error), 0, false, false, question, 0);
            }
        }
        long retrieveStart = System.nanoTime();
        Prepared prepared;
        try {
            prepared = prepare(question, requestSettings, rewriteOverride);
        } catch (Exception error) {
            return new Result("", List.of(), List.of(), elapsedMs(retrieveStart), 0,
                    Status.ERROR, errorText(error), 0, false, false, question, 0);
        }
        return complete(prepared);
    }

    private Rewrite rewrite(String question, RagSettings requestSettings, String override) {
        if (!requestSettings.rewriteEnabled()) return new Rewrite(question, false, 0);
        if (override != null) return new Rewrite(override, false, 0);
        if (rewriter == null) return new Rewrite(question, false, 0);
        RagQueryRewriter.Result result = rewriter.rewrite(question);
        return new Rewrite(result.query(), result.rewriteFallback(), result.rewriteMs());
    }

    private record Rewrite(String query, boolean fallback, long ms) {
    }

    private String completeWithRetry(String system, String user) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                String answer = completeOneCall(system, user);
                if (answer == null || answer.isBlank()) {
                    throw new EmptyLlmAnswerException("Модель вернула пустой итоговый ответ.");
                }
                return answer;
            } catch (EmptyLlmAnswerException empty) {
                if (attempt >= RagConstants.EMPTY_RESPONSE_RETRIES) {
                    throw empty;
                }
            }
        }
    }

    private String completeOneCall(String system, String user) throws Exception {
        Future<String> future = LLM_EXECUTOR.submit(() ->
                llm.complete(system, user, RagConstants.RAG_MAX_OUTPUT_TOKENS));
        try {
            return future.get(llmCallTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new LlmCallTimeoutException("Таймаут LLM-вызова ("
                    + llmCallTimeoutSeconds + " с).", timeout);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Ошибка LLM-вызова", cause);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static final class LlmCallTimeoutException extends Exception {
        private LlmCallTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static String errorText(Exception error) {
        return RagQueryRewriter.redactSecrets(error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage());
    }

    public static long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}
