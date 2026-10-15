package com.example;

/** Ошибка файлового инструмента; текст уходит модели и пользователю в структурированном виде. */
final class FilesToolException extends RuntimeException {
    FilesToolException(String message) {
        super(message);
    }
}
