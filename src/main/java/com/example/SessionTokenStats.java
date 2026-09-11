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
 * Каждый вызов помечается назначением ({@link Purpose}): обычный ответ,
 * сжатие истории, обновление блока фактов, ручное сравнение (все виды,
 * включая подготовку). Общий расход считает все назначения ровно один раз:
 * каждый запрос учитывается в одной категории, total_tokens поверх суммы
 * не прибавляется.
 */
public final class SessionTokenStats {

    /** Назначение API-вызова: определяет, к какой группе расхода он относится. */
    public enum Purpose {
        /** Обычный ответ пользователю. */
        REGULAR,
        /** Создание или обновление резюме истории (в том числе /summary refresh). */
        SUMMARY,
        /** Ручное сравнение сжатия: запрос без сжатия. */
        COMPARE_FULL,
        /** Ручное сравнение сжатия: запрос со сжатым контекстом. */
        COMPARE_SUMMARY,
        /** Ручное сравнение сжатия: подготовка резюме внутри сравнения. */
        COMPARE_SUMMARY_PREP,
        /** Обновление блока фактов (стратегия facts). */
        FACTS_UPDATE,
        /** Сравнение стратегий: вариант sliding-window. */
        COMPARE_SLIDING,
        /** Сравнение стратегий: вариант facts. */
        COMPARE_FACTS,
        /** Сравнение стратегий: вариант branching. */
        COMPARE_BRANCHING,
        /** Сравнение стратегий: подготовка фактов внутри сравнения. */
        COMPARE_FACTS_PREP
    }

    private long apiAttempts;
    private long requestsWithUsage;
    private long requestsWithoutUsage;
    private long requestsWithPartialUsage;
    private long totalPromptTokens;
    private long totalCompletionTokens;

    private long regularAttempts;
    private long regularPromptTokens;
    private long regularCompletionTokens;

    private long summaryAttempts;
    private long summaryPromptTokens;
    private long summaryCompletionTokens;

    private long compareAttempts;
    private long comparePromptTokens;
    private long compareCompletionTokens;

    private long factsAttempts;
    private long factsPromptTokens;
    private long factsCompletionTokens;

    /**
     * Накопленная экономия/перерасход входных токенов обычных запросов
     * за счёт сжатия: оценка ≈ (разница локальной оценки полного
     * входа и фактического prompt_tokens запроса со сжатием). Положительное
     * значение — экономия, отрицательное — перерасход. Запросы без usage
     * не попадают в сумму и помечаются неизвестными («недостаточно данных»).
     */
    private long contextSavingsPromptTokens;
    private long contextSavingsRequests;
    private long contextSavingsEstimatedRequests;
    private long contextSavingsUnknownRequests;

    /**
     * Учитывает оценку (≈) разницы входа одного запроса со сжатием против
     * гипотетического полного входа. Вызывается ровно один раз на успешный
     * обычный запрос в режиме summary. positive = экономия.
     */
    public void recordContextSavings(long deltaTokens, boolean exact,
                                     boolean actualPromptKnown) {
        if (!actualPromptKnown) {
            contextSavingsUnknownRequests++;
            return;
        }
        contextSavingsPromptTokens += deltaTokens;
        contextSavingsRequests++;
        if (!exact) {
            contextSavingsEstimatedRequests++;
        }
    }

    /**
     * Журнал учтённого расхода по попыткам: ровно одна запись на запрос,
     * в порядке попыток. Значения те же, что уже учтены в суммах, — это
     * детализация для таблиц /demo stats, а не отдельный счётчик.
     */
    private final List<AttemptUsage> attemptUsage = new ArrayList<>();

    /** Ещё одна попытка обращения к API; вызывается ровно один раз на запрос. */
    public void recordAttempt() {
        recordAttempt(Purpose.REGULAR);
    }

    /** Попытка с назначением; ровно одна запись на запрос. */
    public void recordAttempt(Purpose purpose) {
        apiAttempts++;
        switch (purpose) {
            case REGULAR -> regularAttempts++;
            case SUMMARY -> summaryAttempts++;
            case FACTS_UPDATE -> factsAttempts++;
            case COMPARE_FULL, COMPARE_SUMMARY, COMPARE_SUMMARY_PREP, COMPARE_SLIDING,
                    COMPARE_FACTS, COMPARE_BRANCHING, COMPARE_FACTS_PREP -> compareAttempts++;
        }
    }

    /**
     * Учитывает usage одного обычного ответа; вызывается ровно один раз
     * на запрос.
     */
    public void recordUsage(Integer promptTokens, Integer completionTokens) {
        recordUsage(Purpose.REGULAR, promptTokens, completionTokens);
    }

