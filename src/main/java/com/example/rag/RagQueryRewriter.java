package com.example.rag;

import com.example.EmptyLlmAnswerException;
import com.example.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One bounded, fallback-safe LLM query rewrite. */
public final class RagQueryRewriter {
    public static final String INSTRUCTION = "Перепиши вопрос в поисковый запрос для базы кода и документации проекта: "
            + "раскрой сокращения, добавь вероятные имена классов, методов и терминов, "
            + "сохрани смысл, не добавляй фактов, не отвечай на вопрос. Верни одну строку";
    private static final Pattern FENCED = Pattern.compile(
            "(?is).*?```(?:json)?\\s*(.*?)\\s*```.*");
    private static final long DEFAULT_TIMEOUT_SECONDS = 20;
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "rag-query-rewrite");
        thread.setDaemon(true);
        return thread;
    });

    @FunctionalInterface
    public interface Client {
        String rewrite(String systemInstruction, String question) throws Exception;
    }

    public record Result(String query, boolean rewriteFallback, long rewriteMs,
                         String rewriteStatus) {
        public Result(String query, boolean rewriteFallback, long rewriteMs) {
            this(query, rewriteFallback, rewriteMs,
                    rewriteFallback ? "fallback:unknown" : "ok");
        }
    }

    private final Client client;
    private final long timeoutSeconds;

    public RagQueryRewriter(Client client) {
        this(client, DEFAULT_TIMEOUT_SECONDS);
    }

    public RagQueryRewriter(Client client, long timeoutSeconds) {
        this.client = Objects.requireNonNull(client, "client");
        if (timeoutSeconds < 1) throw new IllegalArgumentException("timeout должен быть > 0");
        this.timeoutSeconds = timeoutSeconds;
    }

    public Result rewrite(String question) {
        long started = System.nanoTime();
        Future<String> future = EXECUTOR.submit(() -> client.rewrite(INSTRUCTION, question));
        String raw;
        try {
            raw = future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            return fallback(question, started, "timeout");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return fallback(question, started, "interrupted");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof EmptyLlmAnswerException) return fallback(question, started, "empty");
            if (cause instanceof TimeoutException
                    || (cause != null && cause.getClass().getSimpleName().contains("Timeout"))) {
                return fallback(question, started, "timeout");
            }
            return fallback(question, started, "error");
        } catch (Exception failure) {
            future.cancel(true);
            return fallback(question, started, "error");
        }

        if (raw == null || raw.isBlank()) return fallback(question, started, "empty");
        String query;
        try {
            query = unwrapQuery(raw);
        } catch (InvalidRewriteFormat invalid) {
            return fallback(question, started, invalid.reason);
        }
        if (query == null || query.isBlank()) return fallback(question, started, "empty");
        String oneLine = query.strip().replaceAll("\\s*\\R+\\s*", " ");
        if (oneLine.isBlank()) return fallback(question, started, "empty");
        if (oneLine.length() > 300) return fallback(question, started, "too-long");
        if (looksSecret(oneLine)) return fallback(question, started, "secret");
        return new Result(oneLine, false, elapsed(started), "ok");
    }

    private static String unwrapQuery(String raw) throws InvalidRewriteFormat {
        String candidate = raw.strip();
        Matcher fenced = FENCED.matcher(candidate);
        if (fenced.matches()) candidate = fenced.group(1).strip();
        if (!candidate.startsWith("{") && !candidate.startsWith("[")
                && !candidate.startsWith("\"")) return candidate;
        try {
            JsonNode node = JsonSupport.MAPPER.readTree(candidate);
            if (node == null) throw new InvalidRewriteFormat("invalid-json");
            if (node.isTextual()) return node.asText();
            if (!node.isObject()) throw new InvalidRewriteFormat("invalid-json");
            JsonNode query = node.path("query");
            if (!query.isTextual()) throw new InvalidRewriteFormat("missing-query");
            return query.asText();
        } catch (IOException invalidJson) {
            throw new InvalidRewriteFormat("invalid-json");
        }
    }

    private static Result fallback(String question, long started, String reason) {
        return new Result(question, true, elapsed(started), "fallback:" + reason);
    }

    private static final class InvalidRewriteFormat extends Exception {
        private final String reason;

        private InvalidRewriteFormat(String reason) {
            this.reason = reason;
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
