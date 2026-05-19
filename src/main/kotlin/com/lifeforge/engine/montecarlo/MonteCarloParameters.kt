package com.lifeforge.engine.montecarlo

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Parametros de entrada para a simulacao de Monte Carlo.
 *
 * Convencao: TODOS os parametros financeiros sao expressos em base ANUAL.
 * A engine faz a conversao para base mensal internamente, pois e mais
 * intuitivo o usuario pensar em "8% ao ano" do que "0.643% ao mes".
 *
 * @param initialCapital patrimonio inicial em moeda corrente
 * @param monthlyContribution aporte mensal regular
 * @param expectedReturnAnnual retorno esperado anual da carteira (ex: 0.08 para 8%)
 * @param volatilityAnnual desvio padrao anual dos retornos (ex: 0.15 para 15%)
 * @param horizonMonths horizonte de simulacao em meses
 * @param targetAmount meta a ser atingida (usado para calcular probabilidade de sucesso)
 * @param unemploymentProbAnnual probabilidade anual de evento de desemprego (default: 0)
 * @param unemploymentDurationMonths duracao tipica do desemprego em meses (default: 6)
 * @param inflationAnnual inflacao anual para deflacionar resultado (default: 0)
 * @param numSimulations quantidade de cenarios a simular (default: 10_000, minimo do TCC)
 * @param seed semente para reprodutibilidade (default: timestamp atual)
 */
data class MonteCarloParameters(
    val initialCapital: Double,
    val monthlyContribution: Double,
    val expectedReturnAnnual: Double,
    val volatilityAnnual: Double,
    val horizonMonths: Int,
    val targetAmount: Double,
    val unemploymentProbAnnual: Double = 0.0,
    val unemploymentDurationMonths: Int = 6,
    val inflationAnnual: Double = 0.0,
    val numSimulations: Int = 10_000,
    val seed: Long = System.currentTimeMillis(),
) {
    init {
        require(initialCapital >= 0.0) { "initialCapital deve ser >= 0" }
        require(monthlyContribution >= 0.0) { "monthlyContribution deve ser >= 0" }
        require(volatilityAnnual >= 0.0) { "volatilityAnnual deve ser >= 0" }
        require(horizonMonths > 0) { "horizonMonths deve ser > 0" }
        require(targetAmount > 0.0) { "targetAmount deve ser > 0" }
        require(unemploymentProbAnnual in 0.0..1.0) {
            "unemploymentProbAnnual deve estar em [0, 1]"
        }
        require(unemploymentDurationMonths >= 0) {
            "unemploymentDurationMonths deve ser >= 0"
        }
        require(numSimulations > 0) { "numSimulations deve ser > 0" }
    }

    // Conversoes anual -> mensal usadas pela engine.
    // Encapsuladas como properties para nao recalcular em cada iteracao.

    /**
     * Retorno mensal efetivo equivalente ao retorno anual.
     * Formula: r_m = (1 + r_a)^(1/12) - 1
     */
    val expectedReturnMonthly: Double
        get() = (1.0 + expectedReturnAnnual).pow(1.0 / 12.0) - 1.0

    /**
     * Volatilidade mensal a partir da anual, assumindo retornos i.i.d.
     * Formula: sigma_m = sigma_a / sqrt(12)
     */
    val volatilityMonthly: Double
        get() = volatilityAnnual / sqrt(12.0)

    /**
     * Probabilidade mensal de desemprego, a partir da anual.
     * Aproximacao: p_m ~ p_a / 12 (valida para p_a pequeno).
     */
    val unemploymentProbMonthly: Double
        get() = unemploymentProbAnnual / 12.0

    /**
     * Fator de deflacao para converter valor nominal em valor real ao final do horizonte.
     * Formula: deflator = (1 + inflacao_anual)^(anos)
     */
    val inflationDeflator: Double
        get() = (1.0 + inflationAnnual).pow(horizonMonths / 12.0)
}
