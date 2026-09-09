package com.example;

/**
 * Метрики одного запроса к LLM. Позволяют отделить задержку HTTP
 * от разбора ответа и записи истории; используются только в
 * диагностическом режиме и предупреждениях о лимите.
 *
 * Значения null означают «нет данных» (поле отсутствует в ответе),
 * а не ноль. Время HTTP — время до получения полного тела ответа,
 * а не время до первого токена: запрос непотоковый.
 *
 * Токен-поля двух типов, их нельзя смешивать:
 * - фактические (promptTokens, completionTokens, totalTokens) — из usage
 *   ответа API; для completion_tokens для некоторых моделей в это число
 *   могут входить дополнительные категории (например, reasoning), поэтому
 *   оно не приравнивается к токенам видимого текста ответа;
 * - оценочные (estimated*) — локальная эвристика (≈), помечены префиксом
 *   estimated; это оценки, а не измерения API.
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
        Integer totalTokens,
        Integer estimatedUserMessageTokens,
        Integer estimatedRequestTokens,
        Integer estimatedRequestOverheadTokens,
        Integer estimatedAnswerTokens,
        Integer estimatedHistoryTokensAfter,
        Integer savedMessagesAfter,
        boolean contextBudgetExceeded) {

    /** Признак остановки генерации по лимиту (finish_reason: length). */
    public boolean limitReached() {
        return "length".equals(finishReason);
    }

    /** Лимит генерации, который был фактически отправлен в запросе. */
    public int effectiveMaxOutputTokens() {
        return maxOutputTokens;
    }
}
