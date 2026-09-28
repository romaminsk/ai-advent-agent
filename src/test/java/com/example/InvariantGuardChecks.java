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

final class InvariantGuardChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkInvariantGuardRules();
        checkInvariantGuardNoApiInMessage();
        checkTaskInvariantBlockAndGuard();
        checkInvariantsNotInWorkingMemoryOrHistory();
    }

     static void checkInvariantGuardRules() {
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

     static void checkInvariantGuardNoApiInMessage() throws Exception {
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

     static void checkTaskInvariantBlockAndGuard() throws Exception {
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

     static void checkInvariantsNotInWorkingMemoryOrHistory() throws Exception {
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
}
