package com.example.rag;

import java.util.List;
import java.util.Objects;

/** Stateless RAG requests; neither mode includes chat history. */
public final class RagService {
    public enum Mode { OFF, ON }

    @FunctionalInterface
    public interface LlmClient {
        String complete(String system, String user);
    }

    public record Prepared(String question, RagPromptBuilder.Prompt prompt,
                           List<RagRetriever.Chunk> retrievedChunks, long retrieveMs) {
        public Prepared {
            retrievedChunks = List.copyOf(retrievedChunks);
        }
    }

    public record Result(String answer, List<RagRetriever.Chunk> chunks,
                         List<RagRetriever.Chunk> retrievedChunks,
                         long retrieveMs, long llmMs) {
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
                    prepared.retrieveMs(), 0);
        }
        long start = System.nanoTime();
        String answer = llm.complete(prepared.prompt().system(), prepared.prompt().user());
        return new Result(answer, prepared.prompt().chunks(), prepared.retrievedChunks(),
                prepared.retrieveMs(), elapsedMs(start));
    }

    public Result ask(String question, Mode mode) throws Exception {
        if (mode == Mode.OFF) {
            long start = System.nanoTime();
            String answer = llm.complete(offSystemPrompt, question);
            return new Result(answer, List.of(), List.of(), 0, elapsedMs(start));
        }
        return complete(prepare(question));
    }

    public static long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}
