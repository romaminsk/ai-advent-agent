package com.example;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Отдельный агент, инкапсулирующий всё общение с LLM:
 * проверку запроса, формирование JSON с историей диалога, отправку по HTTP,
 * обработку ошибок и разбор ответа.
 *
 * Агент владеет историей беседы: завершённые пары user/assistant хранятся
 * только в памяти и передаются в API явно через массив messages
 * (идентификатор сессии сам по себе память не обеспечивает).
 * HttpClient и ObjectMapper создаются один раз и переиспользуются.
 */
public final class LlmAgent {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(180);

    /** Постоянная системная инструкция; отправляется первым сообщением каждого запроса. */
    private static final String SYSTEM_PROMPT =
            "Ты полезный ассистент. Учитывай историю диалога. "
                    + "Отвечай на языке пользователя, если он не попросил иначе. "
                    + "Если информации недостаточно, уточни вопрос.";

    /** Максимум завершённых пар user/assistant в одном запросе и в памяти. */
    public static final int MAX_HISTORY_TURNS = 20;

    private final Config config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Идентификатор текущей беседы. Шлюз OpenCode Go требует заголовок
     * x-opencode-session на каждый запрос (официальная документация провайдера:
     * opencode.ai/docs/go — «Send a stable session ID in x-opencode-session
     * for each conversation»). Одна беседа = период до {@link #resetConversation()}.
     * Важно: session ID не заменяет историю — контекст всегда передаётся через messages.
     */
    private String sessionId = UUID.randomUUID().toString();

    /**
     * Завершённые пары user/assistant текущей беседы (system-сообщение здесь
     * не хранится, оно добавляется при формировании каждого запроса).
     * История живёт только в памяти и исчезает при завершении работы.
     */
    private final List<ChatMessage> history = new ArrayList<>();

    public LlmAgent(Config config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
    }

    /** Пакетно-приватный конструктор для локальных тестов с собственным HttpClient. */
    LlmAgent(Config config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Принимает сообщение пользователя и возвращает итоговый текст ответа модели
     * (choices[0].message.content).
     *
     * История обновляется только после успешного ответа: при таймауте, HTTP-ошибке,
     * некорректном JSON, пустом ответе или прерывании история остаётся неизменной,
     * поэтому повторный ввод не создаёт дубликатов.
     */
    public String ask(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            // Пустой запрос не отправляем — API не вызываем вовсе.
            throw new AgentException("Пустой запрос: нечего отправлять модели.");
        }

        // Временный список сообщений для этого запроса; историю ещё не трогаем.
        List<ChatMessage> outgoing = buildOutgoingMessages(userMessage);

        HttpRequest request = buildRequest(outgoing);
        HttpResponse<String> response = send(request);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Статус и краткое описание; тело ответа и заголовки не выводим.
            // Историю не очищаем и не дополняем: неудавшийся запрос в неё не попадает.
            throw new AgentException(
                    "Сервер вернул HTTP-статус " + response.statusCode()
                            + ". Запрос не выполнен (автоматические повторы отключены).");
        }

        String answer = extractContent(response.body());

        // Успех: сохраняем новую завершённую пару и при необходимости
        // удаляем самые старые целые пары.
        history.add(new ChatMessage("user", userMessage));
        history.add(new ChatMessage("assistant", answer));
        trimHistory();

        return answer;
    }

    /**
     * Начинает новую беседу: очищает историю user/assistant и создаёт новый
     * идентификатор x-opencode-session. Системная инструкция сохраняется —
     * она не хранится в истории, а добавляется при формировании каждого запроса.
     */
    public void resetConversation() {
        history.clear();
        sessionId = UUID.randomUUID().toString();
    }

    /**
     * Возвращает неизменяемый снимок истории беседы (только пары user/assistant,
     * без system-сообщения и без доступа к внутреннему изменяемому списку).
     */
    public List<ChatMessage> getHistory() {
        return List.copyOf(history);
    }

    /**
     * Собирает сообщения одного запроса в порядке:
     * system → предыдущие завершённые пары (не более MAX_HISTORY_TURNS последних) →
     * новый запрос пользователя. Каждое сообщение — отдельный объект массива,
     * история не склеивается в одну строку.
     */
    private List<ChatMessage> buildOutgoingMessages(String userMessage) {
        List<ChatMessage> outgoing = new ArrayList<>();
        outgoing.add(new ChatMessage("system", SYSTEM_PROMPT));

        int from = Math.max(0, history.size() - MAX_HISTORY_TURNS * 2);
        outgoing.addAll(history.subList(from, history.size()));

        outgoing.add(new ChatMessage("user", userMessage));
        return outgoing;
    }

    /** После успешного ответа оставляем не более MAX_HISTORY_TURNS последних пар. */
    private void trimHistory() {
        // Удаляем только целые пары с начала списка: список всегда чередует
        // user/assistant, поэтому удаление первых двух элементов сохраняет парность.
        while (history.size() > MAX_HISTORY_TURNS * 2) {
            history.remove(0);
            history.remove(0);
        }
    }

    /** Формирует OpenAI-совместимое тело запроса из готового списка сообщений. */
    private HttpRequest buildRequest(List<ChatMessage> outgoing) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.model());

        ArrayNode messages = body.putArray("messages");
        for (ChatMessage message : outgoing) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role());
            node.put("content", message.content());
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            // Практически недостижимо для простого дерева, но обязателен по контракту API.
            throw new AgentException("Не удалось сформировать JSON-запрос.", e);
        }

        return HttpRequest.newBuilder()
                .uri(URI.create(config.apiUrl()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                // Обязателен для эндпоинта OpenCode Go, подтверждено документацией
                // и ошибкой HTTP 400 MissingSessionID при его отсутствии.
                .header("x-opencode-session", sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    /** Отправляет запрос через HttpClient и разбирает сетевые ошибки. */
    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new AgentException(
                    "Превышен таймаут ожидания ответа сервера (180 секунд).", e);
        } catch (InterruptedException e) {
            // Восстанавливаем флаг прерывания и корректно прекращаем работу.
            Thread.currentThread().interrupt();
            throw new AgentException("Запрос прерван, работа останавливается.", e);
        } catch (IOException e) {
            // Сетевые ошибки: недоступен сервер, DNS, обрыв соединения и т.п.
            throw new AgentException(
                    "Сетевая ошибка при обращении к LLM: " + e.getMessage()
                            + ". Проверьте подключение и попробуйте ещё раз.", e);
        }
    }

    /** Разбирает JSON-ответ и извлекает choices[0].message.content. */
    private String extractContent(String responseBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (IOException e) {
            throw new AgentException("Ответ сервера не является корректным JSON.", e);
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new AgentException("В ответе сервера отсутствует массив choices с вариантами ответа.");
        }

        JsonNode contentNode = choices.get(0).path("message").path("content");
        if (!contentNode.isTextual() || contentNode.asText().isBlank()) {
            // reasoning_content и другие служебные поля пользователю не подставляем.
            throw new AgentException("Модель вернула пустой итоговый ответ "
                    + "(choices[0].message.content отсутствует или пуст).");
        }

        return contentNode.asText().trim();
    }
}
