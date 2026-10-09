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

/** SelfTest group runner. */
public final class SelfTest extends SelfTestSupport {
    private interface GroupAction {
        void run() throws Exception;
    }

    private record TestGroup(String displayName, boolean fullRun, GroupAction action) {
    }

    private SelfTest() {
    }

    public static void main(String[] args) throws Exception {
        String encoding = System.getProperty("sun.jnu.encoding", "");
        if (!encoding.equalsIgnoreCase("UTF-8")) {
            System.err.println("нужна UTF-8 локаль, текущая: " + encoding);
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(SelfTest::cleanupProcesses,
                "self-test-process-cleanup"));
        LinkedHashMap<String, TestGroup> registry = groupRegistry();
        if (args.length == 1 && "list".equalsIgnoreCase(args[0])) {
            registry.forEach((name, entry) -> System.out.println(name
                    + (entry.fullRun() ? "" : " (explicit only)") + "\t" + entry.displayName()));
            return;
        }
        List<Map.Entry<String, TestGroup>> selected;
        try {
            selected = selectGroups(args, registry);
        } catch (IllegalArgumentException invalid) {
            System.err.println("Ошибка выбора группы: " + invalid.getMessage()
                    + ". Список: mvn -q test-compile exec:java@self-test -Dexec.args=list");
            System.exit(2);
            return;
        }

        baseTempDir = Files.createTempDirectory(args.length == 0
                ? "selftest-day8" : "selftest-groups");
        // Изоляция от реальных настроек RAG пользователя: 3-арг Main.runLoop
        // без инъекции сервиса читает defaultStore(); enabled:true после
        // live-прогонов не должно ломать suite. Файл создаётся пустым —
        // состояние по умолчанию (режим выключен).
        System.setProperty("ai-agent.rag.settings-file",
                baseTempDir.resolve("rag-settings.json").toString());
        // Изоляция активного профиля /model: self-tests не пишут реальный
        // ~/.ai-advent-agent/model-profile.json пользователя.
        System.setProperty("ai-agent.model-profile-file",
                baseTempDir.resolve("model-profile.json").toString());
        try {
            for (Map.Entry<String, TestGroup> entry : selected) {
                int beforePassed = passed;
                int beforeFailed = failures.size();
                long started = System.nanoTime();
                group(entry.getValue().displayName());
                try {
                    entry.getValue().action().run();
                } finally {
                    long elapsedMs = (System.nanoTime() - started) / 1_000_000;
                    System.out.printf(Locale.ROOT, "GROUP: %s | %d | %d | %d ms%n",
                            entry.getKey(), passed - beforePassed,
                            failures.size() - beforeFailed, elapsedMs);
                }
            }
        } catch (Throwable error) {
            failures.add("группа: " + currentGroup + "; непредвиденная ошибка: "
                    + error + "\n" + stackTrace(error));
            System.out.println("CATCH-INFO: непредвиденная ошибка группы: "
                    + error + "\n" + stackTrace(error));
            error.printStackTrace();
        } finally {
            cleanupProcesses();
            deleteRecursively(baseTempDir);
        }
        printSummary();
        if (failures.isEmpty()) {
            System.out.println("OK: все проверки пройдены (" + passed + ").");
        } else {
            System.exit(1);
        }
    }

    private static List<Map.Entry<String, TestGroup>> selectGroups(
            String[] args, LinkedHashMap<String, TestGroup> registry) {
        if (args.length == 0) {
            return registry.entrySet().stream().filter(entry -> entry.getValue().fullRun()).toList();
        }
        if (args.length != 1 || args[0].isBlank()) {
            throw new IllegalArgumentException("ожидается один аргумент <группа>[,<группа>]");
        }
        List<Map.Entry<String, TestGroup>> selected = new ArrayList<>();
        Set<String> selectedNames = new java.util.LinkedHashSet<>();
        for (String rawName : args[0].split(",", -1)) {
            String name = rawName.trim().toLowerCase(Locale.ROOT);
            if (name.equals("mcp")) {
                selectedNames.add("mcp");
                selectedNames.add("mcp-pipeline");
                continue;
            }
            Map.Entry<String, TestGroup> entry = registry.entrySet().stream()
                    .filter(candidate -> candidate.getKey().equals(name))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "неизвестная группа «" + rawName.trim() + "»"));
            selectedNames.add(entry.getKey());
        }
        for (String name : selectedNames) selected.add(Map.entry(name, registry.get(name)));
        return selected;
    }

    private static LinkedHashMap<String, TestGroup> groupRegistry() {
        LinkedHashMap<String, TestGroup> groups = new LinkedHashMap<>();
        groups.put("dialog", new TestGroup("Диалог", true, DialogChecks::run));
        groups.put("stores", new TestGroup("Хранилища", true, StoreChecks::run));
        groups.put("task-state", new TestGroup("Состояние задачи", true, TaskStateChecks::run));
        groups.put("context", new TestGroup("Контекст", true, ContextChecks::run));
        groups.put("commands", new TestGroup("Команды", true, CommandChecks::run));
        groups.put("private-chat", new TestGroup("Приватный чат", true, PrivateChatChecks::run));
        groups.put("invariant-guard", new TestGroup("InvariantGuard", true, InvariantGuardChecks::run));
        groups.put("ux", new TestGroup("UX", true, UxChecks::run));
        groups.put("mcp", new TestGroup("MCP", true,
                () -> runWithMcpTestTimeout(McpChecks::run)));
        groups.put("mcp-pipeline", new TestGroup("MCP Pipeline", true,
                () -> runWithMcpTestTimeout(McpPipelineChecks::run)));
        groups.put("orchestration", new TestGroup("Orchestration", true,
                () -> runWithMcpTestTimeout(OrchestrationChecks::run)));
        groups.put("worker-scheduler", new TestGroup("Worker планировщик", true, WorkerSchedulerChecks::run));
        groups.put("worker-process", new TestGroup("Worker процесс", true, WorkerProcessChecks::run));
        groups.put("store-failures", new TestGroup("Store и runner отказы", true, StoreFailureChecks::run));
        groups.put("measurements", new TestGroup("Измерения", true, MeasurementChecks::run));
        groups.put("quiet-output", new TestGroup("Тихий вывод", true, QuietOutputChecks::run));
        groups.put("integration", new TestGroup("Интеграция", true, IntegrationChecks::run));
        groups.put("index", new TestGroup("Индексация", true, IndexChecks::run));
        groups.put("rag", new TestGroup("RAG", true, RagChecks::run));
        groups.put("orchestration-live", new TestGroup("Orchestration live", false,
                SelfTest::runLiveOrchestration));
        return groups;
    }

    private static void runWithMcpTestTimeout(GroupAction action) throws Exception {
        try (AutoCloseable ignored = McpClientComponent.useTestTimeout(Duration.ofSeconds(2))) {
            action.run();
        }
    }

    private static void runLiveOrchestration() throws Exception {
        Map<String, String> env = readDotEnv();
        if (env.get("LLM_API_KEY") == null || env.get("LLM_API_KEY").isBlank()) {
            failures.add("orchestration-live: нет ключа в .env");
            System.out.println("FAIL: orchestration-live: нет ключа в .env");
            return;
        }
        OrchestrationLiveChecks.checkOrchestrationLive(env);
    }

}
