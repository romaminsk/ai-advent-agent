package com.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Консольный интерфейс агента.
 * Здесь нет ни JSON, ни HTTP — только чтение ввода, вывод ответа
 * и обращение к отдельному агенту LlmAgent.
 *
 * Поддерживает многошаговый диалог: агент учитывает историю текущей беседы,
 * а также локальные команды /help, /history, /reset, /exit (без вызова API).
 */
public final class Main {

    public static void main(String[] args) {
        // 1. Загружаем конфигурацию.
        Config config;
        try {
            config = Config.fromEnv();
        } catch (AgentException e) {
            System.err.println("Ошибка конфигурации: " + e.getMessage());
            System.exit(1);
            return;
        }

        // 2. Запускаем консольный цикл.
        run(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                System.out, config);
    }

    /**
     * Консольный цикл. Пакетно-приватный метод выделен для локальных тестов:
     * ввод и вывод передаются параметрами, в тестах подставляется локальный сервер.
     */
    static void run(BufferedReader reader, PrintStream out, Config config) {
        // Один экземпляр агента на всё время работы; историю беседы хранит агент.
        LlmAgent agent = new LlmAgent(config);

        out.println("CLI-агент запущен. Модель: " + config.model());
        out.println("Агент учитывает историю текущей беседы. Команды: /help, /history, /reset, /exit.");
        out.println("Пустая строка пропускается, exit или quit — выход.");

        try {
            while (true) {
                out.print("> ");
                out.flush();

                String line = reader.readLine();
                if (line == null) {
                    // EOF (Ctrl+D): корректно завершаем приложение. История не сохраняется.
                    out.println();
                    break;
                }

                String userMessage = line.trim();
                if (userMessage.isEmpty()) {
                    continue;
                }

                // Локальные команды обрабатываются без обращения к API.
                if (userMessage.equals("/help")) {
                    printHelp(out);
                    continue;
                }
                if (userMessage.equals("/history")) {
                    printHistory(out, agent.getHistory());
                    continue;
                }
                if (userMessage.equals("/reset")) {
                    agent.resetConversation();
                    out.println("Начата новая беседа. История очищена.");
                    continue;
                }
                if (userMessage.equals("/exit")) {
                    break;
                }
                if (userMessage.startsWith("/")) {
                    out.println("Неизвестная команда. Введите /help для справки.");
                    continue;
                }
                if (userMessage.equalsIgnoreCase("exit") || userMessage.equalsIgnoreCase("quit")) {
                    break;
                }

                try {
                    String answer = agent.ask(userMessage);
                    out.println("Агент: " + answer);
                } catch (AgentException e) {
                    // Обычную ошибку запроса показываем и позволяем ввести следующий запрос.
                    // История беседы при ошибке не изменяется.
                    out.println("Ошибка запроса: " + e.getMessage());
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                }
            }
        } catch (IOException e) {
            out.println("Ошибка чтения ввода: " + e.getMessage());
        } finally {
            out.println("Работа завершена. История диалога не сохраняется.");
        }
    }

    /** Справка по командам. */
    private static void printHelp(PrintStream out) {
        out.println("Доступные команды:");
        out.println("  /help    — справка");
        out.println("  /history — показать сохранённые сообщения с ролями");
        out.println("  /reset   — начать новую беседу (очистить историю)");
        out.println("  /exit    — завершить приложение");
        out.println("Также работают exit и quit. Пустая строка пропускается.");
    }

    /** Печатает снимок истории с ролями; без обращения к API. */
    private static void printHistory(PrintStream out, List<ChatMessage> history) {
        if (history.isEmpty()) {
            out.println("История диалога пуста.");
            return;
        }
        out.println("Сохранённые сообщения (" + history.size() / 2 + " пар):");
        for (ChatMessage message : history) {
            out.println("[" + message.role() + "] " + message.content());
        }
    }

    private Main() {
    }
}
