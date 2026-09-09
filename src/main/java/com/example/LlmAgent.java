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
import java.util.Locale;
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
 *
 * Учёт токенов (День 8): перед HTTP локально ({@link TokenCounter},
 * оценка ≈) считаются новое сообщение и окончательный список messages;
 * после ответа — видимый текст ответа и полная сохранённая история.
 * Фактические токены берутся только из usage ответа и накапливаются
 * в {@link SessionTokenStats} за сессию (ровно один раз на запрос;
 * запрос без usage помечается как неизвестный расход). При настроенном
 * контекстном бюджете (LLM_CONTEXT_WINDOW_TOKENS) прогноз «вход + резерв
 * выхода» сравнивается с бюджетом: warn — предупреждение и прежнее
 * поведение, block — локальная блокировка без HTTP и без изменения истории.
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
     * Локальный счётчик токенов (оценка ≈). Заменяемый: подтверждённый
     * токенизатор модели не подключён, в тестах подставляется
     * детерминированная реализация.
     */
    private TokenCounter tokenCounter = HeuristicTokenCounter.INSTANCE;

    /** Накопленные фактические расходы токенов за текущую сессию приложения. */
    private final SessionTokenStats sessionStats = new SessionTokenStats();

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

    /**
     * Порог лимита сессии, по которому уведомление уже показано. Используется,
     * чтобы одно превышение не повторялось на каждом запросе и повторная
     * установка того же числового лимита не создавала дублирующее сообщение.
     */
    private Long sessionLimitNotified;

    /** Отложенное информационное сообщение о превышении лимита сессии. */
    private String pendingSessionLimitNotice;

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

    /** Замена счётчика токенов для локальных тестов (детерминированный подсчёт). */
    void setTokenCounter(TokenCounter counter) {
        this.tokenCounter = Objects.requireNonNull(counter, "counter");
    }

    /** Снимок накопленных фактических расходов токенов за текущую сессию. */
    public SessionTokenStats.Snapshot sessionStats() {
        return sessionStats.snapshot();
    }

    /**
     * Устанавливает лимит расхода токенов за сессию (команда /limit).
     * null — отключить уведомления. Лимит информационный: запросы не
     * блокируются, история и накопленный расход не сбрасываются. Если новый
     * лимит уже превышен учтённым расходом и уведомление по этому порогу
     * ещё не показывалось, оно возвращается сразу; иначе — null.
     */
    public String setSessionTokenLimit(Long limit) {
        settings = settings.withSessionTokenLimit(limit);
        return limitNoticeIfExceeded();
    }

    /**
     * Отложенное информационное сообщение о превышении лимита сессии;
     * однократное чтение (после прочтения считается показанным).
     */
    public String consumeSessionLimitNotice() {
        String notice = pendingSessionLimitNotice;
        pendingSessionLimitNotice = null;
        return notice;
    }

    /**
     * Информационное сообщение о превышении лимита сессии, если учтённый
     * расход строго больше лимита и по этому порогу ещё не сообщали.
     * Равенство расхода и лимита — «достигнут», но не превышение.
     * Один порог — одно уведомление: повторные вызовы возвращают null.
     */
    private String limitNoticeIfExceeded() {
        Long limit = settings.sessionTokenLimit();
        if (limit == null) {
            return null;
        }
        long known = sessionStats.snapshot().knownTotal();
        if (known <= limit) {
            return null;
        }
        if (limit.equals(sessionLimitNotified)) {
            return null;
        }
        sessionLimitNotified = limit;
        return buildSessionLimitNotice(limit, known);
    }

    /** Текст информационного сообщения о превышении лимита сессии. */
    private String buildSessionLimitNotice(long limit, long known) {
        SessionTokenStats.Snapshot stats = sessionStats.snapshot();
        StringBuilder text = new StringBuilder("Лимит токенов за сессию превышен.\n");
        text.append("Установленный лимит: ").append(limit).append(".\n");
        if (stats.complete()) {
            text.append("Учтённый расход: ").append(known).append(".\n");
            text.append("Превышение: ").append(known - limit).append(" токенов.\n");
        } else {
            // Данные неполные: превышение обозначается как минимум, точный
            // остаток неизвестен — часть запросов могла не попасть в сумму.
            text.append("Учтённый расход (неполные данные): не менее ").append(known).append(".\n");
            text.append("Превышение: не менее ").append(known - limit).append(" токенов.\n");
            text.append("Для части запросов usage неполный или отсутствует; ")
                    .append("фактический расход может быть выше.\n");
        }
        text.append("Агент продолжает работу. Изменить лимит: /limit <число>, ")
                .append("отключить уведомление: /limit off.");
        return text.toString();
    }

    /** Компонент подсчёта токенов: источник и тип подсчёта для /tokens. */
    public TokenCounter tokenCounter() {
        return tokenCounter;
    }

    /**
     * Оценка (≈) полной сохранённой истории диалога. System-инструкция
     * не входит: она не хранится в истории и добавляется к каждому запросу;
     * метаданные JSON-файла (версия схемы, sessionId, форматирование) не
     * учитываются по определению — считается только текст сообщений.
     */
    public int estimateHistoryTokens() {
        return tokenCounter.countMessages(history);
    }

    /**
     * Оценка (≈) контекста следующего запроса без ещё не введённого
     * сообщения: system-инструкция плюс история с текущим ограничением
     * отправки ({@link ModelSettings#effectiveContextMaxTurns}).
     */
    public int estimateNextContextTokens() {
        return tokenCounter.countMessages(buildContextMessages());
    }

    /** Действующий лимит отправляемых пар (явная настройка или прежнее поведение). */
    public int nextContextPairLimit() {
        return settings.effectiveContextMaxTurns(MAX_HISTORY_TURNS);
    }

    /**
     * Предупреждение о прогнозируемом превышении контекстного бюджета
     * (локальная оценка входа + резерв выхода больше LLM_CONTEXT_WINDOW_TOKENS)
     * для политики warn; null, если предупреждать не о чем. Оценка строится
     * той же формулой, что и проверка block в {@link #ask(String)}.
     */
    public String predictContextBudgetWarning(String userMessage) {
        if (settings.contextWindowTokens() == null
                || settings.overflowPolicy() != ContextOverflowPolicy.WARN
                || userMessage == null
                || userMessage.isBlank()) {
            return null;
        }
        int projected = projectedContextTokens(tokenCounter.countMessages(
                buildOutgoingMessages(userMessage)));
        if (projected <= settings.contextWindowTokens()) {
            return null;
        }
        return "Предупреждение (локальная оценка, не ответ провайдера): оценка входа ≈"
                + (projected - settings.maxOutputTokens()) + " токенов + резерв выхода "
                + settings.maxOutputTokens() + " = ≈" + projected
                + " превышает контекстный бюджет " + settings.contextWindowTokens()
                + " (LLM_CONTEXT_WINDOW_TOKENS, источник: ручная настройка). Запрос будет "
                + "отправлен (LLM_CONTEXT_OVERFLOW_POLICY=warn); особенности API могут "
                + "менять правило бюджета. Запрос можно сократить или уменьшить историю (/reset).";
    }

    /** Системная инструкция для профиля: общие правила, для fast — плюс краткость. */
    static String systemPromptFor(String profile) {
        return ModelSettings.FAST.equals(profile) ? SYSTEM_PROMPT + SHORT_ANSWER_SUFFIX : SYSTEM_PROMPT;
    }

    /**
     * Принимает сообщение пользователя и возвращает итоговый текст ответа модели
     * (choices[0].message.content).
     *
     * Порядок работы: локальная оценка токенов → проверка контекстного бюджета
     * (block — локальная блокировка без HTTP) → подготовка запроса с текущей
     * историей → HTTP-запрос (одна засчитанная попытка) → проверка ответа →
     * учёт usage (ровно один раз на запрос; при пустом ответе с usage расход
     * всё равно учитывается) → новое состояние с парой user/assistant
     * (с лимитом) → сохранение в хранилище → обновление истории в памяти →
     * возврат ответа. Запись выполняется сразу после успешного ответа,
     * не откладывается до выхода.
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

        // Локальные оценки (≈): новое сообщение и окончательный список messages
        // (system + выбранная история + новое сообщение) непосредственно перед HTTP.
        int userMessageTokens = tokenCounter.count(userMessage);
        List<ChatMessage> outgoing = buildOutgoingMessages(userMessage);
        int requestTokens = tokenCounter.countMessages(outgoing);

        // Локальная проверка контекстного бюджета: оценка входа + резерв выхода.
        // Равенство бюджету не считается превышением; превышение — строго больше.
        boolean budgetExceeded = false;
        if (settings.contextWindowTokens() != null) {
            int projected = projectedContextTokens(requestTokens);
            budgetExceeded = projected > settings.contextWindowTokens();
            if (budgetExceeded && settings.overflowPolicy() == ContextOverflowPolicy.BLOCK) {
                // Локальная блокировка по оценке: HTTP не выполняется, история
                // не изменяется, попытка к API не засчитывается.
                throw new ContextBudgetBlockedException(
                        "Запрос заблокирован локально (LLM_CONTEXT_OVERFLOW_POLICY=block): "
                                + "оценка входа ≈" + requestTokens + " токенов + резерв выхода "
                                + settings.maxOutputTokens() + " = ≈" + projected
                                + " превышает контекстный бюджет " + settings.contextWindowTokens()
                                + " (LLM_CONTEXT_WINDOW_TOKENS, источник: ручная настройка). "
                                + "Это локальная оценка, а не отказ провайдера. HTTP-запрос "
                                + "не выполнялся, история не изменена. Варианты: сократить "
                                + "сообщение или историю (/reset), увеличить "
                                + "LLM_CONTEXT_WINDOW_TOKENS или выбрать "
                                + "LLM_CONTEXT_OVERFLOW_POLICY=warn.");
            }
        }

        HttpRequest request = buildRequest(outgoing);
        long prepareNanos = System.nanoTime() - totalStart;

        // Попытка обращения к API засчитывается ровно один раз на запрос:
        // и на успешный ответ, и на любую ошибку после фактической отправки.
        sessionStats.recordAttempt();
        long httpStart = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = send(request);
        } catch (AgentException e) {
            // Ответ не получен (таймаут, сеть, прерывание): расход неизвестен.
            sessionStats.recordUnknownUsage();
            throw e;
        }
        long httpNanos = System.nanoTime() - httpStart;

        // Время до получения полного тела ответа, не «время до первого токена»:
        // запрос непотоковый.
        long parseStart = System.nanoTime();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Статус и безопасная классификация; тело ответа и заголовки не выводим.
            // Историю не очищаем и не дополняем: неудавшийся запрос в неё не попадает.
            sessionStats.recordUnknownUsage();
            throw httpError(response);
        }
        ParsedAnswer parsed;
        try {
            parsed = parseAnswer(response.body());
        } catch (AgentException e) {
            // JSON или choices не разобраны: usage извлечь нельзя, расход неизвестен.
            sessionStats.recordUnknownUsage();
            throw e;
        }
        long parseNanos = System.nanoTime() - parseStart;

        if (parsed.content() == null) {
            // Пустой видимый ответ: если usage присутствует, расход всё равно
            // учитываем — такой запрос мог потребить токены (например, на
            // внутренние рассуждения модели). Учёт ровно один раз на запрос.
            sessionStats.recordUsage(
                    parsed.usage() != null ? parsed.usage().promptTokens() : null,
                    parsed.usage() != null ? parsed.usage().completionTokens() : null);
            // Проверка лимита и после пустого ответа: расход уже учтён.
            String notice = limitNoticeIfExceeded();
            if (notice != null) {
                pendingSessionLimitNotice = notice;
            }
            throw emptyAnswerError(parsed.finishReason());
        }
        String answer = parsed.content();

        // Учёт фактического расхода по usage: ровно один раз на успешный запрос.
        sessionStats.recordUsage(
                parsed.usage() != null ? parsed.usage().promptTokens() : null,
                parsed.usage() != null ? parsed.usage().completionTokens() : null);
        // Информационный лимит сессии: проверка ровно один раз на запрос,
        // после учтённого usage; превышение не блокирует работу.
        String limitNotice = limitNoticeIfExceeded();
        if (limitNotice != null) {
            pendingSessionLimitNotice = limitNotice;
        }

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
                parsed.usage() != null ? parsed.usage().totalTokens() : null,
                userMessageTokens,
                requestTokens,
                tokenCounter.countOverhead(outgoing.size()),
                tokenCounter.count(answer),
                tokenCounter.countMessages(updated),
                updated.size(),
                budgetExceeded);

        return answer;
    }

    /** Прогноз контекстного бюджета: оценка входа плюс резерв выхода (max_tokens). */
    private int projectedContextTokens(int estimatedRequestTokens) {
        return estimatedRequestTokens + settings.maxOutputTokens();
    }

    /**
     * HTTP-ошибка с безопасной классификацией. Переполнение контекста
     * признаётся только при подтверждённом признаке в стандартной структуре
     * ошибки OpenAI-совместимого ответа (HTTP 400 и error.code
     * context_length_exceeded либо явное упоминание контекстной длины
     * в error.message); тело ошибки пользователю не выводится. Остальные
     * HTTP-ошибки остаются общими: приписывать им причину нельзя.
     */
    private AgentException httpError(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 400 && looksLikeContextOverflow(response.body())) {
            return new AgentException(
                    "API отклонил запрос из-за размера контекста (подтверждённый отказ "
                            + "по признаку в стандартной структуре ошибки OpenAI-совместимого "
                            + "ответа; HTTP-статус 400). Тело ошибки не выводится. История "
                            + "не изменена. Сократите запрос или историю (/reset) либо "
                            + "настройте LLM_CONTEXT_MAX_TURNS.");
        }
        return new AgentException(
                "Сервер вернул HTTP-статус " + status
                        + ". Запрос не выполнен (автоматические повторы отключены).");
    }

    /**
     * Консервативное распознавание отказа из-за контекста по стандартным полям
     * error.code/error.message. Это сопоставление с типовыми значениями
     * OpenAI-совместимых ответов, а не подтверждённый контракт провайдера;
     * при любом сомнении возвращается false (останется общая ошибка).
     */
    private boolean looksLikeContextOverflow(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            if (!error.isObject()) {
                return false;
            }
            JsonNode code = error.get("code");
            if (code != null && code.isTextual()
                    && "context_length_exceeded".equals(code.asText())) {
                return true;
            }
            JsonNode message = error.get("message");
            if (message != null && message.isTextual()) {
                String lower = message.asText().toLowerCase(Locale.ROOT);
                return lower.contains("maximum context length")
                        || lower.contains("context length")
                        || lower.contains("context_length_exceeded");
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Понятная ошибка пустого видимого ответа (с подсказкой при лимите генерации). */
    private AgentException emptyAnswerError(String finishReason) {
        String message = "Модель вернула пустой итоговый ответ "
                + "(choices[0].message.content отсутствует или пуст).";
        if ("length".equals(finishReason)) {
            // Лимит мог быть израсходован на внутренние рассуждения модели.
            message += " Лимит генерации (max_tokens=" + settings.maxOutputTokens()
                    + ") мог быть израсходован до видимого текста. Увеличьте лимит: "
                    + "/mode detailed или переменная LLM_MAX_OUTPUT_TOKENS "
                    + "(например, " + (settings.maxOutputTokens() * 2) + ").";
        }
        return new AgentException(message);
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
        List<ChatMessage> outgoing = buildContextMessages();
        outgoing.add(new ChatMessage("user", userMessage));
        return outgoing;
    }

    /**
     * Контекст без нового сообщения: system-инструкция для текущего профиля
     * плюс последние завершённые пары с учётом ограничения отправки.
     * Используется и для формирования запроса, и для оценок /tokens.
     */
    private List<ChatMessage> buildContextMessages() {
        List<ChatMessage> context = new ArrayList<>();
        context.add(new ChatMessage("system", systemPromptFor(settings.profile())));

        int maxTurns = settings.effectiveContextMaxTurns(MAX_HISTORY_TURNS);
        int from = Math.max(0, history.size() - maxTurns * 2);
        context.addAll(history.subList(from, history.size()));
        return context;
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
     * Вложенные детализации usage (если есть) повторно не суммируются —
     * читается только корневой объект.
     *
     * Пустой или отсутствующий content не бросает исключение: возвращается
     * content == null, чтобы вызывающий код успел учесть usage (пустой ответ
     * тоже мог потребить токены). Ошибками остаются только некорректный JSON
     * и отсутствие массива choices — в этих случаях usage извлечь нельзя.
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
        String content = contentNode.isTextual() && !contentNode.asText().isBlank()
                ? contentNode.asText().trim()
                : null;

        return new ParsedAnswer(content, finishReason, parseUsage(root.get("usage")));
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
