package com.example;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Компактный индикатор ожидания ответа: «Ожидаем ответ… N с» + спиннер.
 *
 * Работает только когда терминал поддерживает перерисовку строки (интерактивный
 * режим); в plain-режиме создаётся с enabled=false и ничего не печатает.
 * Поток — демон: после close() он гарантированно останавливается, строка
 * полностью стирается, курсор показывается заново.
 */
final class ProgressSpinner implements TerminalUi.ProgressIndicator {

    private static final String[] FRAMES =
            {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final String[] FRAMES_ASCII =
            {"|", "/", "-", "\\"};
    private static final String ERASE_LINE = "\r\u001b[2K";
    private static final String HIDE_CURSOR = "\u001b[?25l";
    private static final String SHOW_CURSOR = "\u001b[?25h";

    private final PrintWriter out;
    private final boolean enabled;
    private final boolean ascii;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread thread;
    private final long startNanos = System.nanoTime();

    ProgressSpinner(PrintWriter out, boolean enabled) {
        this(out, enabled, false);
    }

    ProgressSpinner(PrintWriter out, boolean enabled, boolean asciiMode) {
        this.out = out;
        this.enabled = enabled;
        this.ascii = asciiMode;
        if (!enabled) {
            thread = null;
            return;
        }
        out.print(HIDE_CURSOR);
        out.flush();
        thread = new Thread(() -> {
            int frame = 1; // кадр 0 рисуется синхронно в конструкторе
            while (running.get()) {
                render(activeFrames()[frame % activeFrames().length]);
                frame++;
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "agent-progress");
        thread.setDaemon(true);
        // Первый кадр отображается сразу, до первого ожидания.
        render(activeFrames()[0]);
        thread.start();
    }

    /** Метка ожидания: фактическое действие известного этапа либо просто «… Думаю». */
    private String label() {
        return ascii ? "... Думаю " : "… Думаю ";
    }

    private void render(String frame) {
        long seconds = (System.nanoTime() - startNanos) / 1_000_000_000L;
        out.print(ERASE_LINE + label() + seconds + " с " + frame);
        out.flush();
    }

    private String[] activeFrames() {
        return ascii ? FRAMES_ASCII : FRAMES;
    }

    /** Для тестов: жив ли поток индикатора. */
    boolean isThreadAlive() {
        return thread != null && thread.isAlive();
    }

    /** Останавливает поток, стирает строку индикатора и возвращает курсор. */
    @Override
    public void close() {        if (!enabled) {
            return;
        }
        running.set(false);
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        out.print(ERASE_LINE + SHOW_CURSOR);
        out.flush();
    }
}
