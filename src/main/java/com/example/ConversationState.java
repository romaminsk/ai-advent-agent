package com.example;

import java.util.List;
import java.util.UUID;

/**
 * Снимок беседы для хранения: идентификатор сессии, завершённые пары
 * user/assistant (полный архив) и необязательное резюме покрытого
 * префикса ({@link ConversationSummary}, День 9) — отдельная сущность,
 * а не сообщение архива. Системная инструкция в снимок не входит — она не
 * хранится в истории, а добавляется к каждому запросу из текущего кода
 * агента, поэтому после перезапуска она не дублируется.
 */
public record ConversationState(String sessionId, List<ChatMessage> messages,
                                ConversationSummary summary) {

    /** Совместимый конструктор без summary (старая схема, День 7). */
    public ConversationState(String sessionId, List<ChatMessage> messages) {
        this(sessionId, messages, null);
    }

    public ConversationState {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId обязателен и не может быть пустым");
        }
        messages = List.copyOf(messages);
    }

    /** Новая пустая беседа со свежим идентификатором сессии (без summary). */
    public static ConversationState newEmpty() {
        return new ConversationState(UUID.randomUUID().toString(), List.of());
    }
}
