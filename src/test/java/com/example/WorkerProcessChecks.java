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

final class WorkerProcessChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkWorkerProcessStarts();
        checkWorkerSecondInstanceRejected();
        checkWorkerHeartbeatAndShutdown();
        checkWorkerNoSecretLeak();
    }

     static void checkWorkerProcessStarts() throws Exception {
        Path home = Files.createTempDirectory(baseTempDir, "worker-t1-home-");
        Path stdout = Files.createTempFile(baseTempDir, "t1-out-", ".txt");
        Path stderr = Files.createTempFile(baseTempDir, "t1-err-", ".txt");
        Process process = startWorkerProcess(home, stdout, stderr, Map.of());
        try {
            awaitWorkerHeartbeat(home, "running", 30);
            expect("T1 worker-процесс жив без LLM_API_KEY", process.isAlive());
            workerIoLineCheck(process, stdout, stderr);
        } finally {
            process.destroy();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
        expect("T1 worker-процесс завершился штатно после destroy", process.exitValue() == 0);
        checkWorkerProcessEarlyDestroy();
    }

     static void checkWorkerProcessEarlyDestroy() throws Exception {
        Path home = Files.createTempDirectory(baseTempDir, "worker-t1b-home-");
        Path stdout = Files.createTempFile(baseTempDir, "t1b-out-", ".txt");
        Path stderr = Files.createTempFile(baseTempDir, "t1b-err-", ".txt");
        Process process = startWorkerProcess(home, stdout, stderr, Map.of());
        process.destroy();
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        expect("T1b ранний destroy завершается (0 или 143)", exited
                && (process.exitValue() == 0 || process.exitValue() == 143));
        expect("T1b после раннего destroy нет дочерних процессов", process.descendants().count() == 0);
        Path lock = home.resolve(".ai-advent-agent").resolve("monitor-worker.lock");
        boolean free = !Files.exists(lock);
        if (Files.exists(lock)) {
            try (FileChannel channel = FileChannel.open(lock, java.nio.file.StandardOpenOption.READ,
                    java.nio.file.StandardOpenOption.WRITE)) {
                free = channel.tryLock() != null;
            }
        }
        expect("T1b lock-файл свободен", free);
    }

     static void checkWorkerSecondInstanceRejected() throws Exception {
        Path home = Files.createTempDirectory(baseTempDir, "worker-t2-home-");
        Path stdoutA = Files.createTempFile(baseTempDir, "t2-out-", ".txt");
        Path stderrA = Files.createTempFile(baseTempDir, "t2-err-", ".txt");
        Process first = startWorkerProcess(home, stdoutA, stderrA, Map.of());
        try {
            awaitWorkerHeartbeat(home, "running", 30);
            Path stdoutB = Files.createTempFile(baseTempDir, "t2b-out-", ".txt");
            Path stderrB = Files.createTempFile(baseTempDir, "t2b-err-", ".txt");
            Process second = startWorkerProcess(home, stdoutB, stderrB, Map.of());
            expect("T2 второй worker завершается сам (<= 30 с)",
                    second.waitFor(30, TimeUnit.SECONDS));
            expect("T2 второй worker возвращает ненулевой код выхода",
                    second.exitValue() != 0);
            String errorText = Files.readString(stderrB, StandardCharsets.UTF_8);
            expect("T2 ошибка второго worker'а понятна", errorText.contains("уже запущен"));
            expect("T2 вывод второго worker'а не упоминает пути сборки",
                    !errorText.contains("target/") && !errorText.contains("java -cp"));
            expect("T2 первый worker продолжает работать после попытки второго",
                    first.isAlive());
        } finally {
            first.destroy();
            if (!first.waitFor(20, TimeUnit.SECONDS)) {
                first.destroyForcibly();
            }
        }
    }

     static void checkWorkerHeartbeatAndShutdown() throws Exception {
        Path home = Files.createTempDirectory(baseTempDir, "worker-t3-home-");
        Path stdout = Files.createTempFile(baseTempDir, "t3-out-", ".txt");
        Path stderr = Files.createTempFile(baseTempDir, "t3-err-", ".txt");
        Process process = startWorkerProcess(home, stdout, stderr, Map.of());
        awaitWorkerHeartbeat(home, "running", 30);
        expect("T3 worker-процесс жив после первого heartbeat", process.isAlive());
        String firstHeartbeat = Files.readString(workerHeartbeatFile(home), StandardCharsets.UTF_8);
        int updates = 0;
        String latest = firstHeartbeat;
        for (int i = 0; i < 9; i++) {
            Thread.sleep(1_000);
            String next = Files.readString(workerHeartbeatFile(home), StandardCharsets.UTF_8);
            if (!next.equals(latest)) {
                updates++;
            }
            latest = next;
        }
        String secondHeartbeat = latest;
        expect("T3 heartbeat обновляется (" + updates + " обновлений за 9 с; до: "
                + firstHeartbeat.replace("\n", " ") + "; после: "
                + secondHeartbeat.replace("\n", " ") + ")",
                !firstHeartbeat.equals(secondHeartbeat)
                        && secondHeartbeat.contains("running"));
        long started = System.nanoTime();
        process.destroy();
        expect("T3 после SIGTERM выход не дольше 20 с", process.waitFor(20, TimeUnit.SECONDS));
        long exitMillis = (System.nanoTime() - started) / 1_000_000;
        expect("T3 после SIGTERM код выхода 0", process.exitValue() == 0);
        String stopped = awaitWorkerHeartbeat(home, "stopped", 10);
        expect("T3 heartbeat после завершения сообщает stopped",
                MAPPER.readTree(stopped).path("pid").asLong(-1) == process.pid()
                        && MAPPER.readTree(stopped).path("state").asText("").equals("stopped"));
        // lock-файлы освобождены: tryLock из теста проходит по каждому *.lock.
        Path dir = home.resolve(".ai-advent-agent");
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(name -> name.toString().endsWith(".lock")).toList()) {
                try (FileChannel channel = FileChannel.open(file,
                        java.nio.file.StandardOpenOption.READ,
                        java.nio.file.StandardOpenOption.WRITE)) {
                    expect("T3 lock-файл освобождён: " + file.getFileName(),
                            channel.tryLock() != null);
                }
            }
        }
        expect("T3 дочерних процессов у worker'а нет",
                process.descendants().count() == 0);
        expect("T3 завершение было быстрым (меньше 10 с)",
                exitMillis < 10_000);
        String errors = Files.readString(stderr, StandardCharsets.UTF_8);
        expect("T3 stderr worker'а при штатном завершении пуст", errors.isBlank());
    }

     static void checkWorkerNoSecretLeak() throws Exception {
        Path home = Files.createTempDirectory(baseTempDir, "worker-t10-home-");
        Path stdout = Files.createTempFile(baseTempDir, "t10-out-", ".txt");
        Path stderr = Files.createTempFile(baseTempDir, "t10-err-", ".txt");
        Process process = startWorkerProcess(home, stdout, stderr,
                Map.of("LLM_API_KEY", T10_TOKEN));
        try {
            awaitWorkerHeartbeat(home, "running", 30);
        } finally {
            process.destroy();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
        String output = Files.readString(stdout, StandardCharsets.UTF_8)
                + Files.readString(stderr, StandardCharsets.UTF_8);
        expect("T10 фиктивный токен не попадает в stdout/stderr",
                !output.contains(T10_TOKEN));
        expect("T10 вывод не упоминает target/",
                !output.contains("target/"));
        expect("T10 вывод не упоминает java -cp",
                !output.contains("java -cp") && !output.contains(" -cp "));
        expect("T10 вывод не упоминает diff", !output.contains("diff"));
        expect("T10 вывод не содержит remote URL",
                !output.contains("https://") && !output.contains("http://"));
    }
}
