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
import java.util.Objects;
import java.util.UUID;

/**
 * Отдельный агент, инкапсулирующий всё общение с LLM:
 * проверку запроса, формирование JSON с историей диалога, отправку по HTTP,
 * обработку ошибок и разбор ответа.
 *
 * Агент владеет историей беседы: завершённые пары user/assistant хранятся
 * в памяти и передаются в API явно через массив messages (идентификатор
 * сессии сам по себе память не обеспечивает). Постоянное хранение выделено
 * {@link ConversationStore}: при создании агент восстанавливает из него
 * сохранённую беседу, после каждого успешного ответа — сохраняет обновлённое
 * состояние (не откладывая запись до выхода). HttpClient и ObjectMapper
 * создаются один раз и переиспользуются.
 *
 * Параметры запроса (лимит генерации, temperature, таймаут, лимит
 * отправляемого контекста) централизованы в {@link ModelSettings};
 * в JSON запроса отправляются только подтверждённые контрактом
 * OpenAI-совместимого эндпоинта поля: model, messages, max_tokens
 * и temperature (последний — только при явном переопределении).
 * Провайдерские параметры вроде reasoning_effort или enable_thinking
 * наугад не добавляются. Для каждого запроса собираются метрики
 * {@link RequestDiagnostics}: время подготовки, HTTP до полного тела,
 * разбора и записи истории.
 */
public final class LlmAgent {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /** Постоянная системная инструкция; отправляется первым сообщением каждого запроса. */
    private static final String SYSTEM_PROMPT =
            "Ты полезный ассистент. Учитывай историю диалога. "
                    + "Отвечай на языке пользователя, если он не попросил иначе. "
                    + "Если информации недостаточно, уточни вопрос.";

    /** Короткое предпочтение краткости только для профиля fast. */
    private static final String SHORT_ANSWER_SUFFIX =
            " Отвечай кратко и по существу. Если пользователь явно просит подробности, "
                    + "полный код или определённый формат, соблюдай его запрос.";

    /** Максимум завершённых пар user/assistant в памяти и в файле истории. */
    public static final int MAX_HISTORY_TURNS = 20;

    private final Config config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ConversationStore store;

    /**
     * Текущие настройки модели. Заменяются только командой /mode
     * ({@link #setProfile(String)}); сам объект неизменяемый.
     */
    private ModelSettings settings;

    /**
     * Идентификатор текущей беседы. Шлюз OpenCode Go требует заголовок
     * x-opencode-session на каждый запрос (официальная документация провайдера:
     * opencode.ai/docs/go — «Send a stable session ID in x-opencode-session
     * for each conversation»). Одна беседа = период до {@link #resetConversation()}.
     * Идентификатор восстанавливается из хранилища и сохраняется в нём вместе
     * с историей. Важно: session ID не заменяет историю — контекст всегда
     * передаётся через messages.
     */
    private String sessionId = UUID.randomUUID().toString();

    /**
     * Завершённые пары user/assistant текущей беседы (system-сообщение здесь
     * не хранится, оно добавляется при формировании каждого запроса).
     * При старте список заполняется из {@link ConversationStore}.
     */
    private final List<ChatMessage> history = new ArrayList<>();

    /** true, если из хранилища восстановлена непустая история беседы. */
    private boolean contextRestored;

    /** Метрики последнего выполненного запроса; null, пока запросов не было. */
    private RequestDiagnostics lastDiagnostics;

    /** Создаёт агента с настройками по умолчанию и готовым хранилищем. */
    public LlmAgent(Config config, ConversationStore store) {
        this(config, ModelSettings.defaults(), store);
    }

    /** Создаёт агента с заданными настройками; сохранённая беседа восстанавливается сразу. */
    public LlmAgent(Config config, ModelSettings settings, ConversationStore store) {
        this(config, settings, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build(), store);
    }

    /** Пакетно-приватный конструктор для локальных тестов с собственным HttpClient. */
    LlmAgent(Config config, ModelSettings settings, HttpClient httpClient, ConversationStore store) {
        this.config = config;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.httpClient = httpClient;
        this.store = Objects.requireNonNull(store, "store");
        this.objectMapper = new ObjectMapper();
        restoreFromStore();
    }

    /**
     * Восстанавливает контекст при старте: если в хранилище есть сохранённая
     * беседа, загружает её пары user/assistant и sessionId. Повреждённый файл
     * или неизвестная версия формата останавливают запуск с ошибкой
     * ({@link ConversationStoreException}); файл при этом не изменяется.
     */
    private void restoreFromStore() {
        ConversationState state = store.load();
        if (!state.messages().isEmpty()) {
            history.addAll(state.messages());
            contextRestored = true;
        }
        sessionId = state.sessionId();
    }

    /** true, если при старте из хранилища была восстановлена непустая история. */
    public boolean hasRestoredContext() {
        return contextRestored;
    }

    /** Текущие настройки модели (профиль, лимит, таймаут, диагностика). */
    public ModelSettings currentSettings() {
        return settings;
    }

