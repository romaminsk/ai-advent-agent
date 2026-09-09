package com.example;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ручной режим измерения токенов (/demo tokens).
 *
 * Пользователь сам вводит все сообщения, ответы приходят от настоящего API,
 * метрики берутся только из фактического usage. Отдельная пустая
 * демонстрационная история живёт во временном файле и не касается основной
 * беседы: после /demo stop основная история и её счётчики неизменны.
 *
 * Особенности режима:
 * - история демо-беседы не ограничивается: все пары отправляются с каждым
 *   запросом (ограничение количества пар отключено);
 * - контекстный бюджет работает только как предупреждение, без блокировки HTTP;
 * - лимит генерации (max_tokens) сохраняется и не увеличивается;
 * - переиспользуется HTTP-клиент основного агента; второй учёт usage
 *   не создаётся: суммы остаются в SessionTokenStats, журнал попыток —
 *   лишь детализация уже учтённых значений.
 *
 * Любое переполнение считается подтверждённым только по ответу API.
 */
final class TokenDemoSession {

    /** Одна попытка запроса в журнале демо-режима (включая неуспешные). */
    record AttemptRow(int number, Integer promptTokens, Integer completionTokens,
                      Long httpNanos, String status) {
    }

    /** Источник размеров для переполнения — только текст ошибки провайдера. */
    private static final Pattern ALLOWED_SIZE =
            Pattern.compile("maximum context length is (\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUESTED_SIZE =
            Pattern.compile("requested (\\d+)", Pattern.CASE_INSENSITIVE);

    private final LlmAgent agent;
    private final JsonConversationStore store;
    private final List<AttemptRow> attempts = new java.util.ArrayList<>();

    /** Номера попыток, после которых история демо-беседы была очищена (/clear). */
    private final List<Integer> clearedAfterAttempts = new java.util.ArrayList<>();
    private Integer firstSuccessfulPrompt;
    private Integer lastSuccessfulPrompt;
    private Integer previousKnownPrompt;
    private Integer lastDeltaPrompt;

    private TokenDemoSession(LlmAgent agent, JsonConversationStore store) {
        this.agent = agent;
        this.store = store;
    }

    /** Агент демонстрационной беседы (сообщения и команды идут в него). */
    LlmAgent agent() {
        return agent;
    }

    /**
     * Создаёт демо-сессию: временный пустой файл истории и отдельный агент
     * с неограниченной историей, предупреждающим (не блокирующим) контекстным
     * бюджетом и сохранённым лимитом генерации. HTTP-клиент переиспользуется.
     */
    static TokenDemoSession start(LlmAgent mainAgent) throws IOException {
        ModelSettings s = mainAgent.currentSettings();
        // Отдельная копия настроек: демо не меняет настройки основной беседы.
        ModelSettings demoSettings = new ModelSettings(
                s.profile(), s.maxOutputTokens(), s.limitOverridden(), s.temperature(),
                s.requestTimeoutSeconds(), null, s.contextWindowTokens(),
                ContextOverflowPolicy.WARN, s.inputPricePer1M(), s.outputPricePer1M(),
                s.sessionTokenLimit(), false);
        Path file = Files.createTempFile("ai-advent-agent-demo-", ".json");
        Files.deleteIfExists(file);
        JsonConversationStore store = new JsonConversationStore(file);
        LlmAgent agent = new LlmAgent(mainAgent.config(), demoSettings,
                mainAgent.httpClient(), store);
        agent.setHistoryUnlimited(true);
        return new TokenDemoSession(agent, store);
    }

    /** Регистрирует успешную попытку по метрикам последнего запроса. */
    void logSuccess() {
        RequestDiagnostics d = agent.getLastDiagnostics();
        addRow(new AttemptRow(attempts.size() + 1, d.promptTokens(), d.completionTokens(),
                d.httpNanos(), d.finishReason() == null ? "успех" : d.finishReason()));
        if (firstSuccessfulPrompt == null) {
            firstSuccessfulPrompt = d.promptTokens();
        }
        lastSuccessfulPrompt = d.promptTokens();
    }

    /**
     * Регистрирует неуспешную попытку. Расход берётся из журнала учтённого
     * usage (если API вернул usage при пустом ответе) — неуспешный запрос
     * не считается автоматически бесплатным.
     */
    void logFailure(Exception e) {
        SessionTokenStats.AttemptUsage usage = lastUsage();
        addRow(new AttemptRow(attempts.size() + 1, usage.promptTokens(), usage.completionTokens(),
                null, classifyFailure(e)));
    }

    /** Последняя запись учтённого usage; null-поля — «нет данных». */
    private SessionTokenStats.AttemptUsage lastUsage() {
        List<SessionTokenStats.AttemptUsage> log = agent.attemptUsageLog();
        return log.isEmpty()
                ? new SessionTokenStats.AttemptUsage(null, null)
                : log.get(log.size() - 1);
    }

    /** Классификация неуспешной попытки для столбца «Статус». */
    private String classifyFailure(Exception e) {
        Throwable cause = e.getCause();
        if (cause instanceof java.net.http.HttpTimeoutException) {
            return "таймаут";
        }
        if (cause instanceof IOException) {
            return "сетевая ошибка";
        }
        LlmAgent.ApiErrorInfo api = agent.getLastApiError();
        if (api != null) {
            return "HTTP " + api.httpStatus()
                    + (api.code() != null ? " (" + api.code() + ")" : "");
        }
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("пустой итоговый ответ")) {
            return "пустой ответ";
        }
        if (message.contains("не сохранён")) {
            return "сбой записи";
        }
        return "ошибка запроса";
    }

