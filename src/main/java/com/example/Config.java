package com.example;

import java.net.URI;

/**
 * Конфигурация агента: читает настройки из переменных окружения
 * и проверяет их корректность.
 *
 * Ожидаемые переменные:
 * - LLM_API_KEY  — ключ доступа к API (не логируем и не выводим);
 *   для локальных моделей без аутентификации допускается заглушка
 *   (например, "ollama"), в запрос она подставляется как любой ключ;
 * - LLM_API_URL  — полный URL эндпоинта chat/completions;
 *   облачным провайдерам нужен HTTPS; открытый HTTP разрешён только
 *   для loopback-адресов (localhost/127.0.0.1/::1) — локальная
 *   LLM вроде Ollama (http://localhost:11434/v1) без шифрования;
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
        checkApiUrl(this.apiUrl, "LLM_API_URL");
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

    /**
     * Для полностью локального RAG: адрес обязан быть loopback
     * (localhost/127.0.0.1/::1). Используется профилем ollama и защитой
     * эмбеддера EMBEDDING_BASE_URL; бросает AgentException на другой адрес.
     */
    public static void requireLoopbackUrl(String url, String name) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new AgentException(name + " не является корректным URL: " + url, e);
        }
        if (!isLoopbackHost(uri.getHost())) {
            throw new AgentException(
                    name + " в локальном режиме должен указывать на loopback "
                            + "(localhost, 127.0.0.1, ::1), получено: " + url);
        }
    }

    /** URL должен быть HTTPS-адресом; loopback дополнительно допускает открытый HTTP. */
    private static void checkApiUrl(String url, String name) {        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new AgentException(
                    name + " не является корректным URL: " + url, e);
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || scheme == null) {
            throw new AgentException(
                    name + " должен быть адресом вида https://host/... (или "
                            + "http://localhost:port/... для локальной LLM), получено: " + url);
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return;
        }
        if ("http".equalsIgnoreCase(scheme) && isLoopbackHost(uri.getHost())) {
            return;
        }
        throw new AgentException(
                name + " должен быть HTTPS-адресом вида https://host/...; открытый HTTP "
                        + "допускается только для локальной LLM на loopback "
                        + "(localhost/127.0.0.1/::1), получено: " + url);
    }

    /** true для loopback-хостов: localhost, домен localhost.* и литеральные адреса. */
    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("localhost")
                || normalized.startsWith("localhost.")
                || normalized.endsWith(".localhost")
                || normalized.equals("localhost.localdomain")) {
            return true;
        }
        try {
            return java.net.InetAddress.getByName(host).isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }
}