    /**
     * Переключает профиль ответа (команда /mode). Действует до конца текущего
     * запуска; историю не трогает. Явный LLM_MAX_OUTPUT_TOKENS сохраняет
     * приоритет над лимитом профиля. Неизвестный профиль — ошибка.
     */
    public ModelSettings setProfile(String profile) {
        settings = settings.withProfile(profile);
        return settings;
    }

    /** Метрики последнего выполненного запроса; null, пока запросов не было. */
    public RequestDiagnostics getLastDiagnostics() {
        return lastDiagnostics;
    }

    /** Системная инструкция для профиля: общие правила, для fast — плюс краткость. */
    static String systemPromptFor(String profile) {
        return ModelSettings.FAST.equals(profile) ? SYSTEM_PROMPT + SHORT_ANSWER_SUFFIX : SYSTEM_PROMPT;
    }

    /**
     * Принимает сообщение пользователя и возвращает итоговый текст ответа модели
     * (choices[0].message.content).
     *
     * Порядок работы: подготовка запроса с текущей историей → HTTP-запрос →
     * проверка ответа → новое состояние с парой user/assistant (с лимитом) →
     * сохранение в хранилище → обновление истории в памяти → возврат ответа.
     * Запись выполняется сразу после успешного ответа, не откладывается до выхода.
     *
     * При таймауте, HTTP-ошибке, некорректном JSON, пустом ответе или прерывании
     * ни память, ни файл истории не изменяются, повторный ввод не создаёт
     * дубликатов, повторные платные запросы не выполняются. Отдельная ситуация —
     * сбой записи после успешного ответа ({@link ConversationSaveException}):
     * история не меняется, полученный ответ доставляется вызывающему коду.
     */
    public String ask(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            // Пустой запрос не отправляем — API не вызываем вовсе.
            throw new AgentException("Пустой запрос: нечего отправлять модели.");
        }
        long totalStart = System.nanoTime();

        // Временный список сообщений для этого запроса; историю ещё не трогаем.
        List<ChatMessage> outgoing = buildOutgoingMessages(userMessage);
        HttpRequest request = buildRequest(outgoing);
        long prepareNanos = System.nanoTime() - totalStart;

        long httpStart = System.nanoTime();
        HttpResponse<String> response = send(request);
        long httpNanos = System.nanoTime() - httpStart;

