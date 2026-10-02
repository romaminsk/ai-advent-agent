package com.example;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Снимок беседы для хранения: идентификатор сессии, завершённые пары
 * user/assistant (полный архив), необязательное резюме покрытого префикса
 * ({@link ConversationSummary}), блок фактов ({@link FactsBlock},
 * стратегия facts), модель веток ({@link BranchData}, стратегия branching)
 * и состояние диалога ({@link DialogTaskState}: цель, уточнения, ограничения,
 * термины, открытые вопросы). Резюме, факты, ветки и состояние диалога —
 * отдельные сущности, а не сообщения архива.
 * Системная инструкция в снимок не входит — она не хранится в истории,
 * а добавляется к каждому запросу из текущего кода агента, поэтому
 * после перезапуска она не дублируется.
 */
public record ConversationState(String sessionId, List<ChatMessage> messages,
                                ConversationSummary summary,
                                LinkedHashMap<String, String> facts,
                                BranchData branches,
                                DialogTaskState dialogState) {

    /** Совместимый конструктор без summary (старые файлы истории). */
    public ConversationState(String sessionId, List<ChatMessage> messages) {
        this(sessionId, messages, null, null, null);
    }

    /** Совместимый конструктор с summary (сжатие истории). */
    public ConversationState(String sessionId, List<ChatMessage> messages,
                             ConversationSummary summary) {
        this(sessionId, messages, summary, null, null);
    }

    /** Совместимый конструктор без состояния диалога (файлы до его появления). */
    public ConversationState(String sessionId, List<ChatMessage> messages,
                             ConversationSummary summary,
                             LinkedHashMap<String, String> facts,
                             BranchData branches) {
        this(sessionId, messages, summary, facts, branches, null);
    }

    public ConversationState {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId обязателен и не может быть пустым");
        }
        messages = List.copyOf(messages);
    }

    /** Новая пустая беседа со свежим идентификатором сессии. */
    public static ConversationState newEmpty() {
        return new ConversationState(UUID.randomUUID().toString(), List.of(),
                null, null, null, null);
    }
}
