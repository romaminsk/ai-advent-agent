package com.example;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Централизованные настройки модели и запроса: профиль ответа, лимит
 * генерации, temperature, таймаут запроса, лимит отправляемого контекста
 * и режим диагностики. Все значения читаются из переменных окружения
 * в одном месте и не размазываются по Main, UI и LlmAgent.
 *
 * Это имена нашей конфигурации, а не новые поля API:
 * - LLM_RESPONSE_MODE — профиль: fast, balanced или detailed;
 * - LLM_MAX_OUTPUT_TOKENS — необязательное переопределение лимита генерации
 *   (обладает приоритетом над профилем, в том числе после /mode);
 * - LLM_TEMPERATURE — необязательное переопределение temperature
 *   (если не задано, поле в запрос не включается);
 * - LLM_REQUEST_TIMEOUT_SECONDS — таймаут ожидания ответа (по умолчанию 180);
 * - LLM_CONTEXT_MAX_TURNS — необязательный предел пар, отправляемых в API
 *   (файл истории не урезается — ограничивается только запрос);
 * - LLM_CONTEXT_WINDOW_TOKENS — необязательный контекстный бюджет модели
 *   в токенах (ручная настройка: подтверждённый лимит glm-5.3-flash
 *   в код не зашит и не угадывается);
 * - LLM_CONTEXT_OVERFLOW_POLICY — поведение при прогнозируемом превышении
 *   бюджета: warn (предупредить и отправить) или block (локальная блокировка);
 * - LLM_INPUT_PRICE_PER_1M и LLM_OUTPUT_PRICE_PER_1M — необязательный тариф
 *   «USD за 1 000 000 входных/выходных токенов»; отсутствие значения не равно 0;
 * - LLM_SESSION_TOKEN_LIMIT — необязательный информационный лимит расхода
 *   токенов за сессию (сумма известных prompt_tokens и completion_tokens);
 *   уведомление при превышении, не жёсткая квота;
 * - LLM_CONTEXT_MODE — режим контекста: full (полная история,
 *   по умолчанию) или summary (резюме старой части + несжатый хвост);
 * - LLM_CONTEXT_KEEP_LAST_MESSAGES — положительное чётное число последних
 *   сообщений, всегда сохраняемых дословно (по умолчанию 10);
 * - LLM_SUMMARY_BATCH_MESSAGES — положительное чётное число новых старых
 *   сообщений, после которого обновляется резюме (по умолчанию 10);
 * - LLM_SUMMARY_MAX_OUTPUT_TOKENS — положительный отдельный лимит генерации
 *   для запроса суммаризации (по умолчанию 512); обычный лимит ответа
 *   не меняет;
 * - LLM_DIAGNOSTICS — краткие метрики запроса (true/false);
 * - LLM_CONTEXT_STRATEGY — стратегия управления контекстом:
 *   sliding-window (по умолчанию), facts или branching;
 * - LLM_SLIDING_WINDOW_MESSAGES — положительное чётное число последних
 *   сообщений, отправляемых в запросе стратегии sliding-window
 *   (по умолчанию 10);
 * - LLM_FACTS_WINDOW_MESSAGES — положительное чётное число последних
 *   сообщений стратегии facts (по умолчанию 10);
 * - LLM_FACTS_MAX_OUTPUT_TOKENS — положительный отдельный лимит генерации
 *   служебного запроса обновления facts (по умолчанию 2048);
 * - LLM_FACTS_UPDATE_MODE — auto (обновление после каждого сообщения
 *   пользователя, по умолчанию) или manual (только /facts refresh).
 * 
 * Примечание: LLM_CONTEXT_MAX_TURNS в режимах контекста full/summary
 * больше не ограничивает отправку (см. README); о заданной переменной
 * приложение сообщает явно.
 *
 * Лимиты профилей — экспериментальные начальные значения,
 * а не проверенные оптимумы для glm-5.3-flash. max_tokens — верхний предел
 * генерации, а не обязательная длина ответа; его уменьшение не ускоряет
 * генерацию до первого токена.
 */
