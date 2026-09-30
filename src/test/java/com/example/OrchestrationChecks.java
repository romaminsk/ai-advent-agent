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

final class OrchestrationChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkOrchestration();
    }

     static void checkOrchestration() throws Exception {
        Path repo = Files.createDirectory(baseTempDir.resolve("orchestration-repo"));
        git(repo, "init");
        Files.writeString(repo.resolve("todo.txt"), "TODO one\nTODO two\n");
        git(repo, "add", "todo.txt");
        git(repo, "commit", "-m", "initial");
        Path results = Files.createDirectory(baseTempDir.resolve("pipeline-results"));
        List<String> replies = List.of(
                "{\"tool\":\"git.get-repository-status\",\"args\":{\"repoPath\":\"" + repo + "\"}}",
                "{\"tool\":\"pipeline.search\",\"args\":{\"root\":\"" + repo + "\",\"query\":\"TODO\"}}",
                "{\"tool\":\"pipeline.summarize\",\"args\":{\"inputRef\":\"step:2\"}}",
                "{\"tool\":\"pipeline.saveToFile\",\"args\":{\"inputRef\":\"step:3\",\"fileName\":\"orchestration.md\"}}",
                "{\"final\":\"Проверка выполнена.\"}");
        try (McpRegistry registry = McpRegistry.open(repo, results)) {
            expect("каталог содержит только git и pipeline", registry.statuses().size() == 2
                    && registry.tools().size() == 4);
            McpOrchestrator orchestrator = new McpOrchestrator(registry, new McpOrchestrator.Model() {
                private int index;
                @Override public String complete(String system, String prompt) { return replies.get(index++); }
            });
            McpOrchestrator.Outcome outcome = orchestrator.run("Проверь TODO и сохрани сводку");
            List<ToolRouter.Step> steps = outcome.steps();
            expect("длинный флоу завершён", !outcome.stopped()
                    && outcome.answer().contains("orchestration.md")
                    && outcome.answer().contains("SHA-256"));
            expect("маршрутизация git и pipeline", steps.size() == 4
                    && "git".equals(steps.get(0).server()) && "pipeline".equals(steps.get(1).server()));
            expect("порядок search summarize saveToFile", "search".equals(steps.get(1).tool())
                    && "summarize".equals(steps.get(2).tool()) && "saveToFile".equals(steps.get(3).tool()));
            expect("inputRef передан ссылкой", "step:2".equals(steps.get(2).inputRef())
                    && "step:3".equals(steps.get(3).inputRef()));
            expect("файл результата создан", Files.exists(results.resolve("orchestration.md")));
            expect("monitor отсутствует в каталоге", registry.tools().stream()
                    .noneMatch(tool -> tool.qualifiedName().startsWith("monitor.")));

            McpOrchestrator invalid = new McpOrchestrator(registry,
                    new SequenceModel("{\"tool\":\"pipeline.summarize\",\"args\":{\"searchResult\":{}}}",
                            "{\"tool\":\"foo.bar\",\"args\":{}}", "{\"final\":\"исправлено\"}"));
            McpOrchestrator.Outcome invalidOutcome = invalid.run("исправь");
            expect("ошибочные шаги возвращаются модели", invalidOutcome.steps().size() >= 2
                    && !invalidOutcome.steps().get(0).ok() && !invalidOutcome.steps().get(1).ok());

            Path nonGit = Files.createDirectory(baseTempDir.resolve("orchestration-non-git"));
            Files.writeString(nonGit.resolve("fix.txt"), "fixword one\nfixword two\n");
            Path nonGitResults = Files.createDirectory(baseTempDir.resolve("non-git-results"));
            try (McpRegistry outsideRegistry = McpRegistry.open(nonGit, nonGitResults)) {
                expect("O14 Git MCP доступен из non-Git cwd", outsideRegistry.tools().size() == 4
                        && outsideRegistry.statuses().stream().allMatch(McpRegistry.ServerStatus::available));
                String badPath = nonGit.resolve("not-a-repository").toString();
                List<String> badFlow = List.of(
                        "{\"tool\":\"git.get-repository-status\",\"args\":{\"repoPath\":\"" + badPath + "\"}}",
                        "{\"tool\":\"pipeline.search\",\"args\":{\"root\":\"" + nonGit + "\",\"query\":\"fixword\"}}",
                        "{\"tool\":\"pipeline.summarize\",\"args\":{\"inputRef\":\"step:2\"}}",
                        "{\"tool\":\"pipeline.saveToFile\",\"args\":{\"inputRef\":\"step:3\",\"fileName\":\"non-git.md\"}}",
                        "{\"final\":\"Git-шаг не удался, но pipeline завершён.\"}");
                McpOrchestrator.Outcome badOutcome = new McpOrchestrator(outsideRegistry,
                        new SequenceModel(badFlow.toArray(String[]::new))).run("проверь fixword");
                expect("O15 ошибка Git не останавливает pipeline", badOutcome.steps().size() == 4
                        && !badOutcome.steps().get(0).ok() && badOutcome.steps().get(1).ok()
                        && Files.exists(nonGitResults.resolve("non-git.md"))
                        && badOutcome.answer().contains("non-git.md"));
                expect("O27 ошибка Git-шаг содержит repoPath", badOutcome.steps().get(0).args().containsKey("repoPath"));
                ToolRouter.Result validFromOutside = new ToolRouter(outsideRegistry).call(
                        "git.get-repository-status", Map.of("repoPath", repo.toString()));
                expect("O16 Git-репозиторий читается из non-Git cwd", validFromOutside.ok());
            }

            Path homeDemo = Files.createTempDirectory(Path.of(System.getProperty("user.home")), "selftest-fix-demo-");
            Files.createDirectories(homeDemo.resolve("sub"));
            Files.createDirectories(homeDemo.resolve(".ssh"));
            Files.writeString(homeDemo.resolve("a.txt"), "FixWord one\nfixword two\n");
            Files.writeString(homeDemo.resolve("sub/b.txt"), "FIXWORD sub\n");
            Files.writeString(homeDemo.resolve(".ssh/id_ed25519"), "fixword secret\n");
            Files.writeString(homeDemo.resolve("server.PEM"), "fixword cert\n");
            Path homeResults = Files.createDirectory(baseTempDir.resolve("home-demo-results"));
            try (McpRegistry homeRegistry = McpRegistry.open(homeDemo, homeResults)) {
                ToolRouter.Result homeSearch = new ToolRouter(homeRegistry).call("pipeline.search",
                        Map.of("root", "~/" + homeDemo.getFileName(), "query", "fixword"));
                expect("O21 tilde search находит 3 совпадения в 2 файлах", homeSearch.ok()
                        && Integer.valueOf(3).equals(homeSearch.data().get("totalMatches"))
                        && ((List<?>) homeSearch.data().get("matches")).size() == 3
                        && ((List<?>) homeSearch.data().get("matches")).stream()
                        .noneMatch(item -> String.valueOf(item).contains(".ssh") || String.valueOf(item).contains("PEM")));

                java.util.concurrent.atomic.AtomicReference<String> secondContext = new java.util.concurrent.atomic.AtomicReference<>();
                String firstRoot = homeDemo.toString();
                List<String> firstFlow = List.of(
                        "{\"tool\":\"git.get-repository-status\",\"args\":{\"repoPath\":\"" + firstRoot + "\"}}",
                        "{\"tool\":\"pipeline.search\",\"args\":{\"root\":\"" + firstRoot + "\",\"query\":\"TODO\"}}",
                        "{\"tool\":\"pipeline.summarize\",\"args\":{\"inputRef\":\"step:2\"}}",
                        "{\"tool\":\"pipeline.saveToFile\",\"args\":{\"inputRef\":\"step:3\",\"fileName\":\"first.md\"}}",
                        "{\"final\":\"first\"}");
                new McpOrchestrator(homeRegistry, new SequenceModel(firstFlow.toArray(String[]::new)))
                        .run("найди TODO");
                List<String> secondFlow = List.of(
                        "{\"tool\":\"git.get-repository-status\",\"args\":{\"repoPath\":\"" + firstRoot + "\"}}",
                        "{\"tool\":\"pipeline.search\",\"args\":{\"root\":\"" + firstRoot + "\",\"query\":\"fixword\"}}",
                        "{\"tool\":\"pipeline.summarize\",\"args\":{\"inputRef\":\"step:2\"}}",
                        "{\"tool\":\"pipeline.saveToFile\",\"args\":{\"inputRef\":\"step:3\",\"fileName\":\"second.md\"}}",
                        "{\"final\":\"second\"}");
                McpOrchestrator second = new McpOrchestrator(homeRegistry, new McpOrchestrator.Model() {
                    int index;
                    public String complete(String system, String prompt) {
                        if (index++ == 0) secondContext.set(system + "\n" + prompt);
                        return secondFlow.get(Math.min(index - 1, secondFlow.size() - 1));
                    }
                });
                McpOrchestrator.Outcome secondOutcome = second.run("найди все fixword");
                expect("O25 второй flow изолирован и ищет fixword", !secondOutcome.stopped()
                        && secondOutcome.answer().contains("second.md")
                        && secondOutcome.steps().stream().anyMatch(step -> "search".equals(step.tool()) && step.ok())
                        && secondContext.get() != null && !secondContext.get().contains("TODO")
                        && secondContext.get().contains("fixword"));

                List<String> badQueryFlow = List.of(
                        "{\"tool\":\"git.get-repository-status\",\"args\":{\"repoPath\":\"" + firstRoot + "\"}}",
                        "{\"tool\":\"pipeline.search\",\"args\":{\"root\":\"" + firstRoot + "\",\"query\":\"TODO\"}}",
                        "{\"tool\":\"pipeline.search\",\"args\":{\"root\":\"" + firstRoot + "\",\"query\":\"fixword\"}}",
                        "{\"tool\":\"pipeline.summarize\",\"args\":{\"inputRef\":\"step:3\"}}",
                        "{\"tool\":\"pipeline.saveToFile\",\"args\":{\"inputRef\":\"step:4\",\"fileName\":\"query.md\"}}",
                        "{\"final\":\"query fixed\"}");
                McpOrchestrator.Outcome badQuery = new McpOrchestrator(homeRegistry,
                        new SequenceModel(badQueryFlow.toArray(String[]::new))).run("найди fixword");
                expect("O26 query вне запроса отклоняется и flow продолжается", badQuery.steps().get(1).error()
                        .contains("query должен быть словом из запроса пользователя") && !badQuery.stopped());
            } finally {
                deleteRecursively(homeDemo);
            }

            List<String> correction = List.of(
                    "{\"tool\":\"server.pipeline.summarize\",\"args\":{\"inputRef\":\"step:2\"}}",
                    "{\"tool\":\"git.get-repository-status\",\"args\":{\"repoPath\":\"" + repo + "\"}}",
                    "{\"tool\":\"pipeline.search\",\"args\":{\"root\":\"" + repo + "\",\"query\":\"TODO\"}}",
                    "{\"tool\":\"pipeline.summarize\",\"args\":{\"inputRef\":\"step:3\"}}",
                    "{\"tool\":\"pipeline.saveToFile\",\"args\":{\"inputRef\":\"step:4\",\"fileName\":\"corrected.md\"}}",
                    "{\"final\":\"исправлено\"}");
            McpOrchestrator.Outcome corrected = new McpOrchestrator(registry,
                    new SequenceModel(correction.toArray(String[]::new))).run("исправь имя и найди TODO");
            expect("O17 неверное имя исправляется", corrected.steps().get(0).error().contains("Ближайшее точное имя")
                    && !corrected.stopped() && corrected.answer().contains("corrected.md"));

            StringBuilder large = new StringBuilder();
            for (int i = 0; i < 120; i++) large.append("fixword ").append("x".repeat(70)).append('\n');
            Files.writeString(repo.resolve("large.txt"), large.toString());
            java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
            List<String> largeFlow = List.of(
                    "{\"tool\":\"git.get-repository-status\",\"args\":{\"repoPath\":\"" + repo + "\"}}",
                    "{\"tool\":\"pipeline.search\",\"args\":{\"root\":\"" + repo + "\",\"query\":\"fixword\"}}",
                    "{\"tool\":\"pipeline.summarize\",\"args\":{\"inputRef\":\"step:2\"}}",
                    "{\"tool\":\"pipeline.saveToFile\",\"args\":{\"inputRef\":\"step:3\",\"fileName\":\"large.md\"}}",
                    "{\"final\":\"large done\"}");
            McpOrchestrator.Outcome largeOutcome = new McpOrchestrator(registry,
                    new McpOrchestrator.Model() { int i; public String complete(String s, String p) {
                        if (i++ == 2) captured.set(p); return largeFlow.get(Math.min(i - 1, largeFlow.size() - 1));
                    }}).run("large fixword");
            expect("O18 результат ограничен только в prompt", captured.get() != null
                    && captured.get().contains("обрезано") && largeOutcome.steps().size() == 4);

            McpOrchestrator.Outcome apiFailure = new McpOrchestrator(registry,
                    new McpOrchestrator.Model() { public String complete(String s, String p) {
                        throw new AgentException("Сервер вернул HTTP-статус 500. Запрос не выполнен.");
                    }}).run("api failure");
            expect("O19 API 500 повторяется дважды, итог — диагностика со статусом",
                    apiFailure.answer().contains("ошибка API модели: HTTP-статус 500")
                    && apiFailure.diagnostic() != null
                    && apiFailure.diagnostic().contains("статус=HTTP-статус 500")
                    && apiFailure.diagnostic().contains("повтор=2")
                    && Files.exists(results.resolve("orchestration.md")));

            // O28: после успешного saveToFile модель больше не вызывается.
            List<String> autoFlow = List.of(replies.get(0), replies.get(1), replies.get(2),
                    "{\"tool\":\"pipeline.saveToFile\",\"args\":{\"inputRef\":\"step:3\",\"fileName\":\"auto.md\"}}");
            java.util.concurrent.atomic.AtomicInteger autoCalls = new java.util.concurrent.atomic.AtomicInteger();
            McpOrchestrator.Outcome autoOutcome = new McpOrchestrator(registry,
                    new McpOrchestrator.Model() {
                        @Override public String complete(String system, String prompt) {
                            int call = autoCalls.getAndIncrement();
                            return call < autoFlow.size() ? autoFlow.get(call) : "{\"final\":\"ЛИШНИЙ ВЫЗОВ\"}";
                        }}).run("найди TODO");
            expect("O28 после saveToFile новых запросов к модели нет", !autoOutcome.stopped()
                    && autoCalls.get() == autoFlow.size()
                    && autoOutcome.answer().contains("auto.md")
                    && autoOutcome.answer().contains("SHA-256")
                    && Files.exists(results.resolve("auto.md")));

            // O29: 429, 429, затем успех — флоу завершается файлом.
            List<String> retryFlow = List.of(replies.get(0), replies.get(1), replies.get(2),
                    "{\"tool\":\"pipeline.saveToFile\",\"args\":{\"inputRef\":\"step:3\",\"fileName\":\"retry.md\"}}");
            java.util.concurrent.atomic.AtomicInteger retryCalls = new java.util.concurrent.atomic.AtomicInteger();
            McpOrchestrator.Outcome retryOutcome = new McpOrchestrator(registry,
                    new McpOrchestrator.Model() {
                        @Override public String complete(String system, String prompt) {
                            int call = retryCalls.getAndIncrement();
                            if (call < 2) {
                                throw new AgentException("Сервер вернул HTTP-статус 429. Запрос не выполнен.");
                            }
                            return retryFlow.get(Math.min(call - 2, retryFlow.size() - 1));
                        }}).run("найди TODO");
            String retryDiagnostics = "stopped=" + retryOutcome.stopped()
                    + ", reason=" + retryOutcome.stopReason()
                    + ", calls=" + retryCalls.get()
                    + ", steps=" + retryOutcome.steps().size()
                    + ", fileExists=" + Files.exists(results.resolve("retry.md"));
            expect("O29 429, 429, затем успех — флоу завершён (" + retryDiagnostics + ")",
                    !retryOutcome.stopped()
                    && retryCalls.get() == retryFlow.size() + 2
                    && retryOutcome.answer().contains("retry.md")
                    && Files.exists(results.resolve("retry.md")));

            // O30: медленный провайдер — таймаут запроса, повтор не выполняется.
            java.util.concurrent.atomic.AtomicInteger slowCalls = new java.util.concurrent.atomic.AtomicInteger();
            McpOrchestrator slowOrchestrator = new McpOrchestrator(registry,
                    new McpOrchestrator.Model() {
                        @Override public String complete(String system, String prompt) {
                            slowCalls.incrementAndGet();
                            try { Thread.sleep(500); } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return replies.get(0);
                        }}, 250);
            McpOrchestrator.Outcome slowOutcome = slowOrchestrator.run("найди TODO");
            expect("O30 медленный провайдер: таймаут запроса без повторов и с типом лимита",
                    slowOutcome.stopped()
                    && slowOutcome.answer().contains("таймаут запроса к модели (шаг 1")
                    && slowOutcome.answer().contains("Общая длительность флоу:")
                    && slowCalls.get() == 1);

            // O31: ответ в ```json-обёртке и с текстом вокруг разбирается.
            List<String> fencedFlow = List.of(
                    "```json\n{\"tool\":\"git.get-repository-status\",\"args\":{\"repoPath\":\"" + repo + "\"}}\n```",
                    "Вот результат анализа:\n{\"tool\":\"pipeline.search\",\"args\":{\"root\":\"" + repo
                            + "\",\"query\":\"TODO\"}}",
                    "{\"tool\":\"pipeline.summarize\",\"args\":{\"inputRef\":\"step:2\"}}",
                    "Итог вызова: {\"tool\":\"pipeline.saveToFile\",\"args\":{\"inputRef\":\"step:3\","
                            + "\"fileName\":\"fenced.md\"}}");
            McpOrchestrator.Outcome fencedOutcome = new McpOrchestrator(registry,
                    new SequenceModel(fencedFlow.toArray(String[]::new))).run("найди TODO");
            String fencedDiagnostics = "stopped=" + fencedOutcome.stopped()
                    + ", reason=" + fencedOutcome.stopReason()
                    + ", steps=" + fencedOutcome.steps()
                    + ", fileExists=" + Files.exists(results.resolve("fenced.md"));
            expect("O31 ответ в ```json-обёртке и с текстом вокруг разбирается ("
                    + fencedDiagnostics + ")", !fencedOutcome.stopped()
                    && fencedOutcome.steps().size() == 4
                    && fencedOutcome.steps().stream().noneMatch(step -> "invalid".equals(step.server()))
                    && Files.exists(results.resolve("fenced.md")));

            // O32: мусорный ответ — не invalid, строка «не распознан», стоп после 2 подряд.
            McpOrchestrator.Outcome garbageOutcome = new McpOrchestrator(registry,
                    new SequenceModel("К сожалению, не могу выполнить запрос без уточнений.",
                            "Ответ: конечно, сделаю всё в лучшем виде.")).run("найди TODO");
            expect("O32 мусорный ответ распознаётся как нераспознанный, стоп после 2 подряд",
                    garbageOutcome.stopped()
                    && garbageOutcome.answer().contains("модель не вернула корректный вызов")
                    && garbageOutcome.steps().size() == 2
                    && garbageOutcome.steps().stream().allMatch(step -> "модель".equals(step.server()))
                    && garbageOutcome.steps().stream().noneMatch(step -> "invalid".equals(step.server()))
                    && garbageOutcome.steps().get(0).error().contains("не могу выполнить"));
        }
    }
}
