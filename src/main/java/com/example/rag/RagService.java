package com.example.rag;

import com.example.EmptyLlmAnswerException;

import java.util.List;
import java.util.Objects;

/** Stateless RAG requests; neither mode includes chat history. */
public final class RagService {
    public enum Mode { OFF, ON }
    public enum Status { OK, EMPTY, ERROR }

    @FunctionalInterface
    public interface LlmClient {
        String complete(String system, String user, int maxOutputTokens) throws Exception;
    }

    public record Prepared(String question, RagPromptBuilder.Prompt prompt,
                           List<RagRetriever.Chunk> retrievedChunks, long retrieveMs) {
        public Prepared {
            retrievedChunks = List.copyOf(retrievedChunks);
        }
    }

    public record Result(String answer, List<RagRetriever.Chunk> chunks,
                         List<RagRetriever.Chunk> retrievedChunks,
                         long retrieveMs, long llmMs, Status status, String error) {
        public Result {
            chunks = List.copyOf(chunks);
            retrievedChunks = List.copyOf(retrievedChunks);
        }
    }

    private final RagRetriever retriever;
    private final RagPromptBuilder promptBuilder;
    private final LlmClient llm;
    private final String offSystemPrompt;

    public RagService(RagRetriever retriever, RagPromptBuilder promptBuilder,
                      String offSystemPrompt, LlmClient llm) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.offSystemPrompt = Objects.requireNonNull(offSystemPrompt, "offSystemPrompt");
        this.llm = Objects.requireNonNull(llm, "llm");
    }

    public Prepared prepare(String question) throws Exception {
        long start = System.nanoTime();
        List<RagRetriever.Chunk> chunks = retriever.retrieve(question);
        RagPromptBuilder.Prompt prompt = promptBuilder.build(question, chunks);
        return new Prepared(question, prompt, chunks, elapsedMs(start));
    }

    public Result complete(Prepared prepared) {
        if (prepared.prompt().chunks().isEmpty()) {
            return new Result(RagConstants.NO_ANSWER, List.of(), prepared.retrievedChunks(),
                    prepared.retrieveMs(), 0, Status.OK, null);
        }
        long start = System.nanoTime();
        try {
            String answer = completeWithRetry(prepared.prompt().system(), prepared.prompt().user());
            return new Result(answer, prepared.prompt().chunks(), prepared.retrievedChunks(),
                    prepared.retrieveMs(), elapsedMs(start), Status.OK, null);
        } catch (EmptyLlmAnswerException empty) {
            return new Result("", prepared.prompt().chunks(), prepared.retrievedChunks(),
                    prepared.retrieveMs(), elapsedMs(start), Status.EMPTY, errorText(empty));
        } catch (Exception error) {
            return new Result("", prepared.prompt().chunks(), prepared.retrievedChunks(),
                    prepared.retrieveMs(), elapsedMs(start), Status.ERROR, errorText(error));
        }
    }

    public Result ask(String question, Mode mode) {
        if (mode == Mode.OFF) {
            long start = System.nanoTime();
            try {
                String answer = completeWithRetry(offSystemPrompt, question);
                return new Result(answer, List.of(), List.of(), 0, elapsedMs(start),
                        Status.OK, null);
            } catch (EmptyLlmAnswerException empty) {
                return new Result("", List.of(), List.of(), 0, elapsedMs(start),
                        Status.EMPTY, errorText(empty));
            } catch (Exception error) {
                return new Result("", List.of(), List.of(), 0, elapsedMs(start),
                        Status.ERROR, errorText(error));
            }
        }
        long retrieveStart = System.nanoTime();
        Prepared prepared;
        try {
            prepared = prepare(question);
        } catch (Exception error) {
            return new Result("", List.of(), List.of(), elapsedMs(retrieveStart), 0,
                    Status.ERROR, errorText(error));
        }
        return complete(prepared);
    }

    private String completeWithRetry(String system, String user) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                String answer = llm.complete(system, user, RagConstants.RAG_MAX_OUTPUT_TOKENS);
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

    private static String errorText(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    public static long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}
