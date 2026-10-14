package com.example.support;

import com.example.AgentException;
import com.example.JsonSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * MCP stdio-сервер команды «ai-agent mcp-crm»: get_customer, get_ticket, list_customer_tickets.
 * Только чтение из CRM по HTTP; персональные поля клиента (имя, email) не возвращаются.
 */
public final class CrmMcpServer {

    static final String GET_CUSTOMER = "get_customer";
    static final String GET_TICKET = "get_ticket";
    static final String LIST_TICKETS = "list_customer_tickets";
    public static final String NOT_FOUND = "NOT_FOUND: ";
    public static final String UNAVAILABLE = "UNAVAILABLE: ";
    static final String INVALID = "INVALID: ";
    static final String DEFAULT_URL = "http://127.0.0.1:" + CrmHttpServer.DEFAULT_PORT;
    static final String URL_VARIABLE = "CRM_URL";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,40}");
    private static final List<String> CUSTOMER_FIELDS = List.of("id", "plan", "status", "authMethod",
            "failedLoginAttempts", "lastLogin");
    private static final List<String> TICKET_SUMMARY_FIELDS = List.of("id", "subject", "status", "createdAt");

    private CrmMcpServer() {
    }

    /** Командная строка дочернего процесса; prefix — необязательный префикс (например, env для тестов). */
    public static String commandLine(String prefix, String classpath) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return prefix + "\"" + java + "\" -cp \"" + classpath + "\" com.example.Main mcp-crm";
    }

    public static int run() {
        String url = System.getenv(URL_VARIABLE);
        if (url == null || url.isBlank()) {
            url = DEFAULT_URL;
        }
        String token = System.getenv(CrmHttpServer.TOKEN_VARIABLE);
        if (token == null || token.isBlank()) {
            System.err.println("mcp-crm: " + CrmHttpServer.TOKEN_VARIABLE + " не задан в .env проекта.");
            return 2;
        }
        CrmClient client;
        try {
            client = new CrmClient(url, token.strip());
        } catch (AgentException e) {
            System.err.println("mcp-crm: " + e.getMessage());
            return 2;
        }
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                new JacksonMcpJsonMapperSupplier().get());
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("support-crm", "1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(
                        specification(GET_CUSTOMER, "Клиент CRM без имени и email: тариф, статус, способ входа, попытки входа",
                                Map.of("customerId", Map.of("type", "string", "description", "Идентификатор клиента, например C-1")),
                                "Клиент", client, CrmMcpServer::customer),
                        specification(GET_TICKET, "Тикет CRM: тема, описание, статус, история",
                                Map.of("ticketId", Map.of("type", "string", "description", "Идентификатор тикета, например T-1001")),
                                "Тикет", client, CrmMcpServer::ticket),
                        specification(LIST_TICKETS, "Тикеты клиента: идентификатор, тема, статус, дата создания",
                                Map.of("customerId", Map.of("type", "string", "description", "Идентификатор клиента")),
                                "Клиент", client, CrmMcpServer::customerTickets))
                .build();
        try {
            Thread.currentThread().join();
            server.closeGracefully();
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.closeGracefully();
            return 0;
        }
    }

    @FunctionalInterface
    interface ToolAction {
        JsonNode run(CrmClient client, String id) throws CrmClient.CrmException;
    }

    static McpServerFeatures.SyncToolSpecification specification(String name, String description,
                                                                 Map<String, Object> properties, String entity,
                                                                 CrmClient client, ToolAction action) {
        String idField = properties.keySet().iterator().next();
        McpSchema.Tool definition = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(McpSchema.JsonSchema.builder()
                        .type("object")
                        .properties(properties)
                        .required(List.of(idField))
                        .additionalProperties(false)
                        .build())
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(definition)
                .callHandler((exchange, request) -> {
                    Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
                    Object raw = arguments.get(idField);
                    if (!(raw instanceof String id) || !ID.matcher(id.strip()).matches()) {
                        return error(INVALID + "нужен идентификатор " + idField + " (буквы, цифры, дефис).");
                    }
                    try {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(JsonSupport.MAPPER.writeValueAsString(action.run(client, id.strip())))
                                .build();
                    } catch (CrmClient.CrmException e) {
                        return e.kind() == CrmClient.Kind.NOT_FOUND
                                ? error(NOT_FOUND + entity + " " + id.strip() + " не найден в CRM.")
                                : error(UNAVAILABLE + e.getMessage());
                    } catch (JsonProcessingException e) {
                        return error(UNAVAILABLE + "не удалось сформировать ответ.");
                    }
                })
                .build();
    }

    private static McpSchema.CallToolResult error(String text) {
        return McpSchema.CallToolResult.builder().isError(true).addTextContent(text).build();
    }

    private static JsonNode customer(CrmClient client, String id) throws CrmClient.CrmException {
        return pick(client.get("/api/v1/customers/" + id), CUSTOMER_FIELDS);
    }

    private static JsonNode ticket(CrmClient client, String id) throws CrmClient.CrmException {
        return client.get("/api/v1/tickets/" + id);
    }

    private static JsonNode customerTickets(CrmClient client, String id) throws CrmClient.CrmException {
        JsonNode tickets = client.get("/api/v1/customers/" + id + "/tickets");
        var result = JsonSupport.MAPPER.createArrayNode();
        tickets.forEach(ticket -> result.add(pick(ticket, TICKET_SUMMARY_FIELDS)));
        return result;
    }

    private static JsonNode pick(JsonNode source, List<String> fields) {
        ObjectNode result = JsonSupport.MAPPER.createObjectNode();
        for (String field : fields) {
            if (source.has(field)) {
                result.set(field, source.get(field));
            }
        }
        return result;
    }
}
