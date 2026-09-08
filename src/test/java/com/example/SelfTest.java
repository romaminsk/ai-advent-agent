package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

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
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Локальный самотест без платных запросов.
 * Поднимает встроенный JDK HTTPS-сервер с самоподписанным сертификатом
 * (Config требует HTTPS) и проверяет:
 * - ошибки конфигурации и пустой ввод;
 * - многошаговый диалог: состав и порядок messages, владение историей;
 * - неизменность истории при ошибках запроса;
 * - resetConversation, session ID, лимит истории целыми парами;
 * - снимок getHistory;
 * - диспетчер команд Main (через подставной интерфейс);
 * - plain-интерфейс: многострочный ввод, разделение потоков, отсутствие ANSI;
 * - обезвреживание управляющих последовательностей;
 * - индикатор ожидания (спиннер останавливается и стирает строку);
 * - хранилище контекста: JSON-формат, валидация, блокировка, безопасная запись;
 * - восстановление беседы новым экземпляром агента и передача восстановленных
 *   сообщений в API (в правильном порядке, без дублирования system);
 * - поведение при сбое записи и повреждённом файле;
 * - /reset и /clear с сохранением между запусками;
 * - интеграционный тест двух последовательных запусков процесса.
 *
 * Все проверки с файлами используют временные каталоги и никогда не трогают
 * настоящую переписку (~/.ai-advent-agent/conversation.json).
 *
 * Запуск: mvn test-compile exec:java@self-test
 */
