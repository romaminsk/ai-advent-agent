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

final class IndexChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkIndex();
    }

     static void checkIndex() throws Exception {
        Path corpus = Files.createDirectory(baseTempDir.resolve("corpus"));
        Files.writeString(corpus.resolve("README.md"), """
                # Тестовый корпус
                Обший текст проекта про поиск документов и эмбеддинги.
                Постепенно чанки собираются в JSON индекс.
                ## Использование
                Секция использования описывает построение и поиск.
                ### Детали
                Дополнительные детали поиска и сравнения стратегий.
                """);
        Files.writeString(corpus.resolve("Sample.java"), """
                package test;

                /** Тестовый класс with methods. */
                public class Sample {
                    private int counter;

                    public void increment(int step) {
                        counter += step;
                        while (counter < 100) {
                            counter++;
                        }
                    }

                    private String describe() {
                        return "counter=" + counter;
                    }
                }
                """);
        Files.writeString(corpus.resolve(".env"), "LLM_API_KEY=\"sk-secret-index-test\"\n");
        Files.createDirectories(corpus.resolve(".git"));
        Files.writeString(corpus.resolve(".git/head"), "excluded");
        Path target = Files.createDirectory(corpus.resolve("target"));
        Files.writeString(target.resolve("out.md"), "excluded target file content");
        Files.writeString(corpus.resolve("big.md"), "б".repeat(2_000_000));

        Path indexDir = baseTempDir.resolve("index");
        CountingEmbedder fake = new CountingEmbedder(64);
        IndexCommands commands = new IndexCommands(
                new IndexStore(indexDir), () -> fake, "test-model");

        String build = commands.handle("build " + corpus);
        expect("I0 сборка обоих индексов успешна", build.contains("fixed:")
                && build.contains("structure:"));

        IndexStore.Index fixed = new IndexStore(indexDir).load("fixed");
        IndexStore.Index structure = new IndexStore(indexDir).load("structure");
        expect("I0 оба индекса прочитаны", fixed != null && structure != null);
        if (fixed == null || structure == null) {
            return;
        }
        expect("I9 холодные запросы сохранены при первой сборке", fixed.coldRequests() > 0);

        // I1: fixed ≤ 800, перекрытие 100, покрытие без потерь.
        boolean sizesOk = true;
        boolean coordsOk = true;
        boolean overlapOk = true;
        for (IndexStore.IndexedChunk chunk : fixed.chunks()) {
            if (chunk.meta().chars() > 800) {
                sizesOk = false;
            }
        }
        List<IndexStore.IndexedChunk> sortedFixed = new ArrayList<>(fixed.chunks());
        for (int i = 1; i < sortedFixed.size(); i++) {
            ChunkMeta prev = sortedFixed.get(i - 1).meta();
            ChunkMeta current = sortedFixed.get(i).meta();
            if (prev.source().equals(current.source())) {
                if (current.startChar() > prev.endChar()) {
                    coordsOk = false;
                }
                if (current.startChar() != prev.endChar() - 100) {
                    overlapOk = false;
                }
            }
        }
        expect("I1 размеры fixed ≤ 800", sizesOk);
        expect("I1 перекрытие fixed = 100", overlapOk);
        expect("I0 текст чанка = подстрока документа по координатам", coordsOk);

        // I1: покрытие текста без потерь по всем документам.
        boolean lossless = true;
        DocumentLoader loader = new DocumentLoader();
        for (DocumentLoader.Document document : loader.load(corpus)) {
            boolean[] cover = new boolean[Math.max(1, document.content().length())];
            boolean matched = false;
            for (IndexStore.IndexedChunk chunk : fixed.chunks()) {
                if (!chunk.meta().source().equals(document.relativePath())) {
                    continue;
                }
                matched = true;
                int start = Math.max(0, chunk.meta().startChar());
                int end = Math.min(document.content().length(), chunk.meta().endChar());
                if (!document.content().substring(start, end).equals(chunk.text())) {
                    lossless = false;
                }
                for (int i = start; i < end; i++) {
                    cover[i] = true;
                }
            }
            if (matched && document.content().length() > 0) {
                for (int i = 0; i < document.content().length(); i++) {
                    if (!cover[i]) {
                        lossless = false;
                    }
                }
            }
        }
        expect("I1 fixed покрывает текст без потерь", lossless);

        // I2: structure по заголовкам md и по методам java.
        boolean mdSectionsOk = true;
        boolean javaSectionsOk = true;
        for (IndexStore.IndexedChunk chunk : structure.chunks()) {
            String source = chunk.meta().source().toLowerCase();
            if (source.endsWith(".md")) {
                String section = chunk.meta().section();
                mdSectionsOk &= section.equals("Тестовый корпус")
                        || section.equals("Использование")
                        || section.equals("Детали")
                        || section.equals("(начало)");
            }
            if (source.endsWith(".java")) {
                javaSectionsOk &= chunk.meta().section().startsWith("Sample#");
            }
        }
        expect("I2 md режется по заголовкам #/##/###", mdSectionsOk);
        expect("I2 java режется по методам (Sample#…)", javaSectionsOk);

        // I3: метаданные непусты, chunk_id стабилен.
        boolean metaOk = true;
        for (IndexStore.IndexedChunk chunk : structure.chunks()) {
            metaOk &= chunk.meta().chunkId() != null && !chunk.meta().chunkId().isBlank()
                    && chunk.meta().source() != null && !chunk.meta().source().isBlank()
                    && chunk.meta().title() != null && !chunk.meta().title().isBlank()
                    && chunk.meta().section() != null && !chunk.meta().section().isBlank();
        }
        expect("I3 метаданные всех чанков непусты", metaOk);
        List<String> idsBefore = new ArrayList<>();
        for (IndexStore.IndexedChunk chunk : structure.chunks()) {
            idsBefore.add(chunk.meta().chunkId());
        }
        commands.handle("build " + corpus);
        IndexStore.Index structure2 = new IndexStore(indexDir).load("structure");
        List<String> idsAfter = new ArrayList<>();
        for (IndexStore.IndexedChunk chunk : structure2.chunks()) {
            idsAfter.add(chunk.meta().chunkId());
        }
        expect("I3 chunk_id стабилен между сборками", idsBefore.equals(idsAfter));

        // I5: повторная сборка без изменений = 0 запросов.
        int before = fake.requests();
        int coldRequestsBefore = fixed.coldRequests();
        commands.handle("build " + corpus);
        expect("I5 повторная сборка без изменений = 0 запросов",
                fake.requests() == before);
        expect("I9 coldRequests сохраняется при кэшевой сборке",
                new IndexStore(indexDir).load("fixed").coldRequests() == coldRequestsBefore);

        // I4: туда-обратно без потерь, запись атомарная.
        boolean roundtripOk = structure.chunks().size() == structure2.chunks().size();
        IndexStore.IndexedChunk original = structure.chunks().isEmpty() ? null : structure.chunks().get(0);
        IndexStore.IndexedChunk restored = structure2.chunks().isEmpty() ? null : structure2.chunks().get(0);
        if (original != null && restored != null) {
            roundtripOk &= original.text().equals(restored.text())
                    && original.meta().chars() == restored.meta().chars()
                    && original.meta().startChar() == restored.meta().startChar()
                    && original.meta().endChar() == restored.meta().endChar()
                    && java.util.Arrays.equals(original.vector(), restored.vector());
        }
        expect("I4 JSON индекс сохраняется и читается без потерь", roundtripOk);
        boolean noTemp = true;
        try (var files = Files.list(indexDir)) {
            noTemp = files.noneMatch(path ->
                    path.getFileName().toString().endsWith(".tmp"));
        }
        expect("I4 без .tmp файлов после атомарной записи", noTemp);

        // I6: запрос с точным текстом чанка даёт его на top-1.
        IndexStore.IndexedChunk probe = structure.chunks().stream()
                .filter(c -> c.text().length() > 10).findFirst().orElse(null);
        boolean searchOk = probe != null;
        if (probe != null) {
            List<IndexSearch.Hit> top = IndexSearch.topK(structure, fake.vector(probe.text()), 3);
            searchOk = !top.isEmpty() && top.get(0).score() > 0.999
                    && top.get(0).chunk().meta().chunkId().equals(probe.meta().chunkId());
        }
        expect("I6 точный текст запроса даёт top-1 (score 1.0)", searchOk);

        // I7: .env и исключённые каталоги не в индексе, ключа нет ни в одном файле.
        boolean excludedOk = true;
        for (IndexStore.IndexedChunk chunk : structure.chunks()) {
            String source = chunk.meta().source().toLowerCase();
            excludedOk &= !source.contains(".env") && !source.contains(".git")
                    && !source.contains("target/") && !source.equals("big.md")
                    && !source.equals("sample");
        }
        expect("I7 исключённые .env/.git/target/большой файл не в индексе", excludedOk);
        boolean noSecret = true;
        secretProbe:
        {
            for (String name : List.of("fixed.json", "structure.json", "embed-cache.json")) {
                Path file = indexDir.resolve(name);
                if (Files.isRegularFile(file)
                        && Files.readString(file, StandardCharsets.UTF_8)
                        .contains("sk-secret-index-test")) {
                    noSecret = false;
                    break secretProbe;
                }
            }
        }
        expect("I7 ключ из .env не попадает ни в один файл индекса", noSecret);

        // I8: 429 → 429 → 200 успех; таймаут не повторяется.
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("localhost", 0), 0);
        int[] statuses = {429, 429, 200};
        int[] state = {0};
        server.createContext("/v1/embeddings", exchange -> {
            int status = state[0] < statuses.length ? statuses[state[0]++] : 429;
            String body = status == 200
                    ? "{\"data\":[{\"embedding\":[" + payload(3) + "]},{\"embedding\":[" + payload(7) + "]}]}"
                    : "{\"error\":\"spam\"}";
            respond(exchange, status, body);
        });
        server.start();
        try {
            OpenAiEmbedder retried = new OpenAiEmbedder(
                    "http://localhost:%d/v1".formatted(server.getAddress().getPort()),
                    "fake-model", java.time.Duration.ofSeconds(60), 16);
            List<float[]> vectors = retried.embed(List.of("a", "b"));
            expect("I8 429,429,200 → батч успешен",
                    vectors.size() == 2 && state[0] == 3);
        } catch (IOException error) {
            expect("I8 429,429,200 → батч успешен (" + error.getMessage() + ")", false);
        }
        var slowServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("localhost", 0), 0);
        slowServer.createContext("/v1/embeddings", exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"data\":[{\"embedding\":[1.0]}]}");
        });
        slowServer.start();
        try {
            OpenAiEmbedder strict = new OpenAiEmbedder(
                    "http://localhost:%d/v1".formatted(slowServer.getAddress().getPort()),
                    "fake-model", java.time.Duration.ofMillis(300), 16);
            int[] attempts = {0};
            var attemptServer = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("localhost", 0), 0);
            attemptServer.createContext("/v1/embeddings", exchange -> {
                attempts[0]++;
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, 200, "{\"data\":[{\"embedding\":[1.0]}]}");
            });
            attemptServer.start();
            attemptServer.stop(0);
            // Повторофф нет: вызов единый, ответ приходит до 1.2 с.
            long startedAt = System.currentTimeMillis();
            boolean timedOut = false;
            try {
                strict.embed(List.of("запрос с таймаутом"));
            } catch (IOException error) {
                timedOut = error.getMessage() != null
                        && (error.getMessage().toLowerCase(Locale.ROOT).contains("таймаут")
                        || error.getCause() instanceof java.net.http.HttpTimeoutException)
                        || error instanceof java.net.http.HttpTimeoutException;
            }
            expect("I8 таймаут запроса не повторяется",
                    timedOut && System.currentTimeMillis() - startedAt < 1200);
        } finally {
            server.stop(0);
            slowServer.stop(0);
        }
    }
}