        // Время до получения полного тела ответа, не «время до первого токена»:
        // запрос непотоковый.
        long parseStart = System.nanoTime();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Статус и краткое описание; тело ответа и заголовки не выводим.
            // Историю не очищаем и не дополняем: неудавшийся запрос в неё не попадает.
            throw new AgentException(
                    "Сервер вернул HTTP-статус " + response.statusCode()
                            + ". Запрос не выполнен (автоматические повторы отключены).");
        }
        ParsedAnswer parsed = parseAnswer(response.body());
        long parseNanos = System.nanoTime() - parseStart;
        String answer = parsed.content();

        // Успех: новое состояние = текущая история + завершённая пара,
        // с применением существующего лимита (только целые старые пары).
        List<ChatMessage> updated = new ArrayList<>(history);
        updated.add(new ChatMessage("user", userMessage));
        updated.add(new ChatMessage("assistant", answer));
        trimToLimit(updated);
        ConversationState newState = new ConversationState(sessionId, updated);

        long saveStart = System.nanoTime();
        try {
            // Сохраняем на диск ровно тот контекст, который дальше будет в памяти.
            store.save(newState);
        } catch (ConversationStoreException e) {
            // Файл и память не изменились; повторный платный запрос не выполняем,
            // а полученный ответ не теряем — доставляем его через исключение.
            throw new ConversationSaveException(answer, e.getMessage(), e);
        }
        long saveNanos = System.nanoTime() - saveStart;

        history.clear();
        history.addAll(updated);

        int includedPairs = (outgoing.size() - 2) / 2;
        int omittedPairs = history.size() / 2 - includedPairs;
        lastDiagnostics = new RequestDiagnostics(
                settings.profile(),
                settings.maxOutputTokens(),
                settings.temperature(),
                outgoing.size(),
                includedPairs,
                omittedPairs,
                lastRequestBytes,
                prepareNanos,
                httpNanos,
                parseNanos,
                saveNanos,
                System.nanoTime() - totalStart,
                parsed.finishReason(),
                parsed.usage() != null ? parsed.usage().promptTokens() : null,
                parsed.usage() != null ? parsed.usage().completionTokens() : null,
                parsed.usage() != null ? parsed.usage().totalTokens() : null);

        return answer;
    }

    /**
     * Начинает новую беседу: сначала безопасно записывает в хранилище пустую
     * беседу с новым sessionId (при ошибке записи исключение уходит вызывающему
     * коду и старое состояние остаётся неизменным и в памяти, и в файле),
     * затем очищает историю в памяти. Системная инструкция сохраняется —
     * она не хранится в истории, а добавляется при формировании каждого запроса.
     */
    public void resetConversation() {
        ConversationState empty = ConversationState.newEmpty();
        store.save(empty);
        history.clear();
        sessionId = empty.sessionId();
        contextRestored = false;
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
     * system → предыдущие завершённые пары (не более effectiveContextMaxTurns
     * последних) → новый запрос пользователя. Каждое сообщение — отдельный
     * объект массива, история не склеивается в одну строку. Лимит
     * LLM_CONTEXT_MAX_TURNS ограничивает только отправку: файл истории
     * и память сохраняют всю существующую политику хранения.
     */
    private List<ChatMessage> buildOutgoingMessages(String userMessage) {
        List<ChatMessage> outgoing = new ArrayList<>();
        outgoing.add(new ChatMessage("system", systemPromptFor(settings.profile())));

        int maxTurns = settings.effectiveContextMaxTurns(MAX_HISTORY_TURNS);
        int from = Math.max(0, history.size() - maxTurns * 2);
        outgoing.addAll(history.subList(from, history.size()));

        outgoing.add(new ChatMessage("user", userMessage));
        return outgoing;
    }

    /** Оставляет в списке не более MAX_HISTORY_TURNS последних целых пар. */
    private static void trimToLimit(List<ChatMessage> messages) {
        // Удаляем только целые пары с начала списка: список всегда чередует
        // user/assistant, поэтому удаление первых двух элементов сохраняет парность.
        while (messages.size() > MAX_HISTORY_TURNS * 2) {
            messages.remove(0);
            messages.remove(0);
        }
    }

    /** Размер последнего сформированного тела запроса в байтах UTF-8. */
    private int lastRequestBytes;

    /**
     * Формирует OpenAI-совместимое тело запроса из готового списка сообщений:
     * model, messages, max_tokens (верхний предел генерации из профиля
     * или LLM_MAX_OUTPUT_TOKENS) и temperature — только при явном
     * переопределении. Другие параметры сэмплирования (top_p и т.п.)
     * и провайдерские поля наугад не отправляются.
     */
    private HttpRequest buildRequest(List<ChatMessage> outgoing) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.model());
        body.put("max_tokens", settings.maxOutputTokens());
        if (settings.temperature() != null) {
            body.put("temperature", settings.temperature());
        }

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
        byte[] jsonBytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        lastRequestBytes = jsonBytes.length;

        return HttpRequest.newBuilder()
                .uri(URI.create(config.apiUrl()))
                .timeout(Duration.ofSeconds(settings.requestTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                // Обязателен для эндпоинта OpenCode Go, подтверждено документацией
                // и ошибкой HTTP 400 MissingSessionID при его отсутствии.
                .header("x-opencode-session", sessionId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(jsonBytes))
                .build();
    }

    /** Отправляет запрос через HttpClient и разбирает сетевые ошибки. */
    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new AgentException(
                    "Превышен таймаут ожидания ответа сервера ("
                            + settings.requestTimeoutSeconds() + " секунд).", e);
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

    /** Результат разбора ответа: видимый текст, finish_reason и usage (если есть). */
    private record ParsedAnswer(String content, String finishReason, Usage usage) {
    }

    /**
     * Использование токенов по стандартным полям OpenAI-совместимого ответа.
     * null — поле отсутствует (нет данных), а не ноль.
     */
    private record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    }

    /**
     * Разбирает JSON-ответ: choices[0].message.content (итоговый видимый текст),
     * choices[0].finish_reason и корневой usage. reasoning_content и другие
     * служебные поля пользователю не подставляются и в историю не попадают.
     */
    private ParsedAnswer parseAnswer(String responseBody) {
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

        JsonNode choice = choices.get(0);
        JsonNode finishReasonNode = choice.get("finish_reason");
        String finishReason = finishReasonNode != null && finishReasonNode.isTextual()
                ? finishReasonNode.asText()
                : null;

        JsonNode contentNode = choice.path("message").path("content");
        if (!contentNode.isTextual() || contentNode.asText().isBlank()) {
            // Пустую пару в историю не записываем и запрос не повторяем автоматически.
            String message = "Модель вернула пустой итоговый ответ "
                    + "(choices[0].message.content отсутствует или пуст).";
            if ("length".equals(finishReason)) {
                // Лимит мог быть израсходован на внутренние рассуждения модели.
                message += " Лимит генерации (max_tokens=" + settings.maxOutputTokens()
                        + ") мог быть израсходован до видимого текста. Увеличьте лимит: "
                        + "/mode detailed или переменная LLM_MAX_OUTPUT_TOKENS.";
            }
            throw new AgentException(message);
        }

        return new ParsedAnswer(contentNode.asText().trim(), finishReason, parseUsage(root.get("usage")));
    }

    /** Разбирает usage по стандартным полям; отсутствующие поля — null. */
    private static Usage parseUsage(JsonNode usageNode) {
        if (usageNode == null || !usageNode.isObject()) {
            return null;
        }
        return new Usage(
                intValueOrNull(usageNode.get("prompt_tokens")),
                intValueOrNull(usageNode.get("completion_tokens")),
                intValueOrNull(usageNode.get("total_tokens")));
    }

    private static Integer intValueOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }
}
