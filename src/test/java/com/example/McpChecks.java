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

final class McpChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkMcpClient();
        checkGitMcp();
        checkMonitorStage();
        checkMonitorRuntimeLockCleanup();
    }

     static void checkMcpClient() throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        String classpath = Path.of("target/test-classes").toAbsolutePath()
                + File.pathSeparator
                + Path.of("target/classes").toAbsolutePath()
                + File.pathSeparator + System.getProperty("java.class.path");
        String command = java + " -cp \"" + classpath + "\" " + McpStubServer.class.getName();
        McpClientComponent client = new McpClientComponent();

        McpClientComponent.ToolListResult result = client.listTools(command);
        expect("MCP stdio соединение устанавливается", result.transport()
                == McpClientComponent.TransportType.STDIO);
        expect("tools/list возвращает ожидаемый инструмент", result.tools().size() == 1
                && "greet".equals(result.tools().get(0).name()));
        expect("описание инструмента сохраняется", "Returns a greeting".equals(
                result.tools().get(0).description()));

        McpClientComponent.ToolListResult empty = client.listTools(command + " empty");
        expect("пустой tools/list обрабатывается", empty.tools().isEmpty()
                && McpClientComponent.format(empty).contains("Инструментов нет"));

        try {
            client.listTools("/path/that/does/not/exist");
            expect("ошибка недоступного MCP-сервера обрабатывается", false);
        } catch (McpClientComponent.McpClientException e) {
            expect("ошибка недоступного MCP-сервера обрабатывается",
                    !e.getMessage().contains("время ожидания"));
        }

        try {
            client.listTools(command + " stderr");
            expect("stderr ошибки запуска показывается кратко", false);
        } catch (McpClientComponent.McpClientException e) {
            expect("stderr ошибки запуска показывается кратко",
                    e.getMessage().contains("Не удалось запустить")
                            && !e.getMessage().contains("время ожидания"));
            expect("stderr ошибки запуска не содержит секрет", !e.getMessage().contains(secretValue()));
        }

        try {
            new McpClientComponent(Duration.ofMillis(100)).listTools(command + " timeout");
            expect("таймаут MCP обрабатывается", false);
        } catch (McpClientComponent.McpClientException e) {
            expect("таймаут MCP обрабатывается", e.getMessage().contains("время ожидания"));
        }

        try {
            client.listTools("http://127.0.0.1:1/mcp");
            expect("ошибка HTTP MCP обрабатывается", false);
        } catch (McpClientComponent.McpClientException e) {
            expect("ошибка HTTP MCP не маскируется под stdio или timeout",
                    e.getMessage().contains("подключиться")
                            && !e.getMessage().contains("stdio")
                            && !e.getMessage().contains("время ожидания"));
        }

        String secret = "secret-value-must-not-leak";
        try {
            client.listTools("/missing " + secret);
        } catch (McpClientComponent.McpClientException e) {
            expect("секрет не попадает в ошибку", !e.getMessage().contains(secret));
        }
        expect("формат списка не печатает секреты", !McpClientComponent.format(result).contains(secret));

        McpClientComponent.ToolCallResult call = client.callTool(command, "greet",
                Map.of("name", "Ada"));
        expect("tools/call возвращает результат", !call.error() && call.text().contains("ok"));
        try {
            McpClientComponent.ToolCallResult missing = client.callTool(command, "missing", Map.of());
            expect("ошибка неизвестного инструмента обрабатывается",
                    missing.error() && missing.text().contains("not found"));
        } catch (McpClientComponent.McpClientException e) {
            expect("ошибка неизвестного инструмента обрабатывается",
                    e.getMessage().contains("tools/call"));
        }
        try {
            client.callTool(command + " stderr", "greet", Map.of());
            expect("раннее завершение stdio со stderr обрабатывается", false);
        } catch (McpClientComponent.McpClientException e) {
            expect("раннее завершение stdio со stderr не маскируется timeout",
                    e.getMessage().contains("запустить")
                            && !e.getMessage().contains("времени ожидания"));
        }
        try {
            client.callTool(command + " exit", "greet", Map.of());
            expect("раннее завершение stdio без stderr обрабатывается", false);
        } catch (McpClientComponent.McpClientException e) {
            expect("раннее завершение stdio без stderr не маскируется timeout",
                    !e.getMessage().contains("времени ожидания"));
        }
        try {
            new McpClientComponent(Duration.ofMillis(100))
                    .callTool(command + " long-timeout", "greet", Map.of());
            expect("живой MCP-процесс без ответа даёт timeout", false);
        } catch (McpClientComponent.McpClientException e) {
            expect("живой MCP-процесс без ответа даёт timeout",
                    e.getMessage().contains("время ожидания"));
        }
    }

     static void checkGitMcp() throws Exception {
        expect("Git MCP регистрирует get-repository-status",
                GitMcpServer.TOOL_NAME.equals(GitMcpServer.toolDefinition().name()));
        expect("Git MCP schema требует только repoPath",
                GitMcpServer.toolDefinition().inputSchema().get("required").toString().contains("repoPath")
                        && GitMcpServer.toolDefinition().inputSchema().get("additionalProperties").equals(false));
        checkGitExplainPromptContract();

        Path repo = Files.createTempDirectory(baseTempDir, "git-repo-");
        git(repo, "init", "-q");
        Files.writeString(repo.resolve("tracked.txt"), "one\n", StandardCharsets.UTF_8);
        git(repo, "add", "tracked.txt");
        git(repo, "-c", "user.name=SelfTest", "-c", "user.email=selftest@example.invalid",
                "commit", "-qm", "initial");
        GitRepositoryReader reader = new GitRepositoryReader(repo);
        GitRepositoryStatus clean = reader.read(repo);
        expect("чистый репозиторий определяется", clean.clean() && clean.headCommit() != null
                && !clean.detachedHead());

        Files.writeString(repo.resolve("tracked.txt"), "two\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("stage.txt"), "stage\n", StandardCharsets.UTF_8);
        git(repo, "add", "stage.txt");
        Path unusual = repo.resolve("space кирилл\nname.txt");
        Files.writeString(unusual, "untracked\n", StandardCharsets.UTF_8);
        GitRepositoryStatus mixed = reader.read(repo);
        expect("staged, unstaged и untracked разделяются", mixed.staged().contains("stage.txt")
                && mixed.unstaged().contains("tracked.txt")
                && mixed.untracked().contains("space кирилл\nname.txt") && !mixed.clean());

        git(repo, "add", "tracked.txt");
        Files.writeString(repo.resolve("tracked.txt"), "three\n", StandardCharsets.UTF_8);
        GitRepositoryStatus both = reader.read(repo);
        expect("файл может быть staged и unstaged одновременно",
                both.staged().contains("tracked.txt") && both.unstaged().contains("tracked.txt"));

        git(repo, "mv", "stage.txt", "renamed file.txt");
        GitRepositoryStatus renamed = reader.read(repo);
        expect("переименование возвращает новый путь", renamed.staged().contains("renamed file.txt")
                && !renamed.staged().contains("stage.txt"));

        Path deletedRepo = Files.createTempDirectory(baseTempDir, "git-deleted-");
        git(deletedRepo, "init", "-q");
        Files.writeString(deletedRepo.resolve("deleted.txt"), "delete\n", StandardCharsets.UTF_8);
        git(deletedRepo, "add", "deleted.txt");
        git(deletedRepo, "-c", "user.name=SelfTest", "-c", "user.email=selftest@example.invalid",
                "commit", "-qm", "initial");
        git(deletedRepo, "rm", "-q", "deleted.txt");
        GitRepositoryStatus deleted = new GitRepositoryReader(deletedRepo).read(deletedRepo);
        expect("удаление определяется как staged", deleted.staged().contains("deleted.txt"));

        Path conflictRepo = Files.createTempDirectory(baseTempDir, "git-conflict-");
        git(conflictRepo, "init", "-q");
        Files.writeString(conflictRepo.resolve("conflict.txt"), "base\n", StandardCharsets.UTF_8);
        git(conflictRepo, "add", "conflict.txt");
        git(conflictRepo, "-c", "user.name=SelfTest", "-c", "user.email=selftest@example.invalid",
                "commit", "-qm", "base");
        git(conflictRepo, "checkout", "-qb", "side");
        Files.writeString(conflictRepo.resolve("conflict.txt"), "side\n", StandardCharsets.UTF_8);
        git(conflictRepo, "commit", "-qam", "side");
        git(conflictRepo, "checkout", "-q", "-");
        Files.writeString(conflictRepo.resolve("conflict.txt"), "main\n", StandardCharsets.UTF_8);
        git(conflictRepo, "commit", "-qam", "main");
        ProcessBuilder mergeBuilder = new ProcessBuilder("git", "-C", conflictRepo.toString(), "merge", "side")
                .redirectErrorStream(true);
        Map<String, String> mergeEnv = mergeBuilder.environment();
        mergeEnv.put("GIT_AUTHOR_NAME", "selftest");
        mergeEnv.put("GIT_AUTHOR_EMAIL", "selftest@localhost");
        mergeEnv.put("GIT_COMMITTER_NAME", "selftest");
        mergeEnv.put("GIT_COMMITTER_EMAIL", "selftest@localhost");
        mergeEnv.put("GIT_CONFIG_NOSYSTEM", "1");
        mergeEnv.put("GIT_CONFIG_GLOBAL", "/dev/null");
        Process merge = mergeBuilder.start();
        activeProcesses.add(merge);
        merge.getInputStream().readAllBytes();
        merge.waitFor();
        activeProcesses.remove(merge);
        GitRepositoryStatus conflict = new GitRepositoryReader(conflictRepo).read(conflictRepo);
        expect("конфликт выделяется отдельно", conflict.conflicts().contains("conflict.txt")
                && !conflict.staged().contains("conflict.txt") && !conflict.unstaged().contains("conflict.txt"));

        Path detachedRepo = Files.createTempDirectory(baseTempDir, "git-detached-");
        git(detachedRepo, "init", "-q");
        Files.writeString(detachedRepo.resolve("a.txt"), "a\n", StandardCharsets.UTF_8);
        git(detachedRepo, "add", "a.txt");
        git(detachedRepo, "-c", "user.name=SelfTest", "-c", "user.email=selftest@example.invalid",
                "commit", "-qm", "initial");
        git(detachedRepo, "checkout", "--detach", "-q", "HEAD");
        GitRepositoryStatus detached = new GitRepositoryReader(detachedRepo).read(detachedRepo);
        expect("detached HEAD определяется", detached.detachedHead() && detached.branch() == null);

        Path empty = Files.createTempDirectory(baseTempDir, "git-empty-");
        git(empty, "init", "-q");
        GitRepositoryStatus noCommits = new GitRepositoryReader(empty).read(empty);
        expect("репозиторий без коммитов читается", noCommits.headCommit() == null && noCommits.branch() != null);

        Path outside = Files.createTempDirectory(baseTempDir, "git-outside-");
        try {
            reader.read(outside);
            expect("путь вне разрешённого корня отклоняется", false);
        } catch (GitRepositoryReader.GitRepositoryException e) {
            expect("путь вне разрешённого корня отклоняется", e.getMessage().contains("вне"));
        }
        Path nested = repo.resolve("nested");
        Files.createDirectories(nested);
        git(nested, "init", "-q");
        try {
            reader.read(nested);
            expect("вложенный репозиторий отклоняется", false);
        } catch (GitRepositoryReader.GitRepositoryException e) {
            expect("вложенный репозиторий отклоняется", e.getMessage().contains("другому"));
        }
        try {
            reader.read(Path.of("relative-repository"));
            expect("относительный путь отклоняется", false);
        } catch (GitRepositoryReader.GitRepositoryException e) {
            expect("относительный путь отклоняется", e.getMessage().contains("абсолютным"));
        }
        try {
            reader.read(baseTempDir.resolve("does-not-exist").toAbsolutePath());
            expect("несуществующий путь отклоняется", false);
        } catch (GitRepositoryReader.GitRepositoryException e) {
            expect("несуществующий путь отклоняется", e.getMessage().contains("не существует"));
        }
        Path bare = Files.createTempDirectory(baseTempDir, "git-bare-");
        git(bare, "init", "--bare", "-q");
        try {
            new GitRepositoryReader(bare);
            expect("bare-репозиторий отклоняется", false);
        } catch (GitRepositoryReader.GitRepositoryException e) {
            expect("bare-репозиторий отклоняется", e.getMessage().contains("Bare"));
        }

        Path worktree = Files.createTempDirectory(baseTempDir, "git-worktree-");
        Files.delete(worktree);
        git(repo, "worktree", "add", "-q", "-b", "selftest-worktree", worktree.toString());
        GitRepositoryStatus worktreeStatus = new GitRepositoryReader(worktree).read(worktree);
        expect("Git worktree читается как выбранный корень", worktreeStatus.repositoryRoot()
                .equals(worktree.toRealPath().toString()));

        McpSchema.CallToolResult valid = new GitMcpServer(repo).handle(
                McpSchema.CallToolRequest.builder().name(GitMcpServer.TOOL_NAME)
                        .arguments(Map.of("repoPath", repo.toString())).build());
        expect("Git MCP tools/call возвращает структуру", !Boolean.TRUE.equals(valid.isError())
                && valid.structuredContent().toString().contains("repositoryRoot"));
        McpSchema.CallToolResult invalid = new GitMcpServer(repo).handle(
                McpSchema.CallToolRequest.builder().name(GitMcpServer.TOOL_NAME)
                        .arguments(Map.of()).build());
        expect("пропущенный repoPath возвращает ошибку", Boolean.TRUE.equals(invalid.isError()));
        McpSchema.CallToolResult extra = new GitMcpServer(repo).handle(
                McpSchema.CallToolRequest.builder().name(GitMcpServer.TOOL_NAME)
                        .arguments(Map.of("repoPath", repo.toString(), "extra", true)).build());
        expect("лишний аргумент отклоняется", Boolean.TRUE.equals(extra.isError()));
        Files.writeString(repo.resolve("outside-root.txt"), "outside\n", StandardCharsets.UTF_8);
        Path subdirectory = repo.resolve("subdir");
        Files.createDirectories(subdirectory);
        FakeUi subdirUi = new FakeUi(
                TerminalUi.Input.command("/mcp git tools " + subdirectory),
                TerminalUi.Input.command("/mcp git status " + subdirectory),
                TerminalUi.Input.command("/exit"));
        String previousClasspath = System.getProperty("java.class.path");
        try {
            System.setProperty("java.class.path", buildClasspath());
            Main.runLoop(subdirUi, newAgentWithTempStore(
                    new Config("test-key", "https://127.0.0.1:1/v1/chat/completions", "test-model")),
                    "test-model");
        } finally {
            System.setProperty("java.class.path", previousClasspath);
        }
        String repositoryRoot = repo.toRealPath().toString();
        expect("CLI tools принимает подкаталог и запускает сервер с корнем",
                subdirUi.systems.stream().anyMatch(s -> s.contains("get-repository-status")));
        expect("CLI status подкаталога возвращает состояние всего репозитория",
                subdirUi.systems.stream().anyMatch(s -> s.contains(repositoryRoot)
                        && s.contains("outside-root.txt")));
        checkGitExplainGuards(repo);
    }

     static void checkGitExplainGuards(Path repo) throws Exception {
        Config config = new Config("test-key", "https://127.0.0.1:1/v1/chat/completions", "test-model");
        LlmAgent clearAgent = newAgentWithTempStore(config);
        String statusCommand = "/mcp git status " + repo;
        FakeUi clearUi = new FakeUi(TerminalUi.Input.command(statusCommand),
                TerminalUi.Input.command("/clear"), TerminalUi.Input.command("/mcp explain"),
                TerminalUi.Input.command("/exit"));
        clearUi.confirmClearAnswer = true;
        Main.runLoop(clearUi, clearAgent, "test-model");
        expect("/clear очищает последний Git-снимок",
                clearUi.systems.stream().anyMatch(s -> s.contains("Успешного Git-снимка нет")));

        LlmAgent blockedAgent = newAgentWithTempStore(config);
        blockedAgent.taskStart("Git explain guard");
        blockedAgent.taskBlock();
        FakeUi blockedUi = new FakeUi(TerminalUi.Input.command("/mcp explain"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(blockedUi, blockedAgent, "test-model");
        expect("BLOCKED запрещает /mcp explain без вызова модели",
                blockedUi.systems.stream().anyMatch(s -> s.contains("/mcp explain не вызывает модель"))
                        && blockedAgent.getHistory().isEmpty());

        LlmAgent failedStatusAgent = newAgentWithTempStore(config);
        FakeUi failedStatusUi = new FakeUi(TerminalUi.Input.command(statusCommand),
                TerminalUi.Input.command("/mcp git status " + repo.resolve("missing")),
                TerminalUi.Input.command("/mcp explain"), TerminalUi.Input.command("/exit"));
        Main.runLoop(failedStatusUi, failedStatusAgent, "test-model");
        expect("ошибка нового Git status инвалидирует прежний снимок",
                failedStatusUi.systems.stream().anyMatch(s -> s.contains("Успешного Git-снимка нет")));
        expect("ошибка предварительной проверки пути понятна",
                failedStatusUi.errors.stream().anyMatch(s -> s.contains("не существует")));

        Path nonGit = Files.createTempDirectory(baseTempDir, "git-cli-non-git-");
        FakeUi nonGitUi = new FakeUi(TerminalUi.Input.command(
                "/mcp git status " + nonGit), TerminalUi.Input.command("/exit"));
        Main.runLoop(nonGitUi, newAgentWithTempStore(config), "test-model");
        expect("CLI каталог без Git возвращает понятную ошибку",
                nonGitUi.errors.stream().anyMatch(s -> s.contains("не является Git-репозиторием")));
    }

     static void checkMonitorStage() throws Exception {
        expect("interval принимает нижнюю и верхнюю границу",
                MonitorSchedule.parseInterval("30s", "interval", 30, 86_400).toSeconds() == 30
                        && MonitorSchedule.parseInterval("24h", "interval", 30, 86_400).toSeconds() == 86_400);
        try {
            MonitorSchedule.parseInterval("29s", "interval", 30, 86_400);
            expect("interval ниже минимума отклоняется", false);
        } catch (MonitorException e) {
            expect("interval ниже минимума отклоняется", true);
        }
        try {
            MonitorSchedule.parseInterval("5d", "interval", 30, 86_400);
            expect("неверный формат interval отклоняется", false);
        } catch (MonitorException e) {
            expect("неверный формат interval отклоняется", true);
        }

        Path storeFile = Files.createTempDirectory(baseTempDir, "monitor-store-")
                .resolve("git-monitor.json");
        MonitorStore store = new MonitorStore(storeFile);
        Path repo = Files.createTempDirectory(baseTempDir, "monitor-repo-");
        git(repo, "init", "-q");
        MonitorSchedule schedule = store.create(repo.toRealPath().toString(),
                Duration.ofSeconds(30), Duration.ofMinutes(1));
        expect("monitor schedule создаётся", store.schedules().size() == 1
                && store.schedule(schedule.id()).enabled());
        try {
            store.create(repo.toRealPath().toString(), Duration.ofSeconds(30), Duration.ofMinutes(1));
            expect("дубликат monitor schedule отклоняется", false);
        } catch (MonitorException e) {
            expect("дубликат monitor schedule отклоняется", true);
        }
        store.setEnabled(schedule.id(), false);
        expect("monitor schedule отключается", !store.schedule(schedule.id()).enabled());
        store.setEnabled(schedule.id(), true);

        String previousClasspath = System.getProperty("java.class.path");
        MonitorRunner.Result run;
        try {
            System.setProperty("java.class.path", buildClasspath());
            run = new MonitorRunner(store).run(schedule.id());
        } finally {
            System.setProperty("java.class.path", previousClasspath);
        }
        expect("MonitorRunner получает настоящий Git snapshot через MCP",
                run.run().success() && run.summary() != null && store.lastSnapshot(schedule.id()) != null);

        try (MonitorStore.RuntimeLease ignored = store.tryRuntimeLock(schedule.id())) {
            MonitorRunner.Result busy = new MonitorRunner(store).run(schedule.id());
            expect("занятый runtime-lock возвращает BUSY", !busy.run().success()
                    && "BUSY".equals(busy.run().errorCode()));
        }

        for (int i = 0; i < 105; i++) {
            MonitorRun runRecord = new MonitorRun(schedule.id(), Instant.now().toString(),
                    Instant.now().toString(), false, 1, null, false, null, false,
                    0, 0, 0, 0, "TEST", "safe");
            store.record(runRecord, null, null);
        }
        expect("retention запусков ограничен 100", store.runs(schedule.id()).size() == 100);
        MonitorSummary summary = new MonitorSummary(schedule.id(), Instant.now().minusSeconds(60).toString(),
                Instant.now().toString(), 1, 0, 0, Instant.now().toString(), null,
                false, 2, 3, 4, 0, 1, 2, 3, 0, List.of("main -> feature"),
                List.of("aaa -> bbb"), false);
        for (int i = 0; i < 55; i++) store.recordSummary(summary);
        expect("retention сводок ограничен 50", store.summaries(schedule.id()).size() == 50);

        MonitorRun first = new MonitorRun("aggregate", Instant.now().minusSeconds(30).toString(),
                Instant.now().minusSeconds(29).toString(), true, 1, "main", false, "a", true,
                1, 2, 3, 0, null, null);
        MonitorRun second = new MonitorRun("aggregate", Instant.now().toString(), Instant.now().toString(),
                true, 1, "feature", true, "b", false, 3, 1, 5, 1, null, null);
        MonitorSchedule aggregateSchedule = MonitorSchedule.create("/tmp/aggregate",
                Duration.ofSeconds(30), Duration.ofMinutes(1));
        MonitorSummary aggregate = MonitorAggregator.aggregate(aggregateSchedule,
                List.of(first, second), Instant.now());
        expect("aggregator считает дельты и branch/head changes",
                aggregate.stagedDelta() == 2 && aggregate.unstagedDelta() == -1
                        && aggregate.untrackedDelta() == 2 && aggregate.conflictsDelta() == 1
                        && aggregate.branchChanges().contains("main -> feature")
                        && aggregate.headChanges().contains("a -> b") && aggregate.detachedHead());

        previousClasspath = System.getProperty("java.class.path");
        try {
            System.setProperty("java.class.path", buildClasspath());
            String monitorCommand = selfTestMcpCommand("git-monitor");
            McpClientComponent.ToolListResult monitorTools = new McpClientComponent()
                    .listTools(monitorCommand);
            expect("Git monitor MCP регистрирует четыре инструмента",
                    monitorTools.tools().size() == 4
                            && monitorTools.tools().stream().anyMatch(t -> t.name().equals("schedule-git-monitor"))
                            && monitorTools.tools().stream().anyMatch(t -> t.name().equals("list-git-monitors"))
                            && monitorTools.tools().stream().anyMatch(t -> t.name().equals("get-git-monitor-summary"))
                            && monitorTools.tools().stream().anyMatch(t -> t.name().equals("disable-git-monitor")));
            McpClientComponent.ToolCallResult invalidMonitorCall = new McpClientComponent()
                    .callTool(monitorCommand, "schedule-git-monitor", Map.of("extra", true));
            expect("Git monitor MCP отклоняет неверные аргументы",
                    invalidMonitorCall.error());
            McpClientComponent.ToolListResult portable = new McpClientComponent()
                    .listTools(selfTestMcpCommand("git-monitor"));
            expect("portable MCP режим из текущей сборки возвращает monitor tools",
                    portable.tools().size() == 4
                            && portable.tools().stream().anyMatch(t -> t.name().equals("get-git-monitor-summary")));
            try {
                new McpClientComponent().listTools(selfTestMcpCommand("unknown"));
                expect("неизвестный portable MCP server отклоняется", false);
            } catch (McpClientComponent.McpClientException e) {
                expect("неизвестный portable MCP server отклоняется",
                        !e.getMessage().contains("target/") && !e.getMessage().contains("stack trace"));
            }
            FakeUi routed = new FakeUi(
                    TerminalUi.Input.command("/mcp monitor call schedule-git-monitor {\"extra\":true}"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(routed, newAgentWithTempStore(
                    new Config("test-key", "https://127.0.0.1:1/v1/chat/completions", "test-model")),
                    "test-model");
            expect("/mcp monitor call маршрутизируется в monitor MCP",
                    routed.errors.stream().noneMatch(s -> s.contains("Использование: /mcp tools")));
        } finally {
            System.setProperty("java.class.path", previousClasspath);
        }

        try {
            store.remove(schedule.id());
            expect("monitor schedule удаляется", store.schedules().isEmpty());
        } catch (MonitorException e) {
            expect("monitor schedule удаляется", false);
        }
    }

     static void checkMonitorRuntimeLockCleanup() throws Exception {
        MonitorStore store = workerStore("runtime-lock-cleanup");
        MonitorSchedule schedule = workerSchedule(store, baseTempDir, "runtime-lock-cleanup");
        Path runtime = store.file().resolveSibling(store.file().getFileName() + "."
                + schedule.id() + ".run.lock");

        MonitorStore.RuntimeLease first = store.tryRuntimeLock(schedule.id());
        expect("runtime-lock T1 захватывается и создаётся", first != null && Files.exists(runtime));
        first.close();
        first.close();
        expect("runtime-lock T1 удалён после успешного close", !Files.exists(runtime));

        MonitorStore.RuntimeLease second = store.tryRuntimeLock(schedule.id());
        expect("runtime-lock T3 повторный запуск захватывается", second != null);
        second.close();
        expect("runtime-lock T3 после повторного запуска удалён", !Files.exists(runtime));

        Files.createFile(runtime);
        MonitorStore.RuntimeLease stale = store.tryRuntimeLock(schedule.id());
        expect("runtime-lock T4 мёртвый lock-файл не мешает запуску", stale != null);
        stale.close();
        expect("runtime-lock T4 мёртвый lock-файл удалён", !Files.exists(runtime));

        Path storeLock = store.file().resolveSibling(store.file().getFileName() + ".lock");
        store.schedules();
        expect("runtime-lock T8 основной monitor-store lock существует", Files.exists(storeLock));

        MonitorStore.setFileKeyReaderForTests(path -> null);
        try {
            MonitorStore.RuntimeLease noKey = store.tryRuntimeLock(schedule.id());
            expect("runtime-lock T7 при fileKey == null захват проходит", noKey != null);
            noKey.close();
            expect("runtime-lock T7 при fileKey == null файл сохраняется", Files.exists(runtime));
            Files.deleteIfExists(runtime);
        } finally {
            MonitorStore.setFileKeyReaderForTests(null);
        }

        java.util.concurrent.atomic.AtomicInteger keys = new java.util.concurrent.atomic.AtomicInteger();
        MonitorStore.setFileKeyReaderForTests(path -> switch (keys.getAndIncrement()) {
            case 0 -> "old-owner";
            case 1 -> "replaced-path";
            default -> "new-owner";
        });
        try {
            MonitorStore.RuntimeLease replaced = store.tryRuntimeLock(schedule.id());
            expect("runtime-lock T6 подмена файла вызывает повторный захват", replaced != null
                    && keys.get() >= 4);
            replaced.close();
            expect("runtime-lock T6 после повторного захвата файл удалён", !Files.exists(runtime));
        } finally {
            MonitorStore.setFileKeyReaderForTests(null);
            Files.deleteIfExists(runtime);
        }

        java.util.concurrent.atomic.AtomicReference<FileChannel> contenderChannel =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<FileLock> contenderLockRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        MonitorStore.setRuntimeLockDeleteBarrierForTests(() -> {
            try {
                FileChannel channel = FileChannel.open(runtime, java.nio.file.StandardOpenOption.READ,
                        java.nio.file.StandardOpenOption.WRITE);
                contenderChannel.set(channel);
                try {
                    contenderLockRef.set(channel.tryLock());
                } catch (java.nio.channels.OverlappingFileLockException ignored) {
                    contenderLockRef.set(null);
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        MonitorStore.RuntimeLease owner = store.tryRuntimeLock(schedule.id());
        owner.close();
        MonitorStore.setRuntimeLockDeleteBarrierForTests(null);
        FileChannel contender = contenderChannel.get();
        boolean pathMissingAfterOwnerDelete;
        try {
            Files.readAttributes(runtime, java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            pathMissingAfterOwnerDelete = false;
        } catch (IOException e) {
            pathMissingAfterOwnerDelete = true;
        }
        expect("runtime-lock R1 старый открытый канал видит удалённый путь",
                contenderLockRef.get() == null && pathMissingAfterOwnerDelete);
        if (contenderLockRef.get() != null) contenderLockRef.get().release();
        contender.close();
        MonitorStore.RuntimeLease replacement = store.tryRuntimeLock(schedule.id());
        MonitorStore.RuntimeLease blocked = store.tryRuntimeLock(schedule.id());
        expect("runtime-lock R1 новый владелец перезахвачен, C получает BUSY",
                replacement != null && blocked == null);
        replacement.close();
        expect("runtime-lock R1 после владельца B файл удалён", !Files.exists(runtime));

        MonitorStore badStore = workerStore("runtime-lock-error");
        MonitorSchedule bad = badStore.create(baseTempDir.resolve("does-not-exist-runtime-repo")
                .toString(), Duration.ofSeconds(30), Duration.ofMinutes(1));
        Path badRuntime = badStore.file().resolveSibling(badStore.file().getFileName() + "."
                + bad.id() + ".run.lock");
        MonitorRunner.Result failed;
        try (ClasspathOverride ignored = new ClasspathOverride()) {
            failed = new MonitorRunner(badStore).run(bad.id());
        }
        expect("runtime-lock T2 ошибка запуска сохраняет неуспешный результат",
                !failed.run().success());
        expect("runtime-lock T2 после исключения удалён", !Files.exists(badRuntime));

        Path holderReady = Files.createTempFile(baseTempDir, "runtime-holder-", ".ready");
        Files.deleteIfExists(holderReady);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process holder = new ProcessBuilder(java, "-cp", withChildClasspath(),
                RuntimeLockHolder.class.getName(), store.file().toString(), schedule.id(),
                holderReady.toString()).start();
        activeProcesses.add(holder);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(holderReady) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            MonitorRunner.Result busy = new MonitorRunner(store).run(schedule.id());
            expect("runtime-lock R2 дочерний владелец возвращает BUSY",
                    !busy.run().success() && "BUSY".equals(busy.run().errorCode())
                            && "Расписание уже выполняется.".equals(busy.run().errorMessage()));
        } finally {
            try { holder.getOutputStream().close(); } catch (IOException ignored) { }
            if (!holder.waitFor(10, TimeUnit.SECONDS)) holder.destroyForcibly();
            activeProcesses.remove(holder);
        }
        expect("runtime-lock R2 после освобождения дочернего владельца удалён",
                !Files.exists(runtime));
    }

     static void checkGitExplainPromptContract() {
        GitRepositoryStatus status = new GitRepositoryStatus(
                "/tmp/repo", null, true, null, false,
                List.of("same.txt"), List.of("same.txt"),
                List.of("$(do-not-run).txt"), List.of("conflict.txt"));
        String prompt = Main.buildMcpExplainPrompt(status);
        expect("explain prompt использует структурированный snapshot", prompt.contains("repositoryRoot")
                && prompt.contains("detachedHead") && prompt.contains("same.txt")
                && prompt.contains("$(do-not-run).txt"));
        expect("explain prompt явно запрещает доверять предыдущим ответам",
                prompt.contains("Предыдущие сообщения пользователя и ответы ассистента не являются"));
        expect("explain prompt задаёт границы неизвестных данных",
                prompt.contains("содержимое diff") && prompt.contains("remote")
                        && prompt.contains("прохождение тестов") && prompt.contains(".gitignore"));
        expect("explain prompt не навязывает команды и неизвестные remote/ветку",
                !prompt.contains("mvn test") && !prompt.contains("gradle test")
                        && !prompt.contains("origin/") && !prompt.contains("first-mcp-tool"));
        expect("explain prompt объясняет staged и unstaged отдельно",
                prompt.contains("staged означает изменения в индексе")
                        && prompt.contains("unstaged — изменения вне индекса")
                        && prompt.contains("одновременно staged и unstaged"));
    }
}
