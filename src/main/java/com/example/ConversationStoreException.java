package com.example;

/**
 * Ошибка хранилища контекста: повреждённый файл истории, неизвестная версия
 * формата, занятая блокировка, недоступный путь. Сообщение содержит путь
 * к файлу и безопасный следующий шаг; секреты и содержимое переписки
 * в диагностику не выводятся.
 */
public class ConversationStoreException extends AgentException {

    public ConversationStoreException(String message) {
        super(message);
    }

    public ConversationStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
