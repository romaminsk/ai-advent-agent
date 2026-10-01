package com.example.rag;

/** Shared RAG prompt, retrieval, and report settings. */
public final class RagConstants {
    public static final int DEFAULT_TOP_K = 5;
    public static final int CONTEXT_MAX_CHARS = 6000;
    public static final int RAG_MAX_OUTPUT_TOKENS = 4096;
    public static final int EMPTY_RESPONSE_RETRIES = 1;
    public static final String INDEX_STRATEGY = "structure";
    public static final String NO_ANSWER = "В базе нет ответа";
    public static final String LOW_RELEVANCE_ANSWER = "Не знаю: в базе нет достаточно релевантных фрагментов.";
    public static final String SYSTEM_PROMPT = "Отвечай только по приведённому контексту. "
            + "Каждое фактическое утверждение снабжай ссылкой [n], где n — номер чанка в контексте. "
            + "В конце приведи 1–3 цитаты строго в формате [n] «дословный фрагмент», "
            + "каждая длиной 20–300 символов и дословно из соответствующего чанка. "
            + "Не выдумывай источники, цитаты и факты. Если контекст не содержит ответа, "
            + "ответь ровно НЕ ЗНАЮ.";
    public static final String QUESTIONS_RESOURCE = "/rag/questions.json";

    private RagConstants() {
    }
}
