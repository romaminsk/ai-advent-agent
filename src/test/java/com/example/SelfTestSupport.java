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

class SelfTestSupport {


     static final ObjectMapper MAPPER = new ObjectMapper();
     static final char[] KEYSTORE_PASSWORD = "changeit".toCharArray();

    /** Ожидаемый текст системной инструкции агента (базовая часть без блоков памяти). */
     static final String SYSTEM_PROMPT_TEXT = ContextBuilder.BASE_SYSTEM_PROMPT;

    /** Детерминированный счётчик для проверок: 1 токен = 1 символ текста. */
     static final TokenCounter LEN_COUNTER = new TokenCounter() {
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
     static Path baseTempDir;

    /**
     * Единый счётчик пройденных проверок и текущая группа (для отчёта):
     * инкрементируется ровно в одном месте — в {@link #expect}; итоговое
     * число выводится из него же. Падение называет группу, проверку
     * и разницу «ожидалось / получено» без голого assert.
     */
     static int passed = 0;
     static final List<String> failures = new ArrayList<>();
     static final Set<Process> activeProcesses = new CopyOnWriteArraySet<>();

    /** Текущая группа проверок (слои архитектуры), задётся методом group(). */
     static String currentGroup = "без группы";
     static final String T10_TOKEN = "fake-secret-XYZ";
     static final String SUMMARY_MARKER = "Ты сжимаешь историю диалога";
     static final String FACTS_MARKER = "Ты обновляешь блок фактов";

    /** Задание текущей группы проверок (для сообщений об ошибках). */
     static void group(String name) {
        currentGroup = name;
        System.out.println("--- Группа: " + name);
    }
     static void expect(String description, boolean condition) {
        if (!condition) {
            StackTraceElement caller = StackWalker.getInstance().walk(stream ->
                    stream.skip(1).findFirst().orElseThrow().toStackTraceElement());
            String failure = "группа: " + currentGroup + "; проверка: " + description
                    + "; файл: " + caller.getFileName() + ":" + caller.getLineNumber()
                    + "; ожидалось: истина, получено: ложь";
            failures.add(failure);
            System.out.println("FAIL: " + failure);
            return;
        }
        passed++;
        System.out.println("OK: " + description);
    }
     static boolean liveChecks(List<ToolRouter.Step> steps, Path results, String secret) {
        if (steps.size() > 8) return false;
        int git = indexOf(steps, "git", "get-repository-status");
        int search = indexOf(steps, "pipeline", "search");
        int summarize = indexOf(steps, "pipeline", "summarize");
        int save = indexOf(steps, "pipeline", "saveToFile");
        if (git < 0 || search < 0 || summarize < 0 || save < 0 || !(git < save && search < summarize && summarize < save)) return false;
        if (!("step:" + (search + 1)).equals(steps.get(summarize).inputRef())
                || !("step:" + (summarize + 1)).equals(steps.get(save).inputRef())) return false;
        try {
            String contents;
            try (var files = Files.list(results)) {
                contents = files.filter(Files::isRegularFile)
                        .map(path -> { try { return Files.readString(path); } catch (IOException e) { return ""; } })
                        .reduce("", String::concat);
            }
            return !contents.contains(".ssh") && !contents.contains("server.PEM")
                    && (secret == null || !contents.contains(secret));
        } catch (IOException e) { return false; }
    }
     static int indexOf(List<ToolRouter.Step> steps, String server, String tool) {
        for (int i = 0; i < steps.size(); i++) if (server.equals(steps.get(i).server()) && tool.equals(steps.get(i).tool()) && steps.get(i).ok()) return i;
        return -1;
    }
     static boolean resultFilesContain(Path directory, String text) {
        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> {
                try { return Files.readString(path).contains(text); }
                catch (IOException e) { return false; }
            });
        } catch (IOException e) { return false; }
    }
     static Map<String, String> readDotEnv() throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        Path file = Path.of(".env");
        if (!Files.isRegularFile(file)) {
            try {
                Path location = Path.of(SelfTest.class.getProtectionDomain().getCodeSource()
                        .getLocation().toURI()).toAbsolutePath();
                Path project = Files.isDirectory(location) ? location.getParent().getParent() : location.getParent();
                file = project.resolve(".env");
            } catch (Exception ignored) { }
        }
        if (!Files.isRegularFile(file)) return values;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            if (value.startsWith("export ")) value = value.substring(7).trim();
            int equals = value.indexOf('=');
            if (equals <= 0) continue;
            String key = value.substring(0, equals).trim();
            String parsed = value.substring(equals + 1).trim();
            if ((parsed.startsWith("\"") && parsed.endsWith("\"")) || (parsed.startsWith("'") && parsed.endsWith("'"))) {
                parsed = parsed.substring(1, parsed.length() - 1);
            }
            values.put(key, parsed);
        }
        return values;
    }
     static void printSummary() {
        int skipped = 0;
        int total = passed + failures.size() + skipped;
        System.out.println("SelfTest summary: passed=" + passed + ", failed=" + failures.size()
                + ", skipped=" + skipped + ", total=" + total);
        for (String failure : failures) System.out.println("FAILED: " + failure);
    }
     static String stackTrace(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
     static void cleanupProcesses() {
        for (Process process : activeProcesses) {
            if (!process.isAlive()) {
                activeProcesses.remove(process);
                continue;
            }
            process.destroy();
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            activeProcesses.remove(process);
        }
    }
     static String selfTestMcpCommand(String server) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return "\"" + java + "\" -cp \"" + buildClasspath() + "\" "
                + Main.class.getName() + " --mcp-server " + server;
    }
     static MonitorStore workerStore(String label) throws IOException {
        Path file = Files.createTempDirectory(baseTempDir, "worker-" + label + "-")
                .resolve("git-monitor.json");
        return new MonitorStore(file);
    }
     static MonitorSchedule workerSchedule(MonitorStore store, Path baseTemp,
                                                  String label) throws Exception {
        Path repo = Files.createTempDirectory(baseTemp, "worker-" + label + "-repo-");
        git(repo, "init", "-q");
        return store.create(repo.toRealPath().toString(),
                Duration.ofSeconds(30), Duration.ofMinutes(1));
    }
     static String withChildClasspath() {
        try {
            return buildClasspath();
        } catch (Exception e) {
            throw new AssertionError("buildClasspath failed", e);
        }
    }
     static Process startWorkerProcess(Path homeDir, Path stdout, Path stderr,
                                              Map<String, String> extraEnv) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = List.of(javaBin,
                "-Duser.home=" + homeDir.toAbsolutePath(),
                "-cp", withChildClasspath(),
                "com.example.Main", "--background");
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Map<String, String> env = processBuilder.environment();
        // Никаких ключей модели и прочих секретов родительского окружения.
        env.keySet().removeIf(key -> key.startsWith("LLM_") || key.contains("TOKEN")
                || key.contains("SECRET") || key.contains("API"));
        env.put("HOME", homeDir.toAbsolutePath().toString());
        env.put("GIT_AUTHOR_NAME", "selftest");
        env.put("GIT_AUTHOR_EMAIL", "selftest@localhost");
        env.put("GIT_COMMITTER_NAME", "selftest");
        env.put("GIT_COMMITTER_EMAIL", "selftest@localhost");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        for (Map.Entry<String, String> extra : extraEnv.entrySet()) {
            env.put(extra.getKey(), extra.getValue());
        }
        processBuilder.redirectOutput(stdout.toFile());
        processBuilder.redirectError(stderr.toFile());
        Process process = processBuilder.start();
        activeProcesses.add(process);
        return process;
    }
     static Path workerHeartbeatFile(Path homeDir) {
        return homeDir.resolve(".ai-advent-agent").resolve("monitor-worker.json");
    }
     static String awaitWorkerHeartbeat(Path homeDir, String state, long seconds)
            throws Exception {
        Path file = workerHeartbeatFile(homeDir);
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        String result = null;
        while (System.nanoTime() < deadline) {
            if (Files.exists(file)) {
                try {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    String actualState = MAPPER.readTree(content).path("state").asText(null);
                    if (state.equals(actualState)) {
                        return content;
                    }
                    result = content;
                } catch (Exception readError) {
                    // Файл мог быть только что перезаписан — пробуем дальше.
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("heartbeat \"" + state + "\" не появился за " + seconds
                + " с; последний: " + result);
    }
     static String workerIoLineCheck(Process process, Path stdout, Path stderr)
            throws Exception {
        String output = Files.readString(stdout, StandardCharsets.UTF_8);
        String errors = Files.readString(stderr, StandardCharsets.UTF_8);
        // Все содержательные строки stdout — служебные строки worker'а.
        for (String line : output.split("\\R")) {
            if (!line.isBlank()) {
                expect("T1 stdout worker'а содержит только служебные строки",
                        line.contains("monitor-worker"));
            }
        }
        expect("T1 stderr worker'а пуст", errors.isBlank());
        return output;
    }
     static void compileSilentStub(Path stubDir) throws Exception {
        String source = String.join("\n",
                "package com.example;",
                "public final class GitMcpServer {",
                "    public static void main(String[] args) throws Exception {",
                "        Thread.currentThread().join();",
                "    }",
                "}");
        Path javaFile = stubDir.resolve("com/example/GitMcpServer.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, source, StandardCharsets.UTF_8);
        String javac = Path.of(System.getProperty("java.home"), "bin", "javac").toString();
        Process compile = new ProcessBuilder(javac, "-d", stubDir.toString(),
                javaFile.toString()).inheritIO().start();
        if (compile.waitFor() != 0) {
            throw new IllegalStateException("Не удалось скомпилировать silent-stub");
        }
    }
     static Path pipelineCorpus(Path base) throws Exception {
        Path root = Files.createTempDirectory(base, "pipeline-src-");
        Files.writeString(root.resolve("alpha.txt"), "Первый элемент: MonitorStore внутри.\n"
                + "Вторая строка не про то.\nMonitorStore снова\nПривет Кириллица\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("nested"));
        Files.writeString(root.resolve("nested/beta.md"), "monitorstore строчными\nбез совпадений\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "MonitorStore в .git\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".env"), "TOKEN=MonitorStore-env-secret\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".env.local"), "KEY=MonitorStore-env-local\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".envrc"), "back=MonitorStore-envrc\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".envproduction"), "HOST=MonitorStore-envproduction\n",
                StandardCharsets.UTF_8);
        // Каталоги и файлы секретов P4 (регистронезависимо по именам).
        Files.createDirectories(root.resolve(".ssh"));
        Files.writeString(root.resolve(".ssh/config"), "KEYS=MonitorStore в .ssh\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".ssh/id_ed25519"), "KEY=MonitorStore в id_ed25519\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve(".gnupg"));
        Files.writeString(root.resolve(".gnupg/x"), "GNUPG=MonitorStore в .gnupg\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve(".aws"));
        Files.writeString(root.resolve(".aws/credentials"), "AWS=MonitorStore в .aws\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve(".kube"));
        Files.writeString(root.resolve(".kube/config"), "KUBE=MonitorStore в .kube\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve(".docker"));
        Files.writeString(root.resolve(".docker/config.json"), "DOCKER=MonitorStore в .docker\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("server.PEM"), "PEM=MonitorStore в pem\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("tls.key"), "KEY=MonitorStore в key\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("cert.p12"), "P12=MonitorStore в p12\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("id_rsa_bkp"), "RSA=MonitorStore в id_rsa\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".netrc"), "NETRC=MonitorStore в netrc\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".pgpass"), "PGPASS=MonitorStore в pgpass\n", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/skip.txt"), "MonitorStore в target\n", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("node_modules"));
        Files.writeString(root.resolve("node_modules/skip.js"), "MonitorStore в node_modules\n",
                StandardCharsets.UTF_8);
        byte[] marker = "MonitorStore в сплошных байтах".getBytes(StandardCharsets.UTF_8);
        // Бинарный файл: нулевой байт в первых 8 КБ.
        byte[] binary = new byte[8500];
        System.arraycopy(marker, 0, binary, 0, marker.length);
        binary[8192] = 0;
        Files.write(root.resolve("binary.bin"), binary);
        // Больше 1 МБ.
        byte[] big = new byte[1024 * 1024 + 1];
        System.arraycopy(marker, 0, big, 0, marker.length);
        for (int i = marker.length; i < big.length; i++) big[i] = 'x';
        Files.write(root.resolve("big.txt"), big);
        // Симлинк наружу.
        Path outside = Files.createTempDirectory(base, "pipeline-outside-");
        Files.writeString(outside.resolve("secret.md"), "MonitorStore за пределами корня\n",
                StandardCharsets.UTF_8);
        Files.createSymbolicLink(root.resolve("link.txt"), outside.resolve("secret.md"));
        return root;
    }
     static String searchError(Path root, String query, Integer maxResults)
            throws PipelineToolException {
        try {
            FileSearcher.search(root, query, maxResults);
            failures.add("searchError: ожидался PipelineToolException для root=" + root);
            System.out.println("FAIL: searchError ожидал PipelineToolException для " + root);
            return null;
        } catch (PipelineToolException e) {
            return e.getMessage() == null ? "" : e.getMessage();
        }
    }
     static String expectedPlural(int count, String one, String few, String many) {
        return SearchSummarizer.ruPlural(count, one, few, many);
    }
     static String stepSummaryForCounts(int matchesTotal, int files, boolean truncated,
                                               java.lang.reflect.Method stepSummary)
            throws Exception {
        int shown = truncated ? Math.min(matchesTotal, 50) : matchesTotal;
        List<Map<String, Object>> matches = new java.util.ArrayList<>();
        for (int i = 0; i < shown; i++) {
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("file", "f" + (files <= 0 ? 0 : i % files) + ".txt");
            match.put("line", i + 1);
            match.put("text", "строка " + i);
            matches.add(match);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalMatches", matchesTotal);
        data.put("truncated", truncated);
        data.put("matches", matches);
        PipelineRunner.StepResult step = new PipelineRunner.StepResult(1, "search", true, null,
                data, 0);
        return (String) stepSummary.invoke(null, step);
    }
     static String pipelineChildCommand(Path results) {
        try (ClasspathOverride ignored = new ClasspathOverride()) {
            return PipelineMcpServer.javaCommand(results);
        }
    }
     static PipelineRunner pipelineRunner(Path results) {
         return new PipelineRunner(results, pipelineChildCommand(results), Duration.ofSeconds(2));
    }
     static boolean noPipelineChildProcess(Path results) throws Exception {
        String marker = "PipelineMcpServer --results-dir " + results;
        for (int i = 0; i < 30; i++) {
            boolean alive = ProcessHandle.allProcesses().anyMatch(process -> process.info()
                    .commandLine().map(line -> line.contains(marker)).orElse(false));
            if (!alive) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }
     static void pipelineChildProcessLeakCheck(boolean condition, String when) {
        expect("дочерний pipeline-процесс завершён " + when, condition);
    }
     static String searcherJson(FileSearcher.SearchResult search) {
        return PipelineCanonicalJson.canonical(search.toMap());
    }
     static String shortName(String name) {
        return name.length() <= 16 ? name : name.substring(0, 16);
    }
     static int resultsFileCount(Path results) throws IOException {
        try (var list = Files.list(results)) {
            return (int) list.count();
        }
    }
     static List<String> resultsFileNames(Path results) throws IOException {
        try (var list = Files.list(results)) {
            return list.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
     static boolean onlyMdFiles(Path results) throws IOException {
        boolean onlyMd = true;
        try (var list = Files.list(results)) {
            for (Path item : list.toList()) {
                if (!item.getFileName().toString().endsWith(".md")) onlyMd = false;
            }
        }
        return onlyMd;
    }
     static String pipelineRunUsage(LlmAgent agent) {
        FakeUi ui = new FakeUi(TerminalUi.Input.command("/mcp pipeline run"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(ui, agent, "test-model");
        return String.join("\n", ui.errors);
    }
     static String pipelineCallUsage(LlmAgent agent) {
        FakeUi ui = new FakeUi(TerminalUi.Input.command("/mcp pipeline call не-инструмент {}"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(ui, agent, "test-model");
        return String.join("\n", ui.errors);
    }
     static void git(Path directory, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("GIT_AUTHOR_NAME", "selftest");
        env.put("GIT_AUTHOR_EMAIL", "selftest@localhost");
        env.put("GIT_COMMITTER_NAME", "selftest");
        env.put("GIT_COMMITTER_EMAIL", "selftest@localhost");
        Process process = builder.start();
        activeProcesses.add(process);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        activeProcesses.remove(process);
        if (exitCode != 0) {
            throw new AssertionError("SelfTest git command failed: " + output);
        }
    }
     static String payload(int start) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(start + i).append(".0");
        }
        return out.toString();
    }
     static String secretValue() {
        return "secret-value-must-not-leak";
    }
     static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
     static MemoryStore tempMemoryStore() throws IOException {
        return new MemoryStore(
                Files.createTempDirectory(baseTempDir, "mem-").resolve("memory.json"));
    }
     static JsonConversationStore tempStore() throws IOException {
        Path dir = Files.createTempDirectory(baseTempDir, "hist-");
        return new JsonConversationStore(dir.resolve("conversation.json"));
    }
     static void deleteRecursively(Path path) {
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
     static String expectConfigError(String key, String url, String model) {
        try {
            new Config(key, url, model);
        } catch (AgentException e) {
            return e.getMessage();
        }
        return "";
    }
     static LlmAgent newAgentWithTempStore(Config config) {
        try {
            return new LlmAgent(config, tempStore());
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось создать временное хранилище", e);
        }
    }
     static CapturedStream capturingStream() {
        return new CapturedStream();
    }
     static BufferedReader reader(String input) {
        return new BufferedReader(new StringReader(input));
    }
     static void expectLoadCorrupted(JsonConversationStore store, Path file, String description) {
        boolean reported;
        try {
            store.load();
            reported = false;
        } catch (ConversationStoreException e) {
            reported = e.getMessage().contains(file.toString());
        }
        expect(description + " (ошибка содержит путь)", reported);
    }
     static void expectUnknownVersion(JsonConversationStore store, Path file) {
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
     static void expectSettingsError(Map<String, String> env, String name, String value) {
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
     static int agentProjected(LlmAgent agent, String userMessage) {
        return agent.estimateNextContextTokens()
                + agent.tokenCounter().count(userMessage)
                + TokenCounter.MESSAGE_OVERHEAD_TOKENS
                + agent.currentSettings().maxOutputTokens();
    }
     static LlmAgent newAgentLimit(Config config, HttpClient client,
                                          JsonConversationStore store, long limit) {
        Map<String, String> env = new java.util.HashMap<>();
        env.put("LLM_SESSION_TOKEN_LIMIT", String.valueOf(limit));
        return new LlmAgent(config, ModelSettings.from(env), client, store);
    }
     static LlmAgent newEnvAgent(Config config, HttpClient client,
                                        JsonConversationStore store, Map<String, String> env) {
        return new LlmAgent(config, ModelSettings.from(env), client, store);
    }
     static Map<String, String> day9Env(String keep, String batch) {
        Map<String, String> env = new java.util.HashMap<>();
        env.put("LLM_CONTEXT_MODE", "summary");
        // Сжатие истории применяется внутри стратегии веток; это даёт
        // старым проверкам те же семантики, что до появления стратегий.
        env.put("LLM_CONTEXT_STRATEGY", "branching");
        env.put("LLM_CONTEXT_KEEP_LAST_MESSAGES", keep);
        env.put("LLM_SUMMARY_BATCH_MESSAGES", batch);
        return env;
    }
     record Day9Server(HttpsServer server, AtomicInteger regularHits,
                              AtomicInteger summaryHits, List<String> bodies) {
    }
     static Day9Server startDay9Server(Path keyStore) throws Exception {
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
     static String day9Url(Day9Server s) {
        return "https://127.0.0.1:" + s.server().getAddress().getPort() + "/v1/chat/completions";
    }
     static Map<String, String> branchingEnv() {
        Map<String, String> env = new java.util.HashMap<>();
        env.put("LLM_CONTEXT_STRATEGY", "branching");
        return env;
    }
     static Path day9KeyStore() throws Exception {
        return createSelfSignedKeyStore();
    }
     static List<ChatMessage> seededArchive(String userPrefix, String assistantPrefix,
                                                   int pairs) {
        List<ChatMessage> archive = new ArrayList<>();
        for (int i = 1; i <= pairs; i++) {
            archive.add(new ChatMessage("user", userPrefix + " " + i));
            archive.add(new ChatMessage("assistant", "принял " + i));
        }
        return archive;
    }
     static ConversationSummary seedSummary(List<ChatMessage> archive, int covered,
                                                   String sessionId, String text) {
        return new ConversationSummary(text, covered,
                ConversationSummary.computeFingerprint(sessionId,
                        new ArrayList<>(archive.subList(0, covered))),
                ConversationSummary.FORMAT_VERSION);
    }
     record RunResult(int exitCode, String stdout, String stderr) {    }
     static String buildClasspath() throws Exception {
        List<String> entries = new ArrayList<>();
        addCodeSource(entries, SelfTest.class);                          // target/test-classes
        addCodeSource(entries, Main.class);                              // target/classes
        addCodeSource(entries, com.fasterxml.jackson.databind.ObjectMapper.class);
        addCodeSource(entries, com.fasterxml.jackson.core.JsonFactory.class);
        addCodeSource(entries, com.fasterxml.jackson.annotation.JsonValue.class);
        addCodeSource(entries, org.jline.terminal.Terminal.class);
        addCodeSource(entries, io.modelcontextprotocol.spec.McpSchema.class);
        addCodeSource(entries, io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier.class);
        addCodeSource(entries, reactor.core.publisher.Mono.class);
        addCodeSource(entries, org.reactivestreams.Publisher.class);
        addCodeSource(entries, tools.jackson.databind.ObjectMapper.class);
        addCodeSource(entries, tools.jackson.core.JacksonException.class);
        addCodeSource(entries, com.networknt.schema.dialect.Dialects.class);
        addCodeSource(entries, org.slf4j.LoggerFactory.class);
        addCodeSource(entries, org.slf4j.nop.NOPServiceProvider.class);
        StringBuilder classpath = new StringBuilder();
        for (String entry : entries) {
            if (classpath.length() > 0) {
                classpath.append(File.pathSeparator);
            }
            classpath.append(entry);
        }
        return classpath.toString();
    }
     static void addCodeSource(List<String> entries, Class<?> type) throws Exception {
        var source = type.getProtectionDomain().getCodeSource();
        if (source != null && source.getLocation() != null) {
            entries.add(Path.of(source.getLocation().toURI()).toString());
        }
    }
     static RunResult runAgentProcess(String javaBin, String classpath, Path trustStore,
                                             String apiUrl, Path historyFile,
                                             List<String> inputLines) throws Exception {
        return runAgentProcess(javaBin, classpath, trustStore, apiUrl, historyFile,
                inputLines, Map.of());
    }
     static RunResult runAgentProcess(String javaBin, String classpath, Path trustStore,
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
     static Path createTrustStore(Path keyStorePath) throws IOException, InterruptedException {
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
     static Path createSelfSignedKeyStore() throws IOException, InterruptedException {
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
     static HttpsServer startHttpsServer(Path keyStorePath, Handler handler) throws Exception {
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
     static HttpClient trustedHttpClient(Path keyStorePath) throws Exception {
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
     record Response(int status, byte[] body) {
    }
     interface Handler {
        Response handle(String requestBody, String sessionHeader, String authHeader);
    }
     static boolean historyEquals(List<ChatMessage> actual, ChatMessage... expected) {
        return actual.equals(List.of(expected));
    }
     static boolean historyEquals(List<ChatMessage> actual, List<ChatMessage> expected) {
        return actual.equals(expected);
    }
     static boolean rolesAlternate(List<ChatMessage> history) {
        for (int i = 0; i < history.size(); i++) {
            String expectedRole = (i % 2 == 0) ? "user" : "assistant";
            if (!expectedRole.equals(history.get(i).role())) {
                return false;
            }
        }
        return true;
    }
     static java.util.stream.Stream<String> streamContents(JsonNode messages) {
        List<String> contents = new ArrayList<>();
        messages.forEach(m -> contents.add(m.path("content").asText()));
        return contents.stream();
    }
     static String expectAgentError(LlmAgent agent, String marker) {
        try {
            agent.ask(marker);
        } catch (AgentException e) {
            return e.getMessage();
        }
        return "";
    }
     static Map<String, String> factsEnv() {
        Map<String, String> env = new java.util.HashMap<>();
        env.put("LLM_CONTEXT_STRATEGY", "facts");
        return env;
    }
     static String expectAgentThrow(java.util.function.Supplier<String> action) {
        try {
            action.get();
            return "";
        } catch (AgentException e) {
            return e.getMessage();
        }
    }
     static String lengthAnswer() {
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
     static String regularAnswer(int number) {
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
     static Response json(final int status, final String body) {
        return new Response(status, body.getBytes(StandardCharsets.UTF_8));
    }
     static String factsUsage95Body() {
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
     static String expectStoreError(Runnable action) {
        try {
            action.run();
            return "";
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }
     static LlmAgent newMemoryAgent(Config config, HttpClient client,
                                           Map<String, String> env) throws IOException {
        JsonConversationStore store = tempStore();
        MemoryStore memory = new MemoryStore(
                Files.createTempDirectory(baseTempDir, "mem-").resolve("memory.json"));
        return new LlmAgent(config, ModelSettings.from(env), client, store, memory);
    }
     static ProfileStore tempProfileStoreForTests() throws IOException {
        return new ProfileStore(Files.createTempDirectory(baseTempDir, "prof-")
                .resolve("profile.json"));
    }
     static InvariantStore tempInvariantStoreForTests() throws IOException {
        return new InvariantStore(Files.createTempDirectory(baseTempDir, "inv-")
                .resolve("invariants.json"));
    }
     static boolean resumedEqualsPaused(TaskState resumed, TaskState paused) {
        return resumed.stage() == paused.stage()
                && resumed.status() == TaskStatus.ACTIVE
                && java.util.Objects.equals(resumed.currentStep(), paused.currentStep())
                && java.util.Objects.equals(resumed.expectedAction(), paused.expectedAction())
                && resumed.completedSteps().equals(paused.completedSteps())
                && java.util.Objects.equals(resumed.description(), paused.description())
                && java.util.Objects.equals(resumed.plan(), paused.plan())
                && resumed.planApproved() == paused.planApproved()
                && java.util.Objects.equals(resumed.validationResult(), paused.validationResult())
                && resumed.validationPassed() == paused.validationPassed()
                && resumed.localInvariantsView().equals(paused.localInvariantsView());
    }
     static String expectError(java.util.function.Supplier<?> action) {
        try {
            action.get();
            return "";
        } catch (AgentException e) {
            return e.getMessage();
        }
    }
     static int countSubstring(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
     static boolean hintErrorsContain(List<String> errors, String needle) {
        return errors.stream().anyMatch(t -> t.contains(needle));
    }
     static Invariant gsonAllowedInvariant() {
        return new Invariant("11aa22bb", "миграция на gson — разрешено",
                "stack", Instant.parse("2026-01-01T00:00:00Z"));
    }
            static final class SequenceModel implements McpOrchestrator.Model {
                final List<String> replies;
                int index;
                SequenceModel(String... replies) { this.replies = List.of(replies); }
        @Override public String complete(String system, String prompt) {
            return replies.get(Math.min(index++, replies.size() - 1));
        }
    }
            static final class FakeClock extends java.time.Clock {
                Instant current;

        FakeClock(Instant start) {
            this.current = start;
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }
    }
            static final class ClasspathOverride implements AutoCloseable {
                final String previous;

        ClasspathOverride() {
            previous = System.getProperty("java.class.path");
            System.setProperty("java.class.path", withChildClasspath());
        }

        @Override
        public void close() {
            System.setProperty("java.class.path", previous);
        }
    }
            static final class CountingEmbedder implements com.example.index.Embedder {
                final int dim;
                int requests;

        CountingEmbedder(int dim) {
            this.dim = dim;
        }

        void resetCounter() {
            requests = 0;
        }

        int requests() {
            return requests;
        }

        float[] vector(String text) {
            byte[] hash = ChunkMeta.sha256Hex(
                    text.getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            float[] vector = new float[dim];
            for (int i = 0; i < dim; i++) {
                byte b = hash[i % hash.length];
                vector[i] = ((b & 0xFF) - 128) / 128.0f;
            }
            return vector;
        }

        @Override
        public int batchSize() {
            return 3;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            requests++;
            List<float[]> vectors = new ArrayList<>();
            for (String text : texts) {
                vectors.add(vector(text));
            }
            return vectors;
        }
    }
            static final class FakeUi implements TerminalUi {
                final List<TerminalUi.Input> script;
                int cursor = 0;
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
            static final class CapturedStream {
        final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        final PrintStream stream = new PrintStream(buffer, true, StandardCharsets.UTF_8);

        String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
            static final class FailingStore implements ConversationStore {
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
            static final class HistoryButFailingStore implements ConversationStore {
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
    public static final class RuntimeLockHolder {
        public static void main(String[] args) throws Exception {
            Path storeFile = Path.of(args[0]);
            String scheduleId = args[1];
            Path ready = Path.of(args[2]);
            MonitorStore store = new MonitorStore(storeFile);
            try (MonitorStore.RuntimeLease ignored = store.tryRuntimeLock(scheduleId)) {
                Files.writeString(ready, "ready", StandardCharsets.UTF_8);
                while (System.in.read() != -1) {
                    // Keep the lease until the parent closes stdin.
                }
            }
        }
    }
}
