package com.example;

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
 * - LLM_DIAGNOSTICS — краткие метрики запроса (true/false).
 *
 * Лимиты профилей — экспериментальные начальные значения (512/1024/2048),
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
        boolean diagnostics) {

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

    /** Максимум temperature по контракту OpenAI-совместимых эндпоинтов. */
    public static final double MAX_TEMPERATURE = 2.0;

    /** Настройки по умолчанию без чтения окружения (для тестов и базового конструктора). */
    public static ModelSettings defaults() {
        return new ModelSettings(DEFAULT_PROFILE, PROFILE_LIMITS.get(DEFAULT_PROFILE),
                false, null, DEFAULT_REQUEST_TIMEOUT_SECONDS, null, false);
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
        boolean diagnostics = readBoolean(env, "LLM_DIAGNOSTICS");
        return new ModelSettings(
                profile,
                limitOverride != null ? limitOverride : PROFILE_LIMITS.get(profile),
                limitOverride != null,
                temperature,
                timeout != null ? timeout : DEFAULT_REQUEST_TIMEOUT_SECONDS,
                contextMaxTurns,
                diagnostics);
    }

    /**
     * Тот же экземпляр с другим профилем. Явный LLM_MAX_OUTPUT_TOKENS
     * сохраняет приоритет: лимит не меняется, меняется только профиль
     * (и связанное с ним поведение системной инструкции).
     */
    public ModelSettings withProfile(String newProfile) {
        String normalized = normalizeProfile(newProfile);
        if (!PROFILE_LIMITS.containsKey(normalized)) {
            throw new AgentException("Неизвестный профиль ответа: " + newProfile
                    + ". Доступны: fast, balanced, detailed.");
        }
        int limit = limitOverridden ? maxOutputTokens : PROFILE_LIMITS.get(normalized);
        return new ModelSettings(normalized, limit, limitOverridden,
                temperature, requestTimeoutSeconds, contextMaxTurns, diagnostics);
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
