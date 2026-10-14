package com.example.support;

import com.example.JsonSupport;
import com.example.rag.RagRetriever;
import com.example.review.ReviewLlmConfig;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Команда «ai-agent support-chat»: чат с памятью локально или через support-server (POST /chat). */
public final class SupportChatCli {

    static final String TOKEN_VARIABLE = "SUPPORT_API_TOKEN";
    static final String USAGE = "Использование: ai-agent support-chat [--ticket T-1001] [--server URL]";
    static final String HELP = "Команды: /ticket <id> — выбрать тикет, /new — новый диалог, /help — справка, /exit — выход.";

    /** Ход диалога в общем виде: одинаково для локального и серверного режимов. */
    public record Turn(String sessionId, String answer, List<String> sources, String ticketLine,
                       boolean escalated, String llmSource, long durationMs) {
    }

    /** Исполнитель хода: локальный SupportChatService или HTTP-клиент к support-server. */
    @FunctionalInterface
    public interface Backend {
        Turn turn(String sessionId, String message, String ticketId) throws Exception;
    }

    private record Args(String ticket, String server) {
    }

    private SupportChatCli() {
    }

    public static int run(String[] args, BufferedReader in, PrintStream out, PrintStream err) {
        Args parsed;
        try {
            parsed = parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return 2;
        }
        Backend backend;
        if (parsed.server() == null) {
            SupportChatService chat = new SupportChatService(err);
            Supplier<SupportCommand.Options> options = () -> SupportCommand.Options.fromEnvironment(err);
            backend = (sessionId, message, ticket) -> local(chat, options, sessionId, message, ticket);
        } else {
            String token = System.getenv(TOKEN_VARIABLE);
            if (token == null || token.isBlank()) {
                err.println("Ошибка: " + TOKEN_VARIABLE + " не задан в .env. Нужен для --server.");
                return 2;
            }
            String server = parsed.server();
            String secret = token.strip();
            SupportRemoteClient client = new SupportRemoteClient(server, secret);
            backend = (sessionId, message, ticket) -> client.chat(sessionId, message, ticket);
        }
        return loop(parsed.ticket(), backend, in, out, err);
    }

    public static int loop(String initialTicket, Backend backend, BufferedReader in, PrintStream out, PrintStream err) {
        String ticket = initialTicket;
        String sessionId = null;
        out.println("Чат поддержки. Тикет: " + (ticket == null ? "не задан" : ticket) + ". /help — команды.");
        try {
            String line;
            while ((line = in.readLine()) != null) {
                String text = line.strip();
                if (text.isEmpty()) {
                    continue;
                }
                if (text.equals("/exit")) {
                    break;
                }
                if (text.equals("/help")) {
                    out.println(HELP);
                    continue;
                }
                if (text.equals("/new")) {
                    sessionId = null;
                    out.println("Новый диалог.");
                    continue;
                }
                if (text.equals("/ticket") || text.startsWith("/ticket ")) {
                    String value = text.substring("/ticket".length()).strip();
                    if (value.isEmpty()) {
                        out.println("Тикет: " + (ticket == null ? "не задан" : ticket));
                    } else {
                        ticket = value.toUpperCase(Locale.ROOT);
                        out.println("Тикет выбран: " + ticket);
                    }
                    continue;
                }
                if (text.startsWith("/")) {
                    out.println("Неизвестная команда. " + HELP);
                    continue;
                }
                err.println("думаю…");
                try {
                    Turn turn = backend.turn(sessionId, text, ticket);
                    sessionId = turn.sessionId();
                    print(turn, out);
                } catch (Exception e) {
                    err.println("Ошибка: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            err.println("Ошибка ввода: " + e.getMessage());
            return 1;
        }
        return 0;
    }

    static void print(Turn turn, PrintStream out) {
        if (turn.ticketLine() != null) {
            out.println(turn.ticketLine());
        }
        out.println();
        out.println(turn.answer());
        if (!turn.sources().isEmpty()) {
            out.println();
            out.println("Источники: " + String.join("; ", turn.sources()));
        }
        out.println("Эскалация: " + (turn.escalated() ? "да" : "нет"));
        out.println("llmSource: " + (turn.llmSource() == null ? "нет (без LLM)" : turn.llmSource())
                + " · durationMs: " + turn.durationMs());
        out.println();
    }

    private static Turn local(SupportChatService chat, Supplier<SupportCommand.Options> options,
                              String sessionId, String message, String ticket) throws Exception {
        long started = System.nanoTime();
        SupportChatService.ChatResult result = chat.turn(sessionId, message, ticket, options);
        SupportPipeline.Result pipeline = result.result();
        SupportService.Answer answer = pipeline.answer();
        List<String> sources = new ArrayList<>();
        for (RagRetriever.Chunk chunk : answer.sources()) {
            sources.add(chunk.source() + "#" + chunk.section());
        }
        String mode = pipeline.llmMode();
        String source = mode == null ? null
                : mode.startsWith(ReviewLlmConfig.MODE_CLOUD) ? "cloud"
                : mode.startsWith(ReviewLlmConfig.MODE_LOCAL) ? "local" : null;
        return new Turn(result.sessionId(), answer.text(), sources, ticketLine(pipeline.ticket()),
                answer.escalation(), source, (System.nanoTime() - started) / 1_000_000);
    }

    static String ticketLine(JsonNode ticket) {
        if (ticket == null || ticket.isNull() || ticket.isMissingNode()) {
            return null;
        }
        String subject = ticket.path("subject").asText(ticket.path("title").asText(""));
        return String.format(Locale.ROOT, "[тикет %s · «%s» · статус: %s]", ticket.path("id").asText(),
                subject, ticket.path("status").asText());
    }

    private static Args parse(String[] args) {
        String ticket = null;
        String server = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--ticket") || arg.equals("--server")) {
                if (i + 1 >= args.length || args[i + 1].isBlank()) {
                    throw new IllegalArgumentException("Ошибка: после " + arg + " нужно значение.");
                }
                String value = args[++i].strip();
                if (arg.equals("--ticket")) ticket = value.toUpperCase(Locale.ROOT); else server = value;
            } else if (arg.startsWith("--ticket=") || arg.startsWith("--server=")) {
                String value = arg.substring(arg.indexOf('=') + 1).strip();
                if (value.isEmpty()) {
                    throw new IllegalArgumentException("Ошибка: пустое значение в " + arg + ".");
                }
                if (arg.startsWith("--ticket=")) ticket = value.toUpperCase(Locale.ROOT); else server = value;
            } else {
                throw new IllegalArgumentException("Ошибка: неизвестный аргумент " + arg + ".");
            }
        }
        if (server != null && !(server.startsWith("http://") || server.startsWith("https://"))) {
            throw new IllegalArgumentException("Ошибка: --server должен начинаться с http:// или https://.");
        }
        return new Args(ticket, server);
    }
}
