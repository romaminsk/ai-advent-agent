package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Модель веток диалога (стратегия branching,).
 *
 * Хранение: общий префикс до checkpoint — сообщения [0..checkpointIndex)
 * активной беседы, общие для всех веток; у каждой ветки — свой «хвост»,
 * то есть сообщения активной беседы после checkpoint. Полная история ветки
 * = префикс + её хвост. Активная ветка = та, в которой сейчас идёт диалог.
 *
 * Инвариант checkpoint: checkpoint можно перенести вперёд только когда
 * у всех прочих веток пустые хвосты — иначе разветвлённые истории молча
 * переписывались бы общим префиксом (недопустимая потеря сообщений).
 */
public record BranchData(int checkpointIndex, String active, List<Branch> branches) {

  /** Имя первой (начальной) ветки. */
  public static final String DEFAULT_ACTIVE = "main";

  /** Наибольшая длина имени ветки. */
  public static final int MAX_NAME_LENGTH = 32;

  public record Branch(String name, List<ChatMessage> messages) {
    public Branch {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("имя ветки обязательно");
      }
      messages = List.copyOf(messages);
    }
  }

  public BranchData {
    if (checkpointIndex < 0 || checkpointIndex % 2 != 0) {
      throw new IllegalArgumentException(
          "checkpointIndex должен быть неотрицательным чётным числом");
    }
    active = validateName(active);
    if (branches.isEmpty()) {
      throw new IllegalArgumentException("должна существовать хотя бы одна ветка");
    }
    boolean activeFound = false;
    for (Branch branch : branches) {
      if (branch.name().equals(active)) {
        activeFound = true;
      }
    }
    if (!activeFound) {
      throw new IllegalArgumentException("активная ветка отсутствует в списке");
    }
    branches = List.copyOf(branches);
  }

  /** Начальное состояние: checkpoint в начале, одна пустая ветка main. */
  public static BranchData empty() {
    return new BranchData(0, DEFAULT_ACTIVE, List.of(new Branch(DEFAULT_ACTIVE, List.of())));
  }

  /** Хвост по имени ветки; null — ветки с таким именем нет. */
  public Branch branchByName(String name) {
    for (Branch branch : branches) {
      if (branch.name().equals(name)) {
        return branch;
      }
    }
    return null;
  }

  /** Валидация имени ветки: непустое, без пробелов и запрещённых символов. */
  public static String validateName(String name) {
    if (name == null || name.isBlank()) {
      throw new AgentException("Имя ветки обязательно и не может быть пустым.");
    }
    String trimmed = name.trim();
    if (trimmed.length() > MAX_NAME_LENGTH) {
      throw new AgentException("Имя ветки не длиннее " + MAX_NAME_LENGTH
          + " символов, получено: " + trimmed.length() + ".");
    }
    for (char c : trimmed.toCharArray()) {
      boolean allowed = Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
      if (!allowed || Character.isWhitespace(c)) {
        throw new AgentException("Имя ветки: только буквы, цифры, «_», «-», «.» "
            + "без пробелов. Недопустимый символ: «" + c + "».");
      }
    }
    return trimmed.toLowerCase(Locale.ROOT);
  }
}
