package com.example.support;

import com.example.AgentException;
import com.example.Config;
import com.example.index.Embedder;
import com.example.index.OpenAiEmbedder;
import com.example.rag.RagRetriever;
import com.example.rag.RagService;
import com.example.review.ReviewLlmConfig;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Команда «ai-agent support»: MCP (тикет и клиент) → RAG по support-docs → LLM. Только чтение. */
public final class SupportCommand {

    static final long LLM_TIMEOUT_SECONDS = SupportLlm.BUDGET_SECONDS;
    static final String THRESHOLD_VARIABLE = "SUPPORT_IDK_THRESHOLD";
    static final String USAGE = "Использование: ai-agent support --ticket T-1001 \"вопрос\" | "
            + "--customer C-1 \"вопрос\" | \"вопрос\" [--reindex]";
    public static final String CRM_UNAVAILABLE = "⚠ Данные CRM недоступны";

    /** Зависимости команды: в бою — из окружения, в тестах — заглушки. */
    public record Options(String mcpCommand, Embedder embedder, String embeddingModel,
                          RagService.LlmClient llm, Path docsDir, Path indexDir, double threshold) {

        /** Для server: вызывается на каждый запрос, чтобы бюджет LLM отсчитывался заново. */
        public static Options fromEnvironment(PrintStream log) {
            Map<String, String> env = System.getenv();
            String baseUrl = env.get("EMBEDDING_BASE_URL");
            String model = env.get("EMBEDDING_MODEL");
            if (baseUrl == null || model == null || baseUrl.isBlank() || model.isBlank()) {
                throw new IllegalStateException("не заданы EMBEDDING_BASE_URL и EMBEDDING_MODEL (добавьте их в .env).");
            }
            Config.requireLoopbackUrl(baseUrl, "EMBEDDING_BASE_URL");
            return new Options(CrmMcpServer.commandLine("", System.getProperty("java.class.path")),
                    new OpenAiEmbedder(baseUrl, model), model,
                    SupportLlm.fromEnvironment(env, log), SupportPaths.supportDocsDir(), SupportPaths.supportIndexDir(),
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

    private record Args(String ticket, String customer, boolean reindex, String question) {
    }

    private SupportCommand() {
    }

    public static int main(String[] args, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = Options.fromEnvironment(err);
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
        try {
            SupportPipeline.Result result = SupportPipeline.ask(options, parsed.question(), parsed.ticket(),
                    parsed.customer(), parsed.reindex(), err);
            SupportService.Answer answer = result.answer();
            if (result.crmUnavailable()) {
                out.println(CRM_UNAVAILABLE);
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
            if (result.llmMode() != null) {
                err.println(result.llmMode().startsWith(ReviewLlmConfig.MODE_LOCAL)
                        ? SupportLlm.LOCAL_NOTE : "LLM: ответ от " + result.llmMode());
            }
            return answer.decision() == SupportService.Decision.LLM_FAILED ? 1 : 0;
        } catch (SupportPipeline.NotFoundException e) {
            err.println(e.getMessage());
            return 1;
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
}
