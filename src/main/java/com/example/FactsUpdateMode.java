package com.example;

import java.util.Locale;

/**
 * Режим обновления блока фактов (стратегия facts,:
 * - AUTO — после каждого успешного ответа facts обновляются отдельным
 *  служебным запросом (назначение FACTS_UPDATE);
 * - MANUAL — обновление только по явной команде /facts refresh.
 */
public enum FactsUpdateMode {

  AUTO,
  MANUAL;

  public static final FactsUpdateMode DEFAULT = AUTO;

  /** Разбор значения LLM_FACTS_UPDATE_MODE; пустое значение — по умолчанию. */
  public static FactsUpdateMode parse(String value, String variableName) {
    if (value == null || value.isBlank()) {
      return DEFAULT;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    if ("auto".equals(normalized)) {
      return AUTO;
    }
    if ("manual".equals(normalized)) {
      return MANUAL;
    }
    throw new AgentException(variableName + " должна быть auto или manual, получено: "
        + value.trim() + ".");
  }

  public String title() {
    return this == AUTO ? "auto (после каждого сообщения)" : "manual (только /facts refresh)";
  }
}
