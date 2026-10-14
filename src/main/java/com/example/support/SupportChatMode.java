package com.example.support;

import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Режим поддержки в основном чате: /support on|off|status|ticket|new и маршрутизация сообщений
 * на support-сервер. Реплики не попадают в историю агента, LLM и RAG основного чата не вызываются.
 */
public final class SupportChatMode implements AutoCloseable {

    public static final String USAGE = "Использование: /support on [T-1001] · /support off · /support status "
            + "· /support ticket <id> · /support new";
    private static final Pattern TICKET = Pattern.compile("(?i)T-\\d{1,6}");

    private final Map<String, String> env;
    private final Consumer<String> system;
    private final Consumer<String> message;
    private SupportConnection connection;
    private String ticket;
    private String sessionId;

    public SupportChatMode(Map<String, String> env, Consumer<String> system, Consumer<String> message) {
        this.env = env;
        this.system = system;
        this.message = message;
    }

    public boolean enabled() {
        return connection != null;
    }

    /** Команда /support ... (сама строка ввода, например «/support on T-1001»). */
    public void command(String raw) {
        String[] parts = raw.strip().split("\\s+");
        String sub = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "on" -> on(parts.length > 2 ? parts[2] : null);
            case "off" -> off();
            case "status" -> system.accept(status());
            case "ticket" -> {
                if (parts.length < 3) {
                    system.accept("Укажите тикет: /support ticket T-1001");
                } else {
                    setTicket(parts[2]);
                }
            }
            case "new" -> {
                sessionId = null;
                system.accept("Новый диалог поддержки. Тикет: " + ticketOrNone() + ".");
            }
            default -> system.accept(USAGE);
        }
    }

    /** true — сообщение обработано режимом поддержки и не должно идти в основной чат. */
    public boolean handleMessage(String text) {
        if (connection == null) {
            return false;
        }
        system.accept("думаю…");
        try {
            SupportChatCli.Turn turn = connection.turn(sessionId, text, ticket);
            sessionId = turn.sessionId();
            message.accept(format(turn));
        } catch (SupportException e) {
            system.accept("⚠ " + e.getMessage());
        }
        return true;
    }

    @Override
    public void close() {
        closeConnection();
    }

    private void on(String requested) {
        if (requested != null) {
            if (!TICKET.matcher(requested).matches()) {
                system.accept("Неверный номер тикета «" + requested + "». Пример: T-1001.");
                return;
            }
            ticket = requested.toUpperCase(Locale.ROOT);
        }
        if (connection != null && connection.alive()) {
            system.accept("Поддержка уже включена · тикет: " + ticketOrNone() + ".");
            return;
        }
        try {
            connection = SupportConnection.open(env);
            sessionId = null;
            system.accept("Поддержка включена · " + connection.description() + " · тикет: " + ticketOrNone() + ".");
        } catch (SupportException e) {
            system.accept("⚠ Поддержка не включена: " + e.getMessage());
        }
    }

    private void off() {
        boolean was = connection != null;
        closeConnection();
        sessionId = null;
        system.accept(was ? "Поддержка выключена. Обычный режим чата." : "Поддержка уже выключена.");
    }

    private void setTicket(String requested) {
        if (!TICKET.matcher(requested).matches()) {
            system.accept("Неверный номер тикета «" + requested + "». Пример: T-1001.");
            return;
        }
        ticket = requested.toUpperCase(Locale.ROOT);
        system.accept("Тикет: " + ticket + ".");
    }

    String status() {
        String server = connection == null ? "—" : connection.description();
        String session = sessionId == null ? "нет" : sessionId.substring(0, Math.min(8, sessionId.length())) + "…";
        return "Поддержка: " + (connection == null ? "выключена" : "включена") + " · сервер: " + server
                + " · тикет: " + ticketOrNone() + " · сессия: " + session;
    }

    private String ticketOrNone() {
        return ticket == null ? "не задан" : ticket;
    }

    private void closeConnection() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    /** Ответ поддержки для терминала (markdown): тикет, ответ, источники, эскалация и служебная строка. */
    static String format(SupportChatCli.Turn turn) {
        StringBuilder text = new StringBuilder();
        if (turn.ticketLine() != null) {
            text.append("**Тикет:** ").append(turn.ticketLine().replaceAll("^\\[тикет\\s*|\\]$", "")).append("\n\n");
        }
        text.append(turn.answer());
        if (!turn.sources().isEmpty()) {
            text.append("\n\n**Источники:** ").append(String.join("; ", turn.sources()));
        }
        text.append("\n\n**Эскалация:** ").append(turn.escalated() ? "да" : "нет");
        text.append("\n\n_llmSource: ").append(turn.llmSource() == null ? "нет (без LLM)" : turn.llmSource())
                .append(" · ").append(turn.durationMs()).append(" мс_");
        return text.toString();
    }
}
