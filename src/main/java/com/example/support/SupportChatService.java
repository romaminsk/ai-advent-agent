package com.example.support;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Чат поддержки с памятью в процессе: сессия хранит тикет и последние реплики.
 * Поток тот же, что у support (SupportPipeline); история передаётся в промт как данные.
 */
public final class SupportChatService {

    public static final int MAX_PAIRS = 6;
    public static final int MAX_HISTORY_CHARS = 4000;
    public static final Duration TTL = Duration.ofMinutes(30);
    public static final int MAX_SESSIONS = 50;
    static final int MAX_ANSWER_CHARS = 1200;
    private static final Pattern TICKET_IN_TEXT = Pattern.compile("(?i)\\bT-\\d{1,6}\\b");

    /** Реплика: вопрос пользователя и текст ответа поддержки. */
    public record Turn(String question, String answer) {
    }

    /** Результат хода: id сессии, тикет хода и результат общего потока (ответ, CRM, LLM). */
    public record ChatResult(String sessionId, String ticketId, SupportPipeline.Result result) {
    }

    private static final class Session {
        private final String id;
        private String ticketId;
        private final Deque<Turn> turns = new ArrayDeque<>();
        private Instant lastUsed;

        Session(String id, Instant now) {
            this.id = id;
            this.lastUsed = now;
        }

        synchronized String lastQuestion() {
            return turns.isEmpty() ? null : turns.getLast().question();
        }

        synchronized String history() {
            StringBuilder text = new StringBuilder();
            for (Turn turn : turns) {
                text.append("Пользователь: ").append(turn.question()).append('\n')
                        .append("Поддержка: ").append(turn.answer()).append('\n');
            }
            return text.toString();
        }

        /** Смена тикета сбрасывает историю: старый диалог относится к другому тикету. */
        synchronized void useTicket(String ticket) {
            if (ticket == null) {
                return;
            }
            if (ticketId != null && !ticketId.equals(ticket)) {
                turns.clear();
            }
            ticketId = ticket;
        }

        synchronized void add(String question, String answer) {
            String shortAnswer = answer.length() > MAX_ANSWER_CHARS ? answer.substring(0, MAX_ANSWER_CHARS) : answer;
            turns.addLast(new Turn(question, shortAnswer));
            while (turns.size() > MAX_PAIRS || chars() > MAX_HISTORY_CHARS) {
                turns.removeFirst();
            }
        }

        private int chars() {
            int total = 0;
            for (Turn turn : turns) {
                total += turn.question().length() + turn.answer().length();
            }
            return total;
        }
    }

    private final PrintStream diagnostics;
    private final Clock clock;
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public SupportChatService(PrintStream diagnostics) {
        this(diagnostics, Clock.systemUTC());
    }

    public SupportChatService(PrintStream diagnostics, Clock clock) {
        this.diagnostics = diagnostics;
        this.clock = clock;
    }

    /**
     * Один ход диалога. Тикет: параметр ticketId, затем T-номер из текста, затем тикет сессии.
     * sessionId без сессии создаёт новую сессию с этим id; null — новый id.
     */
    public ChatResult turn(String sessionId, String message, String ticketId,
                           Supplier<SupportCommand.Options> options) throws Exception {
        String text = message == null ? "" : message.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Пустое сообщение.");
        }
        Session session = acquire(sessionId);
        String ticket;
        synchronized (session) {
            ticket = ticketFor(ticketId, text, session);
            session.useTicket(ticket);
        }
        String previous = session.lastQuestion();
        String history = session.history();
        SupportPipeline.Result result = SupportPipeline.ask(options.get(), text, ticket, null, false,
                diagnostics, previous, history);
        if (result.answer().decision() != SupportService.Decision.LLM_FAILED) {
            session.add(text, result.answer().text());
        }
        return new ChatResult(session.id, ticket, result);
    }

    static String ticketFor(String explicit, String text, Session session) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.strip().toUpperCase(Locale.ROOT);
        }
        var matcher = TICKET_IN_TEXT.matcher(text);
        if (matcher.find()) {
            return matcher.group().toUpperCase(Locale.ROOT);
        }
        return session.ticketId;
    }

    private synchronized Session acquire(String sessionId) {
        Instant now = clock.instant();
        sessions.values().removeIf(session -> session.lastUsed.plus(TTL).isBefore(now));
        Session existing = sessionId == null ? null : sessions.get(sessionId);
        if (existing != null) {
            existing.lastUsed = now;
            return existing;
        }
        if (sessions.size() >= MAX_SESSIONS) {
            sessions.values().stream().min(Comparator.comparing(session -> session.lastUsed))
                    .ifPresent(oldest -> sessions.remove(oldest.id));
        }
        String id = sessionId == null ? UUID.randomUUID().toString() : sessionId;
        Session created = new Session(id, now);
        sessions.put(id, created);
        return created;
    }

    /** Число активных сессий (для проверки лимита и TTL). */
    public synchronized int sessionCount() {
        return sessions.size();
    }
}
