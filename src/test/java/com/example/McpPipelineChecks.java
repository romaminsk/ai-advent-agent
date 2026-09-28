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

final class McpPipelineChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkPipelineStage();
        checkPipelineChildProcess();
        checkPipelineCommandsViaMain();
    }

     static void checkPipelineStage() throws Exception {
        expect("pipeline регистрирует ровно три инструмента",
                PipelineMcpServer.TOOL_NAMES.size() == 3
                        && PipelineMcpServer.TOOL_NAMES.equals(
                        List.of("search", "summarize", "saveToFile")));

        Path base = Files.createTempDirectory(baseTempDir, "pipeline-base-");
        Path root = pipelineCorpus(base);
        Path results = Files.createTempDirectory(baseTempDir, "pipeline-results-");

        // --- search ---
        FileSearcher.SearchResult search = FileSearcher.search(root, "MonitorStore", null);
        expect("pipeline search находит совпадения, порядок детерминирован",
                search.totalMatches() == 3 && search.matches().size() == 3
                        && search.filesScanned() == 2
                        && "alpha.txt".equals(search.matches().get(0).file())
                        && search.matches().get(0).line() == 1
                        && search.matches().get(1).file().equals("alpha.txt")
                        && search.matches().get(1).line() == 3
                        && search.matches().get(2).file().equals("nested/beta.md")
                        && search.matches().get(2).line() == 1);
        expect("pipeline search без лимита не обрезан, корень абсолютный",
                !search.truncated() && search.root().equals(root.toRealPath().toString()));
        FileSearcher.SearchResult again = FileSearcher.search(root, "MonitorStore", null);
        expect("одинаковый вход search даёт одинаковый payloadSha256",
                search.payloadSha256().equals(again.payloadSha256())
                        && search.payloadSha256().equals(search.integritySha256()));
        expect("pipeline search: кириллица, регистронезависимо",
                FileSearcher.search(root, "кириллица", null).totalMatches() == 1
                        && FileSearcher.search(root, "ПРИВЕТ", null).totalMatches() == 1);
        expect("pipeline search пропускает .git/.env*/target/node_modules/секрет-каталоги/ключи/бинарный/большой/симлинк",
                search.filesSkipped() == 21
                        && !searcherJson(search).contains("target/")
                        && !searcherJson(search).contains("node_modules")
                        && !searcherJson(search).contains("binary")
                        && !searcherJson(search).contains("big.txt")
                        && !searcherJson(search).contains("link.txt"));
        expect("секрет-файлы P4 не читаются и не попадают в вывод",
                !searcherJson(search).contains("в .ssh") && !searcherJson(search).contains("pem")
                        && !searcherJson(search).contains("netrc") && !searcherJson(search).contains("pgpass")
                        && FileSearcher.search(root, "MonitorStore в .aws", null).totalMatches() == 0
                        && FileSearcher.search(root, "MonitorStore в id_rsa", null).totalMatches() == 0
                        && FileSearcher.search(root, "MonitorStore в pgpass", null).totalMatches() == 0);
        expect(".env содержимое не попадает в вывод search",
                !searcherJson(search).contains("MonitorStore-env-secret")
                        && !searcherJson(search).contains("MonitorStore-env-local"));
        expect("все варианты .env* пропускаются, содержимого нет в matches",
                FileSearcher.search(root, "MonitorStore-envrc", null).totalMatches() == 0
                        && FileSearcher.search(root, "MonitorStore-envproduction", null).totalMatches() == 0
                        && PipelineCanonicalJson.canonical(search.toMap())
                        .contains("MonitorStore-envrc") == false
                        && PipelineCanonicalJson.canonical(search.toMap())
                        .contains("MonitorStore-envproduction") == false);
        expect("симлинк наружу не следуется",
                FileSearcher.search(root, "за пределами корня", null).totalMatches() == 0);
        FileSearcher.SearchResult truncatedSearch = FileSearcher.search(root, "MonitorStore", 1);
        expect("maxResults обрезает список, totalMatches честный",
                truncatedSearch.truncated() && truncatedSearch.totalMatches() == 3
                        && truncatedSearch.matches().size() == 1);
        FileSearcher.SearchResult emptySearch = FileSearcher.search(root, "нет-такого-слова", null);
        expect("ноль совпадений — не ошибка",
                emptySearch.totalMatches() == 0 && emptySearch.matches().isEmpty()
                        && !emptySearch.truncated() && emptySearch.filesScanned() == 2);

        // --- ошибки search (инструмент, а не исключение сервера) ---
        expect("pipeline search: root не существует — ошибка инструмента",
                searchError(root.resolve("missing"), "MonitorStore", null)
                        .contains("должен существовать и быть каталогом"));
        expect("pipeline search: root — файл — ошибка инструмента",
                searchError(root.resolve("alpha.txt"), "MonitorStore", null)
                        .contains("должен существовать и быть каталогом"));
        expect("pipeline search: пустой query — ошибка",
                searchError(root, "   ", null).contains("пустым"));
        expect("pipeline search: query > 200 — ошибка",
                searchError(root, "q".repeat(201), null).contains("длиннее"));
        expect("pipeline search: maxResults 0 — ошибка",
                searchError(root, "MonitorStore", 0).contains("maxResults"));
        expect("pipeline search: maxResults 201 — ошибка",
                searchError(root, "MonitorStore", 201).contains("maxResults"));

        // --- summarize ---
        Map<String, Object> searchMap = search.toMap();
        SearchSummarizer.Summary summary = SearchSummarizer.summarize(searchMap);
        expect("pipeline summarize перепроверяет SHA-256 входа",
                summary.sourceSha256().equals(search.payloadSha256()));
        expect("pipeline summarize topFiles по убыванию, при равенстве по имени",
                summary.filesWithMatches() == 2 && summary.topFiles().size() == 2
                        && summary.topFiles().get(0).file().equals("alpha.txt")
                        && summary.topFiles().get(0).matches() == 2
                        && summary.topFiles().get(1).file().equals("nested/beta.md"));
        expect("pipeline summarize sampleLines — первые совпадения",
                summary.sampleLines().size() == 3
                        && summary.sampleLines().get(0).file().equals("alpha.txt")
                        && summary.sampleLines().get(0).line() == 1);
        expect("pipeline summarize summaryText описывает совпадения на русском",
                summary.summaryText().contains("Найдено 3")
                        && summary.summaryText().contains("alpha.txt")
                        && summary.summaryText().contains("файлах"));
        expect("pipeline summarize summarySha256 воспроизводим",
                summary.summarySha256().equals(summary.integritySha256()));
        expect("pipeline summarize shownMatches/truncated соответствуют search",
                summary.shownMatches() == 3 && summary.truncated() == search.truncated());

        // изменяем одну строку в matches — целостность нарушена
        Map<String, Object> tampered = new LinkedHashMap<>(searchMap);
        List<Map<String, Object>> tamperedMatches = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> originalMatches =
                (List<Map<String, Object>>) searchMap.get("matches");
        for (int i = 0; i < originalMatches.size(); i++) {
            Map<String, Object> item = new LinkedHashMap<>(originalMatches.get(i));
            if (i == 0) item.put("text", item.get("text") + " изменено");
            tamperedMatches.add(item);
        }
        tampered.put("matches", tamperedMatches);
        try {
            SearchSummarizer.summarize(tampered);
            expect("изменённый вход summarize даёт integrity error", false);
        } catch (PipelineToolException e) {
            expect("изменённый вход summarize даёт integrity error",
                    e.getMessage().contains("input integrity check failed"));
        }

        SearchSummarizer.Summary emptySummary = SearchSummarizer.summarize(emptySearch.toMap());
        expect("пустой результат search даёт сводку с нулями",
                emptySummary.totalMatches() == 0 && emptySummary.shownMatches() == 0
                        && emptySummary.filesWithMatches() == 0
                        && emptySummary.topFiles().isEmpty()
                        && emptySummary.sampleLines().isEmpty()
                        && emptySummary.summaryText().toLowerCase(Locale.ROOT).contains("не найдено"));
        expect("пустая сводка воспроизводима по SHA-256",
                emptySummary.summarySha256().equals(emptySummary.integritySha256()));

        // --- saveToFile ---
        ResultFileWriter writer = new ResultFileWriter(results);
        ResultFileWriter.SaveResult saved = writer.save(summary.toMap(), "report-ok.md");
        expect("saveToFile создаёт указанный файл",
                saved.path().endsWith("report-ok.md") && Files.exists(Path.of(saved.path())));
        expect("saveToFile fileSha256 совпадает с содержимым на диске",
                PipelineCanonicalJson.sha256Hex(Files.readAllBytes(Path.of(saved.path())))
                        .equals(saved.fileSha256())
                        && Files.size(Path.of(saved.path())) == saved.bytes());
        expect("saveToFile сохраняет связки sourceSha256/summarySha256",
                saved.sourceSha256().equals(search.payloadSha256())
                        && saved.summarySha256().equals(summary.summarySha256()));
        String markdown = Files.readString(Path.of(saved.path()), StandardCharsets.UTF_8);
        expect("Markdown содержит сводку, счётчики и хеши",
                markdown.contains("MonitorStore") && markdown.contains("Сводка")
                        && markdown.contains(search.payloadSha256())
                        && markdown.contains(summary.summarySha256()));
        ResultFileWriter.SaveResult defaultSaved = writer.save(summary.toMap(), null);
        expect("имя по умолчанию: pipeline-yyyyMMdd-HHmmss-8hex.md",
                Path.of(defaultSaved.path()).getFileName().toString()
                        .matches("pipeline-\\d{8}-\\d{6}-[0-9a-f]{8}\\.md"));

        int filesBefore = resultsFileCount(results);
        Map<String, Object> corrupted = new LinkedHashMap<>(summary.toMap());
        // Пустой/пробельный fileName — ошибка инструмента, файл не создаётся.
        for (String blank : List.of("", " ", "\t")) {
            try {
                writer.save(summary.toMap(), blank);
                expect("бессодержательное fileName отклоняется", false);
            } catch (PipelineToolException e) {
                expect("бессодержательное fileName отклоняется",
                        e.getMessage().contains("не должен быть пустым"));
            }
        }
        expect("после пустых fileName файлы не созданы",
                resultsFileCount(results) == filesBefore);
        corrupted.put("summaryText", "испорченная сводка");
        try {
            writer.save(corrupted, "corrupt.md");
            expect("испорченная сводка отклоняется", false);
        } catch (PipelineToolException e) {
            expect("испорченная сводка отклоняется",
                    e.getMessage().contains("integrity check failed"));
        }
        expect("испорченная сводка не создаёт файл",
                resultsFileCount(results) == filesBefore);

        List<String> badNames = List.of("../x.md", "a/b.md", "noext", "a.md.", "файл.md",
                "sub..dir.md", "x".repeat(99) + ".md");
        for (String bad : badNames) {
            try {
                writer.save(summary.toMap(), bad);
                expect("недопустимое fileName отклоняется: " + shortName(bad), false);
            } catch (PipelineToolException e) {
                expect("недопустимое fileName отклоняется: " + shortName(bad),
                        e.getMessage().contains("fileName"));
            }
        }
        expect("после плохих имён файлы не созданы",
                resultsFileCount(results) == filesBefore);

        byte[] okBytes = Files.readAllBytes(Path.of(saved.path()));
        try {
            writer.save(summary.toMap(), "report-ok.md");
            expect("существующий файл не перезаписывается", false);
        } catch (PipelineToolException e) {
            expect("существующий файл не перезаписывается",
                    e.getMessage().contains("file already exists")
                            && Arrays.equals(okBytes, Files.readAllBytes(Path.of(saved.path()))));
        }
        expect("во временном каталоге нет temp-файлов", onlyMdFiles(results));

        // --- обработчики инструментов (аргументы, без запуска процесса) ---
        expect("обработчик search требует ровно root и query",
                PipelineMcpServer.search(McpSchema.CallToolRequest.builder().name(
                                PipelineMcpServer.SEARCH_TOOL)
                        .arguments(Map.of("root", root.toString(), "query", "MonitorStore",
                                "extra", "x")).build()).isError());
        expect("обработчик search отрабатывает корректный вызов",
                !PipelineMcpServer.search(McpSchema.CallToolRequest.builder().name(
                                PipelineMcpServer.SEARCH_TOOL)
                        .arguments(Map.of("root", root.toString(), "query", "MonitorStore"))
                        .build()).isError());
        expect("обработчик summarize требует searchResult",
                PipelineMcpServer.summarize(McpSchema.CallToolRequest.builder()
                        .name(PipelineMcpServer.SUMMARIZE_TOOL)
                        .arguments(Map.of("other", 1)).build()).isError());
        expect("обработчик summarize принимает результат search",
                !PipelineMcpServer.summarize(McpSchema.CallToolRequest.builder()
                        .name(PipelineMcpServer.SUMMARIZE_TOOL)
                        .arguments(Map.of("searchResult", searchMap)).build()).isError());
        expect("обработчик saveToFile требует summary",
                new PipelineMcpServer(results).save(McpSchema.CallToolRequest.builder()
                        .name(PipelineMcpServer.SAVE_TOOL)
                        .arguments(Map.of("other", 1)).build()).isError());

        // --- формат строки шага (P1/P2): склонение и признак обрезки ---
        checkPipelineStepSummaryPlural();
    }

     static void checkPipelineStepSummaryPlural() throws Exception {
        java.lang.reflect.Method summary = Main.class.getDeclaredMethod("stepSummary",
                PipelineRunner.StepResult.class);
        summary.setAccessible(true);
        for (int[] counts : List.of(new int[]{0, 0}, new int[]{1, 1}, new int[]{2, 2},
                new int[]{5, 5}, new int[]{11, 11}, new int[]{21, 21}, new int[]{22, 22},
                new int[]{25, 25}, new int[]{111, 111})) {
            String text = stepSummaryForCounts(counts[0], counts[1], false, summary);
            expect("склонение строки поиска: " + counts[0] + " совпадений/" + counts[1] + " файлов",
                    text.equals(counts[0] + expectedPlural(counts[0], " совпадение",
                            " совпадения", " совпадений")
                            + " в " + counts[1] + expectedPlural(counts[1],
                            " файле", " файлах", " файлах")));
        }
        String threeTwo = stepSummaryForCounts(3, 2, false, summary);
        expect("строка поиска на корпусе 3/2: «3 совпадения в 2 файлах»",
                threeTwo.equals("3 совпадения в 2 файлах"));
        String one = stepSummaryForCounts(1, 1, false, summary);
        expect("строка поиска 1/1: «1 совпадение в 1 файле»",
                one.equals("1 совпадение в 1 файле"));
        String zero = stepSummaryForCounts(0, 0, false, summary);
        expect("строка поиска 0/0: «0 совпадений в 0 файлах»",
                zero.equals("0 совпадений в 0 файлах"));
        String truncatedFalse = stepSummaryForCounts(6, 3, false, summary);
        expect("без обрезки суффикс «показано» отсутствует",
                !truncatedFalse.contains("показано"));
        String truncatedTrue = stepSummaryForCounts(65, 1, true, summary);
        expect("с обрезкой строка содержит «показано 50»",
                truncatedTrue.equals("65 совпадений в 1 файле (показано 50, остальное обрезано лимитом)"));
    }

     static void checkPipelineChildProcess() throws Exception {
        Path base = Files.createTempDirectory(baseTempDir, "pipeline-child-base-");
        Path root = pipelineCorpus(base);
        Path results = Files.createTempDirectory(baseTempDir, "pipeline-child-results-");

        // --- успех: три шага, три связки SHA-256, файл перечитан и совпал ---
        PipelineRunner.ChainResult chain = pipelineRunner(results).run(root.toString(), "MonitorStore");
        expect("цепочка через настоящий дочерний процесс выполняется",
                chain.success() && chain.steps().size() == 3
                        && chain.step("search") != null && chain.step("summarize") != null
                        && chain.step("saveToFile") != null);
        expect("данные переданы без искажений (все три связки sha256 проверены)",
                chain.file() != null
                        && String.valueOf(chain.steps().get(0).data().get("payloadSha256"))
                        .equals(String.valueOf(chain.steps().get(1).data().get("sourceSha256")))
                        && String.valueOf(chain.steps().get(0).data().get("payloadSha256"))
                        .equals(String.valueOf(chain.steps().get(2).data().get("sourceSha256")))
                        && String.valueOf(chain.steps().get(1).data().get("summarySha256"))
                        .equals(String.valueOf(chain.steps().get(2).data().get("summarySha256"))));
        expect("файл результата перечитан с тем же SHA-256",
                chain.file() != null
                        && Files.exists(chain.file())
                        && PipelineCanonicalJson.sha256Hex(Files.readAllBytes(chain.file()))
                        .equals(String.valueOf(chain.steps().get(2).data().get("fileSha256"))));
        pipelineChildProcessLeakCheck(noPipelineChildProcess(results), "после успеха");
        expect("во временном каталоге результатов нет temp-файлов",
                onlyMdFiles(results));

        // --- сбой на шаге 1: последующие шаги не вызываются, файла нет ---
        PipelineRunner.ChainResult failed = pipelineRunner(results).run(
                root.resolve("missing-dir").toString(), "MonitorStore");
        expect("плохой путь останавливает цепочку на шаге 1/3 (search)",
                !failed.success() && "search".equals(failed.failedTool())
                        && failed.steps().size() == 1
                        && failed.error() != null && failed.file() == null);
        expect("после ошибки шагов 2 и 3 не было и файл не создан",
                onlyMdFiles(results) && resultsFileNames(results).size() == 1);
        pipelineChildProcessLeakCheck(noPipelineChildProcess(results), "после ошибки");

        // расширение идеи: ~ и относительный путь разворачиваются
        expect("~ разворачивается в дом пользователя",
                PipelineRunner.expandPath("~").equals(Path.of(System.getProperty("user.home"))));
        expect("относительный путь приводится к абсолютной форме",
                PipelineRunner.expandPath("src/main").endsWith(Path.of("src/main"))
                        && PipelineRunner.expandPath("src/main").isAbsolute());
    }

     static void checkPipelineCommandsViaMain() throws Exception {
        Path base = Files.createTempDirectory(baseTempDir, "pipeline-main-base-");
        Path root = pipelineCorpus(base);
        Path results = Files.createTempDirectory(baseTempDir, "pipeline-main-results-");
        System.setProperty("ai-agent.pipeline.results-dir", results.toString());
        System.setProperty("ai-agent.pipeline.server-command", pipelineChildCommand(results));
        try {
            Config config = new Config("test-key", "https://127.0.0.1:1/v1/chat/completions",
                    "glm-5.3-flash");
            LlmAgent agent = new LlmAgent(config, ModelSettings.from(
                    Map.of("LLM_DIAGNOSTICS", "true")), tempStore());

            // /mcp pipeline tools — ровно три инструмента.
            FakeUi toolsUi = new FakeUi(TerminalUi.Input.command("/mcp pipeline tools"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(toolsUi, agent, "test-model");
            String toolsText = String.join("\n", toolsUi.systems);
            expect("/mcp pipeline tools возвращает ровно три инструмента",
                    toolsText.contains("Инструменты (3)")
                            && toolsText.contains("search") && toolsText.contains("summarize")
                            && toolsText.contains("saveToFile"));

            // /mcp pipeline run — полная цепочка через обработчик Main.
            FakeUi runUi = new FakeUi(
                    TerminalUi.Input.command("/mcp pipeline run " + root + " MonitorStore"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(runUi, agent, "test-model");
            String runText = String.join("\n", runUi.systems);
            expect("/mcp pipeline run выполняет цепочку без вызова модели",
                    runUi.errors.isEmpty()
                            && runText.contains("1/3 search ✓")
                            && runText.contains("2/3 summarize ✓")
                            && runText.contains("целостность входа подтверждена")
                            && runText.contains("3/3 saveToFile ✓")
                            && runText.contains("Цепочка выполнена"));
            expect("run при LLM_DIAGNOSTICS=true показывает хеши и длительность шагов",
                    runText.contains("sourceSha256") && runText.contains("мс"));
            expect("команда run создала ровно один файл результатов",
                    resultsFileNames(results).size() == 1
                            && resultsFileNames(results).get(0).endsWith(".md"));

            // Ручной вызов одного инструмента командой.
            FakeUi callUi = new FakeUi(
                    TerminalUi.Input.command("/mcp pipeline call search {\"root\":\""
                            + root + "\",\"query\":\"MonitorStore\"}"),
                    TerminalUi.Input.command("/exit"));
            Main.runLoop(callUi, agent, "test-model");
            expect("/mcp pipeline call search возвращает payloadSha256",
                    callUi.errors.isEmpty()
                            && String.join("\n", callUi.systems).contains("payloadSha256"));

            // Неверные аргументы команды — понятные подсказки.
            expect("run без запроса даёт понятную подсказку",
                    pipelineRunUsage(agent).contains("Использование: /mcp pipeline run"));
            expect("call с неизвестным инструментом даёт понятную подсказку",
                    pipelineCallUsage(agent).contains("<search|summarize|saveToFile>"));
        } finally {
            System.clearProperty("ai-agent.pipeline.results-dir");
            System.clearProperty("ai-agent.pipeline.server-command");
        }
    }
}
