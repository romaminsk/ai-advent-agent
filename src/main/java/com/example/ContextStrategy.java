package com.example;

import java.util.Locale;

/**
 * Стратегия управления контекстом : определяет, какие сообщения
 * и блоки уходят в массив messages очередного запроса.
 *
 * - SLIDING_WINDOW — системная инструкция и последние N завершённых сообщений
 *  (окно; более ранние сообщения в запрос не отправляются, архив при этом
 *  сохраняется полностью);
 * - FACTS — системная инструкция с блоком фактов «ключ: значение»
 *  плюс последние N сообщений; факты обновляются отдельным служебным
 *  запросом (LLM_FACTS_UPDATE_MODE);
 * - BRANCHING — ветки диалога от checkpoint: контекст — история активной
 *  ветки (целиком; режим контекста Дня 9 full/summary может применяться
 *  внутри ветки).
 *
 * Переключение стратегии (/strategy ...) само по себе API не вызывает:
 * оно меняет способ формирования следующего запроса.
 */
public enum ContextStrategy {

  SLIDING_WINDOW,
  FACTS,
  BRANCHING;

  public static final ContextStrategy DEFAULT = SLIDING_WINDOW;

  /** Разбор значения LLM_CONTEXT_STRATEGY; пустое значение — по умолчанию. */
  public static ContextStrategy parse(String value, String variableName) {
    if (value == null || value.isBlank()) {
      return DEFAULT;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    switch (normalized) {
      case "sliding-window": return SLIDING_WINDOW;
      case "facts": return FACTS;
      case "branching": return BRANCHING;
      default:
        throw new AgentException(variableName
            + " должна быть sliding-window, facts или branching, получено: "
            + value.trim() + ".");
    }
  }

    /** Русское название стратегии для сообщений терминала. */
    public String title() {
        switch (this) {
            case SLIDING_WINDOW:
                return "Скользящее окно (последние N сообщений)";
            case FACTS:
                return "Факты (ключ: значение) + последние N сообщений";
            default:
                return "Ветки (история активной ветки)";
        }
    }
}
