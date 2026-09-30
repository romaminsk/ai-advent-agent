package com.example.rag;

import com.example.EmptyLlmAnswerException;
import com.example.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.EnumSet;
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
            + "сохрани смысл, не добавляй фактов, не отвечай на вопрос. "
            + "Не более 15 слов, не длиннее 200 символов, без пояснений. Верни одну строку";
    private static final Pattern FENCED = Pattern.compile(
            "(?is).*?```(?:json)?\\s*(.*?)\\s*```.*");
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 2048;
    public static final long DEFAULT_TIMEOUT_SECONDS = 45;
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

    public record Diagnostic(String questionId, String model, int maxTokens,
                             long timeoutSeconds, long durationMs, String finishReason,
                             int contentLength, int reasoningLength,
                             boolean reasoningFieldPresent, Integer reasoningTokens,
                             String rewriteStatus, String prompt, String rawContent) {
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
        String oneLine = query.strip().replaceAll("\\s+", " ");
        if (oneLine.isBlank()) return fallback(question, started, "empty");
        if (looksSecret(oneLine)) return fallback(question, started, "secret");
        if (oneLine.length() > 300) {
            String truncated = truncateAtWordBoundary(oneLine, 300);
            if (truncated.isBlank()) return fallback(question, started, "empty");
            return new Result(truncated, false, elapsed(started), "ok-truncated");
        }
        return new Result(oneLine, false, elapsed(started), "ok");
    }

    private static String truncateAtWordBoundary(String value, int maxLength) {
        if (value.length() <= maxLength) return value;
        int boundary = value.lastIndexOf(' ', maxLength);
        if (boundary <= 0) {
            java.text.BreakIterator words = java.text.BreakIterator.getWordInstance(java.util.Locale.ROOT);
            words.setText(value);
            boundary = words.preceding(maxLength + 1);
        }
        if (boundary <= 0) boundary = value.offsetByCodePoints(0,
                Math.min(maxLength, value.codePointCount(0, value.length())));
        return value.substring(0, boundary).stripTrailing();
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

    public static Path diagnosticLogPath(Path home) {
        return home.resolve(".ai-advent-agent").resolve("rag-results")
                .resolve("rewrite-diagnostics-2026-09-30.log");
    }

    /** Appends one owner-only diagnostic record; raw model text is redacted and capped at 500 chars. */
    public static void appendDiagnostic(Path file, Diagnostic diagnostic) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        if (!Files.exists(absolute)) {
            try {
                Files.createFile(absolute, PosixFilePermissions.asFileAttribute(
                        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
            } catch (UnsupportedOperationException unsupported) {
                Files.createFile(absolute);
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                // Another diagnostic writer created the file first.
            }
        }
        setOwnerOnly(absolute);
        String raw = safeRawPreview(diagnostic.rawContent());
        String entry = "time=" + Instant.now() + "\n"
                + "question=" + diagnostic.questionId() + "\n"
                + "model=" + diagnostic.model() + "\n"
                + "max_tokens=" + diagnostic.maxTokens() + "\n"
                + "timeout_seconds=" + diagnostic.timeoutSeconds() + "\n"
                + "duration_ms=" + diagnostic.durationMs() + "\n"
                + "finish_reason=" + printable(diagnostic.finishReason()) + "\n"
                + "content_length=" + diagnostic.contentLength() + "\n"
                + "reasoning_length=" + diagnostic.reasoningLength() + "\n"
                + "reasoning_field_present=" + diagnostic.reasoningFieldPresent() + "\n"
                + "reasoning_tokens=" + (diagnostic.reasoningTokens() == null
                ? "unavailable" : diagnostic.reasoningTokens()) + "\n"
                + "rewrite_status=" + diagnostic.rewriteStatus() + "\n"
                + "prompt=" + diagnostic.prompt() + "\n"
                + "raw_content_begin\n" + raw + "\nraw_content_end\n---\n";
        Files.writeString(absolute, entry, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        setOwnerOnly(absolute);
    }

    private static String safeRawPreview(String rawContent) {
        if (rawContent == null || rawContent.isEmpty()) return "";
        if (looksSecret(rawContent)) return "[скрыт: похож на секрет]";
        String redacted = redactSecrets(rawContent);
        return redacted.length() <= 500 ? redacted : redacted.substring(0, 500) + "…";
    }

    private static String printable(String value) {
        return value == null || value.isBlank() ? "unavailable" : value.replaceAll("[\\r\\n]", " ");
    }

    private static void setOwnerOnly(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // POSIX file permissions are not available on every supported filesystem.
        }
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
