package com.example;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Клиент-оркестратор: запускает PipelineMcpServer как дочерний stdio-процесс
 * (тем же способом, что и Git MCP) и последовательно выполняет цепочку
 * search → summarize → saveToFile через tools/call — один живой сеанс.
 * Между шагами проверяет связки SHA-256 и перечитывает записанный файл;
 * при любой ошибке цепочка останавливается, следующие шаги не вызываются,
 * файл не создаётся. Дочерний процесс закрывается гарантированно (try/finally,
 * SDK завершает его при закрытии транспорта).
 */
public final class PipelineRunner {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Результат одного шага: номер с 1, инструмент, успех, ошибка, данные, длительность. */
    public record StepResult(int number, String tool, boolean ok, String error,
                             Map<String, Object> data, long durationMillis) {
    }

    /** Итог цепочки. При успехе file указан; при сбое failedTool и error заполняются. */
    public record ChainResult(boolean success, String failedTool, String error,
                              List<StepResult> steps, Path file) {

        public StepResult step(String tool) {
            for (StepResult step : steps) {
                if (step.tool().equals(tool)) return step;
            }
            return null;
        }
    }

    private final Path resultsDir;
    private final String commandLine;
    private final Duration timeout;

    public PipelineRunner(Path resultsDir) {
        this(resultsDir, PipelineMcpServer.command(resultsDir), McpClientComponent.TIMEOUT);
    }

    /** Для SelfTest: явная команда запуска (всегда java из текущего classpath). */
    public PipelineRunner(Path resultsDir, String commandLine, Duration timeout) {
        this.resultsDir = resultsDir;
        this.commandLine = commandLine;
        this.timeout = timeout;
    }

    /** Полная цепочка по пути и запросу; пути с ~ и относительные разворачиваются. */
    public ChainResult run(String rawRoot, String query) {
        List<StepResult> steps = new ArrayList<>();
        Path root = expandPath(rawRoot);
        Snapshot beforeStart = new Snapshot(resultsDir);
        try {
            // --- 1/3 search ---
            Map<String, Object> searchContent;
            try {
                searchContent = callStep(steps, FileSearcher.TOOL_NAME,
                        Map.of("root", root.toString(), "query", query),
                        result -> text(result.get("payloadSha256")) == null
                                ? "search не вернул payloadSha256." : null);
            } catch (Exception e) {
                return failed(steps, FileSearcher.TOOL_NAME, safe(e), beforeStart);
            }
            if (searchContent == null) {
                return failed(steps, FileSearcher.TOOL_NAME,
                        steps.get(steps.size() - 1).error(), beforeStart);
            }

            // --- 2/3 summarize — вход целиком из search ---
            Map<String, Object> summaryContent;
            try {
                summaryContent = callStep(steps, SearchSummarizer.TOOL_NAME,
                        Map.of("searchResult", searchContent), summary -> {
                            String expected = text(searchContent.get("payloadSha256"));
                            String actual = text(summary.get("sourceSha256"));
                            if (actual == null || !actual.equals(expected)) {
                                return "Целостность входа нарушена: summarize.sourceSha256 ("
                                        + actual + ") != search.payloadSha256 (" + expected + ").";
                            }
                            return null;
                        });
            } catch (Exception e) {
                return failed(steps, SearchSummarizer.TOOL_NAME, safe(e), beforeStart);
            }
            if (summaryContent == null) {
                return failed(steps, SearchSummarizer.TOOL_NAME,
                        steps.get(steps.size() - 1).error(), beforeStart);
            }

            // --- 3/3 saveToFile — вход целиком из summarize ---
            Map<String, Object> saveContent;
            try {
                saveContent = callStep(steps, ResultFileWriter.TOOL_NAME,
                        Map.of("summary", summaryContent), saved -> {
                            String searchPayload = text(searchContent.get("payloadSha256"));
                            String summarySha = text(summaryContent.get("summarySha256"));
                            if (!searchPayload.equals(text(saved.get("sourceSha256")))) {
                                return "saveToFile.sourceSha256 != search.payloadSha256.";
                            }
                            if (summarySha == null || !summarySha.equals(text(saved.get("summarySha256")))) {
                                return "saveToFile.summarySha256 != summarize.summarySha256.";
                            }
                            return null;
                        });
            } catch (Exception e) {
                return failed(steps, ResultFileWriter.TOOL_NAME, safe(e), beforeStart);
            }
            if (saveContent == null) {
                return failed(steps, ResultFileWriter.TOOL_NAME,
                        steps.get(steps.size() - 1).error(), beforeStart);
            }
            String path = text(saveContent.get("path"));
            Integer bytes = SearchSummarizer.integer(saveContent.get("bytes"));
            String fileSha256 = text(saveContent.get("fileSha256"));
            if (path == null || bytes == null || fileSha256 == null) {
                return failed(steps, ResultFileWriter.TOOL_NAME,
                        "saveToFile вернул неполный структурированный результат.", beforeStart);
            }
            // Контроль записи: перечитать файл — SHA-256 совпадает с fileSha256.
            try {
                byte[] actual = Files.readAllBytes(Path.of(path));
                String actualSha = PipelineCanonicalJson.sha256Hex(actual);
                if (actual.length != bytes || !actualSha.equals(fileSha256)) {
                    return failed(steps, ResultFileWriter.TOOL_NAME,
                            "Файл на диске не совпадает с fileSha256 (перечитано " + actual.length
                                    + " байт, sha256 " + actualSha + ").", beforeStart);
                }
            } catch (Exception e) {
                return failed(steps, ResultFileWriter.TOOL_NAME,
                        "Не удалось перечитать файл результата: " + safe(e), beforeStart);
            }
            return new ChainResult(true, null, null, List.copyOf(steps), Path.of(path));
        } finally {
            // Дочерний процесс закрывается всегда: SDK закрывает транспорт в
            // try-with-resources; если процесс ребёнка задержался, завершаем его.
            beforeStart.reapChildren();
        }
    }

