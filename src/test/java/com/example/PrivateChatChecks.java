package com.example;

final class PrivateChatChecks extends SelfTestSupport {
    static void run() throws Exception {
        expect("private-chat разбирает ответ Ollama",
                "Ответ из модели".equals(PrivateChatClient.assistantContent(
                        "{\"message\":{\"role\":\"assistant\",\"content\":\"Ответ из модели\"}}")));
        expect("private-chat отвергает ответ без content",
                throwsIOException("{\"message\":{}}"));
        expect("private-chat обрабатывает HTTP-коды",
                "Ошибка: неверный ключ".equals(PrivateChatClient.statusMessage(401))
                        && "Ошибка: сообщение слишком длинное".equals(PrivateChatClient.statusMessage(413))
                        && "Ошибка: подожди несколько секунд".equals(PrivateChatClient.statusMessage(429))
                        && "Ошибка: сервис занят, повтори".equals(PrivateChatClient.statusMessage(503)));
    }

    private static boolean throwsIOException(String body) {
        try {
            PrivateChatClient.assistantContent(body);
            return false;
        } catch (java.io.IOException e) {
            return true;
        }
    }
}
