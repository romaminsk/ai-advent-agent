package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Детерминированная проверка инвариантов на вводе — быстрый предварительный
 * фильтр БЕЗ вызова API. Модель с блоком «ИНВАРИАНТЫ» ({@link ContextBuilder})
 * остаётся последней линией защиты; здесь ловятся только ЯВНЫЕ конфликты.
 *
 * Правила (без LLM, локальные и честно ограниченные):
 * - маркер — запрещённое слово или фраза инварианта (явные «запрещено: …»),
 *   плюс встроенный словарь по категориям architecture/stack;
 * - матчинг по границам слов: запрос и маркер разбиваются на токены
 *   (буквы/цифры), маркер совпадает по непрерывной последовательности
 *   токенов, поэтому «springfield» не ловится как «spring», «spring-boot»
 *   ловится как «spring boot»;
 * - блокируется только императив: если в запросе нет побуждения
 *   («добавь», «подключи», «перепиши на» и т.п.), это обсуждение,
 *   а не запрос на нарушение — модель решает сама;
 * - вопрос (знак «?») не блокируется: почему нельзя X — обсуждение;
 * - отрицание не блокируется: «не используй spring» — не запрос на ввод
 *   spring; проверяется «не» непосредственно перед императивом;
 * - встроенный словарь не применяется к маркеру, если сам текст инварианта
   содержит это слово (тогда это разрешённая часть стека; явные маркеры —
 *   авторитетные и не фильтруются);
 * - ложные срабатывания главнее пропуска: если сомневаетесь — не блокируйте,
 *   отдайте модели.
 */
final class InvariantGuard {

    /** Один конфликт: нарушенный инвариант и сработавшие маркеры. */
    record Conflict(Invariant invariant, List<String> matchedMarkers) {
    }

    /**
     * Встроенный словарь по категориям. business — не выводится из текста
     * автоматически: только явно заданные маркеры.
     */
    private static final Map<String, List<String>> BUILTIN_MARKERS = Map.of(
            "architecture", List.of("spring", "spring boot", "hibernate",
                    "jpa", "orm"),
            "stack", List.of("gson", "fastjson", "log4j", "guava"));

    /**
     * Императивы: маркер + императив = склонение нарушить рамку («добавь
     * spring»). Список умеренный: сомнительные глаголы не включаются —
     * такой запрос молча уйдёт модели.
     */
    private static final List<String> IMPERATIVES = List.of(
            "добавь", "добавьте", "добавить", "добавляем",
            "используй", "использовать", "используйте",
            "примени", "применить", "применяй",
            "подключи", "подключить", "подключите",
            "включи", "включить", "включите",
            "внедри", "внедрить", "внедрите",
            "вставь", "вставить", "вставьте",
            "перепиши", "переписать", "перепишите",
            "замени", "заменить", "замени на",
            "переведи на", "перенеси на", "мигрируй на",
            "возьми", "взять", "возьмите",
            "поставь", "поставить", "поставьте",
            "установи", "установить", "установите",
            "сделай на", "сделать на", "сделайте на",
            "напиши на", "написать на", "напишите на",
            "заюзай", "заюзать");

    /**
     * Единственный вход: запрос + инварианты → конфликты (пусто — пропускаем
     * к модели). Императив обязателен; маркеры совпадают по границам слов.
     */
    static List<InvariantGuard.Conflict> check(String userMessage,
                                               List<Invariant> invariants) {
        if (userMessage == null || userMessage.isBlank() || invariants == null
                || invariants.isEmpty()) {
            return List.of();
        }
        // Вопрос — обсуждение, а не запрос на нарушение; не блокируем.
        if (userMessage.contains("?")) {
            return List.of();
        }
        String lower = userMessage.toLowerCase(Locale.ROOT);
        List<String> tokens = tokenize(lower);

        // Императив обязателен: без него это обсуждение/пересказ, не запрос.
        if (!hasActiveImperative(tokens)) {
            return List.of();
        }

        List<InvariantGuard.Conflict> conflicts = new ArrayList<>();
        for (Invariant invariant : invariants) {
            // Явные маркеры приоритетны; затем встроенный словарь категории.
            List<String> effectiveMarkers = new ArrayList<>(
                    invariant.forbiddenMarkers());
            List<String> builtin = BUILTIN_MARKERS.get(invariant.category());
            if (builtin != null) {
                List<String> invariantTextTokens =
                        tokenize(invariant.text());
                for (String marker : builtin) {
                    // Если инвариант сам разрешает технологию (текст содержит
                    // слово), встроенный маркер не применяется — ложных
                    // блоков нет. Явные маркеры — авторитетные и не фильтруются.
                    if (!invariant.forbiddenMarkers().contains(marker)
                            && !textContainsToken(invariantTextTokens, marker)) {
                        effectiveMarkers.add(marker);
                    }
                }
            }
            List<String> matched = new ArrayList<>();
            for (String marker : effectiveMarkers) {
                if (textContainsToken(tokens, marker)) {
                    if (!matched.contains(marker)) {
                        matched.add(marker);
                    }
                }
            }
            if (!matched.isEmpty()) {
                conflicts.add(new InvariantGuard.Conflict(invariant, matched));
            }
        }
        return conflicts;
    }

    /**
     * Есть ли в запросе императив без отрицания? Отрицание при императиве
     * («не используй») — не запрос на нарушение.
     */
    private static boolean hasActiveImperative(List<String> tokens) {
        for (String imperative : IMPERATIVES) {
            List<String> imperativeTokens = tokenize(imperative);
            int index = indexMatches(tokens, imperativeTokens, 0);
            while (index >= 0 && index + imperativeTokens.size() <= tokens.size()) {
                boolean negated = index > 0
                        && tokens.get(index - 1).equals("не");
                if (!negated) {
                    return true;
                }
                int next = indexMatches(tokens, imperativeTokens, index + 1);
                if (next < 0) {
                    break;
                }
                index = next;
            }
        }
        return false;
    }

    /**
     * Совпадение маркера-фразы по границам слов: последовательность токенов
     * маркера встречается как подряд идущие токены запроса.
     */
    private static boolean textContainsToken(List<String> tokens, String marker) {
        List<String> markerTokens = tokenize(marker);
        return indexMatches(tokens, markerTokens, 0) >= 0;
    }

    /** Первый индекс последовательности token from позиции; -1 — нет. */
    private static int indexMatches(List<String> tokens,
                                    List<String> markerTokens, int from) {
        if (markerTokens.isEmpty() || tokens.size() - from < markerTokens.size()) {
            return -1;
        }
        for (int i = from; i <= tokens.size() - markerTokens.size(); i++) {
            boolean matched = true;
            for (int j = 0; j < markerTokens.size(); j++) {
                if (!tokens.get(i + j).equals(markerTokens.get(j))) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return i;
            }
        }
        return -1;
    }

    /** Токены: непрерывные последовательности букв и цифр («spring-boot» → 2). */
    static List<String> tokenize(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                current.append(c);
            } else if (current.length() > 0) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private InvariantGuard() {
    }
}
