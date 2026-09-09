package com.example;

import java.util.List;

/**
 * Заменяемый компонент локального подсчёта токенов.
 *
 * Подтверждённый токенизатор выбранной модели (glm-5.3-flash) в проекте
 * не подключён, поэтому реализация по умолчанию — эвристика
 * {@link HeuristicTokenCounter}, а её результат всегда является ОЦЕНКОЙ
 * (в UI помечается знаком ≈ и словом «оценка»), а не точным подсчётом.
 * В тестах подставляется детерминированная реализация.
 *
 * Точность текстового счётчика и точность оценки полного chat-запроса —
 * разные вещи: служебный шаблон сообщений провайдера (рамки JSON, роль,
 * служебные поля тела запроса) точно неизвестен, поэтому накладные расходы
 * оцениваются константами и учитываются отдельным слагаемым, а не в тексте.
 */
public interface TokenCounter {

    /** Оценка служебных накладных расходов одного сообщения (роль + рамки JSON). */
    int MESSAGE_OVERHEAD_TOKENS = 4;

    /** Оценка накладных расходов тела запроса (model и служебные поля провайдера). */
    int REQUEST_OVERHEAD_TOKENS = 8;

    /**
     * Локальная оценка числа токенов в тексте. null и пустой текст — 0.
     * Результат — оценка, а не точный подсчёт.
     */
    int count(String text);

    /** true только для подтверждённого токенизатора конкретной модели. */
    boolean exact();

    /** Человекочитаемое описание источника подсчёта для /tokens и диагностики. */
    String description();

    /** Оценка одного сообщения: текст плюс накладные расходы роли и рамок JSON. */
    default int countMessage(ChatMessage message) {
        return count(message.content()) + MESSAGE_OVERHEAD_TOKENS;
    }

    /**
     * Оценка списка сообщений (полный запрос или сохранённая история):
     * тексты всех сообщений плюс накладные расходы каждого сообщения
     * и тела запроса.
     */
    default int countMessages(List<ChatMessage> messages) {
        int total = REQUEST_OVERHEAD_TOKENS;
        for (ChatMessage message : messages) {
            total += countMessage(message);
        }
        return total;
    }

    /** Оценка накладных расходов для списка из messageCount сообщений. */
    default int countOverhead(int messageCount) {
        return REQUEST_OVERHEAD_TOKENS + messageCount * MESSAGE_OVERHEAD_TOKENS;
    }
}
