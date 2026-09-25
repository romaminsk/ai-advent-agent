package com.example;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Цикл строгого JSON-протокола выбора MCP-инструментов. */
public final class McpOrchestrator {
    public interface Model { String complete(String system, String prompt); }
    public record Outcome(String answer, boolean stopped, String stopReason, List<ToolRouter.Step> steps,
                          String diagnostic) {
        public Outcome(String answer, boolean stopped, String stopReason, List<ToolRouter.Step> steps) {
            this(answer, stopped, stopReason, steps, null);
        }
    }
    private static final int MAX_STEPS = 8;
    /** Общий лимит флоу: 300 с. */
    private static final long FLOW_DEADLINE_NANOS = 300_000_000_000L;
    /** Таймаут одного запроса к модели по умолчанию: 60 с. */
    private static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 60_000;
    private static final String FLOW_LIMIT_TEXT = "Остановлено: общий лимит флоу (300с)";
    private final McpRegistry registry;
    private final ToolRouter router;
    private final Model model;
    private final long requestTimeoutMillis;
    private final ObjectMapper mapper = new ObjectMapper();
    private int modelRequestChars;
    private long flowStartNanos;
    private String requestText = "";
    private final java.util.concurrent.ExecutorService modelExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "mcp-orchestration-model");
        thread.setDaemon(true);
        return thread;
    });
    private static final String TRUNCATION = " [обрезано, полные данные доступны через inputRef step:%d]";

    public McpOrchestrator(McpRegistry registry, Model model) {
        this(registry, model, DEFAULT_REQUEST_TIMEOUT_MILLIS);
    }

    /** Тестовый конструктор: переопределяет таймаут одного запроса к модели. */
    McpOrchestrator(McpRegistry registry, Model model, long requestTimeoutMillis) {
        this.registry = registry; this.router = new ToolRouter(registry); this.model = model;
        this.requestTimeoutMillis = requestTimeoutMillis;
    }
    public ToolRouter router() { return router; }

    public Outcome run(String request) {
        String system = systemPrompt();
        String prompt = request;
        requestText = request;
        router.setRequestText(request);
        flowStartNanos = System.nanoTime();
        long deadline = flowStartNanos + FLOW_DEADLINE_NANOS;
        int invalidResponses = 0;
        for (int i = 0; i < MAX_STEPS; i++) {
            if (System.nanoTime() >= deadline) return stopped(FLOW_LIMIT_TEXT);
            String raw;
            long remainingMillis = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
            long attemptTimeoutMillis = Math.min(requestTimeoutMillis, remainingMillis);
            try {
                raw = completeWithRetry(system, prompt, attemptTimeoutMillis, deadline);
            } catch (TimeoutException e) {
                return stopped(requestTimeoutText(attemptTimeoutMillis));
            } catch (FlowLimitException e) {
                return stopped(FLOW_LIMIT_TEXT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return stopped("Остановлено: запрос прерван.");
            } catch (ApiFailure e) {
                if ("таймаут запроса".equals(e.reason())) {
                    return stopped(requestTimeoutText(attemptTimeoutMillis), e.diagnostic(modelRequestChars));
                }
                return stopped("Остановлено: ошибка API модели: " + e.reason(), e.diagnostic(modelRequestChars));
            }
            Map<String, Object> command;
            try {
                if (raw == null || raw.isBlank()) throw new IllegalArgumentException("пустой ответ");
                command = mapper.readValue(raw.trim(), new TypeReference<>() {});
                boolean finalShape = command.size() == 1 && command.containsKey("final");
                boolean toolShape = command.size() == 2 && command.containsKey("tool") && command.containsKey("args");
                if (!finalShape && !toolShape) throw new IllegalArgumentException("нужны final или tool+args");
            } catch (Exception e) {
                invalidResponses++;
                router.call("invalid.invalid", Map.of());
                if (invalidResponses >= 3) {
                    return stopped("Остановлено: модель вернула невалидный ответ 3 раза подряд");
                }
                prompt = "Ошибка шага: невалидный JSON (" + safe(e.getMessage()) + "). Верни строгий JSON.";
                continue;
            }
            invalidResponses = 0;
            if (command.containsKey("final")) {
                if (!requiredFlowComplete()) {
                    router.call("invalid.invalid", Map.of());
                    prompt = "Нельзя отвечать final: сначала выполни git.get-repository-status,"
                            + " pipeline.search, pipeline.summarize и pipeline.saveToFile через inputRef.";
                    continue;
                }
                return new Outcome(String.valueOf(command.get("final")), false, null, router.steps());
            }
            Object toolValue = command.get("tool");
            if (!(toolValue instanceof String qualified) || !(command.get("args") instanceof Map<?, ?> rawArgs)) {
                router.call("invalid.invalid", Map.of());
                prompt = "Ошибка шага: ожидается {\"tool\":\"server.name\",\"args\":{}}.";
                continue;
            }
            @SuppressWarnings("unchecked") Map<String, Object> args = (Map<String, Object>) rawArgs;
            ToolRouter.Result result = router.call(qualified, args);
            if (result.ok() && "pipeline".equals(result.step().server())
                    && "saveToFile".equals(result.step().tool())) {
                return new Outcome(savedAnswer(result.data()), false, null, router.steps());
            }
            prompt = result.ok() ? successFeedback(result) : errorFeedback(result);
        }
        return stopped("Остановлено: превышен лимит шагов (8)");
    }

    private Outcome stopped(String reason) { return new Outcome(reason + durationSuffix(), true, reason, router.steps()); }
    private Outcome stopped(String reason, String diagnostic) {
        return new Outcome(reason + durationSuffix(), true, reason, router.steps(), diagnostic);
    }
    /** Общая длительность флоу в секундах — добавляется к тексту остановки. */
    private String durationSuffix() {
        return " Общая длительность флоу: " + ((System.nanoTime() - flowStartNanos) / 1_000_000_000L) + " с.";
    }
    private String requestTimeoutText(long attemptTimeoutMillis) {
        return "Остановлено: таймаут запроса к модели (шаг " + (router.steps().size() + 1) + ", "
                + formatTimeout(attemptTimeoutMillis) + ").";
    }
    private static String formatTimeout(long millis) {
        return millis % 1000 == 0 ? (millis / 1000) + "с" : millis + "мс";
    }
    private boolean requiredFlowComplete() {
        boolean git = false, search = false, summarize = false, save = false;
        for (ToolRouter.Step step : router.steps()) {
            if ("git".equals(step.server()) && "get-repository-status".equals(step.tool())) git = true;
            if (step.ok() && "pipeline".equals(step.server()) && "search".equals(step.tool())) search = true;
            if (step.ok() && "pipeline".equals(step.server()) && "summarize".equals(step.tool())) summarize = true;
            if (step.ok() && "pipeline".equals(step.server()) && "saveToFile".equals(step.tool())) save = true;
        }
        return git && search && summarize && save;
    }
    private String successFeedback(ToolRouter.Result result) {
        String feedback = "Шаг " + result.step().number() + " выполнен. Результат сохранён как step:"
                + result.step().number() + "; используй inputRef для следующего шага.";
        try {
            String json = mapper.writeValueAsString(result.data());
            if (json.length() > 4000) {
                json = json.substring(0, Math.max(0, 4000 - TRUNCATION.length() - 8))
                        + String.format(TRUNCATION, result.step().number());
            }
            String next = nextInstruction(result.step());
            return feedback + " Результат инструмента: " + json + " " + next;
        } catch (Exception e) {
            return feedback + " " + nextInstruction(result.step());
        }
    }

    private String errorFeedback(ToolRouter.Result result) {
        String message = "Ошибка шага " + result.step().number() + ": " + safe(result.message()) + ".";
        if ("git".equals(result.step().server())) {
            return message + " Git-ошибка не останавливает flow. Следующий вызов строго: "
                    + "{\"tool\":\"pipeline.search\",\"args\":{\"root\":\""
                    + registry.repoRoot() + "\",\"query\":\"" + queryHint() + "\"}}";
        }
        if ("search".equals(result.step().tool())) {
            return message + " Следующий вызов строго: {\"tool\":\"pipeline.search\",\"args\":{\"root\":\""
                    + registry.repoRoot() + "\",\"query\":\"" + queryHint() + "\"}}";
        }
        if ("summarize".equals(result.step().tool())) {
            String searchRef = latestSuccessful("search");
            return message + " Следующий вызов строго: {\"tool\":\"pipeline.summarize\",\"args\":{\"inputRef\":\""
                    + searchRef + "\"}}";
        }
        return message + " Исправь вызов, используя точное имя инструмента и inputRef.";
    }

    /** Итог собирается из журнала: путь, размер и SHA-256 файла результата. */
    private static String savedAnswer(Map<String, Object> data) {
        if (data == null) return "Файл сохранён.";
        Object path = data.get("path");
        Object bytes = data.get("bytes");
        Object sha = data.get("fileSha256");
        StringBuilder answer = new StringBuilder("Файл сохранён");
        if (path != null) answer.append(": ").append(path);
        if (bytes != null) answer.append(" (").append(bytes).append(" байт");
        if (sha != null) answer.append(bytes == null ? " (SHA-256: " : ", SHA-256: ").append(sha);
        if (bytes != null || sha != null) answer.append(")");
        return answer.append(".").toString();
    }

    private String nextInstruction(ToolRouter.Step step) {
        if ("get-repository-status".equals(step.tool())) {
            return "Следующий вызов строго pipeline.search с root=" + registry.repoRoot() + ".";
        }
        if ("search".equals(step.tool())) {
            return "Следующий вызов строго {\"tool\":\"pipeline.summarize\",\"args\":{\"inputRef\":\"step:"
                    + step.number() + "\"}}.";
        }
        if ("summarize".equals(step.tool())) {
            return "Следующий вызов строго {\"tool\":\"pipeline.saveToFile\",\"args\":{\"inputRef\":\"step:"
                    + step.number() + "\"}}.";
        }
        if ("saveToFile".equals(step.tool())) return "Теперь верни final на русском.";
        return "Продолжай flow.";
    }

    private String latestSuccessful(String tool) {
        String ref = "step:2";
        for (ToolRouter.Step step : router.steps()) {
            if (step.ok() && tool.equals(step.tool())) ref = "step:" + step.number();
        }
        return ref;
    }

    private String queryHint() {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)(?:найди|find).*?(?:все|all)\\s+([\\p{L}\\p{N}_-]+)").matcher(requestText);
        if (matcher.find()) return matcher.group(1);
        matcher = java.util.regex.Pattern.compile("(?i)(?:найди|find)\\s+([\\p{L}\\p{N}_-]+)").matcher(requestText);
        return matcher.find() ? matcher.group(1) : "слово из запроса";
    }
    private String systemPrompt() {
        List<String> names = new ArrayList<>();
        for (McpRegistry.ToolDescriptor tool : registry.tools()) names.add(tool.qualifiedName() + " " + tool.schema());
        return "Ты оркестратор MCP. Каталог: " + String.join("; ", names)
                + ". Для проверки репозитория сначала вызови git.get-repository-status, затем pipeline.search;"
                + " только потом pipeline.summarize и pipeline.saveToFile. Сначала собери данные."
                + " Не отвечай final до завершения нужных инструментальных шагов."
                + " Передавай данные между шагами только через inputRef=step:N, не выдумывай результаты."
                + " query — точное искомое слово из запроса пользователя, без кавычек и лишних слов."
                + " Примеры: {\"tool\":\"git.get-repository-status\",\"args\":{\"repoPath\":\"каталог из запроса\"}};"
                + " {\"tool\":\"pipeline.search\",\"args\":{\"root\":\"каталог из запроса\",\"query\":\"слово из запроса\"}};"
                + " {\"tool\":\"pipeline.summarize\",\"args\":{\"inputRef\":\"step:2\"}};"
                + " {\"tool\":\"pipeline.saveToFile\",\"args\":{\"inputRef\":\"step:3\"}}."
                + " Отвечай строго одним JSON: {\"tool\":\"server.tool\",\"args\":{...}} или {\"final\":\"ответ\"}. Финальный ответ на русском.";
    }
    private static String safe(String value) { return value == null ? "ошибка" : value.replaceAll("[\\r\\n]+", " "); }

    private String completeWithRetry(String system, String prompt, long attemptTimeoutMillis, long deadlineNanos)
            throws TimeoutException, InterruptedException, FlowLimitException, ApiFailure {
        Throwable failure = null;
        for (int attempt = 0; attempt < 1 + RETRY_PAUSES_MILLIS.length; attempt++) {
            if (attempt > 0) {
                long pause = RETRY_PAUSES_MILLIS[attempt - 1];
                // Повтор не выполняется, если не остаётся общего бюджета флоу.
                if (System.nanoTime() + (pause + 1000L) * 1_000_000 >= deadlineNanos) {
                    throw new FlowLimitException();
                }
                Thread.sleep(pause);
            }
            try {
                String modelPrompt = prompt;
                modelRequestChars = system.length() + modelPrompt.length();
                Future<String> call = modelExecutor.submit(() -> model.complete(system, modelPrompt));
                return call.get(attemptTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                failure = e.getCause() == null ? e : e.getCause();
                if (attempt < RETRY_PAUSES_MILLIS.length && transientApiFailure(failure)) continue;
                throw new ApiFailure(apiReason(failure), failure, attempt + 1);
            }
        }
        throw new ApiFailure(apiReason(failure), failure, RETRY_PAUSES_MILLIS.length + 1);
    }

    /** Паузы между повторами при временных сбоях API: 2 с и 5 с. */
    private static final long[] RETRY_PAUSES_MILLIS = {2000, 5000};

    /** Исчерпан общий лимит флоу — повтор бессмыслен. */
    private static final class FlowLimitException extends Exception {}

    private static boolean transientApiFailure(Throwable error) {
        String text = error == null ? "" : String.valueOf(error.getMessage()).toLowerCase();
        // Таймаут запроса не повторяется; повторяются 429, 5xx, обрыв сети и пустой ответ.
        return text.contains("http-статус 429") || text.matches(".*http-статус 5\\d\\d.*")
                || text.contains("сетевая ошибка") || text.contains("пустой итоговый ответ");
    }

    private static String apiReason(Throwable error) {
        String text = error == null ? "" : String.valueOf(error.getMessage());
        java.util.regex.Matcher code = java.util.regex.Pattern.compile("HTTP-статус \\d+").matcher(text);
        if (code.find()) return code.group();
        if (text.toLowerCase().contains("сетевая ошибка")) return "сетевая ошибка";
        String lower = text.toLowerCase();
        if (lower.contains("таймаут") || lower.contains("timed out")) return "таймаут запроса";
        return error == null ? "неизвестный тип" : error.getClass().getSimpleName();
    }

    private static final class ApiFailure extends Exception {
        private final String reason;
        private final Throwable cause;
        private final int attempts;
        private ApiFailure(String reason, Throwable cause, int attempts) {
            this.reason = reason; this.cause = cause; this.attempts = attempts;
        }
        private String reason() { return reason; }
        private String diagnostic(int requestChars) {
            List<String> causes = new ArrayList<>();
            Throwable current = cause;
            while (current != null) {
                causes.add(current.getClass().getSimpleName());
                current = current.getCause();
            }
            return "класс=" + (cause == null ? "unknown" : cause.getClass().getSimpleName())
                    + "; статус=" + reason
                    + "; cause=" + String.join("->", causes)
                    + "; повтор=" + (attempts - 1) + "; requestChars=" + requestChars;
        }
    }
}
