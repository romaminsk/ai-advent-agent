package com.example;

import java.util.regex.Pattern;

/**
 * Обезвреживание терминальных управляющих последовательностей в тексте,
 * приходящем извне (ответы модели, содержимое истории).
 *
 * Текст не должен менять заголовок окна, управлять буфером обмена
 * или выполнять терминальные команды. Обычные переносы строк и табуляция
 * сохраняются, чтобы не терять форматирование ответов.
 */
public final class AnsiSanitizer {

    /** CSI-последовательности: ESC [ параметры финальный-байт. */
    private static final Pattern CSI =
            Pattern.compile("\u001B\\[[0-9;:?]*[ -/]*[@-~]");

    /** OSC-последовательности (заголовок окна, ссылки и т.п.): ESC ] ... BEL или ESC \\. */
    private static final Pattern OSC =
            Pattern.compile("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)?");

    /** Одиночные ESC и прочие escape-последовательности из набора C1 и частные. */
    private static final Pattern LONE_ESC = Pattern.compile("\u001B(?:[@-Z\\-_]?|\u009B)?");

    /** Остаточные управляющие символы C0/C1, кроме \\n и \\t. */
    private static final Pattern CONTROL =
            Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u009B-\\u009F]");

    private AnsiSanitizer() {
    }

    /** Возвращает текст без управляющих последовательностей; переносы и табуляция сохранены. */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String result = text;
        result = OSC.matcher(result).replaceAll("");
        result = CSI.matcher(result).replaceAll("");
        result = LONE_ESC.matcher(result).replaceAll("");
        // CRLF и одиночный CR приводим к обычному переносу строки.
        result = result.replace("\r\n", "\n").replace('\r', '\n');
        result = CONTROL.matcher(result).replaceAll("");
        return result;
    }
}