    /** Обновление значений для дельты входа (после добавления строки). */
    private void addRow(AttemptRow row) {
        attempts.add(row);
        Integer current = row.promptTokens();
        lastDeltaPrompt = (previousKnownPrompt == null || current == null)
                ? null
                : current - previousKnownPrompt;
        if (current != null) {
            previousKnownPrompt = current;
        }
    }

    /** Компактный блок фактических метрик после ответа модели. */
    String metricsAfterAnswer() {
        RequestDiagnostics d = agent.getLastDiagnostics();
        SessionTokenStats.Snapshot stats = agent.sessionStats();
        ModelSettings settings = agent.currentSettings();
        AttemptRow row = attempts.get(attempts.size() - 1);

        StringBuilder text = new StringBuilder("Запрос №").append(row.number());
        text.append("\n  Вход: ").append(tokensOrNoData(row.promptTokens())).append(" токенов");
        text.append("\n  Выход: ").append(tokensOrNoData(row.completionTokens())).append(" токенов");
        text.append("\n  Расход этого запроса: ").append(requestSpend(row)).append(" токенов");
        text.append("\n  Накопленный расход беседы: ").append(stats.knownTotal()).append(" токенов");
        if (stats.apiAttempts() > 0 && !stats.complete()) {
            text.append(" (неполные данные: часть запросов без usage или с частичным usage)");
        }
        text.append("\n  Стоимость запроса: ").append(costLine(settings, row));
        text.append("\n  Накопленная стоимость: ").append(cumulativeCost(settings, stats));
        text.append("\n  Изменение входа относительно прошлого запроса: ")
                .append(deltaLine(row, settings));
        text.append("\n  Время HTTP: ").append(row.httpNanos() == null
                ? "нет данных" : String.valueOf(row.httpNanos() / 1_000_000)).append(" мс");
        text.append("\n  Завершение: ").append(row.status());
        if ("length".equals(row.status())) {
            text.append("\n  Генерация завершилась по лимиту. Ответ может быть обрезан. ")
                    .append("Это не подтверждает переполнение входного контекста.");
        }
        if (settings.inputPricePer1M() != null && settings.outputPricePer1M() != null) {
            text.append("\n  Расчётная стоимость по настроенному тарифу, ")
                    .append("не подтверждённое списание провайдера.");
        }
        return text.toString();
    }

    private String tokensOrNoData(Integer value) {
        return value == null ? "нет данных" : value.toString();
    }

    /** Расход одного запроса без двойного учёта total_tokens. */
    private String requestSpend(AttemptRow row) {
        if (row.promptTokens() != null && row.completionTokens() != null) {
            return String.valueOf(row.promptTokens() + row.completionTokens());
        }
        if (row.promptTokens() != null || row.completionTokens() != null) {
            long known = (row.promptTokens() == null ? 0 : row.promptTokens())
                    + (row.completionTokens() == null ? 0 : row.completionTokens());
            return "не менее " + known + " (usage неполный)";
        }
        return "нет данных";
    }

    /** Стоимость одного запроса: только при полном usage и настроенном тарифе. */
    private String costLine(ModelSettings settings, AttemptRow row) {
        if (settings.inputPricePer1M() == null || settings.outputPricePer1M() == null) {
            return "неизвестна: тариф не задан";
        }
        if (row.promptTokens() == null || row.completionTokens() == null) {
            return "нет данных (usage неполный или отсутствует)";
        }
        return formatCost(settings, row.promptTokens(), row.completionTokens());
    }

