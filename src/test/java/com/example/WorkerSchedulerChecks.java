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

final class WorkerSchedulerChecks extends SelfTestSupport {
    static void run() throws Exception {
        checkWorkerSchedulerInterval();
        checkWorkerCoalesce();
        checkWorkerBusyTicker();
        checkWorkerSummaryRetention();
        checkWorkerStoreRefresh();
        checkWorkerScheduleIsolation();
    }

     static void checkWorkerSchedulerInterval() throws Exception {
        MonitorStore store = workerStore("t4");
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        MonitorWorker worker = new MonitorWorker(clock);
        MonitorSchedule schedule = workerSchedule(store, baseTempDir, "t4");

        try (ClasspathOverride ignored = new ClasspathOverride()) {
            worker.tick(store);
        }
        MonitorSchedule afterFirst = store.schedule(schedule.id());
        expect("T4 первый тик запускает расписание (nextRunAt = now + interval)",
                afterFirst.nextRunAt().equals(clock.instant().plusSeconds(30).toString()));
        expect("T4 первый тик создаёт ровно один успешный запуск",
                store.runs(schedule.id()).size() == 1
                        && store.runs(schedule.id()).get(0).success());

        store.setEnabled(schedule.id(), false);
        clock.advanceSeconds(60);
        worker.tick(store);
        expect("T4 отключённое расписание не запускается тиком",
                store.schedule(schedule.id()).nextRunAt()
                        .equals(clock.instant().minusSeconds(30).toString())
                        && store.runs(schedule.id()).size() == 1);

        // Удаление: нагрузочный тик после remove не создаёт новых записей,
        // а запись в store для удалённого id невозможна (guard на уровне store).
        store.setEnabled(schedule.id(), true);
        store.remove(schedule.id());
        clock.advanceSeconds(60);
        worker.tick(store);
        expect("T4 тик после remove не создаёт записей для удалённого расписания",
                store.schedule(schedule.id()) == null
                        && store.runs(schedule.id()).isEmpty()
                        && store.summaries(schedule.id()).isEmpty());
        MonitorRun removedRun = new MonitorRun(schedule.id(), Instant.now().toString(),
                Instant.now().toString(), true, 1, "main", false, null, true, 0, 0, 0, 0,
                null, null);
        try {
            store.record(removedRun, null, null);
            expect("T4 запись для удалённого расписания отклоняется store", false);
        } catch (MonitorException e) {
            expect("T4 запись для удалённого расписания отклоняется store", true);
        }
        expect("T4 после отклонённой записи run-ов для удалённого id нет",
                store.runs(schedule.id()).isEmpty());
    }

     static void checkWorkerCoalesce() throws Exception {
        MonitorStore store = workerStore("t6");
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        MonitorWorker worker = new MonitorWorker(clock);
        MonitorSchedule schedule = workerSchedule(store, baseTempDir, "t6");
        store.setTiming(schedule.id(), 0, clock.instant().toString());
        clock.advanceSeconds(95);

        try (ClasspathOverride ignored = new ClasspathOverride()) {
            worker.tick(store);
        }
        MonitorSchedule after = store.schedule(schedule.id());
        expect("T6 простой в 3 интервала даёт ровно один запуск",
                store.runs(schedule.id()).size() == 1);
        expect("T6 missedCount после пропуска = 3",
                after != null && after.missedCount() == 3);
        expect("T6 следующий запуск через один интервал",
                after != null && after.nextRunAt().equals(clock.instant().plusSeconds(30).toString()));
    }

