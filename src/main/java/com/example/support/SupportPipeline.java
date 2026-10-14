package com.example.support;

import com.example.JsonSupport;
import com.example.McpClientComponent;
import com.example.index.DocumentLoader;
import com.example.index.IndexService;
import com.example.index.IndexStore;
import com.example.rag.RagPromptBuilder;
import com.example.rag.RagRetriever;
import com.example.rag.RagService;
import com.example.rag.RagSettings;
import com.example.rag.RagSettingsStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.Map;

/** Общий поток команды support и сервера: MCP (тикет и клиент) → RAG по support-docs → LLM. */
public final class SupportPipeline {

    static final String STRATEGY = IndexService.STRATEGY_STRUCTURE;
    static final int TOP_K = 5;

    /** Тикет или клиент, явно указанный в запросе, не найден в CRM. Ответ не формируется. */
    public static final class NotFoundException extends Exception {
        NotFoundException(String message) {
            super(message);
        }
    }

    /** Результат: ответ, тикет (если найден), доступность CRM и режим LLM («облако:…», «локально:…» или null). */
    public record Result(SupportService.Answer answer, JsonNode ticket, boolean crmUnavailable, String llmMode) {
    }

    private enum Lookup { OK, NOT_FOUND, UNAVAILABLE }

    private record Found(Lookup status, JsonNode data, String message) {
    }

    private SupportPipeline() {
    }

    /**
     * ticketId или customerId — необязательны; без них ответ строится только по RAG.
     * Диагностика (состояние CRM, сборка индекса) пишется в diagnostics.
     */
    public static Result ask(SupportCommand.Options options, String question, String ticketId,
                             String customerId, boolean reindex, PrintStream diagnostics)
            throws Exception {
        McpClientComponent mcp = new McpClientComponent();
        boolean crmDown = false;
        JsonNode ticket = null;
        JsonNode customer = null;
        if (ticketId != null) {
            Found found = lookup(mcp, options.mcpCommand(), CrmMcpServer.GET_TICKET, "ticketId", ticketId);
            if (found.status() == Lookup.NOT_FOUND) {
                throw new NotFoundException("Ошибка: тикет " + ticketId + " не найден в CRM. Ответ не сформирован.");
            }
            if (found.status() == Lookup.UNAVAILABLE) {
                crmDown = true;
                diagnostics.println("CRM: " + found.message());
            } else {
                ticket = found.data();
            }
        }
        String effectiveCustomer = customerId != null ? customerId
                : ticket == null ? null : ticket.path("customerId").asText(null);
        if (effectiveCustomer != null) {
            Found found = lookup(mcp, options.mcpCommand(), CrmMcpServer.GET_CUSTOMER, "customerId", effectiveCustomer);
            if (found.status() == Lookup.NOT_FOUND && customerId != null) {
                throw new NotFoundException("Ошибка: клиент " + customerId + " не найден в CRM. Ответ не сформирован.");
            }
            if (found.status() == Lookup.UNAVAILABLE) {
                crmDown = true;
                diagnostics.println("CRM: " + found.message());
            } else if (found.status() == Lookup.OK) {
                customer = found.data();
            }
        }
        IndexStore store = new IndexStore(options.indexDir());
        ensureIndex(options, store, reindex, diagnostics);
        SupportService.Answer answer = SupportService.answer(
                new SupportService.Input(question, ticket, customer), service(options, store));
        String mode = options.llm() instanceof SupportLlm llm && answer.decision() == SupportService.Decision.ANSWERED
                ? llm.lastMode() : null;
        return new Result(answer, ticket, crmDown, mode);
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

    private static void ensureIndex(SupportCommand.Options options, IndexStore store, boolean reindex,
                                    PrintStream diagnostics) throws IOException, InterruptedException {
        if (!reindex && store.exists(STRATEGY)) {
            return;
        }
        if (!Files.isDirectory(options.docsDir())) {
            throw new IllegalStateException("корпус support не найден: " + options.docsDir());
        }
        diagnostics.println("Сборка индекса support: " + options.docsDir());
        IndexService.BuildReport report = new IndexService(store).build(STRATEGY, options.docsDir().toString(),
                new DocumentLoader(), options.embedder(), options.embeddingModel(),
                line -> diagnostics.println("  " + line));
        diagnostics.println("Индекс support: " + report.chunks() + " чанков из " + report.files() + " файлов, "
                + store.file(STRATEGY));
    }

    private static RagService service(SupportCommand.Options options, IndexStore store) throws IOException {
        RagSettings settings = RagSettingsStore.defaultStore().load().settings()
                .withRewrite(false)
                .withMinScore(options.threshold())
                .withIdkThreshold(options.threshold());
        return new RagService(new RagRetriever(store, options.embedder(), STRATEGY, TOP_K),
                new RagPromptBuilder(), SupportService.SYSTEM_PROMPT, options.llm(), settings, null,
                SupportCommand.LLM_TIMEOUT_SECONDS);
    }
}