    /** Накопленная стоимость по суммам учтённого usage. */
    private String cumulativeCost(ModelSettings settings, SessionTokenStats.Snapshot stats) {
        if (settings.inputPricePer1M() == null || settings.outputPricePer1M() == null) {
            return "неизвестна: тариф не задан";
        }
        if (stats.requestsWithUsage() == 0) {
            return "нет данных";
        }
        String cost = formatCost(settings, stats.totalPromptTokens(), stats.totalCompletionTokens());
        return stats.complete() ? cost : cost + " (неполные данные)";
    }

    private String formatCost(ModelSettings settings, long promptTokens, long completionTokens) {
        BigDecimal total = TokenCost.total(
                TokenCost.perMillion(settings.inputPricePer1M(), promptTokens),
                TokenCost.perMillion(settings.outputPricePer1M(), completionTokens));
        return "≈" + TokenCost.formatUsd(total) + " (расчётная)";
    }

    private String deltaLine(AttemptRow row, ModelSettings settings) {
        if (lastDeltaPrompt == null) {
            return previousKnownPrompt == null && row.promptTokens() != null
                    ? "нет данных (это первый запрос)"
                    : "нет данных (прошлый вход неизвестен)";
        }
        return (lastDeltaPrompt >= 0 ? "+" : "") + lastDeltaPrompt + " токенов";
    }

    /** Таблица всех попыток текущей демонстрационной беседы (без вызова API). */
    String table() {
        SessionTokenStats.Snapshot stats = agent.sessionStats();
        ModelSettings settings = agent.currentSettings();
        boolean prices = settings.inputPricePer1M() != null && settings.outputPricePer1M() != null;

        StringBuilder text = new StringBuilder(
                "Таблица демонстрационной беседы (фактические значения usage API):");
        text.append("\n  № | Вход API | Выход API | Накоплено | Стоимость накопительно")
                .append(" | HTTP, мс | Статус");
        long cumPrompt = 0;
        long cumCompletion = 0;
        for (AttemptRow row : attempts) {
            if (row.promptTokens() != null) {
                cumPrompt += row.promptTokens();
            }
            if (row.completionTokens() != null) {
                cumCompletion += row.completionTokens();
            }
            long cumulative = cumPrompt + cumCompletion;
            text.append("\n  ").append(row.number())
                    .append(" | ").append(tokensOrNoData(row.promptTokens()))
                    .append(" | ").append(tokensOrNoData(row.completionTokens()))
                    .append(" | ").append(cumulative)
                    .append(" | ").append(prices
                            ? formatCost(settings, cumPrompt, cumCompletion)
                            : "нет данных: тариф не задан")
                    .append(" | ").append(row.httpNanos() == null
                            ? "нет данных" : String.valueOf(row.httpNanos() / 1_000_000))
                    .append(" | ").append(row.status());
            if (clearedAfterAttempts.contains(row.number())) {
                // Пометка очистки истории: следующий запрос шёл с пустой историей.
                text.append("\n  — | — | — | — | — | — | история очищена (/clear)");
            }
        }
        if (attempts.isEmpty()) {
            text.append("\n  попыток ещё не было");
        }

        text.append("\nСводка:");
        text.append("\n  вход первого успешного запроса: ")
                .append(tokensOrNoData(firstSuccessfulPrompt));
        text.append("\n  вход последнего успешного запроса: ")
                .append(tokensOrNoData(lastSuccessfulPrompt));
        if (firstSuccessfulPrompt != null && lastSuccessfulPrompt != null) {
            long diff = (long) lastSuccessfulPrompt - firstSuccessfulPrompt;
            text.append("\n  разница входа: ").append(diff >= 0 ? "+" : "").append(diff);
        } else {
            text.append("\n  разница входа: нет данных");
        }
        text.append("\n  накопленный известный расход: ").append(stats.knownTotal()).append(" токенов");
        if (stats.apiAttempts() == 0) {
            text.append(" (попыток ещё не было)");
        } else if (stats.complete()) {
            text.append(" (полные данные)");
        } else {
            text.append(" (неполные данные: ").append(stats.requestsWithoutUsage())
                    .append(" запрос(ов) с неизвестным расходом, ")
                    .append(stats.requestsWithPartialUsage()).append(" с частичным usage)");
        }
        return text.toString();
    }