public record ModelSettings(
        String profile,
        int maxOutputTokens,
        boolean limitOverridden,
        Double temperature,
        int requestTimeoutSeconds,
        Integer contextMaxTurns,
        Integer contextWindowTokens,
        ContextOverflowPolicy overflowPolicy,
        BigDecimal inputPricePer1M,
        BigDecimal outputPricePer1M,
        Long sessionTokenLimit,
        boolean diagnostics,
        ContextMode contextMode,
        int keepLastMessages,
        int summaryBatchMessages,
        int summaryMaxOutputTokens,
        ContextStrategy contextStrategy,
        int slidingWindowMessages,
        int factsWindowMessages,
        int factsMaxOutputTokens,
        FactsUpdateMode factsUpdateMode) {

    public static final String FAST = "fast";
    public static final String BALANCED = "balanced";
    public static final String DETAILED = "detailed";

    /**
     * Экспериментальные начальные лимиты профилей, не проверенные оптимумы.
     * Реальный случай glm-5.3-flash: лимит 1024 (бывший balanced) был
     * израсходован внутренними рассуждениями модели до видимого текста
     * (finish_reason: length, пустой content) — поэтому лимиты подняты:
     * 1024/2048/4096.
     */
    public static final Map<String, Integer> PROFILE_LIMITS =
            Map.of(FAST, 1024, BALANCED, 2048, DETAILED, 4096);

    /**
     * Профиль по умолчанию. balanced, а не fast: у модели с внутренними
     * рассуждениями слишком маленький лимит может быть израсходован на
     * reasoning и дать пустой видимый ответ.
     */
    public static final String DEFAULT_PROFILE = BALANCED;

    public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 180;

    /** Настройки сжатия истории по умолчанию. */
    public static final int DEFAULT_KEEP_LAST_MESSAGES = 10;
    public static final int DEFAULT_SUMMARY_BATCH_MESSAGES = 10;
    public static final int DEFAULT_SUMMARY_MAX_OUTPUT_TOKENS = 512;

    /**
     * Настройки стратегий управления контекстом по умолчанию.
     * Лимит 2048 для обновления фактов: блок памяти диалога средней длины
     * на reasoning-модели glm-5.3-flash не укладывался в 512 (реальный
     * прогон закончился finish_reason: length с пустым блоком фактов).
     */
    public static final int DEFAULT_SLIDING_WINDOW_MESSAGES = 10;
    public static final int DEFAULT_FACTS_WINDOW_MESSAGES = 10;
    public static final int DEFAULT_FACTS_MAX_OUTPUT_TOKENS = 2048;

    /** Максимум temperature по контракту OpenAI-совместимых эндпоинтов. */
    public static final double MAX_TEMPERATURE = 2.0;

    /** Настройки по умолчанию без чтения окружения (для тестов и базового конструктора). */
    public static ModelSettings defaults() {
        return new ModelSettings(DEFAULT_PROFILE, PROFILE_LIMITS.get(DEFAULT_PROFILE),
                false, null, DEFAULT_REQUEST_TIMEOUT_SECONDS, null,
                null, ContextOverflowPolicy.DEFAULT, null, null, null, false,
                ContextMode.DEFAULT, DEFAULT_KEEP_LAST_MESSAGES,
                DEFAULT_SUMMARY_BATCH_MESSAGES, DEFAULT_SUMMARY_MAX_OUTPUT_TOKENS,
                ContextStrategy.DEFAULT, DEFAULT_SLIDING_WINDOW_MESSAGES,
                DEFAULT_FACTS_WINDOW_MESSAGES, DEFAULT_FACTS_MAX_OUTPUT_TOKENS,
                FactsUpdateMode.DEFAULT);
    }

    /** Читает настройки из переменных окружения. */
    public static ModelSettings fromEnv() {
        return from(System.getenv());
    }

    /** Читает настройки из переданного набора переменных (для тестов). */
    static ModelSettings from(Map<String, String> env) {
        String profile = readProfile(env);
        Integer limitOverride = readOptionalPositiveInt(env, "LLM_MAX_OUTPUT_TOKENS");
        Double temperature = readTemperature(env);
        Integer timeout = readOptionalPositiveInt(env, "LLM_REQUEST_TIMEOUT_SECONDS");
        Integer contextMaxTurns = readOptionalPositiveInt(env, "LLM_CONTEXT_MAX_TURNS");
        Integer contextWindowTokens = readOptionalPositiveInt(env, "LLM_CONTEXT_WINDOW_TOKENS");
        ContextOverflowPolicy overflowPolicy =
                ContextOverflowPolicy.parse(env.get("LLM_CONTEXT_OVERFLOW_POLICY"),
                        "LLM_CONTEXT_OVERFLOW_POLICY");
        BigDecimal inputPrice = readOptionalPrice(env, "LLM_INPUT_PRICE_PER_1M");
        BigDecimal outputPrice = readOptionalPrice(env, "LLM_OUTPUT_PRICE_PER_1M");
        Long sessionTokenLimit = readOptionalPositiveLong(env, "LLM_SESSION_TOKEN_LIMIT");
        boolean diagnostics = readBoolean(env, "LLM_DIAGNOSTICS");
        ContextMode contextMode = ContextMode.parse(env.get("LLM_CONTEXT_MODE"),
                "LLM_CONTEXT_MODE");
        int keepLastMessages = readOptionalEvenPositiveInt(env, "LLM_CONTEXT_KEEP_LAST_MESSAGES",
                DEFAULT_KEEP_LAST_MESSAGES);
        int summaryBatchMessages = readOptionalEvenPositiveInt(env, "LLM_SUMMARY_BATCH_MESSAGES",
                DEFAULT_SUMMARY_BATCH_MESSAGES);
        Integer summaryMaxOverride = readOptionalPositiveInt(env, "LLM_SUMMARY_MAX_OUTPUT_TOKENS");
        int summaryMaxOutputTokens = summaryMaxOverride != null
                ? summaryMaxOverride
                : DEFAULT_SUMMARY_MAX_OUTPUT_TOKENS;
        ContextStrategy contextStrategy = ContextStrategy.parse(env.get("LLM_CONTEXT_STRATEGY"),
                "LLM_CONTEXT_STRATEGY");
        int slidingWindowMessages = readOptionalEvenPositiveInt(env, "LLM_SLIDING_WINDOW_MESSAGES",
                DEFAULT_SLIDING_WINDOW_MESSAGES);
        int factsWindowMessages = readOptionalEvenPositiveInt(env, "LLM_FACTS_WINDOW_MESSAGES",
                DEFAULT_FACTS_WINDOW_MESSAGES);
        Integer factsMaxOverride = readOptionalPositiveInt(env, "LLM_FACTS_MAX_OUTPUT_TOKENS");
        int factsMaxOutputTokens = factsMaxOverride != null
                ? factsMaxOverride
                : DEFAULT_FACTS_MAX_OUTPUT_TOKENS;
        FactsUpdateMode factsUpdateMode =
                FactsUpdateMode.parse(env.get("LLM_FACTS_UPDATE_MODE"), "LLM_FACTS_UPDATE_MODE");
        return new ModelSettings(
                profile,
                limitOverride != null ? limitOverride : PROFILE_LIMITS.get(profile),
                limitOverride != null,
                temperature,
                timeout != null ? timeout : DEFAULT_REQUEST_TIMEOUT_SECONDS,
                contextMaxTurns,
                contextWindowTokens,
                overflowPolicy,
                inputPrice,
                outputPrice,
                sessionTokenLimit,
                diagnostics,
                contextMode,
                keepLastMessages,
                summaryBatchMessages,
                summaryMaxOutputTokens,
                contextStrategy,
                slidingWindowMessages,
                factsWindowMessages,
                factsMaxOutputTokens,
                factsUpdateMode);
    }

    /**
     * Тот же экземпляр с другим профилем. Явный LLM_MAX_OUTPUT_TOKENS
     * сохраняет приоритет: лимит не меняется, меняется только профиль
     * (и связанное с ним поведение системной инструкции). Тарифы и
     * контекстный бюджет сменой профиля не меняются.
     */
    public ModelSettings withProfile(String newProfile) {
        String normalized = normalizeProfile(newProfile);
        if (!PROFILE_LIMITS.containsKey(normalized)) {
            throw new AgentException("Неизвестный профиль ответа: " + newProfile
                    + ". Доступны: fast, balanced, detailed.");
        }
        int limit = limitOverridden ? maxOutputTokens : PROFILE_LIMITS.get(normalized);
        return new ModelSettings(normalized, limit, limitOverridden,
                temperature, requestTimeoutSeconds, contextMaxTurns,
                contextWindowTokens, overflowPolicy, inputPricePer1M, outputPricePer1M,
                sessionTokenLimit, diagnostics, contextMode,
                keepLastMessages, summaryBatchMessages, summaryMaxOutputTokens,
                contextStrategy, slidingWindowMessages, factsWindowMessages,
                factsMaxOutputTokens, factsUpdateMode);
    }

    /**
     * Тот же экземпляр с другим лимитом расхода токенов за сессию (/limit). Запись неизменяема: метод возвращает копию. null — лимит
     * отключён; накопленный расход и счётчики при смене не сбрасываются.
     */
    public ModelSettings withSessionTokenLimit(Long newLimit) {
        return new ModelSettings(profile, maxOutputTokens, limitOverridden,
                temperature, requestTimeoutSeconds, contextMaxTurns,
                contextWindowTokens, overflowPolicy, inputPricePer1M, outputPricePer1M,
                newLimit, diagnostics, contextMode,
                keepLastMessages, summaryBatchMessages, summaryMaxOutputTokens,
                contextStrategy, slidingWindowMessages, factsWindowMessages,
                factsMaxOutputTokens, factsUpdateMode);
    }

    /**
     * Тот же экземпляр с другим режимом контекста (/context full|summary).
     * Настройки сжатия и прочие значения не меняются; API команда не вызывает.
     */
    public ModelSettings withContextMode(ContextMode newMode) {
        return new ModelSettings(profile, maxOutputTokens, limitOverridden,
                temperature, requestTimeoutSeconds, contextMaxTurns,
                contextWindowTokens, overflowPolicy, inputPricePer1M, outputPricePer1M,
                sessionTokenLimit, diagnostics, newMode,
                keepLastMessages, summaryBatchMessages, summaryMaxOutputTokens,
                contextStrategy, slidingWindowMessages, factsWindowMessages,
                factsMaxOutputTokens, factsUpdateMode);
    }

    /**
     * Тот же экземпляр с другой стратегией управления контекстом
     * (/strategy). Все прочие значения не меняются; API команда не вызывает,
     * историю и факты не меняет.
     */
    public ModelSettings withStrategy(ContextStrategy newStrategy) {
        return new ModelSettings(profile, maxOutputTokens, limitOverridden,
                temperature, requestTimeoutSeconds, contextMaxTurns,
                contextWindowTokens, overflowPolicy, inputPricePer1M, outputPricePer1M,
                sessionTokenLimit, diagnostics, contextMode,
                keepLastMessages, summaryBatchMessages, summaryMaxOutputTokens,
                newStrategy, slidingWindowMessages, factsWindowMessages,
                factsMaxOutputTokens, factsUpdateMode);
    }

    /** Лимит отправляемых в API пар: явная настройка или прежнее поведение. */
    public int effectiveContextMaxTurns(int defaultTurns) {
        return contextMaxTurns != null ? contextMaxTurns : defaultTurns;
    }

    private static String readProfile(Map<String, String> env) {
        String value = env.get("LLM_RESPONSE_MODE");
        if (value == null || value.isBlank()) {
            return DEFAULT_PROFILE;
        }
        String normalized = normalizeProfile(value);
        if (!PROFILE_LIMITS.containsKey(normalized)) {
            throw new AgentException("LLM_RESPONSE_MODE: неизвестный профиль '" + value.trim()
                    + "'. Доступны: fast, balanced, detailed.");
        }
        return normalized;
    }

    private static String normalizeProfile(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Integer readOptionalPositiveInt(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new AgentException(name + " должна быть целым числом, получено: "
                    + value.trim() + ".", e);
        }
        if (parsed <= 0) {
            throw new AgentException(name + " должна быть положительным числом, получено: "
                    + parsed + ".");
        }
        return parsed;
    }

    private static Double readTemperature(Map<String, String> env) {
        String value = env.get("LLM_TEMPERATURE");
        if (value == null || value.isBlank()) {
            return null;
        }
        double parsed;
        try {
            parsed = Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new AgentException("LLM_TEMPERATURE должна быть числом, получено: "
                    + value.trim() + ".", e);
        }
        if (!Double.isFinite(parsed) || parsed < 0 || parsed > MAX_TEMPERATURE) {
            throw new AgentException("LLM_TEMPERATURE должна быть конечным числом "
                    + "в диапазоне от 0 до " + formatNumber(MAX_TEMPERATURE)
                    + ", получено: " + value.trim() + ".");
        }
        return parsed;
    }

    /**
     * Необязательный неотрицательный тариф «USD за 1 000 000 токенов».
     * Отсутствие значения — null (не равно 0): без тарифа стоимость
     * «нет данных». Явный 0 допустим. Дробные значения — через точку.
     */
    private static BigDecimal readOptionalPrice(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new AgentException(name + " должна быть неотрицательным числом "
                    + "(USD за 1 000 000 токенов, дробная часть через точку), получено: "
                    + value.trim() + ".", e);
        }
        if (parsed.signum() < 0) {
            throw new AgentException(name + " не может быть отрицательной, получено: "
                    + value.trim() + ".");
        }
        return parsed;
    }

    /**
     * Необязательный положительный целый лимит расхода токенов за сессию.
     * Отсутствие значения — лимит отключён. 0, отрицательные, дробные числа,
     * текст и переполнение диапазона long дают понятную ошибку конфигурации.
     */
    private static Long readOptionalPositiveLong(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        long parsed;
        try {
            parsed = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            // Включая переполнение допустимого диапазона.
            throw new AgentException(name + " должна быть положительным целым числом "
                    + "(без дробной части, в пределах " + Long.MAX_VALUE + "), получено: "
                    + value.trim() + ".", e);
        }
        if (parsed <= 0) {
            throw new AgentException(name + " должна быть положительным целым числом, "
                    + "получено: " + parsed + ".");
        }
        return parsed;
    }

    /**
     * Необязательное положительное чётное число (параметры сжатия).
     * Отсутствие значения — переданное значение по умолчанию. Ноль,
     * нечётные, текст и прочие ошибки дают понятное сообщение.
     */
    private static int readOptionalEvenPositiveInt(Map<String, String> env, String name,
                                                   int defaultValue) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new AgentException(name + " должна быть положительным чётным числом, "
                    + "получено: " + value.trim() + ".", e);
        }
        if (parsed <= 0 || parsed % 2 != 0) {
            throw new AgentException(name + " должна быть положительным чётным числом, "
                    + "получено: " + parsed + ".");
        }
        return parsed;
    }

    private static boolean readBoolean(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }
        throw new AgentException(name + " должна быть true или false, получено: "
                + value.trim() + ".");
    }

    private static String formatNumber(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
