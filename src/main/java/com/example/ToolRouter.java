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
    private String requestText = "";

    public ToolRouter(McpRegistry registry) { this.registry = registry; }
    public List<Step> steps() { return List.copyOf(steps); }
    public Map<String, Object> data(String ref) { return results.get(ref); }
    public void setRequestText(String requestText) { this.requestText = requestText == null ? "" : requestText; }

    /** Шаг журнала для нераспознанного ответа модели: это не вызов инструмента. */
    public Step recordUnrecognized(String raw) {
        long start = System.nanoTime();
        String snippet = safeSnippet(raw);
        Step step = new Step(steps.size() + 1, "модель", "ответ не распознан", Map.of(), null, false,
                (System.nanoTime() - start) / 1_000_000, snippet);
        steps.add(step);
        return step;
    }

    /** Первые 200 символов ответа в одну строку; похожие на ключи последовательности скрыты. */
    private static String safeSnippet(String raw) {
        String text = raw == null ? "" : String.valueOf(raw).replaceAll("[\\r\\n\\t]+", " ").trim();
        if (text.length() > 200) text = text.substring(0, 200);
        return text.replaceAll("(?i)(sk-[A-Za-z0-9_\\-]{8,}|Bearer\\s+\\S+|[A-Fa-f0-9]{32,})", "[скрыто]");
    }

    public Result call(String qualifiedName, Map<String, Object> supplied) {
        long start = System.nanoTime();
        int number = steps.size() + 1;
        String[] name = qualifiedName == null ? new String[0] : qualifiedName.split("\\.", 2);
        String server = name.length == 2 ? name[0] : "?";
        String tool = name.length == 2 ? name[1] : String.valueOf(qualifiedName);
        Map<String, Object> args = supplied == null ? Map.of() : new LinkedHashMap<>(supplied);
        if ("git".equals(server) && "get-repository-status".equals(tool)
                && !args.containsKey("repoPath") && registry.repoRoot() != null) {
            args.put("repoPath", registry.repoRoot());
        }
        if ("pipeline".equals(server) && "search".equals(tool) && args.get("root") instanceof String root) {
            String normalizedRoot = expandUser(root.trim().replaceAll("[.,;:!?\\)\\]]+$", ""));
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
                if ("git".equals(server) && "get-repository-status".equals(tool)
                        && registry.repoRoot() != null && !callArgs.containsKey("repoPath")) {
                    callArgs.put("repoPath", registry.repoRoot());
                }
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
        if (!registry.has(server + "." + tool)) {
            String requested = server + "." + tool;
            String available = registry.tools().stream().map(McpRegistry.ToolDescriptor::qualifiedName)
                    .reduce((a, b) -> a + ", " + b).orElse("нет");
            String nearest = registry.tools().stream()
                    .min(java.util.Comparator.comparingInt(t -> distance(requested, t.qualifiedName())))
                    .map(McpRegistry.ToolDescriptor::qualifiedName).orElse("нет");
            return "Инструмент " + requested + " недоступен. Доступны: " + available
                    + ". Ближайшее точное имя: " + nearest;
        }
        if ("pipeline".equals(server) && "search".equals(tool)
                && args.get("root") instanceof String root && !registry.allowsRoot(root)) {
            return "pipeline.search разрешён только внутри проверяемого Git-репозитория;"
                    + " используй root=" + registry.repoRoot();
        }
        if ("pipeline".equals(server) && "search".equals(tool)
                && args.get("query") instanceof String query
                && !requestText.isBlank()
                && !containsQueryWord(requestText, query)) {
            return "query должен быть словом из запроса пользователя";
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

    private static int distance(String left, String right) {
        int[] row = new int[right.length() + 1];
        for (int j = 0; j < row.length; j++) row[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int previous = row[0]; row[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int current = row[j];
                row[j] = Math.min(Math.min(row[j] + 1, row[j - 1] + 1),
                        previous + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
                previous = current;
            }
        }
        return row[right.length()];
    }

    private static String expandUser(String value) {
        if ("~".equals(value)) return System.getProperty("user.home");
        if (value.startsWith("~/")) return System.getProperty("user.home") + value.substring(1);
        return value;
    }

    private static boolean containsQueryWord(String request, String query) {
        String withoutPaths = request.replaceAll("(?:~[/\\\\]|/)[^\\s,;]+", " ");
        return java.util.regex.Pattern.compile("(?i)(?<![\\p{L}\\p{N}_])"
                        + java.util.regex.Pattern.quote(query)
                        + "(?![\\p{L}\\p{N}_])")
                .matcher(withoutPaths).find();
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
