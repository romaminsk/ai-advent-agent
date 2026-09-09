package com.example;

/**
 * Запрос заблокирован локально: прогнозируемый вход (по локальной оценке)
 * плюс резерв выхода превышают настроенный контекстный бюджет
 * (LLM_CONTEXT_WINDOW_TOKENS) при LLM_CONTEXT_OVERFLOW_POLICY=block.
 *
 * HTTP-запрос не выполнялся, история не изменялась, попытка обращения к API
 * не засчитывалась. Это локальная оценка, а не отказ провайдера.
 */
public final class ContextBudgetBlockedException extends AgentException {

    public ContextBudgetBlockedException(String message) {
        super(message);
    }
}