    /**
     * Учитывает usage одного ответа с назначением; вызывается ровно один
     * раз на запрос. null-поля означают «нет данных» (частичный usage),
     * а не ноль: такой запрос помечается как частичный и делает итог
     * неполным.
     */
    public void recordUsage(Purpose purpose, Integer promptTokens, Integer completionTokens) {
        attemptUsage.add(new AttemptUsage(promptTokens, completionTokens, purpose));
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
        switch (purpose) {
            case REGULAR -> {
                if (promptTokens != null) {
                    regularPromptTokens += promptTokens;
                }
                if (completionTokens != null) {
                    regularCompletionTokens += completionTokens;
                }
            }
            case SUMMARY -> {
                if (promptTokens != null) {
                    summaryPromptTokens += promptTokens;
                }
                if (completionTokens != null) {
                    summaryCompletionTokens += completionTokens;
                }
            }
            case COMPARE_FULL, COMPARE_SUMMARY, COMPARE_SUMMARY_PREP -> {
                if (promptTokens != null) {
                    comparePromptTokens += promptTokens;
                }
                if (completionTokens != null) {
                    compareCompletionTokens += completionTokens;
                }
            }
            case FACTS_UPDATE -> {
                if (promptTokens != null) {
                    factsPromptTokens += promptTokens;
                }
                if (completionTokens != null) {
                    factsCompletionTokens += completionTokens;
                }
            }
            case COMPARE_SLIDING, COMPARE_FACTS, COMPARE_BRANCHING, COMPARE_FACTS_PREP -> {
                if (promptTokens != null) {
                    comparePromptTokens += promptTokens;
                }
                if (completionTokens != null) {
                    compareCompletionTokens += completionTokens;
                }
            }
        }
    }

    /**
     * Обычный запрос к API выполнен, но расход неизвестен: ответ не получен
     * (таймаут, сеть, HTTP-ошибка) либо usage разобрать не удалось.
     * Провайдер мог списать токены — в итог они не попадут.
     */
    public void recordUnknownUsage() {
        recordUnknownUsage(Purpose.REGULAR);
    }

    /** То же для запроса с назначением (суммаризация, сравнение). */
    public void recordUnknownUsage(Purpose purpose) {
        attemptUsage.add(new AttemptUsage(null, null, purpose));
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
    public record AttemptUsage(Integer promptTokens, Integer completionTokens,
                               Purpose purpose) {

        /** Совместимый конструктор: назначение считается обычным ответом. */
        public AttemptUsage(Integer promptTokens, Integer completionTokens) {
            this(promptTokens, completionTokens, Purpose.REGULAR);
        }
    }

    /** Неизменяемый снимок для UI и тестов. */
    public Snapshot snapshot() {
        boolean complete = apiAttempts > 0
                && requestsWithoutUsage == 0
                && requestsWithPartialUsage == 0;
        return new Snapshot(apiAttempts, requestsWithUsage, requestsWithoutUsage,
                requestsWithPartialUsage, totalPromptTokens, totalCompletionTokens, complete,
                regularAttempts, regularPromptTokens, regularCompletionTokens,
                summaryAttempts, summaryPromptTokens, summaryCompletionTokens,
                compareAttempts, comparePromptTokens, compareCompletionTokens,
                factsAttempts, factsPromptTokens, factsCompletionTokens,
                contextSavingsPromptTokens, contextSavingsRequests,
                contextSavingsEstimatedRequests, contextSavingsUnknownRequests);
    }

    /**
     * Снимок накопленных фактических расходов сессии. totalPromptTokens и
     * totalCompletionTokens — суммы только по запросам с доступным usage по
     * всем назначениям; группы — детализация тех же запросов (двойного учёта
     * нет: сумма групп равна общим суммам); complete — признак полноты итога
     * (все запросы дали полный usage).
     */
    public record Snapshot(
            long apiAttempts,
            long requestsWithUsage,
            long requestsWithoutUsage,
            long requestsWithPartialUsage,
            long totalPromptTokens,
            long totalCompletionTokens,
            boolean complete,
            long regularAttempts,
            long regularPromptTokens,
            long regularCompletionTokens,
            long summaryAttempts,
            long summaryPromptTokens,
            long summaryCompletionTokens,
            long compareAttempts,
            long comparePromptTokens,
            long compareCompletionTokens,
            /** Попытки обновления блока фактов. */
            long factsAttempts,
            long factsPromptTokens,
            long factsCompletionTokens,
            /** Оценка (≈) накопленной экономии входа обычных запросов за счёт сжатия. */
            long contextSavingsPromptTokens,
            long contextSavingsRequests,
            long contextSavingsEstimatedRequests,
            long contextSavingsUnknownRequests) {

        /** Учтена ли экономия без зазора (все запросы с фактическим usage). */
        public boolean contextSavingsKnown() {
            return contextSavingsUnknownRequests == 0;
        }

        /** Есть ли хотя бы одна учтённая оценка различия входа. */
        public boolean contextSavingsAvailable() {
            return contextSavingsRequests > 0 || contextSavingsUnknownRequests > 0;
        }

        /**
         * Учтённый расход сессии: сумма известных prompt_tokens и
         * completion_tokens всех запросов (обычные ответы, суммаризация, факты
     * и сравнение; лимит сессии учитывает все назначения).
         * total_tokens повторно не прибавляется — это привело бы к двойному
         * учёту. Переполнение суммы (практически недостижимо) трактуется
         * безопасно: расход считается максимальным, то есть не меньше
         * любого лимита.
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
