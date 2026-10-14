package com.example.support;

import com.example.AgentException;
import com.example.Config;
import com.example.JsonSupport;
import com.example.McpClientComponent;
import com.example.index.DocumentLoader;
import com.example.index.Embedder;
import com.example.index.IndexService;
import com.example.index.IndexStore;
import com.example.index.OpenAiEmbedder;
import com.example.rag.RagPromptBuilder;
import com.example.rag.RagRetriever;
import com.example.rag.RagService;
import com.example.rag.RagSettings;
import com.example.rag.RagSettingsStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Команда «ai-agent support»: MCP (тикет и клиент) → RAG по support-docs → LLM. Только чтение. */
public final class SupportCommand {

    static final String STRATEGY = IndexService.STRATEGY_STRUCTURE;
    static final int TOP_K = 5;
    static final long LLM_TIMEOUT_SECONDS = 600;
    static final String THRESHOLD_VARIABLE = "SUPPORT_IDK_THRESHOLD";
    static final String USAGE = "Использование: ai-agent support --ticket T-1001 \"вопрос\" | "
            + "--customer C-1 \"вопрос\" | \"вопрос\" [--reindex]";
    public static final String CRM_UNAVAILABLE = "⚠ Данные CRM недоступны";

    /** Зависимости команды: в бою — из окружения, в тестах — заглушки. */
    public record Options(String mcpCommand, Embedder embedder, String embeddingModel,
                          RagService.LlmClient llm, Path docsDir, Path indexDir, double threshold) {

        public static Options fromEnvironment() {
            Map<String, String> env = System.getenv();
            String baseUrl = env.get("EMBEDDING_BASE_URL");
            String model = env.get("EMBEDDING_MODEL");
            if (baseUrl == null || model == null || baseUrl.isBlank() || model.isBlank()) {
                throw new IllegalStateException("не заданы EMBEDDING_BASE_URL и EMBEDDING_MODEL (добавьте их в .env).");
            }
            Config.requireLoopbackUrl(baseUrl, "EMBEDDING_BASE_URL");
            return new Options(CrmMcpServer.commandLine("", System.getProperty("java.class.path")),
                    new OpenAiEmbedder(baseUrl, model), model,
                    SupportLlm.fromEnvironment(env), SupportPaths.supportDocsDir(), SupportPaths.supportIndexDir(),
                    parseThreshold(env.get(THRESHOLD_VARIABLE)));
        }

        public static double parseThreshold(String configured) {
            if (configured == null || configured.isBlank()) {
                return SupportService.DEFAULT_IDK_THRESHOLD;
            }
            try {
                double value = Double.parseDouble(configured.strip());
                if (Double.isFinite(value) && value >= 0 && value <= 1) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // неверное значение обрабатывается ниже тем же сообщением
            }
            throw new IllegalStateException(THRESHOLD_VARIABLE + " должен быть числом в диапазоне 0..1.");
        }
    }

    private enum Lookup { OK, NOT_FOUND, UNAVAILABLE }

    private record Found(Lookup status, JsonNode data, String message) {
    }

    private record Args(String ticket, String customer, boolean reindex, String question) {
    }

    private SupportCommand() {
    }

