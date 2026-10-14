package com.example.support;

import com.example.Config;
import com.example.JsonConversationStore;
import com.example.LlmAgent;
import com.example.ModelSettings;
import com.example.rag.RagService;
import com.example.review.ReviewLlmConfig;
import com.example.review.ReviewRunner;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM для support: облако, затем локальная Ollama — порядок и число попыток берутся из ReviewLlmConfig
 * и ReviewRunner.Model. Таймаут облачной попытки и общий потолок шага ограничены: часть бюджета
 * резервируется под локальную модель. Неудачная облачная попытка выводится одной строкой в лог.
 */
public final class SupportLlm implements RagService.LlmClient {

    public static final int CLOUD_ATTEMPT_SECONDS = 60;
    public static final int BUDGET_SECONDS = 180;
    public static final int LOCAL_RESERVE_SECONDS = 120;
    static final String LOCAL_NOTE = "ответ локальной моделью (облако недоступно)";
    /** Те же заголовки, что у команды ревью: провайдер учитывает их как один клиент и стабильную сессию. */
    static final String CLOUD_USER_AGENT = "ai-agent-review/1.0";
    static final String CLOUD_SESSION_ID = "ai-agent-review";
    private static final Pattern HTTP_STATUS = Pattern.compile("HTTP-статус (\\d{3})");
    private static final ExecutorService POOL = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "support-llm");
        thread.setDaemon(true);
        return thread;
    });

    private final List<ReviewRunner.Model> models;
    private final PrintStream log;
    private final int cloudAttemptSeconds;
    private final int localReserveSeconds;
    private final long deadlineNanos;
    private volatile String lastMode = "нет ответа";

    private SupportLlm(List<ReviewRunner.Model> models, PrintStream log, int cloudAttemptSeconds,
                       int budgetSeconds, int localReserveSeconds) {
        this.models = List.copyOf(models);
        this.log = log;
        this.cloudAttemptSeconds = cloudAttemptSeconds;
        this.localReserveSeconds = localReserveSeconds;
        this.deadlineNanos = System.nanoTime() + budgetSeconds * 1_000_000_000L;
    }

    /** Бюджет отсчитывается от создания объекта: создаётся в начале команды support. */
    public static SupportLlm create(List<ReviewRunner.Model> models, PrintStream log, int cloudAttemptSeconds,
                                    int budgetSeconds, int localReserveSeconds) {
        return new SupportLlm(models, log, cloudAttemptSeconds, budgetSeconds, localReserveSeconds);
    }

    public static SupportLlm fromEnvironment(Map<String, String> env, PrintStream log) {
        Map<String, String> values = ReviewLlmConfig.merged(env, Map.of());
        ReviewLlmConfig.Selection selection = ReviewLlmConfig.select(values);
        long localTimeout = ReviewLlmConfig.localTimeoutSeconds(values);
        List<ReviewRunner.Model> models = new ArrayList<>();
        for (ReviewLlmConfig.Backend backend : selection.backends()) {
            boolean local = ReviewLlmConfig.MODE_LOCAL.equals(backend.mode());
            models.add(new ReviewRunner.Model(backend.mode(), backend.model(), llmFor(backend),
                    backend.apiKey(), local ? localTimeout : ReviewRunner.LLM_TIMEOUT_SECONDS));
        }
        return create(models, log, CLOUD_ATTEMPT_SECONDS, BUDGET_SECONDS, LOCAL_RESERVE_SECONDS);
    }

    /** Режим и модель, которые дали последний успешный ответ: «облако:…» или «локально:…». */
    public String lastMode() {
        return lastMode;
    }

    @Override
    public String complete(String system, String user, int maxOutputTokens) throws Exception {
        boolean localBackend = models.stream().anyMatch(model -> ReviewLlmConfig.MODE_LOCAL.equals(model.mode()));
        List<String> failures = new ArrayList<>();
        for (ReviewRunner.Model model : models) {
            boolean cloud = ReviewLlmConfig.MODE_CLOUD.equals(model.mode());
            for (int attempt = 1; attempt <= model.attempts(); attempt++) {
                long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                long reserveMs = cloud && localBackend ? localReserveSeconds * 1000L : 0;
                long limitMs = cloud ? Math.min(cloudAttemptSeconds * 1000L, remainingMs - reserveMs) : remainingMs;
                if (limitMs < 1000) {
                    failures.add(model.mode() + ": бюджет времени исчерпан");
                    break;
                }
                long started = System.nanoTime();
                try {
                    String text = call(model, system, user, maxOutputTokens, limitMs);
                    lastMode = model.mode() + ":" + model.name();
                    return text;
                } catch (Exception e) {
                    String reason = classify(e, model.secret());
                    failures.add(model.mode() + " попытка " + attempt + ": " + reason);
                    if (cloud) {
                        log.println(String.format(Locale.ROOT, "облако, попытка %d/%d: %s, %.1f с",
                                attempt, model.attempts(), reason, (System.nanoTime() - started) / 1e9));
                    }
                }
            }
        }
        throw new IllegalStateException("все попытки LLM исчерпаны: " + String.join("; ", failures));
    }

    private static String call(ReviewRunner.Model model, String system, String user, int maxOutputTokens,
                               long limitMs) throws Exception {
        Future<String> future = POOL.submit(() -> model.llm().complete(system, user, maxOutputTokens));
        try {
            return future.get(limitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** Короткая причина без текста ответа сервера и без ключа: timeout, HTTP NNN, ошибка сети или усечённое описание. */
    static String classify(Throwable error, String secret) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException) {
                return "timeout";
            }
            String message = current.getMessage() == null ? "" : current.getMessage();
            if (message.contains("таймаут")) {
                return "timeout";
            }
            Matcher status = HTTP_STATUS.matcher(message);
            if (status.find()) {
                return "HTTP " + status.group(1);
            }
            if (message.contains("Сетевая ошибка")) {
                return "ошибка сети";
            }
        }
        String text = ReviewLlmConfig.scrub(ReviewRunner.describe(error), secret);
        return "ошибка: " + (text.length() > 120 ? text.substring(0, 120) + "…" : text);
    }

    private static ReviewRunner.Llm llmFor(ReviewLlmConfig.Backend backend) {
        try {
            Config config = new Config(backend.apiKey(), backend.url(), backend.model());
            Path history = Files.createTempDirectory("ai-agent-support-").resolve("history.json");
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    new JsonConversationStore(history, true));
            if (ReviewLlmConfig.MODE_CLOUD.equals(backend.mode())) {
                agent.useReviewIdentity(CLOUD_USER_AGENT, CLOUD_SESSION_ID);
            }
            return (system, user, maxOutputTokens) ->
                    agent.statelessRagCall(system, user, maxOutputTokens).text();
        } catch (Exception e) {
            String reason = ReviewLlmConfig.scrub(ReviewRunner.describe(e), backend.apiKey());
            return (system, user, maxOutputTokens) -> {
                throw new IllegalStateException(reason);
            };
        }
    }
}
