package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Логика стратегий управления контекстом (часть бывшего LlmAgent):
 * выбор сообщений, отправляемых дословно по текущей стратегии,
 * подписи фактически отправленного запроса и пользовательские заметки
 * после ответа. Класс не хранит состояния: все методы статические,
 * исходные данные (история, настройки, резюме, факты) передаются явно.
 *
 * - SLIDING_WINDOW — последние LLM_SLIDING_WINDOW_MESSAGES сообщений;
 * - FACTS — окно LLM_FACTS_WINDOW_MESSAGES; рабочая память (факты) уходит
 *   в system-сообщение ({@link ContextBuilder});
 * - BRANCHING — вся история активной ветки; в режиме summary покрытый
 *   резюме префикс дословно не дублируется.
 *
 * Архив беседы при этом ни в одной стратегии не обрезается: ограничивается
 * только состав запроса.
 */
final class StrategyEngine {

    private StrategyEngine() {
    }

    /** Последние limit сообщений (без изменения исходного списка). */
    static List<ChatMessage> lastMessages(List<ChatMessage> messages, int limit) {
        if (limit <= 0 || messages.size() <= limit) {
            return new ArrayList<>(messages);
        }
        return new ArrayList<>(messages.subList(messages.size() - limit, messages.size()));
    }

    /**
     * История, отправляемая дословно по текущей стратегии:
     * - sliding-window — последние N сообщений (LLM_SLIDING_WINDOW_MESSAGES);
     * - facts — последние LLM_FACTS_WINDOW_MESSAGES сообщений;
     * - branching — вся история активной ветки с учётом режима контекста
     *   (summary отрезает покрытый префикс без дублирования).
     */
    static List<ChatMessage> verbatimHistory(List<ChatMessage> history,
                                             ModelSettings settings,
                                             ConversationSummary summary) {
        switch (settings.contextStrategy()) {
            case SLIDING_WINDOW:
                return lastMessages(history, settings.slidingWindowMessages());
            case FACTS:
                return lastMessages(history, settings.factsWindowMessages());
            case BRANCHING:
            default:
                return verbatimBranchingHistory(history, settings, summary);
        }
    }

    /** История активной ветки с учётом режима контекста (full/summary). */
    static List<ChatMessage> verbatimBranchingHistory(List<ChatMessage> history,
                                                      ModelSettings settings,
                                                      ConversationSummary summary) {
        if (settings.contextMode() != ContextMode.SUMMARY) {
            return new ArrayList<>(history);
        }
        ConversationSummary active = summary;
        int covered = active != null ? active.coveredMessages() : 0;
        if (covered <= 0) {
            return new ArrayList<>(history);
        }
        return new ArrayList<>(history.subList(covered, history.size()));
    }

    /**
     * Замечание после ответа о фактическом составе запроса по стратегии:
     * имя стратегии, размер окна, число отброшенных сообщений, размер блока
     * фактов или активная ветка.
     */
    static String strategyNoteAfterAnswer(int includedPairs, int omittedPairs,
                                          ModelSettings settings,
                                          Map<String, String> facts,
                                          String activeBranchName) {
        StringBuilder text = new StringBuilder("Стратегия: ")
                .append(settings.contextStrategy().title());
        if (settings.contextStrategy() == ContextStrategy.SLIDING_WINDOW) {
            text.append(" · окно ").append(settings.slidingWindowMessages())
                    .append(" · в запросе ").append(includedPairs * 2)
                    .append(" сообщений истории");
            if (omittedPairs > 0) {
                text.append(" · отброшено из запроса ").append(omittedPairs * 2)
                        .append(" ранних (архив сохранён полностью)");
            }
        } else if (settings.contextStrategy() == ContextStrategy.FACTS) {
            text.append(" · окно ").append(settings.factsWindowMessages())
                    .append(" · блок фактов: ").append(facts.size())
                    .append(" пар · в запросе ").append(includedPairs * 2)
                    .append(" сообщений истории");
            if (omittedPairs > 0) {
                text.append(" · отброшено из запроса ").append(omittedPairs * 2)
                        .append(" ранних (архив сохранён полностью)");
            }
        } else {
            text.append(" · ветка «").append(activeBranchName).append("» · в запросе ")
                    .append(includedPairs * 2).append(" сообщений истории");
        }
        return text.toString();
    }

    /**
     * Подпись контекста формируемого запроса: вычисляется по состоянию
     * истории до отправки, включая уже применимое резюме. Системная
     * инструкция и новый вопрос в подписи не перечисляются.
     */
    static String buildContextCaption(List<ChatMessage> history,
                                      ModelSettings settings,
                                      ConversationSummary summary,
                                      Map<String, String> facts) {
        if (settings.contextStrategy() == ContextStrategy.SLIDING_WINDOW) {
            int size = history.size();
            if (size == 0) {
                return "Первый запрос: предыдущей истории нет";
            }
            int window = Math.min(size, settings.slidingWindowMessages());
            int dropped = size - window;
            if (dropped == 0) {
                return "Контекст запроса: вся история — "
                        + size + " сообщений дословно";
            }
            return "Контекст запроса: последние " + window + " сообщений из " + size
                    + "; отброшено из запроса " + dropped + " ранних (архив сохранён)";
        }
        if (settings.contextStrategy() == ContextStrategy.FACTS) {
            int size = history.size();
            int window = Math.min(size, settings.factsWindowMessages());
            int dropped = size - window;
            StringBuilder factsText = new StringBuilder("Контекст запроса: ");
            if (!facts.isEmpty()) {
                factsText.append("блок фактов (").append(facts.size()).append(" пар) + ");
            } else {
                factsText.append("блок фактов пуст + ");
            }
            if (size == 0) {
                factsText.append("нет сообщений истории");
                return factsText.toString();
            }
            factsText.append("последние ").append(window).append(" сообщений");
            if (dropped > 0) {
                factsText.append(" из ").append(size).append("; отброшено из запроса ")
                        .append(dropped).append(" ранних (архив сохранён)");
            } else {
                factsText.append(" дословно");
            }
            return factsText.toString();
        }
        if (settings.contextMode() == ContextMode.SUMMARY) {
            List<ChatMessage> verbatim =
                    verbatimBranchingHistory(history, settings, summary);
            if (summary != null) {
                return "Контекст запроса: резюме первых " + summary.coveredMessages()
                        + " сообщений и " + verbatim.size() + " сообщений дословно";
            }
            if (history.isEmpty()) {
                return "Контекст запроса: 0 сообщений дословно. Резюме пока не создано";
            }
            return "Контекст запроса: " + verbatim.size()
                    + " сообщений истории дословно. Резюме пока не создано";
        }
        // full: вся история дословно.
        if (history.isEmpty()) {
            return "Первый запрос: предыдущей истории нет";
        }
        return "Контекст запроса: вся история — "
                + history.size() + " сообщений дословно";
    }
}