     static void checkWorkerBusyTicker() throws Exception {
        MonitorStore store = workerStore("t7");
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        MonitorWorker worker = new MonitorWorker(clock);
        MonitorSchedule schedule = workerSchedule(store, baseTempDir, "t7");
        store.setTiming(schedule.id(), 0, clock.instant().toString());
        clock.advanceSeconds(30);

        long start = System.nanoTime();
        try (MonitorStore.RuntimeLease lease = store.tryRuntimeLock(schedule.id())) {
            worker.tick(store);
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        expect("T7 занятый runtime-lock приводит ровно к одному BUSY-запуску",
                store.runs(schedule.id()).size() == 1
                        && "BUSY".equals(store.runs(schedule.id()).get(0).errorCode())
                        && store.runs(schedule.id()).get(0).attempt() == 0);
        expect("T7 тик не ждёт таймаута (меньше 5 с)",
                elapsedMillis < 5_000);
    }

     static void checkWorkerSummaryRetention() throws Exception {
        MonitorStore store = workerStore("t9");
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        MonitorWorker worker = new MonitorWorker(clock);
        MonitorSchedule schedule = workerSchedule(store, baseTempDir, "t9");

        try (ClasspathOverride ignored = new ClasspathOverride()) {
            worker.tick(store);
            clock.advanceSeconds(30);
            worker.tick(store);
        }
        expect("T9 каждый запуск тика добавляет ровно одну сводку",
                store.summaries(schedule.id()).size() == 2);
        MonitorSummary latest = store.summaries(schedule.id()).get(1);
        expect("T9 окно сводки соответствует summaryInterval",
                latest.windowStart() != null
                        && latest.windowEnd() != null
                        && java.time.Instant.parse(latest.windowStart())
                                .isBefore(java.time.Instant.now()));
        expect("T9 сводка фиксирует успех последнего запуска",
                latest.successCount() == 2 && latest.failureCount() == 0
                        && Boolean.TRUE.equals(latest.clean()));

        for (int i = 0; i < 53; i++) {
            store.recordSummary(new MonitorSummary(schedule.id(),
                    Instant.now().minusSeconds(60).toString(), Instant.now().toString(),
                    0, 0, 0, null, null, null, 0, 0, 0, 0, null, null, null, null,
                    List.of(), List.of(), false));
        }
        expect("T9 retention сводок не превышает 50", store.summaries(schedule.id()).size() == 50);
    }

     static void checkWorkerStoreRefresh() throws Exception {
        MonitorStore store = workerStore("t5");
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        MonitorWorker worker = new MonitorWorker(clock);
        MonitorSchedule first = workerSchedule(store, baseTempDir, "t5a");
        store.setTiming(first.id(), 0, clock.instant().toString());
        clock.advanceSeconds(30);

        // «Внешнее» изменение: новое расписание и отключение первого.
        MonitorSchedule second = workerSchedule(store, baseTempDir, "t5b");
        store.setEnabled(first.id(), false);

        try (ClasspathOverride ignored = new ClasspathOverride()) {
            worker.tick(store);
        }
        expect("T5 отключённое извне расписание не запускается",
                store.runs(first.id()).isEmpty());
        expect("T5 новое расписание запускается существующим worker'ом (тот же объект)",
                store.runs(second.id()).size() == 1
                        && store.runs(second.id()).get(0).success());

        // Внешнее включение первого обратно -> следующий тик запускает и его.
        store.setEnabled(first.id(), true);
        clock.advanceSeconds(30);
        try (ClasspathOverride ignored = new ClasspathOverride()) {
            worker.tick(store);
        }
        expect("T5 повторное включение извне подхватывается без перезапуска",
                store.runs(first.id()).size() == 1);
    }

     static void checkWorkerScheduleIsolation() throws Exception {
        MonitorStore store = workerStore("t8");
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        MonitorWorker worker = new MonitorWorker(clock);
        MonitorSchedule busy = workerSchedule(store, baseTempDir, "t8a");
        MonitorSchedule healthy = workerSchedule(store, baseTempDir, "t8b");
        store.setTiming(busy.id(), 0, clock.instant().toString());
        store.setTiming(healthy.id(), 0, clock.instant().toString());
        clock.advanceSeconds(30);

        // «Зависший» запуск: один тик держит runtime-lock (как застрявший MCP),
        // тик одного прохода должен немедленно пропустить busy и выполнить healthy.
        long start = System.nanoTime();
        try (MonitorStore.RuntimeLease stuck = store.tryRuntimeLock(busy.id())) {
            try (ClasspathOverride ignored = new ClasspathOverride()) {
                worker.tick(store);
            }
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        expect("T8 застрявшее расписание даёт BUSY, не блокируя тик",
                store.runs(busy.id()).size() == 1
                        && "BUSY".equals(store.runs(busy.id()).get(0).errorCode()));
        expect("T8 здоровое расписание того же тика выполняется успешно",
                store.runs(healthy.id()).size() == 1
                        && store.runs(healthy.id()).get(0).success());
        expect("T8 общий тик не ждал таймаута зависшего (меньше 8 с)",
                elapsedMillis < 8_000);
    }
}
