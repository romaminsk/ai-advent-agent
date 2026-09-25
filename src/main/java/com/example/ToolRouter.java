package com.example;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Маршрутизация квалифицированных MCP-инструментов и безопасная передача ссылок. */
public final class ToolRouter {
    public record Step(int number, String server, String tool, Map<String, Object> args,
                       String inputRef, boolean ok, long durationMillis, String error) {}
    public record Result(boolean ok, String message, Map<String, Object> data, Step step) {}
    private final McpRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Step> steps = new ArrayList<>();
    private final Map<String, Map<String, Object>> results = new LinkedHashMap<>();
    private final Map<String, String> resultTools = new LinkedHashMap<>();

    public ToolRouter(McpRegistry registry) { this.registry = registry; }
    public List<Step> steps() { return List.copyOf(steps); }
    public Map<String, Object> data(String ref) { return results.get(ref); }

    public Result call(String qualifiedName, Map<String, Object> supplied) {
        long start = System.nanoTime();
        int number = steps.size() + 1;
        String[] name = qualifiedName == null ? new String[0] : qualifiedName.split("\\.", 2);
        String server = name.length == 2 ? name[0] : "?";
        String tool = name.length == 2 ? name[1] : String.valueOf(qualifiedName);
        Map<String, Object> args = supplied == null ? Map.of() : new LinkedHashMap<>(supplied);
        if ("pipeline".equals(server) && "search".equals(tool) && args.get("root") instanceof String root) {
            String normalizedRoot = root.trim().replaceAll("[.,;:!?\\)\\]]+$", "");
            if (!normalizedRoot.equals(root)) args.put("root", normalizedRoot);
        }
        String inputRef = args.get("inputRef") instanceof String ref ? ref : null;
        String error = validate(server, tool, args, inputRef);
        if (error == null) {
            try {
                Map<String, Object> callArgs = new LinkedHashMap<>(args);
                callArgs.remove("inputRef");
                if ("summarize".equals(tool)) callArgs.put("searchResult", results.get(inputRef));
                if ("saveToFile".equals(tool)) callArgs.put("summary", results.get(inputRef));
                McpClientComponent.ToolCallResult response = registry.call(server, tool, callArgs);
                if (response.error()) error = response.text();
                else {
                    Map<String, Object> data = asMap(response.structuredContent(), response.text());
                    if (data == null) error = "Инструмент вернул результат не-объект.";
                    else {
                        results.put("step:" + number, data);
                        resultTools.put("step:" + number, qualifiedName);
                        Step step = step(number, server, tool, args, inputRef, true, start, null);
                        steps.add(step);
                        return new Result(true, "step:" + number + " сохранён; продолжайте через inputRef", data, step);
                    }
                }
            } catch (Exception e) { error = e.getMessage() == null ? "ошибка MCP" : e.getMessage(); }
        }
        Step step = step(number, server, tool, args, inputRef, false, start, error);
        steps.add(step);
        return new Result(false, error, null, step);
    }

    private String validate(String server, String tool, Map<String, Object> args, String inputRef) {
        if (registry.status(server) != null && !registry.status(server).available()) {
            return "Сервер " + server + " недоступен: " + registry.status(server).reason();
        }
        if (!registry.has(server + "." + tool)) return "Неизвестный инструмент: " + server + "." + tool;
        if ("pipeline".equals(server) && "search".equals(tool)
                && args.get("root") instanceof String root && !registry.allowsRoot(root)) {
            return "pipeline.search разрешён только внутри проверяемого Git-репозитория;"
                    + " используй root=" + registry.repoRoot();
        }
        if ("summarize".equals(tool) || "saveToFile".equals(tool)) {
            if (args.containsKey("searchResult") || args.containsKey("summary")) return "используй inputRef вместо сырых данных";
            if (inputRef == null || !inputRef.matches("step:\\d+")) return "нужна ссылка inputRef=step:N";
            if (!results.containsKey(inputRef)) return "Ссылка " + inputRef + " не существует или шаг завершился ошибкой";
            String expected = "summarize".equals(tool) ? "pipeline.search" : "pipeline.summarize";
            if (!expected.equals(resultTools.get(inputRef))) return "Ссылка " + inputRef + " указывает на результат не того инструмента";
        } else if (args.containsKey("inputRef")) return "inputRef допустим только для pipeline summarize/saveToFile";
        return null;
    }

    private Step step(int n, String server, String tool, Map<String, Object> args, String ref,
                      boolean ok, long start, String error) {
        return new Step(n, server, tool, redact(args), ref, ok,
                (System.nanoTime() - start) / 1_000_000, error);
    }
    private static Map<String, Object> redact(Map<String, Object> args) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            String key = e.getKey().toLowerCase();
            safe.put(e.getKey(), key.contains("key") || key.contains("token") || key.contains("secret")
                    || key.equals("searchresult") || key.equals("summary") ? "<скрыто>" : e.getValue());
        }
        return safe;
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object structured, String text) throws Exception {
        if (structured instanceof Map<?, ?> map) return new LinkedHashMap<>((Map<String, Object>) map);
        if (text == null || text.isBlank()) return null;
        return mapper.readValue(text, new TypeReference<>() {});
    }
}
