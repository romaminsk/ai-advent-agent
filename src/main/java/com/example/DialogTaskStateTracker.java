package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Детерминированное обновление {@link DialogTaskState} после каждого хода
 * диалога — без дополнительных запросов к модели (ответ не удваивается
 * по времени, поведение воспроизводимо в тестах).
 *
 * Правила:
 * - цель (goal) — первое содержательное сообщение беседы (приветствия
 *   пропускаются); меняется только при явной смене темы (маркеры
 *   «новая тема», «сменим тему» и т.п.), уточняющий вопрос цель не затирает;
 *   при явной смене темы списки очищаются — договорённости старой темы
 *   не протекают в новую;
 * - уточнения, ограничения и термины — только по явным формулировкам
 *   пользователя («уточняю: …», «ограничение: …», «только …»,
 *   «термины: …»); одна строка сообщения попадает не более чем в один список;
 * - открытые вопросы — вопросы, получившие локальный отказ RAG
 *   (без вызова модели) или отказ по итогам проверки;
 * - лимиты: не более {@link #MAX_ITEMS} элементов на список (при переполнении
 *   вытесняется самый старый), {@link #MAX_ITEM_CHARS} символов на элемент,
 *   {@link #MAX_GOAL_CHARS} на цель; дубликаты (без учёта регистра) не
 *   добавляются; секреты не сохраняются
 *   ({@link com.example.rag.RagQueryRewriter#looksSecret}).
 *
 * Состояние — не источник фактов о проекте: в промпт оно идёт отдельным
 * коротким блоком ({@link #promptBlock}) до RAG-контекста, в поисковый
 * запрос подмешиваются только цель и термины ({@link #searchQuery}).
 */
public final class DialogTaskStateTracker {

    /** Максимум элементов в каждом списке состояния. */
    public static final int MAX_ITEMS = 10;
    /** Максимум символов в одном элементе списка. */
    public static final int MAX_ITEM_CHARS = 200;
    /** Максимум символов в тексте цели. */
    public static final int MAX_GOAL_CHARS = 300;
    /** Максимум символов блока состояния в системном промпте. */
    public static final int MAX_PROMPT_BLOCK_CHARS = 1200;
    /** Максимум символов добавки (цель + термины) к поисковому запросу. */
    public static final int MAX_SEARCH_SUPPLEMENT_CHARS = 300;

    private static final List<String> TOPIC_CHANGE_MARKERS = List.of(
            "новая тема", "сменим тему", "смена темы", "другая задача",
            "новая задача", "переключимся", "давай про другое",
            "давай о другом", "поговорим о другом", "теперь другая тема");

    private static final List<String> TERM_MARKERS = List.of("термины", "термин");

    private static final List<String> CONSTRAINT_MARKERS = List.of(
            "ограничение", "ограничь", "ограничьтесь", "только ");

    private static final List<String> CLARIFICATION_MARKERS = List.of(
            "уточняю", "уточнение", "имею в виду", "точнее говоря", "точнее");

    private static final List<String> GREETINGS = List.of(
            "привет", "здравствуй", "здравствуйте", "добрый день", "добрый вечер",
            "доброе утро", "hello", "hi", "hey", "хай");

    private DialogTaskStateTracker() { }

    /**
     * Обновляет состояние по новому сообщению пользователя. Вызывается после
     * каждого хода (успешный ответ и локальный отказ). Исходное состояние
     * не изменяется; null трактуется как пустое.
     */
    public static DialogTaskState update(DialogTaskState current, String userMessage) {
        String text = userMessage == null ? "" : userMessage.trim();
        if (text.isEmpty()) {
            return current == null ? DialogTaskState.EMPTY : current;
        }
        DialogTaskState state = current == null ? DialogTaskState.EMPTY : current;
        String lower = text.toLowerCase(Locale.ROOT);

        if (containsMarker(lower, TOPIC_CHANGE_MARKERS)) {
            // Явная смена темы: новая цель — это сообщение; договорённости
            // прежней темы не переносятся.
            DialogTaskState restarted = DialogTaskState.EMPTY;
            return safeGoal(text) == null ? restarted : restarted.withGoal(safeGoal(text));
        }
        if (state.goal() == null && !isGreeting(lower)) {
            String goal = safeGoal(text);
            if (goal != null) {
                state = state.withGoal(goal);
            }
        }

        for (String fragment : text.split("\\n")) {
            String line = fragment.trim();
            if (line.isEmpty()) {
                continue;
            }
            String lineLower = line.toLowerCase(Locale.ROOT);
            String termMarker = firstMarker(lineLower, TERM_MARKERS);
            if (termMarker != null) {
                for (String term : afterMarker(line, lineLower, termMarker).split(",")) {
                    state = addTerm(state, term);
                }
                continue;
            }
            String constraintMarker = firstMarker(lineLower, CONSTRAINT_MARKERS);
            if (constraintMarker != null) {
                state = addConstraint(state, constraintItem(line, lineLower, constraintMarker));
                continue;
            }
            String clarificationMarker = firstMarker(lineLower, CLARIFICATION_MARKERS);
            if (clarificationMarker != null) {
                state = addClarification(state, afterMarker(line, lineLower, clarificationMarker));
            }
        }
        return state;
    }

    /**
     * Фиксирует вопрос как открытый (локальный отказ RAG без вызова модели
     * или отказ по итогам проверки). Дубликаты не добавляются.
     */
    public static DialogTaskState withOpenQuestion(DialogTaskState current, String question) {
        DialogTaskState state = current == null ? DialogTaskState.EMPTY : current;
        return state.withOpenQuestions(addItem(state.openQuestions(), question));
    }

    /**
     * Поисковый запрос для RAG в режиме чата: сам вопрос плюс цель и термины
     * из состояния (добавка ограничена {@link #MAX_SEARCH_SUPPLEMENT_CHARS}).
     * Пороги, веса и фильтры retrieval не меняются.
     */
    public static String searchQuery(DialogTaskState state, String question) {
        if (state == null || state.isEmpty()) {
            return question;
        }
        StringBuilder supplement = new StringBuilder();
        if (state.goal() != null) {
            supplement.append(state.goal());
        }
        for (String term : state.terms()) {
            if (supplement.length() > 0) {
                supplement.append(", ");
            }
            supplement.append(term);
        }
        String extra = supplement.toString().trim();
        if (extra.isEmpty()) {
            return question;
        }
        if (extra.length() > MAX_SEARCH_SUPPLEMENT_CHARS) {
            extra = extra.substring(0, MAX_SEARCH_SUPPLEMENT_CHARS).trim();
        }
        return question + "\n" + extra;
    }

    /**
     * Короткий блок состояния для системного промпта (идёт до RAG-контекста;
     * ограничен {@link #MAX_PROMPT_BLOCK_CHARS}). Пустое состояние блока не даёт.
     */
    public static String promptBlock(DialogTaskState state) {
        if (state == null || state.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder("""
                \n\nСОСТОЯНИЕ ДИАЛОГА (цель и договорённости беседы; не источник фактов о проекте — факты только из контекста):
                """);
        if (state.goal() != null) {
            block.append("Цель диалога: ").append(state.goal()).append('\n');
        }
        appendLine(block, "Уточнения пользователя", state.clarifications());
        appendLine(block, "Ограничения", state.constraints());
        appendLine(block, "Термины", state.terms());
        appendLine(block, "Открытые вопросы", state.openQuestions());
        String text = block.toString().stripTrailing();
        if (text.length() <= MAX_PROMPT_BLOCK_CHARS) {
            return text;
        }
        return text.substring(0, MAX_PROMPT_BLOCK_CHARS).stripTrailing() + "…";
    }

    /** Текстовое представление для команды /dialogstate (без вызова API). */
    public static String renderView(DialogTaskState state) {
        if (state == null || state.isEmpty()) {
            return "Состояние диалога пока пусто. Оно заполняется автоматически: "
                    + "цель — из первого сообщения; уточнения, ограничения и термины — "
                    + "по явным формулировкам («уточняю: …», «ограничение: …», "
                    + "«термины: …»). Открытые вопросы — после отказов RAG.";
        }
        StringBuilder view = new StringBuilder("Состояние диалога (память задачи):");
        if (state.goal() != null) {
            view.append("\nЦель: ").append(state.goal());
        }
        appendList(view, "Уточнения", state.clarifications());
        appendList(view, "Ограничения", state.constraints());
        appendList(view, "Термины", state.terms());
        appendList(view, "Открытые вопросы", state.openQuestions());
        view.append("\nСброс: /dialogstate clear (только состояние); "
                + "вместе с историей — /clear.");
        return view.toString();
    }

    private static void appendLine(StringBuilder block, String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        block.append(title).append(": ").append(String.join("; ", items)).append('\n');
    }

    private static void appendList(StringBuilder view, String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        view.append('\n').append(title).append(':');
        for (String item : items) {
            view.append("\n  - ").append(item);
        }
    }

    private static DialogTaskState addTerm(DialogTaskState state, String rawTerm) {
        String term = stripTrailingPunctuation(rawTerm.trim());
        return state.withTerms(addItem(state.terms(), term));
    }

    private static DialogTaskState addConstraint(DialogTaskState state, String rawItem) {
        return state.withConstraints(addItem(state.constraints(),
                stripTrailingPunctuation(rawItem == null ? "" : rawItem.trim())));
    }

    private static DialogTaskState addClarification(DialogTaskState state, String rawItem) {
        return state.withClarifications(addItem(state.clarifications(),
                stripTrailingPunctuation(rawItem == null ? "" : rawItem.trim())));
    }

    /**
     * Добавляет элемент с лимитами: пустые, длинные, дубликаты (без учёта
     * регистра) и секреты отбрасываются; при переполнении вытесняется самый
     * старый элемент (свежие договорённости важнее).
     */
    private static List<String> addItem(List<String> items, String candidate) {
        if (candidate == null || candidate.isBlank()
                || com.example.rag.RagQueryRewriter.looksSecret(candidate)) {
            return items;
        }
        String item = candidate.length() > MAX_ITEM_CHARS
                ? candidate.substring(0, MAX_ITEM_CHARS).trim() : candidate;
        if (item.isEmpty()) {
            return items;
        }
        for (String existing : items) {
            if (existing.equalsIgnoreCase(item)) {
                return items;
            }
        }
        List<String> updated = new ArrayList<>(items);
        while (updated.size() >= MAX_ITEMS) {
            updated.remove(0);
        }
        updated.add(item);
        return List.copyOf(updated);
    }

    /** Цель из текста сообщения: лимит длины, секреты не сохраняются. */
    private static String safeGoal(String text) {
        if (com.example.rag.RagQueryRewriter.looksSecret(text)) {
            return null;
        }
        String goal = text.length() > MAX_GOAL_CHARS
                ? text.substring(0, MAX_GOAL_CHARS).trim() : text;
        return goal.isEmpty() ? null : goal;
    }

    /**
     * Элемент ограничения из строки: «ограничение: X» → «X»;
     * «смотри только X» → «только X»; иначе строка целиком.
     */
    private static String constraintItem(String line, String lineLower, String marker) {
        if ("только ".equals(marker)) {
            int onlyIndex = lineLower.indexOf("только ");
            return line.substring(onlyIndex).trim();
        }
        int colon = line.indexOf(':');
        if (colon >= 0 && colon < line.length() - 1) {
            return line.substring(colon + 1).trim();
        }
        return line;
    }

    /** Текст после маркера без разделителей («уточняю: X» → «X»). */
    private static String afterMarker(String line, String lineLower, String marker) {
        int start = lineLower.indexOf(marker) + marker.length();
        while (start < line.length()) {
            char ch = line.charAt(start);
            if (ch == ':' || ch == '—' || ch == '-' || ch == ',' || Character.isWhitespace(ch)) {
                start++;
            } else {
                break;
            }
        }
        String item = line.substring(start).trim();
        return item.isEmpty() ? line : item;
    }

    private static boolean containsMarker(String lower, List<String> markers) {
        return firstMarker(lower, markers) != null;
    }

    private static String firstMarker(String lower, List<String> markers) {
        for (String marker : markers) {
            if (lower.contains(marker)) {
                return marker;
            }
        }
        return null;
    }

    /** Приветствие без содержания целью не становится. */
    private static boolean isGreeting(String lower) {
        String normalized = lower.replaceAll("[^\\p{L} ]", "").trim();
        return GREETINGS.contains(normalized);
    }

    private static String stripTrailingPunctuation(String item) {
        String out = item;
        while (!out.isEmpty() && ".;!".indexOf(out.charAt(out.length() - 1)) >= 0) {
            out = out.substring(0, out.length() - 1).trim();
        }
        return out;
    }
}
