package com.example;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Расчётная стоимость по фактическим токенам (usage) и ручному тарифу
 * «USD за 1 000 000 токенов». Это расчёт по тарифу, а не фактическое
 * списание провайдера. Особенности тарификации, которые модель расчёта
 * не учитывает (если они есть у провайдера): кэширование входа, отдельные
 * тарифы на reasoning, ценовые ступени и пакетные скидки.
 *
 * Денежные величины — только BigDecimal; форматирование не зависит от локали.
 */
public final class TokenCost {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    /** Точность денежного результата: 6 знаков после запятой. */
    private static final int MONEY_SCALE = 6;

    /** Стоимость указанного числа токенов по тарифу за 1 000 000 токенов. */
    public static BigDecimal perMillion(BigDecimal pricePer1M, long tokens) {
        return pricePer1M.multiply(BigDecimal.valueOf(tokens))
                .divide(ONE_MILLION, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Сумма стоимостей входа и выхода. */
    public static BigDecimal total(BigDecimal inputCost, BigDecimal outputCost) {
        return inputCost.add(outputCost);
    }

    /** Формат суммы в USD без зависимости от локали. */
    public static String formatUsd(BigDecimal amount) {
        return "$" + amount.toPlainString();
    }

    private TokenCost() {
    }
}
