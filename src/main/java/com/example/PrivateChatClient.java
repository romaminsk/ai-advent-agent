package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Client for the private Ollama service reachable through the local SSH tunnel. */
public final class PrivateChatClient {
    static final int LOCAL_PORT = 18080;
    private static final URI CHAT_URI = URI.create("http://127.0.0.1:" + LOCAL_PORT + "/api/chat");
    private static final Path HISTORY_FILE = Path.of(System.getProperty("user.home"),
            ".ai-advent-agent", "private-chat-history.json");
    private static final int MAX_REQUEST_MESSAGES = 6;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    private PrivateChatClient() {
    }

    public static int run(String[] args) {
        String key = System.getenv("PRIVATE_LLM_KEY");
        if (key == null || key.isBlank()) {
            System.err.println("Ошибка: PRIVATE_LLM_KEY не найден в .env");
            return 1;
        }
        String oneShot = args.length == 0 ? null : String.join(" ", args).trim();
        try (Tunnel tunnel = openTunnel()) {
            ArrayNode history = readHistory();
            if (oneShot != null) {
                if (oneShot.isBlank()) {
                    System.err.println("Ошибка: вопрос пустой");
                    return 1;
                }
                return askAndPrint(key, history, oneShot);
            }
            return interactive(key, history);
        } catch (PrivateChatException e) {
            System.err.println("Ошибка: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            System.err.println("Ошибка: не удалось обработать историю или запрос");
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Ошибка: запрос прерван");
            return 1;
        }
    }

    private static int interactive(String key, ArrayNode history)
            throws IOException, InterruptedException, PrivateChatException {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("Вы › ");
            System.out.flush();
            String question = input.readLine();
            if (question == null || "/exit".equals(question.trim())) {
                return 0;
            }
            if (question.isBlank()) {
                continue;
            }
            int result = askAndPrint(key, history, question.trim());
            if (result != 0) {
                return result;
            }
        }
    }

    private static int askAndPrint(String key, ArrayNode history, String question)
            throws IOException, InterruptedException, PrivateChatException {
        ArrayNode requestHistory = recentMessages(history, question);
        ObjectNode request = JsonSupport.MAPPER.createObjectNode();
        request.put("model", "private-chat");
        request.set("messages", requestHistory);
        request.put("stream", false);

        HttpRequest httpRequest = HttpRequest.newBuilder(CHAT_URI)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        JsonSupport.MAPPER.writeValueAsString(request), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = HTTP.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PrivateChatException("не удалось получить ответ от приватного сервиса", e);
        }
        String statusError = statusMessage(response.statusCode());
        if (statusError != null) {
            System.err.println(statusError);
            return 1;
        }
        String answer;
        try {
            answer = assistantContent(response.body());
        } catch (IOException | IllegalArgumentException e) {
            throw new PrivateChatException("приватный сервис вернул некорректный JSON", e);
        }
        System.out.println("Модель › " + answer);
        history.add(message("user", question));
        history.add(message("assistant", answer));
        saveHistory(history);
        return 0;
    }

    private static ArrayNode recentMessages(ArrayNode history, String question) {
        List<JsonNode> messages = new ArrayList<>();
        for (JsonNode message : history) {
            messages.add(message);
        }
        messages.add(message("user", question));
        int from = Math.max(0, messages.size() - MAX_REQUEST_MESSAGES);
        ArrayNode result = JsonSupport.MAPPER.createArrayNode();
        for (int i = from; i < messages.size(); i++) {
            result.add(messages.get(i));
        }
        return result;
    }

    private static ObjectNode message(String role, String content) {
        ObjectNode node = JsonSupport.MAPPER.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    static String assistantContent(String body) throws IOException {
        JsonNode root = JsonSupport.MAPPER.readTree(body);
        String content = root.path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            throw new IOException("поле message.content отсутствует");
        }
        return content;
    }

    static String statusMessage(int status) {
        return switch (status) {
            case 401 -> "Ошибка: неверный ключ";
            case 413 -> "Ошибка: сообщение слишком длинное";
            case 429 -> "Ошибка: подожди несколько секунд";
            case 503 -> "Ошибка: сервис занят, повтори";
            case 200 -> null;
            default -> "Ошибка: приватный сервис вернул HTTP " + status;
        };
    }

    private static ArrayNode readHistory() throws PrivateChatException {
        if (!Files.exists(HISTORY_FILE)) {
            return JsonSupport.MAPPER.createArrayNode();
        }
        try {
            JsonNode root = JsonSupport.MAPPER.readTree(HISTORY_FILE.toFile());
            if (!root.isArray()) {
                throw new IOException("история не является массивом");
            }
            return (ArrayNode) root;
        } catch (IOException | ClassCastException e) {
            throw new PrivateChatException("не удалось прочитать историю приватного чата", e);
        }
    }

    private static void saveHistory(ArrayNode history) throws PrivateChatException {
        try {
            Path directory = HISTORY_FILE.getParent();
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "private-chat-history-", ".tmp");
            try {
                Files.writeString(temporary, JsonSupport.MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(history) + "\n", StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, HISTORY_FILE, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, HISTORY_FILE, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            try {
                Files.setPosixFilePermissions(HISTORY_FILE,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows does not expose POSIX permissions.
            }
        } catch (IOException e) {
            throw new PrivateChatException("не удалось сохранить историю приватного чата", e);
        }
    }

    private static Tunnel openTunnel() throws PrivateChatException, InterruptedException {
        if (healthCheck()) {
            return new Tunnel(null);
        }
        if (portOpen()) {
            throw new PrivateChatException("порт 18080 занят нерабочим процессом");
        }
        Process process;
        try {
            process = new ProcessBuilder("ssh", "-N", "-L",
                    "18080:127.0.0.1:8080", "ai-agent-01")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            throw new PrivateChatException("туннель не поднялся: не найден ssh", e);
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (healthCheck()) {
                return new Tunnel(process);
            }
            if (!process.isAlive()) {
                break;
            }
            Thread.sleep(200);
        }
        process.destroyForcibly();
        throw new PrivateChatException("туннель не поднялся");
    }

    private static boolean healthCheck() {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + LOCAL_PORT + "/health"))
                    .timeout(Duration.ofSeconds(1)).GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("\"ok\"");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static boolean portOpen() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", LOCAL_PORT), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private record Tunnel(Process process) implements AutoCloseable {
        @Override
        public void close() {
            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    static final class PrivateChatException extends Exception {
        PrivateChatException(String message) {
            super(message);
        }

        PrivateChatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