    private ChainResult failed(List<StepResult> steps, String tool, String error, Snapshot ignored) {
        return new ChainResult(false, tool, error, List.copyOf(steps), null);
    }

    /**
     * Один tools/call в общем stdio-сеансе; верификатор по структурированному
     * результату возвращает понятную ошибку или null. Ошибка шага останавливает
     * цепочку (следующие шаги не вызываются).
     */
    private Map<String, Object> callStep(List<StepResult> steps, String tool,
                                         Map<String, Object> arguments,
                                         java.util.function.Function<Map<String, Object>, String> verifier)
            throws Exception {
        long started = System.nanoTime();
        StepResult step = callTool(steps.size() + 1, tool, arguments);
        long duration = (System.nanoTime() - started) / 1_000_000;
        step = new StepResult(step.number(), step.tool(), step.ok(), step.error(),
                step.data(), duration);
        steps.add(step);
        if (!step.ok()) {
            return null;
        }
        String mismatch = verifier.apply(step.data());
        if (mismatch != null) {
            steps.set(steps.size() - 1, new StepResult(step.number(), tool, false, mismatch,
                    null, duration));
            return null;
        }
        return step.data();
    }

    /** Транспорт и клиент как в McpClientComponent.callToolOnTransport. */
    private StepResult callTool(int number, String tool, Map<String, Object> arguments)
            throws Exception {
        List<String> parts = McpClientComponent.splitCommand(commandLine);
        if (parts.isEmpty()) {
            throw new McpClientComponent.McpClientException(
                    "Не удалось запустить Pipeline MCP-сервер: команда не задана.");
        }
        AtomicReference<String> stderr = new AtomicReference<>("");
        StdioClientTransport transport = new StdioClientTransport(
                ServerParameters.builder(parts.get(0))
                        .args(parts.subList(1, parts.size())).build(),
                new JacksonMcpJsonMapperSupplier().get());
        transport.setStdErrorHandler(message -> appendStderr(stderr, message));
        try (McpSyncClient client = McpClient.sync(transport)
                .initializationTimeout(timeout).requestTimeout(timeout).build()) {
            try {
                client.initialize();
            } catch (Exception e) {
                throw new McpClientComponent.McpClientException(
                        "Не удалось запустить Pipeline MCP-сервер: " + safeStderr(stderr.get()));
            }
            try {
                McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                        .name(tool).arguments(arguments).build());
                if (Boolean.TRUE.equals(result.isError())) {
                    return new StepResult(number, tool, false,
                            firstText(result), null, 0);
                }
                if (!(result.structuredContent() instanceof Map<?, ?> map)) {
                    return new StepResult(number, tool, false,
                            tool + " не вернул структурированный результат.", null, 0);
                }
                return new StepResult(number, tool, true, null,
                        new LinkedHashMap<String, Object>((Map<String, Object>) map), 0);
            } catch (Exception e) {
                throw new McpClientComponent.McpClientException(
                        "Ошибка tools/call " + tool + ": " + classify(e));
            }
        }
    }

    private static String text(Object value) {
        return value instanceof String string ? string : null;
    }

    private static String firstText(McpSchema.CallToolResult result) {
        return result.content() == null ? "" : result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private static void appendStderr(AtomicReference<String> stderr, String message) {
        stderr.updateAndGet(previous -> {
            String next = previous + (message == null ? "" : message) + "\n";
            return next.length() > 4096 ? next.substring(0, 4096) : next;
        });
    }

    private static String safeStderr(String stderr) {
        if (stderr == null || stderr.isBlank()) return "";
        String clean = stderr.replaceAll("\\u001B\\[[;\\d]*m", "")
                .replaceAll("\\s+", " ").trim();
        return clean.length() > 240 ? clean.substring(0, 240) + "..." : clean;
    }

    private static String classify(Exception e) {
        Throwable current = e;
        while (current != null) {
            String message = (current.getMessage() == null ? "" : current.getMessage())
                    .toLowerCase(java.util.Locale.ROOT);
            if (current instanceof java.util.concurrent.TimeoutException
                    || message.contains("timeout") || message.contains("timed out")) {
                return "истекло время ожидания tools/call (10 с).";
            }
            current = current.getCause();
        }
        return "сервер вернул ошибку.";
    }

    private static String safe(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? "Неизвестная ошибка шага." : e.getMessage();
    }

    /** Путь: ~ и ~/... разворачиваются в дом пользователя, относительный — от cwd. */
    public static Path expandPath(String raw) {
        String value = raw.trim();
        if (value.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            value = Path.of(System.getProperty("user.home"), value.substring(2)).toString();
        }
        return Path.of(value).normalize().toAbsolutePath();
    }

    /**
     * Снимок собственных потомков на время цепочки: дочерний PipelineMcpServer
     * узнаётся по маркеру --results-dir с этим каталогом и гарантированно
     * завершается (destroy, при необходимости destroyForcibly) в finally —
     * независимо от того, завершилась ли цепочка успехом или ошибкой.
     */
    private static final class Snapshot {
        private final String childMarker;
        private final Set<Long> seenBefore = new java.util.HashSet<>();

        Snapshot(Path resultsDir) {
            this.childMarker = PipelineMcpServer.RESULTS_ARGUMENT + " " + resultsDir;
            for (ProcessHandle process : ProcessHandle.current().descendants().toList()) {
                seenBefore.add(process.pid());
            }
        }

        void reapChildren() {
            for (ProcessHandle process : ProcessHandle.current().descendants().toList()) {
                boolean ourChild = process.info().commandLine()
                        .map(line -> line.contains(childMarker)).orElse(false);
                if (!ourChild || seenBefore.contains(process.pid())) {
                    continue;
                }
                process.destroy();
                try {
                    long remainingNanos = TimeUnit.SECONDS.toNanos(5);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (process.isAlive() && System.nanoTime() < deadline) {
                        Thread.sleep(100);
                    }
                    if (process.isAlive()) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }
    }
}
