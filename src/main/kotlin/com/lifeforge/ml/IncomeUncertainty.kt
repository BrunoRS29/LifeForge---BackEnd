package com.lifeforge.ml

import kotlin.math.sqrt

/**
 * Como a incerteza da renda entra na simulacao calibrada (TCC, Secao 4.7).
 *
 * A renda do mes e I_t = I + e, com e ~ N(0, sigma) e sigma = desvio-padrao
 * dos residuos da regressao de renda, em R$/mes. Com a despesa prevista fixa,
 * o aporte e A_t = I_t - D = A + e = A * (1 + e/A): a incerteza da renda vira
 * um desvio RELATIVO do aporte, sigma / A - exatamente a "variacao de renda"
 * que o motor aplica ao aporte (Normal truncada em zero, Proposta 6.2).
 *
 * Por que nao somar a volatilidade da carteira: a volatilidade do retorno
 * incide sobre TODO o patrimonio acumulado, enquanto a oscilacao da renda so
 * altera o quanto entra no mes. Tratar sigma/renda como volatilidade de retorno
 * inflava a dispersao - um historico CLT com 13o salario e dividendos chegava a
 * 66% a.a. de volatilidade "da carteira".
 *
 * O desvio relativo e limitado a [MAX_CONTRIBUTION_VARIATION_MONTHLY] (100% ao
 * mes): acima disso o truncamento em zero passaria a inflar o aporte medio
 * (com sigma = A, o vies ja e de cerca de 8%).
 *
 * Funcoes puras, testadas isoladamente.
 */
object IncomeUncertainty {

    const val MAX_CONTRIBUTION_VARIATION_MONTHLY = 1.0

    /**
     * Desvio mensal RELATIVO do aporte: sigma da renda (R$/mes) / aporte,
     * limitado a [MAX_CONTRIBUTION_VARIATION_MONTHLY]. Zero sem aporte ou sem
     * incerteza estimada.
     */
    fun contributionVariationMonthly(incomeStdMonthly: Double, contribution: Double): Double {
        if (contribution <= 0.0 || incomeStdMonthly <= 0.0) return 0.0
        return (incomeStdMonthly / contribution).coerceAtMost(MAX_CONTRIBUTION_VARIATION_MONTHLY)
    }

    /**
     * O mesmo desvio na escala ANUAL que o motor recebe em
     * `incomeVolatilityAnnual` (ele divide por raiz de 12 para voltar ao mensal).
     */
    fun contributionVariationAnnual(incomeStdMonthly: Double, contribution: Double): Double =
        contributionVariationMonthly(incomeStdMonthly, contribution) * sqrt(12.0)

    /**
     * Desvio da renda em R$/mes a partir de uma volatilidade ANUAL relativa
     * (base de referencia por vinculo de trabalho) e da renda mensal estimada.
     */
    fun incomeStdFromRelativeAnnual(relativeAnnual: Double, monthlyIncome: Double): Double =
        relativeAnnual / sqrt(12.0) * monthlyIncome
}
