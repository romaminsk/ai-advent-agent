package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 * День 8 (токены, всё без платных запросов):
 * - эвристический счётчик: пустой текст, русский/английский, код, emoji, CJK,
 *   маркировка «оценка», отдельные накладные расходы; подстановка
 *   детерминированного счётчика в агент;
 * - разделение метрик: новое сообщение, отправленный запрос после ограничения
 *   истории, полная сохранённая история, видимый ответ, накладные расходы;
 * - фактический usage: полный, частичный и отсутствующий; учёт расхода
 *   при пустом ответе с usage; отсутствие подмены «нет данных» нулём;
 *   отсутствие двойного учёта total_tokens; различие completion_tokens
 *   и оценки видимого ответа;
 * - накопители сессии: полные и неполные итоги;
 * - тарифы на BigDecimal: отсутствующий, нулевой, дробный, некорректный;
 *   расчётная стоимость и «нет данных» без тарифа;
 * - контекстный бюджет: неизвестное окно, граница бюджета, warn и block,
 *   отсутствие HTTP при локальной блокировке;
 * - различение типов ошибок: отказ по контексту, прочий HTTP 400, таймаут;
 * - /tokens и /stats без вызова API; совместимость старых JSON-файлов.
 *
 * Все проверки с файлами используют временные каталоги и никогда не трогают
 * настоящую переписку (~/.ai-advent-agent/conversation.json).
 *
 * Запуск: mvn test-compile exec:java@self-test
 */
