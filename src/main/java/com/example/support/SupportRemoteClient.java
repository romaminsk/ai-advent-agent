package com.example.support;

import com.example.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** HTTP-клиент к support-серверу: GET /health и POST /chat с Bearer-токеном. Токен не выводится. */
public final class SupportRemoteClient {

    public static final int HTTP_TIMEOUT_SECONDS = 200;

    private final String baseUrl;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public SupportRemoteClient(String baseUrl, String token) {
        this.baseUrl = baseUrl.strip().replaceAll("/+$", "");
        this.token = token;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public boolean health() {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() == 200 && response.body().contains("\"ok\"");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public SupportChatCli.Turn chat(String sessionId, String message, String ticket) throws SupportException {
        ObjectNode body = JsonSupport.MAPPER.createObjectNode();
        if (sessionId != null) {
            body.put("sessionId", sessionId);
        }
        body.put("message", message);
        if (ticket != null) {
            body.put("ticketId", ticket);
        }
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/chat"))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(JsonSupport.MAPPER.writeValueAsString(body),
                            StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new SupportException("таймаут ответа сервера поддержки (" + HTTP_TIMEOUT_SECONDS + " с).");
        } catch (IOException e) {
            throw new SupportException("сервер поддержки недоступен (" + baseUrl + ").");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SupportException("запрос к серверу поддержки прерван.");
        }
        if (response.statusCode() == 401) {
            throw new SupportException("сервер поддержки отклонил токен (401): проверьте SUPPORT_API_TOKEN в .env.");
        }
        JsonNode json;
        try {
            json = JsonSupport.MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new SupportException("сервер поддержки вернул HTTP " + response.statusCode() + " без JSON.");
        }
        if (response.statusCode() != 200) {
            throw new SupportException(json.path("error").asText("сервер поддержки вернул HTTP "
                    + response.statusCode() + "."));
        }
        List<String> sources = new ArrayList<>();
        json.path("sources").forEach(source -> sources.add(source.asText()));
        JsonNode ticketNode = json.path("ticket");
        String ticketLine = ticketNode.isObject() ? SupportChatCli.ticketLine(ticketNode) : null;
        JsonNode llm = json.path("llmSource");
        String source = llm.isNull() || llm.isMissingNode() ? null : llm.asText();
        return new SupportChatCli.Turn(json.path("sessionId").asText(), json.path("answer").asText(), sources,
                ticketLine, json.path("escalated").asBoolean(), source, json.path("durationMs").asLong());
    }
}
