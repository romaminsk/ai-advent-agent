package com.example;

/**
 * Ошибка уровня инструмента MCP (structuredContent не формируется,
 * tools/call возвращает isError). Сервер возвращает её текст пользователю,
 * а не падает с исключением сервера.
 */
public final class PipelineToolException extends Exception {

    public PipelineToolException(String message) {
        super(message);
    }
}
