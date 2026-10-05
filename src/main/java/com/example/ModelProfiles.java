package com.example;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Профили провайдера для команды /model: именованные настройки, из которых
 * собирается конфигурация агента без правки .env и без перезапуска.
 *
 * - cloud — профиль текущей конфигурации (Config.fromEnv: LLM_API_URL,
 *   LLM_MODEL, LLM_API_KEY) и таймаут настроек старта; без явного
 *   переключения агент работает с ним, прежнее поведение сохранено;
 * - ollama — локальная LLM: http://localhost:11434/v1/chat/completions,
 *   qwen2.5:3b, ключ-заглушка "ollama", таймаут 300 с; значения
 *   переопределяются необязательными переменными окружения
 *   OLLAMA_API_URL, OLLAMA_MODEL, OLLAMA_REQUEST_TIMEOUT_SECONDS.
 *
 * Правило транспорта проверяет Config (https для внешних адресов, открытый
 * HTTP только для loopback), поэтому профиль с http://example.com создастся
 * не будет: Config конструктор отклоняет такую комбинацию.
 */
public final class ModelProfiles {

    public record Profile(String name, String apiUrl, String model, String apiKey,
                          int requestTimeoutSeconds) {
        public Profile {
            Objects.requireNonNull(name, "имя профиля");
            Objects.requireNonNull(apiUrl, "apiUrl профиля");
            Objects.requireNonNull(model, "модель профиля");
            if (requestTimeoutSeconds <= 0) {
                throw new IllegalArgumentException(
                        "Таймаут профиля должен быть положительным: " + name);
            }
        }
    }

    public static final String CLOUD = "cloud";
    public static final String OLLAMA = "ollama";

    /** Заглушка ключа для локальных провайдеров без аутентификации. */
    public static final String OLLAMA_DEFAULT_KEY = "ollama";
    public static final String OLLAMA_DEFAULT_URL =
            "http://localhost:11434/v1/chat/completions";
    public static final String OLLAMA_DEFAULT_MODEL = "qwen2.5:3b";
    public static final int OLLAMA_DEFAULT_TIMEOUT_SECONDS = 300;

    /** Короткий таймаут проверки доступности локальной Ollama. */
    static final Duration OLLAMA_CHECK_TIMEOUT = Duration.ofSeconds(2);

    private ModelProfiles() {
    }

    /** Профиль cloud: конфигурация и таймаут, с которыми агент создан. */
    public static Profile cloud(Config config, int requestTimeoutSeconds) {
        return new Profile(CLOUD, config.apiUrl(), config.model(), config.apiKey(),
                requestTimeoutSeconds);
    }

    /** Профиль ollama: дефолты с необязательными env-переопределениями. */
    public static Profile ollama() {
        return ollama(System.getenv());
    }

    /** Профиль ollama по переданному окружению (для тестов). */
    public static Profile ollama(Map<String, String> env) {
        String url = firstNonBlank(env.get("OLLAMA_API_URL"), OLLAMA_DEFAULT_URL);
        String model = firstNonBlank(env.get("OLLAMA_MODEL"), OLLAMA_DEFAULT_MODEL);
        String key = firstNonBlank(env.get("OLLAMA_API_KEY"), OLLAMA_DEFAULT_KEY);
        int timeout = OLLAMA_DEFAULT_TIMEOUT_SECONDS;
        String timeoutEnv = env.get("OLLAMA_REQUEST_TIMEOUT_SECONDS");
        if (timeoutEnv != null && !timeoutEnv.isBlank()) {
            try {
                timeout = Integer.parseInt(timeoutEnv.trim());
            } catch (NumberFormatException e) {
                throw new AgentException(
                        "OLLAMA_REQUEST_TIMEOUT_SECONDS должна быть целым числом, получено: "
                                + timeoutEnv.trim(), e);
            }
            if (timeout <= 0) {
                throw new AgentException(
                        "OLLAMA_REQUEST_TIMEOUT_SECONDS должна быть положительным числом, "
                                + "получено: " + timeout);
            }
        }
        return new Profile(OLLAMA, url, model, key, timeout);
    }

    /** Профиль по имени; неизвестное имя — исключение со списком профилей. */
    public static Profile resolve(String name, Profile cloud) {
        String normalized = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case CLOUD -> cloud;
            case OLLAMA -> ollama();
            default -> throw new AgentException("Неизвестный профиль модели: " + name
                    + ". Доступны: " + namesForHelp() + ".");
        };
    }

    /** true, если имя — известный профиль (проверка сохранённого файла). */
    public static boolean known(String name) {
        return CLOUD.equalsIgnoreCase(name) || OLLAMA.equalsIgnoreCase(name);
    }

    public static String namesForHelp() {
        return CLOUD + ", " + OLLAMA;
    }

    /** Origin профиля (scheme://host[:port]) — адрес проверки /api/tags. */
    public static String origin(Profile profile) {
        URI uri = URI.create(profile.apiUrl());
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        return scheme + "://" + host + (port > 0 ? ":" + port : "");
    }

    /**
     * true, если провайдер отвечает: быстрый GET <origin>/api/tags с коротким
     * таймаутом. Сеть/таймаут/не-2xx — недоступен (статус 2xx и тело с
     * массивом models — как у Ollama).
     */
    public static boolean reachable(Profile profile) {
        return reachable(profile, OLLAMA_CHECK_TIMEOUT);
    }

    /** Вариант проверки с настраиваемым таймаутом (для тестов). */
    static boolean reachable(Profile profile, Duration timeout) {
        URI uri;
        try {
            uri = URI.create(profile.apiUrl());
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || scheme.isEmpty()) {
            return false;
        }
        URI tags = URI.create(scheme + "://" + host
                + (port > 0 ? ":" + port : "") + "/api/tags");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(tags)
                            .timeout(timeout)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300
                    && response.body().contains("models");
        } catch (HttpTimeoutException e) {
            return false;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
