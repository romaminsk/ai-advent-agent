package com.example.rag;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** One bounded, fallback-safe LLM query rewrite. */
public final class RagQueryRewriter {
    public static final String INSTRUCTION = "Перепиши вопрос в поисковый запрос для базы кода и документации проекта: "
            + "раскрой сокращения, добавь вероятные имена классов, методов и терминов, "
            + "сохрани смысл, не добавляй фактов, не отвечай на вопрос. Верни одну строку";
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "rag-query-rewrite");
        thread.setDaemon(true);
        return thread;
    });

    @FunctionalInterface
    public interface Client {
        String rewrite(String systemInstruction, String question) throws Exception;
    }

    public record Result(String query, boolean rewriteFallback, long rewriteMs) {
    }

    private final Client client;

    public RagQueryRewriter(Client client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public Result rewrite(String question) {
        long started = System.nanoTime();
        Future<String> future = EXECUTOR.submit(() -> client.rewrite(INSTRUCTION, question));
        try {
            String query = future.get(20, TimeUnit.SECONDS);
            if (query == null || query.isBlank() || query.length() > 300) {
                return new Result(question, true, elapsed(started));
            }
            String oneLine = query.strip().replaceAll("\\s*\\R+\\s*", " ");
            if (oneLine.isBlank() || oneLine.length() > 300) {
                return new Result(question, true, elapsed(started));
            }
            if (looksSecret(oneLine)) {
                return new Result(question, true, elapsed(started));
            }
            return new Result(oneLine, false, elapsed(started));
        } catch (Exception failure) {
            future.cancel(true);
            return new Result(question, true, elapsed(started));
        }
    }

    public static boolean looksSecret(String value) {
        if (value == null) return false;
        return value.matches("(?is).*(\\bsk-[A-Za-z0-9_-]{12,}|\\bgh[pousr]_[A-Za-z0-9]{20,}|"
                + "\\bBearer\\s+[A-Za-z0-9._~-]{16,}|(?:api[_-]?key|token|secret)\\s*[:=]\\s*"
                + "[^\\s,;]{8,}|\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"
                + "(?:\\.[A-Za-z0-9_-]{10,})?).*");
    }

    public static String redactSecrets(String value) {
        if (value == null || !looksSecret(value)) return value;
        return value.replaceAll("(?i)\\b(sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9]{20,}|"
                        + "Bearer\\s+[A-Za-z0-9._~-]{16,}|(?:api[_-]?key|token|secret)\\s*[:=]\\s*"
                        + "[^\\s,;]{8,}|eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"
                        + "(?:\\.[A-Za-z0-9_-]{10,})?)",
                "[скрыто]");
    }

    private static long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
