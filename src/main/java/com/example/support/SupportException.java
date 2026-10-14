package com.example.support;

/** Понятная пользователю ошибка подключения к support-серверу или его ответа. */
public final class SupportException extends Exception {

    public SupportException(String message) {
        super(message);
    }
}
