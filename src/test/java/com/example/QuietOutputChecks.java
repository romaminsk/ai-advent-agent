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

final class QuietOutputChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkUserFacingOutputNeutral();
        checkDefaultRunSettings();
        checkQuietStartupAndAnswer();
        checkExplicitCommandsStillDetailed();
        checkDiagnosticsHiddenByDefault();
    }

     static void checkUserFacingOutputNeutral() throws Exception {
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

     static void checkDefaultRunSettings() {
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

     static void checkQuietStartupAndAnswer() throws Exception {
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

     static void checkExplicitCommandsStillDetailed() throws Exception {
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

     static void checkDiagnosticsHiddenByDefault() throws Exception {
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
}