    /**
     * Подробности неуспешной попытки: номер, статус, код и краткое описание
     * провайдера, установленная причина, расход, сохранность истории.
     * Переполнение контекста показывается только при подтверждении API.
     */
    String errorDetails(Exception e) {
        AttemptRow row = attempts.get(attempts.size() - 1);
        LlmAgent.ApiErrorInfo api = agent.getLastApiError();
        StringBuilder text = new StringBuilder("Запрос №").append(row.number()).append(" не выполнен.");

        if ("таймаут".equals(row.status())) {
            text.append("\n  Причина: таймаут ожидания ответа (это не переполнение контекста).");
        } else if ("сетевая ошибка".equals(row.status())) {
            text.append("\n  Причина: сетевая ошибка (сервер недоступен или соединение прервано).");
        }
        if (api != null) {
            text.append("\n  HTTP-статус: ").append(api.httpStatus());
            if (api.code() != null) {
                text.append("\n  Код провайдера: ").append(api.code());
            }
            if (api.message() != null) {
                text.append("\n  Описание провайдера: ").append(api.message());
            }
            text.append("\n  Причина: ").append(api.contextOverflow()
                    ? "переполнение контекста подтверждено ответом API"
                    : "причина не подтверждена");
        } else if ("пустой ответ".equals(row.status())) {
            text.append("\n  Причина: модель вернула пустой видимый ответ.");
        }

        if (api != null && api.contextOverflow()) {
            text.append("\n  API отклонил запрос: превышен допустимый контекст.")
                    .append("\n  Ответ на это сообщение не получен.");
            long[] sizes = providerSizes(api.message());
            if (sizes != null) {
                text.append("\n  По тексту ошибки провайдера: лимит ").append(sizes[0])
                        .append(" токенов, запрошено ").append(sizes[1]).append(" токенов.");
            } else {
                text.append("\n  Допустимый и запрошенный размеры провайдер не сообщил.");
            }
        } else if (!"таймаут".equals(row.status()) && !"сетевая ошибка".equals(row.status())
                && !"пустой ответ".equals(row.status()) && api == null) {
            text.append("\n  Причина не подтверждена.");
        }

        if (row.promptTokens() != null || row.completionTokens() != null) {
            text.append("\n  Расход попытки по usage: ").append(requestSpend(row)).append(" токенов.")
                    .append(" Он учтён в накопленном расходе беседы.");
        } else {
            text.append("\n  Расход попытки: нет данных (usage недоступен). ")
                    .append("Неуспешный запрос не считается автоматически бесплатным.");
        }
        text.append("\n  Завершённая история сохранена (")
                .append(agent.getHistory().size() / 2).append(" пар), ")
                .append("приложение продолжает работать.");
        return text.toString();
    }

    /**
     * Размеры (лимит, запрошено) — только если провайдер сообщил их в тексте
     * ошибки; иначе null и значения не придумываются.
     */
    private static long[] providerSizes(String message) {
        if (message == null) {
            return null;
        }
        Matcher allowed = ALLOWED_SIZE.matcher(message);
        Matcher requested = REQUESTED_SIZE.matcher(message);
        if (allowed.find() && requested.find()) {
            return new long[]{Long.parseLong(allowed.group(1)), Long.parseLong(requested.group(1))};
        }
        return null;
    }

    /** Очищает журнал попыток (команда /reset в демо-режиме: новая беседа). */
    void clearLog() {
        attempts.clear();
        clearedAfterAttempts.clear();
        firstSuccessfulPrompt = null;
        lastSuccessfulPrompt = null;
        previousKnownPrompt = null;
        lastDeltaPrompt = null;
    }

    /**
     * Отмечает очистку истории демо-беседы (/clear): таблица сохраняется,
     * между попытками появляется строка «история очищена», чтобы по таблице
     * было видно, что следующий запрос шёл с пустой историей.
     */
    void markHistoryCleared() {
        if (!attempts.isEmpty()) {
            clearedAfterAttempts.add(attempts.get(attempts.size() - 1).number());
        }
    }

    /**
     * Завершает режим: освобождает блокировку и удаляет временные файлы
     * демо-истории (основная беседа не затрагивается).
     */
    void close() {
        store.close();
        Path file = store.file();
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
        Path lock = file.getParent() == null ? null
                : file.getParent().resolve(file.getFileName().toString() + ".lock");
        if (lock != null) {
            try {
                Files.deleteIfExists(lock);
            } catch (IOException ignored) {
            }
        }
    }
}
