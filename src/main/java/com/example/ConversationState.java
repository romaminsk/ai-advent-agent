package com.example;

import java.util.List;
import java.util.UUID;

/**
 * Снимок беседы для хранения: идентификатор сессии и завершённые пары
 * user/assistant. Системная инструкция в снимок не входит — она не хранится
 * в истории, а добавляется к каждому запросу из текущего кода агента,
 * поэтому после перезапуска она не дублируется.
 */
public record ConversationState(String sessionId, List<ChatMessage> messages) {

    public ConversationState {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId обязателен и не может быть пустым");
        }
        messages = List.copyOf(messages);
    }

    /** Новая пустая беседа со свежим идентификатором сессии. */
    public static ConversationState newEmpty() {
        return new ConversationState(UUID.randomUUID().toString(), List.of());
    }
}