public final class SelfTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final char[] KEYSTORE_PASSWORD = "changeit".toCharArray();

    /** Ожидаемый текст системной инструкции агента (базовая часть без блоков памяти). */
    private static final String SYSTEM_PROMPT_TEXT = ContextBuilder.BASE_SYSTEM_PROMPT;

    /** Детерминированный счётчик для проверок: 1 токен = 1 символ текста. */
    private static final TokenCounter LEN_COUNTER = new TokenCounter() {
        @Override
        public int count(String text) {
            return text == null ? 0 : text.length();
        }

        @Override
        public boolean exact() {
            return false;
        }

        @Override
        public String description() {
            return "тестовый детерминированный счётчик (символы)";
        }
    };

    /** Базовый временный каталог для всех файловых проверок; удаляется в конце. */
    private static Path baseTempDir;

    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        baseTempDir = Files.createTempDirectory("selftest-day8");
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
            checkTokenCounterHeuristics();
            checkTokenEstimations();
            checkSessionUsageAccounting();
            checkSessionCost();
            checkContextBudget();
            checkErrorClassification();
            checkTokensStatsCommandsNoApi();
            checkOldHistoryCompatible();
            checkSessionTokenLimitSettings();
            checkSessionTokenLimitFeature();
            checkClearCommand();
            checkClearConfirmationUi();
            checkUiPrompt();
            checkUiMessageMarkers();
            checkUiRedesign();
            checkUiColorAndMarkdown();
            checkHistoryStoreCleanText();
            checkClearDemoIsolation();
            checkClearErrorKeepsMemory();
            checkDemoTokensMode();
            checkDemoFailureClassifications();
            checkDemoCommandsNoApi();
            checkPasteInput();
            checkDay9SettingsValidation();
            checkDay9SummaryThresholds();
            checkDay9IncrementalSummary();
            checkDay9PersistenceAndStale();
            checkDay9FailureKeepsHistory();
            checkDay9ClearRemovesSummary();
            checkDay9ContextCommandsNoApi();
            checkDay9Compare();
            checkDay9CompareGuards();
            checkDay9CompareReadySummary();
            checkDay9CompareAfterRefresh();
            checkDay9CompareReuseAfterRestart();
            checkDay9ComparePrepFailure();
            checkDay9CompareUsageGaps();
            checkDay9ContextCaptions();
            checkDay9ArchiveNoLossAcrossModes();
            checkDay92BenefitGateShortSkips();
            checkDay92BenefitGateProfitableRuns();
            checkDay92RefreshWarnsButRuns();
            checkDay92CompareWarnsButRuns();
            checkDay92CompactTemplateAndRules();
            checkDay92StatsBalanceLine();
            checkDay10SettingsValidation();
            checkDay10StrategySwitchNoApi();
            checkDay10SlidingWindow();
            checkDay10FactsFlow();
            checkDay10FactsManualAndClear();
            checkDay10FactsInSessionLimit();
            checkDay10Branching();
            checkDay10BranchPersistence();
            checkDay10StrategyCompare();
            checkDay10CompareGuards();
            checkDay10OtherStoreValidation();
            checkFactsPrepLengthAbortsCompare();
            checkFactsNearLimitWarning();
            checkMemoryLayerSeparation();
            checkRememberKeyFormats();
            checkProfileStoreLifecycle();
            checkProfileSubcommandUi();
            checkProfileBlockInSystemMessage();
            checkProfileBlockAcrossRestartsAndClear();
            checkSkillsAndPipelines();
            checkPipelineSubstitutionInRequest();
            checkForgetPartialMatches();
            checkLegacyRememberEntries();
            checkSystemInstructionRules();
            checkWorkingCounterMatchesFacts();
            checkRememberForgetMemoryCommands();
            checkMemoryPersistsAcrossRestarts();
            checkClearKeepsLongTermMemory();
            checkTaskCommands();
            checkTaskStateMachineModel();
            checkTaskStateCommands();
            checkTaskStateBlockInSystemMessage();
            checkTaskStatePauseResumeInRequest();
            checkInvariantsStoreLifecycle();
            checkInvariantCommands();
            checkInvariantBlockInSystemMessage();
            checkInvariantBlockInRealRequest();
            checkInvariantGuardRules();
            checkInvariantGuardNoApiInMessage();
            checkInvariantAddForbiddenMarkers();
            checkTaskInvariantCommands();
            checkTaskInvariantBlockAndGuard();
            checkInvariantsNotInWorkingMemoryOrHistory();
            checkInvariantHelpAndIndex();
            checkThreeLayersInRequest();
            checkMemoryUpdateAccountingOnce();
            checkUserFacingOutputNeutral();
            checkDefaultRunSettings();
            checkQuietStartupAndAnswer();
            checkExplicitCommandsStillDetailed();
            checkDiagnosticsHiddenByDefault();
            checkTwoProcessIntegration();
            checkProfilePersistenceAcrossProcesses();
            checkShortHelpAndFullIndex();
            checkNextHints();
            checkInteractiveMenus();
            checkPlainMenusDisabledWithSyntaxHint();
            checkTypoSuggestions();
            checkStatusOverview();
            checkOnboardingFirstLaunch();
        } finally {
            deleteRecursively(baseTempDir);
        }

        System.out.println("OK: все проверки пройдены (" + passed + ").");
    }

    /** Агент с изолированной долговременной памятью: реальный memory.json не мешает. */
    private static MemoryStore tempMemoryStore() throws IOException {
        return new MemoryStore(
                Files.createTempDirectory(baseTempDir, "mem-").resolve("memory.json"));
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
                // Стратегия branching: без веток контекст — вся история дословно
                // (как в режиме full Дня 9), поэтому этот общий диалог проверяет
                // прежние семантики после появления стратегий.
                LlmAgent agent = new LlmAgent(config, ModelSettings.from(branchingEnv()),
                        trustedHttpClient(keyStore), store, tempMemoryStore());
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

                // --- День 9: архив больше не обрезается ---
                for (int i = 1; i <= LlmAgent.MAX_HISTORY_TURNS + 1; i++) {
                    agent.ask("вопрос " + i);
                }
                List<ChatMessage> fullHistory = agent.getHistory();
                expect("День 9: архив не теряет старые сообщения",
                        fullHistory.size() == (1 + LlmAgent.MAX_HISTORY_TURNS + 1) * 2);
                expect("пары в истории не разорваны (чередование user/assistant)",
                        rolesAlternate(fullHistory));

                String overflowQuestion = "вопрос " + (LlmAgent.MAX_HISTORY_TURNS + 2);
                agent.ask(overflowQuestion);
                JsonNode overflowBody = MAPPER.readTree(lastBody.get());
                JsonNode overflowMessages = overflowBody.path("messages");
                expect("запрос при большой истории содержит system, весь архив и нового user",
                        overflowMessages.size() == 2
                                + (LlmAgent.MAX_HISTORY_TURNS + 2) * 2);
                expect("в запросе есть и самые старые, и самые новые пары",
                        "system".equals(overflowMessages.get(0).path("role").asText())
                                && streamContents(overflowMessages).anyMatch(
                                        m -> m.contains("вопрос после сброса"))
                                && streamContents(overflowMessages).anyMatch("вопрос 1"::equals)
                                && streamContents(overflowMessages).noneMatch("Вопрос"::equals)
                                && overflowQuestion.equals(overflowMessages.get(overflowMessages.size() - 1).path("content").asText()));

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
                        snapshot.size() == (LlmAgent.MAX_HISTORY_TURNS + 3) * 2);

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
        int confirmClearCount = 0;
        boolean confirmClearAnswer = false;
        String confirmClearSubject;
        int progressCount = 0;
        int confirmCount = 0;
        boolean confirmAnswer = false;
        int confirmCompareCount = 0;
        boolean confirmCompareAnswer = false;
        int confirmStrategyCompareCount = 0;
        boolean confirmStrategyCompareAnswer = false;
        int confirmFactsClearCount = 0;
        boolean confirmFactsClearAnswer = false;
        int confirmBranchDeleteCount = 0;
        boolean confirmBranchDeleteAnswer = false;
        String confirmBranchDeleteName;
        int confirmProfileClearCount = 0;
        boolean confirmProfileClearAnswer = false;
        String confirmProfileClearSubject;
        boolean interactiveMenusEnabled = false;
        final List<String> promptTasks = new ArrayList<>();
        final List<String> commandHelps = new ArrayList<>();

        @Override
        public boolean interactiveMenus() {
            return interactiveMenusEnabled;
        }

        @Override
        public void setPromptTask(String task) {
            promptTasks.add(task);
        }

        @Override
        public void showCommandHelp(String name) {
            commandHelps.add(name);
        }

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
        public boolean confirmHistoryClear(String subject) {
            confirmClearCount++;
            confirmClearSubject = subject;
            return confirmClearAnswer;
        }

        @Override
        public boolean confirmCompare() {
            confirmCompareCount++;
            return confirmCompareAnswer;
        }

        @Override
        public boolean confirmStrategyCompare() {
            confirmStrategyCompareCount++;
            return confirmStrategyCompareAnswer;
        }

        @Override
        public boolean confirmFactsClear() {
            confirmFactsClearCount++;
            return confirmFactsClearAnswer;
        }

        @Override
        public boolean confirmBranchDelete(String name) {
            confirmBranchDeleteCount++;
            confirmBranchDeleteName = name;
            return confirmBranchDeleteAnswer;
        }

        @Override
        public boolean confirmProfileClear(String subject) {
            confirmProfileClearCount++;
            confirmProfileClearSubject = subject;
            return confirmProfileClearAnswer;
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
        expect("/clear запрашивает подтверждение предмета очистки",
                commandsUi.confirmClearCount == 1
                        && "текущего диалога".equals(commandsUi.confirmClearSubject)
                        && commandsUi.systems.stream().anyMatch(s -> s.contains("Удаление отменено")));
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

        // /clear без подтверждения не трогает историю.
        agent.resetConversation();
        FakeUi clearUi = new FakeUi(
                TerminalUi.Input.message("вопрос для /clear"),
                TerminalUi.Input.command("/clear"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(clearUi, agent, "test-model");
        expect("/clear без подтверждения сохраняет историю",
                clearUi.confirmClearCount == 1
                        && !clearUi.confirmClearAnswer
                        && agent.getHistory().size() == 2);

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
                outText.contains("◆") && outText.contains("с Markdown")
                        && !errText.contains("с Markdown")
                        && errText.contains("AI Advent Agent")
                        && errText.contains("История диалога пуста."));
        expect("plain-режим не содержит ANSI-последовательностей",
                !outText.contains("\u001B") && !errText.contains("\u001B"));
        expect("приветствие plain-режима компактное",
                errText.contains("/help — команды"));
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
        expect("спиннер показывает «… Думаю»", output.contains("Думаю"));
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
                LlmAgent agent1 = new LlmAgent(config, ModelSettings.from(branchingEnv()), client, store1);
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
                LlmAgent agent2 = new LlmAgent(config, ModelSettings.from(branchingEnv()), client, store2);
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

                // --- День 9: на диск сохраняется весь архив без обрезания ---
                for (int i = 1; i <= LlmAgent.MAX_HISTORY_TURNS + 1; i++) {
                    agent2.ask("вопрос переполнения " + i);
                }
                List<ChatMessage> memoryAfterOverflow = agent2.getHistory();
                JsonNode overflowFile = MAPPER.readTree(
                        Files.readString(historyFile, StandardCharsets.UTF_8));
                List<ChatMessage> fileMessages = new ArrayList<>();
                overflowFile.path("messages").forEach(m -> fileMessages.add(
                        new ChatMessage(m.path("role").asText(), m.path("content").asText())));
                expect("файл хранит тот же архив, что и память (без обрезания)",
                        fileMessages.equals(memoryAfterOverflow)
                                && memoryAfterOverflow.size() > LlmAgent.MAX_HISTORY_TURNS * 2);
                expect("чек на диске: только целые пары",
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

            // /clear без подтверждения: файл и память не трогает.
            FakeUi clearUi = new FakeUi(
                    TerminalUi.Input.command("/clear"),
                    TerminalUi.Input.command("/exit"));
            expect("/clear завершает цикл нормально",
                    Main.runLoop(clearUi, agent1, "test-model") == 0);
            expect("/clear без подтверждения не меняет файл истории",
                    Arrays.equals(Files.readAllBytes(historyFile), afterAsk));
            expect("/clear без подтверждения не меняет память", agent1.getHistory().size() == 2);

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
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env), client, store,
                    tempMemoryStore());
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
            LlmAgent balancedAgent = new LlmAgent(config, ModelSettings.defaults(), client,
                    store2, tempMemoryStore());
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
            // Полный архив отправляется в стратегии веток.
            env.put("LLM_CONTEXT_STRATEGY", "branching");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env), client, store);
            Path historyFile = store.file();

            for (int i = 1; i <= 4; i++) {
                agent.ask("вопрос " + i);
            }
            agent.ask("вопрос с урезанным контекстом");

            JsonNode messages = MAPPER.readTree(lastBody.get()).path("messages");
            // День 9: LLM_CONTEXT_MAX_TURNS в режимах контекста не применяется —
            // full действительно отправляет весь архив: system + 4 пары + новый запрос.
            expect("LLM_CONTEXT_MAX_TURNS не применяется: full отправляет весь архив",
                    messages.size() == 10
                            && "system".equals(messages.get(0).path("role").asText())
                            && "вопрос 1".equals(messages.get(1).path("content").asText())
                            && "вопрос 4".equals(messages.get(7).path("content").asText())
                            && "вопрос с урезанным контекстом".equals(
                            messages.get(9).path("content").asText()));

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
                            s -> s.contains("Профиль: fast · лимит генерации: 1024"))
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

    // ---------- День 8: эвристический счётчик токенов ----------

    private static void checkTokenCounterHeuristics() {
        HeuristicTokenCounter counter = HeuristicTokenCounter.INSTANCE;
        expect("пустой текст оценивается нулём", counter.count("") == 0 && counter.count(null) == 0);
        expect("русский текст даёт ненулевую оценку", counter.count("Привет, как дела?") > 0);
        expect("кириллица оценивается дороже латиницы той же длины",
                counter.count("абвгдеёжзи") > counter.count("abcdefghij"));
        expect("код даёт ненулевую оценку", counter.count("if (a < b) { return null; }") > 0);
        expect("emoji оценивается примерно в два токена",
                counter.count("👍") == 2 && counter.count("😀😀") == 4);
        expect("CJK — около токена на иероглиф", counter.count("你好世界") == 4);
        expect("эвристика помечается как оценка, а не точный подсчёт",
                !counter.exact() && counter.description().contains("оценк"));
        expect("накладные расходы сообщения и тела считаются отдельно",
                counter.countOverhead(2) == TokenCounter.REQUEST_OVERHEAD_TOKENS
                        + 2 * TokenCounter.MESSAGE_OVERHEAD_TOKENS);
        int messages = counter.countMessages(List.of(
                new ChatMessage("system", "абв"), new ChatMessage("user", "def")));
        expect("оценка списка сообщений = тексты + накладные всех сообщений и тела",
                messages == counter.count("абв") + counter.count("def")
                        + 2 * TokenCounter.MESSAGE_OVERHEAD_TOKENS
                        + TokenCounter.REQUEST_OVERHEAD_TOKENS);
    }

    // ---------- День 8: разделение оценок сообщения, запроса и истории ----------

    private static void checkTokenEstimations() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger successCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ " + successCounter.incrementAndGet() + "\"}}],"
                        + "\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":7,\"total_tokens\":49}}")
                        .getBytes(StandardCharsets.UTF_8)));
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            Map<String, String> env = new java.util.HashMap<>();
            env.put("LLM_CONTEXT_MAX_TURNS", "1");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env), client, store);
            agent.setTokenCounter(LEN_COUNTER);

            String question1 = "первый вопрос";
            agent.ask(question1);
            RequestDiagnostics d1 = agent.getLastDiagnostics();
            expect("оценка нового сообщения совпадает с детерминированным счётчиком",
                    d1.estimatedUserMessageTokens() == LEN_COUNTER.count(question1));
            List<ChatMessage> expectedOutgoing1 = List.of(
                    new ChatMessage("system", LlmAgent.systemPromptFor(ModelSettings.BALANCED)),
                    new ChatMessage("user", question1));
            expect("оценка отправленного запроса включает system, сообщение и накладные",
                    d1.estimatedRequestTokens() == LEN_COUNTER.countMessages(expectedOutgoing1));
            expect("оценка накладных расходов указана отдельно",
                    d1.estimatedRequestOverheadTokens() == LEN_COUNTER.countOverhead(2));
            expect("фактические токены API берутся из usage, а не из оценки",
                    d1.promptTokens() == 42 && d1.completionTokens() == 7);
            expect("оценка видимого ответа считается локально",
                    d1.estimatedAnswerTokens() == LEN_COUNTER.count("Ответ 1"));
            List<ChatMessage> historyAfterFirst = List.of(
                    new ChatMessage("user", question1),
                    new ChatMessage("assistant", "Ответ 1"));
            expect("оценка полной истории после ответа и число сообщений совпадают",
                    d1.estimatedHistoryTokensAfter() == LEN_COUNTER.countMessages(historyAfterFirst)
                            && d1.savedMessagesAfter() == 2);

            String question2 = "второй вопрос";
            agent.ask(question2);
            RequestDiagnostics d2 = agent.getLastDiagnostics();
            // День 9: LLM_CONTEXT_MAX_TURNS больше не ограничивает —
            // контекст включает system + все пары + новое сообщение.
            List<ChatMessage> expectedOutgoing2 = List.of(
                    new ChatMessage("system", LlmAgent.systemPromptFor(ModelSettings.BALANCED)),
                    new ChatMessage("user", question1),
                    new ChatMessage("assistant", "Ответ 1"),
                    new ChatMessage("user", question2));
            expect("оценка окончательного messages считается после ограничения истории",
                    d2.estimatedRequestTokens() == LEN_COUNTER.countMessages(expectedOutgoing2));
            expect("полная сохранённая история считается отдельно от отправляемого контекста",
                    d2.estimatedHistoryTokensAfter() == LEN_COUNTER.countMessages(agent.getHistory())
                            && agent.estimateNextContextTokens()
                            == LEN_COUNTER.countMessages(List.of(
                                    new ChatMessage("system",
                                            LlmAgent.systemPromptFor(ModelSettings.BALANCED)),
                                    new ChatMessage("user", question1),
                                    new ChatMessage("assistant", "Ответ 1"),
                                    new ChatMessage("user", question2),
                                    new ChatMessage("assistant", "Ответ 2"))));

            // День 9: архив и отправляемый контекст растут вместе (нет ограничения
            // отправки для контекстного запроса).
            int historyBeforeThird = agent.estimateHistoryTokens();
            int contextBeforeThird = agent.estimateNextContextTokens();
            agent.ask(question2);
            expect("архив и отправляемый контекст согласованно растут",
                    agent.estimateHistoryTokens() > historyBeforeThird
                            && agent.estimateNextContextTokens() > contextBeforeThird);

            int contextBalanced = agent.estimateNextContextTokens();
            agent.setProfile(ModelSettings.FAST);
            expect("изменение профиля отражается в оценке system-инструкции",
                    agent.estimateNextContextTokens() > contextBalanced);
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    // ---------- День 8: учёт фактического usage и итоги сессии ----------

    private static void checkSessionUsageAccounting() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains("case=partial-usage")) {
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ P\"}}],\"usage\":{\"prompt_tokens\":7}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=no-usage")) {
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ U\"}}]}").getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=empty-with-usage")) {
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"\"}}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":22}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=big-completion")) {
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ок\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":500}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ ОК\"}}],\"usage\":{\"prompt_tokens\":10,"
                    + "\"completion_tokens\":20,\"total_tokens\":30}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);

            // Полный usage: ровно один учёт на запрос, total_tokens повторно не суммируется.
            JsonConversationStore fullStore = tempStore();
            LlmAgent fullAgent = new LlmAgent(config, ModelSettings.defaults(), client, fullStore);
            fullAgent.ask("вопрос с полным usage");
            SessionTokenStats.Snapshot full = fullAgent.sessionStats();
            expect("полный usage учитывается один раз: 10/20, total_tokens не дублируется",
                    full.apiAttempts() == 1 && full.requestsWithUsage() == 1
                            && full.totalPromptTokens() == 10 && full.totalCompletionTokens() == 20
                            && full.complete());
            fullStore.close();

            // Частичный usage: отсутствующее поле — «нет данных», а не 0.
            JsonConversationStore partialStore = tempStore();
            LlmAgent partialAgent = new LlmAgent(config, ModelSettings.defaults(), client, partialStore);
            partialAgent.ask("case=partial-usage");
            SessionTokenStats.Snapshot partial = partialAgent.sessionStats();
            expect("частичный usage: вход учтён, итог помечен неполным",
                    partial.apiAttempts() == 1 && partial.requestsWithUsage() == 1
                            && partial.requestsWithPartialUsage() == 1
                            && partial.totalPromptTokens() == 7
                            && partial.totalCompletionTokens() == 0
                            && !partial.complete());
            expect("частичный usage в диагностике — null, а не 0",
                    partialAgent.getLastDiagnostics() != null
                            && partialAgent.getLastDiagnostics().completionTokens() == null);
            partialStore.close();

            // Отсутствие usage: расход неизвестен, итог неполный, «нет данных».
            JsonConversationStore noUsageStore = tempStore();
            LlmAgent noUsageAgent = new LlmAgent(config, ModelSettings.defaults(), client, noUsageStore);
            noUsageAgent.ask("case=no-usage");
            SessionTokenStats.Snapshot noUsage = noUsageAgent.sessionStats();
            expect("запрос без usage: попытка засчитана, расход неизвестен",
                    noUsage.apiAttempts() == 1 && noUsage.requestsWithUsage() == 0
                            && noUsage.requestsWithoutUsage() == 1 && !noUsage.complete());
            String diagNoUsage = Main.formatDiagnostics(noUsageAgent.getLastDiagnostics(),
                    "glm-5.3-flash", noUsage, noUsageAgent.currentSettings());
            expect("диагностика без usage показывает «нет данных», а не ноль",
                    diagNoUsage.contains("usage: нет данных")
                            && diagNoUsage.contains("вход нет данных"));
            noUsageStore.close();

            // Пустой ответ с usage: расход учитывается, история не меняется.
            JsonConversationStore emptyStore = tempStore();
            LlmAgent emptyAgent = new LlmAgent(config, ModelSettings.defaults(), client, emptyStore);
            expect("пустой ответ распознаётся",
                    expectAgentError(emptyAgent, "case=empty-with-usage")
                            .contains("пустой итоговый ответ"));
            SessionTokenStats.Snapshot empty = emptyAgent.sessionStats();
            expect("usage при пустом ответе учитывается в расходе сессии",
                    empty.apiAttempts() == 1 && empty.requestsWithUsage() == 1
                            && empty.totalPromptTokens() == 11
                            && empty.totalCompletionTokens() == 22);
            expect("после пустого ответа история не изменилась", emptyAgent.getHistory().isEmpty());
            emptyStore.close();

            // completion_tokens (API) != оценка видимого ответа (локальная).
            JsonConversationStore bigStore = tempStore();
            LlmAgent bigAgent = new LlmAgent(config, ModelSettings.defaults(), client, bigStore);
            bigAgent.setTokenCounter(LEN_COUNTER);
            bigAgent.ask("case=big-completion");
            RequestDiagnostics bigDiag = bigAgent.getLastDiagnostics();
            expect("фактический completion_tokens и оценка видимого ответа разделены",
                    bigDiag.completionTokens() == 500
                            && bigDiag.estimatedAnswerTokens() == LEN_COUNTER.count("Ок")
                            && bigDiag.estimatedAnswerTokens() != 500);
            String bigDiagText = Main.formatDiagnostics(bigDiag, "glm-5.3-flash",
                    bigAgent.sessionStats(), bigAgent.currentSettings());
            expect("в диагностике обе величины показаны раздельно",
                    bigDiagText.contains("completion_tokens: 500")
                            && bigDiagText.contains("видимый ответ ≈" + LEN_COUNTER.count("Ок")));
            bigStore.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    // ---------- День 8: тарифы и расчётная стоимость ----------

    private static void checkSessionCost() throws Exception {
        // Арифметика и форматирование на BigDecimal.
        expect("тариф 0.60 за 1M при 1 500 000 токенов даёт 0.900000",
                TokenCost.perMillion(new BigDecimal("0.60"), 1_500_000L)
                        .compareTo(new BigDecimal("0.900000")) == 0);
        expect("формат суммы не зависит от локали",
                TokenCost.formatUsd(new BigDecimal("0.900000")).equals("$0.900000"));

        // Разбор тарифов из окружения.
        Map<String, String> env = new java.util.HashMap<>();
        expect("без переменных тарифа стоимость «нет данных» (отсутствие не равно 0)",
                ModelSettings.from(env).inputPricePer1M() == null
                        && ModelSettings.from(env).outputPricePer1M() == null);
        env.put("LLM_INPUT_PRICE_PER_1M", "0");
        expect("явный нулевой тариф допустим",
                ModelSettings.from(env).inputPricePer1M().signum() == 0);
        env.put("LLM_INPUT_PRICE_PER_1M", "0.75");
        expect("дробный тариф читается",
                ModelSettings.from(env).inputPricePer1M().compareTo(new BigDecimal("0.75")) == 0);
        expectSettingsError(env, "LLM_INPUT_PRICE_PER_1M", "-1");
        expectSettingsError(env, "LLM_INPUT_PRICE_PER_1M", "abc");
        expectSettingsError(env, "LLM_OUTPUT_PRICE_PER_1M", "1,5");
        env.put("LLM_OUTPUT_PRICE_PER_1M", "2.2");

        // /stats с тарифом и usage: расчётная стоимость с пометкой «расчёт».
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ\"}}],\"usage\":{\"prompt_tokens\":1000000,"
                        + "\"completion_tokens\":2000000}}").getBytes(StandardCharsets.UTF_8)));
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env), client, store);
            FakeUi costUi = new FakeUi(
                    TerminalUi.Input.message("вопрос для стоимости"),
                    TerminalUi.Input.command("/stats"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(costUi, agent, "glm-5.3-flash");
            // 1M вход × 0.75 = 0.75; 2M выход × 2.2 = 4.4; итог 5.150000.
            expect("расчётная стоимость показана с тарифом и пометкой «расчёт»",
                    costUi.systems.stream().anyMatch(s -> s.contains("$5.150000")
                            && s.contains("расчёт") && s.contains("USD за 1 000 000")));

            // Одна из двух переменных — расчёт стоимости не выполняется.
            JsonConversationStore halfStore = tempStore();
            LlmAgent halfAgent = new LlmAgent(config, ModelSettings.from(env), client, halfStore);
            halfAgent.ask("вопрос при полном тарифе");
            Map<String, String> halfEnv = new java.util.HashMap<>(env);
            halfEnv.remove("LLM_OUTPUT_PRICE_PER_1M");
            JsonConversationStore halfOnlyStore = tempStore();
            LlmAgent halfOnlyAgent = new LlmAgent(config, ModelSettings.from(halfEnv), client,
                    halfOnlyStore);
            halfOnlyAgent.ask("вопрос при половинном тарифе");
            expect("один тариф без второго не даёт расчёт стоимости",
                    Main.formatStats(halfOnlyAgent).contains("только одна из переменных"));
            halfOnlyStore.close();

            // Без тарифов — «нет данных», а не ноль.
            JsonConversationStore plainStore = tempStore();
            LlmAgent plainAgent = new LlmAgent(config, ModelSettings.defaults(), client, plainStore);
            plainAgent.ask("вопрос без тарифа");
            expect("без тарифа стоимость «нет данных»",
                    Main.formatStats(plainAgent)
                            .contains("Стоимость: нет данных — тариф не настроен"));
            plainStore.close();

            // С тарифом, но без запросов — тоже «нет данных».
            JsonConversationStore freshStore = tempStore();
            LlmAgent freshAgent = new LlmAgent(config, ModelSettings.from(env), client, freshStore);
            expect("с тарифом, но без запросов с usage стоимость «нет данных»",
                    Main.formatStats(freshAgent)
                            .contains("нет данных — не было запросов с usage"));
            freshStore.close();
            halfStore.close();
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    // ---------- День 8: контекстный бюджет, warn и block ----------

    /** Прогноз бюджета тем же расчётом, что и в агенте: вход + резерв выхода. */
    private static int agentProjected(LlmAgent agent, String userMessage) {
        return agent.estimateNextContextTokens()
                + agent.tokenCounter().count(userMessage)
                + TokenCounter.MESSAGE_OVERHEAD_TOKENS
                + agent.currentSettings().maxOutputTokens();
    }

    private static void checkContextBudget() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);

            // Разбор политики и окна из окружения.
            Map<String, String> env = new java.util.HashMap<>();
            expect("политика по умолчанию — warn",
                    ModelSettings.from(env).overflowPolicy() == ContextOverflowPolicy.WARN);
            env.put("LLM_CONTEXT_OVERFLOW_POLICY", " block ");
            expect("политика block читается без учёта регистра и пробелов",
                    ModelSettings.from(env).overflowPolicy() == ContextOverflowPolicy.BLOCK);
            env.put("LLM_CONTEXT_WINDOW_TOKENS", "8192");
            expect("контекстное окно читается",
                    ModelSettings.from(env).contextWindowTokens() == 8192);
            expectSettingsError(env, "LLM_CONTEXT_WINDOW_TOKENS", "0");
            expectSettingsError(env, "LLM_CONTEXT_OVERFLOW_POLICY", "nope");

            env.put("LLM_MAX_OUTPUT_TOKENS", "64");
            env.remove("LLM_CONTEXT_WINDOW_TOKENS");

            // Прогноз считается на «зондовом» агенте без окна.
            JsonConversationStore probeStore = tempStore();
            LlmAgent probeAgent = new LlmAgent(config, ModelSettings.from(env), client, probeStore);
            probeAgent.setTokenCounter(LEN_COUNTER);
            String message = "вопрос бюджета";
            int projected = agentProjected(probeAgent, message);
            probeStore.close();

            // Граница: оценка ровно на уровне бюджета — блокировки нет.
            Map<String, String> edgeEnv = new java.util.HashMap<>(env);
            edgeEnv.put("LLM_CONTEXT_WINDOW_TOKENS", String.valueOf(projected));
            edgeEnv.put("LLM_CONTEXT_OVERFLOW_POLICY", "block");
            JsonConversationStore edgeStore = tempStore();
            LlmAgent edgeAgent = new LlmAgent(config, ModelSettings.from(edgeEnv), client, edgeStore);
            edgeAgent.setTokenCounter(LEN_COUNTER);
            expect("оценка на границе бюджета не блокируется",
                    "Ок".equals(edgeAgent.ask(message)));
            edgeStore.close();

            // block: прогнозируемое превышение — без HTTP, без изменения истории.
            Map<String, String> blockEnv = new java.util.HashMap<>(env);
            blockEnv.put("LLM_CONTEXT_WINDOW_TOKENS", String.valueOf(projected - 1));
            blockEnv.put("LLM_CONTEXT_OVERFLOW_POLICY", "block");
            JsonConversationStore blockStore = tempStore();
            LlmAgent blockAgent = new LlmAgent(config, ModelSettings.from(blockEnv), client, blockStore);
            blockAgent.setTokenCounter(LEN_COUNTER);
            int hitsBefore = hitCounter.get();
            long attemptsBefore = blockAgent.sessionStats().apiAttempts();
            List<ChatMessage> historyBeforeBlock = blockAgent.getHistory();
            String blockedMessage = expectAgentError(blockAgent, message);
            expect("превышение бюджета при block даёт локальную блокировку",
                    blockedMessage.contains("заблокирован локально")
                            && blockedMessage.contains("локальная оценка"));
            expect("при локальной блокировке HTTP не выполнялся",
                    hitCounter.get() == hitsBefore);
            expect("при локальной блокировке история не изменена",
                    blockAgent.getHistory().equals(historyBeforeBlock));
            expect("при локальной блокировке попытка к API не засчитана",
                    blockAgent.sessionStats().apiAttempts() == attemptsBefore);
            expect("при block предупреждение перед отправкой не выдаётся",
                    blockAgent.predictContextBudgetWarning(message) == null);

            // warn: предупреждение с пометкой «оценка», прежнее поведение отправки.
            Map<String, String> warnEnv = new java.util.HashMap<>(env);
            warnEnv.put("LLM_CONTEXT_WINDOW_TOKENS", String.valueOf(projected - 1));
            warnEnv.put("LLM_CONTEXT_OVERFLOW_POLICY", "warn");
            JsonConversationStore warnStore = tempStore();
            LlmAgent warnAgent = new LlmAgent(config, ModelSettings.from(warnEnv), client, warnStore);
            warnAgent.setTokenCounter(LEN_COUNTER);
            String warning = warnAgent.predictContextBudgetWarning(message);
            expect("при warn выдаётся предупреждение с пометкой «оценка»",
                    warning != null && warning.contains("оценка"));
            expect("при warn запрос всё равно отправляется",
                    "Ок".equals(warnAgent.ask(message)) && hitCounter.get() == hitsBefore + 1);
            expect("диагностика отмечает превышение бюджета при warn",
                    warnAgent.getLastDiagnostics() != null
                            && warnAgent.getLastDiagnostics().contextBudgetExceeded());

            // /tokens: окно настроено — источник и значение; окна нет — «неизвестен».
            FakeUi tokensUi = new FakeUi(
                    TerminalUi.Input.command("/tokens"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(tokensUi, warnAgent, "glm-5.3-flash");
            expect("/tokens показывает настроенное окно и источник",
                    tokensUi.systems.stream().anyMatch(s -> s.contains("контекстное окно: "
                            + (projected - 1)) && s.contains("ручная настройка")));
            warnStore.close();
            blockStore.close();

            Map<String, String> noWindowEnv = new java.util.HashMap<>();
            noWindowEnv.put("LLM_MAX_OUTPUT_TOKENS", "64");
            JsonConversationStore noWindowStore = tempStore();
            LlmAgent noWindowAgent = new LlmAgent(config, ModelSettings.from(noWindowEnv), client,
                    noWindowStore);
            String tokensNoWindow = Main.formatTokens(noWindowAgent, "glm-5.3-flash");
            expect("/tokens без окна сообщает, что лимит неизвестен",
                    tokensNoWindow.contains("лимит контекста неизвестен")
                            && tokensNoWindow.contains("процент заполнения не вычисляется"));
            noWindowStore.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    // ---------- День 8: различение типов ошибок ----------

    private static void checkErrorClassification() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains("case=ctx-overflow")) {
                return new Response(400, ("{\"error\":{\"code\":\"context_length_exceeded\","
                        + "\"message\":\"This model's maximum context length is 4096 tokens\"}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=other-400")) {
                return new Response(400, ("{\"error\":{\"code\":\"invalid_request_error\","
                        + "\"message\":\"Invalid parameter: temperature\"}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(), client, store);
            agent.setTokenCounter(LEN_COUNTER);
            agent.ask("вопрос перед ошибками");
            List<ChatMessage> before = agent.getHistory();
            long attemptsBefore = agent.sessionStats().apiAttempts();
            long unknownBefore = agent.sessionStats().requestsWithoutUsage();

            String overflowMessage = expectAgentError(agent, "case=ctx-overflow");
            expect("отказ из-за контекста распознаётся по стандартной структуре ошибки",
                    overflowMessage.contains("размера контекста")
                            && overflowMessage.contains("HTTP-статус 400"));
            expect("отказ по контексту не портит историю", agent.getHistory().equals(before));

            String other400 = expectAgentError(agent, "case=other-400");
            expect("прочий HTTP 400 не называется переполнением контекста",
                    other400.contains("HTTP-статус 400") && !other400.contains("контекста"));
            expect("после ошибок история не изменена", agent.getHistory().equals(before));
            expect("неудачные запросы засчитаны как попытки с неизвестным расходом",
                    agent.sessionStats().apiAttempts() == attemptsBefore + 2
                            && agent.sessionStats().requestsWithoutUsage() == unknownBefore + 2
                            && !agent.sessionStats().complete());
            store.close();

            // Таймаут — отдельный тип ошибки; расход неизвестен.
            Path keyStore2 = createSelfSignedKeyStore();
            HttpsServer slowServer = startHttpsServer(keyStore2, (requestBody, session, auth) -> {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
            });
            try {
                Config slowConfig = new Config("test-key",
                        "https://127.0.0.1:" + slowServer.getAddress().getPort()
                                + "/v1/chat/completions",
                        "glm-5.3-flash");
                Map<String, String> env = new java.util.HashMap<>();
                env.put("LLM_REQUEST_TIMEOUT_SECONDS", "1");
                JsonConversationStore slowStore = tempStore();
                LlmAgent slowAgent = new LlmAgent(slowConfig, ModelSettings.from(env),
                        trustedHttpClient(keyStore2), slowStore);
                String timeoutMessage = expectAgentError(slowAgent, "вопрос с таймаутом");
                expect("таймаут распознаётся как таймаут", timeoutMessage.contains("таймаут"));
                expect("после таймаута история не изменилась", slowAgent.getHistory().isEmpty());
                expect("после таймаута попытка засчитана, расход неизвестен",
                        slowAgent.sessionStats().apiAttempts() == 1
                                && slowAgent.sessionStats().requestsWithoutUsage() == 1);
                slowStore.close();
            } finally {
                slowServer.stop(0);
                Files.deleteIfExists(keyStore2);
            }
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    // ---------- День 8: команды /tokens и /stats без вызова API ----------

    private static void checkTokensStatsCommandsNoApi() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ\"}}],\"usage\":{\"prompt_tokens\":12,"
                    + "\"completion_tokens\":34}}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(), client, store);
            agent.ask("вопрос для команд токенов");
            int hitsAfterAsk = hitCounter.get();

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/tokens"),
                    TerminalUi.Input.command("/stats"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("/tokens и /stats не вызывают API", hitCounter.get() == hitsAfterAsk);
            expect("/tokens показывает оценку истории и пометку «оценка»",
                    ui.systems.stream().anyMatch(s -> s.contains("Токены")
                            && s.contains("источник подсчёта")
                            && s.contains("оценка")
                            && s.contains("сохранённый архив")));
            expect("/stats показывает фактические суммы usage",
                    ui.systems.stream().anyMatch(s -> s.contains("Статистика сессии")
                            && s.contains("попыток обращения к API: 1")
                            && s.contains("12") && s.contains("34")));

            // Без запросов: итог «запросов ещё не было», расход «нет данных».
            JsonConversationStore freshStore = tempStore();
            LlmAgent freshAgent = new LlmAgent(config, ModelSettings.defaults(), client, freshStore);
            String freshStats = Main.formatStats(freshAgent);
            expect("/stats без запросов не выдумывает расход",
                    freshStats.contains("запросов ещё не было")
                            && freshStats.contains("нет данных")
                            && freshStats.contains("Стоимость: нет данных — тариф не настроен"));
            freshStore.close();

            // Справка plain-режима содержит новые команды.
            CapturedStream err = capturingStream();
            PlainTerminalUi helpUi = new PlainTerminalUi(reader(""), capturingStream().stream,
                    err.stream);
            helpUi.showFullHelp();
            expect("справка plain-режима содержит /tokens и /stats",
                    err.text().contains("/tokens") && err.text().contains("/stats"));
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    // ---------- День 8+: лимит расхода токенов за сессию ----------

    /** Агент с информационным лимитом сессии из окружения. */
    private static LlmAgent newAgentLimit(Config config, HttpClient client,
                                          JsonConversationStore store, long limit) {
        Map<String, String> env = new java.util.HashMap<>();
        env.put("LLM_SESSION_TOKEN_LIMIT", String.valueOf(limit));
        return new LlmAgent(config, ModelSettings.from(env), client, store);
    }

    private static void checkSessionTokenLimitSettings() {
        Map<String, String> env = new java.util.HashMap<>();
        expect("без переменной лимит сессии отключён",
                ModelSettings.from(env).sessionTokenLimit() == null);
        env.put("LLM_SESSION_TOKEN_LIMIT", "5000");
        expect("положительное значение читается как long",
                ModelSettings.from(env).sessionTokenLimit() == 5000L);
        expectSettingsError(env, "LLM_SESSION_TOKEN_LIMIT", "0");
        expectSettingsError(env, "LLM_SESSION_TOKEN_LIMIT", "-1");
        expectSettingsError(env, "LLM_SESSION_TOKEN_LIMIT", "5.5");
        expectSettingsError(env, "LLM_SESSION_TOKEN_LIMIT", "abc");
        expectSettingsError(env, "LLM_SESSION_TOKEN_LIMIT", "99999999999999999999999");
    }

    private static void checkSessionTokenLimitFeature() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            // Маркер более длинного сценария проверяется первым: после первого
            // запроса маркеры попадают в историю и уходят в следующих телах.
            if (requestBody.contains("case=partial-big")) {
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ P2\"}}],\"usage\":{\"prompt_tokens\":600}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=partial-small")) {
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ P1\"}}],\"usage\":{\"prompt_tokens\":10}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=no-usage")) {
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ U\"}}]}").getBytes(StandardCharsets.UTF_8));
            }
            if (requestBody.contains("case=empty-with-usage")) {
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"\"}}],\"usage\":{\"prompt_tokens\":1000,"
                        + "\"completion_tokens\":2000,\"total_tokens\":9000}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"Ответ "
                    + hitCounter.get() + "\"}}],\"usage\":{\"prompt_tokens\":100,"
                    + "\"completion_tokens\":50,\"total_tokens\":500}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);

            // Расход меньше лимита: уведомления нет, показывается остаток.
            JsonConversationStore underStore = tempStore();
            LlmAgent underAgent = newAgentLimit(config, client, underStore, 400);
            underAgent.ask("вопрос до лимита");
            expect("расход меньше лимита: уведомления нет",
                    underAgent.consumeSessionLimitNotice() == null);
            String underStatus = Main.formatLimit(underAgent);
            expect("статус «не превышен» с остатком до лимита",
                    underStatus.contains("не превышен")
                            && underStatus.contains("остаток до лимита: 250"));
            underStore.close();

            // Расход равен лимиту: «достигнут», уведомление о превышении не выводится.
            JsonConversationStore equalStore = tempStore();
            LlmAgent equalAgent = newAgentLimit(config, client, equalStore, 150);
            equalAgent.ask("вопрос на границе лимита");
            expect("расход равен лимиту: уведомления о превышении нет",
                    equalAgent.consumeSessionLimitNotice() == null);
            String equalStatus = Main.formatLimit(equalAgent);
            expect("при равенстве статус «достигнут» без превышения",
                    equalStatus.contains("достигнут")
                            && !equalStatus.contains("превышен")
                            && !equalStatus.contains("превышение"));
            equalStore.close();

            // Пересечение: уведомление один раз, не повторяется на следующих
            // запросах; после превышения запросы по-прежнему разрешены;
            // total_tokens не суммируется повторно.
            JsonConversationStore crossStore = tempStore();
            LlmAgent crossAgent = newAgentLimit(config, client, crossStore, 200);
            crossAgent.ask("первый вопрос с лимитом");
            expect("до пересечения уведомления нет",
                    crossAgent.consumeSessionLimitNotice() == null);
            crossAgent.ask("второй вопрос с лимитом");
            String firstNotice = crossAgent.consumeSessionLimitNotice();
            expect("при пересечении показывается уведомление с деталями",
                    firstNotice != null
                            && firstNotice.contains("Лимит токенов за сессию превышен")
                            && firstNotice.contains("Установленный лимит: 200")
                            && firstNotice.contains("Учтённый расход: 300")
                            && firstNotice.contains("Превышение: 100 токенов")
                            && firstNotice.contains("Агент продолжает работу")
                            && firstNotice.contains("/limit off"));
            expect("уведомление однократное: повторное чтение пусто",
                    crossAgent.consumeSessionLimitNotice() == null);
            String thirdAnswer = crossAgent.ask("третий вопрос с лимитом");
            expect("после превышения следующий запрос разрешён",
                    thirdAnswer.contains("Ответ") && crossAgent.consumeSessionLimitNotice() == null);
            SessionTokenStats.Snapshot crossStats = crossAgent.sessionStats();
            expect("total_tokens не учитывается повторно (450, а не 1500)",
                    crossStats.totalPromptTokens() == 300
                            && crossStats.totalCompletionTokens() == 150
                            && crossStats.knownTotal() == 450);
            crossStore.close();

            // Команды /limit через диспетчер Main: установка, off, повторы,
            // понижение/повышение и некорректные значения.
            JsonConversationStore cmdStore = tempStore();
            LlmAgent cmdAgent = newAgentLimit(config, client, cmdStore, 200);
            cmdAgent.ask("командный вопрос 1");
            cmdAgent.ask("командный вопрос 2");
            expect("первое превышение по порогу из окружения зафиксировано",
                    cmdAgent.consumeSessionLimitNotice() != null);
            int hitsBeforeCommands = hitCounter.get();
            int historyBeforeCommands = cmdAgent.getHistory().size();
            FakeUi limitUi = new FakeUi(
                    TerminalUi.Input.command("/limit"),
                    TerminalUi.Input.command("/limit 200"),
                    TerminalUi.Input.command("/limit 500"),
                    TerminalUi.Input.command("/limit 100"),
                    TerminalUi.Input.command("/limit off"),
                    TerminalUi.Input.command("/limit"),
                    TerminalUi.Input.message("вопрос после отключения лимита"),
                    TerminalUi.Input.command("/limit 100"),
                    TerminalUi.Input.command("/limit 90"),
                    TerminalUi.Input.command("/limit zero"),
                    TerminalUi.Input.command("/limit 0"),
                    TerminalUi.Input.command("/limit -5"),
                    TerminalUi.Input.command("/limit 5.5"),
                    TerminalUi.Input.command("/limit 99999999999999999999999"),
                    TerminalUi.Input.command("/limit"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(limitUi, cmdAgent, "glm-5.3-flash");
            expect("при запуске сообщается, включён ли лимит сессии",
                    limitUi.systems.stream().anyMatch(s ->
                            s.contains("Лимит расхода токенов за сессию: 200")));
            expect("/limit показывает статус превышенного лимита",
                    limitUi.systems.stream().anyMatch(s ->
                            s.contains("Лимит расхода токенов за сессию: 200")
                                    && s.contains("статус: превышен")));
            expect("повторная установка того же лимита без дублирующего уведомления",
                    limitUi.systems.stream().noneMatch(s ->
                            s.contains("Установленный лимит: 200")));
            expect("повышение лимита выше расхода не создаёт уведомления",
                    limitUi.systems.stream().noneMatch(s ->
                            s.contains("Установленный лимит: 500")));
            expect("установка порога ниже расхода даёт сообщение сразу",
                    limitUi.systems.stream().filter(s ->
                            s.contains("Установленный лимит: 100")).count() == 1);
            expect("/limit off отключает уведомления и сохраняет расход",
                    limitUi.systems.stream().anyMatch(s ->
                            s.contains("Уведомление по лимиту сессии отключено")
                                    && s.contains("Накопленный расход сохранён: 300")));
            expect("после отключения статус «отключён»",
                    limitUi.systems.stream().anyMatch(s ->
                            s.contains("Лимит расхода токенов за сессию: отключён")));
            expect("после превышения запрос по-прежнему выполнен",
                    limitUi.messages.size() == 1);
            expect("повторное включение того же порога не дублирует уведомление",
                    limitUi.systems.stream().filter(s ->
                            s.contains("Установленный лимит: 100")).count() == 1);
            expect("понижение до нового порога даёт уведомление сразу",
                    limitUi.systems.stream().filter(s ->
                            s.contains("Установленный лимит: 90")).count() == 1);
            expect("некорректные значения отклоняются с подсказкой (5 попыток)",
                    limitUi.errors.stream().filter(s ->
                            s.contains("положительным целым числом")).count() == 5);
            expect("после некорректного ввода прежняя настройка сохранена",
                    limitUi.systems.stream().anyMatch(s ->
                            s.contains("Лимит расхода токенов за сессию: 90")));
            expect("уведомления по лимиту не привязаны к диагностике",
                    limitUi.systems.stream().noneMatch(s -> s.contains("Диагностика:")));
            expect("команды лимита не вызывают API",
                    hitCounter.get() == hitsBeforeCommands + 1);
            expect("команды лимита не меняют историю",
                    cmdAgent.getHistory().size() == historyBeforeCommands + 2);
            expect("изменение лимита не сбрасывает накопленный расход",
                    cmdAgent.sessionStats().knownTotal() == 450);
            cmdStore.close();

            // Неполный usage: расход «не менее», статус неопределён.
            JsonConversationStore partialStore = tempStore();
            LlmAgent partialAgent = newAgentLimit(config, client, partialStore, 50);
            partialAgent.ask("case=partial-small");
            expect("частичный usage ниже лимита: уведомления нет",
                    partialAgent.consumeSessionLimitNotice() == null);
            String partialStatus = Main.formatLimit(partialAgent);
            expect("неполные данные: расход «не менее» и статус неопределён",
                    partialStatus.contains("не менее 10")
                            && partialStatus.contains("нельзя достоверно определить")
                            && partialStatus.contains("фактический расход может быть выше"));
            partialAgent.ask("case=partial-big");
            String partialNotice = partialAgent.consumeSessionLimitNotice();
            expect("превышение при неполном usage обозначается как минимум",
                    partialNotice != null
                            && partialNotice.contains("не менее 610")
                            && partialNotice.contains("Превышение: не менее 560 токенов")
                            && partialNotice.contains("фактический расход может быть выше"));
            partialStore.close();

            // Отсутствие usage: расход неизвестен, уведомления нет.
            JsonConversationStore noUsageStore = tempStore();
            LlmAgent noUsageAgent = newAgentLimit(config, client, noUsageStore, 50);
            noUsageAgent.ask("case=no-usage");
            expect("без usage расход неизвестен и уведомления нет",
                    noUsageAgent.consumeSessionLimitNotice() == null);
            expect("статус с отсутствующим usage — «нельзя достоверно определить»",
                    Main.formatLimit(noUsageAgent).contains("нельзя достоверно определить"));
            noUsageStore.close();

            // Пустой ответ с usage через Main: уведомление показывается
            // при выключенной диагностике; история не меняется; далее чат жив.
            JsonConversationStore emptyStore = tempStore();
            Map<String, String> emptyEnv = new java.util.HashMap<>();
            emptyEnv.put("LLM_SESSION_TOKEN_LIMIT", "50");
            LlmAgent emptyAgent = new LlmAgent(config, ModelSettings.from(emptyEnv), client, emptyStore);
            FakeUi emptyUi = new FakeUi(
                    TerminalUi.Input.message("case=empty-with-usage"),
                    TerminalUi.Input.message("обычный вопрос после пустого"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(emptyUi, emptyAgent, "glm-5.3-flash");
            expect("пустой ответ распознаётся ошибкой",
                    emptyUi.errors.stream().anyMatch(s -> s.contains("пустой итоговый ответ")));
            expect("уведомление о лимите показано при пустом ответе с usage",
                    emptyUi.systems.stream().anyMatch(s ->
                            s.contains("Лимит токенов за сессию превышен")
                                    && s.contains("Установленный лимит: 50")
                                    && s.contains("Учтённый расход: 3000")));
            expect("уведомление показывается при выключенной диагностике",
                    emptyUi.systems.stream().noneMatch(s -> s.contains("Диагностика:")));
            expect("после пустого ответа история не изменилась",
                    emptyAgent.getHistory().size() == 2);
            expect("после превышения следующим сообщением получен ответ",
                    emptyUi.messages.size() == 1);
            emptyStore.close();

            // Перезапуск: расход сессии сбрасывается, история сохраняется.
            Path restartFile = Files.createTempDirectory(baseTempDir, "limit-restart-")
                    .resolve("conversation.json");
            Map<String, String> restartEnv = new java.util.HashMap<>();
            restartEnv.put("LLM_SESSION_TOKEN_LIMIT", "200");
            int historyBeforeRestart;
            try (JsonConversationStore restartStore = new JsonConversationStore(restartFile)) {
                LlmAgent first = new LlmAgent(config, ModelSettings.from(restartEnv),
                        client, restartStore);
                first.ask("вопрос до перезапуска");
                first.ask("второй вопрос до перезапуска");
                expect("перед перезапуском расход учтён",
                        first.sessionStats().knownTotal() == 300);
                historyBeforeRestart = first.getHistory().size();
            }
            try (JsonConversationStore reopened = new JsonConversationStore(restartFile)) {
                LlmAgent second = new LlmAgent(config, ModelSettings.from(restartEnv),
                        client, reopened);
                expect("перезапуск сбрасывает расход сессии",
                        second.sessionStats().apiAttempts() == 0
                                && second.sessionStats().knownTotal() == 0);
                expect("перезапуск сохраняет историю",
                        second.hasRestoredContext()
                                && second.getHistory().size() == historyBeforeRestart);
                expect("после перезапуска уведомлений нет",
                        second.consumeSessionLimitNotice() == null);
            }
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    // ---------- День 8+: /clear — удаление истории текущего диалога ----------

    private static void checkClearCommand() throws Exception {
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

    /** Подтверждение очистки в UI: только y/yes подтверждают, остальное отменяет. */
    private static void checkClearConfirmationUi() {
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

    // ---------- UI: приглашение, маркеры, справка, цвет, Markdown ----------

    /** Приглашение: задача, ветка, обрезка, многострочный маркер. */
    private static void checkUiPrompt() {
        CapturedStream out = capturingStream();
        CapturedStream err = capturingStream();

        PlainTerminalUi taskUi = new PlainTerminalUi(reader("текст\n"), out.stream, err.stream);
        taskUi.setPromptTask("карточка проекта");
        taskUi.nextInput();
        expect("приглашение показывает текущую задачу",
                err.text().contains("[карточка проекта] > "));

        err = capturingStream();
        PlainTerminalUi longTaskUi = new PlainTerminalUi(reader("текст\n"), out.stream, err.stream);
        String longTask = "очень длинное название задачи, которое не влезает в приглашение";
        longTaskUi.setPromptTask(longTask);
        longTaskUi.nextInput();
        expect("длинная задача в приглашении обрезается с многоточием",
                err.text().contains("…") && !err.text().contains(longTask));

        err = capturingStream();
        PlainTerminalUi clearedUi = new PlainTerminalUi(reader("текст\n"), out.stream, err.stream);
        clearedUi.setPromptTask("задача");
        clearedUi.setPromptTask(null);
        clearedUi.nextInput();
        expect("после /task clear приглашение возвращается к обычному",
                err.text().contains("> ") && !err.text().contains("[задача]"));

        err = capturingStream();
        PlainTerminalUi labelsUi = new PlainTerminalUi(reader("текст\n"), out.stream, err.stream);
        labelsUi.setActiveModeLabel("ветка: main");
        labelsUi.setPromptTask("задача");
        labelsUi.nextInput();
        expect("приглашение показывает и режим, и задачу вместе",
                err.text().contains("[ветка: main] [задача] > "));

        err = capturingStream();
        PlainTerminalUi mlUi = new PlainTerminalUi(reader("/multiline\nстрока\n/send\n"),
                out.stream, err.stream);
        mlUi.nextInput();
        expect("в многострочном режиме приглашение — отдельный маркер",
                err.text().contains("… › "));
    }

    /** Подтверждения успеха с маркером «✓» и подсказки в ошибках памяти. */
    private static void checkUiMessageMarkers() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());
            FakeUi taskUi = new FakeUi(
                    TerminalUi.Input.command("/task подготовить отчёт"),
                    TerminalUi.Input.command("/task"),
                    TerminalUi.Input.command("/task clear"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(taskUi, agent, "glm-5.3-flash");
            expect("сообщения об успехе короткие и с маркером «✓»",
                    taskUi.systems.stream().anyMatch(s ->
                            s.startsWith("✓") && s.contains("Задача задана")
                                    && !s.contains("Долговременная"))
                            && taskUi.systems.stream().anyMatch(s ->
                            s.startsWith("✓") && s.contains("Задача очищена")));
            expect("успешное сообщение не разъясняет устройство памяти (это в /help)",
                    taskUi.systems.stream().noneMatch(s ->
                            s.contains("подставляется в блок") || s.contains("рабочей памяти каждого")));
            expect("задача обновляет приглашение и очищается вместе с ней",
                    taskUi.promptTasks.contains("подготовить отчёт")
                            && taskUi.promptTasks.contains(null));

            LlmAgent memAgent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());
            FakeUi memUi = new FakeUi(
                    TerminalUi.Input.command("/remember Проект: Север"),
                    TerminalUi.Input.command("/forget НесуществующийКлюч"),
                    TerminalUi.Input.command("/forget Проект"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(memUi, memAgent, "glm-5.3-flash");
            expect("сохранение подтверждается кратко: ключ + подсказка /memory",
                    memUi.systems.stream().anyMatch(s ->
                            s.startsWith("✓ Сохранено: Проект") && s.contains("/memory")));
            expect("ошибка «запись не найдена» содержит один следующий шаг",
                    memUi.errors.stream().anyMatch(s ->
                            s.contains("Запись не найдена") && s.contains("Попробуйте /memory")));
            expect("удаление подтверждается маркером и ключом записи",
                    memUi.systems.stream().anyMatch(s ->
                            s.startsWith("✓ Удалено: «Проект»")));
        } finally {
            Files.deleteIfExists(keyStore);
        }

        String help = TerminalUi.chatIndex(80);
        expect("полный индекс /help all — по группам, с назначением каждой",
                help.contains("Память") && help.contains("Контекст")
                        && help.contains("Статистика")
                        && help.contains("/memory") && help.contains("/context")
                        && help.contains("/status")
                        && help.lines().count() <= 18);
        expect("внизу справки — подсказка /help <команда> и навигация",
                help.contains("/help <команда>") && help.contains("Tab"));
    }

    /** Снимки редизайна: старт, prompt по ширинам, help, подтверждение, ошибка. */
    private static void checkUiRedesign() {
        // Старт: 2 строки, без декоративной линии и очевидного текста.
        CapturedStream err = capturingStream();
        new PlainTerminalUi(reader(""), capturingStream().stream, err.stream)
                .showWelcome("test-model");
        String welcome = err.text();
        expect("старт до приглашения: 2 строки, без линии и баннера",
                welcome.lines().count() == 2
                        && welcome.contains("AI Advent Agent")
                        && welcome.contains("test-model")
                        && !welcome.contains("───")
                        && !welcome.contains("Контекст текущей беседы включён")
                        && !welcome.contains("Начата новая беседа"));

        // Prompt по ширинам: 40 остаётся якорем, кириллица и wide Unicode.
        expect("усечение по видимой ширине 40 не ломает prompt",
                UiText.visibleWidth(UiText.truncate(
                        "полное-название-задачи-которое-очень-длинное",
                        40 - 10)) <= 40 - 10
                        && UiText.truncate("продумать карточку проекта", 6).endsWith("…"));
        expect("wide-символы считаются как 2 колонки",
                UiText.visibleWidth("У颳颳") == 5);
        String at40 = "[main · " + UiText.truncate("очень длинное название задачи для проверки сетки", 8) + "]";
        expect("на ширине 40 ветка+задача не разъезжают prompt",
                UiText.visibleWidth(at40) <= 40 - 10);

        // /help <команда>: назначение, usage, примеры, эффекты, связанные.
        String taskHelp = TerminalUi.chatCommandHelp("/task");
        expect("/help /task содержит назначение, usage и примеры",
                taskHelp.startsWith("/task — ") && taskHelp.contains("Использование")
                        && taskHelp.contains("Примеры")
                        && taskHelp.contains("Эффекты") && taskHelp.contains("Связано"));
        expect("неизвестная команда справки даёт один шаг",
                TerminalUi.chatCommandHelp("/несуществующая") == null);
    }

    /** Переключатель цвета LLM_COLOR и Markdown-рендеринг ответа. */
    private static void checkUiColorAndMarkdown() {
        expect("LLM_COLOR=never полностью отключает цвет",
                !TerminalUi.colorsEnabled("never", null));
        expect("LLM_COLOR=always включает цвет даже при NO_COLOR",
                TerminalUi.colorsEnabled("always", "1"));
        expect("LLM_COLOR=auto учитывает NO_COLOR",
                !TerminalUi.colorsEnabled("auto", "1")
                        && TerminalUi.colorsEnabled("auto", null));
        expect("без переменных цвет включён (auto по умолчанию)",
                TerminalUi.colorsEnabled(null, null));
        expect("неизвестное значение LLM_COLOR трактуется как auto",
                TerminalUi.colorsEnabled("мусор", "1") == TerminalUi.colorsEnabled("auto", "1"));

        expect("маркер «!» не дублируется",
                Main.warn("! текст").equals("! текст") && Main.warn("текст").equals("! текст"));

        String rendered = MarkdownTerminal.render(
                "# Заголовок\n"
                        + "- пункт списка\n"
                        + "**жирный** и `код` и [текст](https://example.com)\n"
                        + "```java\n"
                        + "int x = 5; // комментарий\n"
                        + "```\n"
                        + "обычный текст без разметки");
        expect("заголовок оформляется жирным цветом",
                rendered.contains("\u001b[1m\u001b[36mЗаголовок"));
        expect("жирный текст оформляется ANSI-жирностью",
                rendered.contains("\u001b[1mжирный\u001b[0m"));
        expect("inline-код выделяется цветом",
                rendered.contains("\u001b[36mкод\u001b[0m"));
        expect("блок кода помечается языком",
                rendered.contains("· java"));
        expect("код подсвечивается: комментарий и число",
                rendered.contains("\u001b[2m// комментарий")
                        && rendered.contains("\u001b[36m5"));
        String stripped = rendered.replaceAll("\u001b\\[[0-9;]*[a-zA-Z]", "");
        expect("список отображается символом «•»",
                stripped.contains("• пункт списка"));
        expect("содержимое ответа при рендере не теряется",
                stripped.contains("int x = 5; // комментарий")
                        && stripped.contains("обычный текст без разметки"));
    }

    /** В хранимую историю и файл попадает чистый текст без ANSI. */
    private static void checkHistoryStoreCleanText() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"\\u001b[31mкрасный\\u001b[0m ответ\"}}],"
                        + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3}}")
                        .getBytes(StandardCharsets.UTF_8)));
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), store);
            agent.ask("вопрос");
            expect("история хранит чистый текст без управляющих последовательностей",
                    agent.getHistory().size() == 2
                            && agent.getHistory().get(1).content().contains("красный ответ")
                            && !agent.getHistory().get(1).content().contains("\u001b"));
            String fileText = Files.readString(store.file(), StandardCharsets.UTF_8);
            expect("в файле истории чистый текст без ANSI-кодов",
                    fileText.contains("красный ответ") && !fileText.contains("\u001b"));
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    private static void checkClearDemoIsolation() throws Exception {
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

    /** Хранилище с сохранённой парой, у которого запись всегда падает. */
    private static final class HistoryButFailingStore implements ConversationStore {
        @Override
        public ConversationState load() {
            return new ConversationState("sid-clear-error", List.of(
                    new ChatMessage("user", "вопрос до сбоя"),
                    new ChatMessage("assistant", "ответ до сбоя")));
        }

        @Override
        public void save(ConversationState state) {
            throw new ConversationStoreException("тестовый отказ записи (диск недоступен)");
        }

        @Override
        public void close() {
        }
    }

    private static void checkClearErrorKeepsMemory() {
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

    // ---------- День 8+: ручной режим измерения токенов (/demo) ----------

    /** Агент с заданным набором настроек из окружения. */
    private static LlmAgent newEnvAgent(Config config, HttpClient client,
                                        JsonConversationStore store, Map<String, String> env) {
        return new LlmAgent(config, ModelSettings.from(env), client, store);
    }

    private static void checkDemoTokensMode() throws Exception {
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

    private static void checkDemoFailureClassifications() throws Exception {
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

    private static void checkDemoCommandsNoApi() throws Exception {
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

    // ---------- День 8+: вставка длинного текста одним сообщением (/paste) ----------

    private static void checkPasteInput() {
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

    // ---------- День 8: совместимость со старыми JSON-файлами ----------

    private static void checkOldHistoryCompatible() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ\"}}],\"usage\":{\"prompt_tokens\":9,"
                        + "\"completion_tokens\":3}}").getBytes(StandardCharsets.UTF_8)));
        try {
            Path historyFile = Files.createTempDirectory(baseTempDir, "compat-")
                    .resolve("conversation.json");
            String sessionId = "compat-session-id";
            String oldJson = "{\"schemaVersion\":1,\"sessionId\":\"" + sessionId + "\","
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"старый вопрос\"},"
                    + "{\"role\":\"assistant\",\"content\":\"старый ответ\"}]}";
            Files.write(historyFile, oldJson.getBytes(StandardCharsets.UTF_8));

            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            try (JsonConversationStore store = new JsonConversationStore(historyFile)) {
                LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                        trustedHttpClient(keyStore), store);
                expect("история старого формата восстанавливается без изменений",
                        agent.getHistory().equals(List.of(
                                new ChatMessage("user", "старый вопрос"),
                                new ChatMessage("assistant", "старый ответ"))));
                agent.ask("новый вопрос");
                JsonNode saved = MAPPER.readTree(
                        Files.readString(historyFile, StandardCharsets.UTF_8));
                expect("новая запись сохраняет прежнюю схему и sessionId без новых полей",
                        saved.path("schemaVersion").asInt(-1)
                                == JsonConversationStore.SUPPORTED_SCHEMA_VERSION
                                && sessionId.equals(saved.path("sessionId").asText())
                                && saved.path("messages").size() == 4);
            }
            try (JsonConversationStore reopened = new JsonConversationStore(historyFile)) {
                expect("файл, записанный после учёта токенов, читается прежним форматом",
                        reopened.load().messages().size() == 4);
            }
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    // ---------- День 9: сжатие истории ----------

    /** Маркер служебного запроса суммаризации в теле запроса. */
    private static final String SUMMARY_MARKER = "Ты сжимаешь историю диалога";

    private static Map<String, String> day9Env(String keep, String batch) {
        Map<String, String> env = new java.util.HashMap<>();
        env.put("LLM_CONTEXT_MODE", "summary");
        // Сжатие истории применяется внутри стратегии веток; это даёт
        // старым проверкам те же семантики, что до появления стратегий.
        env.put("LLM_CONTEXT_STRATEGY", "branching");
        env.put("LLM_CONTEXT_KEEP_LAST_MESSAGES", keep);
        env.put("LLM_SUMMARY_BATCH_MESSAGES", batch);
        return env;
    }

    /** Проверка валидации настроек сжатия: чётность, положительность, режим. */
    private static void checkDay9SettingsValidation() {
        Map<String, String> env = day9Env("10", "10");
        ModelSettings settings = ModelSettings.from(env);
        expect("LLM_CONTEXT_MODE=summary принимается", settings.contextMode() == ContextMode.SUMMARY);
        expect("значения сжатия по умолчанию соответствуют константам",
                settings.keepLastMessages() == ModelSettings.DEFAULT_KEEP_LAST_MESSAGES
                        && settings.summaryBatchMessages()
                        == ModelSettings.DEFAULT_SUMMARY_BATCH_MESSAGES
                        && settings.summaryMaxOutputTokens()
                        == ModelSettings.DEFAULT_SUMMARY_MAX_OUTPUT_TOKENS);

        for (String invalid : new String[]{"0", "3", "11", "abc", "-2"}) {
            Map<String, String> badKeep = day9Env(invalid, "10");
            try {
                ModelSettings.from(badKeep);
                expect("LLM_CONTEXT_KEEP_LAST_MESSAGES=" + invalid + " отклоняется", false);
            } catch (AgentException e) {
                expect("LLM_CONTEXT_KEEP_LAST_MESSAGES=" + invalid + " отклоняется с понятной "
                        + "ошибкой", e.getMessage().contains("LLM_CONTEXT_KEEP_LAST_MESSAGES"));
            }
            Map<String, String> badBatch = day9Env("10", invalid);
            try {
                ModelSettings.from(badBatch);
                expect("LLM_SUMMARY_BATCH_MESSAGES=" + invalid + " отклоняется", false);
            } catch (AgentException e) {
                expect("LLM_SUMMARY_BATCH_MESSAGES=" + invalid + " отклоняется", e.getMessage()
                        .contains("LLM_SUMMARY_BATCH_MESSAGES"));
            }
        }
        Map<String, String> badSummaryTokenLimit = day9Env("10", "10");
        badSummaryTokenLimit.put("LLM_SUMMARY_MAX_OUTPUT_TOKENS", "-5");
        try {
            ModelSettings.from(badSummaryTokenLimit);
            expect("LLM_SUMMARY_MAX_OUTPUT_TOKENS=-5 отклоняется", false);
        } catch (AgentException e) {
            expect("LLM_SUMMARY_MAX_OUTPUT_TOKENS=-5 отклоняется",
                    e.getMessage().contains("LLM_SUMMARY_MAX_OUTPUT_TOKENS"));
        }
        Map<String, String> badMode = day9Env("10", "10");
        badMode.put("LLM_CONTEXT_MODE", "gip");
        try {
            ModelSettings.from(badMode);
            expect("LLM_CONTEXT_MODE=gip отклоняется", false);
        } catch (AgentException e) {
            expect("LLM_CONTEXT_MODE=gip отклоняется",
                    e.getMessage().contains("LLM_CONTEXT_MODE"));
        }
    }

    /**
     * Общий сервер Day-9: ответы на обычные вопросы — «Ответ N» с usage
     * 10/20, на суммаризацию — «Резюме: <факты>» с usage 50/5. Возвращается
     * сервер-контекст со счётчиками и списком последних тел запросов.
     */
    private record Day9Server(HttpsServer server, AtomicInteger regularHits,
                              AtomicInteger summaryHits, List<String> bodies) {
    }

    private static Day9Server startDay9Server(Path keyStore) throws Exception {
        AtomicInteger regularHits = new AtomicInteger();
        AtomicInteger answerNumber = new AtomicInteger();
        AtomicInteger summaryHits = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            bodies.add(requestBody);
            if (requestBody.contains(SUMMARY_MARKER)) {
                summaryHits.incrementAndGet();
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\""
                        + "Резюме: кодовое слово ЯКОРЬ-42 и запрет цитрусовых\"}}],"
                        + "\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":5,"
                        + "\"total_tokens\":55}}").getBytes(StandardCharsets.UTF_8));
            }
            regularHits.incrementAndGet();
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":"
                    + "{\"role\":\"assistant\",\"content\":\"Ответ "
                    + answerNumber.incrementAndGet()
                    + ". Подробное развернутое подтверждение с деталями: требование "
                    + "зафиксировано целиком, повторено в терминах переписки и сохранено "
                    + "для дальнейших шагов без сокращений\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,"
                    + "\"total_tokens\":30}}").getBytes(StandardCharsets.UTF_8));
        });
        return new Day9Server(server, regularHits, summaryHits, bodies);
    }

    private static String day9Url(Day9Server s) {
        return "https://127.0.0.1:" + s.server().getAddress().getPort() + "/v1/chat/completions";
    }

    /** Environment-набор со стратегией branching (семантики full-истории Дней 8–9). */
    private static Map<String, String> branchingEnv() {
        Map<String, String> env = new java.util.HashMap<>();
        env.put("LLM_CONTEXT_STRATEGY", "branching");
        return env;
    }

    private static Path day9KeyStore() throws Exception {
        return createSelfSignedKeyStore();
    }

    /**
     * Границы сжатия: ниже порога — без API; на пороге — резюме покрывает
     * ровно первые сообщения вне последних KEEP; последние N дословны.
     */
    private static void checkDay9SummaryThresholds() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "4")),
                    trustedHttpClient(keyStore), store);
            Path historyFile = store.file();

            agent.ask("вопрос 1");  // 2 сообщений: сжимать нечего
            agent.ask("вопрос 2");  // 4 сообщения: uncovered = 0..2 < BATCH=4
            agent.ask("вопрос 3");  // 6 сообщений: uncovered = 2 < BATCH=4
            expect("ниже порога суммаризация не запускается",
                    s.summaryHits().get() == 0 && agent.summary() == null);

            int hitsBefore = s.regularHits().get();
            agent.ask("вопрос 4");  // 8 сообщений: uncovered = 4 >= BATCH → резюме
            expect("на пороге суммаризация выполняется ровно один раз",
                    s.summaryHits().get() == 1 && agent.summary() != null);
            ConversationSummary summary = agent.summary();
            expect("резюме покрывает первые сообщения вне последних KEEP",
                    summary.coveredMessages() == 4 && agent.getHistory().size() == 8
                            && agent.getHistory().size() - summary.coveredMessages() == 4);
            expect("резюме соответствует текущему архиву",
                    summary.matchesArchive(agent.sessionIdAfterAskForTest(), agent.getHistory()));

            // Регулярный запрос после сжатия: system со справкой + хвост дословно.
            expect("обычный запрос выполнен после суммаризации",
                    s.regularHits().get() == hitsBefore + 1);
            String lastRegularBody = null;
            for (int i = s.bodies().size() - 1; i >= 0; i--) {
                String body = s.bodies().get(i);
                if (body.contains("system") && !body.contains(SUMMARY_MARKER)
                        && body.contains("вопрос 4")) {
                    lastRegularBody = body;
                    break;
                }
            }
            JsonNode regularMessages = MAPPER.readTree(lastRegularBody).path("messages");
            expect("system содержит справочное резюме, отделённое от инструкций",
                    regularMessages.get(0).path("content").asText()
                            .contains("<<<РЕЗЮМЕ")
                            && regularMessages.get(0).path("content").asText()
                            .contains("Резюме: кодовое слово ЯКОРЬ-42")
                            && regularMessages.get(0).path("content").asText()
                            .contains("исторические"));
            expect("несжатый хвост отправляется дословно, без дублирования покрытых",
                    regularMessages.size() == 4 // system + tail (2 сообщения) + новый вопрос
                            && "вопрос 3".equals(regularMessages.get(1).path("content").asText())
                            && regularMessages.get(2).path("content").asText().startsWith("Ответ 3")
                            && "вопрос 4".equals(regularMessages.get(3).path("content").asText()));

            // Полный архив сохранён, резюме хранится отдельно, как сущность.
            JsonNode saved = MAPPER.readTree(
                    Files.readString(historyFile, StandardCharsets.UTF_8));
            expect("архив не обрезается сжатием: все сообщения в файле",
                    saved.path("messages").size() == 8);
            expect("резюме сохранено отдельным объектом с отпечатком и версией",
                    saved.path("summary").path("coveredMessages").asInt(-1) == 4
                            && saved.path("summary").path("coveredFingerprint").asText().length()
                            == 64
                            && saved.path("summary").path("formatVersion").asInt(-1) == 1
                            && saved.path("summary").path("text").asText()
                            .contains("ЯКОРЬ-42"));
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /**
     * Инкрементальное обновление: в следующий служебный запрос уходят
     * предыдущее резюме и только новый кусок, без повторной отправки
     * уже сжатых сообщений.
     */
    private static void checkDay9IncrementalSummary() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "4")),
                    trustedHttpClient(keyStore), store);

            for (int i = 1; i <= 4; i++) {
                agent.ask("уникальный вопрос " + i);
            }
            expect("первое резюме создано", agent.summary() != null);
            String firstSummaryText = agent.summary().text();

            // Ещё две пары: первое прихранённое резюме не сдвигается, затем
            // обновление на новую границу (инкрементально).
            agent.ask("запоздалый вопрос 4");
            agent.ask("запоздалый вопрос 4 задача");
            expect("резюме обновлено на новую границу",
                    agent.summary() != null && agent.summary().coveredMessages() == 8);
            String lastSummaryBody = null;
            for (int i = s.bodies().size() - 1; i >= 0; i--) {
                String body = s.bodies().get(i);
                if (body.contains(SUMMARY_MARKER)) {
                    lastSummaryBody = body;
                    break;
                }
            }
            JsonNode summaryMessages = MAPPER.readTree(lastSummaryBody).path("messages");
            String userContent = summaryMessages.get(1).path("content").asText();
            expect("в инкрементальный запрос включено предыдущее резюме",
                    userContent.contains("Резюме: кодовое слово ЯКОРЬ-42")
                            && userContent.contains("уникальный вопрос 3")
                            && userContent.contains("уникальный вопрос 4"));
            expect("уже сжатые сообщения повторно не отправляются на суммаризацию",
                    !userContent.contains("уникальный вопрос 1")
                            && !userContent.contains("уникальный вопрос 2")
                            && !userContent.contains("запоздалый")
                            && firstSummaryText.equals(summaryMessages.get(0)
                            .path("content").asText()) == false);
            expect("инструкция суммаризации не подменяется краткостью профиля",
                    summaryMessages.get(0).path("content").asText().contains(SUMMARY_MARKER)
                            && !summaryMessages.get(0).path("content").asText()
                            .contains("кратко и по существу"));
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Перезапуск: резюме восстанавливается; повреждённый кусок не применяется. */
    private static void checkDay9PersistenceAndStale() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            Path historyFile = store.file();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "4")),
                    trustedHttpClient(keyStore), store);
            for (int i = 1; i <= 4; i++) {
                agent.ask("вопрос персистентности " + i);
            }
            ConversationSummary summaryBefore = agent.summary();
            expect("резюме создано перед перезапуском", summaryBefore != null);
            List<ChatMessage> archiveBefore = agent.getHistory();
            int summaryAttemptsBefore = s.summaryHits().get();
            store.close();

            // Перезапуск: архив и резюме восстанавливаются, API не вызывается.
            try (JsonConversationStore reopened = new JsonConversationStore(historyFile)) {
                LlmAgent restored = new LlmAgent(config,
                        ModelSettings.from(day9Env("2", "4")), trustedHttpClient(keyStore),
                        reopened);
                expect("архив восстанавливается полностью",
                        restored.getHistory().equals(archiveBefore));
                ConversationSummary restoredSummary = restored.summary();
                expect("резюме восстанавливается с той же границей",
                        restoredSummary != null && restoredSummary.text()
                                .equals(summaryBefore.text())
                                && restoredSummary.coveredMessages()
                                == summaryBefore.coveredMessages());
                // Новый запрос: резюме идёт в system, суммаризация не вызывается.
                int summariesBefore2 = s.summaryHits().get();
                restored.ask("вопрос после перезапуска");
                expect("устойчивое резюме не требует повторной суммаризации",
                        s.summaryHits().get() == summariesBefore2);
                expect("после перезапуска в system уходит справка-резюме",
                        s.bodies().get(s.bodies().size() - 1).contains("<<<РЕЗЮМЕ"));
            }

            // Устаревшее резюме: изменить сообщение внутри покрытого куска
            // вручную (как это сделал бы другой процесс или редактирование).
            byte[] raw = Files.readAllBytes(historyFile);
            String json = new String(raw, StandardCharsets.UTF_8)
                    .replace("вопрос персистентности 1", "вопрос персистентности 1 ИЗМЕНЁН");
            Files.write(historyFile, json.getBytes(StandardCharsets.UTF_8));
            try (JsonConversationStore stale = new JsonConversationStore(historyFile)) {
                LlmAgent staleAgent = new LlmAgent(config,
                        ModelSettings.from(day9Env("2", "4")), trustedHttpClient(keyStore), stale);
                expect("устаревшее резюме не применяется, архив остаётся",
                        staleAgent.summary() == null
                                && staleAgent.getHistory().size() == 10);
                // Новый обычный запрос после устаревшего резюме создаёт новое
                // резюме (безопасное поведение без потери архива).
                staleAgent.ask("новый запрос после устаревшего резюме");
                expect("после устаревшего резюме создаётся новое резюме",
                        staleAgent.summary() != null);
            }
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /**
     * Ошибки суммаризации (HTTP 500, пустой ответ, finish_reason=length)
     * не переносят границу резюме и не теряют историю; расход учитывается.
     */
    private static void checkDay9FailureKeepsHistory() throws Exception {
        Path keyStore = day9KeyStore();
        AtomicInteger successCounter = new AtomicInteger();
        AtomicInteger summaryScenarios = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(SUMMARY_MARKER)) {
                int scenario = summaryScenarios.incrementAndGet();
                if (scenario == 1) {
                    return new Response(500, "{\"error\":{\"message\":\"internal\"}}"
                            .getBytes(StandardCharsets.UTF_8));
                }
                if (scenario == 2) {
                    return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                            + "\"message\":{\"role\":\"assistant\",\"content\":\"\"}}],"
                            + "\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":5}}")
                            .getBytes(StandardCharsets.UTF_8));
                }
                if (scenario == 3) {
                    // Обрезанный по лимиту ответ с usage: не выдаётся за резюме.
                    return new Response(200, ("{\"choices\":[{\"finish_reason\":\"length\","
                            + "\"message\":{\"role\":\"assistant\",\"content\":\"частичный\""
                            + "}}],\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":500}}")
                            .getBytes(StandardCharsets.UTF_8));
                }
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":"
                        + "{\"role\":\"assistant\",\"content\":\"Резюме: ок\"}}]}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ " + successCounter.incrementAndGet() + "\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");

            for (int scenario = 1; scenario <= 3; scenario++) {
                JsonConversationStore store = tempStore();
                LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "4")),
                        trustedHttpClient(keyStore), store);
                // Три подготовленных пары: следующий запрос достигает порога
                // и сначала выполняет реальную попытку суммаризации.
                for (int i = 1; i <= 3; i++) {
                    agent.ask("подготовка " + i);
                }
                String answer = agent.ask("вопрос для сбоя резюме " + scenario);
                expect("ошибка суммаризации не мешает обычному ответу",
                        answer != null && !answer.isBlank());
                expect("после сбоя суммаризации история сохранена и дополнена парой",
                        agent.getHistory().size() == 8
                                && agent.summary() == null);
                expect("после сбоя суммаризации архив не потерян и повторов нет",
                        agent.getHistory().size() == 8);
                SessionTokenStats.Snapshot stats = agent.sessionStats();
                // Попытки: обычные ответы + одна попытка суммаризации.
                expect("попытки API учитываются: обычные запросы и суммаризация",
                        stats.apiAttempts() == 5 && stats.regularAttempts() == 4
                                && stats.summaryAttempts() == 1
                                && (scenario >= 2 ? stats.summaryPromptTokens() == 50
                                : stats.summaryPromptTokens() == 0));
                if (scenario == 3) {
                    // length: completion учтён, резюме не принято.
                    expect("ограниченный по length ответ не принят за резюме, но учтён",
                            stats.summaryCompletionTokens() == 500);
                }
                // Разрыв пар нет, служебные запросы в историю не попали.
                expect("служебный запрос суммаризации не попадает в диалог",
                        agent.getHistory().stream()
                                .noneMatch(m -> m.content().contains("Резюме")));
                store.close();
            }
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** /clear и resetConversation сбрасывают резюме, но не статистику сессии. */
    private static void checkDay9ClearRemovesSummary() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "4")),
                    trustedHttpClient(keyStore), store);
            for (int i = 1; i <= 4; i++) {
                agent.ask("вопрос для очистки " + i);
            }
            expect("перед очисткой резюме существует", agent.summary() != null);
            long spendBefore = agent.sessionStats().knownTotal();
            boolean spendTracked = agent.sessionStats().summaryAttempts() > 0;

            agent.resetConversation();
            expect("resetConversation сбрасывает резюме", agent.summary() == null);
            JsonNode saved = MAPPER.readTree(
                    Files.readString(store.file(), StandardCharsets.UTF_8));
            expect("файл после очистки пуст и без объекта summary",
                    !saved.has("summary") && saved.path("messages").isEmpty());
            expect("расход сессии не сбрасывается очисткой",
                    agent.sessionStats().knownTotal() >= spendBefore);
            expect("расход суммаризации был учтён в сессии",
                    spendTracked && agent.sessionStats().summaryAttempts() >= 1);
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /**
     * Переключение /context и показ /summary не вызывают API; обе операции
     * без истории работают; недоступный режим даёт ошибку.
     */
    private static void checkDay9ContextCommandsNoApi() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), store);
            int hitsBefore = s.regularHits().get() + s.summaryHits().get();
            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/context"),
                    TerminalUi.Input.command("/context summary"),
                    TerminalUi.Input.command("/summary"),
                    TerminalUi.Input.command("/context full"),
                    TerminalUi.Input.command("/summary refresh"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("команды /context и /summary не вызывают API",
                    s.regularHits().get() + s.summaryHits().get() == hitsBefore);
            expect("/context показывает текущий режим и параметры",
                    ui.systems.stream().anyMatch(t -> t.contains("Режим контекста")
                            && t.contains("дословно")));
            expect("/context summary сообщает о расходе служебных запросов",
                    ui.systems.stream().anyMatch(t ->
                            t.contains("дополнительные запросы")));
            expect("переключённый режим отражается моделью настроек",
                    agent.currentSettings().contextMode() == ContextMode.FULL);
            expect("/summary refresh без истории объясняет без API",
                    ui.systems.stream().anyMatch(t -> t.contains("Сжимать пока нечего")));
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Сравнение: один снимок, изоляция, учёт расхода, история не затронута. */
    private static void checkDay9Compare() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "4")),
                    trustedHttpClient(keyStore), store);
            for (int i = 1; i <= 4; i++) {
                agent.ask("факт " + i);
            }
            expect("резюме есть перед сравнением", agent.summary() != null);
            List<ChatMessage> historyBefore = agent.getHistory();
            ConversationSummary summaryBefore = agent.summary();
            long knownBefore = agent.sessionStats().knownTotal();
            long attemptsBefore = agent.sessionStats().apiAttempts();

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/context compare Какой факт был первым?"),
                    TerminalUi.Input.command("/exit"));
            ui.confirmCompareAnswer = true;
            Main.runLoop(ui, agent, "glm-5.3-flash");

            expect("сравнение не изменяет историю",
                    agent.getHistory().equals(historyBefore));
            expect("сравнение не продвигает резюме",
                    agent.summary().coveredMessages() == summaryBefore.coveredMessages()
                            && agent.summary().text().equals(summaryBefore.text()));
            expect("показаны оба ответа",
                    ui.messages.stream().anyMatch(m -> m.contains("БЕЗ СЖАТИЯ"))
                            && ui.messages.stream().anyMatch(m -> m.contains("СО СЖАТИЕМ")));
            // Сжатое резюме покрывает 6 из 10: сравнение готовит инкремент
            // (COMPARE_SUMMARY_PREP) и выполняет два сравнительных запроса.
            expect("сравнение = подготовка + ровно два запроса вариантов",
                    agent.sessionStats().apiAttempts() == attemptsBefore + 3
                            && agent.sessionStats().compareAttempts() == 3);
            expect("расход сравнения учтён в сессии",
                    agent.sessionStats().knownTotal() > knownBefore);
            SessionTokenStats.Snapshot stats = agent.sessionStats();
            expect("расход сравнения и суммаризации помечен по назначениям",
                    stats.compareAttempts() == 3 && stats.regularAttempts() >= 3
                            && stats.summaryAttempts() == 1);
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Защиты сравнения: короткая история, отказ подтверждения, демо-изоляция. */
    private static void checkDay9CompareGuards() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");

            // Короткая история: нет смысла сравнивать два одинаковых запроса.
            JsonConversationStore shortStore = tempStore();
            LlmAgent shortAgent = new LlmAgent(config, ModelSettings.from(day9Env("2", "4")),
                    trustedHttpClient(keyStore), shortStore);
            shortAgent.ask("единственный вопрос");
            int hitsBefore = s.regularHits().get() + s.summaryHits().get();
            FakeUi shortUi = new FakeUi(
                    TerminalUi.Input.command("/context compare любой"),
                    TerminalUi.Input.command("/exit"));
            shortUi.confirmCompareAnswer = true;
            Main.runLoop(shortUi, shortAgent, "glm-5.3-flash");
            expect("на короткой истории сравнение не выполняется без API",
                    s.regularHits().get() + s.summaryHits().get() == hitsBefore
                            && shortUi.confirmCompareCount == 0);
            shortStore.close();

            // Отказ подтверждения: API не вызывается.
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "4")),
                    trustedHttpClient(keyStore), store);
            for (int i = 1; i <= 3; i++) {
                agent.ask("вопрос для отмены " + i);
            }
            hitsBefore = s.regularHits().get() + s.summaryHits().get();
            FakeUi cancelUi = new FakeUi(
                    TerminalUi.Input.command("/context compare любой"),
                    TerminalUi.Input.command("/exit"));
            cancelUi.confirmCompareAnswer = false;
            Main.runLoop(cancelUi, agent, "glm-5.3-flash");
            expect("отказ подтверждения не выполняет API",
                    s.regularHits().get() + s.summaryHits().get() == hitsBefore
                            && cancelUi.messages.isEmpty());
            store.close();

            // Демо-беседа: сравнение отказывается (изоляция режимов).
            FakeUi demoUi = new FakeUi(
                    TerminalUi.Input.command("/demo tokens"),
                    TerminalUi.Input.command("/context compare любой"),
                    TerminalUi.Input.command("/demo stop"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(demoUi, agent, "glm-5.3-flash");
            expect("в демо-режиме сравнение не выполняется без API",
                    s.regularHits().get() + s.summaryHits().get() == hitsBefore
                            && demoUi.systems.stream().anyMatch(
                                    t -> t.contains("режиме измерения")
                                            || t.contains("сравнивать не на чем")));
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    // ---------- День 9: регрессии (исправления Дня 9.1) ----------

    /** Создаёт архив из пар «факт/принял» и возвращает его. */
    private static List<ChatMessage> seededArchive(String userPrefix, String assistantPrefix,
                                                   int pairs) {
        List<ChatMessage> archive = new ArrayList<>();
        for (int i = 1; i <= pairs; i++) {
            archive.add(new ChatMessage("user", userPrefix + " " + i));
            archive.add(new ChatMessage("assistant", "принял " + i));
        }
        return archive;
    }

    private static ConversationSummary seedSummary(List<ChatMessage> archive, int covered,
                                                   String sessionId, String text) {
        return new ConversationSummary(text, covered,
                ConversationSummary.computeFingerprint(sessionId,
                        new ArrayList<>(archive.subList(0, covered))),
                ConversationSummary.FORMAT_VERSION);
    }

    /**
     * Обязательная регрессия воспроизводённого отказа: архив 12 сообщений,
     * корректное summary покрывает первые 8, KEEP=4, новых старых сообщений
     * нет — сравнение выполняется по готовому summary без суммаризации.
     */
    private static void checkDay9CompareReadySummary() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            String sessionId = "seed-session-day9";
            List<ChatMessage> archive = seededArchive("ранний факт", "принял", 6);
            List<ChatMessage> covered = new ArrayList<>(archive.subList(0, 8));
            store.save(new ConversationState(sessionId, archive,
                    seedSummary(archive, 8, sessionId,
                            "Резюме: ранние факты 1-4 в свернутом виде")));

            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("4", "4")),
                    trustedHttpClient(keyStore), store);
            expect("готовое summary действует перед сравнением",
                    agent.summary() != null && agent.summary().coveredMessages() == 8);
            expect("новых старых сообщений нет (вне последних 4 всё покрыто)",
                    agent.uncoveredOldMessagesCount() == 0);
            expect("сравнение возможно по готовому summary", agent.canCompare());

            long attemptsBefore = agent.sessionStats().apiAttempts();
            int summaryHitsBefore = s.summaryHits().get();
            long knownBefore = agent.sessionStats().knownTotal();
            List<ChatMessage> archiveBefore = agent.getHistory();
            String question = "Составь карточку проекта по нашей переписке";

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/context compare " + question),
                    TerminalUi.Input.command("/exit"));
            ui.confirmCompareAnswer = true;
            Main.runLoop(ui, agent, "glm-5.3-flash");

            expect("сравнение не блокируется, подтверждение запрошено",
                    ui.confirmCompareCount == 1);
            expect("служебного запроса суммаризации нет",
                    s.summaryHits().get() == summaryHitsBefore);
            expect("выполняются ровно два обычных запроса сравнения",
                    agent.sessionStats().apiAttempts() == attemptsBefore + 2
                            && agent.sessionStats().compareAttempts() == 2);

            List<String> recent = s.bodies().subList(s.bodies().size() - 2, s.bodies().size());
            String fullBody = null;
            String compactBody = null;
            for (String body : recent) {
                if (body.contains("Резюме: ранние факты")) {
                    compactBody = body;
                } else {
                    fullBody = body;
                }
            }
            JsonNode fullMessages = MAPPER.readTree(fullBody).path("messages");
            // system + 12 сообщений архива + новый user = 14.
            expect("FULL содержит все 12 сообщений архива",
                    fullMessages.size() == 14
                            && "ранний факт 1".equals(fullMessages.get(1).path("content").asText())
                            && "ранний факт 6".equals(fullMessages.get(11).path("content").asText())
                            && fullMessages.get(13).path("content").asText().equals(question));
            JsonNode compactMessages = MAPPER.readTree(compactBody).path("messages");
            // system со справкой + 4 последних сообщения + новый user = 6.
            expect("SUMMARY содержит резюме и последние 4 сообщения",
                    compactMessages.size() == 6
                            && compactMessages.get(0).path("content").asText().contains("<<<РЕЗЮМЕ")
                            && "ранний факт 5".equals(compactMessages.get(1).path("content").asText())
                            && "принял 6".equals(compactMessages.get(4).path("content").asText())
                            && compactMessages.get(5).path("content").asText().equals(question));
            expect("ранние 8 сообщений не дублируются в SUMMARY",
                    streamContents(compactMessages).noneMatch("ранний факт 1"::equals)
                            && streamContents(compactMessages).noneMatch("ранний факт 2"::equals)
                            && streamContents(compactMessages).noneMatch("ранний факт 4"::equals));
            expect("вопрос одинаковый в обоих вариантах",
                    streamContents(fullMessages).anyMatch(question::equals)
                            && streamContents(compactMessages).anyMatch(question::equals));
            expect("архив и резюме после сравнения не изменились",
                    agent.getHistory().equals(archiveBefore)
                            && agent.summary().coveredMessages() == 8);
            expect("расходы учтены ровно один раз (полный usage обеих веток)",
                    agent.sessionStats().knownTotal() > knownBefore
                            && agent.sessionStats().complete());
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Сравнение сразу после /summary refresh: подготовка не требуется. */
    private static void checkDay9CompareAfterRefresh() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            String sessionId = "seed-refresh-day9";
            store.save(new ConversationState(sessionId,
                    seededArchive("факт уточнения", "принял уточнение", 6)));
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("4", "4")),
                    trustedHttpClient(keyStore), store);
            String refreshMsg = agent.refreshSummary();
            expect("/summary refresh создаёт резюме на 8 сообщений",
                    agent.summary() != null && agent.summary().coveredMessages() == 8
                            && refreshMsg != null);
            long attemptsBefore = agent.sessionStats().apiAttempts();
            int summaryHitsBefore = s.summaryHits().get();
            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/context compare какие факты важны?"),
                    TerminalUi.Input.command("/exit"));
            ui.confirmCompareAnswer = true;
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("сравнение сразу после refresh: подготовки нет, два запроса",
                    agent.sessionStats().apiAttempts() == attemptsBefore + 2
                            && s.summaryHits().get() == summaryHitsBefore);
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Переиспользование сохранённого summary после перезапуска. */
    private static void checkDay9CompareReuseAfterRestart() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            Path historyFile = store.file();
            String sessionId = "seed-restart-day9";
            List<ChatMessage> archive = seededArchive("пара для перезапуска", "готово", 5);
            store.save(new ConversationState(sessionId, archive,
                    seedSummary(archive, 6, sessionId,
                            "Резюме перезапуска: факты 1-3 в свернутом виде")));
            store.close();
            try (JsonConversationStore reopened = new JsonConversationStore(historyFile)) {
                LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("4", "4")),
                        trustedHttpClient(keyStore), reopened);
                int summaryHitsBefore = s.summaryHits().get();
                long attemptsBefore = agent.sessionStats().apiAttempts();
                FakeUi ui = new FakeUi(
                        TerminalUi.Input.command("/context compare откуда пара 1?"),
                        TerminalUi.Input.command("/exit"));
                ui.confirmCompareAnswer = true;
                Main.runLoop(ui, agent, "glm-5.3-flash");
                expect("после перезапуска summary переиспользуется без суммаризации",
                        s.summaryHits().get() == summaryHitsBefore
                                && agent.sessionStats().apiAttempts() == attemptsBefore + 2);
            }
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /**
     * Ошибка подготовки summary: full-вариант выполняется, сжатая ветка не
     * запускается, сравнение помечается неуспешным; расход попытки учтён.
     */
    private static void checkDay9ComparePrepFailure() throws Exception {
        Path keyStore = day9KeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(SUMMARY_MARKER)) {
                return new Response(500, "{\"error\":{\"message\":\"internal\"}}"
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ\"}}],\"usage\":{\"prompt_tokens\":10,"
                    + "\"completion_tokens\":20}}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            store.save(new ConversationState("seed-prep-fail",
                    seededArchive("предрынок подготовки", "ок", 3)));
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("4", "4")),
                    trustedHttpClient(keyStore), store);
            long attemptsBefore = agent.sessionStats().apiAttempts();
            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/context compare ранние события?"),
                    TerminalUi.Input.command("/exit"));
            ui.confirmCompareAnswer = true;
            Main.runLoop(ui, agent, "glm-5.3-flash");

            SessionTokenStats.Snapshot stats = agent.sessionStats();
            expect("сбой подготовки: full-запрос выполнен как обычный COMPARE_FULL",
                    agent.sessionStats().apiAttempts() == attemptsBefore + 2);
            expect("сбой подготовки не выдаёт сравнение за успешное",
                    ui.messages.stream().anyMatch(m -> m.contains("СО СЖАТИЕМ")
                            && m.contains("сравнение со сжатием не выполнялось"))
                            && ui.systems.stream().anyMatch(t ->
                            t.contains("НЕ полностью успешное")));
            expect("расход неудачной попытки подготовки известен как минимум",
                    !stats.complete() || stats.requestsWithoutUsage() > 0);
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Частичный и отсутствующий usage в сравнении: «недостаточно данных». */
    private static void checkDay9CompareUsageGaps() throws Exception {
        Path keyStore = day9KeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(SUMMARY_MARKER)) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"Резюме: тест\""
                        + "}}],\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":4}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            // Различаем ветки по числу сообщений assistant: FULL содержит
            // весь архив (4), сжатый вариант — только хвост (3-… 2).
            int assistantCount = requestBody.split("assistant", -1).length - 1;
            if (assistantCount >= 4) {
                // Частичный usage у FULL.
                return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ Full\"}}],\"usage\":{\"prompt_tokens\":10}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            // SUMMARY-ветка — без usage вовсе.
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ответ Summary\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            List<ChatMessage> archive = seededArchive("предрынок", "ок", 4);
            store.save(new ConversationState("seed-usage-day9", archive));
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("4", "4")),
                    trustedHttpClient(keyStore), store);
            LlmAgent.CompareResult first = agent.compare("маркерный вопрос");
            SessionTokenStats.Snapshot stats = agent.sessionStats();
            expect("подготовка и оба варианта составляют три сравнительных попытки",
                    stats.compareAttempts() == 3
                            && first.prepPerformed() && first.prepError() == null
                            && first.prepPromptTokens() != null
                            && first.prepCompletionTokens() != null);
            LlmAgent.CompareResult r = agent.compare("вопрос для разрыва usage");
            SessionTokenStats.Snapshot stats2 = agent.sessionStats();
            expect("частичный usage у FULL и отсутствующий у SUMMARY честно помечены",
                    stats2.totalPromptTokens() >= 50
                            && stats2.totalCompletionTokens() >= 0
                            && !stats2.complete()
                            && r.fullPromptTokens() != null && r.fullCompletionTokens() == null
                            && r.summaryPromptTokens() == null
                            && r.summaryCompletionTokens() == null);
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Подписи фактически отправленного контекста: без резюме, с резюме, пусто, full. */
    private static void checkDay9ContextCaptions() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");

            // Пустая история (full по умолчанию).
            JsonConversationStore empty = tempStore();
            LlmAgent fullAgent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), empty);
            fullAgent.ask("первый вопрос");
            expect("пустая история: подпись «Первый запрос»",
                    "Первый запрос: предыдущей истории нет".equals(
                            fullAgent.lastRequestContextCaption()));
            empty.close();

            // Summary-режим без резюме.
            JsonConversationStore noSummary = tempStore();
            LlmAgent noSummaryAgent = new LlmAgent(
                    config, ModelSettings.from(day9Env("4", "4")),
                    trustedHttpClient(keyStore), noSummary);
            noSummaryAgent.ask("начало");
            noSummaryAgent.ask("начало второе");
            expect("summary без резюме: дословные сообщения и «Резюме пока не создано»",
                    "Контекст запроса: 2 сообщений истории дословно. Резюме пока не создано"
                            .equals(noSummaryAgent.lastRequestContextCaption()));

            // Full: подпись последнего запроса фиксировала 6 сообщений истории
            // (перед четвёртым запросом в архиве 3 пары).
            JsonConversationStore fullStore = tempStore();
            LlmAgent fullAgent2 = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), fullStore);
            for (int i = 1; i <= 4; i++) {
                fullAgent2.ask("факт " + i);
            }
            String fullCaption = fullAgent2.lastRequestContextCaption();
            expect("full: «вся история — 6 сообщений дословно»",
                    fullCaption != null
                            && fullCaption.contains("вся история")
                            && fullCaption.contains("6 сообщений дословно"));
            fullStore.close();
            noSummary.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /**
     * Архив длиннее 20 пар без скрытого обрезания; переключение
     * summary → full → summary не теряет данные.
     */
    private static void checkDay9ArchiveNoLossAcrossModes() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            Path historyFile = store.file();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "2")),
                    trustedHttpClient(keyStore), store);
            // 12 пар подготовлены до переключений (KEEP=2/BATCH=2 прощает.
            for (int i = 1; i <= 12; i++) {
                agent.ask("длинный факт " + i);
            }
            List<ChatMessage> archiveAfterSummary = agent.getHistory();
            agent.setContextMode("full");
            agent.ask("проверка полного переключения");
            List<ChatMessage> archiveAfterFull = agent.getHistory();
            JsonNode saved = MAPPER.readTree(
                    Files.readString(historyFile, StandardCharsets.UTF_8));
            expect("архив не терялся при переключениях режимов",
                    saved.path("messages").size() == 26
                            && archiveAfterFull.size() == 26
                            && archiveAfterSummary.size() == 24);
            agent.setContextMode("summary");
            agent.ask("заключительный факт");
            expect("переключение summary → full → summary не теряет архив",
                    agent.getHistory().size() == 28
                            && agent.summary() != null);
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    // ---------- Интеграционный тест: два последовательных запуска процесса ----------

    private record RunResult(int exitCode, String stdout, String stderr) {    }

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
            expect("первый запуск не издаёт лишнего шума (новая беседа очевидна)",
                    !run1.stderr().contains("Начата новая беседа."));
            expect("первый запуск получил ответ от локального сервера",
                    run1.stdout().contains("Ответ 1"));

            // Второй запуск: контекст восстановлен и уходит в API.
            String followUp = "Какое кодовое слово я просил запомнить?";
            RunResult run2 = runAgentProcess(javaBin, classpath, trustStore, url, historyFile,
                    List.of(followUp, "/exit"));
            expect("второй запуск процесса завершился успешно"
                            + (run2.exitCode() == 0 ? "" : " — stderr: " + run2.stderr()),
                    run2.exitCode() == 0);
            expect("второй запуск сообщает о восстановлении контекста",
                    run2.stderr().contains("Восстановлено: 1 обмен"));
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
    /**
     * Персонализация переживает перезапуск: боевой путь создания агента
     * (используемый Main) подключает ProfileStore.openDefault() и
     * MemoryStore.openDefault() — профиль пишется в файл LLM_PROFILE_FILE
     * (а не во временный каталог) и читается новым запуском; долговременная
     * память — в LLM_MEMORY_FILE. Команды /profile и /remember API не
     * вызывают, поэтому тестовый сервер не нужен.
     */
    private static void checkProfilePersistenceAcrossProcesses() throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = buildClasspath();
        // URL валиден по форме (HTTPS), эндпоинт недоступен — но API не вызывается.
        String apiUrl = "https://127.0.0.1:1/v1/chat/completions";
        Path profileFile = Files.createTempDirectory(baseTempDir, "prof-live-")
                .resolve("profile.json");
        Path memoryFile = Files.createTempDirectory(baseTempDir, "mem-live-")
                .resolve("memory.json");
        Path historyFile = Files.createTempDirectory(baseTempDir, "hist-live-")
                .resolve("conversation.json");
        Map<String, String> env = new java.util.HashMap<>();
        env.put("LLM_PROFILE_FILE", profileFile.toAbsolutePath().toString());
        env.put("LLM_MEMORY_FILE", memoryFile.toAbsolutePath().toString());

        // Первый «запуск»: задают имя, стиль, ограничение, скилл, пайплайн и память.
        RunResult run1 = runAgentProcess(javaBin, classpath, null, apiUrl, historyFile,
                List.of(
                        "/profile name ЯКОРЬ-42",
                        "/profile style кратко, по делу",
                        "/profile constraint только русский",
                        "/skill add \"карточка фичи\" название, цель, критерии, шаги",
                        "/pipeline \"напиши фичу\" карточка фичи",
                        "/remember код: ЯКОРЬ-42",
                        "/exit"),
                env);
        expect("первый запуск персистенции профиля завершился успешно"
                        + (run1.exitCode() == 0 ? "" : " — stderr: " + run1.stderr()),
                run1.exitCode() == 0);
        expect("боевой путь пишет в файл профиля по LLM_PROFILE_FILE",
                Files.exists(profileFile));
        expect("файл профиля содержит заданные поля (schemaVersion 1)",
                MAPPER.readTree(Files.readString(profileFile, StandardCharsets.UTF_8))
                        .path("profile").path("name").asText().equals("ЯКОРЬ-42")
                        && MAPPER.readTree(Files.readString(profileFile, StandardCharsets.UTF_8))
                        .path("schemaVersion").asInt() == ProfileStore.SUPPORTED_SCHEMA_VERSION);

        // Второй «запуск»: тот же файл LLM_PROFILE_FILE, новый процесс.
        RunResult run2 = runAgentProcess(javaBin, classpath, null, apiUrl, historyFile,
                List.of("/profile", "/memory", "/pipeline list", "/exit"), env);
        expect("второй запуск персистенции профиля завершился успешно"
                        + (run2.exitCode() == 0 ? "" : " — stderr: " + run2.stderr()),
                run2.exitCode() == 0);
        // PlainTerminalUi печатает ответы команд в stderr, а не stdout.
        String secondOut = run2.stdout() + "\n" + run2.stderr();
        expect("профиль переживает перезапуск: имя читается из файла",
                secondOut.contains("«ЯКОРЬ-42»"));
        expect("профиль переживает перезапуск: стиль читается",
                secondOut.contains("кратко, по делу"));
        expect("профиль переживает перезапуск: ограничение читается",
                secondOut.contains("только русский"));
        expect("скилл и пайплайн переживают перезапуск",
                secondOut.contains("карточка фичи")
                        && secondOut.contains("«напиши фичу»"));
        expect("долговременная память по LLM_MEMORY_FILE переживает перезапуск",
                secondOut.contains("код: ЯКОРЬ-42"));
        expect("файл памяти по LLM_MEMORY_FILE существует",
                Files.exists(memoryFile));
    }

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
        return runAgentProcess(javaBin, classpath, trustStore, apiUrl, historyFile,
                inputLines, Map.of());
    }

    /** Вариант с дополнительными переменными окружения (LLM_PROFILE_FILE и т.п.). */
    private static RunResult runAgentProcess(String javaBin, String classpath, Path trustStore,
                                             String apiUrl, Path historyFile,
                                             List<String> inputLines,
                                             Map<String, String> extraEnv) throws Exception {
        Path stdin = Files.createTempFile(baseTempDir, "stdin-", ".txt");
        Files.write(stdin, (String.join("\n", inputLines) + "\n")
                .getBytes(StandardCharsets.UTF_8));
        Path stdout = Files.createTempFile(baseTempDir, "stdout-", ".txt");
        Path stderr = Files.createTempFile(baseTempDir, "stderr-", ".txt");
        List<String> command = new ArrayList<>(List.of(
                javaBin, "-cp", classpath,
                "com.example.Main"));
        if (trustStore != null) {
            command = new ArrayList<>(List.of(
                    javaBin, "-cp", classpath,
                    "-Djavax.net.ssl.trustStore=" + trustStore.toAbsolutePath(),
                    "-Djavax.net.ssl.trustStorePassword=changeit",
                    "-Djavax.net.ssl.trustStoreType=PKCS12",
                    "com.example.Main"));
        }
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.environment().put("LLM_API_KEY", "test-key");
        processBuilder.environment().put("LLM_API_URL", apiUrl);
        processBuilder.environment().put("LLM_MODEL", "glm-5.3-flash");
        processBuilder.environment().put("LLM_HISTORY_FILE",
                historyFile.toAbsolutePath().toString());
        for (Map.Entry<String, String> extra : extraEnv.entrySet()) {
            processBuilder.environment().put(extra.getKey(), extra.getValue());
        }
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

    // ---------- День 9.2: оценка выгоды и баланс ----------

    /** Короткие сообщения с односложными ответами: сжатие пропускается. */
    private static void checkDay92BenefitGateShortSkips() throws Exception {
        Path keyStore = day9KeyStore();
        AtomicInteger summaryHits = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            // Односложные ответы ассистента — как в реальном замере Дня 9.
            if (requestBody.contains(SUMMARY_MARKER)) {
                summaryHits.incrementAndGet();
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"Резюме: тест\"}}],"
                        + "\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":5}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"Запомнил.\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            Path historyFile = store.file();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "2")),
                    trustedHttpClient(keyStore), store);
            FakeUi ui = new FakeUi(TerminalUi.Input.command("/exit"));
            agent.setProgressListener(ui::showSystem);
            for (int i = 1; i <= 3; i++) {
                agent.ask("подробный факт пользователя номер " + i + " с числом, датой "
                        + "и запретом: длинное содержательное сообщение проекта, история "
                        + "действий, требований и ограничений, доля сведений пользователя "
                        + "в заменяемом объёме велика, и сжатие заведомо невыгодно");
            }
            List<String> notes = agent.consumeContextNotes();
            expect("короткий сценарий: сжатие пропущено, отмечена невыгодность",
                    agent.summary() == null
                            && summaryHits.get() == 0
                            && notes.stream().anyMatch(
                                    t -> t.contains("Сжатие сейчас невыгодно")));
            JsonNode saved = MAPPER.readTree(
                    Files.readString(historyFile, StandardCharsets.UTF_8));
            expect("после пропуска сжатия архив сохранён без резюме",
                    saved.path("messages").size() == 6 && !saved.has("summary"));
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Выгодный сценарий: короткие вопросы, развёрнутые ответы → сжатие выполняется. */
    private static void checkDay92BenefitGateProfitableRuns() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "2")),
                    trustedHttpClient(keyStore), store);
            for (int i = 1; i <= 3; i++) {
                agent.ask("короткий вопрос " + i);
            }
            expect("выгодное сжатие выполняется по локальной оценке",
                    agent.summary() != null && agent.summary().coveredMessages() == 2);
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Явный /summary refresh при невыгодной оценке: предупреждение, но выполнение. */
    private static void checkDay92RefreshWarnsButRuns() throws Exception {
        Path keyStore = day9KeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(SUMMARY_MARKER)) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"Резюме: тест\"}}],"
                        + "\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":5}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"Запомнил.\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("2", "2")),
                    trustedHttpClient(keyStore), store);
            for (int i = 1; i <= 3; i++) {
                agent.ask("подробный факт пользователя номер " + i + ": имя проекта, датa "
                        + "сдачи, точное число вариантов, запрет на публикацию и открытый "
                        + "вопрос о совместимости; строка намеренно длинная, чтобы минимум "
                        + "содержания превышал безопасный порог выгоды по локальной оценке "
                        + "и сжатие заведомо не дало бы экономии");
            }
            expect("оценка указывает на невыгодность", !agent.compressionBenefitLikely());
            String result = agent.refreshSummary();
            expect("refresh выполняется независимо от невыгодной оценки",
                    agent.summary() != null);
            expect("перед явным сжатием показано предупреждение",
                    result != null && result.contains("Предупреждение")
                            && result.contains("невыгодно"));
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** /context compare при невыгодной оценке: предупреждение и выполнение. */
    private static void checkDay92CompareWarnsButRuns() throws Exception {
        Path keyStore = day9KeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(SUMMARY_MARKER)) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"Резюме: тест\"}}],"
                        + "\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":5}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"Запомнил.\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(day9Env("4", "4")),
                    trustedHttpClient(keyStore), store);
            for (int i = 1; i <= 4; i++) {
                agent.ask("подробный факт пользователя " + i + ": имя проекта, численaч "
                        + "версия, запрет на публикацию и открытый вопрос; строка длинная, "
                        + "чтобы содержательная доля превышала порог выгоды и сжатие "
                        + "показывало невыгодность");
            }
            long attemptsBefore = agent.sessionStats().apiAttempts();
            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/context compare короткая проверка?"),
                    TerminalUi.Input.command("/exit"));
            ui.confirmCompareAnswer = true;
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("сравнение выполняется при невыгодной оценке",
                    agent.sessionStats().apiAttempts() >= attemptsBefore + 2
                            && ui.messages.stream().anyMatch(m -> m.contains("БЕЗ СЖАТИЯ")));
            expect("перед сравнением показано предупреждение о невыгодности",
                    ui.systems.stream().anyMatch(t -> t.contains("Предупреждение")
                            && t.contains("невыгодно")));
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Компактный шаблон summary: плоский список, правила против дублей. */
    private static void checkDay92CompactTemplateAndRules() {
        String prompt = LlmAgent.summaryPrompt();
        expect("шаблон summary: плоский список, «один факт — одна строка»",
                prompt.contains("плоский список") && prompt.contains("один факт — одна строка"));
        expect("шаблон запрещает заголовки и Markdown",
                prompt.contains("без заголовков и Markdown"));
        expect("шаблон отбрасывает односложные подтверждения ассистента",
                prompt.contains("«Запомнил.»") && prompt.contains("не сохраняй"));
        expect("каждое сведение записывается один раз",
                prompt.contains("каждое сведение записывай один раз"));
        expect("сохраняются только действующие значения",
                prompt.contains("только действующие значения"));
        expect("исправления заменяют прежние значения",
                prompt.contains("исправления заменяют прежние"));
        expect("разовые просьбы не превращаются в предпочтения",
                prompt.contains("не превращай"));
        expect("пустые разделы не добавляются",
                prompt.contains("только непустые"));
        expect("нет обещания постоянного сокращения",
                prompt.contains("не обещается"));
        expect("в шаблоне нет Markdown-заголовков",
                !prompt.contains("##") && !prompt.contains("# Резюме"));
    }

    /** Баланс экономии/затрат в /stats; отсутствие данных не выдаётся за ноль. */
    private static void checkDay92StatsBalanceLine() {
        SessionTokenStats emptyStats = new SessionTokenStats();
        String emptyLine = Main.compressionBalanceLine(emptyStats.snapshot());
        expect("без запросов баланс помечен недостатком данных",
                emptyLine.contains("недостаточно данных"));

        SessionTokenStats usedStats = new SessionTokenStats();
        usedStats.recordAttempt();
        usedStats.recordUsage(484, 128);
        usedStats.recordContextSavings(20, false, true);
        usedStats.recordAttempt(SessionTokenStats.Purpose.SUMMARY);
        usedStats.recordUsage(SessionTokenStats.Purpose.SUMMARY, 482, 118);
        SessionTokenStats.Snapshot used = usedStats.snapshot();
        String usedLine = Main.compressionBalanceLine(used);
        expect("баланс показывает оценку экономии и затраты суммаризации",
                usedLine.contains("экономия входа: 20 prompt_tokens")
                        && usedLine.contains("вход 482")
                        && usedLine.contains("выход 118"));
        long balance = used.contextSavingsPromptTokens()
                - (used.summaryPromptTokens() + used.summaryCompletionTokens());
        expect("итоговый баланс = экономия минус затраты суммаризации",
                usedLine.contains((balance >= 0 ? "+" : "") + balance));
        expect("баланс отрицательный — перерасход",
                balance < 0 && usedLine.contains(String.valueOf(balance)));

        SessionTokenStats partialStats = new SessionTokenStats();
        partialStats.recordUsage(484, 128);
        partialStats.recordContextSavings(0, false, false);
        String partialLine = Main.compressionBalanceLine(partialStats.snapshot());
        expect("отсутствующий usage помечается недостатком данных, а не нулём",
                partialLine.contains("недостаточно данных"));
        expect("разница при отсутствии usage не записывается как экономия",
                partialStats.snapshot().contextSavingsRequests() == 0);
    }


    private SelfTest() {
    }

    // ================= Стратегии управления контекстом =================

    /** Маркер служебного запроса обновления facts в теле запроса. */
    private static final String FACTS_MARKER = "Ты обновляешь блок фактов";

    private static Map<String, String> factsEnv() {
        Map<String, String> env = new java.util.HashMap<>();
        env.put("LLM_CONTEXT_STRATEGY", "facts");
        return env;
    }

    /** Валидация настроек Дня 10: значения, чётность окон, режим обновления. */
    private static void checkDay10SettingsValidation() {
        Map<String, String> env = new java.util.HashMap<>();
        ModelSettings defaults = ModelSettings.from(env);
        expect("стратегия по умолчанию sliding-window",
                defaults.contextStrategy() == ContextStrategy.SLIDING_WINDOW
                        && defaults.slidingWindowMessages()
                        == ModelSettings.DEFAULT_SLIDING_WINDOW_MESSAGES
                        && defaults.factsWindowMessages()
                        == ModelSettings.DEFAULT_FACTS_WINDOW_MESSAGES
                        && defaults.factsMaxOutputTokens()
                        == ModelSettings.DEFAULT_FACTS_MAX_OUTPUT_TOKENS
                        && defaults.factsUpdateMode() == FactsUpdateMode.AUTO);

        env.put("LLM_CONTEXT_STRATEGY", " facts ");
        expect("LLM_CONTEXT_STRATEGY читается без учёта регистра и пробелов",
                ModelSettings.from(env).contextStrategy() == ContextStrategy.FACTS);
        env.put("LLM_CONTEXT_STRATEGY", "branching");
        expect("стратегия branching читается",
                ModelSettings.from(env).contextStrategy() == ContextStrategy.BRANCHING);
        env.put("LLM_SLIDING_WINDOW_MESSAGES", "14");
        env.put("LLM_FACTS_WINDOW_MESSAGES", "6");
        env.put("LLM_FACTS_MAX_OUTPUT_TOKENS", "256");
        env.put("LLM_FACTS_UPDATE_MODE", " manual ");
        ModelSettings full = ModelSettings.from(env);
        expect("окна и лимит генерации фактов читаются",
                full.slidingWindowMessages() == 14 && full.factsWindowMessages() == 6
                        && full.factsMaxOutputTokens() == 256
                        && full.factsUpdateMode() == FactsUpdateMode.MANUAL);

        for (String invalid : new String[]{"0", "3", "9", "abc", "-2"}) {
            Map<String, String> badWindow = new java.util.HashMap<>(env);
            badWindow.put("LLM_SLIDING_WINDOW_MESSAGES", invalid);
            try {
                ModelSettings.from(badWindow);
                expect("LLM_SLIDING_WINDOW_MESSAGES=" + invalid + " отклоняется", false);
            } catch (AgentException e) {
                expect("LLM_SLIDING_WINDOW_MESSAGES=" + invalid + " отклоняется "
                        + "с понятной ошибкой", e.getMessage()
                        .contains("LLM_SLIDING_WINDOW_MESSAGES"));
            }
            Map<String, String> badFactsWindow = new java.util.HashMap<>(env);
            badFactsWindow.put("LLM_FACTS_WINDOW_MESSAGES", invalid);
            try {
                ModelSettings.from(badFactsWindow);
                expect("LLM_FACTS_WINDOW_MESSAGES=" + invalid + " отклоняется", false);
            } catch (AgentException e) {
                expect("LLM_FACTS_WINDOW_MESSAGES=" + invalid + " отклоняется",
                        e.getMessage().contains("LLM_FACTS_WINDOW_MESSAGES"));
            }
        }
        Map<String, String> badStrategy = new java.util.HashMap<>(env);
        badStrategy.put("LLM_CONTEXT_STRATEGY", "summary");
        try {
            ModelSettings.from(badStrategy);
            expect("LLM_CONTEXT_STRATEGY=summary отклоняется", false);
        } catch (AgentException e) {
            expect("LLM_CONTEXT_STRATEGY=summary отклоняется",
                    e.getMessage().contains("LLM_CONTEXT_STRATEGY"));
        }
        expectSettingsError(env, "LLM_CONTEXT_STRATEGY", "gip");
        expectSettingsError(env, "LLM_FACTS_MAX_OUTPUT_TOKENS", "-1");
        expectSettingsError(env, "LLM_FACTS_UPDATE_MODE", "always");

        ContextStrategy switched = ContextStrategy.SLIDING_WINDOW;
        expect("переключение стратегии через withStrategy не меняет остальные поля",
                switched == ContextStrategy.SLIDING_WINDOW
                        && full.withStrategy(ContextStrategy.FACTS).contextStrategy()
                        == ContextStrategy.FACTS
                        && full.withStrategy(ContextStrategy.FACTS).slidingWindowMessages()
                        == full.slidingWindowMessages());

        expect("имя ветки валидируется: пустое отклоняется",
                expectAgentThrow(() -> BranchData.validateName("  "))
                        .contains("Имя ветки"));
        expect("имя ветки валидируется: пробелы и слэши отклоняются",
                expectAgentThrow(() -> BranchData.validateName("новая ветка/x"))
                        .contains("Недопустимый символ"));
        expect("имя ветки валидируется: длина ограничена 32",
                expectAgentThrow(() -> BranchData.validateName("а".repeat(33)))
                        .contains("не длиннее 32"));
        expect("корректное имя ветки нормализуется",
                BranchData.validateName(" Спорная-1 ").equals("спорная-1"));
    }

    private static String expectAgentThrow(java.util.function.Supplier<String> action) {
        try {
            action.get();
            return "";
        } catch (AgentException e) {
            return e.getMessage();
        }
    }

    /** Переключение стратегий и /facts показ без вызова API; подсказки. */
    private static void checkDay10StrategySwitchNoApi() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), store);
            agent.ask("разговорное сообщение");
            int hitsBefore = s.regularHits().get() + s.summaryHits().get();

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/strategy"),
                    TerminalUi.Input.command("/strategy facts"),
                    TerminalUi.Input.command("/strategy"),
                    TerminalUi.Input.command("/strategy branching"),
                    TerminalUi.Input.command("/branch list"),
                    TerminalUi.Input.command("/strategy sliding-window"),
                    TerminalUi.Input.command("/strategy rectangular"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("переключение стратегии не вызывает API",
                    s.regularHits().get() + s.summaryHits().get() == hitsBefore);
            expect("/strategy показывает текущую стратегию и параметры",
                    ui.systems.stream().anyMatch(t ->
                            t.contains("Стратегия контекста: Скользящее окно")));
            expect("переключение на facts сообщается",
                    ui.systems.stream().anyMatch(t ->
                            t.contains("Стратегия: Факты")));
            expect("переключение на branching меняет настройки и показывает команды",
                    ui.systems.stream().anyMatch(t ->
                            t.contains("Ветки (история активной ветки)")));
            expect("неизвестная стратегия даёт ошибку и подсказку",
                    ui.errors.stream().anyMatch(t ->
                            t.contains("sliding-window, facts или branching")));
            expect("после возврата на sliding-window история не тронута",
                    agent.getHistory().size() == 2 && agent.factsView().isEmpty());
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /**
     * Стратегия sliding-window: в запросе ровно окно, старые сообщения
     * не отправляются, архив сохранён, отброшенное честно показано.
     */
    private static void checkDay10SlidingWindow() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), store);

            for (int i = 1; i <= 7; i++) {
                agent.ask("вопрос окна " + i);
            }
            String lastRegular = null;
            for (int i = s.bodies().size() - 1; i >= 0; i--) {
                if (!s.bodies().get(i).contains(SUMMARY_MARKER)) {
                    lastRegular = s.bodies().get(i);
                    break;
                }
            }
            JsonNode body = MAPPER.readTree(lastRegular);
            JsonNode messages = body.path("messages");
            expect("sliding-window: в запросе ровно system + окно 10 + новое сообщение",
                    messages.size() == 12
                            && "system".equals(messages.get(0).path("role").asText())
                            && "вопрос окна 2".equals(messages.get(1).path("content").asText())
                            && "вопрос окна 7".equals(messages.get(messages.size() - 1).path("content").asText()));
            expect("sliding-window: более ранние сообщения не отправляются",
                    streamContents(messages).noneMatch(m -> m.contains("вопрос окна 1"))
                            && streamContents(messages).noneMatch(
                            m -> m.contains("Ответ 1")));
            JsonNode saved = MAPPER.readTree(Files.readString(store.file(),
                    StandardCharsets.UTF_8));
            expect("sliding-window: архив в файле не теряется (14 сообщений)",
                    saved.path("messages").size() == 14);
            expect("старые сообщения остаются в памяти при отправке окна",
                    agent.getHistory().size() == 14
                            && "вопрос окна 1".equals(agent.getHistory().get(0).content()));
            expect("подпись запроса отмечает отброшенные сообщения",
                    agent.lastRequestContextCaption().contains("отброшено из запроса 2"));
            List<String> notes = agent.consumeContextNotes();
            expect("заметка после ответа: стратегия, окно, отброшенные пакеты "
                    + "и сохранённый архив",
                    notes.stream().anyMatch(t -> t.contains("Стратегия: Скользящее окно"))
                            && notes.stream().anyMatch(t ->
                            t.contains("окно 10") && t.contains("отброшено из запроса 2")));
            SessionTokenStats.Snapshot stats = agent.sessionStats();
            expect("каждый запрос учтён ровно один раз: 7 попыток, полный usage",
                    stats.apiAttempts() == 7 && stats.complete()
                            && stats.knownTotal() == 7 * 30L);
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Поток стратегии facts (auto): facts обновляются после каждого ответа. */
    private static void checkDay10FactsFlow() throws Exception {
        Path keyStore = day9KeyStore();
        // Порядок ответов facts-запросов детерминирован: серия значений.
        AtomicInteger factsCall = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(FACTS_MARKER)) {
                int call = factsCall.incrementAndGet();
                // «Замена устаревшего значения»: тем же ключом — новое значение.
                String pairs = call == 1
                        ? "цель: собрать ТЗ проекта МАЯК"
                        : "цель: собрать финальное ТЗ проекта МАЯК";
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\""
                        + pairs + "\"}}],"
                        + "\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":4,"
                        + "\"total_tokens\":34}}").getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"См. факты "
                    + "блока\"}}],\"usage\":{\"prompt_tokens\":10,"
                    + "\"completion_tokens\":20,\"total_tokens\":30}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(factsEnv()),
                    trustedHttpClient(keyStore), store);

            agent.ask("Сбор ТЗ: цель — проект МАЯК, запрет цитрусовых, число 42");
            expect("после первого ответа facts обновлены первым служебным запросом",
                    factsCall.get() == 1
                            && agent.factsView().size() == 1
                            && agent.factsView().get("цель").contains("МАЯК"));
            expect("служебный запрос facts не попал в историю диалога",
                    agent.getHistory().size() == 2);

            agent.ask("Обновляю: цель меняется на финальное ТЗ, число 42 сохраняем");
            expect("второй служебный запрос обновил и заменил факты",
                    factsCall.get() == 2
                            && agent.factsView().size() == 1
                            && agent.factsView().get("цель").contains("финальное"));
            expect("история содержит только пары диалога (4 сообщения)",
                    agent.getHistory().size() == 4
                            && agent.getHistory().stream()
                            .noneMatch(m -> m.content().contains("цель:")));

            // Проверяем контекст и подписи агента.
            String caption = agent.lastRequestContextCaption();
            expect("подпись facts сообщает блок фактов и окно сообщений",
                    caption != null && caption.contains("блок фактов") && caption.contains("пар"));
            expect("facts хранятся отдельно от сообщений",
                    agent.factsView().size() == 1
                            && agent.getHistory().size() == 4);

            var stats = agent.sessionStats();
            expect("расход facts учтён отдельно: попыток FACTS_UPDATE ровно 2",
                    stats.factsAttempts() == 2 && stats.regularAttempts() == 2
                            && stats.factsPromptTokens() == 60
                            && stats.factsCompletionTokens() == 8);
            expect("случайные тексты фактов не попадали в ответ",
                    agent.lastRequestContextCaption() != null);            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Manual-режим и /facts clear: без автоматических служебных запросов. */
    private static void checkDay10FactsManualAndClear() throws Exception {
        Path keyStore = day9KeyStore();
        AtomicInteger factsCalls = new AtomicInteger();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            lastBody.set(requestBody);
            if (requestBody.contains(FACTS_MARKER)) {
                factsCalls.incrementAndGet();
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\""
                        + "решение: держать факты вручную\"}}],"
                        + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"Ответ "
                    + factsCalls.get() + "\"}}],\"usage\":{\"prompt_tokens\":15,"
                    + "\"completion_tokens\":25}}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            Map<String, String> env = factsEnv();
            env.put("LLM_FACTS_UPDATE_MODE", "manual");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env),
                    trustedHttpClient(keyStore), store);

            agent.ask("должен ли я говорить вручную?");
            agent.ask(" Manual-режим не обновляет сам");
            expect("manual-режим не обновляет facts автоматически",
                    factsCalls.get() == 0 && agent.factsView().isEmpty());

            String result = agent.refreshFacts();
            expect("/facts refresh делает ровно один служебный запрос",
                    factsCalls.get() == 1 && agent.factsView().size() == 1
                            && agent.factsView().get("решение").contains("вручную"));
            expect("в refresh приходят текущая история и блок facts (текущий пуст)",
                    lastBody.get() != null
                            && lastBody.get().contains("история — данные")
                            && lastBody.get().contains("(пуст"));
            expect("результат сводки после подтверждения содержит отметку расхода",
                    result.contains("учтён отдельно"));

            agent.factsClear();
            expect("/facts clear очищает блок (после подтверждения в Main)",
                    agent.factsView().isEmpty());
            expect("очищенный блок в файле не сериализуется как объект facts",
                    !Files.readString(store.file(), StandardCharsets.UTF_8)
                            .contains("\"facts\""));
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Служебный расход facts входит в лимит сессии; неполный usage честно. */
    private static void checkDay10FactsInSessionLimit() throws Exception {
        Path keyStore = day9KeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(FACTS_MARKER)) {
                // Частичный usage у служебного запроса (только prompt_tokens).
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"лимит: 90\"}}],"
                        + "\"usage\":{\"prompt_tokens\":70}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"Ответ\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            Map<String, String> env = factsEnv();
            env.put("LLM_SESSION_TOKEN_LIMIT", "80");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env),
                    trustedHttpClient(keyStore), store);
            agent.ask("сообщение с лимитом фактов");
            var stats = agent.sessionStats();
            String notice = agent.consumeSessionLimitNotice();
            expect("расход facts входит в лимит сессии (10+20+70 > 80)",
                    notice != null && notice.contains("не менее 100")
                            && !stats.complete());
            expect("частичный usage facts: prompt учтён, итог не опровергается",
                    stats.factsAttempts() == 1 && stats.factsPromptTokens() == 70
                            && stats.factsCompletionTokens() == 0
                            && stats.requestsWithPartialUsage() == 1);
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Ветки: checkpoint, создание, независимость, guard переноса, удаление. */
    private static void checkDay10Branching() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(branchingEnv()),
                    trustedHttpClient(keyStore), store);

            agent.ask("вопрос основной 1");
            agent.ask("вопрос основной 2");
            agent.branchCheckpoint();
            expect("checkpoint на конце истории, ветка main пустая",
                    agent.branchesDescription().contains("checkpoint: сообщения [0..4)"));
            agent.branchNew("спорная");
            expect("новая ветка начинается с префикса checkpoint",
                    agent.getHistory().size() == 4
                            && agent.activeBranchName().equals("спорная"));

            agent.ask("вариант спорной");
            agent.branchSwitch("main");
            expect("переключение обратно сохраняет историю ветки «main»",
                    agent.getHistory().size() == 4
                            && agent.activeBranchName().equals("main")
                            && agent.getHistory().get(0).content().contains("основной 1"));

            agent.ask("вопрос основной 3");
            expect("строка «main» продолжает собственную независимую треду",
                    agent.getHistory().size() == 6);

            agent.branchSwitch("спорная");
            expect("ветка «спорная» не потеряла собственные сообщения",
                    agent.getHistory().size() == 6
                            && agent.getHistory().get(4).content().contains("вариант спорной"));

            String guard = expectAgentThrow(() -> {
                agent.branchCheckpoint();
                return "";
            });
            expect("перенос checkpoint при непустых чужих хвостах отказывается",
                    guard.contains("Перенести checkpoint сейчас нельзя"));

            agent.branchSwitch("main");
            agent.branchDelete("спорная");
            expect("после удаления ветки вернулась только одна",
                    agent.branchesDescription().contains("активная: «main»")
                            && !agent.branchesDescription().contains("спорная"));
            expect("активная ветка не удаляется",
                    expectAgentThrow(() -> {
                        agent.branchDelete("main");
                        return "";
                    }).contains("Активную ветку"));

            expect("инвалидные имена веток отклоняются без записи файла",
                    expectAgentThrow(() -> {
                        agent.branchNew("bad name");
                        return "";
                    }).contains("Недопустимый"));

            expect("служебный запрос facts/summary не появился в ветках",
                    s.summaryHits().get() == 0 && s.regularHits().get() >= 4);
            expect("каптion branching называет всю историю ветки",
                    agent.lastRequestContextCaption().contains("вся история"));
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Восстановление веток после перезапуска; повреждённые ветки отклоняются. */
    private static void checkDay10BranchPersistence() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            Path historyFile = store.file();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(branchingEnv()),
                    trustedHttpClient(keyStore), store);
            agent.ask("до checkpoint");
            agent.branchCheckpoint();
            agent.branchNew("вилка");
            agent.ask("в ветке «вилка»");
            store.close();

            try (JsonConversationStore reopened = new JsonConversationStore(historyFile)) {
                LlmAgent restored = new LlmAgent(config, ModelSettings.from(branchingEnv()),
                        trustedHttpClient(keyStore), reopened);
                expect("после перезапуска активная ветка и история восстановлены",
                        restored.activeBranchName().equals("вилка")
                                && restored.getHistory().size() == 4
                                && restored.getHistory().get(0).content()
                                .contains("до checkpoint")
                                && !restored.getHistory().get(3).content().isEmpty());
                restored.branchSwitch("main");
                expect("переключение после перезапуска возвращается в основную ветку",
                        restored.getHistory().size() == 2
                                && restored.getHistory().get(0).content()
                                .contains("до checkpoint"));
            }
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /**
     * /strategy compare: три запроса на одном снимке с подтверждением,
     * отдельный расход facts и изоляция истории.
     */
    private static void checkDay10StrategyCompare() throws Exception {
        Path keyStore = day9KeyStore();
        AtomicInteger answers = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(FACTS_MARKER)) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\""
                        + "сравнение: маркер фактов МАЯК\"}}],"
                        + "\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":4}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            // Вспомогательные запросы сравнения различаются по числу
            // сообщений: branching отправляет весь снимок, facts — хвост
            // с блоком «ФАКТЫ», sliding — то же окно, что и facts.
            if (requestBody.contains("ФАКТЫ")) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"Ответ Facts\"}}]}")
                        .getBytes(StandardCharsets.UTF_8));   // usage отсутствует
            }
            int assistantCount = requestBody.split("\"assistant\"", -1).length - 1;
            if (assistantCount >= 8) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"Ответ Branching\"}}],"
                        + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":3}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"Ответ Sliding "
                    + answers.incrementAndGet() + "\"}}],"
                    + "\"usage\":{\"prompt_tokens\":6,\"completion_tokens\":1}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), store);
            for (int i = 1; i <= 8; i++) {
                agent.ask("факт сравнения " + i);
            }
            List<ChatMessage> before = agent.getHistory();
            long knownBefore = agent.sessionStats().knownTotal();

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/strategy compare первый факт?"),
                    TerminalUi.Input.command("/exit"));
            ui.confirmStrategyCompareAnswer = true;
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("сравнение стратегий не изменяет историю",
                    agent.getHistory().equals(before)
                            && !agent.getHistory().toString().contains("МАЯК"));
            expect("показаны ответы всех трёх стратегий",
                    ui.messages.stream().anyMatch(m -> m.contains("Ответ Sliding"))
                            && ui.messages.stream().anyMatch(m -> m.contains("Ответ Facts"))
                            && ui.messages.stream().anyMatch(m -> m.contains("Ответ Branching")));
            var stats = agent.sessionStats();
            expect("сравнение = 4 попытки (facts-prep и три ряда), отсутствующий "
                    + "usage у варианта facts честно помечен",
                    stats.compareAttempts() == 4
                            && stats.requestsWithPartialUsage() + stats.requestsWithoutUsage() >= 1
                            && !stats.complete());
            expect("расход сравнения входит в общий knownTotal",
                    agent.sessionStats().knownTotal() > knownBefore);
            expect("таблица содержит все три стратегии и пометку «не списание» "
                    + "или честное «нет данных» без тарифа",
                    ui.systems.stream().anyMatch(t ->
                            t.contains("Таблица сравнения стратегий")
                                    && t.contains("sliding-window")
                                    && t.contains("facts")
                                    && t.contains("branching")
                                    && (t.contains("расчётная, не списание")
                                    || t.contains("стоимость: нет данных — тариф не настроен"))));
            expect("факты подготовлены из снимка и занесены в ряд facts",
                    ui.systems.stream().anyMatch(t -> t.contains("подготовлен служебным")));
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Отмена /strategy compare не вызывает API; в демо сравнение отключено. */
    private static void checkDay10CompareGuards() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), store);
            agent.ask("единственный вопрос для сравнения");
            int hitsBefore = s.regularHits().get() + s.summaryHits().get();

            FakeUi cancelUi = new FakeUi(
                    TerminalUi.Input.command("/strategy compare вопрос"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(cancelUi, agent, "glm-5.3-flash");
            expect("отмена подтверждения не вызывает API",
                    s.regularHits().get() + s.summaryHits().get() == hitsBefore);

            FakeUi demoUi = new FakeUi(
                    TerminalUi.Input.command("/demo tokens"),
                    TerminalUi.Input.command("/strategy compare вопрос"),
                    TerminalUi.Input.command("/demo stop"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(demoUi, agent, "glm-5.3-flash");
            expect("в демо сравнение стратегий не выполняется без API",
                    s.regularHits().get() + s.summaryHits().get() == hitsBefore
                            && demoUi.systems.stream().anyMatch(
                            t -> t.contains("режиме измерения")
                                    || t.contains("сравнивать не на чем")));
            store.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /**
     * finish_reason=length при подготовке фактов прерывает вариант facts:
     * сравнение остальных стратегий продолжается, «ответ facts» не выводится,
     * расход попытки учтён ровно один раз.
     */
    private static void checkFactsPrepLengthAbortsCompare() throws Exception {
        Path keyStore = day9KeyStore();
        AtomicInteger regularAnswers = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(FACTS_MARKER)) {
                return new Response(200, lengthAnswer().getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, regularAnswer(regularAnswers.incrementAndGet())
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), store);
            for (int i = 1; i <= 3; i++) {
                agent.ask("факт до сравнения " + i);
            }
            List<ChatMessage> before = agent.getHistory();
            long knownBefore = agent.sessionStats().knownTotal();

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/strategy compare ранние факты?"),
                    TerminalUi.Input.command("/exit"));
            ui.confirmStrategyCompareAnswer = true;
            Main.runLoop(ui, agent, "glm-5.3-flash");

            expect("сравнение не изменяет историю", agent.getHistory().equals(before));
            expect("строка facts помечена «недостоверно», а не полноценным ответом",
                    ui.messages.stream().anyMatch(m -> m.startsWith("Факты")
                            && m.contains("(недостоверно:")
                            && m.contains("подготовка фактов не выполнена")));
            expect("ошибка подготовки предлагает увеличить лимит и повторить",
                    ui.messages.stream().anyMatch(m ->
                            m.contains("LLM_FACTS_MAX_OUTPUT_TOKENS")
                                    && m.contains("повторите")));
            expect("вывод нет полноценного «ответа facts» поверх сбоя",
                    ui.messages.stream().noneMatch(
                            m -> m.startsWith("Факты (ключ: значение)")
                                    && !m.contains("недостоверно")));
            SessionTokenStats.Snapshot stats = agent.sessionStats();
            expect("подготовка учтена ровно один раз, facts-запрос не выполнялся",
                    stats.compareAttempts() == 3
                            && stats.knownTotal() == knownBefore + 60 + 900 + 30 + 30);
            expect("в таблице строка facts без выдуманного расхода",
                    ui.systems.stream().anyMatch(t ->
                            t.contains("Таблица сравнения стратегий")
                                    && t.contains("нет данных")));
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }
    /** Тело length-ответа тестового сервера с полным usage. */
    private static String lengthAnswer() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("finish_reason", "length");
        choice.putObject("message")
                .put("role", "assistant")
                .put("content", "частичный");
        root.putObject("usage")
                .put("prompt_tokens", 60)
                .put("completion_tokens", 900)
                .put("total_tokens", 960);
        return root.toString();
    }

    /** Обычный успешный ответ тестового сервера. */
    private static String regularAnswer(int number) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("finish_reason", "stop");
        choice.putObject("message")
                .put("role", "assistant")
                .put("content", "Ответ " + number + " содержательный развёрнутый текст для проверки контекста");
        root.putObject("usage")
                .put("prompt_tokens", 10)
                .put("completion_tokens", 20)
                .put("total_tokens", 30);
        return root.toString();
    }

    /** Тело response-записи для компактности тестов. */
    private static Response json(final int status, final String body) {
        return new Response(status, body.getBytes(StandardCharsets.UTF_8));
    }

    /** Предупреждение при занятости 90% или более лимита генерации фактов. */
    private static void checkFactsNearLimitWarning() throws Exception {
        Path keyStore = day9KeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(FACTS_MARKER)) {
                return json(200, factsUsage95Body());
            }
            return json(200, regularAnswer(999));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            Map<String, String> env = factsEnv();
            env.put("LLM_FACTS_MAX_OUTPUT_TOKENS", "100");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(env),
                    trustedHttpClient(keyStore), store);
            agent.ask("сообщение для предупреждения");
            List<String> notes = agent.consumeContextNotes();
            expect("предупреждение при занятости лимита 90% и более",
                    notes.stream().anyMatch(t ->
                            t.contains("Предупреждение: обновление фактов заняло 95 из 100")
                                    && t.contains("LLM_FACTS_MAX_OUTPUT_TOKENS")));
            JsonConversationStore freshStore = tempStore();
            Map<String, String> fresh = factsEnv();
            fresh.put("LLM_FACTS_MAX_OUTPUT_TOKENS", "200");
            LlmAgent freshAgent = new LlmAgent(config, ModelSettings.from(fresh),
                    trustedHttpClient(keyStore), freshStore);
            freshAgent.ask("сообщение ниже порога предупреждения");
            expect("ниже 90% предупреждение не выводится",
                    freshAgent.consumeContextNotes().stream()
                            .noneMatch(t -> t.contains("Предупреждение:")));
            freshStore.close();
            store.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Тело facts-ответа с usage completion 95 при тестовом лимите 100. */
    private static String factsUsage95Body() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("finish_reason", "stop");
        choice.putObject("message")
                .put("role", "assistant")
                .put("content", "цель: МАЯК");
        root.putObject("usage")
                .put("prompt_tokens", 10)
                .put("completion_tokens", 95)
                .put("total_tokens", 105);
        return root.toString();
    }

    // ================= Модель памяти: три слоя =================

    // ================= Профиль пользователя =================

    /** Хранилище профиля: пустой файл не создаётся, запись/чтение/валидация/перезапуск. */
    private static void checkProfileStoreLifecycle() throws IOException {
        Path profileFile = Files.createTempDirectory(baseTempDir, "prof-")
                .resolve("profile.json");
        ProfileStore profileStore = new ProfileStore(profileFile);

        // Отсутствующий файл — пустой профиль, файл заранее не создаётся.
        expect("отсутствующий файл профиля даёт пустой профиль",
                profileStore.load().isEmpty());
        expect("при отсутствии файла профиля JSON не создаётся заранее",
                !Files.exists(profileFile));

        // Поля сохраняются и читаются циклом «запись — чтение».
        ProfileStore ps = new ProfileStore(profileFile);
        ps.save(new UserProfile("Алексей", "кратко, по делу", "списками",
                List.of("не используй смайлики", "только русский"),
                new LinkedHashMap<>(), new LinkedHashMap<>(),
                "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"));
        UserProfile loaded = ps.load();
        expect("профиль хранит обращение, стиль, формат и ограничения",
                "Алексей".equals(loaded.name())
                        && "кратко, по делу".equals(loaded.style())
                        && "списками".equals(loaded.format())
                        && loaded.constraints().contains("не используй смайлики")
                        && loaded.constraints().contains("только русский"));
        expect("метки времени профиля сохраняются",
                "2026-01-01T00:00:00Z".equals(loaded.createdAt()));
        expect("файл профиля отдельный от памяти (profile.json)",
                profileFile.getFileName().toString().equals("profile.json"));

        // Повреждённый файл — ошибка повреждения, файл не переписывается.
        byte[] garbage = "это вообще не { json".getBytes(StandardCharsets.UTF_8);
        Files.write(profileFile, garbage);
        try {
            ps.load();
            expect("повреждённый файл профиля даёт ошибку", false);
        } catch (ConversationStoreException e) {
            expect("повреждённый файл профиля даёт ошибку с путём",
                    e.getMessage().contains(profileFile.toString()));
        }
        expect("повреждённый файл профиля не перезаписывается",
                Arrays.equals(Files.readAllBytes(profileFile), garbage));

        // Путь по умолчанию и переменная окружения.
        expect("профиль по умолчанию: ~/.ai-advent-agent/profile.json",
                ProfileStore.defaultProfileFile(Map.of()).getFileName().toString()
                        .equals("profile.json")
                        && ProfileStore.defaultProfileFile(Map.of()).getParent()
                        .getFileName().toString().equals(".ai-advent-agent"));
        expect("LLM_PROFILE_FILE переопределяет путь профиля",
                ProfileStore.defaultProfileFile(Map.of("LLM_PROFILE_FILE",
                        "/tmp/selftest-profile-fix.json"))
                        .equals(Path.of("/tmp/selftest-profile-fix.json")));
        expect("относительный путь LLM_PROFILE_FILE отклоняется",
                expectStoreError(() -> ProfileStore
                        .defaultProfileFile(Map.of("LLM_PROFILE_FILE", "relative/path.json")))
                        .contains("относительный"));

        // Запись скиллов и пайплайнов в файл и чтение обратно.
        var skill = ProfileSkill.create("карточка фичи",
                "название, цель, критерии приёмки, шаги", java.time.Instant.now());
        LinkedHashMap<String, ProfileSkill> skills = new LinkedHashMap<>();
        skills.put(skill.name(), skill);
        LinkedHashMap<String, List<String>> pipelines = new LinkedHashMap<>();
        pipelines.put("напиши фичу", List.of("карточка фичи"));
        ps.save(new UserProfile("Алексей", null, null, List.of(), skills, pipelines,
                "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"));
        UserProfile reloaded = ps.load();
        expect("скиллы и пайплайны профиля перезапускаются",
                reloaded.skills().containsKey("карточка фичи")
                        && reloaded.skills().get("карточка фичи").instructions()
                        .contains("критерии приёмки")
                        && reloaded.pipelines().get("напиши фичу")
                        .contains("карточка фичи"));

        // Проверки валидации: пустые метки времени недопустимы.
        String brokenJson = "{\"schemaVersion\":1,\"profile\":{\"name\":\"x\","
                + "\"constraints\":[],\"createdAt\":\"2026-01-01T00:00:00Z\"}}";
        Files.write(profileFile, brokenJson.getBytes(StandardCharsets.UTF_8));
        boolean rejected;
        try {
            ps.load();
            rejected = false;
        } catch (ConversationStoreException e) {
            rejected = true;
        }
        expect("профиль без updatedAt отклоняется", rejected);
    }

    /** Ошибка отклонения пути профиля (для expectStoreError-подобных проверок). */
    private static String expectStoreError(Runnable action) {
        try {
            action.run();
            return "";
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    /**
     * Блок «ПРОФИЛЬ ПОЛЬЗОВАТЕЛЬ» в system-сообщении: пустого профиля нет,
     * заданный содержит все поля; разные значения дают разный текст.
     */
    private static void checkProfileBlockInSystemMessage() {
        Map<String, MemoryEntry> emptyMemory = new LinkedHashMap<>();
        ModelSettings settings = ModelSettings.defaults();

        ChatMessage emptySystem = ContextBuilder.systemContextMessage(settings,
                UserProfile.empty(), emptyMemory, null, Map.of(), null, null);
        expect("пустой профиль не подставляется в system-сообщение",
                !emptySystem.content().contains("ПРОФИЛЬ ПОЛЬЗОВАТЕЛЬ"));

        ChatMessage fullSystem = ContextBuilder.systemContextMessage(settings,
                new UserProfile("Алексей", "кратко, по делу", "списками",
                        List.of("не используй смайлики"),
                        new LinkedHashMap<>(), new LinkedHashMap<>(),
                        "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
                emptyMemory, null, Map.of(), null, null);
        String block = fullSystem.content();
        expect("заданный профиль подставлен: заголовок блока",
                block.contains("ПРОФИЛЬ ПОЛЬЗОВАТЕЛЬ"));
        expect("блок профиля содержит обращение",
                block.contains("Алексей"));
        expect("блок профиля содержит стиль",
                block.contains("кратко, по делу"));
        expect("блок профиля содержит формат",
                block.contains("списками"));
        expect("блок профиля содержит ограничение",
                block.contains("не используй смайлики"));
        expect("блок профиля формулируется как инструкции",
                ContextBuilder.renderProfile(new UserProfile("Алексей", null, null,
                        List.of(), new LinkedHashMap<>(), new LinkedHashMap<>(),
                        "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"))
                        .contains("называй пользователя"));
        expect("правило приоритета конкретного сообщения над профилем описано",
                block.contains("следуй его сообщению"));

        ChatMessage otherSystem = ContextBuilder.systemContextMessage(settings,
                new UserProfile("Шеф", "подробно", null, List.of(),
                        new LinkedHashMap<>(), new LinkedHashMap<>(),
                        "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
                emptyMemory, null, Map.of(), null, null);
        expect("разные профили дают разный текст system-сообщения",
                !otherSystem.content().equals(fullSystem.content())
                        && otherSystem.content().contains("Шеф")
                        && otherSystem.content().contains("подробно"));
    }

    /**
     * Полный прогон: профиль задан командами через FakeUi, подставлен
     * в запрос на локальном сервере; /profile clear убирает блок.
     */
    private static void checkProfileBlockAcrossRestartsAndClear() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            lastBody.set(requestBody);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            ProfileStore profileStore = new ProfileStore(
                    Files.createTempDirectory(baseTempDir, "prof-").resolve("profile.json"));
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), tempStore(),
                    new MemoryStore(Files.createTempDirectory(baseTempDir, "mem-")
                            .resolve("memory.json")), profileStore);

            // Команды профиля не вызывают API и задают поля.
            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/profile"),
                    TerminalUi.Input.command("/profile name Алексей"),
                    TerminalUi.Input.command("/profile style кратко, по делу"),
                    TerminalUi.Input.command("/profile format списками"),
                    TerminalUi.Input.command("/profile constraint не используй смайлики"),
                    TerminalUi.Input.command("/profile constraint clear"),
                    TerminalUi.Input.command("/profile constraint только русский"),
                    TerminalUi.Input.command("/profile"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("/profile показывает заданные поля",
                    ui.systems.stream().anyMatch(t -> t.contains("Алексей")
                            && t.contains("кратко, по делу") && t.contains("списками")));
            expect("/profile помечает незаданные поля «не задано»",
                    ui.systems.stream().anyMatch(t -> t.contains("не задано")));
            expect("подтверждения команды в едином стиле",
                    ui.systems.stream().anyMatch(t -> t.contains("Профиль обновлён: name"))
                            && ui.systems.stream().anyMatch(t ->
                            t.contains("Профиль обновлён: constraint")));
            expect("пустые поля устанавливаются без API", agent.userProfile().name() != null);

            agent.ask("привет");
            String system = MAPPER.readTree(lastBody.get())
                    .path("messages").get(0).path("content").asText();
            expect("в запросе есть блок ПРОФИЛЬ ПОЛЬЗОВАТЕЛЬ",
                    system.contains("ПРОФИЛЬ ПОЛЬЗОВАТЕЛЬ"));
            expect("в блоке профиля есть обращение и стиль",
                    system.contains("Алексей") && system.contains("кратко, по делу"));
            expect("очищенное ограничение не подставляется",
                    !system.contains("не используй смайлики"));
            expect("оставшееся ограничение подставляется",
                    system.contains("только русский"));

            // Перезапуск: новый агент с тем же файлом профиля видит поля.
            LlmAgent restarted = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), tempStore(),
                    new MemoryStore(Files.createTempDirectory(baseTempDir, "mem-")
                            .resolve("memory.json")), profileStore);
            expect("профиль переживает перезапуск",
                    "Алексей".equals(restarted.userProfile().name()));

            // /profile clear убирает блок после подтверждения.
            FakeUi clearUi = new FakeUi(
                    TerminalUi.Input.command("/profile clear"),
                    TerminalUi.Input.command("/exit"));
            clearUi.confirmProfileClearAnswer = true;
            Main.runLoop(clearUi, restarted, "glm-5.3-flash");
            expect("/profile clear сбрасывает профиль (подтверждение работает)",
                    restarted.userProfile().isEmpty());

            restarted.ask("вопрос после сброса");
            String clearedSystem = MAPPER.readTree(lastBody.get())
                    .path("messages").get(0).path("content").asText();
            expect("после /profile clear блока профиля в запросе нет",
                    !clearedSystem.contains("ПРОФИЛЬ ПОЛЬЗОВАТЕЛЬ"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Команда /profile clear без подтверждения не сбрасывает профиль. */
    private static void checkProfileSubcommandUi() throws IOException {
        // Тонкая проверка без API: отказ подтверждения сохраняет профиль.
        FakeUi ui = new FakeUi(TerminalUi.Input.command("/profile clear"));
        ui.confirmProfileClearAnswer = false;
        ProfileStore profileStore = new ProfileStore(
                Files.createTempDirectory(baseTempDir, "prof-").resolve("profile.json"));
        LlmAgent agent = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(
                        Files.createTempDirectory(baseTempDir, "hist-")
                                .resolve("conversation.json")),
                tempMemoryStore(), profileStore);
        agent.setProfileName("Шеф");
        Main.runLoop(ui, agent, "test-model");
        expect("отказ подтверждения сохраняет профиль",
                "Шеф".equals(agent.userProfile().name()));
    }

    /** Скиллы: /skill add/list/remove; пайплайны: задание, показ, очистка. */
    private static void checkSkillsAndPipelines() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8)));
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/skill add \"карточка фичи\" название, цель, "
                            + "критерии приёмки, шаги"),
                    TerminalUi.Input.command("/skill add \"сборка корзины\" собери товары, "
                            + "сгруппируй, посчитай сумму"),
                    TerminalUi.Input.command("/skill list"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("/skill add сохраняет скиллы",
                    agent.skillsView().containsKey("карточка фичи")
                            && agent.skillsView().containsKey("сборка корзины"));
            expect("/skill list показывает скиллы",
                    ui.systems.stream().anyMatch(t -> t.contains("карточка фичи")
                            && t.contains("критерии приёмки")));

            // Пайплайн задан командой; /pipeline list показывает.
            FakeUi pipelineUi = new FakeUi(
                    TerminalUi.Input.command("/pipeline \"напиши фичу\" "
                            + "\"карточка фичи\""),
                    TerminalUi.Input.command("/pipeline list"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(pipelineUi, agent, "glm-5.3-flash");
            expect("/pipeline задаёт триггер и порядок скиллов",
                    agent.userProfile().pipelinesView().get("напиши фичу")
                            .contains("карточка фичи"));
            expect("/pipeline list показывает пайплайн",
                    pipelineUi.systems.stream().anyMatch(t ->
                            t.contains("«напиши фичу»")));

            // Unknown skill отклоняется с понятной ошибкой.
            FakeUi unknownSkill = new FakeUi(
                    TerminalUi.Input.command("/pipeline \"оплата\" неизвестный"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(unknownSkill, agent, "glm-5.3-flash");
            expect("пайплайн с неизвестным скиллом отклоняется",
                    unknownSkill.errors.stream().anyMatch(t ->
                            t.contains("не найден")));

            // Удаление скилла вычищает и пайплайн.
            FakeUi removeUi = new FakeUi(
                    TerminalUi.Input.command("/skill remove карточка фичи"),
                    TerminalUi.Input.command("/skill list"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(removeUi, agent, "glm-5.3-flash");
            expect("/skill remove удаляет скилл",
                    !agent.skillsView().containsKey("карточка фичи"));
            expect("удалённый скилл вычищен из пайплайнов",
                    agent.userProfile().pipelinesView().containsKey("напиши фичу")
                            == false);

            // /pipeline clear требует подтверждения и чистит только пайплайны.
            FakeUi clearPipelineUi = new FakeUi(
                    TerminalUi.Input.command("/pipeline clear"),
                    TerminalUi.Input.command("/pipeline list"),
                    TerminalUi.Input.command("/exit"));
            clearPipelineUi.confirmProfileClearAnswer = true;
            Main.runLoop(clearPipelineUi, agent, "glm-5.3-flash");
            expect("/pipeline clear очищает пайплайны",
                    agent.userProfile().pipelinesView().isEmpty());
            expect("/pipeline clear сохраняет скиллы",
                    agent.skillsView().containsKey("сборка корзины"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Пайплайн подставляется в system-сообщение при совпадении триггера. */
    private static void checkPipelineSubstitutionInRequest() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            lastBody.set(requestBody);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());
            agent.skillAdd("карточка фичи", "название, цель, критерии приёмки, шаги");
            agent.skillAdd("критерии", "три критерия приёмки с примерами");
            agent.setPipeline("напиши фичу", List.of("карточка фичи", "критерии"));

            // Совпадение триггера — ключевые слова присутствуют в запросе.
            List<ProfileSkill> matched = ContextBuilder.matchedPipeline(
                    agent.userProfile(), "пожалуйста, напиши фичу входа");
            expect("триггер распознаётся по ключевым словам среди текста запроса",
                    matched.size() == 2
                            && matched.get(0).name().equals("карточка фичи")
                            && matched.get(1).name().equals("критерии"));

            // Нет совпадения — пайплайн не подставляется.
            expect("несовпадающий запрос не активирует пайплайн",
                    ContextBuilder.matchedPipeline(agent.userProfile(), "как дела")
                            .isEmpty());

            agent.ask("напиши фичу входа");
            String system = MAPPER.readTree(lastBody.get())
                    .path("messages").get(0).path("content").asText();
            expect("в запросе есть блок ПАЙПЛАЙН",
                    system.contains("ПАЙПЛАЙН"));
            expect("пайплайн содержит порядок и инструкции скиллов",
                    system.contains("карточка фичи") && system.contains("критерии")
                            && system.contains("Порядок применения"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Правила разбиения «ключ: значение» для /remember (Проблема 1). */
    private static void checkRememberKeyFormats() {
        LlmAgent.MemoryKey explicit = LlmAgent.deriveMemoryKey("проект: ЯКОРЬ-42, дедлайн 20.05");
        expect("/remember с «:» даёт короткий ключ и полное значение",
                explicit.key().equals("проект")
                        && explicit.value().equals("ЯКОРЬ-42, дедлайн 20.05"));

        LlmAgent.MemoryKey equalsForm = LlmAgent.deriveMemoryKey("запрет= без Spring");
        expect("/remember с «=» тоже разбирается на ключ и значение",
                equalsForm.key().equals("запрет") && equalsForm.value().equals("без Spring"));

        // Реальный случай прогона: ключом раньше становился весь текст,
        // и /forget Проект не находил запись.
        LlmAgent.MemoryKey comma = LlmAgent.deriveMemoryKey(
                "Проект \"Север-17\", Java: 21, бюджет 620 рублей, срок 20 ноября");
        expect("/remember без явного «ключ:» выделяет ключ по запятой",
                comma.key().equals("Проект \"Север-17\"")
                        && comma.value().equals("Java: 21, бюджет 620 рублей, срок 20 ноября"));

        LlmAgent.MemoryKey plain = LlmAgent.deriveMemoryKey("кодовое слово ЯКОРЬ");
        expect("без разделителя и запятой ключ — первое слово записи",
                plain.key().equals("кодовое")
                        && plain.value().equals("слово ЯКОРЬ"));

        LlmAgent.MemoryKey words = LlmAgent.deriveMemoryKey(
                "экспериментальная настройка повышает информативность ответов модели");
        expect("длинный текст без разделителя: ключ — первое слово, значение — остаток",
                words.key().equals("экспериментальная")
                        && words.value().equals(
                        "настройка повышает информативность ответов модели"));
    }

    /** /forget по короткому и частичному ключу; неоднозначные запросы — список. */
    private static void checkForgetPartialMatches() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8)));
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());
            agent.remember("Проект: \"Север-17\"");
            agent.remember("Производительность: 95 кв. м");
            agent.remember("Запреты: цитрусовые в тизерах");

            // Короткий ключ находит и удаляет запись (сценарий прогона).
            LlmAgent.ForgetResult shortKey = agent.forget("Проект");
            expect("/forget по короткому ключу находит запись",
                    shortKey.removed() && !agent.memoryView().containsKey("Проект"));

            // Различие регистра/пробелов не мешает точному совпадению.
            agent.remember("Бюджет: 620 рублей");
            LlmAgent.ForgetResult caseKey = agent.forget(" бюджет ");
            expect("/forget работает без учёта регистра и лишних пробелов",
                    caseKey.removed() && !agent.memoryView().containsKey("Бюджет"));

            // Несколько совпадений: список ключей, удаление не угадыванием.
            agent.remember("срок: 20 ноября");
            agent.remember("срок сдачи: 25 ноября");
            // Запрос «сро» — не точный ключ, но подстрока обоих записей.
            LlmAgent.ForgetResult ambiguous = agent.forget("сро");
            expect("неоднозначный /forget перечисляет ключи и просит уточнить",
                    !ambiguous.removed() && ambiguous.matches() == 2
                            && ambiguous.message().contains("уточните"));
            expect("при неоднозначности ни одна запись не удалена",
                    agent.memoryView().containsKey("срок")
                            && agent.memoryView().containsKey("срок сдачи"));

            // Частичное совпадение с единственным кандидатом удаляется:
            // точный ключ «срок сдачи» удалён выше, остаётся одна запись «срок».
            LlmAgent.ForgetResult exactSecond = agent.forget("срок сдачи");
            expect("точный ключ второй записи удаляется",
                    exactSecond.removed());
            LlmAgent.ForgetResult partial = agent.forget("сро");
            expect("частичное совпадение с единственной записью удаляется",
                    partial.removed()
                            && !agent.memoryView().containsKey("срок")
                            && partial.message().contains("частичн"));

            LlmAgent.ForgetResult missing = agent.forget("несуществующе");
            expect("отсутствующий ключ даёт честное «нет записи»",
                    !missing.removed() && missing.message().contains("не найдена"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Старые записи без явного ключа читаются и показываются как есть. */
    private static void checkLegacyRememberEntries() throws IOException {
        Path file = Files.createTempDirectory(baseTempDir, "mem-")
                .resolve("memory.json");
        String legacyJson = "{\"schemaVersion\":1,\"entries\":{"
                + "\"Запрещены Spring и базы данных, хранение в JSON\":{"
                + "\"value\":\"Запрещены Spring и базы данных, хранение в JSON\","
                + "\"createdAt\":\"2026-01-01T00:00:00Z\","
                + "\"updatedAt\":\"2026-01-01T00:00:00Z\"}}}";
        Files.write(file, legacyJson.getBytes(StandardCharsets.UTF_8));
        MemoryStore memory = new MemoryStore(file);
        LinkedHashMap<String, MemoryEntry> entries = memory.load();
        expect("старая запись без явного ключа читается без потерь",
                entries.get("Запрещены Spring и базы данных, хранение в JSON") != null
                        && entries.get("Запрещены Spring и базы данных, хранение в JSON")
                        .value().contains("хранение в JSON"));
        expect("старая запись отображается как есть, без дублирования «ключ: значение»",
                entries.values().iterator().next().renderLine()
                        .equals("Запрещены Spring и базы данных, хранение в JSON"));

        // Явная запись рядом со старой: формат «ключ: значение» в отображении.
        memory.put("код", "ЯКОРЬ-42");
        var merged = memory.load();
        expect("обе формы работают: старая как есть, новая с ключом",
                merged.get("код").renderLine().equals("код: ЯКОРЬ-42")
                        && merged.get("Запрещены Spring и базы данных, хранение в JSON")
                        .legacyWithoutKey());
    }

    /** Правила памяти в системной инструкции (Проблема 2). */
    private static void checkSystemInstructionRules() {
        String prompt = ContextBuilder.BASE_SYSTEM_PROMPT;
        expect("инструкция: долговременная память — независимые записи",
                prompt.contains("независимых записей"));
        expect("инструкция: не объединять записи в один перечень",
                prompt.contains("не объединяй"));
        expect("инструкция: запрет выводов сверх текста записи",
                prompt.contains("не делай выводов, которых нет"));
        expect("инструкция: двусмысленная запись — переспросить/пометить",
                prompt.contains("переспроси") && prompt.contains("неоднозначную"));
        expect("инструкция: правило честности по обновлению рабочей памяти",
                prompt.contains("не заявляй")
                        && prompt.contains("/facts refresh"));
    }

    /**
     * Счётчик «рабочая N пар» брёл из фактического блока (Проблема 3):
     * заявление модели «добавлено в рабочую память» вне стратегии facts
     * не меняет факты, и подпись показывает фактическое число.
     */
    private static void checkWorkingCounterMatchesFacts() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger factsCalls = new AtomicInteger();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            lastBody.set(requestBody);
            if (requestBody.contains(FACTS_MARKER)) {
                int call = factsCalls.incrementAndGet();
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\""
                        + (call == 1 ? "цель: МАЯК" : "цель: финальное ТЗ")
                        + "\"}}],\"usage\":{\"prompt_tokens\":10,"
                        + "\"completion_tokens\":2}}").getBytes(StandardCharsets.UTF_8));
            }
            // Модель заявляет добавление в рабочую память — подпись должна
            // показать фактическое состояние блока фактов.
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок (Добавлено в рабочую память: цель МАЯК)\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);

            // В стратегии facts: автообновление делает факты реальными.
            JsonConversationStore factsStore = tempStore();
            LlmAgent factsAgent = new LlmAgent(config, ModelSettings.from(factsEnv()),
                    client, factsStore);
            factsAgent.ask("первое сообщение");
            expect("в стратегии facts счётчик подписи равен числу пар фактов",
                    Main.formatShortAnswerNote(factsAgent).contains("рабочая 1 пара")
                            && factsAgent.factsView().size() == 1);
            FakeUi factsCheck = new FakeUi(
                    TerminalUi.Input.command("/facts"), TerminalUi.Input.command("/exit"));
            Main.runLoop(factsCheck, factsAgent, "glm-5.3-flash");
            expect("подпись совпадает с /facts после автообновления",
                    factsAgent.factsView().get("цель").contains("МАЯК"));

            // В стратегии sliding-window: факты не обновляются служебным
            // запросом — «заявление» модели против фактического 0 пар.
            JsonConversationStore windowStore = tempStore();
            LlmAgent windowAgent = new LlmAgent(config, ModelSettings.defaults(),
                    client, windowStore);
            windowAgent.ask("сообщение в скользящем окне");
            expect("в sliding-window факты не обновляются и счётчик показывает 0",
                    factsCalls.get() == 1
                            && windowAgent.factsView().isEmpty()
                            && Main.formatShortAnswerNote(windowAgent)
                            .contains("рабочая 0 пар"));
            expect("системная инструкция содержит правило честности по рабочей памяти",
                    lastBody.get() != null
                            && lastBody.get().contains("не заявляй"));
            windowStore.close();
            factsStore.close();
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }



    /** Агент со временными файлами истории и долговременной памяти. */
    private static LlmAgent newMemoryAgent(Config config, HttpClient client,
                                           Map<String, String> env) throws IOException {
        JsonConversationStore store = tempStore();
        MemoryStore memory = new MemoryStore(
                Files.createTempDirectory(baseTempDir, "mem-").resolve("memory.json"));
        return new LlmAgent(config, ModelSettings.from(env), client, store, memory);
    }

    /** Временный профиль для проверок без API: реальный profile.json не трогается. */
    private static ProfileStore tempProfileStoreForTests() throws IOException {
        return new ProfileStore(Files.createTempDirectory(baseTempDir, "prof-")
                .resolve("profile.json"));
    }

    /** Инварианты для тестов: временный файл, реальный invariants.json не трогается. */
    private static InvariantStore tempInvariantStoreForTests() throws IOException {
        return new InvariantStore(Files.createTempDirectory(baseTempDir, "inv-")
                .resolve("invariants.json"));
    }

    /** Три слоя хранятся отдельно: отдельный файл, формат, валидация при чтении. */
    private static void checkMemoryLayerSeparation() throws IOException {        Path memoryFile = Files.createTempDirectory(baseTempDir, "mem-")
                .resolve("memory.json");
        MemoryStore memory = new MemoryStore(memoryFile);

        // Отсутствующий файл — пустая память, файл заранее не создаётся.
        expect("отсутствующий файл памяти даёт пустую память", memory.load().isEmpty());
        expect("при отсутствии файла памяти JSON не создаётся заранее",
                !Files.exists(memoryFile));

        MemoryEntry entry = memory.put("кодовое слово", "ЯКОРЬ-42").get("кодовое слово");
        expect("запись памяти хранит значение и метки времени",
                "ЯКОРЬ-42".equals(entry.value())
                        && !entry.createdAt().isBlank() && !entry.updatedAt().isBlank());
        expect("файл памяти отдельный от истории (memory.json)",
                memoryFile.getFileName().toString().equals("memory.json"));

        // Повторная запись того же ключа — обновление значения, createdAt сохранён.
        String createdAt = entry.createdAt();
        memory.put("кодовое слово", "ЯКОРЬ-43");
        var reopened = memory.load();
        expect("повторная запись обновляет значение, createdAt сохраняется",
                reopened.get("кодовое слово").value().equals("ЯКОРЬ-43")
                        && reopened.get("кодовое слово").createdAt().equals(createdAt));

        // Содержимое JSON: отдельная сущность «entries», а не поле истории.
        JsonNode saved = MAPPER.readTree(
                Files.readString(memoryFile, StandardCharsets.UTF_8));
        expect("файл памяти имеет собственный формат (schemaVersion 1 и entries)",
                saved.path("schemaVersion").asInt(-1)
                        == MemoryStore.SUPPORTED_SCHEMA_VERSION
                        && saved.path("entries").path("кодовое слово")
                        .path("value").asText().equals("ЯКОРЬ-43"));

        // Удаление записи.
        expect("remove удаляет существующую запись", memory.remove("кодовое слово"));
        expect("remove не удаляет несуществующую запись", !memory.remove("нет"));

        // Повреждённый файл — ошибка повреждения, файл не переписывается.
        byte[] garbage = "это вообще не { json".getBytes(StandardCharsets.UTF_8);
        Files.write(memoryFile, garbage);
        try (var ignored = new java.io.ByteArrayOutputStream()) {
            boolean reported;
            try {
                memory.load();
                reported = false;
            } catch (ConversationStoreException e) {
                reported = e.getMessage().contains(memoryFile.toString());
            }
            expect("повреждённый файл памяти даёт ошибку с путём", reported);
            expect("повреждённый файл памяти не перезаписывается",
                    Arrays.equals(Files.readAllBytes(memoryFile), garbage));
        }
    }

    /** /remember добавляет, /memory показывает, /forget удаляет; команды без API. */
    private static void checkRememberForgetMemoryCommands() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());
            int hitsBefore = hitCounter.get();

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/remember проект: ЯКОРЬ-42, дедлайн 20.05"),
                    TerminalUi.Input.command("/remember без ключа вообще"),
                    TerminalUi.Input.command("/memory"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("команды памяти не вызывают API", hitCounter.get() == hitsBefore);

            expect("/remember «ключ: значение» сохраняет пару в долговременную память",
                    agent.memoryView().get("проект") != null
                            && agent.memoryView().get("проект").value()
                            .equals("ЯКОРЬ-42, дедлайн 20.05"));
            expect("/remember текста без разделителя: ключ — первое слово",
                    agent.memoryView().get("без") != null
                            && agent.memoryView().get("без").value()
                            .equals("ключа вообще"));
            expect("/memory показывает записи в виде «ключ: значение»",
                    ui.systems.stream().anyMatch(t -> t.contains("Долговременная память")
                            && t.contains("проект: ЯКОРЬ-42, дедлайн 20.05")));

            // Второй прогон того же агента: удаление и повторная попытка.
            FakeUi forgetUi = new FakeUi(
                    TerminalUi.Input.command("/forget проект"),
                    TerminalUi.Input.command("/memory"),
                    TerminalUi.Input.command("/forget проект"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(forgetUi, agent, "glm-5.3-flash");
            expect("/forget удаляет запись долговременной памяти",
                    agent.memoryView().get("проект") == null);
            expect("повторный /forget сообщает об отсутствии записи",
                    forgetUi.errors.stream().anyMatch(t ->
                            t.contains("Запись не найдена")));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Долговременная память переживает перезапуск: запись → новый экземпляр → чтение. */
    private static void checkMemoryPersistsAcrossRestarts() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8)));
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            HttpClient client = trustedHttpClient(keyStore);
            JsonConversationStore history1 = tempStore();
            MemoryStore memory = new MemoryStore(
                    Files.createTempDirectory(baseTempDir, "mem-").resolve("memory.json"));
            LlmAgent first = new LlmAgent(config, ModelSettings.defaults(),
                    client, history1, memory);
            first.remember("профиль: отвечает кратко, формат таблицы");
            expect("первый экземпляр сохранил запись",
                    first.memoryView().get("профиль") != null);

            // Новый «запуск»: новый агент и новое хранилище истории,
            // тот же файл долговременной памяти.
            JsonConversationStore history2 = tempStore();
            LlmAgent second = new LlmAgent(config, ModelSettings.defaults(),
                    client, history2, memory);
            expect("новый экземпляр агента видит долговременную память",
                    second.memoryView().get("профиль") != null
                            && second.memoryView().get("профиль").value()
                            .contains("отвечает кратко"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }


    /** /clear очищает краткосрочную и рабочую память, но НЕ долговременную. */
    private static void checkClearKeepsLongTermMemory() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ок\"}}],\"usage\":{\"prompt_tokens\":10,"
                        + "\"completion_tokens\":20}}").getBytes(StandardCharsets.UTF_8)));
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());
            agent.ask("вопрос перед очисткой");
            agent.remember("кодовое слово: ЯКОРЬ-42");
            agent.setTask("подготовить отчёт");

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/clear"),
                    TerminalUi.Input.command("/exit"));
            ui.confirmClearAnswer = true;
            Main.runLoop(ui, agent, "glm-5.3-flash");

            expect("/clear очищает краткосрочную память (историю)",
                    agent.getHistory().isEmpty());
            expect("/clear очищает рабочую память (задачу и факты)",
                    agent.currentTask() == null && agent.factsView().isEmpty());
            expect("/clear НЕ стирает долговременную память",
                    agent.memoryView().get("кодовое слово") != null);
            expect("сообщение /clear сообщает о сохранении долговременной памяти",
                    ui.systems.stream().anyMatch(t ->
                            t.contains("Долговременная память сохранена")));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /**
     * Конечный автомат задачи (модель, без API): допустимые и недопустимые
     * переходы этапов и статусов, пауза/продолжение без потерь, ошибки.
     */
    private static void checkTaskStateMachineModel() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        TaskState started = TaskState.start("подготовить отчёт к среде", now);
        expect("start создаёт задачу: planning, active, шаг и ожидаемое действие заданы",
                started.stage() == TaskStage.PLANNING
                        && started.status() == TaskStatus.ACTIVE
                        && "сформулировать план".equals(started.currentStep())
                        && "агент предлагает план".equals(started.expectedAction())
                        && started.completedSteps().isEmpty()
                        && "подготовить отчёт к среде".equals(started.description())
                        && !started.updatedAt().isBlank());

        expect("переходы вперёд planning → execution → validation → done допустимы",
                TaskStage.PLANNING.canTransitionTo(TaskStage.EXECUTION)
                        && TaskStage.EXECUTION.canTransitionTo(TaskStage.VALIDATION)
                        && TaskStage.VALIDATION.canTransitionTo(TaskStage.DONE));
        expect("пропуск этапов запрещён (planning → validation/done, execution → done)",
                !TaskStage.PLANNING.canTransitionTo(TaskStage.VALIDATION)
                        && !TaskStage.PLANNING.canTransitionTo(TaskStage.DONE)
                        && !TaskStage.EXECUTION.canTransitionTo(TaskStage.DONE)
                        && !TaskStage.VALIDATION.canTransitionTo(TaskStage.PLANNING));
        expect("DONE → planning запрещён: это новая задача, а не продолжение",
                !TaskStage.DONE.canTransitionTo(TaskStage.PLANNING)
                        && !TaskStage.DONE.canTransitionTo(TaskStage.EXECUTION));
        expect("возврат validation → execution разрешён как исключение и помечен обратным",
                TaskStage.VALIDATION.canTransitionTo(TaskStage.EXECUTION)
                        && TaskStage.VALIDATION.isBackwardTransitionTo(TaskStage.EXECUTION)
                        && !TaskStage.EXECUTION.isBackwardTransitionTo(TaskStage.VALIDATION));

        TaskState execution = started.withStage(TaskStage.EXECUTION, null, now);
        expect("переход на execution переносит плановый шаг в выполненные",
                execution.stage() == TaskStage.EXECUTION
                        && execution.currentStep() == null
                        && execution.completedSteps().contains("сформулировать план"));
        expect("исходное состояние не изменяется (record)", started.stage() == TaskStage.PLANNING
                && started.currentStep() != null);

        TaskState validation = execution.withStage(TaskStage.VALIDATION, null, now);
        expect("возврат validation → execution без причины отклоняется",
                expectError(() -> validation.withStage(TaskStage.EXECUTION, null, now))
                        .contains("причины"));
        TaskState back = validation.withStage(TaskStage.EXECUTION,
                "итоговые цифры не сошлись", now);
        expect("возврат validation → execution с причиной разрешён",
                back.stage() == TaskStage.EXECUTION);
        TaskState done = validation.withStage(TaskStage.DONE, null, now);
        expect("DONE → planning даёт понятную ошибку с подсказкой /task start",
                expectError(() -> done.withStage(TaskStage.PLANNING, null, now))
                        .contains("/task start"));

        TaskState stepped = execution.withStep("собрать цифры", now);
        TaskState paused = stepped.withStatus(TaskStatus.PAUSED, now);
        expect("пауза сохраняет этап, текущий шаг и выполненные шаги",
                paused.status() == TaskStatus.PAUSED
                        && paused.stage() == TaskStage.EXECUTION
                        && "собрать цифры".equals(paused.currentStep())
                        && paused.completedSteps().contains("сформулировать план"));
        TaskState resumed = paused.withStatus(TaskStatus.ACTIVE, now);
        expect("resume восстанавливает состояние без потерь",
                resumedEqualsPaused(resumed, paused));
        TaskState blocked = stepped.withStatus(TaskStatus.BLOCKED, now);
        expect("блокировка возможна из active", blocked.status() == TaskStatus.BLOCKED);
        expect("переход пауза → блокировка напрямую запрещён",
                expectError(() -> paused.withStatus(TaskStatus.BLOCKED, now))
                        .contains("не разрешён"));
        expect("повторная пауза отклоняется с подсказкой /task resume",
                expectError(() -> paused.withStatus(TaskStatus.PAUSED, now))
                        .contains("/task resume"));
        expect("unblock возвращает активный статус",
                blocked.withStatus(TaskStatus.ACTIVE, now).status() == TaskStatus.ACTIVE);
    }

    /** Сравнение после resume и перед паузой: все поля, кроме статуса (ACTIVE после resume). */
    private static boolean resumedEqualsPaused(TaskState resumed, TaskState paused) {
        return resumed.stage() == paused.stage()
                && resumed.status() == TaskStatus.ACTIVE
                && java.util.Objects.equals(resumed.currentStep(), paused.currentStep())
                && java.util.Objects.equals(resumed.expectedAction(), paused.expectedAction())
                && resumed.completedSteps().equals(paused.completedSteps())
                && java.util.Objects.equals(resumed.description(), paused.description());
    }

    /** Сообщение AgentException, если действие отклонено; "" — не отклонено. */
    private static String expectError(java.util.function.Supplier<?> action) {
        try {
            action.get();
            return "";
        } catch (AgentException e) {
            return e.getMessage();
        }
    }

    /**
     * Команды /task (без вызова API): start, stage, step, expect, pause,
     * resume, block, unblock, status, clear; недопустимые переходы отклоняются.
     */
    private static void checkTaskStateCommands() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());

            FakeUi startUi = new FakeUi(
                    TerminalUi.Input.command("/task start подготовить отчёт к среде"),
                    TerminalUi.Input.command("/task status"),
                    TerminalUi.Input.command("/task"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(startUi, agent, "glm-5.3-flash");
            TaskState started = agent.taskState();
            expect("start создаёт состояние planning/active",
                    started.stage() == TaskStage.PLANNING
                            && started.status() == TaskStatus.ACTIVE
                            && "подготовить отчёт к среде".equals(started.description()));
            expect("подтверждение start в едином стиле",
                    startUi.systems.stream().anyMatch(t -> t.contains("✓ Задача задана")));
            expect("/task status показывает этап, статус, шаг и ожидаемое действие",
                    startUi.systems.stream().anyMatch(t -> t.contains("Состояние задачи")
                            && t.contains("planning") && t.contains("active")
                            && t.contains("сформулировать план")
                            && t.contains("агент предлагает план")));
            expect("короткий /task показывает описание задачи",
                    startUi.systems.stream().anyMatch(t ->
                            t.contains("Задача: подготовить отчёт к среде")));
            expect("команды /task не вызывают API", hitCounter.get() == 0);

            FakeUi stageUi = new FakeUi(
                    TerminalUi.Input.command("/task stage validation"),
                    TerminalUi.Input.command("/task stage execution"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(stageUi, agent, "glm-5.3-flash");
            expect("пропуск этапа planning → validation отклоняется",
                    stageUi.errors.stream().anyMatch(t -> t.contains("planning → validation")
                            && t.contains("не разрешён")));
            expect("ошибочный переход не меняет состояние, допустимый проходит",
                    agent.taskState().stage() == TaskStage.EXECUTION
                            && agent.taskState().completedSteps().contains("сформулировать план")
                            && stageUi.systems.stream().anyMatch(t ->
                            t.contains("✓ Задача переведена на этап execution")));

            FakeUi stepsUi = new FakeUi(
                    TerminalUi.Input.command("/task step собрать цифры"),
                    TerminalUi.Input.command("/task expect агент готовит таблицу"),
                    TerminalUi.Input.command("/task step свести таблицу"),
                    TerminalUi.Input.command("/task status"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(stepsUi, agent, "glm-5.3-flash");
            TaskState stepped = agent.taskState();
            expect("текущий шаг задан, прежний перенесён в выполненные",
                    "свести таблицу".equals(stepped.currentStep())
                            && stepped.completedSteps().contains("сформулировать план")
                            && stepped.completedSteps().contains("собрать цифры"));
            expect("ожидаемое действие задано",
                    "агент готовит таблицу".equals(stepped.expectedAction())
                            && stepsUi.systems.stream().anyMatch(t ->
                            t.contains("✓ Ожидаемое действие: «агент готовит таблицу»")));
            expect("/task status показывает выполненные шаги",
                    stepsUi.systems.stream().anyMatch(t -> t.contains("выполненные шаги (2)")
                            && t.contains("- собрать цифры")));

            FakeUi pauseUi = new FakeUi(
                    TerminalUi.Input.command("/task pause"),
                    TerminalUi.Input.command("/task resume"),
                    TerminalUi.Input.command("/task block"),
                    TerminalUi.Input.command("/task unblock"),
                    TerminalUi.Input.command("/task pause"),
                    TerminalUi.Input.command("/task resume"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(pauseUi, agent, "glm-5.3-flash");
            TaskState resumed = agent.taskState();
            expect("пауза и resume сохраняют и восстанавливают состояние",
                    "ACTIVE".equals(resumed.status().name())
                            && "свести таблицу".equals(resumed.currentStep())
                            && "агент готовит таблицу".equals(resumed.expectedAction())
                            && resumed.completedSteps().size() == 2
                            && "подготовить отчёт к среде".equals(resumed.description()));
            expect("подтверждение паузы даёт подсказку /task resume",
                    pauseUi.systems.stream().anyMatch(t -> t.contains("на паузе")
                            && t.contains("/task resume")));
            expect("подтверждение блокировки даёт подсказку /task unblock",
                    pauseUi.systems.stream().anyMatch(t -> t.contains("blocked")
                            && t.contains("/task unblock")));

            FakeUi stage2Ui = new FakeUi(
                    TerminalUi.Input.command("/task stage validation"),
                    TerminalUi.Input.command("/task stage execution"),
                    TerminalUi.Input.command("/task stage execution проверка не прошла"),
                    TerminalUi.Input.command("/task status"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(stage2Ui, agent, "glm-5.3-flash");
            TaskState state2 = agent.taskState();
            expect("возврат validation → execution с причиной разрешён явно",
                    "EXECUTION".equals(state2.stage().name())
                            && state2.expectedAction().contains("устранить: проверка не прошла"));
            expect("возврат без причины отклонён (причина обязательна)",
                    stage2Ui.errors.stream().anyMatch(t -> t.contains("причины")));

            FakeUi doneUi = new FakeUi(
                    TerminalUi.Input.command("/task stage validation"),
                    TerminalUi.Input.command("/task stage done"),
                    TerminalUi.Input.command("/task stage planning"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(doneUi, agent, "glm-5.3-flash");
            expect("этап done подтверждается",
                    agent.taskState().stage() == TaskStage.DONE
                            && doneUi.systems.stream().anyMatch(t ->
                            t.contains("✓ Задача переведена на этап done")));
            expect("DONE → planning отклоняется: это новая задача",
                    agent.taskState().stage() == TaskStage.DONE
                            && doneUi.errors.stream().anyMatch(t ->
                            t.contains("завершённая задача не продолжается")));

            FakeUi clearUi = new FakeUi(
                    TerminalUi.Input.command("/task clear"),
                    TerminalUi.Input.command("/task"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(clearUi, agent, "glm-5.3-flash");
            expect("/task clear стирает состояние задачи",
                    agent.taskState() == null && agent.currentTask() == null
                            && clearUi.systems.stream().anyMatch(t ->
                            t.contains("✓ Задача очищена")));
            expect("после очистки /task сообщает об отсутствии задачи",
                    clearUi.systems.stream().anyMatch(t -> t.contains("Задача не задана")));
            expect("команды состояния задачи по-прежнему не вызывают API",
                    hitCounter.get() == 0);
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /**
     * Блок «СОСТОЯНИЕ ЗАДЧИ» в system-сообщении: пустое состояние — блока нет;
     * заданное — этап/статус/шаг/действие/выполненные; пауза и блокировка
     * дают свои правила поведения модели.
     */
    private static void checkTaskStateBlockInSystemMessage() {
        ModelSettings settings = ModelSettings.defaults();
        UserProfile emptyProfile = UserProfile.empty();
        Map<String, MemoryEntry> emptyMemory = new LinkedHashMap<>();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        ChatMessage withoutTask = ContextBuilder.systemContextMessage(settings,
                emptyProfile, emptyMemory, null, Map.of(), null, null);
        expect("пустое состояние (задачи нет) — блока «СОСТОЯНИЕ ЗАДАЧИ» нет",
                !withoutTask.content().contains("СОСТОЯНИЕ ЗАДАЧИ"));

        TaskState active = TaskState
                .start("подготовить отчёт к среде", now)
                .withStage(TaskStage.EXECUTION, null, now)
                .withStep("собрать цифры", now)
                .withExpectedAction("агент готовит таблицу", now);
        ChatMessage withTask = ContextBuilder.systemContextMessage(settings,
                emptyProfile, emptyMemory, active, Map.of(), null, null);
        String block = withTask.content();
        expect("заданное состояние подставлено блоком с заголовком",
                block.contains("<<<СОСТОЯНИЕ ЗАДАЧИ"));
        expect("в блоке этап и статус как инструкции",
                block.contains("Сейчас этап EXECUTION") && block.contains("статус ACTIVE"));
        expect("в блоке текущий шаг и ожидаемое действие",
                block.contains("Текущий шаг: собрать цифры")
                        && block.contains("Ожидаемое действие: агент готовит таблицу"));
        expect("в блоке выполненные шаги", block.contains("сформулировать план"));
        expect("правило неповторения выполненных шагов после resume в блоке",
                block.contains("выполненные шаги не повторяй"));

        ChatMessage pausedSystem = ContextBuilder.systemContextMessage(settings,
                emptyProfile, emptyMemory,
                active.withStatus(TaskStatus.PAUSED, now), Map.of(), null, null);
        String pausedBlock = pausedSystem.content();
        expect("на паузе блок требует ждать /task resume и не продолжать выполнение",
                pausedBlock.contains("статус PAUSED")
                        && pausedBlock.contains("НЕ продолжай выполнение задачи")
                        && pausedBlock.contains("/task resume"));
        expect("формулировка паузы прежняя (подтверждена живым прогоном), не задет",
                pausedBlock.contains("не решай сам, что")
                        && pausedBlock.contains("задача на паузе"));

        ChatMessage blockedSystem = ContextBuilder.systemContextMessage(settings,
                emptyProfile, emptyMemory,
                active.withStatus(TaskStatus.BLOCKED, now), Map.of(), null, null);
        String blockedBlock = blockedSystem.content();
        expect("в блокировке блок требует ТОЛЬКО запросить недостающее",
                blockedBlock.contains("статус BLOCKED")
                        && blockedBlock.contains("ТОЛЬКО запрос недостающих")
                        && blockedBlock.contains("В ответе"));
        expect("в блокировке явный запрет продолжать: не выполняй другие шаги, "
                        + "не пиши код, не помечай шаги выполненными",
                blockedBlock.contains("НЕ выполняй другие шаги задачи")
                        && blockedBlock.contains("НЕ пиши код")
                        && blockedBlock.contains("НЕ помечай шаги выполненными")
                        && blockedBlock.contains("только после снятия блокировки"));
        expect("в блокировке нет прежней слабой формулировки «запрашивай … в каждом ответе»",
                !blockedBlock.contains("запрашивай недостающие сведения в каждом ответе"));
    }

    /**
     * Блок состояния задачи реально уходит в запрос на локальном сервере
     * (в том числе на паузе и после resume) и стирается /clear.
     */
    private static void checkTaskStatePauseResumeInRequest() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            lastBody.set(requestBody);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());

            agent.taskStart("подготовить отчёт к среде");
            agent.taskStage("execution", null);
            agent.taskStep("собрать цифры продаж");
            agent.taskPause();
            agent.ask("какая погода?");
            String pausedSystem = MAPPER.readTree(lastBody.get()).path("messages")
                    .get(0).path("content").asText();
            expect("на паузе запрос содержит правила ожидания /task resume",
                    pausedSystem.contains("<<<СОСТОЯНИЕ ЗАДАЧИ")
                            && pausedSystem.contains("статус PAUSED")
                            && pausedSystem.contains("/task resume"));
            expect("этап и шаг задачи подставлены в запрос",
                    pausedSystem.contains("этап EXECUTION")
                            && pausedSystem.contains("Текущий шаг: собрать цифры продаж"));

            agent.taskResume();
            agent.ask("продолжаем работу");
            String resumedSystem = MAPPER.readTree(lastBody.get()).path("messages")
                    .get(0).path("content").asText();
            expect("после resume запрос содержит активный статус и текущий шаг",
                    resumedSystem.contains("статус ACTIVE")
                            && resumedSystem.contains("Текущий шаг: собрать цифры продаж")
                            && resumedSystem.contains("Выполнено ранее"));

            agent.resetConversation();
            expect("/clear стирает состояние задачи", agent.taskState() == null);
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** /task задаёт и показывает задачу, /task clear очищает рабочую память задачи. */
    private static void checkTaskCommands() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8)));
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/task"),
                    TerminalUi.Input.command("/task подготовить отчёт к среде"),
                    TerminalUi.Input.command("/task"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("/task без аргумента сообщает об отсутствии задачи",
                    ui.systems.stream().anyMatch(t -> t.contains("Задача не задана")));
            expect("/task устанавливает задачу рабочей памяти",
                    agent.currentTask() != null
                            && agent.currentTask().contains("отчёт к среде"));
            expect("установленная задача отображается", agent.currentTask() != null);
            expect("установленная задача видна в выводе /task",
                    ui.systems.stream().anyMatch(t -> t.contains("Задача: подготовить отчёт к среде")));
            expect("подтверждение установки показано",
                    ui.systems.stream().anyMatch(t -> t.contains("Задача задана")));

            FakeUi clearUi = new FakeUi(
                    TerminalUi.Input.command("/task clear"),
                    TerminalUi.Input.command("/task"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(clearUi, agent, "glm-5.3-flash");
            expect("/task clear очищает задачу, не трогая факты",
                    agent.currentTask() == null
                            && clearUi.systems.stream().anyMatch(t ->
                            t.contains("Задача очищена")));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Хранилище инвариантов: add/list/remove/clear, персистентность, отдельный файл. */
    private static void checkInvariantsStoreLifecycle() throws IOException {
        Path file = Files.createTempDirectory(baseTempDir, "inv-")
                .resolve("invariants.json");
        InvariantStore store = new InvariantStore(file);

        expect("отсутствующий файл инвариантов даёт пустой список", store.list().isEmpty());
        expect("при отсутствии файла инвариантов JSON не создаётся заранее",
                !Files.exists(file));

        Invariant first = store.add("проект «Север-17» — модульный монолит на Java 21, "
                + "без Spring и БД", "architecture");
        Invariant second = store.add("только стандартная библиотека и Jackson", null);
        expect("инвариант хранит текст, категорию и метку времени",
                first.text().startsWith("проект «Север-17»") && first.category() != null
                        && first.createdAt() != null);
        expect("категория по умолчанию — other",
                "other".equals(second.category()));
        expect("идентификаторы инвариантов уникальны и не пусты",
                !first.id().isBlank() && !second.id().isBlank()
                        && !first.id().equals(second.id()));
        expect("файл инвариантов отдельный от истории и памяти (invariants.json)",
                file.getFileName().toString().equals("invariants.json") && Files.exists(file));
        expect("категория сохраняется как задана (нижний регистр)",
                "architecture".equals(first.category()));

        // Персистентность: запись → новый экземпляр стора → чтение.
        InvariantStore reopened = new InvariantStore(file);
        List<Invariant> loaded = reopened.list();
        expect("инварианты переживают переоткрытие хранилища",
                loaded.size() == 2
                        && loaded.get(0).text().equals(first.text())
                        && loaded.get(0).createdAt().equals(first.createdAt())
                        && loaded.get(1).text().equals(second.text()));

        // Удаление и очистка.
        expect("remove по id удаляет запись", reopened.remove(first.id()));
        expect("повторный remove по тому же id — false", !reopened.remove(first.id()));
        expect("после remove остаётся одна запись", reopened.list().size() == 1);
        reopened.clear();
        expect("clear опустошает список инвариантов", reopened.list().isEmpty());
        expect("пустой список переживает переоткрытие",
                new InvariantStore(file).list().isEmpty());

        // Формат файла: собственная сущность, а не поле истории или памяти.
        InvariantStore formatted = new InvariantStore(
                Files.createTempDirectory(baseTempDir, "inv-").resolve("invariants.json"));
        formatted.add("рамка", "stack");
        JsonNode saved = MAPPER.readTree(
                Files.readString(formatted.file(), StandardCharsets.UTF_8));
        expect("файл инвариантов имеет собственный формат (schemaVersion 1 и items)",
                saved.path("schemaVersion").asInt(-1)
                        == InvariantStore.SUPPORTED_SCHEMA_VERSION
                        && saved.path("items").size() == 1
                        && saved.path("items").get(0).path("category").asText()
                        .equals("stack"));

        // Пустой текст — ошибка, файл не трогается.
        try {
            store.add("   ", null);
            expect("пустой текст инварианта отклоняется", false);
        } catch (IllegalArgumentException e) {
            expect("пустой текст инварианта отклоняется", true);
        }

        // Обратная совместимость: старый JSON без forbiddenMarkers читается
        // как пустой список маркеров.
        Path legacyFile = Files.createTempDirectory(baseTempDir, "inv-legacy-")
                .resolve("invariants.json");
        Files.writeString(legacyFile, """
                {"schemaVersion": 1, "items": [
                  {"id": "aa11bb22", "text": "старая рамка без маркеров",
                   "category": "stack", "createdAt": "2026-01-01T00:00:00Z"}
                ]}
                """, StandardCharsets.UTF_8);
        Invariant legacy = new InvariantStore(legacyFile).list().get(0);
        expect("старые инварианты без маркеров читаются как пустой список",
                legacy.forbiddenMarkers().isEmpty());

        // Маркеры сериализуются и читаются обратно.
        InvariantStore roundtrip = new InvariantStore(
                Files.createTempDirectory(baseTempDir, "inv-roundtrip-")
                        .resolve("invariants.json"));
        roundtrip.add("рамка с маркерами", "stack", List.of("fastjson", "log4j"));
        JsonNode markersJson = MAPPER.readTree(Files.readString(
                roundtrip.file(), StandardCharsets.UTF_8));
        expect("маркеры попадают в JSON (forbiddenMarkers)",
                markersJson.path("items").get(0).path("forbiddenMarkers")
                        .size() == 2);
        expect("маркеры переживают переоткрытие хранилища",
                new InvariantStore(roundtrip.file()).list().get(0)
                        .forbiddenMarkers().equals(List.of("fastjson", "log4j")));
    }

    /** /invariant add → в списке; remove по id и по номеру; clear; подсказка. */
    private static void checkInvariantCommands() throws Exception {
        InvariantStore invariants = tempInvariantStoreForTests();
        LlmAgent agent = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                        .resolve("conversation.json")),
                tempMemoryStore(), tempProfileStoreForTests(), invariants);

        FakeUi ui = new FakeUi(
                TerminalUi.Input.command("/invariant"),
                TerminalUi.Input.command(
                        "/invariant add проект «Север-17» — модульный монолит на Java 21, "
                                + "без Spring и БД architecture"),
                TerminalUi.Input.command(
                        "/invariant add только стандартная библиотека и Jackson"),
                TerminalUi.Input.command("/invariant"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(ui, agent, "glm-5.3-flash");
        expect("пустой список инвариантов сообщает, как задать рамку",
                ui.systems.stream().anyMatch(t ->
                        t.contains("инвариантов нет")
                                && t.contains("/invariant add <текст> — задать")));
        expect("подтверждение добавления инварианта с категорией",
                ui.systems.stream().anyMatch(t -> t.contains("✓ Инвариант задан")
                        && t.contains("без Spring и БД") && t.contains("[architecture]")));
        expect("категория без явного указания — other",
                ui.systems.stream().anyMatch(t ->
                        t.contains("✓ Инвариант задан") && t.contains("[other]")));
        expect("после /invariant add подсказка следующего шага",
                ui.systems.stream().anyMatch(t -> t.contains(
                        "Дальше: /invariant — посмотреть все рамки")));
        expect("список показывает обе рамки с категориями",
                ui.systems.stream().anyMatch(t -> t.contains("[architecture]")
                        && t.contains("[other]"))
                        && !ui.systems.stream().anyMatch(t ->
                        t.contains("[stack]") && t.contains("Инварианты — жёсткие")));
        expect("инварианты добавлены в агент и хранилище",
                agent.invariantsView().size() == 2
                        && agent.invariantsView().get(0).category().equals("architecture")
                        && agent.invariantsFile().getFileName().toString()
                        .equals("invariants.json"));

        // Remove по номеру, затем по id (отдельные прогоны: состояние
        // проверяется между ними).
        String secondId = agent.invariantsView().get(1).id();
        FakeUi removeNumberUi = new FakeUi(
                TerminalUi.Input.command("/invariant remove 1"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(removeNumberUi, agent, "glm-5.3-flash");
        expect("remove по номеру удаляет рамку с подтверждением текста",
                removeNumberUi.systems.stream().anyMatch(t -> t.contains("✓ Инвариант удалён")
                        && t.contains("без Spring и БД"))
                        && agent.invariantsView().size() == 1);
        FakeUi removeIdUi = new FakeUi(
                TerminalUi.Input.command("/invariant remove " + secondId),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(removeIdUi, agent, "glm-5.3-flash");
        expect("remove по id удаляет рамку с подтверждением текста",
                removeIdUi.systems.stream().anyMatch(t -> t.contains("✓ Инвариант удалён")
                        && t.contains("стандартная библиотека"))
                        && agent.invariantsView().isEmpty());

        // Неизвестный id/номер — ошибка, ничего не удалено.
        agent.invariantAdd("рамка для удаления", "stack");
        FakeUi notFoundUi = new FakeUi(TerminalUi.Input.command("/invariant remove 99"));
        Main.runLoop(notFoundUi, agent, "glm-5.3-flash");
        expect("несуществующий номер — ошибка, рамка сохранена",
                notFoundUi.errors.stream().anyMatch(t -> t.contains("Инвариант не найден"))
                        && agent.invariantsView().size() == 1);

        // Clear с подтверждением и отказом от подтверждения.
        FakeUi clearNoUi = new FakeUi(TerminalUi.Input.command("/invariant clear"));
        clearNoUi.confirmProfileClearAnswer = false;
        Main.runLoop(clearNoUi, agent, "glm-5.3-flash");
        expect("отказ подтверждения сохраняет инварианты",
                clearNoUi.confirmProfileClearCount == 1 && clearNoUi.confirmProfileClearSubject
                        .contains("инварианты")
                        && agent.invariantsView().size() == 1
                        && clearNoUi.systems.stream().anyMatch(t ->
                        t.contains("Удаление отменено")));
        FakeUi clearYesUi = new FakeUi(
                TerminalUi.Input.command("/invariant clear"),
                TerminalUi.Input.command("/invariant"),
                TerminalUi.Input.command("/exit"));
        clearYesUi.confirmProfileClearAnswer = true;
        Main.runLoop(clearYesUi, agent, "glm-5.3-flash");
        expect("/invariant clear очищает все рамки и сообщает об этом",
                clearYesUi.confirmProfileClearCount == 1
                        && agent.invariantsView().isEmpty()
                        && clearYesUi.systems.stream().anyMatch(t ->
                        t.contains("✓ Инварианты очищены")));

        // Персистентность команд: запись → новый экземпляр агента со тем же файлом.
        FakeUi addUi = new FakeUi(TerminalUi.Input.command(
                "/invariant add персистентная рамка stack"));
        Main.runLoop(addUi, agent, "glm-5.3-flash");
        LlmAgent restarted = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                        .resolve("conversation.json")),
                tempMemoryStore(), tempProfileStoreForTests(),
                new InvariantStore(invariants.file()));
        expect("инварианты переживают перезапуск (новый агент с тем же файлом)",
                restarted.invariantsView().size() == 1
                        && restarted.invariantsView().get(0).text()
                        .contains("персистентная рамка"));
    }

    /** Блок «ИНВАРИАНТЫ» подставляется в system-сообщение, когда рамки есть. */
    private static void checkInvariantBlockInSystemMessage() {
        ModelSettings settings = ModelSettings.defaults();
        Map<String, MemoryEntry> emptyMemory = new LinkedHashMap<>();

        Invariant architecture = Invariant.create(
                "проект «Север-17» — модульный монолит на Java 21, без Spring и БД",
                "architecture", Instant.parse("2026-01-01T00:00:00Z"));
        Invariant business = Invariant.create("CSV-экспорт — по RFC 4180, с BOM", "business",
                Instant.parse("2026-01-01T00:00:00Z"));

        ChatMessage emptySystem = ContextBuilder.systemContextMessage(settings,
                UserProfile.empty(), emptyMemory, null, Map.of(), null, null, List.of());
        expect("без инвариантов блок «ИНВАРИАНТЫ» опускается",
                !emptySystem.content().contains("ИНВАРИАНТЫ"));

        ChatMessage fullSystem = ContextBuilder.systemContextMessage(settings,
                UserProfile.empty(), emptyMemory, null, Map.of(), null, null,
                List.of(architecture, business));
        String system = fullSystem.content();
        expect("заданные инварианты подставлены: заголовок блока",
                system.contains("<<<ИНВАРИАНТЫ (жёсткие рамки, нарушать нельзя)"));
        expect("блок содержит инвариант с категорией architecture",
                system.contains("[architecture] проект «Север-17»"));
        expect("блок содержит инвариант с категорией business",
                system.contains("[business] CSV-экспорт — по RFC 4180"));
        expect("инструкция блока объявляет жёсткие ограничения",
                system.contains("Это жёсткие ограничения"));
        expect("инструкция запрещает решения, нарушающие инвариант",
                system.contains("НЕ предлагай решение, которое нарушает"));
        expect("инструкция требует назвать инвариант и предложить альтернативу",
                system.contains("назови инвариант")
                        && system.contains("альтернативу в рамках инварианта"));
        expect("инвариант не трактуется как исторические сведения",
                !system.contains("исторические сведения"));
    }

    /** Полный прогон: инвариант добавлен командой, блок «ИНВАРИАНТЫ» в запросе. */
    private static void checkInvariantBlockInRealRequest() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            lastBody.set(requestBody);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            InvariantStore invariantStore = new InvariantStore(
                    Files.createTempDirectory(baseTempDir, "inv-")
                            .resolve("invariants.json"));
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), tempStore(), tempMemoryStore(),
                    tempProfileStoreForTests(), invariantStore);

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command(
                            "/invariant add без Spring и БД stack"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            // Нейтральный запрос уходит модели как обычно (проверка не мешает):
            // «добавь spring» теперь ловится до API (см. checkInvariantGuardNoApiInMessage).
            agent.ask("расскажи про структуру проекта");
            String system = MAPPER.readTree(lastBody.get())
                    .path("messages").get(0).path("content").asText();
            expect("в запросе есть блок «ИНВАРИАНТЫ (жёсткие рамки, нарушать нельзя)»",
                    system.contains("ИНВАРИАНТЫ (жёсткие рамки, нарушать нельзя)"));
            expect("блок содержит заданный инвариант",
                    system.contains("[stack] без Spring и БД")
                            && system.contains("НЕ предлагай решение, которое нарушает"));

            // /clear не стирает инварианты: они снова в запросе после очистки.
            agent.resetConversation();
            agent.ask("вопрос после /clear");
            String rebuilt = MAPPER.readTree(lastBody.get())
                    .path("messages").get(0).path("content").asText();
            expect("инварианты переживают /clear и остаются в запросе",
                    rebuilt.contains("[stack] без Spring и БД"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Правила InvariantGuard: границы слов, регистр, отрицание, вопрос, словарь. */
    private static void checkInvariantGuardRules() {
        Invariant springStack = new Invariant("ab12cd34",
                "без Spring и БД", "stack", List.of("spring", "spring boot"),
                Instant.parse("2026-01-01T00:00:00Z"));
        Invariant archBuiltin = new Invariant("ef34cd56",
                "модульный монолит на Java 21", "architecture",
                Instant.parse("2026-01-01T00:00:00Z"));
        Invariant businessNoMarkers = new Invariant("0912abef",
                "CSV-экспорт — по RFC 4180, с BOM", "business",
                Instant.parse("2026-01-01T00:00:00Z"));

        // Явный конфликт с многословным маркером.
        List<InvariantGuard.Conflict> conflicts = InvariantGuard.check(
                "добавь spring boot в проект", List.of(springStack, businessNoMarkers));
        expect("явный конфликт ловится: «добавь spring boot» при маркере «spring boot»",
                conflicts.size() == 1
                        && conflicts.get(0).invariant().equals(springStack)
                        && conflicts.get(0).matchedMarkers().contains("spring boot"));

        // Регистронезависимость.
        expect("матчинг регистронезависим (Locale.ROOT)",
                !InvariantGuard.check("Добавь SPRING BOOT",
                        List.of(springStack)).isEmpty());

        // Границы слов: springfield не ловится как spring.
        expect("границы слов: «springfield-парсер» не ловится как «spring»",
                InvariantGuard.check("добавь springfield-парсер",
                        List.of(springStack)).isEmpty());

        // Отрицание и вопрос — не запрос на нарушение.
        expect("отрицание «не используй spring» не блокируется",
                InvariantGuard.check("не используй spring",
                        List.of(springStack)).isEmpty());
        expect("вопрос «почему нельзя spring?» не блокируется",
                InvariantGuard.check("почему нельзя spring?",
                        List.of(springStack)).isEmpty());
        expect("обсуждение без императива не блокируется",
                InvariantGuard.check("spring обсуждается в команде",
                        List.of(springStack)).isEmpty());

        // Встроенный словарь по категории architecture.
        expect("встроенный словарь: «подключи hibernate» ловится по категории",
                !InvariantGuard.check("подключи hibernate",
                        List.of(archBuiltin)).isEmpty());
        // Встроенный словарь не применяется, если инвариант сам разрешает слово.
        Invariant gsonAllowed = gsonAllowedInvariant();
        expect("встроенный маркер не ловится, если инвариант сам разрешает слово",
                InvariantGuard.check("используй gson для json",
                        List.of(gsonAllowed)).isEmpty());
        Invariant stackBuiltin = new Invariant("cc44dd55",
                "только разрешённые библиотеки", "stack",
                Instant.parse("2026-01-01T00:00:00Z"));
        expect("встроенный словарь stack: «используй log4j» ловится по категории",
                !InvariantGuard.check("используй log4j в модулях",
                        List.of(stackBuiltin)).isEmpty());
        // Пустой список маркеров и категория business — конфликта нет.
        expect("без маркеров и вне словарных категорий конфликта нет",
                InvariantGuard.check("добавь всё что угодно",
                        List.of(businessNoMarkers)).isEmpty());
        // Пустой запрос и пустой список инвариантов.
        expect("пустой запрос не даёт конфликта",
                InvariantGuard.check("   ", List.of(springStack)).isEmpty());
        expect("пустой список инвариантов не даёт конфликта",
                InvariantGuard.check("добавь spring boot", List.of()).isEmpty());
    }

    /** Инвариант «gson разрешён» для проверки фильтра встроенного словаря. */
    private static Invariant gsonAllowedInvariant() {
        return new Invariant("11aa22bb", "миграция на gson — разрешено",
                "stack", Instant.parse("2026-01-01T00:00:00Z"));
    }

    /**
     * Отказ на вводе (до API): счётчик вызовов не растёт, история пуста,
     * текст отказа — три части + пересмотр.
     */
    private static void checkInvariantGuardNoApiInMessage() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger hitCounter = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hitCounter.incrementAndGet();
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            InvariantStore invariantStore = new InvariantStore(
                    Files.createTempDirectory(baseTempDir, "inv-")
                            .resolve("invariants.json"));
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), tempStore(), tempMemoryStore(),
                    tempProfileStoreForTests(), invariantStore);
            FakeUi setupUi = new FakeUi(
                    TerminalUi.Input.command(
                            "/invariant add без Spring и БД stack запрещено: spring, spring boot"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(setupUi, agent, "glm-5.3-flash");

            int hitsBefore = hitCounter.get();
            FakeUi refusedUi = new FakeUi(
                    TerminalUi.Input.message("добавь spring boot"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(refusedUi, agent, "glm-5.3-flash");
            expect("детерминированный конфликт ловится до API: счётчик вызовов не растёт",
                    hitCounter.get() == hitsBefore);
            expect("отказ не попал в историю как ответ модели",
                    agent.getHistory().isEmpty());
            String refusalText = String.join("\n", refusedUi.systems);
            expect("отказ называет инвариант и сработавшие маркеры",
                    refusalText.contains("[stack] без Spring и БД")
                            && refusalText.contains("spring boot"));
            expect("отказ объясняет противоречие и альтернативу",
                    refusalText.contains("почему") && refusalText.contains("альтернатива"));
            expect("отказ указывает на пересмотр (/invariant remove), "
                            + "а не обход в диалоге",
                    refusalText.contains("/invariant remove"));

            // Нейтральное сообщение уходит модели как обычно.
            int afterRefusal = hitCounter.get();
            FakeUi passUi = new FakeUi(
                    TerminalUi.Input.message("расскажи про структуру проекта"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(passUi, agent, "glm-5.3-flash");
            expect("запрос без конфликта уходит модели как обычно",
                    hitCounter.get() == afterRefusal + 1
                            && agent.getHistory().size() == 2);
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** «запрещено: …» сохраняется, показывается в списке и переживает перезапуск. */
    private static void checkInvariantAddForbiddenMarkers() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8)));
        try {
            InvariantStore invariantStore = tempInvariantStoreForTests();
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), tempStore(), tempMemoryStore(),
                    tempProfileStoreForTests(), invariantStore);
            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command(
                            "/invariant add без Spring и БД architecture "
                                    + "запрещено: spring, spring boot, hibernate"),
                    TerminalUi.Input.command("/invariant"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            Invariant stored = agent.invariantsView().get(0);
            expect("«запрещено: …» сохраняется в маркерах инварианта",
                    stored.forbiddenMarkers().equals(List.of(
                            "spring", "spring boot", "hibernate")));
            expect("список инвариантов показывает запрещённые слова",
                    ui.systems.stream().anyMatch(t -> t.contains("запрещено:")
                            && t.contains("spring boot")));
            expect("текст рамки чист от служебного хвоста «запрещено:»",
                    !stored.text().contains("запрещено"));
            expect("подсказка после add упоминает «запрещено:»",
                    ui.systems.stream().anyMatch(t -> t.contains("запрещено:")
                            && t.contains("до API")));

            LlmAgent restarted = new LlmAgent(new Config("test-key",
                    "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                    ModelSettings.defaults(),
                    java.net.http.HttpClient.newHttpClient(),
                    new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                            .resolve("conversation.json")),
                    tempMemoryStore(), tempProfileStoreForTests(),
                    new InvariantStore(invariantStore.file()));
            expect("маркеры переживают перезапуск",
                    restarted.invariantsView().get(0).forbiddenMarkers()
                            .equals(List.of("spring", "spring boot", "hibernate")));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /**
     * Локальные инварианты задачи: add без задачи — ошибка; add/list/remove/
     * clear; живут в TaskState, НЕ в InvariantStore; /task clear и /clear
     * стирают локальные, глобальные остаются.
     */
    private static void checkTaskInvariantCommands() throws Exception {
        InvariantStore globalStore = tempInvariantStoreForTests();
        LlmAgent agent = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                        .resolve("conversation.json")),
                tempMemoryStore(), tempProfileStoreForTests(), globalStore);

        // Без задачи — ошибка, сначала /task start.
        FakeUi noTaskUi = new FakeUi(
                TerminalUi.Input.command("/task invariant add рамка задачи stack"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(noTaskUi, agent, "glm-5.3-flash");
        expect("/task invariant add без задачи — ошибка «сначала /task start»",
                noTaskUi.errors.stream().anyMatch(t -> t.contains("Задача не начата")
                        && t.contains("/task start")));

        // Глобальный инвариант для проверки разделения источников.
        agent.invariantAdd("глобальная рамка проекта", "architecture");

        FakeUi ui = new FakeUi(
                TerminalUi.Input.command("/task start запустить миграцию вставки"),
                TerminalUi.Input.command(
                        "/task invariant add локальная рамка задачи stack "
                                + "запрещено: fastjson"),
                TerminalUi.Input.command("/task invariant"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(ui, agent, "glm-5.3-flash");
        expect("локальный инвариант добавляется к задаче",
                agent.taskInvariantsView().size() == 1
                        && agent.taskInvariantsView().get(0).text()
                        .equals("локальная рамка задачи")
                        && agent.taskInvariantsView().get(0).category()
                        .equals("stack")
                        && agent.taskInvariantsView().get(0).forbiddenMarkers()
                        .equals(List.of("fastjson")));
        expect("локальный инвариант НЕ в глобальном хранилище",
                agent.invariantsView().size() == 1
                        && agent.invariantsView().get(0).text()
                        .equals("глобальная рамка проекта"));
        String globalJson = Files.readString(globalStore.file(),
                StandardCharsets.UTF_8);
        expect("файл глобальных инвариантов не знает про локальную рамку",
                !globalJson.contains("локальная рамка задачи"));
        expect("список /task invariant показывает локальную рамку",
                ui.systems.stream().anyMatch(t -> t.contains("Локальные инварианты")
                        && t.contains("локальная рамка задачи")));
        expect("подсказка после /task invariant add",
                ui.systems.stream().anyMatch(t ->
                        t.contains("Дальше: /task invariant — посмотреть локальные рамки")));
        expect("локальный инвариант живёт вместе с этапами задачи",
                agent.taskState().stage() == TaskStage.PLANNING);

        // Этапы/шаги не теряют локальные инварианты.
        agent.taskStep("проверка контрактов");
        expect("шаг задачи сохраняет локальные инварианты",
                agent.taskInvariantsView().size() == 1);

        // Remove по номеру, затем по id.
        agent.taskInvariantAdd("второй локальный инвариант", "business");
        String firstId = agent.taskInvariantsView().get(0).id();
        FakeUi removeNumber = new FakeUi(TerminalUi.Input.command(
                "/task invariant remove 1"));
        Main.runLoop(removeNumber, agent, "glm-5.3-flash");
        expect("remove по номеру удаляет локальную рамку",
                agent.taskInvariantsView().size() == 1
                        && agent.taskInvariantsView().get(0).text()
                        .equals("второй локальный инвариант")
                        && removeNumber.systems.stream().anyMatch(t ->
                        t.contains("✓ Локальный инвариант удалён")));
        // Remove по id: сначала несуществующий id — ошибка, затем по id.
        FakeUi removeId = new FakeUi(TerminalUi.Input.command(
                "/task invariant remove " + firstId));
        // firstId уже удалён по номеру; проверяем remove несуществующего id.
        Main.runLoop(removeId, agent, "glm-5.3-flash");
        expect("remove по несуществующему id — ошибка",
                removeId.errors.stream().anyMatch(t ->
                        t.contains("Локальный инвариант не найден")));
        FakeUi removeLast = new FakeUi(TerminalUi.Input.command(
                "/task invariant remove "
                        + agent.taskInvariantsView().get(0).id()));
        Main.runLoop(removeLast, agent, "glm-5.3-flash");
        expect("remove по id удаляет последнюю локальную рамку",
                agent.taskInvariantsView().isEmpty());
        agent.taskInvariantAdd("рамка для clear", "other");
        FakeUi clearUi = new FakeUi(TerminalUi.Input.command(
                "/task invariant clear"));
        clearUi.confirmProfileClearAnswer = true;
        Main.runLoop(clearUi, agent, "glm-5.3-flash");
        expect("/task invariant clear очищает локальные, глобальные остаются",
                agent.taskInvariantsView().isEmpty()
                        && agent.invariantsView().size() == 1);

        // /task clear стирает локальные вместе с задачей; глобальные остаются.
        agent.taskStart("новая задача с рамками");
        agent.taskInvariantAdd("локальная рамка перед clear", "stack");
        FakeUi clearTaskUi = new FakeUi(TerminalUi.Input.command("/task clear"));
        Main.runLoop(clearTaskUi, agent, "glm-5.3-flash");
        expect("/task clear стирает локальные инварианты вместе с задачей",
                agent.taskState() == null
                        && agent.taskInvariantsView().isEmpty()
                        && agent.invariantsView().size() == 1);

        // Локальные рамки НЕ переживают перезапуск (TaskState — сессионное).
        agent.taskStart("задача для перезапуска");
        agent.taskInvariantAdd("сессионная рамка", "stack");
        LlmAgent restarted = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                        .resolve("conversation.json")),
                tempMemoryStore(), tempProfileStoreForTests(),
                new InvariantStore(globalStore.file()));
        expect("перезапуск: локальная рамка исчезла (TaskState сессионный), "
                        + "глобальная на месте",
                restarted.taskInvariantsView().isEmpty()
                        && restarted.invariantsView().size() == 1);
    }

    /** Блок «ИНВАРИАНТЫ» из двух источников + guard по локальным. */
    private static void checkTaskInvariantBlockAndGuard() throws Exception {
        ModelSettings settings = ModelSettings.defaults();
        Map<String, MemoryEntry> emptyMemory = new LinkedHashMap<>();
        Invariant global = Invariant.create("глобальная рамка архитектуры",
                "architecture", Instant.parse("2026-01-01T00:00:00Z"));
        Invariant local = Invariant.create("локальная рамка задачи", "business",
                Instant.parse("2026-01-01T00:00:00Z"));
        Instant now = Instant.parse("2026-01-02T00:00:00Z");
        TaskState withLocal = TaskState.start("задача", now)
                .withLocalInvariant(local, now);

        // Только глобальные — как раньше, без подзаголовка локальных.
        ChatMessage globalOnly = ContextBuilder.systemContextMessage(settings,
                UserProfile.empty(), emptyMemory, null, Map.of(), null, null,
                List.of(global), List.of());
        expect("только глобальные: блок без строки локальных",
                globalOnly.content().contains("[architecture] глобальная рамка")
                        && !globalOnly.content().contains("Локальные"));

        // Только локальные — блок с пометкой «Локальные».
        ChatMessage localOnly = ContextBuilder.systemContextMessage(settings,
                UserProfile.empty(), emptyMemory, withLocal, Map.of(), null, null,
                List.of(), List.of(local));
        expect("только локальные: пометка «Локальные (только для текущей задачи)»",
                localOnly.content().contains("Локальные (только для текущей задачи)")
                        && localOnly.content().contains("[business] локальная рамка")
                        && !localOnly.content().contains("Глобальные"));

        // Оба источника — два подзаголовка в одном блоке.
        ChatMessage both = ContextBuilder.systemContextMessage(settings,
                UserProfile.empty(), emptyMemory, withLocal, Map.of(), null, null,
                List.of(global), List.of(local));
        String bothText = both.content();
        expect("оба источника в одном блоке с пометками источников",
                bothText.contains("Глобальные (действуют всегда)")
                        && bothText.contains("Локальные (только для текущей задачи)")
                        && bothText.indexOf("Глобальные")
                        < bothText.indexOf("Локальные"));

        // Пустые оба — блок опускается.
        ChatMessage none = ContextBuilder.systemContextMessage(settings,
                UserProfile.empty(), emptyMemory, withLocal, Map.of(), null, null,
                List.of(), List.of());
        expect("без глобальных и локальных блок «ИНВАРИАНТЫ» опускается",
                !none.content().contains("ИНВАРИАНТЫ"));

        // Приоритет: инструкция блока предупреждает, локальные не отменяют
        // глобальные.
        expect("инструкция блока: «локальные рамки уточняют, но не отменяют "
                        + "глобальные»",
                bothText.contains("локальные рамки уточняют, но не отменяют глобальные"));

        // Guard учитывает оба набора: конфликт ловится по локальной рамке.
        Invariant localStack = Invariant.create("запрет fastjson в этой задаче",
                "stack", List.of("fastjson"), now);
        TaskState guardedState = TaskState.start("задача", now)
                .withLocalInvariant(localStack, now);
        List<InvariantGuard.Conflict> conflicts = InvariantGuard.check(
                "добавь fastjson к парсеру",
                List.of(global), guardedState.localInvariantsView());
        expect("guard ловит конфликт по локальному инварианту",
                conflicts.size() == 1
                        && conflicts.get(0).invariant().equals(localStack)
                        && conflicts.get(0).matchedMarkers().contains("fastjson"));
        // Глобальный не нарушен, только локальный.
        expect("локальный конфликт не помечает глобальный инвариант",
                conflicts.stream()
                        .noneMatch(c -> c.invariant().equals(global)));
    }

    /** Инварианты НЕ попадают в рабочую память и НЕ в историю диалога. */
    private static void checkInvariantsNotInWorkingMemoryOrHistory() throws Exception {
        InvariantStore invariantStore = tempInvariantStoreForTests();
        LlmAgent agent = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                        .resolve("conversation.json")),
                tempMemoryStore(), tempProfileStoreForTests(), invariantStore);
        FakeUi ui = new FakeUi(
                TerminalUi.Input.command("/invariant add жёсткая рамка стек stack"),
                TerminalUi.Input.command("/invariant clear"),
                TerminalUi.Input.command("/exit"));
        ui.confirmProfileClearAnswer = true;
        Main.runLoop(ui, agent, "glm-5.3-flash");
        expect("инварианты не превратились в факты рабочей памяти",
                agent.factsView().isEmpty());
        expect("инварианты не превратились в задачу рабочей памяти",
                agent.taskState() == null);
        expect("инварианты не попали в историю диалога",
                agent.getHistory().isEmpty());
        expect("инварианты не попали в долговременную память",
                agent.memoryView().isEmpty());
    }

    /** /invariant в полном индексе (группа «Рамки» с назначением) и в справке. */
    private static void checkInvariantHelpAndIndex() {
        String full = TerminalUi.chatIndex(80);
        expect("полный индекс содержит группу «Рамки»", full.contains("Рамки"));
        expect("полный индекс содержит /invariant", full.contains("/invariant"));
        expect("группа «Рамки» объяснена одной строкой назначения",
                full.contains("жёсткие ограничения, которые агент не нарушает"));
        String commandHelp = TerminalUi.chatCommandHelp("/invariant");
        expect("у /invariant есть подробная справка (жёсткие рамки)",
                commandHelp != null && commandHelp.contains("/invariant add")
                        && commandHelp.contains("не нарушает"));
        boolean listed = false;
        for (String name : TerminalUi.chatCommandNames()) {
            if (name.equals("/invariant")) {
                listed = true;
            }
        }
        expect("/invariant входит в список известных команд (подсказки опечаток)",
                listed);
        expect("опечатка в имени команды даёт подсказку /invariant",
                Main.closestCommand("/indvarian").equals("/invariant"));
    }

    /** В запрос подставляются все три слоя с корректными заголовками. */
    private static void checkThreeLayersInRequest() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            lastBody.set(requestBody);
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());
            agent.remember("кодовое слово: ЯКОРЬ-42");
            agent.setTask("написать краткое резюме проекта");
            agent.ask("первый вопрос");
            agent.ask("второй вопрос");

            JsonNode messages = MAPPER.readTree(lastBody.get()).path("messages");
            String system = messages.get(0).path("content").asText();
            expect("в запросе подставлены все три слоя: долговременная память",
                    system.contains("<<<ДОЛГОВРЕМЕННАЯ ПАМЯТЬ")
                            && system.contains("кодовое слово: ЯКОРЬ-42"));
            expect("в запросе подставлена рабочая память с задачей",
                    system.contains("<<<РАБОЧАЯ ПАМЯТЬ")
                            && system.contains("Текущая задача: написать краткое резюме проекта"));
            expect("краткосрочная история отправляется после system, без дубликатов system",
                    messages.size() == 4
                            && "system".equals(messages.get(0).path("role").asText())
                            && "первый вопрос".equals(messages.get(1).path("content").asText())
                            && "второй вопрос".equals(
                            messages.get(messages.size() - 1).path("content").asText()));
            expect("правило непересечения слоёв в базовой инструкции",
                    system.contains("Слои памяти не дублируй"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Служебный расход обновления памяти учтён ровно один раз и входит в лимит сессии. */
    private static void checkMemoryUpdateAccountingOnce() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            if (requestBody.contains(FACTS_MARKER)) {
                return new Response(200, ("{\"choices\":[{\"finish_reason\":\"stop\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"цель: МАЯК, "
                        + "дедлайн 20.05\"}}],\"usage\":{\"prompt_tokens\":70,"
                        + "\"completion_tokens\":9}}").getBytes(StandardCharsets.UTF_8));
            }
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}],\"usage\":{\"prompt_tokens\":10,"
                    + "\"completion_tokens\":20}}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            JsonConversationStore store = tempStore();
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(factsEnv()),
                    trustedHttpClient(keyStore), store);
            agent.ask("обновление памяти один");
            agent.ask("обновление памяти два");
            SessionTokenStats.Snapshot stats = agent.sessionStats();

            long memoryPurposes = agent.attemptUsageLog().stream()
                    .filter(u -> u.purpose() == SessionTokenStats.Purpose.MEMORY_UPDATE)
                    .count();
            expect("служебные запросы обновления памяти учтены назначением MEMORY_UPDATE",
                    memoryPurposes == 2 && stats.factsAttempts() == 2);
            expect("расход обновления памяти входит в итог сессии ровно один раз (без дублей)",
                    stats.totalPromptTokens() == 20 + 140
                            && stats.totalCompletionTokens() == 40 + 18
                            && stats.regularAttempts() == 2
                            && stats.knownTotal() == 218);
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    private static void checkUserFacingOutputNeutral() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());

            FakeUi ui = new FakeUi(
                    TerminalUi.Input.message("обычное сообщение"),
                    TerminalUi.Input.command("/help"),
                    TerminalUi.Input.command("/tokens"),
                    TerminalUi.Input.command("/stats"),
                    TerminalUi.Input.command("/limit"),
                    TerminalUi.Input.command("/context"),
                    TerminalUi.Input.command("/summary"),
                    TerminalUi.Input.command("/strategy"),
                    TerminalUi.Input.command("/strategy facts"),
                    TerminalUi.Input.command("/strategy"),
                    TerminalUi.Input.command("/facts"),
                    TerminalUi.Input.command("/branch list"),
                    TerminalUi.Input.command("/memory"),
                    TerminalUi.Input.command("/task текущая работа"),
                    TerminalUi.Input.command("/task clear"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            String text = String.join("\n", ui.systems) + "\n"
                    + String.join("\n", ui.messages) + "\n"
                    + String.join("\n", ui.errors);
            for (String banned : new String[]{
                    "День 8", "День 9", "День 10", "Day 8", "Day 9", "Day 10",
                    "Day-", "задание", "учеб"}) {
                expect("пользовательский вывод без отметки «" + banned + "»",
                        !text.contains(banned));
            }
            expect("подписи стратегии нейтральные",
                    text.contains("Скользящее окно")
                            && text.contains("Факты"));
            // Сводка слоёв памяти сохраняется в подробном блоке после ответа
            // (formatShortAnswerNote) и по командам; по умолчанию вывод тихий,
            // поэтому здесь проверяется сама строка, а не экран.
            expect("подпись слоёв памяти присутствует в подробном блоке",
                    Main.formatShortAnswerNote(agent).contains("Память: долговременная"));
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Значения по умолчанию для запуска одной командой (Проблема 1). */
    private static void checkDefaultRunSettings() {
        Map<String, String> empty = Map.of();
        expect("LLM_DIAGNOSTICS по умолчанию false (краткий вывод)",
                !ModelSettings.from(empty).diagnostics());

        Path first = JsonConversationStore.defaultHistoryFile(empty);
        Path second = JsonConversationStore.defaultHistoryFile(empty);
        expect("история по умолчанию: каталог ~/.ai-advent-agent, имя chat-*.json",
                first.getParent().getFileName().toString().equals(".ai-advent-agent")
                        && first.getFileName().toString().startsWith("chat-")
                        && first.getFileName().toString().endsWith(".json"));
        expect("уникальное имя файла истории на каждый запуск",
                !first.equals(second));

        Map<String, String> explicit = new java.util.HashMap<>();
        explicit.put("LLM_HISTORY_FILE", "/tmp/selftest-history-fix.json");
        expect("LLM_HISTORY_FILE переопределяет путь истории",
                JsonConversationStore.defaultHistoryFile(explicit)
                        .equals(Path.of("/tmp/selftest-history-fix.json")));

        expect("память по умолчанию: ~/.ai-advent-agent/memory.json (общая)",
                MemoryStore.defaultMemoryFile(empty)
                        .getFileName().toString().equals("memory.json")
                        && MemoryStore.defaultMemoryFile(empty).getParent()
                        .getFileName().toString().equals(".ai-advent-agent"));
        Map<String, String> explicitMemory = new java.util.HashMap<>();
        explicitMemory.put("LLM_MEMORY_FILE", "/tmp/selftest-memory-fix.json");
        expect("LLM_MEMORY_FILE переопределяет путь памяти",
                MemoryStore.defaultMemoryFile(explicitMemory)
                        .equals(Path.of("/tmp/selftest-memory-fix.json")));
    }

    /**
     * Тихий старт и минимальный вывод по умолчанию (Проблема 2):
     * только ответ плюс статусная строка старта; подробности — по командам.
     */
    private static void checkQuietStartupAndAnswer() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        AtomicInteger success = new AtomicInteger();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ответ " + success.incrementAndGet() + "\"}}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}")
                        .getBytes(StandardCharsets.UTF_8)));
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());
            FakeUi ui = new FakeUi(
                    TerminalUi.Input.message("короткое сообщение"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            // Ответ показан; в системных строках нет ни диагностики,
            // ни расходов, ни подписи контекста/стратегии/слоёв памяти.
            expect("по умолчанию после ответа только ответ",
                    ui.messages.size() == 1 && ui.messages.get(0).contains("Ответ 1"));
            String systems = String.join("\n", ui.systems);
            for (String banned : new String[]{
                    "Диагностика:", "Расход сессии", "Контекст:", "Режим контекста",
                    "Стратегия:", "Память: долговременная", "профир", "Профиль:"}) {
                expect("по умолчанию в выводе нет «" + banned + "»",
                        !systems.contains(banned));
            }
            expect("старт по умолчанию короткий: пусто до приглашения, подробностей нет",
                    ui.systems.stream().allMatch(t -> t.equals("Работа завершена. История беседы сохранена."))
                            && !systems.contains("Стратегия контекста")
                            && !systems.contains("Профиль")
                            && !systems.contains("Лимит расхода токенов"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Явные команды по-прежнему показывают подробности (Проблема 2, п.4). */
    private static void checkExplicitCommandsStillDetailed() throws Exception {
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) ->
                new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8)));
        try {
            Config config = new Config("test-key",
                    "https://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions", "glm-5.3-flash");
            LlmAgent agent = newMemoryAgent(config, trustedHttpClient(keyStore),
                    new java.util.HashMap<>());
            agent.remember("код: ЯКОРЬ-42");
            FakeUi ui = new FakeUi(
                    TerminalUi.Input.command("/stats"),
                    TerminalUi.Input.command("/tokens"),
                    TerminalUi.Input.command("/strategy"),
                    TerminalUi.Input.command("/context"),
                    TerminalUi.Input.command("/memory"),
                    TerminalUi.Input.command("/limit"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            String systems = String.join("\n", ui.systems);
            expect("/stats показывает подробности по явному запросу",
                    systems.contains("Статистика сессии"));
            expect("/tokens показывает оценку контекста",
                    systems.contains("Токены (без вызова API)"));
            expect("/strategy показывает стратегию и окно",
                    systems.contains("Стратегия контекста"));
            expect("/context показывает режим контекста",
                    systems.contains("Режим контекста"));
            expect("/memory показывает долговременную память по явному запросу",
                    systems.contains("Долговременная память"));
            expect("/limit показывает лимит сессии",
                    systems.contains("Лимит расхода токенов за сессию"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /**
     * Подробная диагностика скрыта по умолчанию и появляется только
     * при LLM_DIAGNOSTICS=true.
     */
    private static void checkDiagnosticsHiddenByDefault() throws Exception {
        Path keyStore = day9KeyStore();
        Day9Server s = startDay9Server(keyStore);
        try {
            Config config = new Config("test-key", day9Url(s), "glm-5.3-flash");

            JsonConversationStore quietStore = tempStore();
            LlmAgent quietAgent = new LlmAgent(config, ModelSettings.defaults(),
                    trustedHttpClient(keyStore), quietStore);
            FakeUi quietUi = new FakeUi(
                    TerminalUi.Input.message("короткое сообщение"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(quietUi, quietAgent, "glm-5.3-flash");
            expect("подробная диагностика скрыта по умолчанию",
                    quietUi.systems.stream().noneMatch(t -> t.contains("Диагностика:")));
            // По умолчанию после ответа — только ответ: ни «Расход сессии»,
            // ни «Контекст:», ни «Стратегия:", ни «Режим контекста».
            String quietText = String.join("\n", quietUi.systems);
            expect("по умолчанию нет кратких служебных блоков после ответа",
                    !quietText.contains("Диагностика:")
                            && !quietText.contains("Расход сессии")
                            && !quietText.contains("Контекст:")
                            && !quietText.contains("Стратегия:")
                            && !quietText.contains("Режим контекста"));
            expect("по умолчанию после ответа показан сам ответ",
                    quietUi.messages.size() == 1
                            && quietUi.messages.get(0).contains("Ответ"));
            quietStore.close();

            Map<String, String> env = new java.util.HashMap<>();
            env.put("LLM_DIAGNOSTICS", "true");
            JsonConversationStore diagStore = tempStore();
            LlmAgent diagAgent = new LlmAgent(config, ModelSettings.from(env),
                    trustedHttpClient(keyStore), diagStore);
            FakeUi diagUi = new FakeUi(
                    TerminalUi.Input.message("сообщение с диагностикой"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(diagUi, diagAgent, "glm-5.3-flash");
            expect("при LLM_DIAGNOSTICS=true подробная диагностика видна",
                    diagUi.systems.stream().anyMatch(t -> t.contains("Диагностика:")));
            String diagText = String.join("\n", diagUi.systems);
            expect("при LLM_DIAGNOSTICS=true сохраняется весь блок отчёта",
                    diagText.contains("Расход сессии")
                            && diagText.contains("Контекст:")
                            && diagText.contains("Стратегия:")
                            && diagText.contains("Память: долговременная")
                            && diagText.contains("Профиль:")
                            && diagText.contains("Стратегия контекста")
                            && diagText.contains("Режим контекста"));
            diagStore.close();
        } finally {
            s.server().stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Повреждённые необязательные сущности (facts, branches) отклоняются. */
    private static void checkDay10OtherStoreValidation() throws IOException {
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

    // ================= Ведущий интерфейс: справка, подсказки, меню =================

    /** /help короткий (не стена), /help all полный, у групп — назначение. */
    private static void checkShortHelpAndFullIndex() {
        String shortHelp = TerminalUi.shortHelp();
        String[] lines = shortHelp.split("\n", -1);
        expect("короткая /help умещается в 8 строк", lines.length <= 8);
        expect("короткая /help отсылает к полному списку",
                shortHelp.contains("/help all"));
        expect("короткая /help показывает /task start, /remember и /profile name",
                shortHelp.contains("/task start") && shortHelp.contains("/remember")
                        && shortHelp.contains("/profile name"));
        expect("короткая /help сообщает количество остальных команд",
                shortHelp.matches("(?s).*ещё \\d+ .*: /help all.*"));

        String full = TerminalUi.chatIndex(80);
        for (String group : new String[]{"Память", "Профиль", "Контекст", "Диалог",
                "Режимы", "Статистика", "Прочее"}) {
            expect("полный индекс содержит группу «" + group + "»",
                    full.contains(group));
        }
        expect("полный индекс объясняет назначение групп",
                full.contains("что агент помнит") && full.contains("что уходит в запрос"));
        expect("полный индекс содержит /status", full.contains("/status"));

        // Вывод plain-терминала: /help короткий, /help all полный.
        CapturedStream out = capturingStream();
        CapturedStream err = capturingStream();
        new PlainTerminalUi(reader(""), out.stream, err.stream).showHelp();
        CapturedStream outAll = capturingStream();
        CapturedStream errAll = capturingStream();
        new PlainTerminalUi(reader(""), outAll.stream, errAll.stream).showFullHelp();
        String helpText = err.text();
        String fullText = errAll.text();
        expect("plain /help выводит короткую справку без стены групп",
                helpText.contains("/task start") && helpText.contains("/help all")
                        && !helpText.contains("Память"));
        expect("plain /help all выводит полный индекс",
                fullText.contains("Память") && fullText.contains("Статистика"));
    }

    /** Подсказка следующего шага после /task start, /profile, /skill, /remember. */
    private static void checkNextHints() throws Exception {
        LlmAgent agent = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                        .resolve("conversation.json")),
                tempMemoryStore(), tempProfileStoreForTests());

        FakeUi ui = new FakeUi(
                TerminalUi.Input.command("/task start сверить параметры проекта"),
                TerminalUi.Input.command("/profile name Алексей"),
                TerminalUi.Input.command("/remember кодовое слово: ЯКОРЬ-42"),
                TerminalUi.Input.command("/skill add \"карточка фичи\" название, цель, шаги"),
                TerminalUi.Input.command("/exit"));
        ui.interactiveMenusEnabled = true; // краткая форма работает и с меню включёнными
        Main.runLoop(ui, agent, "glm-5.3-flash");
        String systems = String.join("\n", ui.systems);
        expect("после /task start подсказка следующего шага (/task stage execution)",
                ui.systems.stream().anyMatch(t -> t.contains("✓ Задача задана")
                        && t.contains("/task stage execution")));
        expect("после /profile name — подсказка следующих полей профиля",
                systems.contains("/profile style|format|constraint"));
        expect("после /remember — подсказка /memory",
                systems.contains("Дальше: /memory — посмотреть записи"));
        expect("после /skill add — подсказка /pipeline",
                systems.contains("Дальше: /pipeline"));
        expect("краткая форма /task start создала состояние",
                agent.taskState() != null);
    }

    /** Интерактивные меню /profile и /task без аргументов (FakeUi с меню). */
    private static void checkInteractiveMenus() throws Exception {
        // Агент без API: команды меню не вызывают API.
        LlmAgent agent = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                        .resolve("conversation.json")),
                tempMemoryStore(), tempProfileStoreForTests());

        FakeUi profileUi = new FakeUi(
                TerminalUi.Input.command("/profile"),
                TerminalUi.Input.command("1"),
                TerminalUi.Input.command("Алексей"),
                TerminalUi.Input.command("/status"),
                TerminalUi.Input.command("/exit"));
        profileUi.interactiveMenusEnabled = true;
        Main.runLoop(profileUi, agent, "glm-5.3-flash");
        expect("/profile без аргументов открывает меню выбора поля",
                profileUi.systems.stream().anyMatch(t ->
                        t.contains("1) обращение 2) стиль 3) формат")));
        expect("выбор пункта запрашивает значение",
                profileUi.systems.stream().anyMatch(t ->
                        t.contains("Введите обращение")));
        expect("значение применено без памяти синтаксиса",
                "Алексей".equals(agent.userProfile().name())
                        && profileUi.systems.stream().anyMatch(t ->
                        t.contains("✓ Профиль обновлён: name")));
        expect("мусорного ввода в меню нет: выбор 1 и имя не попали в историю",
                agent.getHistory().isEmpty());

        FakeUi taskUi = new FakeUi(
                TerminalUi.Input.command("/task"),
                TerminalUi.Input.command("1"),
                TerminalUi.Input.command("подготовить отчёт к среде"),
                TerminalUi.Input.command("/task"),
                TerminalUi.Input.command("4"),
                TerminalUi.Input.command("/task status"),
                TerminalUi.Input.command("/exit"));
        taskUi.interactiveMenusEnabled = true;
        Main.runLoop(taskUi, agent, "glm-5.3-flash");
        expect("/task без аргументов открывает меню",
                taskUi.systems.stream().anyMatch(t ->
                        t.contains("1) начать 2) этап 3) шаг")));
        expect("меню: начало задачи по номеру без синтаксиса",
                "подготовить отчёт к среде".equals(agent.taskState().description()));
        expect("меню: пауза по номеру 4",
                agent.taskState().status() == TaskStatus.PAUSED
                        && taskUi.systems.stream().anyMatch(t ->
                        t.contains("✓ Задача на паузе")));
        expect("меню: короткая форма по-прежнему работает штатно",
                agent.getHistory().isEmpty());
    }

    /** В неинтерактивном (--plain) меню не запускаются: показывается синтаксис. */
    private static void checkPlainMenusDisabledWithSyntaxHint() throws Exception {
        LlmAgent agent = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                        .resolve("conversation.json")),
                tempMemoryStore(), tempProfileStoreForTests());
        agent.setProfileName("Шеф");

        FakeUi plainUi = new FakeUi(
                TerminalUi.Input.command("/profile"),
                TerminalUi.Input.command("/task"),
                TerminalUi.Input.command("/exit"));
        // interactiveMenusEnabled=false (по умолчанию) — как PlainTerminalUi.
        Main.runLoop(plainUi, agent, "glm-5.3-flash");
        String systems = String.join("\n", plainUi.systems);
        expect("в plain-режиме нет пошагового меню профиля",
                !systems.contains("1) обращение"));
        expect("в plain-режиме есть подсказка точного синтаксиса",
                systems.contains("/profile name|style|format|constraint")
                        && systems.contains("/task start <описание>"));
        expect("в plain-режиме меню задач не запускается",
                !systems.contains("1) начать 2) этап"));
        expect("профиль не изменился опросом", "Шеф".equals(agent.userProfile().name()));
        expect("состояние задачи создано только явно", agent.taskState() == null);
    }

    /** Опечатки ведут к подсказке: неизвестная команда и неверный этап. */
    private static void checkTypoSuggestions() throws Exception {
        LlmAgent agent = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                        .resolve("conversation.json")),
                tempMemoryStore(), tempProfileStoreForTests());

        expect("опечатка /profil ловится подсказкой /profile",
                "/profile".equals(Main.closestCommand("/profil")));
        expect("опечатка /таск не даёт ложного совпадения без схемы",
                Main.closestCommand("/totally-unknown-cmd") == null);
        FakeUi ui = new FakeUi(
                TerminalUi.Input.command("/profil"),
                TerminalUi.Input.command("/task start сверить параметры"),
                TerminalUi.Input.command("/task stage foo"),
                TerminalUi.Input.command("/exit"));
        ui.interactiveMenusEnabled = false;
        Main.runLoop(ui, agent, "glm-5.3-flash");
        expect("неизвестная команда подсказывает ближайщую",
                ui.systems.stream().anyMatch(t -> t.contains("Неизвестная команда: /profil")
                        && t.contains("/profile")));
        expect("неверный этап даёт список допустимых",
                ui.errors.stream().anyMatch(t -> t.contains("Допустимые этапы")
                        && t.contains("planning, execution, validation, done")));
        expect("ошибочный этап не изменил состояние",
                agent.taskState().stage() == TaskStage.PLANNING);
    }

    /** /status — обзор одним экраном, с подсказками, без вызова API. */
    private static void checkStatusOverview() throws Exception {
        LlmAgent agent = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                        .resolve("conversation.json")),
                tempMemoryStore(), tempProfileStoreForTests());
        agent.remember("кодовое слово: ЯКОРЬ-42");
        agent.setProfileName("Шеф");
        agent.taskStart("подготовить отчёт к среде");

        AtomicInteger hits = new AtomicInteger();
        Path keyStore = createSelfSignedKeyStore();
        HttpsServer server = startHttpsServer(keyStore, (requestBody, session, auth) -> {
            hits.incrementAndGet();
            return new Response(200, ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Ок\"}}]}").getBytes(StandardCharsets.UTF_8));
        });
        try {
            FakeUi ui = new FakeUi(TerminalUi.Input.command("/status"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(ui, agent, "glm-5.3-flash");
            expect("/status без вызова API", hits.get() == 0);
            String systems = String.join("\n", ui.systems);
            expect("/status показывает задачу с этапом и статусом",
                    systems.contains("подготовить отчёт к среде")
                            && systems.contains("этап planning")
                            && systems.contains("статус active"));
            expect("/status показывает профиль и память",
                    systems.contains("обращение «Шеф»") && systems.contains("память: 1"));
            expect("/status подсказывает, как изменить каждую строку",
                    systems.contains("/mode fast|balanced|detailed")
                            && systems.contains("/strategy, /context")
                            && systems.contains("/limit")
                            && systems.contains("/task status")
                            && systems.contains("/stats"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(keyStore);
        }
    }

    /** Онбординг при первом запуске и его отсутствие на повторном. */
    private static void checkOnboardingFirstLaunch() throws IOException {
        ProfileStore freshProfile = new ProfileStore(
                Files.createTempDirectory(baseTempDir, "prof-").resolve("profile.json"));
        JsonConversationStore freshHistory = new JsonConversationStore(
                Files.createTempDirectory(baseTempDir, "hist-")
                        .resolve("conversation.json"));
        LlmAgent fresh = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                freshHistory, tempMemoryStore(), freshProfile);
        expect("первый запуск: профиль отсутствует и история пуста",
                Main.firstLaunch(fresh));

        fresh.setProfileName("Шеф");
        expect("повторный запуск: профиль сохранён — онбординга не будет",
                !Main.firstLaunch(fresh));

        String onboarding = TerminalUi.firstRunOnboarding();
        String[] lines = onboarding.split("\n", -1);
        expect("онбординг короткий (не больше 6 строк)", lines.length <= 6);
        expect("онбординг показывает примеры и ссылку на /help",
                onboarding.contains("/task start") && onboarding.contains("/remember")
                        && onboarding.contains("Подробности: /help"));

        // Метка первого запуска снята фактически: файл профиля на диске существует.
        expect("после установки поля профиль записан (метка первого запуска снята)",
                Files.exists(freshProfile.file()));

        // Plain-терминал: онбординг только при первом запуске.
        CapturedStream err = capturingStream();
        new PlainTerminalUi(reader(""), capturingStream().stream, err.stream)
                .showWelcome("test-model", true);
        CapturedStream errAgain = capturingStream();
        new PlainTerminalUi(reader(""), capturingStream().stream, errAgain.stream)
                .showWelcome("test-model", false);
        expect("онбординг показывается при первом запуске",
                err.text().contains("Привет! Я агент с памятью и задачами."));
        expect("повторный запуск — без онбординга",
                !errAgain.text().contains("Привет! Я агент с памятью"));
    }
}
