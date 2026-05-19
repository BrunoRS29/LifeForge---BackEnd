package com.lifeforge.engine.optimization

import com.lifeforge.engine.montecarlo.MonteCarloResult

/**
 * Resultado de uma execucao do [OptimizationEngine].
 *
 * Inclui o trace completo da busca binaria ([iterations]) — util para a
 * apresentacao do TCC, ja que permite plotar a convergencia da busca e
 * demonstrar visualmente o funcionamento do algoritmo.
 *
 * O campo [verification] traz a simulacao final completa (10k iteracoes por
 * default) executada com o valor otimo encontrado, contendo histograma e
 * percentis para visualizacao no app.
 */
data class OptimizationResult(
    val type: OptimizationType,
    val feasible: Boolean,
    val optimalValue: Double,
    val achievedProbability: Double,
    val targetProbability: Double,
    val iterations: List<IterationStep>,
    val verification: MonteCarloResult?,
    val executionTimeMs: Long,
    val terminationReason: TerminationReason,
) {
    /**
     * Quando feasible = false e util saber o que foi tentado:
     * o melhor (maior probabilidade) candidato testado.
     */
    val bestProbabilityFound: Double
        get() = iterations.maxOfOrNull { it.measuredProbability } ?: 0.0
}

/**
 * Modo de otimizacao: descobrir o aporte mensal otimo OU o horizonte otimo.
 */
enum class OptimizationType {
    OPTIMAL_CONTRIBUTION,
    OPTIMAL_HORIZON,
}

/**
 * Razao pela qual a busca terminou. Util para diagnosticar comportamento.
 */
enum class TerminationReason {
    /** Convergiu dentro da tolerancia. */
    CONVERGED,
    /** Atingiu o numero maximo de iteracoes sem convergir. */
    MAX_ITERATIONS,
    /** Mesmo o limite superior nao atinge a probabilidade-alvo. */
    INFEASIBLE_UPPER_BOUND,
    /** Limite inferior ja atinge a probabilidade-alvo (otimo trivial). */
    LOWER_BOUND_SUFFICIENT,
}

/**
 * Snapshot de um passo da busca binaria. Permite reconstruir a convergencia.
 *
 * @param index numero do passo (0-indexed)
 * @param candidate valor do candidato testado neste passo (aporte ou meses)
 * @param measuredProbability probabilidade de sucesso medida na simulacao
 * @param lowerBound limite inferior corrente da busca
 * @param upperBound limite superior corrente da busca
 */
data class IterationStep(
    val index: Int,
    val candidate: Double,
    val measuredProbability: Double,
    val lowerBound: Double,
    val upperBound: Double,
)
