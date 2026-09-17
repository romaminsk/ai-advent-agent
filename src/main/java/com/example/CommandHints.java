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

    /** После /task start: план и затем переход к выполнению. */
    public static String afterTaskStart() {
        return "Дальше: /task stage execution, когда план готов · /task expect <действие>";
    }

    /** После перехода на этап: следующий логичный шаг цепочки. */
    public static String afterTaskStage(TaskStage stage) {
        return switch (stage) {
            case PLANNING -> "Дальше: /task step <шаг> — что делаем прямо сейчас";
            case EXECUTION -> "Дальше: /task step <шаг>, /task expect <действие> — уточнение хода";
            case VALIDATION -> "Дальше: /task stage done, когда проверка пройдена";
            case DONE -> "Начать новую: /task start <описание>";
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

    /** После /pipeline: как проверить, что связка работает. */
    public static String afterPipeline() {
        return "Дальше: обычным сообщением с триггером — скиллы подставятся в запрос";
    }
}