    public static int main(String[] args, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = Options.fromEnvironment();
        } catch (IllegalStateException | AgentException e) {
            err.println("Ошибка support: " + e.getMessage());
            return 1;
        }
        return run(args, out, err, options);
    }

    public static int run(String[] args, PrintStream out, PrintStream err, Options options) {
        Args parsed;
        try {
            parsed = parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return 2;
        }
        if (parsed.question().isBlank()) {
            err.println(USAGE);
            return 2;
        }
        McpClientComponent mcp = new McpClientComponent();
        boolean crmDown = false;
        JsonNode ticket = null;
        JsonNode customer = null;
        if (parsed.ticket() != null) {
            Found found = lookup(mcp, options.mcpCommand(), CrmMcpServer.GET_TICKET, "ticketId", parsed.ticket());
            if (found.status() == Lookup.NOT_FOUND) {
                err.println("Ошибка: тикет " + parsed.ticket() + " не найден в CRM. Ответ не сформирован.");
                return 1;
            }
            if (found.status() == Lookup.UNAVAILABLE) {
                crmDown = true;
                err.println("CRM: " + found.message());
            } else {
                ticket = found.data();
            }
        }
        String customerId = parsed.customer() != null ? parsed.customer()
                : ticket == null ? null : ticket.path("customerId").asText(null);
        if (customerId != null) {
            Found found = lookup(mcp, options.mcpCommand(), CrmMcpServer.GET_CUSTOMER, "customerId", customerId);
            if (found.status() == Lookup.NOT_FOUND && parsed.customer() != null) {
                err.println("Ошибка: клиент " + customerId + " не найден в CRM. Ответ не сформирован.");
                return 1;
            }
            if (found.status() == Lookup.UNAVAILABLE) {
                crmDown = true;
                err.println("CRM: " + found.message());
            } else if (found.status() == Lookup.OK) {
                customer = found.data();
            }
        }
        if (crmDown) {
            out.println(CRM_UNAVAILABLE);
        }
        try {
            IndexStore store = new IndexStore(options.indexDir());
            ensureIndex(options, store, parsed.reindex(), err);
            RagService service = service(options, store);
            SupportService.Answer answer = SupportService.answer(
                    new SupportService.Input(parsed.question(), ticket, customer), service);
            if (options.llm() instanceof SupportLlm llm && answer.decision() != SupportService.Decision.NO_DOCS) {
                err.println("LLM: ответ от " + llm.lastMode());
            }
            out.println(String.format(Locale.ROOT,
                    "Поиск: top-1 score %.4f, порог supportIdkThreshold %.4f — %s",
                    answer.top1(), answer.threshold(),
                    answer.decision() == SupportService.Decision.NO_DOCS
                            ? "ниже порога, ответ без вызова LLM" : "ответ по документации"));
            out.println();
            out.println(answer.text());
            if (!answer.sources().isEmpty()) {
                out.println();
                out.println("Источники:");
                for (RagRetriever.Chunk chunk : answer.sources()) {
                    out.println("  - " + chunk.source() + "#" + chunk.section());
                }
            }
            return answer.decision() == SupportService.Decision.LLM_FAILED ? 1 : 0;
        } catch (Exception e) {
            err.println("Ошибка support: " + e.getMessage());
            return 1;
        }
    }

    static Args parse(String[] args) {
        String ticket = null;
        String customer = null;
        boolean reindex = false;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--ticket") || arg.equals("--customer")) {
                if (i + 1 >= args.length || args[i + 1].isBlank()) {
                    throw new IllegalArgumentException("Ошибка: после " + arg + " нужен идентификатор.");
                }
                String value = args[++i].strip();
                if (arg.equals("--ticket")) ticket = value; else customer = value;
            } else if (arg.startsWith("--ticket=") || arg.startsWith("--customer=")) {
                boolean isTicket = arg.startsWith("--ticket=");
                String value = arg.substring(arg.indexOf('=') + 1).strip();
                if (value.isEmpty()) {
                    throw new IllegalArgumentException("Ошибка: пустой идентификатор в " + arg + ".");
                }
                if (isTicket) ticket = value; else customer = value;
            } else if (arg.equals("--reindex")) {
                reindex = true;
            } else {
                rest.add(arg);
            }
        }
        if (ticket != null && customer != null) {
            throw new IllegalArgumentException("Ошибка: укажите --ticket или --customer, не оба сразу.");
        }
        return new Args(ticket, customer, reindex, String.join(" ", rest).strip());
    }

    private static Found lookup(McpClientComponent mcp, String command, String tool, String argument, String id) {
        try {
            McpClientComponent.ToolCallResult result = mcp.callTool(command, tool, Map.of(argument, id));
            String text = result.text() == null ? "" : result.text();
            if (result.error()) {
                if (text.startsWith(CrmMcpServer.NOT_FOUND)) {
                    return new Found(Lookup.NOT_FOUND, null, text);
                }
                return new Found(Lookup.UNAVAILABLE, null, text);
            }
            return new Found(Lookup.OK, JsonSupport.MAPPER.readTree(text), null);
        } catch (McpClientComponent.McpClientException e) {
            return new Found(Lookup.UNAVAILABLE, null, e.getMessage());
        } catch (IOException e) {
            return new Found(Lookup.UNAVAILABLE, null, "MCP вернул некорректный ответ");
        }
    }

    private static void ensureIndex(Options options, IndexStore store, boolean reindex, PrintStream err)
            throws IOException, InterruptedException {
        if (!reindex && store.exists(STRATEGY)) {
            return;
        }
        if (!Files.isDirectory(options.docsDir())) {
            throw new IllegalStateException("корпус support не найден: " + options.docsDir());
        }
        err.println("Сборка индекса support: " + options.docsDir());
        IndexService.BuildReport report = new IndexService(store).build(STRATEGY, options.docsDir().toString(),
                new DocumentLoader(), options.embedder(), options.embeddingModel(), line -> err.println("  " + line));
        err.println("Индекс support: " + report.chunks() + " чанков из " + report.files() + " файлов, "
                + store.file(STRATEGY));
    }

    private static RagService service(Options options, IndexStore store) throws IOException {
        RagSettings settings = RagSettingsStore.defaultStore().load().settings()
                .withRewrite(false)
                .withMinScore(options.threshold())
                .withIdkThreshold(options.threshold());
        return new RagService(new RagRetriever(store, options.embedder(), STRATEGY, TOP_K),
                new RagPromptBuilder(), SupportService.SYSTEM_PROMPT, options.llm(), settings, null,
                LLM_TIMEOUT_SECONDS);
    }
}
