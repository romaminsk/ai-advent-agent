package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.index.ChunkMeta;
import com.example.index.DocumentLoader;
import com.example.index.IndexCommands;
import com.example.index.IndexSearch;
import com.example.index.IndexStore;
import com.example.index.OpenAiEmbedder;
import io.modelcontextprotocol.spec.McpSchema;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpServer;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArraySet;

final class MeasurementChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkClearCommand();
        checkClearConfirmationUi();
        checkClearDemoIsolation();
        checkClearErrorKeepsMemory();
        checkDemoTokensMode();
        checkDemoFailureClassifications();
        checkDemoCommandsNoApi();
        checkPasteInput();
        checkDay10OtherStoreValidation();
    }

     static void checkClearCommand() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            lastBody.set(requestBody);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ " + hitCounter.get() + "\"}}],\"usage\":{\"prompt_tokens\":10,"
                    + "\"completion_tokens\":20,\"total_tokens\":30}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            JsonConversationStore store = tempStore();
            LlmAgent agent = newEnvAgent(config, client, store, new java.util.HashMap<>());
            agent.ask("вопрос до очистки");
            agent.setSessionTokenLimit(5000L);
            expect("перед очисткой есть история, расход и лимит",
                    agent.getHistory().size() == 2
                            && agent.sessionStats().knownTotal() == 30
                            && agent.currentSettings().sessionTokenLimit() == 5000L);

            // Подтверждение y: очистка памяти и файла, без API.
            int hitsBeforeClear = hitCounter.get();
            FakeUi clearUi = new FakeUi(
                    TerminalUi.Input.command("/clear"),
                    TerminalUi.Input.command("/exit"));
            clearUi.confirmClearAnswer = true;
            Main.runLoop(clearUi, agent, "glm-5.3-flash");
            expect("подтверждение y удаляет историю в памяти",
                    agent.getHistory().isEmpty());
            expect("/clear не вызывает API", hitCounter.get() == hitsBeforeClear);
            JsonNode saved = MAPPER.readTree(
                    Files.readString(store.file(), StandardCharsets.UTF_8));
            expect("файл истории очищен атомарно в совместимом формате",
                    saved.path("schemaVersion").asInt(-1)
                            == JsonConversationStore.SUPPORTED_SCHEMA_VERSION
                            && !saved.path("sessionId").asText().isBlank()
                            && saved.path("messages").isEmpty());
            expect("сообщение об успехе упоминает сохранение статистики",
                    clearUi.systems.stream().anyMatch(s ->
                            s.contains("История текущего диалога удалена")
                                    && s.contains("статистика сессии сохранена")));
            expect("статистика сессии не сброшена очисткой",
                    agent.sessionStats().apiAttempts() == 1
                            && agent.sessionStats().knownTotal() == 30);
            expect("лимит сессии сохранён после очистки",
                    agent.currentSettings().sessionTokenLimit() == 5000L);
            expect("/tokens показывает пустую историю",
                    Main.formatTokens(agent, "glm-5.3-flash").contains("0 сообщений (0 пар)"));            expect("/stats продолжает показывать прежний расход",
                    Main.formatStats(agent).contains(
                            "входные токены (фактические, сумма prompt_tokens): 10"));

            // Повторная очистка уже пустой истории — снова с подтверждением.
            FakeUi clearAgainUi = new FakeUi(
                    TerminalUi.Input.command("/clear"),
                    TerminalUi.Input.command("/exit"));
            clearAgainUi.confirmClearAnswer = true;
            Main.runLoop(clearAgainUi, agent, "glm-5.3-flash");
            expect("повторная очистка пустой истории проходит штатно",
                    agent.getHistory().isEmpty() && clearAgainUi.confirmClearCount == 1);

            // Следующий запрос формируется без удалённых сообщений; system сохранён.
            agent.ask("новый вопрос после очистки");
            JsonNode body = MAPPER.readTree(lastBody.get());
            expect("после очистки запрос содержит только system и новое сообщение",
                    body.path("messages").size() == 2
                            && "system".equals(body.path("messages").get(0).path("role").asText())
                            && "новый вопрос после очистки".equals(
                            body.path("messages").get(1).path("content").asText())
                            && !lastBody.get().contains("вопрос до очистки"));

            // Перезапуск: старая переписка не восстанавливается.
            store.close();
            try (JsonConversationStore reopened = new JsonConversationStore(store.file())) {
                LlmAgent restarted = new LlmAgent(config, ModelSettings.defaults(), client, reopened);
                expect("после перезапуска восстанавливается только новая беседа",
                        restarted.getHistory().equals(List.of(
                                new ChatMessage("user", "новый вопрос после очистки"),
                                new ChatMessage("assistant", "Ответ 2")))
                                && !restarted.getHistory().toString().contains("вопрос до очистки"));
            }
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkClearConfirmationUi() {
        CapturedStream err = capturingStream();
        PlainTerminalUi yesUi = new PlainTerminalUi(reader("y\n"),
                capturingStream().stream, err.stream);
        expect("подтверждение y удаляет историю",
                yesUi.confirmHistoryClear("текущего диалога"));
        expect("текст подтверждения объясняет правила",
                err.text().contains("? Очистить историю текущего диалога? Это нельзя отменить. [y/N]"));
        expect("подтверждение YES без учёта регистра",
                new PlainTerminalUi(reader("YES\n"), capturingStream().stream,
                        capturingStream().stream).confirmHistoryClear("текущего диалога"));
        expect("пустой ввод отменяет удаление",
                !new PlainTerminalUi(reader("\n"), capturingStream().stream,
                        capturingStream().stream).confirmHistoryClear("текущего диалога"));
        expect("ответ n отменяет удаление",
                !new PlainTerminalUi(reader("n\n"), capturingStream().stream,
                        capturingStream().stream).confirmHistoryClear("текущего диалога"));
        expect("ответ «да» не подтверждает (только y/yes)",
                !new PlainTerminalUi(reader("да\n"), capturingStream().stream,
                        capturingStream().stream).confirmHistoryClear("текущего диалога"));
        expect("EOF отменяет удаление",
                !new PlainTerminalUi(reader(""), capturingStream().stream,
                        capturingStream().stream).confirmHistoryClear("текущего диалога"));
        CapturedStream demoErr = capturingStream();
        new PlainTerminalUi(reader("y\n"), capturingStream().stream, demoErr.stream)
                .confirmHistoryClear("временной беседы измерений");
        expect("в измерениях подтверждение называет временную беседу",
                demoErr.text().contains("Очистить историю временной беседы измерений?"));
        CapturedStream resetErr = capturingStream();
        new PlainTerminalUi(reader("y\n"), capturingStream().stream, resetErr.stream)
                .confirmReset();
        expect("подтверждение /reset в едином формате с маркером",
                resetErr.text().contains("? Начать новую беседу и очистить историю текущей?"));
        CapturedStream branchErr = capturingStream();
        new PlainTerminalUi(reader("y\n"), capturingStream().stream, branchErr.stream)
                .confirmBranchDelete("main");
        expect("подтверждение удаления ветки называет ветку",
                branchErr.text().contains("? Ветка «main» необратимо удаляется. Продолжить? [y/N]"));
    }

     static void checkClearDemoIsolation() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            bodies.add(requestBody);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ " + hitCounter.get() + "\"}}],\"usage\":{\"prompt_tokens\":15,"
                    + "\"completion_tokens\":25}}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            JsonConversationStore mainStore = tempStore();
            LlmAgent mainAgent = newEnvAgent(config, client, mainStore, new java.util.HashMap<>());
            mainAgent.ask("основной вопрос");
            int mainHits = hitCounter.get();

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/demo tokens"),
                    TerminalUi.Input.message("вопрос демо один"),
                    TerminalUi.Input.command("/clear"),
                    TerminalUi.Input.message("вопрос демо два"),
                    TerminalUi.Input.command("/demo stats"),
                    TerminalUi.Input.command("/demo stop"),
                    TerminalUi.Input.command("/exit"));
            ui.confirmClearAnswer = true;
            Main.runLoop(ui, mainAgent, "glm-5.3-flash");

            expect("/clear в режиме измерений называет временную беседу и сохраняет таблицу",
                    ui.systems.stream().anyMatch(s ->
                            s.contains("История временной беседы измерений удалена")
                                    && s.contains("Расход и таблица режима измерений сохранены")));
            expect("таблица демо показывает очистку истории между попытками",
                    ui.systems.stream().anyMatch(s ->
                            s.contains("история очищена (/clear)")
                                    && s.contains("1 | 15 | 25 | 40")
                                    && s.contains("2 | 15 | 25 | 80")));
            expect("запрос после очистки демо идёт с пустой историей",
                    MAPPER.readTree(bodies.get(2)).path("messages").size() == 2
                            && bodies.get(2).contains("вопрос демо два")
                            && !bodies.get(2).contains("вопрос демо один"));
            expect("основная беседа не изменялась демо-очисткой",
                    mainAgent.getHistory().equals(List.of(
                            new ChatMessage("user", "основной вопрос"),
                            new ChatMessage("assistant", "Ответ 1")))
                            && hitCounter.get() == mainHits + 2);
            mainStore.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkClearErrorKeepsMemory() {
        Config config = new Config("test-key",
                "https://example.com/v1/chat/completions", "glm-5.3-flash");
        LlmAgent agent = new LlmAgent(config, new HistoryButFailingStore());
        FakeUi ui = new FakeUi(
                TerminalUi.Input.command("/clear"),
                TerminalUi.Input.command("/exit"));
        ui.confirmClearAnswer = true;
        expect("после ошибки записи чат продолжает работать",
                Main.runLoop(ui, agent, "test-model") == 0);
        expect("ошибка записи не интерпретируется как успешное удаление",
                ui.errors.stream().anyMatch(s -> s.contains("Очистка не выполнена"))
                        && ui.systems.stream().noneMatch(s ->
                        s.contains("История текущего диалога удалена")));
        expect("при ошибке записи история в памяти сохранена",
                agent.getHistory().equals(List.of(
                        new ChatMessage("user", "вопрос до сбоя"),
                        new ChatMessage("assistant", "ответ до сбоя"))));
    }

     static void checkDemoTokensMode() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            bodies.add(requestBody);
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"Ответ "
                    + hitCounter.get() + "\"}}],\"usage\":{\"prompt_tokens\":123,"
                    + "\"completion_tokens\":45,\"total_tokens\":500}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            Map<String, String> env = new java.util.HashMap<>();
            env.put("LLM_CONTEXT_MAX_TURNS", "1");
            env.put("LLM_INPUT_PRICE_PER_1M", "0.6");
            env.put("LLM_OUTPUT_PRICE_PER_1M", "2.2");
            JsonConversationStore mainStore = tempStore();
            LlmAgent mainAgent = newEnvAgent(config, client, mainStore, env);

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/demo tokens"),
                    TerminalUi.Input.message("вопрос демо один"),
                    TerminalUi.Input.message("вопрос демо два"),
                    TerminalUi.Input.command("/demo stats"),
                    TerminalUi.Input.command("/demo stop"),
                    TerminalUi.Input.message("вопрос основной беседы"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, mainAgent, "glm-5.3-flash");

            // Включение режима не вызывает API; ручной ввод — ровно по одному.
            expect("в демо-режиме три ручных запроса по одному разу",
                    hitCounter.get() == 3 && bodies.size() == 3);
            expect("включение режима показывает вступление без запроса к API",
                    ui.systems.stream().anyMatch(s -> s.contains("Режим измерения токенов включён"))
                            && !ui.systems.stream().limit(1).anyMatch(s -> s.contains("Запрос №")));
            expect("после ответа выводятся фактические метрики запроса",
                    ui.systems.stream().anyMatch(s -> s.contains("Запрос №1")
                            && s.contains("Вход: 123 токенов")
                            && s.contains("Выход: 45 токенов")
                            && s.contains("Расход этого запроса: 168 токенов")));
            expect("накопленный расход и накопленная стоимость из usage",
                    ui.systems.stream().anyMatch(s -> s.contains("Накопленный расход беседы: 336 токенов")
                            && s.contains("Накопленная стоимость: ≈$0.000346")));
            expect("стоимость подписана как расчётная по тарифу",
                    ui.systems.stream().anyMatch(s ->
                            s.contains("Расчётная стоимость по настроенному тарифу, "
                                    + "не подтверждённое списание провайдера")));
            expect("время HTTP и причина завершения показываются",
                    ui.systems.stream().anyMatch(s -> s.contains("Время HTTP:")
                            && s.contains("Завершение: stop")));

            // В демо отправляется вся история без ограничения пар,
            // хотя в основной беседе действует LLM_CONTEXT_MAX_TURNS=1.
            JsonNode secondDemoBody = MAPPER.readTree(bodies.get(1));
            expect("в демо вся история уходит без ограничения пар",
                    secondDemoBody.path("messages").size() == 4
                            && secondDemoBody.toString().contains("вопрос демо один"));

            // /demo stats и /demo stop не вызывают API (3 хита — по числу сообщений).
            expect("таблица демо содержит все попытки и сводку",
                    ui.systems.stream().anyMatch(s -> s.contains("Таблица временной беседы измерений")
                            && s.contains("№ | Вход API | Выход API | Накоплено")
                            && s.contains("1 | 123 | 45 | 168")
                            && s.contains("2 | 123 | 45 | 336")
                            && s.contains("вход первого успешного запроса: 123")
                            && s.contains("вход последнего успешного запроса: 123")
                            && s.contains("разница входа: +0")
                            && s.contains("накопленный известный расход: 336 токенов")
                            && s.contains("полные данные")));
            expect("остановка режима возвращает основную беседу",
                    ui.systems.stream().anyMatch(s ->
                            s.contains("Режим измерения токенов завершён")));

            // Основная беседа изолирована: только её собственное сообщение.
            expect("основная история не изменялась демо-режимом",
                    mainAgent.getHistory().equals(List.of(
                            new ChatMessage("user", "вопрос основной беседы"),
                            new ChatMessage("assistant", "Ответ 3"))));
            expect("счётчики основной беседы считают только её запросы",
                    mainAgent.sessionStats().apiAttempts() == 1
                            && mainAgent.sessionStats().knownTotal() == 168);
            mainStore.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDemoFailureClassifications() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            if (requestBody.contains("case=http-500")) {
                return new Response(500, ("{\"error\":{\"code\":\"internal_error\","
                        + "\"message\":\"internal\"}}").getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=ctx-sizes")) {
                return new Response(400, ("{\"error\":{\"code\":\"context_length_exceeded\","
                        + "\"message\":\"This model's maximum context length is 4096 tokens. "
                        + "However, you requested 5000 tokens\"}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=other-400")) {
                return new Response(400, ("{\"error\":{\"code\":\"invalid_request_error\","
                        + "\"message\":\"Invalid parameter: temperature\"}}").getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ\"}}],\"usage\":{\"prompt_tokens\":123,"
                    + "\"completion_tokens\":45}}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);

            // HTTP-ошибка без признаков переполнения: причина не подтверждается.
            JsonConversationStore httpStore = tempStore();
            LlmAgent httpAgent = newEnvAgent(config, client, httpStore, new java.util.HashMap<>());
            FakeUi httpUi = new FakeUi(
                    TerminalUi.Input.command("/demo tokens"),
                    TerminalUi.Input.message("case=http-500"),
                    TerminalUi.Input.message("вопрос после ошибки"),
                    TerminalUi.Input.command("/demo stats"),
                    TerminalUi.Input.command("/demo stop"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(httpUi, httpAgent, "glm-5.3-flash");
            expect("ошибка API в демо получает номер и статус",
                    httpUi.systems.stream().anyMatch(s -> s.contains("Запрос №1 не выполнен")
                            && s.contains("HTTP-статус: 500")
                            && s.contains("Код провайдера: internal_error")));
            expect("прочий HTTP-статус не называется переполнением контекста",
                    httpUi.systems.stream().anyMatch(s -> s.contains("причина не подтверждена"))
                            && httpUi.messages.size() == 1);
            expect("неуспешная попытка попадает в таблицу без выдуманного расхода",
                    httpUi.systems.stream().anyMatch(s -> s.contains("Таблица временной беседы измерений")
                            && s.contains("1 | нет данных | нет данных | 0")
                            && s.contains("HTTP 500 (internal_error)")
                            && s.contains("2 | 123 | 45 | 168")));
            expect("сводка помечает неполноту данных после неуспешной попытки",
                    httpUi.systems.stream().anyMatch(s ->
                            s.contains("накопленный известный расход: 168 токенов")
                                    && s.contains("неполные данные")));
            expect("основная история при демо с ошибкой остаётся пустой",
                    httpAgent.getHistory().isEmpty()
                            && httpAgent.sessionStats().apiAttempts() == 0);
            httpStore.close();

            // Подтверждённое переполнение: показываются слова режима и размеры
            // провайдера, если он их сообщил.
            JsonConversationStore overflowStore = tempStore();
            LlmAgent overflowAgent = newEnvAgent(config, client, overflowStore, new java.util.HashMap<>());
            FakeUi overflowUi = new FakeUi(
                    TerminalUi.Input.command("/demo tokens"),
                    TerminalUi.Input.message("case=ctx-sizes"),
                    TerminalUi.Input.command("/demo stop"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(overflowUi, overflowAgent, "glm-5.3-flash");
            expect("подтверждённое переполнение описывается отдельным текстом",
                    overflowUi.systems.stream().anyMatch(s ->
                            s.contains("API отклонил запрос: превышен допустимый контекст")
                                    && s.contains("Ответ на это сообщение не получен")
                                    && s.contains("Завершённая история сохранена")
                                    && s.contains("переполнение контекста подтверждено ответом API")));
            expect("размеры берутся из текста ошибки провайдера",
                    overflowUi.systems.stream().anyMatch(s ->
                            s.contains("лимит 4096 токенов, запрошено 5000 токенов")));
            expect("после отклонённого запроса демо-история пуста",
                    overflowAgent.getHistory().isEmpty());
            overflowStore.close();

            // Прочий HTTP 400 в демо не переполнение.
            JsonConversationStore otherStore = tempStore();
            LlmAgent otherAgent = newEnvAgent(config, client, otherStore, new java.util.HashMap<>());
            FakeUi otherUi = new FakeUi(
                    TerminalUi.Input.command("/demo tokens"),
                    TerminalUi.Input.message("case=other-400"),
                    TerminalUi.Input.command("/demo stop"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(otherUi, otherAgent, "glm-5.3-flash");
            expect("HTTP 400 без признаков переполнения остаётся неопределённым",
                    otherUi.systems.stream().anyMatch(s ->
                            s.contains("HTTP-статус: 400")
                                    && s.contains("причина не подтверждена"))
                            && otherUi.systems.stream()
                            .noneMatch(s -> s.contains("превышен допустимый контекст")));
            otherStore.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkDemoCommandsNoApi() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ\"}}],\"usage\":{\"prompt_tokens\":10,"
                    + "\"completion_tokens\":5}}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            JsonConversationStore store = tempStore();
            LlmAgent agent = newEnvAgent(config, client, store, new java.util.HashMap<>());

            // Команды без включённого режима и повторы — без API.
            FakeUi hintsUi = new FakeUi(
                    TerminalUi.Input.command("/demo stats"),
                    TerminalUi.Input.command("/demo stop"),
                    TerminalUi.Input.command("/demo"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(hintsUi, agent, "glm-5.3-flash");
            expect("команды демо без включённого режима дают подсказки без API",
                    hitCounter.get() == 0
                            && hintsUi.systems.stream().anyMatch(s ->
                            s.contains("Режим измерения токенов не включён")));

            // Повторное включение и выход во время демо: тихая очистка.
            FakeUi doubleUi = new FakeUi(
                    TerminalUi.Input.command("/demo tokens"),
                    TerminalUi.Input.command("/demo tokens"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(doubleUi, agent, "glm-5.3-flash");
            expect("повторное включение режима не создаёт вторую беседу",
                    doubleUi.systems.stream().anyMatch(s ->
                            s.contains("Режим измерения токенов уже включён")));
            expect("выход во время демо закрывает её без изменения основной истории",
                    doubleUi.systems.stream().anyMatch(s ->
                            s.contains("Временная беседа измерений закрыта"))
                            && agent.getHistory().isEmpty() && agent.sessionStats().apiAttempts() == 0);
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

     static void checkPasteInput() {
        CapturedStream out = capturingStream();
        CapturedStream err = capturingStream();
        PlainTerminalUi pasteUi = new PlainTerminalUi(
                reader("/paste\nпервая строка длинного текста\nвторая строка\n/send\n"),
                out.stream, err.stream);
        TerminalUi.Input composed = pasteUi.nextInput();
        expect("/paste отправляет вставленный текст одним сообщением",
                composed.type() == TerminalUi.InputType.MESSAGE
                        && composed.text().equals("первая строка длинного текста\nвторая строка"));
        expect("правила вставки показаны при входе в /paste",
                err.text().contains("одним сообщением") && err.text().contains("/cancel"));

        // /cancel отменяет вставку без запроса к API.
        out = capturingStream();
        err = capturingStream();
        PlainTerminalUi cancelUi = new PlainTerminalUi(
                reader("/paste\nчерновик\n/cancel\n/help\n"), out.stream, err.stream);
        TerminalUi.Input afterCancel = cancelUi.nextInput();
        expect("/paste /cancel отменяет ввод без отправки",
                afterCancel.type() == TerminalUi.InputType.COMMAND
                        && afterCancel.text().equals("/help"));

        // Метка активного режима видна в приглашении.
        out = capturingStream();
        err = capturingStream();
        PlainTerminalUi labelUi = new PlainTerminalUi(reader("exit\n"), out.stream, err.stream);
        labelUi.setActiveModeLabel("измерение");
        labelUi.nextInput();
        expect("приглашение показывает активный режим измерений",
                err.text().contains("[измерение]"));
        labelUi.setActiveModeLabel(null);
        out = capturingStream();
        err = capturingStream();
        PlainTerminalUi restoredUi = new PlainTerminalUi(reader("exit\n"), out.stream, err.stream);
        restoredUi.nextInput();
        expect("после выхода из режима приглашение обычное",
                !err.text().contains("[измерение]"));
    }

     static void checkDay10OtherStoreValidation() throws IOException {
        Path file = Files.createTempDirectory(baseTempDir, "day10-")
                .resolve("conversation.json");
        byte[] brokenFact = ("{\"schemaVersion\":3,\"sessionId\":\"s\",\"messages\":[],"
                + "\"facts\":{\"ключ\": 42}}").getBytes(StandardCharsets.UTF_8);
        Files.write(file, brokenFact);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            expectLoadCorrupted(store, file, "повреждённый facts распознаётся");
            expect("повреждённый facts не перезаписывается",
                    Arrays.equals(Files.readAllBytes(file), brokenFact));
        }

        byte[] brokenBranch = ("{\"schemaVersion\":3,\"sessionId\":\"s\",\"messages\":[],"
                + "\"branches\":{\"checkpointIndex\":3,\"active\":\"main\","
                + "\"list\":[{\"name\":\"main\",\"messages\":[]}]}}")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, brokenBranch);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            expectLoadCorrupted(store, file, "нечётный checkpoint распознаётся");
        }

        byte[] goodRoundtrip = ("{\"schemaVersion\":3,\"sessionId\":\"s\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"в\"},"
                + "{\"role\":\"assistant\",\"content\":\"о\"}],"
                + "\"facts\":{\"цель\": \"МАЯК\"}}").getBytes(StandardCharsets.UTF_8);
        Files.write(file, goodRoundtrip);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            ConversationState state = store.load();
            expect("facts восстанавливаются из файла, ветки по умолчанию",
                    state.facts() != null && state.facts().get("цель").equals("МАЯК")
                            && state.branches() != null
                            && state.branches().active().equals("main"));
        }
    }
}
