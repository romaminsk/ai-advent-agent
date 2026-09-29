package com.example.rag;

/** Shared RAG prompt, retrieval, and report settings. */
public final class RagConstants {
    public static final int DEFAULT_TOP_K = 5;
    public static final int CONTEXT_MAX_CHARS = 6000;
    public static final String INDEX_STRATEGY = "structure";
    public static final String NO_ANSWER = "В базе нет ответа";
    public static final String SYSTEM_PROMPT = "Отвечай только по контексту. После каждого факта ссылка [source]. "
            + "Если в контексте ответа нет — скажи: \"В базе нет ответа\". Не выдумывай.";
    public static final String QUESTIONS_RESOURCE = "/rag/questions.json";

    private RagConstants() {
    }
}
