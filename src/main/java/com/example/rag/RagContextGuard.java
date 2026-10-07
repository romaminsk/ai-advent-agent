package com.example.rag;

import java.util.Locale;

/**
 * Предупреждение о близости RAG-промпта к контексту локальной модели.
 *
 * Правила (без «тихой деградации»):
 *  - prompt_tokens >= 90% num_ctx — предупреждение «приблизился»;
 *  - prompt_tokens + max_tokens > num_ctx — предупреждение «не поместится»;
 *  - num_ctx определить не удалось — предупреждение «контекст неизвестен».
 * Все проверки чистые, без сети — так их можно покрыть self-test'ами.
 */
public final class RagContextGuard {

    /** Доля num_ctx, при которой промпт считается близким к контексту. */
    public static final double PROXIMITY_RATIO = 0.90;

    private RagContextGuard() {
    }

    /**
     * Возвращает строку предупреждения или null. requestedMaxTokens —
     * фактический лимит выхода этого запроса (после оффлайн-профильных
     * сокращений), contextLength — num_ctx тега или null.
     */
    public static String warning(Integer promptTokens, int requestedMaxTokens,
                                 Integer contextLength) {
        if (promptTokens == null || promptTokens < 0) {
            return null;
        }
        if (contextLength == null || contextLength < 1) {
            return "Предупреждение: num_ctx локальной модели не определён "
                    + "(prompt_tokens=" + promptTokens + "); тихая деградация недопустима — "
                    + "проверьте Modelfile тега (PARAMETER num_ctx) вручную.";
        }
        if (promptTokens + requestedMaxTokens > contextLength) {
            return "Предупреждение: prompt_tokens=" + promptTokens + " + max_tokens="
                    + requestedMaxTokens + " превышает num_ctx=" + contextLength
                    + "; ответ может обрываться. Уменьшите RAG-контекст или поднимите num_ctx.";
        }
        if (promptTokens >= PROXIMITY_RATIO * contextLength) {
            return "Предупреждение: prompt_tokens=" + promptTokens + " стал >= "
                    + String.format(Locale.ROOT, "%.0f%%", PROXIMITY_RATIO * 100)
                    + " num_ctx=" + contextLength + " — близко к контексту модели.";
        }
        return null;
    }
}
