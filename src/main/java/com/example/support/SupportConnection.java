package com.example.support;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Подключение чата к support-серверу: SUPPORT_SERVER_URL, если он отвечает на /health,
 * иначе SSH-туннель к VPS на свободный локальный порт. Туннель закрывается в close().
 */
public final class SupportConnection implements AutoCloseable {

    public static final int REMOTE_PORT = 8090;
    public static final String DEFAULT_SSH_HOST = "ai-agent-01";
    static final long HEALTH_WAIT_MILLIS = 10_000;

    private final SupportRemoteClient client;
    private final String description;
    private final Process tunnel;

    private SupportConnection(SupportRemoteClient client, String description, Process tunnel) {
        this.client = client;
        this.description = description;
        this.tunnel = tunnel;
    }

    /** Аргументы ssh для туннеля: без пароля, с контролем проброса и поддержанием связи. */
    public static List<String> sshCommand(String host, int localPort) {
        return List.of("ssh", "-N",
                "-o", "BatchMode=yes",
                "-o", "ExitOnForwardFailure=yes",
                "-o", "ConnectTimeout=10",
                "-o", "ServerAliveInterval=30",
                "-L", localPort + ":127.0.0.1:" + REMOTE_PORT,
                host);
    }

    public static SupportConnection open(Map<String, String> env) throws SupportException {
        String token = env.get("SUPPORT_API_TOKEN");
        if (token == null || token.isBlank()) {
            throw new SupportException("нет SUPPORT_API_TOKEN в .env: добавьте токен сервера поддержки.");
        }
        String direct = env.get("SUPPORT_SERVER_URL");
        if (direct != null && !direct.isBlank()) {
            SupportRemoteClient client = new SupportRemoteClient(direct, token.strip());
            if (!client.health()) {
                throw new SupportException("сервер поддержки по SUPPORT_SERVER_URL не отвечает: " + direct.strip());
            }
            return new SupportConnection(client, client.baseUrl(), null);
        }
        String host = env.get("SUPPORT_SSH_HOST");
        if (host == null || host.isBlank()) {
            host = DEFAULT_SSH_HOST;
        }
        int port = freePort();
        Path stderr;
        Process process;
        try {
            stderr = Files.createTempFile("support-ssh-", ".log");
            process = new ProcessBuilder(sshCommand(host.strip(), port))
                    .redirectInput(new File("/dev/null"))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(stderr.toFile())
                    .start();
        } catch (IOException e) {
            throw new SupportException("ssh не запустился: " + e.getMessage());
        }
        SupportRemoteClient client = new SupportRemoteClient("http://127.0.0.1:" + port, token.strip());
        long deadline = System.currentTimeMillis() + HEALTH_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new SupportException("ssh не подключился к " + host + " (код " + process.exitValue() + ")"
                        + reason(stderr) + ".");
            }
            if (client.health()) {
                discard(stderr);
                return new SupportConnection(client, "через ssh-туннель " + host, process);
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        stop(process);
        throw new SupportException("таймаут: /health через ssh-туннель " + host + " не ответил за 10 с.");
    }

    public String description() {
        return description;
    }

    public boolean alive() {
        return tunnel == null || tunnel.isAlive();
    }

    public SupportChatCli.Turn turn(String sessionId, String message, String ticket) throws SupportException {
        if (!alive()) {
            throw new SupportException("туннель к серверу поддержки закрыт: /support on — подключить снова.");
        }
        return client.chat(sessionId, message, ticket);
    }

    @Override
    public void close() {
        if (tunnel != null) {
            stop(tunnel);
        }
    }

    private static void stop(Process process) {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static int freePort() throws SupportException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new SupportException("не удалось выбрать свободный локальный порт: " + e.getMessage());
        }
    }

    private static void discard(Path stderr) {
        try {
            Files.deleteIfExists(stderr);
        } catch (IOException e) {
            // временный файл stderr ssh: ошибка удаления не влияет на туннель
        }
    }

    /** Последняя строка stderr ssh без адресов ключей; пусто, если причины нет. */
    private static String reason(Path stderr) {
        try {
            List<String> lines = Files.readAllLines(stderr);
            discard(stderr);
            if (lines.isEmpty()) {
                return "";
            }
            String last = lines.get(lines.size() - 1).strip();
            return last.isEmpty() ? "" : ": " + (last.length() > 160 ? last.substring(0, 160) + "…" : last);
        } catch (IOException e) {
            return "";
        }
    }
}
