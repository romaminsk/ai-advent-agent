package com.example;

import java.util.Locale;

/**
 * Поведение при прогнозируемом (по локальной оценке) превышении
 * контекстного бюджета. Это локальная оценка, а не ответ провайдера:
 * реальный лимит контекста модели и особенности API могут отличаться.
 */
public enum ContextOverflowPolicy {

    /** Предупредить и сохранить прежнее поведение отправки. */
    WARN,

    /**
     * Не выполнять HTTP-запрос при прогнозируемом превышении:
     * локальная блокировка по оценке, история не изменяется,
     * попытка обращения к API не засчитывается.
     */
    BLOCK;

    public static final ContextOverflowPolicy DEFAULT = WARN;

    /** Разбор значения переменной окружения; пустое значение — значение по умолчанию. */
    public static ContextOverflowPolicy parse(String value, String variableName) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("warn".equals(normalized)) {
            return WARN;
        }
        if ("block".equals(normalized)) {
            return BLOCK;
        }
        throw new AgentException(variableName + " должна быть warn или block, получено: "
                + value.trim() + ".");
    }
}
