package com.example.rag;

import java.util.ArrayList;
import java.util.List;

/** Builds a bounded, source-labelled retrieval prompt. */
public final class RagPromptBuilder {
    public record Prompt(String system, String user, String context, List<RagRetriever.Chunk> chunks) {
        public Prompt {
            chunks = List.copyOf(chunks);
        }
    }

    public Prompt build(String question, List<RagRetriever.Chunk> retrieved) {
        StringBuilder context = new StringBuilder();
        List<RagRetriever.Chunk> included = new ArrayList<>();
        for (RagRetriever.Chunk chunk : retrieved) {
            String block = "[" + (included.size() + 1) + "] " + chunk.source() + " › "
                    + chunk.section() + "\n" + chunk.text();
            int separatorLength = context.isEmpty() ? 0 : 1;
            if (context.length() + separatorLength + block.length()
                    > RagConstants.CONTEXT_MAX_CHARS) {
                break;
            }
            if (separatorLength > 0) {
                context.append('\n');
            }
            context.append(block);
            included.add(chunk);
        }
        String user = (context.isEmpty() ? "Контекст отсутствует."
                : "Контекст:\n" + context) + "\n\nВопрос: " + question;
        return new Prompt(RagConstants.SYSTEM_PROMPT, user, context.toString(), included);
    }
}
