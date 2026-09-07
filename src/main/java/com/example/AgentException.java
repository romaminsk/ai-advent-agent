package com.example;

/**
 * Понятная ошибка конфигурации или обращения к LLM.
 * Сообщения не содержат секретов (ключ передаётся только в заголовке запроса).
 */
public class AgentException extends RuntimeException {

    public AgentException(String message) {
        super(message);
    }

    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
