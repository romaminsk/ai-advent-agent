package com.example;

/**
 * Одно сообщение диалога: роль (system, user или assistant) и текст.
 * Роли соответствуют OpenAI-совместимому формату массива messages.
 */
public record ChatMessage(String role, String content) {
}
