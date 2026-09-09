package com.example;

import java.util.ArrayList;
import java.util.List;

/**
 * Накопленные фактические расходы токенов за текущую сессию приложения
 * (жизненный цикл — один запуск). При перезапуске счётчики сбрасываются,
 * сохранённая история при этом не трогается — это разные вещи.
 *
 * Учитываются только фактические токены из usage ответов API. Запрос без
 * usage не обнуляет расход: он помечается как запрос с неизвестным расходом,
 * и итог помечается неполным. Сумма расходов всех запросов — НЕ размер
 * текущей истории: это разные показатели (история отправляется повторно,
 * поэтому сумма входов за сессию обычно больше размера истории).
 *
 * Счётчики обновляются ровно один раз на запрос; total_tokens из ответа
 * повторно не суммируется — накопление ведётся по prompt_tokens и
 * completion_tokens раздельно.
 */
public final class SessionTokenStats {

    private long apiAttempts;
    private long requestsWithUsage;
    private long requestsWithoutUsage;
    private long requestsWithPartialUsage;
    private long totalPromptTokens;
    private long totalCompletionTokens;

    /**
     * Журнал учтённого расхода по попыткам: ровно одна запись на запрос,
     * в порядке попыток. Значения те же, что уже учтены в суммах, — это
     * детализация для таблиц /demo stats, а не отдельный счётчик.
     */
    private final List<AttemptUsage> attemptUsage = new ArrayList<>();

    /** Ещё одна попытка обращения к API; вызывается ровно один раз на запрос. */
    public void recordAttempt() {
        apiAttempts++;
    }

    /**
     * Учитывает usage одного ответа; вызывается ровно один раз на запрос.
     * null-поля означают «нет данных» (частичный usage), а не ноль: такой
     * запрос помечается как частичный и делает итог неполным.
     */
    public void recordUsage(Integer promptTokens, Integer completionTokens) {
        attemptUsage.add(new AttemptUsage(promptTokens, completionTokens));
        if (promptTokens == null && completionTokens == null) {
            requestsWithoutUsage++;
            return;
        }
        requestsWithUsage++;
        if (promptTokens == null || completionTokens == null) {
            requestsWithPartialUsage++;
        }
        if (promptTokens != null) {
            totalPromptTokens += promptTokens;
        }
        if (completionTokens != null) {
            totalCompletionTokens += completionTokens;
        }
    }

    /**
     * Запрос к API выполнен, но расход неизвестен: ответ не получен
     * (таймаут, сеть, HTTP-ошибка) либо usage разобрать не удалось.
     * Провайдер мог списать токены — в итог они не попадут.
     */
    public void recordUnknownUsage() {
        attemptUsage.add(new AttemptUsage(null, null));
        requestsWithoutUsage++;
    }

    /**
     * Неизменяемый журнал учтённого расхода по попыткам; запись с индексом i
     * соответствует попытке i+1. Используется таблицами /demo stats: для
     * неуспешных попыток с usage значения берутся отсюда, а не придумываются.
     */
    public List<AttemptUsage> attemptUsageLog() {
        return List.copyOf(attemptUsage);
    }

    /** Учтённый расход одной попытки; null — «нет данных», а не ноль. */
    public record AttemptUsage(Integer promptTokens, Integer completionTokens) {
    }

    /** Неизменяемый снимок для UI и тестов. */
    public Snapshot snapshot() {
        boolean complete = apiAttempts > 0
                && requestsWithoutUsage == 0
                && requestsWithPartialUsage == 0;
        return new Snapshot(apiAttempts, requestsWithUsage, requestsWithoutUsage,
                requestsWithPartialUsage, totalPromptTokens, totalCompletionTokens, complete);
    }

    /**
     * Снимок накопленных фактических расходов сессии. totalPromptTokens и
     * totalCompletionTokens — суммы только по запросам с доступным usage;
     * complete — признак полноты итога (все запросы дали полный usage).
     */
    public record Snapshot(
            long apiAttempts,
            long requestsWithUsage,
            long requestsWithoutUsage,
            long requestsWithPartialUsage,
            long totalPromptTokens,
            long totalCompletionTokens,
            boolean complete) {

        /**
         * Учтённый расход сессии: сумма известных prompt_tokens и
         * completion_tokens. total_tokens повторно не прибавляется —
         * это привело бы к двойному учёту. Переполнение суммы (практически
         * недостижимо) трактуется безопасно: расход считается максимальным,
         * то есть не меньше любого лимита.
         */
        public long knownTotal() {
            try {
                return Math.addExact(totalPromptTokens, totalCompletionTokens);
            } catch (ArithmeticException e) {
                return Long.MAX_VALUE;
            }
        }
    }
}
