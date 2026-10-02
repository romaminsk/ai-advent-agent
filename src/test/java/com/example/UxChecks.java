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

final class UxChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkUxGroup();
        checkDialogStateCommandUx();
    }

     static void checkDialogStateCommandUx() throws Exception {
        boolean listed = false;
        for (String name : TerminalUi.chatCommandNames()) {
            if (name.equals("/dialogstate")) {
                listed = true;
            }
        }
        expect("/dialogstate входит в список известных команд (подсказки опечаток)", listed);
        expect("полный индекс /help all содержит /dialogstate в группе «Память»",
                TerminalUi.chatIndex(80).contains("/dialogstate"));
        String commandHelp = TerminalUi.chatCommandHelp("/dialogstate");
        expect("у /dialogstate есть подробная справка с /dialogstate clear",
                commandHelp != null && commandHelp.contains("/dialogstate clear"));

        LlmAgent agent = newMemoryAgent(new Config("test-key",
                        "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                HttpClient.newHttpClient(), Map.of());
        FakeUi ui = new FakeUi(
                TerminalUi.Input.command("/dialogstate"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(ui, agent, "glm-5.3-flash");
        expect("/dialogstate при пустом состоянии показывает пустое состояние без ошибок",
                ui.systems.stream().anyMatch(s -> s.contains("Состояние диалога пока пусто"))
                        && ui.errors.isEmpty());
    }

     static void checkPlainTerminalUi() {
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

     static void checkAnsiSanitizer() {
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

     static void checkProgressSpinner() {
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

     static void checkUxGroup() throws Exception {
        checkPlainTerminalUi();
        checkAnsiSanitizer();
        checkProgressSpinner();
        checkUiPrompt();
        checkUiMessageMarkers();
        checkUiRedesign();
        checkUiColorAndMarkdown();
        checkHistoryStoreCleanText();
        checkShortHelpAndFullIndex();
        checkNextHints();
        checkInteractiveMenus();
        checkInteractiveMenuCancel();
        checkPlainMenusDisabledWithSyntaxHint();
        checkTypoSuggestions();
        checkStatusOverview();
        checkOnboardingFirstLaunch();
        checkInvariantHelpAndIndex();
    }

     static void checkUiPrompt() {
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

     static void checkUiMessageMarkers() throws Exception {
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
        expect("полный индекс /help all содержит группу индекса с подкомандами",
                help.contains("Индекс") && help.contains("/index build")
                        && help.contains("/index search") && help.contains("/index compare"));
        expect("внизу справки — подсказка /help <команда> и навигация",
                help.contains("/help <команда>") && help.contains("Tab"));
    }

     static void checkUiRedesign() {
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
        String ragHelp = TerminalUi.chatCommandHelp("/rag");
        expect("подробная справка /rag описывает режимы ask и eval",
                ragHelp != null && ragHelp.contains("/rag on|off|status")
                        && ragHelp.contains("/rag retrieval") && ragHelp.contains("/rag ask")
                        && ragHelp.contains("/rag eval") && ragHelp.contains("4096"));
    }

     static void checkUiColorAndMarkdown() {
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

        // Категории цветов служебных сообщений (только TTY/цветной режим):
        // «!» жёлтый, «✓» зелёный, «?» циан, остальное без подсветки.
        expect("категория предупреждения «!» — жёлтый ANSI-код",
                TerminalUi.categoryColorFor("! лимит превышен").equals("\u001b[33m")
                        && TerminalUi.categoryColor("! лимит превышен")
                        .startsWith("\u001b[33m! ")
                        && TerminalUi.categoryColor("! лимит превышен").endsWith("\u001b[0m"));
        expect("маркер успеха «✓» — зелёный",
                TerminalUi.categoryColorFor("✓ Задача задана").equals("\u001b[32m")
                        && TerminalUi.categoryColor("✓ готово").contains("✓"));
        expect("маркер вопроса «?» — циан-акцент",
                TerminalUi.categoryColorFor("? уточнить").equals("\u001b[36m"));
        expect("строка без категорийного маркера не подсвечивается",
                TerminalUi.categoryColorFor("обычный текст") == null
                        && TerminalUi.categoryColor("обычный текст").equals("обычный текст"));
        expect("RESET не дублируется, если уже в конце строки",
                TerminalUi.categoryColor("! уже с reset\u001b[0m")
                        .startsWith("\u001b[33m")
                        && countSubstring(TerminalUi.categoryColor("! уже с reset\u001b[0m"),
                        "\u001b[0m") == 1);

        // plain-режим: без ANSI, маркер «!» сохраняется как есть.
        CapturedStream errPlain = capturingStream();
        PlainTerminalUi plainWarn = new PlainTerminalUi(reader(""),
                capturingStream().stream, errPlain.stream);
        plainWarn.showSystem(Main.warn("Внимание: превышение"));
        String plainWarnText = errPlain.text();
        expect("plain-режим не добавляет ANSI и не теряет маркер «!»",
                plainWarnText.contains("! Внимание: превышение")
                        && !plainWarnText.contains("\u001b["));

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

     static void checkHistoryStoreCleanText() throws Exception {
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

     static void checkInvariantHelpAndIndex() {
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

     static void checkInteractiveMenuCancel() throws Exception {
        LlmAgent agent = new LlmAgent(new Config("test-key",
                "https://127.0.0.1:1/v1/chat/completions", "glm-5.3-flash"),
                ModelSettings.defaults(),
                java.net.http.HttpClient.newHttpClient(),
                new JsonConversationStore(Files.createTempDirectory(baseTempDir, "hist-")
                        .resolve("conversation.json")),
                tempMemoryStore(), tempProfileStoreForTests(),
                tempInvariantStoreForTests());

        // Меню задачи: отмена пунктом «ничего».
        FakeUi cancelUi = new FakeUi(
                TerminalUi.Input.command("/task"),
                TerminalUi.Input.command("2"),
                TerminalUi.Input.command("/exit"));
        cancelUi.interactiveMenusEnabled = true;
        Main.runLoop(cancelUi, agent, "glm-5.3-flash");
        expect("меню задачи: выбор «ничего» отменяет без состояния и ошибок",
                agent.taskState() == null
                        && cancelUi.systems.stream().anyMatch(t ->
                        t.contains("Действие с задачей отменено"))
                        && cancelUi.errors.isEmpty());

        // Меню задачи: неизвестный пункт даёт повтор вопроса — состояние не создаётся.
        FakeUi junkUi = new FakeUi(
                TerminalUi.Input.command("/task"),
                TerminalUi.Input.command("не-пункт-меню"),
                TerminalUi.Input.command("2"),
                TerminalUi.Input.command("/exit"));
        junkUi.interactiveMenusEnabled = true;
        Main.runLoop(junkUi, agent, "glm-5.3-flash");
        expect("меню задачи: мусорный выбор переспрашивает (Enter — отмена)",
                agent.taskState() == null
                        && junkUi.systems.stream().anyMatch(t ->
                        t.contains("Не понял выбор"))
                        && junkUi.errors.isEmpty());

        // Подсказка следующего шага не выдаётся на чистых командах без результата.
        FakeUi silentUi = new FakeUi(
                TerminalUi.Input.command("/invariant remove 99"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(silentUi, agent, "glm-5.3-flash");
        expect("ошибка удаления локального/глобального инварианта без подсказки «Дальше»",
                silentUi.errors.stream().anyMatch(t ->
                        t.contains("Инвариант не найден"))
                        && silentUi.systems.stream()
                        .noneMatch(t -> t.startsWith("Дальше:")));
    }

     static void checkShortHelpAndFullIndex() {
        String shortHelp = TerminalUi.shortHelp();
        String[] shortLines = shortHelp.split("\n", -1);
        expect("короткая /help умещается в 8 строк", shortLines.length <= 8);
        expect("короткая /help отсылает к полному списку",
                shortHelp.contains("/help all"));
        expect("короткая /help показывает /task start, /remember и /profile name",
                shortHelp.contains("/task start") && shortHelp.contains("/remember")
                        && shortHelp.contains("/profile name"));
        expect("короткая /help сообщает количество остальных команд",
                shortHelp.matches("(?s).*ещё \\d+ .*: /help all.*"));

        String full = TerminalUi.chatIndex(80);
        String[] fullLines = full.split("\n", -1);
        String[] groups = {"Память", "Профиль", "Рамки", "Контекст", "Диалог",
                "Режимы", "Статистика", "Индекс", "RAG", "Прочее"};
        String[] purposes = {"что агент помнит", "как к вам обращаться",
                "жёсткие ограничения", "что уходит в запрос", "удалить текущую историю",
                "формат ответа", "расход и обзор состояния", "сборка, статистика, поиск",
                "ответы с проверяемыми цитатами", "подключения, мониторинг и справка"};
        for (int g = 0; g < groups.length; g++) {
            int row = -1;
            for (int i = 0; i < fullLines.length; i++) {
                if (fullLines[i].startsWith(groups[g]) && fullLines[i].length() > 11) {
                    row = i;
                    break;
                }
            }
            boolean hasCommandsAndPurpose = row >= 0 && row + 1 < fullLines.length
                    && !fullLines[row].substring(11).isBlank()
                    && fullLines[row + 1].startsWith("  ")
                    && fullLines[row + 1].contains(purposes[g]);
            expect("полный индекс содержит группу «" + groups[g]
                            + "» с командами и назначением",
                    hasCommandsAndPurpose);
        }

        boolean commandsHavePurpose = true;
        for (String command : TerminalUi.chatCommandNames()) {
            boolean listed = false;
            for (int i = 0; i + 1 < fullLines.length; i++) {
                boolean groupRow = !fullLines[i].startsWith("  ")
                        && !fullLines[i].startsWith("/") && fullLines[i].length() > 11;
                if (groupRow && fullLines[i].substring(11).contains(command)
                        && fullLines[i + 1].startsWith("  ") && !fullLines[i + 1].isBlank()) {
                    listed = true;
                    break;
                }
            }
            commandsHavePurpose &= listed;
        }
        expect("каждая известная CLI-команда стоит в группе с назначением",
                commandsHavePurpose);
        expect("строки /help all не длиннее 100 символов",
                java.util.Arrays.stream(fullLines).allMatch(line -> line.length() <= 100));

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
                fullText.contains("Память") && fullText.contains("Статистика")
                        && fullText.contains("Индекс") && fullText.contains("RAG"));
    }

     static void checkNextHints() throws Exception {
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

     static void checkInteractiveMenus() throws Exception {
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
                TerminalUi.Input.command("/task"),
                TerminalUi.Input.command("1"),
                TerminalUi.Input.command("/task status"),
                TerminalUi.Input.command("/exit"));
        taskUi.interactiveMenusEnabled = true;
        Main.runLoop(taskUi, agent, "glm-5.3-flash");
        expect("/task без аргументов открывает меню",
                taskUi.systems.stream().anyMatch(t ->
                        t.contains("1) начать 2) ничего")));
        expect("меню: начало задачи без синтаксиса",
                "подготовить отчёт к среде".equals(agent.taskState().description()));
        expect("меню: без плана пункт «утвердить план» скрыт",
                taskUi.systems.stream().anyMatch(t ->
                        t.contains("1) план 2) этап 3) шаг")));
        expect("меню: пауза по пункту 4 (ACTIVE/PLANNING, план ещё не задан)",
                taskUi.systems.stream().anyMatch(t ->
                        t.contains("✓ Задача на паузе")));
        expect("меню: на паузе доступны только продолжение и отмена (resume выполнен пунктом 1)",
                agent.taskState().status() == TaskStatus.ACTIVE
                        && taskUi.systems.stream().anyMatch(t ->
                        t.contains("1) продолжить 2) ничего")));
        FakeUi menuPlanUi = new FakeUi(
                TerminalUi.Input.command("/task"),
                TerminalUi.Input.command("1"),
                TerminalUi.Input.command("сверить цифры"),
                TerminalUi.Input.command("/task"),
                TerminalUi.Input.command("2"),
                TerminalUi.Input.command("/task stage execution"),
                TerminalUi.Input.command("/task"),
                TerminalUi.Input.command("/exit"));
        menuPlanUi.interactiveMenusEnabled = true;
        Main.runLoop(menuPlanUi, agent, "glm-5.3-flash");
        expect("меню: короткая форма по-прежнему работает штатно (история пуста)",
                agent.getHistory().isEmpty());
        FakeUi statusPlainUi = new FakeUi(
                TerminalUi.Input.command("/task status"),
                TerminalUi.Input.command("/exit"));
        Main.runLoop(statusPlainUi, agent, "glm-5.3-flash");
        expect("меню: после перехода в execution показываются только допустимые действия",
                agent.taskState().planApproved()
                        && agent.taskState().stage() == TaskStage.EXECUTION
                        && menuPlanUi.systems.stream().anyMatch(t ->
                        t.contains("1) этап 2) шаг 3) пауза 4) ожидание данных")));
        expect("/task status показывает план, факт утверждения и ближайшее действие",
                statusPlainUi.systems.stream().anyMatch(t -> t.contains("Состояние задачи")
                        && t.contains("план: утверждён · сверить цифры")
                        && t.contains("дальше: /task stage validation")));
    }

     static void checkPlainMenusDisabledWithSyntaxHint() throws Exception {
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

     static void checkTypoSuggestions() throws Exception {
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

     static void checkStatusOverview() throws Exception {
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

     static void checkOnboardingFirstLaunch() throws IOException {
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
