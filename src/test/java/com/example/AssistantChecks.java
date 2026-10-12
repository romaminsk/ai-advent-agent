package com.example;

import com.example.assistant.DevAssistant;
import com.example.assistant.GitReadTools;
import com.example.index.ChunkMeta;
import com.example.index.DocumentLoader;
import com.example.index.Embedder;
import com.example.index.IndexStore;
import com.example.rag.RagPromptBuilder;
import com.example.rag.RagRetriever;
import com.example.rag.RagService;
import com.example.rag.RagSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class AssistantChecks extends SelfTestSupport {

    static void run() throws Exception {
        checkDocsCorpus();
        checkRouting();
        checkMasking();
        checkDocsThreshold();
        checkBranchAndChat();
        checkProjectPath();
        Path repo = checkGitTools();
        checkAnswers();
        checkMcpRoundTrip(repo);
    }

    static void checkDocsCorpus() throws Exception {
        Path root = Files.createDirectory(baseTempDir.resolve("assistant-corpus"));
        writeTestFile(root.resolve("README.md"), "# Проект\nТекст.");
        writeTestFile(root.resolve("docs/a.md"), "## A\nтекст");
        writeTestFile(root.resolve("docs/sub/b.txt"), "текст b");
        writeTestFile(root.resolve("docs/schema.json"), "{}");
        writeTestFile(root.resolve(".env"), "SECRET=1");
        writeTestFile(root.resolve("docs/.env.local"), "SECRET=1");
        writeTestFile(root.resolve("src/main/java/A.java"), "class A {}");
        writeTestFile(root.resolve("src/test/T.md"), "тест");
        writeTestFile(root.resolve("artifacts/x.md"), "x");
        writeTestFile(root.resolve(".idea/i.md"), "i");
        writeTestFile(root.resolve("target/t.md"), "t");
        writeTestFile(root.resolve("docs/big.md"), "a".repeat(201 * 1024));
        List<String> paths = DocumentLoader.docsOnly().load(root).stream()
                .map(DocumentLoader.Document::relativePath).toList();
        expect("ASSIST-1 корпус: README и docs/*.md|*.txt, без .env, src, artifacts, .idea, target и файлов > 200 КБ",
                paths.equals(List.of("README.md", "docs/a.md", "docs/sub/b.txt")));
    }

    static void checkRouting() {
        expect("ASSIST-2 ветка: только git_branch",
                DevAssistant.routeTools("В какой я сейчас ветке?").equals(List.of("git_branch")));
        expect("ASSIST-2 структура: git_branch и list_files",
                DevAssistant.routeTools("Какая у проекта структура?")
                        .equals(List.of("git_branch", "list_files")));
        expect("ASSIST-2 изменения: git_status и git_diff",
                DevAssistant.routeTools("Что изменено в diff?")
                        .equals(List.of("git_branch", "git_status", "git_diff")));
        expect("ASSIST-2 обычный вопрос не MCP", !DevAssistant.isMcpQuestion("Как приготовить борщ?"));
    }

    static void checkMasking() {
        String masked = GitReadTools.mask("+API_KEY=abc\n ok line\n+my_token=1\nsecret: x\nPassword=y");
        expect("ASSIST-3 маскирование KEY/TOKEN/SECRET/PASSWORD без учёта регистра",
                masked.equals("[скрыто]\n ok line\n[скрыто]\n[скрыто]\n[скрыто]"));
        String truncated = GitReadTools.truncate("a".repeat(9000));
        expect("ASSIST-3 diff обрезается до 8 КБ с пометкой",
                truncated.contains("вывод обрезан до 8 КБ")
                        && truncated.getBytes(StandardCharsets.UTF_8).length < 8 * 1024 + 100);
        expect(".env-пути распознаются", GitReadTools.isSecretPath("sub/.env.local")
                && !GitReadTools.isSecretPath("docs/environment.md"));
    }

    static Path checkGitTools() throws Exception {
        Path repo = Files.createDirectory(baseTempDir.resolve("assistant-repo"));
        runGit(repo, "init", "-q");
        runGit(repo, "config", "user.email", "test@example.com");
        runGit(repo, "config", "user.name", "test");
        writeTestFile(repo.resolve("README.md"), "# repo");
        writeTestFile(repo.resolve("docs/a.md"), "a");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-q", "-m", "init");
        runGit(repo, "checkout", "-q", "-b", "feature-x");
        GitReadTools tools = new GitReadTools(GitReadTools.repositoryRoot(repo));
        expect("ASSIST-4 git_branch: текущая ветка", tools.branch().equals("ветка: feature-x"));
        writeTestFile(repo.resolve(".env"), "TOKEN=1");
        writeTestFile(repo.resolve("new.txt"), "n");
        String status = tools.status();
        expect("ASSIST-4 git_status: новый файл виден", status.contains("?? new.txt"));
        expect("ASSIST-4 git_status: .env не показывается", !status.contains(".env"));
        expect("ASSIST-4 list_files: префикс docs", tools.listFiles("docs").equals("docs/a.md"));
        boolean escaped;
        try {
            tools.listFiles("../outside");
            escaped = false;
        } catch (IOException e) {
            escaped = true;
        }
        expect("ASSIST-4 list_files: путь вне корня запрещён", escaped);
        writeTestFile(repo.resolve("README.md"), "# repo\nAPI_TOKEN=secret-value\n");
        String diff = tools.diff(false);
        expect("ASSIST-3 git_diff: строка с TOKEN скрыта",
                diff.contains("[скрыто]") && !diff.contains("secret-value") && diff.contains("README.md"));
        return repo;
    }

    static void checkAnswers() throws Exception {
        Path dir = Files.createDirectory(baseTempDir.resolve("assistant-index"));
        IndexStore store = new IndexStore(dir);
        ChunkMeta meta = new ChunkMeta("c1", "docs/a.md", "A", "Раздел", "structure", 0, 25, 25);
        store.save(new IndexStore.Index("structure", "stub", 3, Instant.now(), "root", 0, 0, 0,
                List.of(new IndexStore.IndexedChunk(meta, "Проект хранит документы.",
                        new float[]{1, 0, 0}))));
        Embedder embedder = new Embedder() {
            @Override
            public int batchSize() {
                return 1;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                List<float[]> vectors = new ArrayList<>();
                for (String text : texts) {
                    vectors.add(text.contains("борщ") || text.contains("ветк") ? new float[]{0, 1, 0}
                            : new float[]{1, 0, 0});
                }
                return vectors;
            }
        };
        AtomicInteger llmCalls = new AtomicInteger();
        AtomicReference<String> seenUser = new AtomicReference<>("");
        RagService.LlmClient llm = (system, user, maxOutputTokens) -> {
            llmCalls.incrementAndGet();
            seenUser.set(user);
            return "Проект хранит документы [1] «Проект хранит документы.»";
        };
        AtomicInteger mcpCalls = new AtomicInteger();
        DevAssistant.McpSource mcp = tools -> {
            mcpCalls.incrementAndGet();
            Map<String, String> data = new LinkedHashMap<>();
            for (String tool : tools) {
                data.put(tool, "stub:" + tool);
            }
            return data;
        };
        RagService service = new RagService(new RagRetriever(store, embedder, "structure", 5),
                new RagPromptBuilder(), "system", llm,
                RagSettings.DEFAULT.withIdkThreshold(0.578629).withRewrite(false), null);

        DevAssistant.Answer refusal = DevAssistant.answer("Как приготовить борщ?", service, mcp);
        expect("ASSIST-5 пустой RAG без MCP-вопроса: «В документации проекта этого нет»",
                refusal.text().equals(DevAssistant.NOT_IN_DOCS) && refusal.sources().isEmpty()
                        && llmCalls.get() == 0);

        DevAssistant.Answer branch = DevAssistant.answer("В какой я сейчас ветке?", service, mcp);
        expect("ASSIST-5 вопрос про ветку при пустом RAG отвечается данными MCP",
                branch.text().contains("stub:git_branch") && llmCalls.get() == 0);

        DevAssistant.Answer cited = DevAssistant.answer("Что хранит проект?", service, mcp);
        expect("ASSIST-5 ответ с цитатой [1] и источником",
                cited.text().contains("[1]") && cited.sources().size() == 1
                        && llmCalls.get() == 1);
        expect("ASSIST-5 MCP-блок попадает в промпт LLM",
                seenUser.get().contains("Контекст проекта (MCP)") && seenUser.get().contains("stub:git_branch"));
        expect("ASSIST-5 MCP вызывается для ответов с RAG", mcpCalls.get() == 2);

        RagService docsService = new RagService(new RagRetriever(store, embedder, "structure", 5),
                new RagPromptBuilder(), "system", llm,
                RagSettings.DEFAULT.withMinScore(DevAssistant.DOCS_IDK_THRESHOLD)
                        .withIdkThreshold(DevAssistant.DOCS_IDK_THRESHOLD).withRewrite(false), null);
        DevAssistant.Answer structure = DevAssistant.answer("Какая структура проекта?", docsService, mcp);
        expect("ASSIST-8 структурный вопрос: при проходе порога ответ идёт через RAG с [n] и путями",
                structure.text().contains("[1]") && structure.sources().size() == 1 && llmCalls.get() == 2);
        expect("ASSIST-8 структурный вопрос: list_files добавляется в блок MCP",
                seenUser.get().contains("stub:list_files"));

        RagService emptyLlm = new RagService(new RagRetriever(store, embedder, "structure", 5),
                new RagPromptBuilder(), "system", (system, user, maxOutputTokens) -> "",
                RagSettings.DEFAULT.withMinScore(DevAssistant.DOCS_IDK_THRESHOLD)
                        .withIdkThreshold(DevAssistant.DOCS_IDK_THRESHOLD).withRewrite(false), null);
        DevAssistant.Answer changes = DevAssistant.answer("Какие файлы изменены?", emptyLlm, mcp);
        expect("ASSIST-9 вопрос про изменения при пустом ответе LLM отвечается данными MCP",
                changes.text().contains("stub:git_status") && !changes.text().contains("Не удалось"));

        AtomicInteger retryCalls = new AtomicInteger();
        RagService.LlmClient flaky = (system, user, maxOutputTokens) ->
                retryCalls.incrementAndGet() <= 2 ? "" : "Проект хранит документы [1] «Проект хранит документы.»";
        RagService retrying = new RagService(new RagRetriever(store, embedder, "structure", 5),
                new RagPromptBuilder(), "system", flaky,
                RagSettings.DEFAULT.withMinScore(DevAssistant.DOCS_IDK_THRESHOLD)
                        .withIdkThreshold(DevAssistant.DOCS_IDK_THRESHOLD).withRewrite(false), null);
        DevAssistant.Answer recovered = DevAssistant.answer("Что хранит проект?", retrying, mcp);
        expect("ASSIST-10 пустой ответ LLM → повтор → ответ с [n] и источником",
                recovered.text().contains("[1]") && recovered.sources().size() == 1 && retryCalls.get() >= 3);

        AtomicInteger failedCalls = new AtomicInteger();
        RagService alwaysEmpty = new RagService(new RagRetriever(store, embedder, "structure", 5),
                new RagPromptBuilder(), "system", (system, user, maxOutputTokens) -> {
                    failedCalls.incrementAndGet();
                    return "";
                },
                RagSettings.DEFAULT.withMinScore(DevAssistant.DOCS_IDK_THRESHOLD)
                        .withIdkThreshold(DevAssistant.DOCS_IDK_THRESHOLD).withRewrite(false), null);
        DevAssistant.Answer fallback = DevAssistant.answer("Что хранит проект?", alwaysEmpty, mcp);
        expect("ASSIST-10 три неудачи → запасной вывод «модель не дала ответ» и источники из найденных чанков",
                fallback.text().contains("Модель не дала ответ") && fallback.sources().size() == 1
                        && failedCalls.get() >= 3);
    }

    static void checkBranchAndChat() throws Exception {
        expect("ASSIST-11 вопрос про ветку распознаётся (положительные примеры)",
                DevAssistant.isBranchOnly("В какой я сейчас ветке?") && DevAssistant.isBranchOnly("Какая ветка?")
                        && DevAssistant.isBranchOnly("Какая у меня branch?") && DevAssistant.isBranchOnly("в какой я ветке"));
        expect("ASSIST-11 не про ветку: файлы, борщ, смешанные вопросы (отрицательные примеры)",
                !DevAssistant.isBranchOnly("Какие файлы изменены?")
                        && !DevAssistant.isBranchOnly("В какой ветке и какие файлы изменены?")
                        && !DevAssistant.isBranchOnly("Какая структура проекта и ветка?")
                        && !DevAssistant.isBranchOnly("Что в документации про ветки?")
                        && !DevAssistant.isBranchOnly("В какой ветке diff?")
                        && !DevAssistant.isBranchOnly("Как приготовить борщ?"));
        expect("ASSIST-12 «/help X» и «X» дают один вопрос",
                DevAssistant.questionOf("/help В какой я ветке?").equals("В какой я ветке?")
                        && DevAssistant.questionOf("В какой я ветке?").equals("В какой я ветке?")
                        && DevAssistant.questionOf("/help").isEmpty() && DevAssistant.questionOf("   ").isEmpty()
                        && DevAssistant.questionOf("/helpful x").equals("/helpful x"));
        expect("ASSIST-12 выход по /exit и /quit без учёта регистра",
                DevAssistant.isExit("/exit") && DevAssistant.isExit("/QUIT") && !DevAssistant.isExit("exit"));

        List<String> asked = new ArrayList<>();
        DevAssistant.runChat(new java.io.BufferedReader(new java.io.StringReader(
                "/help Вопрос1\n\nВопрос2\n/exit\nВопрос3\n")), new java.io.PrintStream(new java.io.ByteArrayOutputStream(),
                true, StandardCharsets.UTF_8), asked::add);
        expect("ASSIST-12 цикл: пустые строки пропускаются, /exit завершает сеанс",
                asked.equals(List.of("Вопрос1", "Вопрос2")));
        List<String> eof = new ArrayList<>();
        DevAssistant.runChat(new java.io.BufferedReader(new java.io.StringReader("Вопрос4")),
                new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8), eof::add);
        expect("ASSIST-12 цикл: конец ввода (Ctrl+D) завершает сеанс без зависания", eof.equals(List.of("Вопрос4")));

        java.io.ByteArrayOutputStream outBytes = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream errBytes = new java.io.ByteArrayOutputStream();
        int okCode = DevAssistant.branchAnswer(tools -> Map.of("git_branch", "ветка: feature-x"),
                new java.io.PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new java.io.PrintStream(errBytes, true, StandardCharsets.UTF_8));
        expect("ASSIST-13 ответ ветки одной строкой из MCP без LLM",
                okCode == 0 && outBytes.toString(StandardCharsets.UTF_8).equals("Текущая git-ветка: feature-x\n"));
        java.io.ByteArrayOutputStream failOut = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream failErr = new java.io.ByteArrayOutputStream();
        int failCode = DevAssistant.branchAnswer(tools -> { throw new java.io.IOException("нет связи"); },
                new java.io.PrintStream(failOut, true, StandardCharsets.UTF_8),
                new java.io.PrintStream(failErr, true, StandardCharsets.UTF_8));
        expect("ASSIST-13 недоступный MCP: понятная ошибка на русском, exit != 0, без стектрейса",
                failCode != 0 && failOut.size() == 0
                        && failErr.toString(StandardCharsets.UTF_8).startsWith("Не удалось определить ветку"));
    }

    static void checkProjectPath() throws Exception {
        Path repoA = Files.createDirectory(baseTempDir.resolve("proj-a"));
        Path repoB = Files.createDirectory(baseTempDir.resolve("proj-b"));
        Path repoCwd = Files.createDirectory(baseTempDir.resolve("proj-cwd"));
        for (Path repo : List.of(repoA, repoB, repoCwd)) {
            runGit(repo, "init", "-q");
        }
        Path a = repoA.toRealPath();
        Path b = repoB.toRealPath();
        Path c = repoCwd.toRealPath();

        DevAssistant.Project byArg = DevAssistant.resolveProject(repoA.toString(), repoB.toString(), repoCwd);
        expect("ASSIST-14 приоритет: --project сильнее PROJECT_DIR и текущей папки",
                byArg.path().equals(a) && byArg.source().equals("--project"));
        DevAssistant.Project byEnv = DevAssistant.resolveProject(null, repoB.toString(), repoCwd);
        expect("ASSIST-14 приоритет: PROJECT_DIR сильнее текущей папки",
                byEnv.path().equals(b) && byEnv.source().equals("PROJECT_DIR"));
        DevAssistant.Project byCwd = DevAssistant.resolveProject(null, null, repoCwd);
        expect("ASSIST-14 приоритет: без --project и PROJECT_DIR берётся текущая папка",
                byCwd.path().equals(c) && byCwd.source().equals("текущая папка"));
        DevAssistant.Project blankEnv = DevAssistant.resolveProject(null, "   ", repoCwd);
        expect("ASSIST-14 пустой PROJECT_DIR игнорируется", blankEnv.path().equals(c));

        DevAssistant.Args after = DevAssistant.parseArgs(new String[]{"--project", "/x", "Вопрос", "а"});
        DevAssistant.Args inside = DevAssistant.parseArgs(new String[]{"Вопрос", "--project=/y", "б"});
        DevAssistant.Args tail = DevAssistant.parseArgs(new String[]{"a", "b", "--project", "/z"});
        DevAssistant.Args none = DevAssistant.parseArgs(new String[]{"Вопрос"});
        expect("ASSIST-15 разбор --project в начале, середине и конце; без него project = null",
                "/x".equals(after.project()) && after.rest().equals(List.of("Вопрос", "а"))
                        && "/y".equals(inside.project()) && inside.rest().equals(List.of("Вопрос", "б"))
                        && "/z".equals(tail.project()) && tail.rest().equals(List.of("a", "b"))
                        && none.project() == null);
        boolean missingValue;
        try {
            DevAssistant.parseArgs(new String[]{"Вопрос", "--project"});
            missingValue = false;
        } catch (IllegalArgumentException e) {
            missingValue = true;
        }
        expect("ASSIST-15 --project без значения — понятная ошибка", missingValue);

        String home = System.getProperty("user.home");
        expect("ASSIST-15 нормализация «~» и «~/»",
                DevAssistant.normalizePath("~", repoCwd).equals(Path.of(home).toRealPath())
                        && DevAssistant.normalizePath("~/", repoCwd).equals(Path.of(home).toRealPath()));
        expect("ASSIST-15 относительный путь и «..» разрешаются от текущей папки",
                DevAssistant.normalizePath("../proj-a", repoCwd).equals(a));

        String missingMessage = "";
        try {
            DevAssistant.resolveProject(baseTempDir.resolve("no-such-project").toString(), null, repoCwd);
        } catch (IllegalStateException e) {
            missingMessage = e.getMessage();
        }
        expect("ASSIST-15 несуществующая папка: ошибка с путём и источником",
                missingMessage.contains("папка проекта не найдена") && missingMessage.contains("(источник: --project)"));

        Path plain = Files.createTempDirectory("aa-nogit-");
        String nonGitMessage = "";
        try {
            DevAssistant.resolveProject(null, plain.toString(), repoCwd);
        } catch (IllegalStateException e) {
            nonGitMessage = e.getMessage();
        }
        expect("ASSIST-15 папка не из git: ошибка с путём и источником PROJECT_DIR",
                nonGitMessage.contains("не является Git-репозиторием") && nonGitMessage.contains("(источник: PROJECT_DIR)"));
    }

    static void checkDocsThreshold() {
        expect("ASSIST-7 docsIdkThreshold между минимумом релевантных 0.4975 и максимумом нерелевантных 0.4610",
                DevAssistant.DOCS_IDK_THRESHOLD > 0.4610 && DevAssistant.DOCS_IDK_THRESHOLD < 0.4975);
        expect("ASSIST-7 глобальный порог RAG не изменён в настройках по умолчанию",
                RagSettings.DEFAULT_IDK_THRESHOLD == 0.50);
        StringBuilder listing = new StringBuilder("README.md\npom.xml\ndocs/a.md\n");
        for (int i = 0; i < 57; i++) {
            listing.append("src/x/f").append(i).append(".java\n");
        }
        listing.append("… показаны первые 200 из 300 файлов");
        String compact = DevAssistant.promptListing(listing.toString());
        long fileLines = compact.lines().filter(line -> !line.startsWith("Корень") && !line.startsWith("…")).count();
        expect("ASSIST-7 list_files в промпте: верхний уровень в строке «Корень»",
                compact.startsWith("Корень: README.md pom.xml docs/ src/"));
        expect("ASSIST-7 list_files в промпте: первые 40 строк файлов", fileLines == 40);
        expect("ASSIST-7 list_files в промпте: остаток в счётчике", compact.contains("… ещё 20 файлов"));
    }

    static void checkMcpRoundTrip(Path repo) {
        DevAssistant.McpSource source = DevAssistant.gitSource(repo, withChildClasspath());
        try {
            Map<String, String> data = source.call(List.of("git_branch", "git_status"));
            expect("ASSIST-6 MCP stdio: initialize + tools/call git_branch",
                    "ветка: feature-x".equals(data.get("git_branch")));
            expect("ASSIST-6 MCP stdio: tools/call git_status возвращает данные",
                    data.get("git_status").contains("new.txt"));
        } catch (Exception e) {
            expect("ASSIST-6 MCP stdio: запрос выполнен (" + e.getMessage() + ")", false);
        }
    }

    static void writeTestFile(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    static void runGit(Path directory, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", args) + " завершился с ошибкой");
        }
    }
}
