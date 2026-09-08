package com.example;

/**
 * Метрики одного запроса к LLM. Позволяют отделить задержку HTTP
 * от разбора ответа и записи истории; используются только в
 * диагностическом режиме и предупреждениях о лимите.
 *
 * Значения null означают «нет данных» (поле отсутствует в ответе),
 * а не ноль. Время HTTP — время до получения полного тела ответа,
 * а не время до первого токена: запрос непотоковый.
 */
public record RequestDiagnostics(
        String profile,
        int maxOutputTokens,
        Double temperature,
        int sentMessages,
        int includedPairs,
        int omittedPairs,
        int requestBytes,
        long prepareNanos,
        long httpNanos,
        long parseNanos,
        long saveNanos,
        long totalNanos,
        String finishReason,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens) {

    /** Признак остановки генерации по лимиту (finish_reason: length). */
    public boolean limitReached() {
        return "length".equals(finishReason);
    }

    /** Лимит генерации, который был фактически отправлен в запросе. */
    public int effectiveMaxOutputTokens() {
        return maxOutputTokens;
    }
}
