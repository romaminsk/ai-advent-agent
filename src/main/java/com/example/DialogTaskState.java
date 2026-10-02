package com.example;

import java.util.List;

/**
 * Состояние диалога (память задачи беседы): цель диалога, что пользователь
 * уже уточнил, какие ограничения и термины зафиксированы, какие вопросы
 * остались открытыми.
 *
 * Не путать с {@link TaskState} — формализованным состоянием этапов задачи
 * (/task): там этап/статус/шаг, здесь — содержательная память беседы.
 *
 * Обновляется детерминированно после каждого хода
 * ({@link DialogTaskStateTracker}), без дополнительных запросов к модели.
 * Хранится в файле истории ({@link ConversationState#dialogState()}),
 * переживает перезапуск и сбрасывается вместе с историей (/clear, /reset).
 * Секреты в состояние не сохраняются (проверка looksSecret при обновлении).
 */
public record DialogTaskState(String goal, List<String> clarifications,
                              List<String> constraints, List<String> terms,
                              List<String> openQuestions) {

    /** Пустое состояние (новая беседа). */
    public static final DialogTaskState EMPTY = new DialogTaskState(null,
            List.of(), List.of(), List.of(), List.of());

    public DialogTaskState {
        if (goal != null && goal.isBlank()) {
            goal = null;
        }
        clarifications = clarifications == null ? List.of() : List.copyOf(clarifications);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        terms = terms == null ? List.of() : List.copyOf(terms);
        openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
    }

    /** true, если ни одно поле не заполнено. */
    public boolean isEmpty() {
        return goal == null && clarifications.isEmpty() && constraints.isEmpty()
                && terms.isEmpty() && openQuestions.isEmpty();
    }

    public DialogTaskState withGoal(String newGoal) {
        return new DialogTaskState(newGoal, clarifications, constraints, terms, openQuestions);
    }

    public DialogTaskState withClarifications(List<String> updated) {
        return new DialogTaskState(goal, updated, constraints, terms, openQuestions);
    }

    public DialogTaskState withConstraints(List<String> updated) {
        return new DialogTaskState(goal, clarifications, updated, terms, openQuestions);
    }

    public DialogTaskState withTerms(List<String> updated) {
        return new DialogTaskState(goal, clarifications, constraints, updated, openQuestions);
    }

    public DialogTaskState withOpenQuestions(List<String> updated) {
        return new DialogTaskState(goal, clarifications, constraints, terms, updated);
    }
}
