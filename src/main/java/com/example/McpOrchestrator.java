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
    public record Outcome(String answer, boolean stopped, String stopReason, List<ToolRouter.Step> steps) {}
    private static final int MAX_STEPS = 8;
    private final McpRegistry registry;
    private final ToolRouter router;
    private final Model model;
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.concurrent.ExecutorService modelExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "mcp-orchestration-model");
        thread.setDaemon(true);
        return thread;
    });

    public McpOrchestrator(McpRegistry registry, Model model) {
        this.registry = registry; this.router = new ToolRouter(registry); this.model = model;
    }
    public ToolRouter router() { return router; }

    public Outcome run(String request) {
        String system = systemPrompt();
        String prompt = request;
        long deadline = System.nanoTime() + 180_000_000_000L;
        for (int i = 0; i < MAX_STEPS; i++) {
            if (System.nanoTime() >= deadline) return stopped("Остановлено: таймаут");
            String raw;
            long remainingMillis = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
            try {
                String modelPrompt = prompt;
                Future<String> call = modelExecutor.submit(() -> model.complete(system, modelPrompt));
                raw = call.get(remainingMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                return stopped("Остановлено: таймаут");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return stopped("Остановлено: таймаут");
            } catch (ExecutionException e) {
                return stopped("Остановлено: ошибка модели");
            }
            Map<String, Object> command;
            try {
                if (raw == null || raw.isBlank()) throw new IllegalArgumentException("пустой ответ");
                command = mapper.readValue(raw.trim(), new TypeReference<>() {});
                boolean finalShape = command.size() == 1 && command.containsKey("final");
                boolean toolShape = command.size() == 2 && command.containsKey("tool") && command.containsKey("args");
                if (!finalShape && !toolShape) throw new IllegalArgumentException("нужны final или tool+args");
            } catch (Exception e) {
                ToolRouter.Result invalid = router.call("invalid.invalid", Map.of());
                prompt = "Ошибка шага: невалидный JSON (" + safe(e.getMessage()) + "). Верни строгий JSON.";
                continue;
            }
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
            prompt = result.ok() ? successFeedback(result) :
                    "Ошибка шага " + result.step().number() + ": " + safe(result.message()) + ". Исправь вызов и продолжай.";
        }
        return stopped("Остановлено: превышен лимит шагов (8)");
    }

    private Outcome stopped(String reason) { return new Outcome(reason, true, reason, router.steps()); }
    private boolean requiredFlowComplete() {
        boolean git = false, search = false, summarize = false, save = false;
        for (ToolRouter.Step step : router.steps()) if (step.ok()) {
            if ("git".equals(step.server()) && "get-repository-status".equals(step.tool())) git = true;
            if ("pipeline".equals(step.server()) && "search".equals(step.tool())) search = true;
            if ("pipeline".equals(step.server()) && "summarize".equals(step.tool())) summarize = true;
            if ("pipeline".equals(step.server()) && "saveToFile".equals(step.tool())) save = true;
        }
        return git && search && summarize && save;
    }
    private String successFeedback(ToolRouter.Result result) {
        String feedback = "Шаг " + result.step().number() + " выполнен. Результат сохранён как step:"
                + result.step().number() + "; используй inputRef для следующего шага.";
        if ("get-repository-status".equals(result.step().tool()) && result.data() != null) {
            Object root = result.data().get("repositoryRoot");
            if (root != null) feedback += " Точный repositoryRoot для pipeline.search: " + root;
        } else if ("search".equals(result.step().tool()) && result.data() != null) {
            feedback += " Найдено совпадений: " + result.data().get("totalMatches") + ".";
        }
        return feedback;
    }
    private String systemPrompt() {
        List<String> names = new ArrayList<>();
        for (McpRegistry.ToolDescriptor tool : registry.tools()) names.add(tool.qualifiedName() + " " + tool.schema());
        return "Ты оркестратор MCP. Каталог: " + String.join("; ", names)
                + ". Для проверки репозитория сначала вызови git.get-repository-status, затем pipeline.search;"
                + " только потом pipeline.summarize и pipeline.saveToFile. Сначала собери данные."
                + " Не отвечай final до завершения нужных инструментальных шагов."
                + " Передавай данные между шагами только через inputRef=step:N, не выдумывай результаты."
                + " Отвечай строго одним JSON: {\"tool\":\"server.tool\",\"args\":{...}} или {\"final\":\"ответ\"}. Финальный ответ на русском.";
    }
    private static String safe(String value) { return value == null ? "ошибка" : value.replaceAll("[\\r\\n]+", " "); }
}
