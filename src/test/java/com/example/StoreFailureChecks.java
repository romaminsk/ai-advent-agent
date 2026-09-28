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

final class StoreFailureChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkStoreAtomicWriteFailure();
        checkStoreConcurrentWriters();
        checkRunnerRetrySilentMcp();
    }

     static void checkStoreAtomicWriteFailure() throws Exception {
        Path file = Files.createTempDirectory(baseTempDir, "t11-store-")
                .resolve("git-monitor.json");
        MonitorStore store = new MonitorStore(file);
        MonitorSchedule schedule = workerSchedule(store, baseTempDir, "t11");
        String original = Files.readString(file, StandardCharsets.UTF_8);
        Path dir = file.getParent();

        // Каталог становится недоступным для записи: createTempFile в save()
        // падает до ATOMIC_MOVE и переименование невозможно по построению.
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));
        String afterFailure;
        try {
            try {
                store.setEnabled(schedule.id(), false);
                expect("T11 сбой записи без права на запись отклоняется", false);
            } catch (MonitorException e) {
                expect("T11 сбой записи без права на запись отклоняется", true);
            }
            afterFailure = Files.readString(file, StandardCharsets.UTF_8);
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
        expect("T11 исходный git-monitor.json не изменён после сбоя",
                afterFailure.equals(original));
        expect("T11 временный .tmp-файл не остался после сбоя",
                !Files.exists(file.resolveSibling(file.getFileName() + ".tmp")));
        MonitorStore reopened = new MonitorStore(file);
        expect("T11 исходный git-monitor.json читается после сбоя",
                reopened.schedule(schedule.id()) != null
                        && reopened.schedule(schedule.id()).enabled()
                        && reopened.schedules().size() == 1);
    }

     static void checkStoreConcurrentWriters() throws Exception {
        Path storeFile = Files.createTempDirectory(baseTempDir, "t12-store-")
                .resolve("git-monitor.json");
        Path logA = Files.createTempFile(baseTempDir, "t12-a-", ".log");
        Path logB = Files.createTempFile(baseTempDir, "t12-b-", ".log");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = withChildClasspath();
        Process processA = new ProcessBuilder(javaBin, "-cp", classpath,
                SelfTestStoreWriter.class.getName(), storeFile.toAbsolutePath().toString(), "15", "a")
                .redirectOutput(logA.toFile()).redirectErrorStream(true).start();
        Process processB = new ProcessBuilder(javaBin, "-cp", classpath,
                SelfTestStoreWriter.class.getName(), storeFile.toAbsolutePath().toString(), "15", "b")
                .redirectOutput(logB.toFile()).redirectErrorStream(true).start();
        expect("T12 первый писатель завершился успешно",
                processA.waitFor(120, TimeUnit.SECONDS) && processA.exitValue() == 0);
        expect("T12 второй писатель завершился успешно",
                processB.waitFor(120, TimeUnit.SECONDS) && processB.exitValue() == 0);

        MonitorStore result = new MonitorStore(storeFile);
        List<MonitorSchedule> schedules = result.schedules();
        expect("T12 оба писателя записали все расписания (нет потерянных обновлений)",
                schedules.size() == 30);
        expect("T12 идентификаторы расписаний уникальны",
                schedules.stream().map(MonitorSchedule::id).distinct().count() == 30);
        long fromA = schedules.stream().filter(s -> s.repositoryRoot().startsWith("repo-a-")).count();
        long fromB = schedules.stream().filter(s -> s.repositoryRoot().startsWith("repo-b-")).count();
        expect("T12 по 15 расписаний от каждого процесса", fromA == 15 && fromB == 15);
        JsonNode root = MAPPER.readTree(Files.readString(storeFile, StandardCharsets.UTF_8));
        expect("T12 git-monitor.json после гонки валиден",
                root.path("schemaVersion").asInt(-1) == MonitorStore.SCHEMA_VERSION
                        && root.path("schedules").isArray()
                        && root.path("schedules").size() == 30);
    }

     static void checkRunnerRetrySilentMcp() throws Exception {
        Path stubDir = Files.createTempDirectory(baseTempDir, "t13-stub-");
        compileSilentStub(stubDir);
        MonitorStore store = workerStore("t13");
        MonitorSchedule schedule = workerSchedule(store, baseTempDir, "t13");
        store.setTiming(schedule.id(), 0, Instant.now().toString());

        String previousClasspath = System.getProperty("java.class.path");
        try {
            // Подставной GitMcpServer первым в classpath: ребёнок живёт, но
            // никогда не отвечает на initialize -> TIMEOUT -> повторы.
            System.setProperty("java.class.path", stubDir + File.pathSeparator
                    + previousClasspath);
            MonitorRunner.Result result = new MonitorRunner(store).run(schedule.id());
            expect("T13 молчащий MCP приводит к неуспешному запуску",
                    !result.run().success());
            expect("T13 код ошибки повторяемый (TIMEOUT или MCP_START)",
                    "TIMEOUT".equals(result.run().errorCode())
                            || "MCP_START".equals(result.run().errorCode()));
            expect("T13 не больше двух повторов (attempt <= 3)",
                    result.run().attempt() <= 3);
            expect("T13 финальная попытка — третья",
                    result.run().attempt() == 3);
            String message = result.run().errorMessage() == null ? ""
                    : result.run().errorMessage();
            expect("T13 сообщение ошибки безопасное",
                    message.length() <= 240 && !message.contains(stubDir.toString())
                            && !message.contains("target/") && !message.contains("Exception")
                            && !message.contains("\n"));
        } finally {
            System.setProperty("java.class.path", previousClasspath);
        }
        // Дочерний процесс stub'а должен погибнуть сам после закрытия транспорта.
        long stale = 0;
        for (int i = 0; i < 30; i++) {
            stale = ProcessHandle.allProcesses()
                    .filter(p -> p.info().commandLine()
                            .map(line -> line.contains(stubDir.toString()))
                            .orElse(false))
                    .count();
            if (stale == 0) {
                break;
            }
            Thread.sleep(500);
        }
        expect("T13 дочерний молчащий процесс убит после завершения", stale == 0);
    }
}
