package com.example.rag;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Полностью локальный RAG: фактический num_ctx модели тега Ollama.
 *
 * Правила источника значения (без «по памяти»):
 *  1. GET {origin}/api/ps — если модель загружена, сервер отдаёт
 *     context_length, под которым она реально работает сейчас;
 *  2. GET {origin}/api/show — иначе разбор PARAMETER num_ctx из
 *     Modelfile-параметров тега (ответ содержит поле parameters);
 *  3. ничего не найдено — null: вызывающий код обязан предупредить
 *     явно, а не подставлять молчаливый дефолт.
 *
 * Запросы идут только на loopback: адрес приводится к origin
 * (scheme://host[:port]) и проверяется Config.requireLoopbackUrl —
 * HTTP без шифрования допускается лишь для localhost/127.0.0.1/::1.
 * Первое успешное значение кэшируется по model@origin и не перезаписывается.
 */
public final class OllamaContextProbe {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Map<String, Integer> CACHE = new ConcurrentHashMap<>();

    private OllamaContextProbe() {
    }

    /**
     * Фактический контекст модели (токены) с кэшем; null — определить нельзя.
     * probeOverride — точка подмены своих запросов для тестов.
     */
    public static Integer contextLength(String model, String origin) {
        return contextLength(model, origin, null);
    }

    static Integer contextLength(String model, String origin, Prober probeOverride) {
        Objects.requireNonNull(model, "model");
        String base = origin == null ? "" : origin.replaceAll("/+$", "");
        if (base.isBlank()) return null;
        try {
            URI parsed = URI.create(base);
            String host = parsed.getHost();
            if (host == null || parsed.getScheme() == null) return null;
            int port = parsed.getPort();
            base = parsed.getScheme() + "://" + host + (port > 0 ? ":" + port : "");
            com.example.Config.requireLoopbackUrl(base, "Ollama probe url");
        } catch (Exception e) {
            return null;
        }
        Prober probe = probeOverride != null ? probeOverride : OllamaContextProbe::httpGet;
        String key = model + "@" + base;
        Integer cached = CACHE.get(key);
        if (cached != null) return cached;

        String ps;
        String show;
        try {
            ps = probe.get(base + "/api/ps");
            show = probe.get(base + "/api/show?" + urlencode("name", model));
        } catch (Exception unavailable) {
            return null;
        }
        Integer live = fromPs(ps, model);
        if (live != null) {
            CACHE.put(key, live);
            return live;
        }
        Integer parameters = fromShowParameters(show);
        if (parameters != null) {
            CACHE.put(key, parameters);
            return parameters;
        }
        return null;
    }

    /** Только тестам: очистить кэш между проверками. */
    static void clearCacheForTests() {
        CACHE.clear();
    }

    interface Prober {
        String get(String url) throws IOException, InterruptedException;
    }

    private static String httpGet(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
        HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(REQUEST_TIMEOUT)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return null;
        }
        try (InputStream body = response.body()) {
            return new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** context_length из /api/ps для загруженной модели (JSON-массив models). */
    public static Integer fromPs(String body, String model) {
        if (body == null) return null;
        try {
            var root = com.example.JsonSupport.MAPPER.readTree(body);
            if (root == null || !root.path("models").isArray()) return null;
            for (var item : root.path("models")) {
                if (model.equals(item.path("name").asText(""))) {
                    return intOrNull(item.get("context_length"));
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** PARAMETER num_ctx N из поля parameters ответа /api/show. */
    public static Integer fromShowParameters(String body) {
        if (body == null) return null;
        try {
            var root = com.example.JsonSupport.MAPPER.readTree(body);
            String parameters = root.path("parameters").asText("");
            var matcher = java.util.regex.Pattern
                    .compile("(?im)^num_ctx\\s+(\\d+)\\s*$").matcher(parameters);
            return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer intOrNull(com.fasterxml.jackson.databind.JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    private static String urlencode(String key, String value) {
        return java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8) + "="
                + java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
