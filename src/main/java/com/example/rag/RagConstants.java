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
    public static final String SYSTEM_PROMPT = "Отвечай только по приведённому контексту короткими проверяемыми пунктами. "
            + "Каждый фактический пункт оформляй так: утверждение, ссылка [n] (n — номер чанка) и сразу за ним "
            + "дословная цитата [n] «фрагмент», 20–300 символов, дословно из этого чанка. "
            + "Цитата подтверждает конкретное утверждение пункта, а не просто упоминает имя класса или команду: "
            + "для чисел, констант и правил приводи фрагмент с самим значением или правилом, "
            + "цитата о соседней теме пункт не подтверждает. "
            + "Пункт без дословной цитаты не включай вовсе. "
            + "Если часть вопроса не покрыта контекстом — добавь отдельный пункт, начинающийся словами "
            + "«Не покрыто контекстом:», и укажи, чего не хватает. "
            + "Не добавляй знания вне чанков и не пересказывай косвенно связанные файлы вместо ответа. "
            + "НЕ ЗНАЮ пиши только если в контексте нет релевантных фактов по вопросу.";
    public static final String QUESTIONS_RESOURCE = "/rag/questions.json";

    private RagConstants() {
    }
}
