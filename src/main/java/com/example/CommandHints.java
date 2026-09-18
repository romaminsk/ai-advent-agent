package com.example;

/**
 * Подсказки следующего шага — единый механизм «ведения» пользователя
 * (главный способ сделать интерфейс ведущим): после каждого действия
 * команда показывает одну ненавязчивую строку о том, что логично
 * сделать дальше. Не дублируется на завершающих шагах цепочки.
 */
public final class CommandHints {

    private CommandHints() {
    }

    /** После /task start: план, утверждение и переход к выполнению. */
    public static String afterTaskStart() {
        return "Дальше: /task plan <текст> — зафиксировать план · /task approve — утвердить "
                + "· /task stage execution — после утверждения плана";
    }

    /** После перехода на этап: следующий логичный шаг цепочки. */
    public static String afterTaskStage(TaskStage stage) {
        return switch (stage) {
            case PLANNING -> "Дальше: /task plan <текст> — зафиксировать план · "
                    + "/task approve — утвердить · /task stage execution — после утверждения";
            case EXECUTION -> "Дальше: /task step <шаг>, /task expect <действие> "
                    + "· /task stage validation — когда готов к проверке";
            case VALIDATION -> "Дальше: /task validate pass|fail <результат> — зафиксировать "
                    + "результат · /task stage execution <причина> — при доработке "
                    + "· /task stage done — после успешной проверки";
            case DONE -> "Начать новую: /task start <описание>";
        };
    }

    /** Ближайшее допустимое управляющее действие для текущего состояния задачи. */
    public static String nextTaskAction(TaskState state) {
        return switch (state.status()) {
            case PAUSED -> "/task resume — продолжить после паузы";
            case BLOCKED -> "/task unblock — снять блокировку";
            case ACTIVE -> switch (state.stage()) {
                case PLANNING -> state.plan() == null
                        ? "/task plan <текст> — зафиксировать план"
                        : (state.planApproved()
                        ? "/task stage execution — перейти к выполнению"
                        : "/task approve — утвердить план");
                case EXECUTION -> "/task stage validation — перейти к проверке, "
                        + "когда текущий шаг готов";
                case VALIDATION -> !state.validationPassed()
                        ? "/task validate pass|fail <результат> — зафиксировать результат проверки"
                        : "/task stage done — завершить задачу";
                case DONE -> "/task start <описание> — начать новую задачу";
            };
        };
    }

    /** После задания шага: состояние видно целиком. */
    public static String afterTaskStep() {
        return "Дальше: /task status — полное состояние";
    }

    /** После первого поля профиля: чем ещё можно настроить. */
    public static String afterProfileField(String field) {
        return switch (field) {
            case "name" -> "Дальше: /profile style|format|constraint";
            case "style", "format" -> "Дальше: /profile constraint <ограничение> · очередное поле — снова /profile";
            default -> "Дальше: /profile — просмотр всех полей";
        };
    }

    /** После добавления скилла: скилл без пайплайна сам не включается. */
    public static String afterSkillAdd() {
        return "Дальше: /pipeline \"<триггер>\" <скилл> — когда применять скилл";
    }

    /** После /remember: посмотреть, что сохранено. */
    public static String afterRemember() {
        return "Дальше: /memory — посмотреть записи";
    }

    /** После /invariant add: посмотреть все рамки; упомянуть явные маркеры. */
    public static String afterInvariantAdd() {
        return "Дальше: /invariant — посмотреть все рамки · при add можно задать "
                + "«запрещено: маркер1, маркер2» — конфликт будет ловиться до API";
    }

    /** После /task invariant add: посмотреть локальные рамки задачи. */
    public static String afterTaskInvariantAdd() {
        return "Дальше: /task invariant — посмотреть локальные рамки";
    }

    /** После /pipeline: как проверить, что связка работает. */
    public static String afterPipeline() {
        return "Дальше: обычным сообщением с триггером — скиллы подставятся в запрос";
    }
}
