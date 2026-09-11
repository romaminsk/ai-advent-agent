package com.example;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Блок фактов «ключ: значение» (стратегия facts,: отдельная
 * сущность памяти диалога, хранится вне массива сообщений.
 *
 * Формат обмена с моделью — по одной паре на строку: «Ключ: значение».
 * Разбор строгий: строка без разделителя «:», пустой ключ или пустое
 * значение делают весь ответ непригодным; тогда прежний блок фактов
 * сохраняется без изменений.
 */
public final class FactsBlock {

  private FactsBlock() {
  }

  /**
   * Разбирает вывод модели в упорядоченный блок фактов. null — ответ
   * не является корректным блоком пар «ключ: значение» (строгий разбор:
   * никакая содержательная строка не молча пропускается).
   * Пустой список (нет пар) разбирается как пустой блок — он допустим,
   * если модель осознанно вернула пустую память.
   */
  public static LinkedHashMap<String, String> parse(String modelOutput) {
    if (modelOutput == null) {
      return null;
    }
    LinkedHashMap<String, String> parsed = new LinkedHashMap<>();
    for (String rawLine : modelOutput.split("\n", -1)) {
      String line = rawLine.trim();
      if (line.isEmpty()) {
        continue;
      }
      int separator = line.indexOf(':');
      if (separator <= 0) {
        return null;
      }
      String key = line.substring(0, separator).trim();
      String value = line.substring(separator + 1).trim();
      if (key.isEmpty() || value.isEmpty()) {
        return null;
      }
      parsed.put(key, value);
    }
    return parsed;
  }

  /** Рендер блока фактов в текст вида «ключ: значение» (по строке на пару). */
  public static String render(Map<String, String> facts) {
    if (facts == null || facts.isEmpty()) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    boolean first = true;
    for (Map.Entry<String, String> entry : facts.entrySet()) {
      if (!first) {
        text.append('\n');
      }
      text.append(entry.getKey()).append(": ").append(entry.getValue());
      first = false;
    }
    return text.toString();
  }
}