public final class SelfTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final char[] KEYSTORE_PASSWORD = "changeit".toCharArray();

    /** Ожидаемый текст системной инструкции агента. */
    private static final String SYSTEM_PROMPT_TEXT =
            "Ты полезный ассистент. Учитывай историю диалога. "
                    + "Отвечай на языке пользователя, если он не попросил иначе. "
                    + "Если информации недостаточно, уточни вопрос.";

    /** Базовый временный каталог для всех файловых проверок; удаляется в конце. */
    private static Path baseTempDir;

    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        baseTempDir = Files.createTempDirectory("selftest-day7");
        try {
            checkConfigErrors();
            checkEmptyQueryNoApiCall();
            checkDialogOnLocalServer();
            checkConversationStore();
            checkPersistenceAcrossAgents();
            checkSaveFailure();
            checkCorruptedFile();
            checkLocking();
            checkMainCommandsPersistence();
            checkPlainTerminalUi();
            checkAnsiSanitizer();
            checkProgressSpinner();
            checkModelSettings();
            checkRequestParameters();
            checkContextLimit();
            checkDiagnosticsAndLimit();
            checkModeCommand();
            checkTwoProcessIntegration();
        } finally {
            deleteRecursively(baseTempDir);
        }

        System.out.println("OK: все проверки пройдены (" + passed + ").");
    }

    /** Хранилище во временном каталоге: тесты никогда не трогают настоящую историю. */
    private static JsonConversationStore tempStore() throws IOException {
        Path dir = Files.createTempDirectory(baseTempDir, "hist-");
        return new JsonConversationStore(dir.resolve("conversation.json"));
    }

    /** Рекурсивное удаление временного каталога; ошибки игнорируются. */
    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    // ---------- Проверки конфигурации ----------

    private static void checkConfigErrors() {
        expect("отсутствует LLM_API_KEY",
                expectConfigError(null, "https://example.com/v1/chat/completions", "glm-5.3-flash")
                        .contains("LLM_API_KEY"));

        expect("пустой LLM_MODEL",
                expectConfigError("test-key", "https://example.com/v1/chat/completions", "  ")
                        .contains("LLM_MODEL"));

        expect("URL без HTTPS отклоняется",
                expectConfigError("test-key", "http://example.com/v1/chat/completions", "glm-5.3-flash")
                        .contains("HTTPS"));
    }

    private static String expectConfigError(String key, String url, String model) {
        try {
            new Config(key, url, model);
        } catch (AgentException e) {
            return e.getMessage();
        }
        return "";
    }

    // ---------- Пустой ввод: API не вызывается ----------

    private static void checkEmptyQueryNoApiCall() {
        Config config = new Config("test-key", "https://127.0.0.1:1/v1/chat/completions", "test-model");
        LlmAgent agent = newAgentWithTempStore(config);
        // Порт 1 закрыт: если бы API вызывался, получили бы сетевую ошибку,
        // а не сообщение о пустом запросе.
        String message = expectAgentError(agent, "   ");
        expect("пустой ввод не вызывает API", message.contains("Пустой запрос"));
    }

    /** Агент с хранилищем во временном каталоге (обычный HttpClient). */
    private static LlmAgent newAgentWithTempStore(Config config) {
        try {
            return new LlmAgent(config, tempStore());
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось создать временное хранилище", e);
        }
    }

    // ---------- Многошаговый диалог на локальном сервере ----------

    private static void checkDialogOnLocalServer() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        // Состояние, наблюдаемое обработчиком сервера.
        AtomicInteger successCounter = new AtomicInteger();
        AtomicInteger hitCounter = new AtomicInteger();
        List<String> sessions = new ArrayList<>();
        AtomicReference<String> lastAuth = new AtomicReference<>();
        AtomicReference<String> lastBody = new AtomicReference<>();
        try {
            HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
                hitCounter.incrementAndGet();
                sessions.add(session);
                lastAuth.set(auth);
                lastBody.set(requestBody);

                int status;
                String body;
                // Сценарий выбираем по маркеру в сообщении пользователя; без маркера —
                // успешный ответ с уникальным текстом «Ответ N».
                if (requestBody.contains("case=bad-json")) {
                    status = 200;
                    body = "это не JSON";
                } else if (requestBody.contains("case=http-500")) {
                    status = 500;
                    body = "{\"error\":{\"message\":\"internal\"}}";
                } else if (requestBody.contains("case=no-choices")) {
                    status = 200;
                    body = "{\"object\":\"chat.completion\"}";
                } else if (requestBody.contains("case=empty-content")) {
                    status = 200;
                    body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"}}]}";
                } else {
                    status = 200;
                    body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Ответ "
                            + successCounter.incrementAndGet() + "\"}}]}";
                }
                return new Response(status, body.getBytes(StandardCharsets.UTF_8));
            });
            try {
                Config config = new Config("test-key",
                        "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                        "glm-5.3-flash");
                JsonConversationStore store = tempStore();
                LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(), trustedHttpClient(keyStore), store);
                Path historyFile = store.file();

                // --- Первый запрос: system + user ---
                String question1 = "Ответь одним словом: столица Франции?";
                String answer1 = agent.ask(question1);
                expect("первый ответ получен от тестового сервера", "Ответ 1".equals(answer1));

                // Пара user/assistant сразу сохраняется в файл истории.
                JsonNode savedFile = MAPPER.readTree(
                        Files.readString(historyFile, StandardCharsets.UTF_8));
                expect("после первого ответа пара сохранена в файл истории",
                        savedFile.path("messages").size() == 2
                                && question1.equals(savedFile.path("messages").get(0).path("content").asText())
                                && "Ответ 1".equals(savedFile.path("messages").get(1).path("content").asText()));

                JsonNode firstBody = MAPPER.readTree(lastBody.get());
                expect("первый запрос содержит ровно два сообщения",
                        firstBody.path("messages").size() == 2);
                expect("первое сообщение запроса — system с ожидаемым текстом",
                        "system".equals(firstBody.path("messages").get(0).path("role").asText())
                                && SYSTEM_PROMPT_TEXT.equals(
                                firstBody.path("messages").get(0).path("content").asText()));
                expect("второе сообщение запроса — user с текстом вопроса",
                        "user".equals(firstBody.path("messages").get(1).path("role").asText())
                                && question1.equals(
                                firstBody.path("messages").get(1).path("content").asText()));
                expect("в теле запроса model из конфигурации",
                        "glm-5.3-flash".equals(firstBody.path("model").asText()));
                expect("Authorization имеет вид Bearer <ключ>",
                        "Bearer test-key".equals(lastAuth.get()));

                // --- История после первого запроса ---
                expect("история содержит ровно одну пару user/assistant без system",
                        historyEquals(agent.getHistory(),
                                new ChatMessage("user", question1),
                                new ChatMessage("assistant", "Ответ 1")));

                // --- Второй запрос: system → user → assistant → user ---
                String question2 = "Какой мой второй вопрос?";
                String answer2 = agent.ask(question2);
                expect("второй ответ получен от тестового сервера", "Ответ 2".equals(answer2));

                JsonNode secondBody = MAPPER.readTree(lastBody.get());
                JsonNode secondMessages = secondBody.path("messages");
                expect("второй запрос содержит system, пару user/assistant и нового user",
                        secondMessages.size() == 4
                                && "system".equals(secondMessages.get(0).path("role").asText())
                                && "user".equals(secondMessages.get(1).path("role").asText())
                                && "assistant".equals(secondMessages.get(2).path("role").asText())
                                && "user".equals(secondMessages.get(3).path("role").asText()));
                expect("порядок и текст сообщений второго запроса сохранены",
                        question1.equals(secondMessages.get(1).path("content").asText())
                                && "Ответ 1".equals(secondMessages.get(2).path("content").asText())
                                && question2.equals(secondMessages.get(3).path("content").asText()));

                expect("история содержит обе пары в исходном порядке",
                        historyEquals(agent.getHistory(),
                                new ChatMessage("user", question1),
                                new ChatMessage("assistant", "Ответ 1"),
                                new ChatMessage("user", question2),
                                new ChatMessage("assistant", "Ответ 2")));
                expect("ответ assistant добавлен в историю ровно один раз",
                        agent.getHistory().stream()
                                .filter(m -> "assistant".equals(m.role()) && "Ответ 1".equals(m.content()))
                                .count() == 1);

                // --- Session ID стабилен внутри беседы ---
                expect("session ID стабилен до сброса",
                        sessions.size() == 2 && sessions.get(0) != null
                                && sessions.get(0).equals(sessions.get(1)));

                // --- Ошибки не меняют историю ---
                List<ChatMessage> historyBeforeErrors = agent.getHistory();

                expect("HTTP 500 даёт понятную ошибку",
                        expectAgentError(agent, "case=http-500").contains("HTTP-статус 500"));
                expect("после HTTP-ошибки история неизменна",
                        historyEquals(agent.getHistory(), historyBeforeErrors));

                expect("некорректный JSON распознаётся",
                        expectAgentError(agent, "case=bad-json").contains("корректным JSON"));
                expect("после некорректного JSON история неизменна",
                        historyEquals(agent.getHistory(), historyBeforeErrors));

                expect("пустой content распознаётся",
                        expectAgentError(agent, "case=empty-content").contains("пустой итоговый ответ"));
                expect("после пустого content история неизменна",
                        historyEquals(agent.getHistory(), historyBeforeErrors));

                expect("отсутствие choices распознаётся",
                        expectAgentError(agent, "case=no-choices").contains("choices"));
                expect("после отсутствия choices история неизменна",
                        historyEquals(agent.getHistory(), historyBeforeErrors));
                expect("маркеры ошибочных запросов не попали в историю",
                        agent.getHistory().stream().noneMatch(m -> m.content().startsWith("case=")));

                // --- Сброс беседы ---
                agent.resetConversation();
                expect("после reset история пуста", agent.getHistory().isEmpty());
                expect("после reset пустая беседа записана в файл",
                        MAPPER.readTree(Files.readString(historyFile, StandardCharsets.UTF_8))
                                .path("messages").isEmpty());

                String questionAfterReset = "вопрос после сброса";
                agent.ask(questionAfterReset);
                JsonNode resetBody = MAPPER.readTree(lastBody.get());
                JsonNode resetMessages = resetBody.path("messages");
                expect("первый запрос после сброса содержит только system и нового user",
                        resetMessages.size() == 2
                                && "system".equals(resetMessages.get(0).path("role").asText())
                                && questionAfterReset.equals(
                                resetMessages.get(1).path("content").asText()));
                expect("старые сообщения после сброса не отправляются",
                        resetBody.toString().contains(question1) == false);
                expect("после сброса session ID изменился",
                        sessions.size() >= 3
                                && !sessions.get(sessions.size() - 1).equals(sessions.get(1)));

                // --- Лимит истории: только целые старые пары ---
                for (int i = 1; i <= LlmAgent.MAX_HISTORY_TURNS + 1; i++) {
                    agent.ask("вопрос " + i);
                }
                // После сброса-запроса было 1 пара + 21 новая = 22 → старейшая удалена.
                List<ChatMessage> fullHistory = agent.getHistory();
                expect("история ограничена MAX_HISTORY_TURNS парами",
                        fullHistory.size() == LlmAgent.MAX_HISTORY_TURNS * 2);
                // Удалены пары «вопрос после сброса» и «вопрос 1»; остались целые пары 2..21.
                expect("удалены только самые старые целые пары",
                        "user".equals(fullHistory.get(0).role())
                                && "вопрос 2".equals(fullHistory.get(0).content())
                                && "user".equals(fullHistory.get(fullHistory.size() - 2).role())
                                && "вопрос 21".equals(fullHistory.get(fullHistory.size() - 2).content()));
                expect("пары в истории не разорваны (чередование user/assistant)",
                        rolesAlternate(fullHistory));

                String overflowQuestion = "вопрос " + (LlmAgent.MAX_HISTORY_TURNS + 2);
                agent.ask(overflowQuestion);
                JsonNode overflowBody = MAPPER.readTree(lastBody.get());
                JsonNode overflowMessages = overflowBody.path("messages");
                expect("запрос при полной истории содержит system, 20 пар и нового user",
                        overflowMessages.size() == 1 + LlmAgent.MAX_HISTORY_TURNS * 2 + 1);
                expect("в запросе остались только последние целые пары",
                        "system".equals(overflowMessages.get(0).path("role").asText())
                                && "вопрос 2".equals(overflowMessages.get(1).path("content").asText())
                                && "assistant".equals(overflowMessages.get(overflowMessages.size() - 2).path("role").asText())
                                && overflowQuestion.equals(overflowMessages.get(overflowMessages.size() - 1).path("content").asText())
                                && streamContents(overflowMessages).noneMatch("вопрос 1"::equals));

                // --- Неудачный запрос при заполненной истории ---
                List<ChatMessage> historyBeforeFailure = agent.getHistory();
                expectAgentError(agent, "case=http-500");
                expect("неудачный запрос при заполненной истории не удаляет старые пары",
                        historyEquals(agent.getHistory(), historyBeforeFailure));

                // --- Снимок getHistory ---
                List<ChatMessage> snapshot = agent.getHistory();
                boolean immutable;
                try {
                    snapshot.add(new ChatMessage("user", "взлом"));
                    immutable = false;
                } catch (UnsupportedOperationException e) {
                    immutable = true;
                }
                expect("getHistory возвращает неизменяемый снимок", immutable);
                agent.ask("вопрос для снимка");
                expect("снимок истории не изменяется при новых запросах",
                        snapshot.size() == LlmAgent.MAX_HISTORY_TURNS * 2);

                // --- Диспетчер команд Main и интерфейс ---
                checkMainDispatch(agent, hitCounter);
            } finally {
                server.stop(0);
            }
        } finally {
            Files.deleteIfExists(keyStore);
        }
    }

    // ---------- Диспетчер команд Main (через подставной интерфейс) ----------

    /** Подставной интерфейс: сценарий ввода фиксирован, вывод накапливается. */
    private static final class FakeUi implements TerminalUi {
        private final List<TerminalUi.Input> script;
        private int cursor = 0;
        final List<String> messages = new ArrayList<>();
        final List<String> systems = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        int helpCount = 0;
        int historyCalls = 0;
        int clearCount = 0;
        int progressCount = 0;
        int confirmCount = 0;
        boolean confirmAnswer = false;

        FakeUi(TerminalUi.Input... inputs) {
            this.script = List.of(inputs);
        }

        @Override
        public TerminalUi.Input nextInput() {
            if (cursor >= script.size()) {
                return TerminalUi.Input.eof();
            }
            return script.get(cursor++);
        }

        @Override
        public void showWelcome(String model) {
        }

        @Override
        public void showMessage(String answer) {
            messages.add(answer);
        }

        @Override
        public void showSystem(String text) {
            systems.add(text);
        }

        @Override
        public void showError(String text) {
            errors.add(text);
        }

        @Override
        public void showHelp() {
            helpCount++;
        }

        @Override
        public void showHistory(List<ChatMessage> history) {
            historyCalls++;
        }

        @Override
        public boolean confirmReset() {
            confirmCount++;
            return confirmAnswer;
        }

        @Override
        public TerminalUi.ProgressIndicator startProgress() {
            progressCount++;
            return () -> {
            };
        }

        @Override
        public void clearScreen() {
            clearCount++;
        }

        @Override
        public void close() {
        }
    }

    private static void checkMainDispatch(LlmAgent agent, AtomicInteger hitCounter) {
        // Начинаем с чистой беседы, чтобы ожидания были детерминированы.
        agent.resetConversation();

        // Служебные команды не вызывают API.
        int hitsBefore = hitCounter.get();
        FakeUi commandsUi = new FakeUi(
                TerminalUi.Input.command("/help"),
                TerminalUi.Input.command("/history"),
                TerminalUi.Input.command("/clear"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(commandsUi, agent, "test-model");
        expect("команды не вызывают API", hitCounter.get() == hitsBefore);
        expect("/help выводит справку", commandsUi.helpCount == 1);
        expect("/history вызывается", commandsUi.historyCalls == 1);
        expect("/clear очищает только экран", commandsUi.clearCount == 1);
        expect("/exit завершает приложение", commandsUi.systems.stream()
                .anyMatch(s -> s.contains("Работа завершена")));

        // Неизвестная команда — подсказка, а не запрос к API.
        agent.resetConversation();
        hitsBefore = hitCounter.get();
        FakeUi unknownUi = new FakeUi(TerminalUi.Input.command("/foo"));
        Main.runLoop(unknownUi, agent, "test-model");
        expect("неизвестная команда выдаёт подсказку и не вызывает API",
                hitCounter.get() == hitsBefore
                        && unknownUi.systems.stream().anyMatch(s -> s.contains("Неизвестная команда")));

        // Сообщение уходит агенту ровно один раз.
        agent.resetConversation();
        hitsBefore = hitCounter.get();
        FakeUi messageUi = new FakeUi(
                TerminalUi.Input.message("Ответь одним словом: столица Франции?"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(messageUi, agent, "test-model");
        expect("MESSAGE вызывает агент ровно один раз",
                hitCounter.get() == hitsBefore + 1 && messageUi.messages.size() == 1);

        // /clear не трогает историю.
        agent.resetConversation();
        FakeUi clearUi = new FakeUi(
                TerminalUi.Input.message("вопрос для /clear"),
                TerminalUi.Input.command("/clear"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(clearUi, agent, "test-model");
        expect("/clear не сбрасывает историю",
                clearUi.clearCount == 1 && agent.getHistory().size() == 2);

        // /reset с подтверждением: отказ сохраняет историю.
        agent.resetConversation();
        FakeUi resetNoUi = new FakeUi(
                TerminalUi.Input.message("вопрос для /reset"),
                TerminalUi.Input.command("/reset"),
                TerminalUi.Input.command("/exit"));
        resetNoUi.confirmAnswer = false;
        Main.runLoop(resetNoUi, agent, "test-model");
        expect("/reset при отказе сохраняет историю",
                resetNoUi.confirmCount == 1 && agent.getHistory().size() == 2
                        && resetNoUi.systems.stream().anyMatch(s -> s.contains("отменён")));

        // /reset после подтверждения очищает историю.
        FakeUi resetYesUi = new FakeUi(TerminalUi.Input.command("/reset"), TerminalUi.Input.command("/exit"));
        resetYesUi.confirmAnswer = true;
        Main.runLoop(resetYesUi, agent, "test-model");
        expect("/reset после подтверждения начинает новую беседу",
                resetYesUi.confirmCount == 1 && agent.getHistory().isEmpty()
                        && resetYesUi.systems.stream().anyMatch(s -> s.contains("Начата новая беседа")));

        // /reset при пустой истории — без подтверждения.
        FakeUi resetEmptyUi = new FakeUi(TerminalUi.Input.command("/reset"), TerminalUi.Input.command("/exit"));
        Main.runLoop(resetEmptyUi, agent, "test-model");
        expect("/reset при пустой истории не запрашивает подтверждение",
                resetEmptyUi.confirmCount == 0 && resetEmptyUi.systems.stream()
                        .anyMatch(s -> s.contains("Начата новая беседа")));

        // После обычной ошибки можно продолжить чат.
        agent.resetConversation();
        hitsBefore = hitCounter.get();
        FakeUi errorUi = new FakeUi(
                TerminalUi.Input.message("case=http-500"),
                TerminalUi.Input.message("вопрос после ошибки"),
                TerminalUi.Input.command("exit"));
        Main.runLoop(errorUi, agent, "test-model");
        expect("ошибка запроса не прерывает чат",
                hitCounter.get() == hitsBefore + 2
                        && errorUi.errors.stream().anyMatch(s -> s.contains("HTTP-статус 500"))
                        && errorUi.messages.size() == 1
                        && agent.getHistory().size() == 2);

        // exit и quit совместимы с прежним поведением.
        FakeUi exitUi = new FakeUi(TerminalUi.Input.command("exit"));
        Main.runLoop(exitUi, agent, "test-model");
        FakeUi quitUi = new FakeUi(TerminalUi.Input.command("quit"));
        Main.runLoop(quitUi, agent, "test-model");
        expect("exit и quit завершают приложение",
                exitUi.systems.stream().anyMatch(s -> s.contains("Работа завершена"))
                        && quitUi.systems.stream().anyMatch(s -> s.contains("Работа завершена")));

        // EOF корректно завершает цикл.
        FakeUi eofUi = new FakeUi();
        Main.runLoop(eofUi, agent, "test-model");
        expect("EOF завершает приложение", eofUi.systems.stream()
                .anyMatch(s -> s.contains("Работа завершена")));
    }

    // ---------- Plain-интерфейс: ввод, многострочный режим, отсутствие ANSI ----------

    private static void checkPlainTerminalUi() {
        CapturedStream out;
        CapturedStream err;

        // Многострочный режим: строки собираются в одно сообщение.
        PlainTerminalUi ui = new PlainTerminalUi(
                reader("/multiline\nпервая строка\nвторая строка\n/send\n"),
                capturingStream().stream, capturingStream().stream);
        TerminalUi.Input composed = ui.nextInput();
        expect("многострочный ввод отправляется одним сообщением",
                composed.type() == TerminalUi.InputType.MESSAGE
                        && composed.text().equals("первая строка\nвторая строка"));

        // /cancel отменяет набор и возвращает обычное приглашение.
        ui = new PlainTerminalUi(
                reader("/multiline\nчерновик\n/cancel\n/help\n"),
                capturingStream().stream, capturingStream().stream);
        TerminalUi.Input afterCancel = ui.nextInput();
        expect("/cancel отменяет набор без отправки",
                afterCancel.type() == TerminalUi.InputType.COMMAND
                        && afterCancel.text().equals("/help"));

        // /send при пустом наборе: API не вызывается, набор продолжается.
        ui = new PlainTerminalUi(
                reader("/multiline\n/send\nтекст после пустой отправки\n/send\n"),
                capturingStream().stream, capturingStream().stream);
        TerminalUi.Input afterEmptySend = ui.nextInput();
        expect("пустой /send не отправляет сообщение",
                afterEmptySend.type() == TerminalUi.InputType.MESSAGE
                        && afterEmptySend.text().equals("текст после пустой отправки"));

        // EOF корректно завершает ввод.
        ui = new PlainTerminalUi(reader(""), capturingStream().stream, capturingStream().stream);
        expect("EOF даёт Input(EOF)", ui.nextInput().type() == TerminalUi.InputType.EOF);

        // Разделение потоков: ответы — в stdout, остальное — в stderr; без ANSI.
        out = capturingStream();
        err = capturingStream();
        PlainTerminalUi splitUi = new PlainTerminalUi(reader("exit\n"), out.stream, err.stream);
        splitUi.showWelcome("test-model");
        TerminalUi.Input input = splitUi.nextInput();
        splitUi.showSystem("Служебное сообщение без секретов");
        splitUi.showError("что-то сломалось");
        splitUi.showHelp();
        splitUi.showHistory(List.of());
        splitUi.showMessage("Ответ **с Markdown**\nи переносами");
        String outText = out.text();
        String errText = err.text();
        expect("exit распознаётся как команда",
                input.type() == TerminalUi.InputType.COMMAND && input.text().equals("exit"));
        expect("ответы идут в stdout, служебные сообщения — в stderr",
                outText.contains("Агент") && outText.contains("с Markdown")
                        && !errText.contains("с Markdown")
                        && errText.contains("AI Advent Agent")
                        && errText.contains("История диалога пуста."));
        expect("plain-режим не содержит ANSI-последовательностей",
                !outText.contains("\u001B") && !errText.contains("\u001B"));
        expect("приветствие plain-режима сообщает о контексте",
                errText.contains("Контекст текущей беседы включён"));
    }

    /** Вывод в память для проверок: и как PrintStream, и как текст. */
    private static CapturedStream capturingStream() {
        return new CapturedStream();
    }

    private static final class CapturedStream {
        final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        final PrintStream stream = new PrintStream(buffer, true, StandardCharsets.UTF_8);

        String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    /** Потоковый ввод со заранее заданным текстом — для тестов интерфейса. */
    private static BufferedReader reader(String input) {
        return new BufferedReader(new StringReader(input));
    }

    // ---------- Обезвреживание управляющих последовательностей ----------

    private static void checkAnsiSanitizer() {
        expect("CSI-последовательности удаляются",
                AnsiSanitizer.sanitize("\u001b[31mкрасный\u001b[0m").equals("красный"));
        expect("OSC (заголовок окна) удаляется",
                AnsiSanitizer.sanitize("\u001b]0;взлом\u0007текст").equals("текст"));
        expect("переносы и табуляция сохраняются",
                AnsiSanitizer.sanitize("a\nb\tc").equals("a\nb\tc"));
        expect("одиночный ESC удаляется",
                !AnsiSanitizer.sanitize("a\u001bb").contains("\u001B"));
        expect("CRLF заменяется обычным переносом",
                AnsiSanitizer.sanitize("a\r\nb\rc").equals("a\nb\nc"));
    }

    // ---------- Индикатор ожидания ----------

    private static void checkProgressSpinner() {
        // В отключённом режиме спиннер не печатает ничего.
        StringWriter disabledBuffer = new StringWriter();
        new ProgressSpinner(new PrintWriter(disabledBuffer), false).close();
        expect("отключённый спиннер не печатает ничего", disabledBuffer.toString().isEmpty());

        // Включённый спиннер: close() останавливает поток и стирает строку с курсором.
        StringWriter enabledBuffer = new StringWriter();
        ProgressSpinner enabled = new ProgressSpinner(new PrintWriter(enabledBuffer), true);
        enabled.close();
        String output = enabledBuffer.toString();
        expect("спиннер показывает «Ожидаем ответ…»", output.contains("Ожидаем ответ"));
        expect("спиннер останавливается и стирает строку",
                !enabled.isThreadAlive() && output.endsWith("\r\u001b[2K\u001b[?25h"));
    }

    // ---------- Хранилище контекста: формат, валидация, запись ----------

    private static void checkConversationStore() throws IOException {
        Path storeFile;
        List<ChatMessage> roundTripMessages;
        String roundTripSessionId;
        // Отсутствующий файл — новая пустая беседа, ничего не записывается.
        try (JsonConversationStore store = tempStore()) {
            ConversationState loaded = store.load();
            expect("отсутствующий файл истории даёт пустую беседу",
                    loaded.messages().isEmpty() && !loaded.sessionId().isBlank());
            expect("при отсутствии файла истории JSON не создаётся заранее",
                    !Files.exists(store.file()));

            // Сохранение и чтение: версия формата, sessionId, пары.
            ChatMessage user = new ChatMessage("user", "Запомни кодовое слово: «северный маяк».");
            ChatMessage assistant = new ChatMessage("assistant", "Кодовое слово: северный маяк.");
            store.save(new ConversationState(loaded.sessionId(), List.of(user, assistant)));
            expect("после сохранения файл истории существует", Files.exists(store.file()));

            JsonNode saved = MAPPER.readTree(Files.readString(store.file(), StandardCharsets.UTF_8));
            expect("файл содержит schemaVersion "
                            + JsonConversationStore.SUPPORTED_SCHEMA_VERSION,
                    saved.path("schemaVersion").asInt(-1)
                            == JsonConversationStore.SUPPORTED_SCHEMA_VERSION);
            expect("файл содержит sessionId беседы",
                    loaded.sessionId().equals(saved.path("sessionId").asText()));
            expect("файл содержит целую пару user/assistant",
                    saved.path("messages").size() == 2
                            && "user".equals(saved.path("messages").get(0).path("role").asText())
                            && user.content().equals(saved.path("messages").get(0).path("content").asText())
                            && "assistant".equals(saved.path("messages").get(1).path("role").asText()));

            // Кириллица, кавычки, переносы строк, табуляция и код — без потерь.
            String tricky = "Кириллица \"в кавычках\" и 'апострофы'\nвторая строка\tс табуляцией\n"
                    + "```java\nif (a < b && c > d) { String s = \"тест\"; }\n```\n"
                    + "символы: \\ \" № — и перенос в конце";
            String trickyAnswer = tricky + "\nстрока ответа";
            store.save(new ConversationState("sid-тест", List.of(
                    new ChatMessage("user", tricky), new ChatMessage("assistant", trickyAnswer))));
            ConversationState roundTrip = store.load();
            expect("кириллица, кавычки, переносы, табуляция и код сохраняются без потерь",
                    roundTrip.messages().size() == 2
                            && tricky.equals(roundTrip.messages().get(0).content())
                            && trickyAnswer.equals(roundTrip.messages().get(1).content()));
            storeFile = store.file();
            roundTripMessages = roundTrip.messages();
            roundTripSessionId = roundTrip.sessionId();
        }

        // Отдельное хранилище того же пути (после освобождения блокировки).
        try (JsonConversationStore reopened = new JsonConversationStore(storeFile)) {
            ConversationState reopenedState = reopened.load();
            expect("повторно открытое хранилище читает сохранённое состояние",
                    reopenedState.messages().equals(roundTripMessages)
                            && reopenedState.sessionId().equals(roundTripSessionId));
        }

        checkStoreValidation();
    }

    /** Повреждённые и неподдерживаемые файлы не читаются и не переписываются. */
    private static void checkStoreValidation() throws IOException {
        Path file = Files.createTempDirectory(baseTempDir, "valid-").resolve("conversation.json");

        byte[] garbage = "это вообще не { json".getBytes(StandardCharsets.UTF_8);
        Files.write(file, garbage);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            expectLoadCorrupted(store, file, "повреждённый JSON распознаётся");
            expect("повреждённый JSON не перезаписывается при чтении",
                    Arrays.equals(Files.readAllBytes(file), garbage));
        }

        byte[] futureVersion = "{\"schemaVersion\":99,\"sessionId\":\"s\",\"messages\":[]}"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, futureVersion);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            expectUnknownVersion(store, file);
            expect("файл с неизвестной версией не изменён",
                    Arrays.equals(Files.readAllBytes(file), futureVersion));
        }

        byte[] brokenPair = ("{\"schemaVersion\":1,\"sessionId\":\"s\",\"messages\":"
                + "[{\"role\":\"user\",\"content\":\"вопрос без ответа\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, brokenPair);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            expectLoadCorrupted(store, file, "обрыв пары (user без assistant) распознаётся");
        }

        byte[] systemRole = ("{\"schemaVersion\":1,\"sessionId\":\"s\",\"messages\":"
                + "[{\"role\":\"system\",\"content\":\"инструкция\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, systemRole);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            expectLoadCorrupted(store, file, "роль system в файле отклоняется (она не хранится)");
        }

        byte[] emptyContent = ("{\"schemaVersion\":1,\"sessionId\":\"s\",\"messages\":"
                + "[{\"role\":\"user\",\"content\":\"\"},{\"role\":\"assistant\",\"content\":\"ok\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, emptyContent);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            expectLoadCorrupted(store, file, "пустой текст сообщения отклоняется");
        }

        byte[] badMessages = "{\"schemaVersion\":1,\"sessionId\":\"s\",\"messages\":\"нет\"}"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, badMessages);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            expectLoadCorrupted(store, file, "messages не массив распознаётся");
        }

        byte[] noSession = "{\"schemaVersion\":1,\"messages\":[]}"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, noSession);
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            expectLoadCorrupted(store, file, "отсутствие sessionId распознаётся");
        }
    }

    private static void expectLoadCorrupted(JsonConversationStore store, Path file, String description) {
        boolean reported;
        try {
            store.load();
            reported = false;
        } catch (ConversationStoreException e) {
            reported = e.getMessage().contains(file.toString());
        }
        expect(description + " (ошибка содержит путь)", reported);
    }

    private static void expectUnknownVersion(JsonConversationStore store, Path file) {
        boolean reported;
        try {
            store.load();
            reported = false;
        } catch (ConversationStoreException e) {
            reported = e.getMessage().contains("версия") || e.getMessage().contains("версию");
        }
        expect("неизвестная версия формата даёт понятную ошибку с путём "
                + file.getFileName(), reported);
    }

    // ---------- Восстановление контекста вторым экземпляром агента ----------

    private static void checkPersistenceAcrossAgents() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger successCounter = new AtomicInteger();
        List<String> sessions = new ArrayList<>();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            sessions.add(session);
            lastBody.set(requestBody);
            if (requestBody.contains("case=http-500")) {
                return new Response(500,
                        "{\"error\":{\"message\":\"internal\"}}".getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=empty-content")) {
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"\"}}]}").getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ " + successCounter.incrementAndGet() + "\"}}]}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            Path historyFile = Files.createTempDirectory(baseTempDir, "persist-")
                    .resolve("conversation.json");

            // --- Первый «запуск»: успешная пара сохраняется в JSON ---
            String question1 = "Запомни: кодовое слово «северный маяк», цвет — зелёный.\n"
                    + "Вторая строка вопроса с \"кавычками\"";
            try (JsonConversationStore store1 = new JsonConversationStore(historyFile)) {
                LlmAgent agent1 = new LlmAgent(config, ModelSettings.defaults(), client, store1);
                expect("первый запуск начинает без истории",
                        !agent1.hasRestoredContext() && agent1.getHistory().isEmpty());
                expect("первый запуск получает ответ", "Ответ 1".equals(agent1.ask(question1)));
            }

            JsonNode saved = MAPPER.readTree(Files.readString(historyFile, StandardCharsets.UTF_8));
            expect("успешная пара user/assistant сохранена в JSON",
                    saved.path("messages").size() == 2
                            && question1.equals(saved.path("messages").get(0).path("content").asText())
                            && "Ответ 1".equals(saved.path("messages").get(1).path("content").asText()));
            String savedText = Files.readString(historyFile, StandardCharsets.UTF_8);
            expect("ключ API не попадает в сохранённый JSON",
                    !savedText.contains("test-key") && !savedText.contains("Bearer")
                            && !savedText.contains("Authorization"));
            String sessionIdInFile = saved.path("sessionId").asText();
            expect("sessionId сохранён в файле истории", !sessionIdInFile.isBlank());

            // --- Второй «запуск»: восстановление и передача контекста в API ---
            try (JsonConversationStore store2 = new JsonConversationStore(historyFile)) {
                LlmAgent agent2 = new LlmAgent(config, ModelSettings.defaults(), client, store2);
                expect("новый экземпляр восстанавливает пару из файла",
                        agent2.hasRestoredContext()
                                && agent2.getHistory().equals(List.of(
                                new ChatMessage("user", question1),
                                new ChatMessage("assistant", "Ответ 1"))));

                String question2 = "Какое кодовое слово и какой цвет я назвал?";
                expect("второй запуск получает ответ", "Ответ 2".equals(agent2.ask(question2)));

                JsonNode messages = MAPPER.readTree(lastBody.get()).path("messages");
                int systemCount = 0;
                for (JsonNode message : messages) {
                    if ("system".equals(message.path("role").asText())) {
                        systemCount++;
                    }
                }
                expect("system-сообщение ровно одно и стоит первым",
                        systemCount == 1 && "system".equals(messages.get(0).path("role").asText()));
                expect("восстановленные сообщения уходят в API в правильном порядке",
                        messages.size() == 4
                                && question1.equals(messages.get(1).path("content").asText())
                                && "Ответ 1".equals(messages.get(2).path("content").asText())
                                && question2.equals(messages.get(3).path("content").asText())
                                && "user".equals(messages.get(1).path("role").asText())
                                && "assistant".equals(messages.get(2).path("role").asText())
                                && "user".equals(messages.get(3).path("role").asText()));
                expect("sessionId восстановлен из файла",
                        sessionIdInFile.equals(sessions.get(sessions.size() - 1)));

                // --- Ошибки API: файл и память не меняются ---
                byte[] fileBeforeError = Files.readAllBytes(historyFile);
                List<ChatMessage> memoryBeforeError = agent2.getHistory();
                expect("HTTP-ошибка API распознаётся",
                        expectAgentError(agent2, "case=http-500").contains("HTTP-статус 500"));
                expect("после ошибки API файл истории не изменился",
                        Arrays.equals(Files.readAllBytes(historyFile), fileBeforeError));
                expect("после ошибки API память не изменилась",
                        agent2.getHistory().equals(memoryBeforeError));

                expect("пустой ответ распознаётся",
                        expectAgentError(agent2, "case=empty-content").contains("пустой итоговый ответ"));
                expect("после пустого ответа файл истории не изменился",
                        Arrays.equals(Files.readAllBytes(historyFile), fileBeforeError));
                expect("после пустого ответа память не изменилась",
                        agent2.getHistory().equals(memoryBeforeError));

                // --- Лимит: на диск сохраняется тот же урезанный контекст ---
                for (int i = 1; i <= LlmAgent.MAX_HISTORY_TURNS + 1; i++) {
                    agent2.ask("вопрос переполнения " + i);
                }
                List<ChatMessage> memoryAfterOverflow = agent2.getHistory();
                JsonNode overflowFile = MAPPER.readTree(
                        Files.readString(historyFile, StandardCharsets.UTF_8));
                List<ChatMessage> fileMessages = new ArrayList<>();
                overflowFile.path("messages").forEach(m -> fileMessages.add(
                        new ChatMessage(m.path("role").asText(), m.path("content").asText())));
                expect("файл хранит тот же ограниченный контекст, что и память",
                        fileMessages.equals(memoryAfterOverflow)
                                && memoryAfterOverflow.size() == LlmAgent.MAX_HISTORY_TURNS * 2);
                expect("лимит сохраняет на диск только целые пары",
                        rolesAlternate(memoryAfterOverflow)
                                && "user".equals(memoryAfterOverflow.get(0).role()));
            }
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    // ---------- Сбой записи после успешного ответа ----------

    /** Хранилище-двойник, у которого запись всегда падает. */
    private static final class FailingStore implements ConversationStore {
        final List<ConversationState> attempted = new ArrayList<>();

        @Override
        public ConversationState load() {
            return ConversationState.newEmpty();
        }

        @Override
        public void save(ConversationState state) {
            attempted.add(state);
            throw new ConversationStoreException("тестовый отказ записи (диск недоступен)");
        }

        @Override
        public void close() {
        }
    }

    private static void checkSaveFailure() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger successCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ " + successCounter.incrementAndGet() + "\"}}]}")
                        .getBytes(StandardCharsets.UTF_8)));
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            FailingStore failingStore = new FailingStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(), trustedHttpClient(keyStore), failingStore);

            // Ответ получен, но записать не удалось: отдельная ошибка сохранения.
            ConversationSaveException failure = null;
            try {
                agent.ask("вопрос при отказе записи");
            } catch (ConversationSaveException e) {
                failure = e;
            }
            expect("сбой записи после ответа даёт отдельную ошибку сохранения", failure != null);
            expect("полученный ответ доступен интерфейсу через ошибку сохранения",
                    failure != null && "Ответ 1".equals(failure.getAnswer()));
            expect("ошибка сохранения не называется ошибкой запроса к модели",
                    failure != null && failure.getMessage().contains("не сохранён")
                            && !failure.getMessage().contains("HTTP-статус"));
            expect("при сбое записи история в памяти не меняется", agent.getHistory().isEmpty());
            expect("при сбое записи выполняется ровно одна попытка сохранения (без повторов)",
                    failingStore.attempted.size() == 1);

            // Через UI: ответ показан, предупреждение показано, сессия завершается.
            FakeUi ui = new FakeUi(TerminalUi.Input.message("второй вопрос при отказе записи"));
            int exitCode = Main.runLoop(ui, agent, "test-model");
            expect("при сбое записи ответ модели показан пользователю",
                    ui.messages.contains("Ответ 2"));
            expect("при сбое записи выводится предупреждение о несохранённой паре",
                    ui.errors.stream().anyMatch(s -> s.contains("не сохранён")));
            expect("после сбоя записи сессия завершается с ошибкой", exitCode == 1);
            expect("после сбоя записи новая пара не добавлена в историю",
                    agent.getHistory().isEmpty());
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }

        checkSaveFailureKeepsPreviousFile();
    }

    /** Реальный сбой записи: каталог без прав записи; прежний файл остаётся пригодным. */
    private static void checkSaveFailureKeepsPreviousFile() throws IOException {
        Path dir = Files.createTempDirectory(baseTempDir, "readonly-");
        Path historyFile = dir.resolve("conversation.json");
        boolean checked = false;
        try (JsonConversationStore store = new JsonConversationStore(historyFile)) {
            store.save(new ConversationState("sid-1", List.of(
                    new ChatMessage("user", "первый вопрос"),
                    new ChatMessage("assistant", "первый ответ"))));
            byte[] before = Files.readAllBytes(historyFile);

            File dirAsFile = dir.toFile();
            if (dirAsFile.setWritable(false)) {
                try {
                    // Под root запрет записи не работает — тогда сценарий пропускается.
                    boolean writeBlocked;
                    try {
                        Files.createTempFile(dir, "probe-", ".tmp");
                        writeBlocked = false;
                    } catch (IOException e) {
                        writeBlocked = true;
                    }
                    if (writeBlocked) {
                        checked = true;
                        try {
                            store.save(new ConversationState("sid-2", List.of(
                                    new ChatMessage("user", "второй вопрос"),
                                    new ChatMessage("assistant", "второй ответ"))));
                            expect("запись в каталог без прав даёт ошибку сохранения", false);
                        } catch (ConversationStoreException e) {
                            expect("запись в каталог без прав даёт ошибку сохранения", true);
                        }
                        expect("при сбое записи предыдущий корректный файл сохранён",
                                Arrays.equals(Files.readAllBytes(historyFile), before));
                        try (var listed = Files.list(dir)) {
                            expect("после сбоя записи временные файлы удалены",
                                    listed.noneMatch(p -> p.toString().endsWith(".tmp")));
                        }
                    }
                } finally {
                    dirAsFile.setWritable(true);
                }
            }
        }
        if (!checked) {
            System.out.println("ПРОПУСК: проверка сбоя записи на каталоге без прав "
                    + "не выполнена (запись ограничить не удалось).");
        }
    }

    // ---------- Повреждённый файл истории ----------

    private static void checkCorruptedFile() throws IOException {
        Path file = Files.createTempDirectory(baseTempDir, "corrupt-")
                .resolve("conversation.json");
        byte[] garbage = "{\"schemaVersion\":1,\"sessionId\":\"s\",\"messages\":[{]"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, garbage);

        Config config = new Config("test-key", "https://example.com/v1/chat/completions",
                "glm-5.3-flash");
        try (JsonConversationStore store = new JsonConversationStore(file)) {
            boolean stopped;
            try {
                new LlmAgent(config, store);
                stopped = false;
            } catch (ConversationStoreException e) {
                stopped = e.getMessage().contains(file.toString());
            }
            expect("повреждённый файл останавливает запуск с понятной ошибкой", stopped);
        }
        expect("повреждённый файл не перезаписывается и не удаляется",
                Arrays.equals(Files.readAllBytes(file), garbage));
        try (var listed = Files.list(file.getParent())) {
            expect("при чтении повреждённого файла временные файлы не создаются",
                    listed.noneMatch(p -> p.toString().endsWith(".tmp")));
        }
    }

    // ---------- Блокировка от двух одновременных запусков ----------

    private static void checkLocking() throws IOException {
        Path file = Files.createTempDirectory(baseTempDir, "lock-")
                .resolve("conversation.json");
        String busyMessage = "";
        JsonConversationStore first = new JsonConversationStore(file);
        try {
            boolean blocked;
            try {
                JsonConversationStore second = new JsonConversationStore(file);
                second.close();
                blocked = false;
            } catch (ConversationStoreException e) {
                blocked = true;
                busyMessage = e.getMessage();
            }
            expect("второй экземпляр не получает доступ к занятой истории", blocked);
            expect("сообщение о занятой истории объясняет следующий шаг",
                    busyMessage.contains("уже открыта")
                            && busyMessage.contains("LLM_HISTORY_FILE"));
        } finally {
            first.close();
        }
        // Lock-файл остаётся, но блокировки больше нет — запуск возможен.
        try (JsonConversationStore after = new JsonConversationStore(file)) {
            expect("после освобождения блокировки новый запуск возможен",
                    after.load().messages().isEmpty());
        }
    }

    // ---------- /reset и /clear с сохранением между запусками ----------

    private static void checkMainCommandsPersistence() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger successCounter = new AtomicInteger();
        List<String> sessions = new ArrayList<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            sessions.add(session);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ " + successCounter.incrementAndGet() + "\"}}]}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            Path historyFile = Files.createTempDirectory(baseTempDir, "commands-")
                    .resolve("conversation.json");

            JsonConversationStore store1 = new JsonConversationStore(historyFile);
            LlmAgent agent1 = new LlmAgent(config, ModelSettings.defaults(), client, store1);
            agent1.ask("вопрос перед командами");
            byte[] afterAsk = Files.readAllBytes(historyFile);

            // /clear меняет только экран: файл и память не трогает.
            FakeUi clearUi = new FakeUi(
                    TerminalUi.Input.command("/clear"),
                    TerminalUi.Input.command("/exit"));
            expect("/clear завершает цикл нормально",
                    Main.runLoop(clearUi, agent1, "test-model") == 0);
            expect("/clear не меняет файл истории",
                    Arrays.equals(Files.readAllBytes(historyFile), afterAsk));
            expect("/clear не меняет память", agent1.getHistory().size() == 2);

            // Отказ от подтверждения (история непуста) оставляет файл без изменений.
            byte[] beforeDeclinedReset = Files.readAllBytes(historyFile);
            FakeUi resetNoUi = new FakeUi(
                    TerminalUi.Input.command("/reset"),
                    TerminalUi.Input.command("/exit"));
            resetNoUi.confirmAnswer = false;
            Main.runLoop(resetNoUi, agent1, "test-model");
            expect("/reset при отказе не меняет файл",
                    Arrays.equals(Files.readAllBytes(historyFile), beforeDeclinedReset));
            expect("/reset при отказе сохраняет память",
                    agent1.getHistory().size() == 2);

            // /reset с подтверждением: пустая беседа записывается на диск.
            String sessionIdBeforeReset = MAPPER.readTree(
                            Files.readString(historyFile, StandardCharsets.UTF_8))
                    .path("sessionId").asText();
            FakeUi resetUi = new FakeUi(
                    TerminalUi.Input.command("/reset"),
                    TerminalUi.Input.command("/exit"));
            resetUi.confirmAnswer = true;
            Main.runLoop(resetUi, agent1, "test-model");
            JsonNode afterReset = MAPPER.readTree(
                    Files.readString(historyFile, StandardCharsets.UTF_8));
            expect("/reset сохраняет пустую беседу на диск",
                    afterReset.path("messages").isEmpty());
            expect("после /reset создаётся новый sessionId",
                    !sessionIdBeforeReset.equals(afterReset.path("sessionId").asText()));
            expect("/reset очищает память", agent1.getHistory().isEmpty());
            expect("/reset сообщает о новой беседе",
                    resetUi.systems.stream().anyMatch(s -> s.contains("Начата новая беседа")));

            // Перезапуск: сброс пережил выход, старая история не вернулась.
            store1.close();
            try (JsonConversationStore store2 = new JsonConversationStore(historyFile)) {
                LlmAgent agent2 = new LlmAgent(config, ModelSettings.defaults(), client, store2);
                expect("после перезапуска история пуста (сброс переживает выход)",
                        agent2.getHistory().isEmpty() && !agent2.hasRestoredContext());
                agent2.ask("вопрос для проверки session id после перезапуска");
                expect("sessionId после /reset восстанавливается между запусками",
                        afterReset.path("sessionId").asText()
                                .equals(sessions.get(sessions.size() - 1)));
            }
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    // ---------- Настройки модели: профили, приоритет, валидация ----------

    private static void checkModelSettings() {
        Map<String, String> env = new java.util.HashMap<>();

        // Значения по умолчанию без переменных окружения.
        ModelSettings defaults = ModelSettings.from(env);
        expect("без переменных выбирается профиль balanced",
                ModelSettings.BALANCED.equals(defaults.profile()));
        expect("лимит генерации по умолчанию 2048", defaults.maxOutputTokens() == 2048);
        expect("таймаут по умолчанию 180 секунд", defaults.requestTimeoutSeconds() == 180);
        expect("temperature по умолчанию не отправляется", defaults.temperature() == null);
        expect("лимит контекста по умолчанию не задан", defaults.contextMaxTurns() == null);
        expect("диагностика по умолчанию выключена", !defaults.diagnostics());

        // Профили и приоритет явных значений.
        env.put("LLM_RESPONSE_MODE", " FAST ");
        expect("профиль fast читается без учёта регистра и пробелов",
                ModelSettings.FAST.equals(ModelSettings.from(env).profile())
                        && ModelSettings.from(env).maxOutputTokens() == 1024);

        env.put("LLM_MAX_OUTPUT_TOKENS", "777");
        ModelSettings overridden = ModelSettings.from(env);
        expect("LLM_MAX_OUTPUT_TOKENS переопределяет лимит профиля",
                overridden.maxOutputTokens() == 777 && overridden.limitOverridden());
        expect("при переключении профиля явный лимит сохраняется",
                overridden.withProfile("detailed").maxOutputTokens() == 777
                        && ModelSettings.DETAILED.equals(overridden.withProfile("detailed").profile()));

        env.remove("LLM_MAX_OUTPUT_TOKENS");
        ModelSettings fast = ModelSettings.from(env);
        expect("без переопределения переключение профиля меняет лимит",
                fast.withProfile(ModelSettings.DETAILED).maxOutputTokens() == 4096);

        env.put("LLM_TEMPERATURE", "0.3");
        expect("LLM_TEMPERATURE читается",
                Double.valueOf(0.3).equals(ModelSettings.from(env).temperature()));
        env.put("LLM_REQUEST_TIMEOUT_SECONDS", "30");
        env.put("LLM_CONTEXT_MAX_TURNS", "4");
        env.put("LLM_DIAGNOSTICS", "TRUE");
        ModelSettings full = ModelSettings.from(env);
        expect("таймаут и лимит контекста читаются",
                full.requestTimeoutSeconds() == 30 && full.contextMaxTurns() == 4);
        expect("LLM_DIAGNOSTICS=true включается без учёта регистра", full.diagnostics());

        // Граничные допустимые значения temperature.
        env.put("LLM_TEMPERATURE", "0");
        expect("temperature 0 допустима",
                Double.valueOf(0).equals(ModelSettings.from(env).temperature()));
        env.put("LLM_TEMPERATURE", "2.0");
        expect("temperature 2.0 допустима",
                Double.valueOf(2).equals(ModelSettings.from(env).temperature()));

        // Ошибки некорректных значений — с именем переменной в сообщении.
        expectSettingsError(env, "LLM_RESPONSE_MODE", "turbo");
        expectSettingsError(env, "LLM_MAX_OUTPUT_TOKENS", "0");
        expectSettingsError(env, "LLM_MAX_OUTPUT_TOKENS", "abc");
        expectSettingsError(env, "LLM_TEMPERATURE", "2.5");
        expectSettingsError(env, "LLM_TEMPERATURE", "NaN");
        expectSettingsError(env, "LLM_TEMPERATURE", "два");
        expectSettingsError(env, "LLM_REQUEST_TIMEOUT_SECONDS", "0");
        expectSettingsError(env, "LLM_CONTEXT_MAX_TURNS", "-1");
        expectSettingsError(env, "LLM_DIAGNOSTICS", "yes");
    }

    private static void expectSettingsError(Map<String, String> env, String name, String value) {
        Map<String, String> copy = new java.util.HashMap<>(env);
        copy.put(name, value);
        boolean reported;
        try {
            ModelSettings.from(copy);
            reported = false;
        } catch (AgentException e) {
            reported = e.getMessage().contains(name);
        }
        expect(name + "=" + value + " отклоняется с понятной ошибкой", reported);
    }

    // ---------- Параметры HTTP-запроса ----------

    private static void checkRequestParameters() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            lastBody.set(requestBody);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);

            // Профиль fast с явной temperature.
            Map<String, String> env = new java.util.HashMap<>();
            env.put("LLM_RESPONSE_MODE", "fast");
            env.put("LLM_TEMPERATURE", "0.3");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env), client, store);
            agent.ask("вопрос для проверки параметров");
            JsonNode body = MAPPER.readTree(lastBody.get());
            expect("в запросе отправляется max_tokens профиля fast (1024)",
                    body.path("max_tokens").asInt(-1) == 1024);
            expect("temperature включается при явном переопределении",
                    body.has("temperature") && body.path("temperature").asDouble(-1) == 0.3);
            expect("model и messages не изменены",
                    "glm-5.3-flash".equals(body.path("model").asText())
                            && body.path("messages").size() == 2);
            java.util.Set<String> fieldNames = new java.util.HashSet<>();
            body.fieldNames().forEachRemaining(fieldNames::add);
            java.util.Set<String> unsupported = java.util.Set.of(
                    "reasoning_effort", "enable_thinking", "thinking",
                    "max_completion_tokens", "top_p");
            expect("в запросе нет неподтверждённых провайдерских параметров",
                    fieldNames.stream().noneMatch(unsupported::contains));
            expect("для fast системная инструкция дополняется предпочтением краткости",
                    body.path("messages").get(0).path("content").asText()
                            .equals(LlmAgent.systemPromptFor(ModelSettings.FAST))
                            && body.path("messages").get(0).path("content").asText()
                            .contains("кратко и по существу"));
            store.close();

            // Базовый профиль balanced: temperature не отправляется.
            JsonConversationStore store2 = tempStore();
            LlmAgent balancedAgent = new LlmAgent(config, ModelSettings.defaults(), client, store2);
            balancedAgent.ask("вопрос для проверки базовых параметров");
            JsonNode balancedBody = MAPPER.readTree(lastBody.get());
            expect("в базовом профиле max_tokens равен 2048",
                    balancedBody.path("max_tokens").asInt(-1) == 2048);
            expect("без явного переопределения temperature не отправляется",
                    !balancedBody.has("temperature"));
            expect("в базовом профиле системная инструкция без изменений",
                    balancedBody.path("messages").get(0).path("content").asText()
                            .equals(SYSTEM_PROMPT_TEXT));
            store2.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    // ---------- Лимит отправляемого контекста: архив не теряется ----------

    private static void checkContextLimit() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            lastBody.set(requestBody);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            Map<String, String> env = new java.util.HashMap<>();
            env.put("LLM_CONTEXT_MAX_TURNS", "2");
            env.put("LLM_MAX_OUTPUT_TOKENS", "128");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env), client, store);
            Path historyFile = store.file();

            for (int i = 1; i <= 4; i++) {
                agent.ask("вопрос " + i);
            }
            agent.ask("вопрос с урезанным контекстом");

            JsonNode messages = MAPPER.readTree(lastBody.get()).path("messages");
            // system + 2 последние пары + новый запрос.
            expect("в запрос уходят только последние целые пары по лимиту контекста",
                    messages.size() == 6
                            && "system".equals(messages.get(0).path("role").asText())
                            && "вопрос 3".equals(messages.get(1).path("content").asText())
                            && "вопрос 4".equals(messages.get(3).path("content").asText())
                            && "вопрос с урезанным контекстом".equals(
                            messages.get(5).path("content").asText()));

            JsonNode saved = MAPPER.readTree(Files.readString(historyFile, StandardCharsets.UTF_8));
            expect("лимит отправки не удаляет архив: в файле все пары",
                    saved.path("messages").size() == 10);

            List<ChatMessage> memoryBeforeRestart = agent.getHistory();
            store.close();
            try (JsonConversationStore reopened = new JsonConversationStore(historyFile)) {
                LlmAgent restored = new LlmAgent(config, ModelSettings.from(env), client, reopened);
                expect("после перезапуска история восстанавливается целиком",
                        restored.getHistory().equals(memoryBeforeRestart));
            }
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    // ---------- Диагностика, finish_reason и обработка лимита ----------

    private static void checkDiagnosticsAndLimit() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        AtomicInteger successCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            if (requestBody.contains("case=truncated")) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"length\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"частичный ответ\"}}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=empty-length")) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"length\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"\"}}]}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=no-usage")) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"Ответ без usage\"}}]}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":"
                    + "{\"role\":\"assistant\",\"content\":\"Ответ "
                    + successCounter.incrementAndGet() + "\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            Map<String, String> env = new java.util.HashMap<>();
            env.put("LLM_DIAGNOSTICS", "true");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env), client, store);

            // Обычный ответ с usage: диагностика без секретов и текстов переписки.
            FakeUi normalUi = new FakeUi(
                    TerminalUi.Input.message("вопрос-секрет-42"),
                    TerminalUi.Input.command("/exit"));
            expect("запрос с диагностикой завершается нормально",
                    Main.runLoop(normalUi, agent, "glm-5.3-flash") == 0);
            String normalText = String.join("\n", normalUi.systems);
            expect("диагностика показывает профиль, лимит и метрики времени",
                    normalText.contains("профиль balanced")
                            && normalText.contains("лимит генерации 2048")
                            && normalText.contains("Диагностика")
                            && normalText.contains("HTTP до полного ответа"));
            expect("диагностика показывает finish_reason и usage из ответа",
                    normalText.contains("finish_reason: stop")
                            && normalText.contains("prompt_tokens: 10")
                            && normalText.contains("completion_tokens: 20")
                            && normalText.contains("total_tokens: 30"));
            expect("диагностика не содержит ключ API и тексты переписки",
                    !normalText.contains("test-key") && !normalText.contains("секрет-42"));

            // Обрезанный по лимиту ответ: показан, с предупреждением, без повторов.
            FakeUi truncatedUi = new FakeUi(
                    TerminalUi.Input.message("case=truncated"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(truncatedUi, agent, "glm-5.3-flash");
            expect("обрезанный ответ показывается пользователю",
                    truncatedUi.messages.contains("частичный ответ"));
            expect("при остановке по лимиту выводится предупреждение",
                    truncatedUi.systems.stream().anyMatch(s -> s.contains("обрезан по лимиту")));
            expect("при обрезанном ответе повторный запрос не выполняется",
                    hitCounter.get() == 2);
            expect("последние метрики фиксируют finish_reason length",
                    agent.getLastDiagnostics() != null && agent.getLastDiagnostics().limitReached());

            // Пустой итоговый ответ при лимите — на отдельном агенте с чистой
            // историей, чтобы маркер предыдущего сценария не попадал в запрос.
            JsonConversationStore emptyStore = tempStore();
            LlmAgent emptyAgent = new LlmAgent(config, ModelSettings.from(env), client, emptyStore);
            expect("пустой ответ при исчерпанном лимите объясняется",
                    expectAgentError(emptyAgent, "case=empty-length").contains("max_tokens"));
            expect("после пустого ответа история в памяти не изменилась",
                    emptyAgent.getHistory().isEmpty());
            expect("после пустого ответа повторный запрос не выполнялся",
                    hitCounter.get() == 3);
            emptyStore.close();

            // Отсутствующий usage — «нет данных», а не ноль (чистая история,
            // чтобы маркер предыдущего сценария не попал в запрос).
            JsonConversationStore noUsageStore = tempStore();
            LlmAgent noUsageAgent = new LlmAgent(config, ModelSettings.from(env), client, noUsageStore);
            FakeUi noUsageUi = new FakeUi(
                    TerminalUi.Input.message("case=no-usage"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(noUsageUi, noUsageAgent, "glm-5.3-flash");
            expect("при отсутствующем usage диагностика показывает «нет данных»",
                    noUsageUi.systems.stream().anyMatch(s -> s.contains("usage: нет данных")));
            noUsageStore.close();
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    // ---------- Команда /mode без вызова API ----------

    private static void checkModeCommand() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(), client, store);
            agent.ask("вопрос перед /mode");
            byte[] fileBeforeMode = Files.readAllBytes(store.file());
            int hitsAfterAsk = hitCounter.get();

            FakeUi modeUi = new FakeUi(
                    TerminalUi.Input.command("/mode"),
                    TerminalUi.Input.command("/mode fast"),
                    TerminalUi.Input.command("/mode turbo"),
                    TerminalUi.Input.command("/history"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(modeUi, agent, "glm-5.3-flash");
            expect("команды /mode не вызывают API", hitCounter.get() == hitsAfterAsk);
            expect("/mode показывает текущий профиль и лимит",
                    modeUi.systems.stream().anyMatch(
                            s -> s.contains("Профиль: balanced · лимит генерации: 2048")));
            expect("профиль переключается на fast с лимитом 1024",
                    modeUi.systems.stream().anyMatch(
                            s -> s.contains("Профиль изменён: fast · лимит генерации: 1024"))
                            && ModelSettings.FAST.equals(agent.currentSettings().profile())
                            && agent.currentSettings().maxOutputTokens() == 1024);
            expect("неизвестный профиль даёт понятную ошибку",
                    modeUi.errors.stream().anyMatch(s -> s.contains("Неизвестный профиль")));
            expect("/mode не трогает файл истории",
                    Arrays.equals(Files.readAllBytes(store.file()), fileBeforeMode));
            expect("/mode не очищает память",
                    agent.getHistory().size() == 2 && modeUi.historyCalls == 1);
            store.close();

            // Явный LLM_MAX_OUTPUT_TOKENS сохраняет приоритет при /mode.
            Map<String, String> env = new java.util.HashMap<>();
            env.put("LLM_MAX_OUTPUT_TOKENS", "300");
            JsonConversationStore overriddenStore = tempStore();
            LlmAgent overriddenAgent = new LlmAgent(
                    config, ModelSettings.from(env), client, overriddenStore);
            FakeUi overrideUi = new FakeUi(
                    TerminalUi.Input.command("/mode"),
                    TerminalUi.Input.command("/mode fast"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(overrideUi, overriddenAgent, "glm-5.3-flash");
            expect("приветствие /mode отмечает переопределение лимита окружением",
                    overrideUi.systems.stream().anyMatch(
                            s -> s.contains("лимит задан LLM_MAX_OUTPUT_TOKENS")));
            expect("при переключении профиля лимит из окружения сохраняется",
                    overriddenAgent.currentSettings().maxOutputTokens() == 300
                            && ModelSettings.FAST.equals(overriddenAgent.currentSettings().profile()));
            overriddenStore.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    // ---------- Интеграционный тест: два последовательных запуска процесса ----------

    private record RunResult(int exitCode, String stdout, String stderr) {
    }

    private static void checkTwoProcessIntegration() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        Path trustStore = createTrustStore(keyStore);
        AtomicInteger successCounter = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            bodies.add(requestBody);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ " + successCounter.incrementAndGet() + "\"}}]}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            String url = "https://127.0.0.1:" + server.getAddress().getPort()
                    + "/v1/chat/completions";
            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = buildClasspath();
            Path historyFile = Files.createTempDirectory(baseTempDir, "process-")
                    .resolve("conversation.json");

            // Первый запуск: ответ и пара user/assistant сохраняются на диск.
            String question = "Запомни кодовое слово: ЯКОРЬ-42. Ответь одним словом.";
            RunResult run1 = runAgentProcess(javaBin, classpath, trustStore, url, historyFile,
                    List.of(question, "/exit"));
            expect("первый запуск процесса завершился успешно"
                            + (run1.exitCode() == 0 ? "" : " — stderr: " + run1.stderr()),
                    run1.exitCode() == 0);
            expect("первый запуск сообщает о новой беседе",
                    run1.stderr().contains("Начата новая беседа."));
            expect("первый запуск получил ответ от локального сервера",
                    run1.stdout().contains("Ответ 1"));

            // Второй запуск: контекст восстановлен и уходит в API.
            String followUp = "Какое кодовое слово я просил запомнить?";
            RunResult run2 = runAgentProcess(javaBin, classpath, trustStore, url, historyFile,
                    List.of(followUp, "/exit"));
            expect("второй запуск процесса завершился успешно", run2.exitCode() == 0);
            expect("второй запуск сообщает о восстановлении контекста",
                    run2.stderr().contains("Контекст восстановлен: 1 завершённых обменов."));
            expect("второй запуск получил ответ", run2.stdout().contains("Ответ 2"));

            expect("второй процесс отправил ровно один запрос к API", bodies.size() == 2);
            JsonNode secondRequest = MAPPER.readTree(bodies.get(1));
            JsonNode messages = secondRequest.path("messages");
            expect("второй запуск отправил восстановленную пару в API",
                    messages.size() == 4
                            && "system".equals(messages.get(0).path("role").asText())
                            && question.equals(messages.get(1).path("content").asText())
                            && "Ответ 1".equals(messages.get(2).path("content").asText())
                            && "user".equals(messages.get(3).path("role").asText())
                            && followUp.equals(messages.get(3).path("content").asText()));
            expect("история процесса не содержит ключ API",
                    !Files.readString(historyFile, StandardCharsets.UTF_8).contains("test-key"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
            Files.deleteIfExists(trustStore);
        }
    }

    /**
     * Classpath для дочернего процесса. Под exec:java свойство java.class.path
     * указывает на загрузчик Maven, поэтому классы проекта и зависимости
     * определяются по фактическим code source загруженных классов.
     */
    private static String buildClasspath() throws Exception {
        List<String> entries = new ArrayList<>();
        addCodeSource(entries, SelfTest.class);                          // target/test-classes
        addCodeSource(entries, Main.class);                              // target/classes
        addCodeSource(entries, com.fasterxml.jackson.databind.ObjectMapper.class);
        addCodeSource(entries, com.fasterxml.jackson.core.JsonFactory.class);
        addCodeSource(entries, com.fasterxml.jackson.annotation.JsonValue.class);
        addCodeSource(entries, org.jline.terminal.Terminal.class);
        StringBuilder classpath = new StringBuilder();
        for (String entry : entries) {
            if (classpath.length() > 0) {
                classpath.append(File.pathSeparator);
            }
            classpath.append(entry);
        }
        return classpath.toString();
    }

    private static void addCodeSource(List<String> entries, Class<?> type) throws Exception {
        var source = type.getProtectionDomain().getCodeSource();
        if (source != null && source.getLocation() != null) {
            entries.add(Path.of(source.getLocation().toURI()).toString());
        }
    }

    /** Запускает com.example.Main отдельным процессом против локального сервера. */
    private static RunResult runAgentProcess(String javaBin, String classpath, Path trustStore,
                                             String apiUrl, Path historyFile,
                                             List<String> inputLines) throws Exception {
        Path stdin = Files.createTempFile(baseTempDir, "stdin-", ".txt");
        Files.write(stdin, (String.join("\n", inputLines) + "\n")
                .getBytes(StandardCharsets.UTF_8));
        Path stdout = Files.createTempFile(baseTempDir, "stdout-", ".txt");
        Path stderr = Files.createTempFile(baseTempDir, "stderr-", ".txt");
        ProcessBuilder processBuilder = new ProcessBuilder(
                javaBin, "-cp", classpath,
                "-Djavax.net.ssl.trustStore=" + trustStore.toAbsolutePath(),
                "-Djavax.net.ssl.trustStorePassword=changeit",
                "-Djavax.net.ssl.trustStoreType=PKCS12",
                "com.example.Main");
        processBuilder.environment().put("LLM_API_KEY", "test-key");
        processBuilder.environment().put("LLM_API_URL", apiUrl);
        processBuilder.environment().put("LLM_MODEL", "glm-5.3-flash");
        processBuilder.environment().put("LLM_HISTORY_FILE",
                historyFile.toAbsolutePath().toString());
        processBuilder.redirectInput(stdin.toFile());
        processBuilder.redirectOutput(stdout.toFile());
        processBuilder.redirectError(stderr.toFile());
        Process process = processBuilder.start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Запуск процесса не завершился за 120 секунд");
        }
        return new RunResult(process.exitValue(),
                Files.readString(stdout, StandardCharsets.UTF_8),
                Files.readString(stderr, StandardCharsets.UTF_8));
    }

    /** Экспортирует сертификат тестового сервера в отдельное доверенное хранилище. */
    private static Path createTrustStore(Path keyStorePath) throws IOException, InterruptedException {
        Path certificate = Files.createTempFile(baseTempDir, "selftest-cert", ".pem");
        Files.deleteIfExists(certificate);
        Path trustStore = Files.createTempFile(baseTempDir, "selftest-truststore", ".p12");
        Files.deleteIfExists(trustStore);
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process export = new ProcessBuilder(keytool, "-exportcert", "-alias", "selftest",
                "-keystore", keyStorePath.toString(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-rfc", "-file", certificate.toString(), "-noprompt")
                .inheritIO().start();
        if (export.waitFor() != 0) {
            throw new IllegalStateException("Не удалось экспортировать тестовый сертификат.");
        }
        Process importCert = new ProcessBuilder(keytool, "-importcert", "-alias", "selftest",
                "-file", certificate.toString(), "-keystore", trustStore.toString(),
                "-storetype", "PKCS12", "-storepass", "changeit", "-noprompt")
                .inheritIO().start();
        if (importCert.waitFor() != 0) {
            throw new IllegalStateException("Не удалось создать доверенное хранилище тестов.");
        }
        Files.deleteIfExists(certificate);
        return trustStore;
    }

    // ---------- Сервисные методы тестового сервера ----------

    /** Генерирует временное PKCS12-хранилище с самоподписанным сертификатом. */
    private static Path createSelfSignedKeyStore() throws IOException, InterruptedException {
        Path keyStore = Files.createTempFile("selftest-keystore", ".p12");
        // keytool не перезаписывает существующее (пустое) хранилище — убираем файл.
        Files.deleteIfExists(keyStore);
        ProcessBuilder pb = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "selftest", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-dname", "CN=localhost",
                "-ext", "san=ip:127.0.0.1",
                "-keystore", keyStore.toString(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-noprompt");
        if (pb.inheritIO().start().waitFor() != 0) {
            throw new IllegalStateException("Не удалось создать тестовый сертификат через keytool.");
        }
        return keyStore;
    }

    private static HttpsServer startHttpsServer(Path keyStorePath, Handler handler) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStorePath)) {
            keyStore.load(in, KEYSTORE_PASSWORD);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, KEYSTORE_PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/v1/chat/completions", exchange -> {
            // Тело читаем один раз и используем и для захвата, и для выбора сценария.
            String requestBody =
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Response response = handler.handle(requestBody,
                    exchange.getRequestHeaders().getFirst("x-opencode-session"),
                    exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), response.body().length);
            try (var out = exchange.getResponseBody()) {
                out.write(response.body());
            }
        });
        server.start();
        return server;
    }

    /** HttpClient, доверяющий тестовому самоподписанному сертификату. */
    private static HttpClient trustedHttpClient(Path keyStorePath) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStorePath)) {
            keyStore.load(in, KEYSTORE_PASSWORD);
        }
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder()
                .sslContext(clientContext)
                // JDK HttpsServer поддерживает только HTTP/1.1.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** Простая пара «статус — тело ответа» тестового сервера. */
    private record Response(int status, byte[] body) {
    }

    @FunctionalInterface
    private interface Handler {
        Response handle(String requestBody, String sessionHeader, String authHeader);
    }

    // ---------- Вспомогательные проверки ----------

    private static boolean historyEquals(List<ChatMessage> actual, ChatMessage... expected) {
        return actual.equals(List.of(expected));
    }

    private static boolean historyEquals(List<ChatMessage> actual, List<ChatMessage> expected) {
        return actual.equals(expected);
    }

    private static boolean rolesAlternate(List<ChatMessage> history) {
        for (int i = 0; i < history.size(); i++) {
            String expectedRole = (i % 2 == 0) ? "user" : "assistant";
            if (!expectedRole.equals(history.get(i).role())) {
                return false;
            }
        }
        return true;
    }

    private static java.util.stream.Stream<String> streamContents(JsonNode messages) {
        List<String> contents = new ArrayList<>();
        messages.forEach(m -> contents.add(m.path("content").asText()));
        return contents.stream();
    }

    private static String expectAgentError(LlmAgent agent, String marker) {
        try {
            agent.ask(marker);
        } catch (AgentException e) {
            return e.getMessage();
        }
        return "";
    }

    private static void expect(String description, boolean condition) {
        if (!condition) {
            throw new AssertionError("Проверка не пройдена: " + description);
        }
        passed++;
        System.out.println("OK: " + description);
    }

    private SelfTest() {
    }
}
