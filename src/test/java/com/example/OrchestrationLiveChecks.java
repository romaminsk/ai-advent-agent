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

final class OrchestrationLiveChecks extends SelfTestSupport {
     static void checkOrchestrationLive(Map<String, String> env) throws Exception {
        String url = env.get("LLM_API_URL");
        String model = env.getOrDefault("LLM_MODEL", "glm-5.3-flash");
        if (url == null || url.isBlank()) throw new AssertionError("LLM_API_URL отсутствует в .env");
        Path repo = Files.createDirectory(baseTempDir.resolve("live-repo"));
        git(repo, "init");
        Files.writeString(repo.resolve("todo.txt"), "TODO one\nTODO two\nTODO three\n");
        Files.createDirectories(repo.resolve(".ssh"));
        Files.writeString(repo.resolve(".ssh/id_ed25519"), "TODO private marker\n");
        Files.writeString(repo.resolve("server.PEM"), "TODO certificate marker\n");
        git(repo, "add", "todo.txt", ".ssh", "server.PEM");
        git(repo, "commit", "-m", "live fixture");
        Files.writeString(repo.resolve("todo.txt"), "TODO changed\n");
        int passedRuns = 0;
        // Одна сессия: один LlmAgent и одна история на все сценарии (a) и (b),
        // как при двух /mcp agent в одном запуске CLI.
        try (JsonConversationStore store = new JsonConversationStore(
                baseTempDir.resolve("live-history-one.json"))) {
            LlmAgent llm = new LlmAgent(new Config(env.get("LLM_API_KEY"), url, model),
                    ModelSettings.fromEnv(), store,
                    new MemoryStore(baseTempDir.resolve("live-memory.json")),
                    new ProfileStore(baseTempDir.resolve("live-profile.json")),
                    new InvariantStore(baseTempDir.resolve("live-invariants.json")));
            for (int liveRun = 1; liveRun <= 3; liveRun++) {
                Path results = Files.createDirectory(baseTempDir.resolve("pipeline-results-" + liveRun));
                boolean passedAttempt = false;
                for (int attempt = 1; attempt <= 2 && !passedAttempt; attempt++) {
                List<ToolRouter.Step> journal;
                try (McpRegistry registry = McpRegistry.open(repo, results)) {
                    McpOrchestrator.Outcome outcome = new McpOrchestrator(registry,
                            (system, prompt) -> llm.askWithoutHistory(system, prompt))
                            .run("Проверь состояние репозитория по точному абсолютному пути " + repo + "\n"
                                    + "Сначала вызови git.get-repository-status, затем найди все TODO "
                                    + "через pipeline.search, затем сделай сводку и сохрани её в файл. "
                                    + "Не отвечай final, пока файл не сохранён.");
                    journal = outcome.steps();
                    for (ToolRouter.Step step : journal) {
                        System.out.println("live attempt " + liveRun + "/" + attempt + ": " + step.number() + " | "
                                + step.server() + " | " + step.tool() + " | " + step.inputRef()
                                + " | " + (step.ok() ? "ok" : "error: " + step.error())
                                + " | args=" + step.args());
                    }
                    passedAttempt = liveChecks(journal, results, env.get("LLM_API_KEY"));
                }
                }
                if (passedAttempt) passedRuns++;
            }
            expect("live orchestration: 3 прогона без API-ошибок", passedRuns == 3);

            Path nonGit = Files.createDirectory(baseTempDir.resolve("live-non-git"));
            Files.createDirectories(nonGit.resolve("sub"));
            Files.createDirectories(nonGit.resolve(".ssh"));
            Files.writeString(nonGit.resolve("a.txt"), "FixWord one\nfixword two\n");
            Files.writeString(nonGit.resolve("sub/b.txt"), "FIXWORD sub\n");
            Files.writeString(nonGit.resolve(".ssh/id_ed25519"), "fixword secret\n");
            Files.writeString(nonGit.resolve("server.PEM"), "fixword cert\n");
            Path nonGitResults = Files.createDirectory(baseTempDir.resolve("live-non-git-results"));
            try (McpRegistry registry = McpRegistry.open(nonGit, nonGitResults)) {
                ToolRouter.Result fixtureSearch = new ToolRouter(registry).call("pipeline.search",
                        Map.of("root", nonGit.toString(), "query", "fixword"));
                expect("live non-Git fixture содержит 3 совпадения в 2 файлах", fixtureSearch.ok()
                        && Integer.valueOf(3).equals(fixtureSearch.data().get("totalMatches"))
                        && ((List<?>) fixtureSearch.data().get("matches")).stream()
                        .map(item -> String.valueOf(((Map<?, ?>) item).get("file"))).distinct().count() == 2);
                McpOrchestrator liveOrchestrator = new McpOrchestrator(registry,
                        (system, prompt) -> llm.askWithoutHistory(system, prompt));
                McpOrchestrator.Outcome outcome = liveOrchestrator.run(
                        "Проверь состояние репозитория " + nonGit
                                + ". Git-шаг может завершиться ошибкой, но продолжи pipeline:"
                                + " найди все fixword, сделай сводку и ОБЯЗАТЕЛЬНО вызови "
                                + "pipeline.saveToFile с inputRef от summarize; только после сохранения "
                                + "верни final и упомяни ошибку Git.");
                for (ToolRouter.Step step : outcome.steps()) {
                    System.out.println("live b: " + step.number() + " | " + step.server() + " | "
                            + step.tool() + " | " + step.inputRef() + " | "
                            + (step.ok() ? "ok" : "error: " + step.error())
                            + " | args=" + step.args());
                }
                expect("live non-Git: Git ошибка, pipeline выполнен",
                        outcome.steps().size() <= 8 && outcome.steps().stream().anyMatch(step -> !step.ok()
                                && "git".equals(step.server()))
                                && outcome.steps().stream().anyMatch(step -> step.ok() && "saveToFile".equals(step.tool()))
                                && outcome.steps().stream().anyMatch(step -> !step.ok()
                                && "Путь не является Git-репозиторием.".equals(step.error())));
                int liveSearch = indexOf(outcome.steps(), "pipeline", "search");
                Object liveTotal = liveSearch < 0 ? null
                        : liveOrchestrator.router().data("step:" + (liveSearch + 1)) == null ? null
                        : liveOrchestrator.router().data("step:" + (liveSearch + 1)).get("totalMatches");
                expect("live non-Git: search query=fixword и 3 совпадения", liveSearch >= 0
                        && String.valueOf(outcome.steps().get(liveSearch).args().get("query"))
                        .equalsIgnoreCase("fixword")
                        && Integer.valueOf(3).equals(liveTotal));
            }
        }
    }
}
