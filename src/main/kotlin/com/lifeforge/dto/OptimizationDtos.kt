package com.lifeforge.dto

import kotlinx.serialization.Serializable

/**
 * DTOs para os endpoints de otimizacao financeira (Sprint 3).
 *
 * Tres familias:
 *   1. APORTE IDEAL   — fixa horizonte, descobre aporte
 *   2. PRAZO AJUSTADO — fixa aporte, descobre horizonte
 *   3. REBALANCE      — recomenda alocacao por classe de ativo
 *
 * Nao persistimos resultados nesta sprint: a otimizacao e analise sob demanda.
 * Se o cliente desejar guardar, persiste o JSON retornado.
 */

// ========== REQUESTS ==========

/**
 * Request de otimizacao por aporte ideal.
 *
 * @param goalId opcional. Se presente, valida posse pelo userId do JWT.
 *        Nao altera a otimizacao em si — targetAmount continua autoritativo.
 * @param targetSuccessProbability default 0.80 (TCC Secao 5.4)
 * @param maxContribution teto manual da busca; se omitido, motor calcula
 *        upper bound analitico com auto-doubling
 * @param simulationsPerStep default 2_000 (rapido, ~1pp de ruido amostral)
 * @param verificationSimulations default 10_000 (compromisso TCC) na rodada final
 * @param seed se omitido, usa System.currentTimeMillis() — cliente pode
 *        fixar para reprodutibilidade (uso comum em screenshots do TCC)
 */
@Serializable
data class OptimizeContributionRequest(
    val goalId: String? = null,
    val initialCapital: Double,
    val expectedReturnAnnual: Double,
    val volatilityAnnual: Double,
    val targetAmount: Double,
    val horizonMonths: Int,
    val targetSuccessProbability: Double = 0.80,
    val unemploymentProbAnnual: Double = 0.0,
    val unemploymentDurationMonths: Int = 6,
    val inflationAnnual: Double = 0.0,
    val maxContribution: Double? = null,
    val simulationsPerStep: Int = 2_000,
    val verificationSimulations: Int = 10_000,
    val seed: Long? = null,
)

/**
 * Request de otimizacao por horizonte (prazo ajustado).
 *
 * Mesmos parametros do request de aporte, exceto:
 *   - monthlyContribution (fixo)
 *   - maxHorizonMonths (teto da busca; default 50 anos)
 */
@Serializable
data class OptimizeHorizonRequest(
    val goalId: String? = null,
    val initialCapital: Double,
    val expectedReturnAnnual: Double,
    val volatilityAnnual: Double,
    val targetAmount: Double,
    val monthlyContribution: Double,
    val targetSuccessProbability: Double = 0.80,
    val unemploymentProbAnnual: Double = 0.0,
    val unemploymentDurationMonths: Int = 6,
    val inflationAnnual: Double = 0.0,
    val maxHorizonMonths: Int = 600,
    val simulationsPerStep: Int = 2_000,
    val verificationSimulations: Int = 10_000,
    val seed: Long? = null,
)

/**
 * Request de rebalanceamento de carteira.
 *
 * Independente do banco — recebe perfil + estado + horizonte e devolve
 * alocacao sugerida. Nao consulta repositorios.
 */
@Serializable
data class RebalanceRequest(
    val riskProfile: String,    // CONSERVATIVE | MODERATE | AGGRESSIVE
    val currentCapital: Double,
    val targetAmount: Double,
    val monthsToGoal: Int,
)

// ========== RESPONSES ==========

/**
 * Response unificada para os dois modos de otimizacao
 * (`/optimize/contribution` e `/optimize/horizon`).
 *
 * @param type "OPTIMAL_CONTRIBUTION" ou "OPTIMAL_HORIZON"
 * @param feasible false quando nem o upper bound atinge a probabilidade alvo
 * @param optimalValue aporte ideal (R$/mes) ou horizonte ideal (meses)
 * @param achievedProbability probabilidade medida na verificacao final
 *        (ou no melhor candidato, em casos infeasible)
 * @param terminationReason CONVERGED | MAX_ITERATIONS | INFEASIBLE_UPPER_BOUND
 *        | LOWER_BOUND_SUFFICIENT
 * @param iterations trace completo da busca binaria — util para visualizar
 *        a convergencia no app (grafico de probabilidade x candidato)
 * @param verification simulacao final com 10k iteracoes; null quando infeasible
 * @param seed seed efetivamente utilizada (preenchida mesmo se request omitiu)
 */
@Serializable
data class OptimizationResponse(
    val type: String,
    val feasible: Boolean,
    val optimalValue: Double,
    val achievedProbability: Double,
    val targetProbability: Double,
    val terminationReason: String,
    val iterations: List<IterationStepDto>,
    val verification: VerificationResultDto?,
    val executionTimeMs: Long,
    val seed: Long,
)

@Serializable
data class IterationStepDto(
    val index: Int,
    val candidate: Double,
    val measuredProbability: Double,
    val lowerBound: Double,
    val upperBound: Double,
)

/**
 * Resultado da rodada de verificacao (Monte Carlo final com N grande).
 *
 * Estrutura paralela ao [SimulationResultResponse] mas SEM id/goalId/createdAt
 * porque a otimizacao nao e persistida nesta sprint.
 */
@Serializable
data class VerificationResultDto(
    val numSimulations: Int,
    val successProbability: Double,
    val mean: Double,
    val median: Double,
    val standardDeviation: Double,
    val percentiles: Map<String, Double>, // "P5", "P10", "P50", ...
    val worstCase: Double,
    val bestCase: Double,
    val meanReal: Double,
    val histogram: List<HistogramBucketDto>,
)

/**
 * Response do endpoint de rebalanceamento.
 *
 * @param weights chave = AssetType.name (ex: "STOCKS"); valores somam 1.0
 * @param expectedReturnAnnual retorno esperado da carteira proposta
 * @param volatilityAnnual desvio padrao anualizado (assume independencia)
 * @param riskScore score em [0, 1] que produziu esta alocacao
 * @param rationale texto curto para a UI explicando a decisao
 */
@Serializable
data class RebalanceResponse(
    val weights: Map<String, Double>,
    val expectedReturnAnnual: Double,
    val volatilityAnnual: Double,
    val riskScore: Double,
    val rationale: String,
)
