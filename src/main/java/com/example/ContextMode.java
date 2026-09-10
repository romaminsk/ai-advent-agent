package com.example;

import java.util.Locale;

/**
 * Режим формирования отправляемого контекста (День 9):
 * - FULL — существующая системная инструкция, все завершённые сообщения
 *   текущей беседы и новое сообщение пользователя;
 * - SUMMARY — системная инструкция, справочное резюме покрытого префикса
 *   (если оно есть), все сообщения после границы покрытия дословно
 *   и новое сообщение пользователя.
 *
 * Переключение режима (/context full|summary) само по себе API не вызывает.
 */
public enum ContextMode {

    FULL,

    SUMMARY;

    public static final ContextMode DEFAULT = FULL;

    /** Разбор значения LLM_CONTEXT_MODE; пустое значение — значение по умолчанию. */
    public static ContextMode parse(String value, String variableName) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("full".equals(normalized)) {
            return FULL;
        }
        if ("summary".equals(normalized)) {
            return SUMMARY;
        }
        throw new AgentException(variableName + " должна быть full или summary, получено: "
                + value.trim() + ".");
    }

    /** Русское название режима для сообщений терминала. */
    public String title() {
        return this == FULL ? "full (полная история)" : "summary (резюме + несжатый хвост)";
    }
}
