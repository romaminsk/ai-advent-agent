package com.example.index;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Эмбеддер в OpenAI-совместимом формате: POST {base}/embeddings
 * с телом {model, input: [...]} и разбором data[].embedding.
 * Провайдер меняется только через .env (EMBEDDING_BASE_URL, EMBEDDING_MODEL).
 * Повтор при 429/5xx (до 2 раз, паузы 2 и 5 секунд); таймаут не повторяется.
 */
public final class OpenAiEmbedder implements Embedder {

    public static final int BATCH_LIMIT = 16;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final long[] RETRY_PAUSE_MS = {2000, 5000};

    private final String endpoint;
    private final String model;
    private final HttpClient http;
    private final Duration timeout;
    private final int batchLimit;

    public OpenAiEmbedder(String baseUrl, String model) {
        this(baseUrl, model, REQUEST_TIMEOUT, BATCH_LIMIT);
    }

    /** Полный конструктор для тестов (таймаут и лимит батча подменяются). */
    public OpenAiEmbedder(String baseUrl, String model, Duration timeout, int batchLimit) {
        String clean = baseUrl == null ? "" : baseUrl.trim();
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.isBlank() || !clean.startsWith("http://")) {
            throw new IllegalArgumentException(
                    "EMBEDDING_BASE_URL должен начинаться с http:// или https://");
        }
        try {
            URI.create(clean + "/embeddings");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("EMBEDDING_BASE_URL не корректный URL", e);
        }
        this.endpoint = clean + "/embeddings";
        this.model = java.util.Objects.requireNonNull(model, "модель эмбеддингов");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.timeout = timeout;
        this.batchLimit = batchLimit;
    }

    @Override
    public int batchSize() {
        return batchLimit;
    }

    /** Один батч текстов → список векторов того же размера. */
    @Override
    public List<float[]> embed(List<String> texts)
            throws IOException, InterruptedException {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        StringBuilder body = new StringBuilder();
        body.append("{\"model\":\"").append(model).append("\",\"input\":[");
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append(com.fasterxml.jackson.databind.node.TextNode.valueOf(texts.get(i))
                    .toString());
        }
        body.append("]}");
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        int attempt = 0;
        while (true) {
            try {
                HttpResponse<byte[]> response = http.send(request,
                        HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status == 200) {
                    return parseEmbeddings(response.body(), texts.size());
                }
                if (status == 429 || status >= 500) {
                    if (attempt < RETRY_PAUSE_MS.length) {
                        Thread.sleep(RETRY_PAUSE_MS[attempt]);
                        attempt++;
                        continue;
                    }
                    throw new IOException("Эмбеддинги: HTTP " + status
                            + " после " + (attempt + 1) + " попыток: "
                            + firstText(response.body()));
                }
                throw new IOException("Эмбеддинги: HTTP " + status + ": "
                        + firstText(response.body()));
            } catch (java.net.http.HttpTimeoutException e) {
                // Таймаут запроса не повторяем.
                throw new IOException("Эмбеддинги: таймаут запроса без повтора", e);
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException e) {
                // Обрыв соединения — повторяем как 5xx, без повтора на таймаут.
                if (isTimeoutLike(e)) {
                    throw e instanceof IOException io ? io : new IOException(e);
                }
                if (attempt < RETRY_PAUSE_MS.length) {
                    Thread.sleep(RETRY_PAUSE_MS[attempt]);
                    attempt++;
                    continue;
                }
                throw new IOException("Эмбеддинги: сеть недоступна после "
                        + (attempt + 1) + " попыток: " + e.getMessage(), e);
            }
        }
    }

    private static boolean isTimeoutLike(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.time.temporal.TemporalAccessor) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** Разбор ответа {"data":[{"embedding":[...]}]} в порядке входа. */
    private static List<float[]> parseEmbeddings(byte[] body, int expected)
            throws IOException {
        com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        if (root == null || !root.has("data") || !root.get("data").isArray()) {
            throw new IOException("Эмбеддинги: неожиданный ответ без data[]");
        }
        com.fasterxml.jackson.databind.JsonNode data = root.get("data");
        if (data.size() != expected) {
            throw new IOException("Эмбеддинги: получено " + data.size()
                    + " векторов из " + expected);
        }
        List<float[]> vectors = new ArrayList<>(data.size());
        for (com.fasterxml.jackson.databind.JsonNode item : data) {
            com.fasterxml.jackson.databind.JsonNode vectorNode = item.get("embedding");
            if (vectorNode == null || !vectorNode.isArray()) {
                throw new IOException("Эмбеддинги: вектор отсутствует в ответе");
            }
            float[] vector = new float[vectorNode.size()];
            for (int i = 0; i < vectorNode.size(); i++) {
                vector[i] = (float) vectorNode.get(i).asDouble();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private static String firstText(byte[] body) {
        if (body == null) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
