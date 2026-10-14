package com.example.support;

import com.example.Config;
import com.example.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** HTTP-клиент к мини-CRM: GET с Bearer-токеном; адрес обязан быть loopback. */
final class CrmClient {

    enum Kind { NOT_FOUND, UNAUTHORIZED, UNAVAILABLE, BAD_RESPONSE }

    static final class CrmException extends Exception {
        private final Kind kind;

        CrmException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        Kind kind() {
            return kind;
        }
    }

    private final URI base;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    CrmClient(String baseUrl, String token) {
        Config.requireLoopbackUrl(baseUrl, "CRM_URL");
        this.base = URI.create(baseUrl.strip());
        this.token = token;
    }

    JsonNode get(String path) throws CrmException {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CrmException(Kind.UNAVAILABLE, "CRM недоступна по адресу " + base + ".");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CrmException(Kind.UNAVAILABLE, "запрос к CRM прерван.");
        }
        return switch (response.statusCode()) {
            case 200 -> parse(response.body());
            case 401 -> throw new CrmException(Kind.UNAUTHORIZED, "CRM отклонила токен (проверьте CRM_API_TOKEN).");
            case 404 -> throw new CrmException(Kind.NOT_FOUND, "не найден в CRM.");
            default -> throw new CrmException(Kind.BAD_RESPONSE, "CRM вернула неожиданный ответ: HTTP "
                    + response.statusCode() + ".");
        };
    }

    private static JsonNode parse(String body) throws CrmException {
        try {
            return JsonSupport.MAPPER.readTree(body);
        } catch (IOException e) {
            throw new CrmException(Kind.BAD_RESPONSE, "CRM вернула некорректный JSON.");
        }
    }
}
