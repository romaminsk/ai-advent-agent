package com.example.support;

import com.example.Config;
import com.example.JsonConversationStore;
import com.example.LlmAgent;
import com.example.ModelSettings;
import com.example.rag.RagService;
import com.example.review.ReviewLlmConfig;
import com.example.review.ReviewRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM для support: облако (3 попытки), затем локальная Ollama (1 попытка) — тот же порядок,
 * что у ревью. Логика выбора и лимиты попыток берутся из ReviewLlmConfig и ReviewRunner.Model.
 */
final class SupportLlm implements RagService.LlmClient {

    private final List<ReviewRunner.Model> models;
    private volatile String lastMode = "нет ответа";

    private SupportLlm(List<ReviewRunner.Model> models) {
        this.models = List.copyOf(models);
    }

    /** Режим и модель, которые дали последний успешный ответ. */
    String lastMode() {
        return lastMode;
    }

    static SupportLlm fromEnvironment(Map<String, String> env) {
        Map<String, String> values = ReviewLlmConfig.merged(env, Map.of());
        ReviewLlmConfig.Selection selection = ReviewLlmConfig.select(values);
        long localTimeout = ReviewLlmConfig.localTimeoutSeconds(values);
        List<ReviewRunner.Model> models = new ArrayList<>();
        for (ReviewLlmConfig.Backend backend : selection.backends()) {
            boolean local = ReviewLlmConfig.MODE_LOCAL.equals(backend.mode());
            models.add(new ReviewRunner.Model(backend.mode(), backend.model(), llmFor(backend),
                    backend.apiKey(), local ? localTimeout : ReviewRunner.LLM_TIMEOUT_SECONDS));
        }
        return new SupportLlm(models);
    }

    /** Режимы в порядке попыток: облако (если настроено), затем локальная модель. */
    List<String> modes() {
        return models.stream().map(model -> model.mode() + ":" + model.name()).toList();
    }

    @Override
    public String complete(String system, String user, int maxOutputTokens) throws Exception {
        List<String> failures = new ArrayList<>();
        for (ReviewRunner.Model model : models) {
            for (int attempt = 1; attempt <= model.attempts(); attempt++) {
                try {
                    String text = model.llm().complete(system, user, maxOutputTokens);
                    lastMode = model.mode() + ":" + model.name();
                    return text;
                } catch (Exception e) {
                    failures.add(model.mode() + " попытка " + attempt + ": "
                            + ReviewLlmConfig.scrub(ReviewRunner.describe(e), model.secret()));
                }
            }
        }
        throw new IllegalStateException("все попытки LLM исчерпаны: " + String.join("; ", failures));
    }

    private static ReviewRunner.Llm llmFor(ReviewLlmConfig.Backend backend) {
        try {
            Config config = new Config(backend.apiKey(), backend.url(), backend.model());
            Path history = Files.createTempDirectory("ai-agent-support-").resolve("history.json");
            LlmAgent agent = new LlmAgent(config, ModelSettings.defaults(),
                    new JsonConversationStore(history, true));
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
