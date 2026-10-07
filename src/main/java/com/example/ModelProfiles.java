package com.example;

import com.example.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;
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
 *   Профиль жёстко ограничен loopback-адресами: даже https на внешний
 *   узел отклоняется — RAG-путь в этом режиме не должен выходить в сеть.
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

    /**
     * Дефолтный лимит выхода RAG-ответа для локального 3B-профиля: вместе
     * с num_ctx 8192 гарантирует запас на контекст. Настраивается
     * OLLAMA_RAG_MAX_OUTPUT_TOKENS; профиль cloud использует
     * RagConstants.RAG_MAX_OUTPUT_TOKENS без изменений.
     */
    public static final int LOCAL_PROFILE_RAG_MAX_OUTPUT_TOKENS = 1024;
    public static final String LOCAL_RAG_MAX_OUTPUT_TOKENS_ENV = "OLLAMA_RAG_MAX_OUTPUT_TOKENS";

    /** Лимит выхода RAG-ответа для ollama-профиля по окружению. */
    public static int localRagMaxOutputTokens(Map<String, String> env) {
        String raw = env.get(LOCAL_RAG_MAX_OUTPUT_TOKENS_ENV);
        if (raw == null || raw.isBlank()) return LOCAL_PROFILE_RAG_MAX_OUTPUT_TOKENS;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1) {
                throw new AgentException(LOCAL_RAG_MAX_OUTPUT_TOKENS_ENV
                        + " должна быть положительным числом, получено: " + value);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new AgentException(LOCAL_RAG_MAX_OUTPUT_TOKENS_ENV
                    + " должна быть целым числом, получено: " + raw.trim(), e);
        }
    }

    /** Лимит выхода RAG-ответа для ollama-профиля по текущему окружению. */
    public static int localRagMaxOutputTokens() {
        return localRagMaxOutputTokens(System.getenv());
    }

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
        Profile profile = buildOllamaProfile(env);
        // Локальный профиль не должен отправлять RAG-запросы на внешние узлы:
        // https-адрес Config бы пропустил, поэтому здесь проверка строже.
        Config.requireLoopbackUrl(profile.apiUrl(), "OLLAMA_API_URL");
        return profile;
    }

    /** Имя модели тега реально есть в /api/tags локального сервера. */
    public static boolean modelExists(Profile profile) {
        return modelExists(profile, OLLAMA_CHECK_TIMEOUT);
    }

    /** Вариант проверки с настраиваемым таймаутом (для тестов). */
    static boolean modelExists(Profile profile, Duration timeout) {
        String body = tagsBody(profile, timeout);
        if (body == null) return false;
        try {
            JsonNode models = JsonSupport.MAPPER.readTree(body).path("models");
            if (!models.isArray()) return false;
            String target = profile.model();
            for (JsonNode item : models) {
                String name = item.path("name").asText("");
                if (name.equals(target)) return true;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /** Тело /api/tags или null (сеть/таймаут/не-2xx). */
    private static String tagsBody(Profile profile, Duration timeout) {
        URI uri;
        try {
            uri = URI.create(profile.apiUrl());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || scheme.isEmpty()) {
            return null;
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
                    ? response.body() : null;
        } catch (HttpTimeoutException e) {
            return null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Profile buildOllamaProfile(Map<String, String> env) {
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
        String body = tagsBody(profile, OLLAMA_CHECK_TIMEOUT);
        return body != null && body.contains("models");
    }

    /** Вариант проверки с настраиваемым таймаутом (для тестов). */
    static boolean reachable(Profile profile, Duration timeout) {
        String body = tagsBody(profile, timeout);
        return body != null && body.contains("models");
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
