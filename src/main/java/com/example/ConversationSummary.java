package com.example;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Версионированное краткое резюме покрытой части беседы (День 9).
 *
 * Summary — отдельная сущность, а не обычное сообщение в архивной переписке.
 * Исходная история (архив) хранится полностью и не изменяется при сжатии.
 *
 * Соответствие архиву проверяется не только числом сообщений (история той же
 * длины могла измениться), а стабильным отпечатком покрытого префикса:
 * SHA-256 над sessionId, числом покрытых сообщений и парами
 * «роль/content» каждого покрытого сообщения.
 *
 * Резюме является сжатием с потерями: полнота деталей не гарантируется.
 */
public record ConversationSummary(
        String text,
        int coveredMessages,
        String coveredFingerprint,
        int formatVersion) {

    /** Текущая версия формата сущности summary в файле. */
    public static final int FORMAT_VERSION = 1;

    public ConversationSummary {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("текст summary обязателен");
        }
        if (coveredMessages <= 0) {
            throw new IllegalArgumentException("coveredMessages должно быть положительным");
        }
        if (coveredFingerprint == null || coveredFingerprint.isBlank()) {
            throw new IllegalArgumentException("отпечаток покрытого префикса обязателен");
        }
        if (formatVersion != FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "поддерживается формат summary только версии " + FORMAT_VERSION);
        }
    }

    /**
     * Стабильный отпечаток покрытого префикса: хеш sessionId, числа сообщений
     * и последовательности «роль + текст» префикса. Двойные переносы —
     * разделители, чтобы одни и те же сочетания не смешивались.
     */
    public static String computeFingerprint(String sessionId, List<ChatMessage> covered) {
        if (sessionId == null || sessionId.isBlank() || covered == null) {
            throw new IllegalArgumentException("sessionId и префикс обязательны");
        }
        StringBuilder source = new StringBuilder();
        source.append(sessionId).append('\n').append(covered.size()).append('\n');
        for (ChatMessage message : covered) {
            source.append(message.role()).append('\n')
                    .append(message.content()).append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    source.toString().getBytes(StandardCharsets.UTF_8));
            return hex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 есть в каждой стандартной платформе Java.
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            result.append(Character.forDigit((b >> 4) & 0xF, 16));
            result.append(Character.forDigit(b & 0xF, 16));
        }
        return result.toString();
    }

    /**
     * true, если резюме по-прежнему соответствует текущему архиву: покрытая
     * часть существует и отпечаток совпадает. Не уточняем «насколько оно
     * устарело» — несоответствие означает, что резюме не применяется.
     */
    public boolean matchesArchive(String sessionId, List<ChatMessage> archiveMessages) {
        return coveredMessages <= archiveMessages.size()
                && coveredFingerprint.equals(
                        computeFingerprint(sessionId,
                                archiveMessages.subList(0, coveredMessages)));
    }
}
