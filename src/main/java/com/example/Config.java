package com.example;

import java.net.URI;

/**
 * Конфигурация агента: читает настройки из переменных окружения
 * и проверяет их корректность.
 *
 * Ожидаемые переменные:
 * - LLM_API_KEY  — ключ доступа к API (не логируем и не выводим);
 * - LLM_API_URL  — полный URL эндпоинта chat/completions;
 * - LLM_MODEL    — идентификатор модели, например glm-5.3-flash.
 */
public final class Config {

    private final String apiKey;
    private final String apiUrl;
    private final String model;

    public Config(String apiKey, String apiUrl, String model) {
        this.apiKey = requireNonBlank(apiKey, "LLM_API_KEY");
        this.apiUrl = requireNonBlank(apiUrl, "LLM_API_URL");
        this.model = requireNonBlank(model, "LLM_MODEL");
        checkHttpsUrl(this.apiUrl, "LLM_API_URL");
    }

    /** Загружает конфигурацию из переменных окружения. */
    public static Config fromEnv() {
        return new Config(
                System.getenv("LLM_API_KEY"),
                System.getenv("LLM_API_URL"),
                System.getenv("LLM_MODEL"));
    }

    public String apiKey() {
        return apiKey;
    }

    public String apiUrl() {
        return apiUrl;
    }

    public String model() {
        return model;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null) {
            throw new AgentException(
                    "Переменная окружения " + name + " не задана. "
                            + "Загрузите env-файл перед запуском: source .env");
        }
        if (value.isBlank()) {
            throw new AgentException(
                    "Переменная окружения " + name + " пуста. "
                            + "Укажите её значение в env-файле (.env).");
        }
        return value;
    }

    /** URL должен быть корректным адресом, пригодным для HTTPS-запроса. */
    private static void checkHttpsUrl(String url, String name) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new AgentException(
                    name + " не является корректным URL: " + url, e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new AgentException(
                    name + " должен быть HTTPS-адресом вида https://host/..., получено: " + url);
        }
    }
}
