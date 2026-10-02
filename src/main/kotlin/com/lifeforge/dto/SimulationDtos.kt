package com.lifeforge.dto

import kotlinx.serialization.Serializable

/**
 * DTOs para o endpoint de simulacao de Monte Carlo.
 *
 * Strings de UUID sao usadas em vez de [java.util.UUID] direto para evitar
 * dependencia de serializadores customizados do kotlinx.serialization.
 * A conversao para UUID e feita nas rotas.
 */

/**
 * Request: parametros para executar uma simulacao.
 *
 * goalId e simulationId sao strings para serem JSON-friendly.
 * Campos opcionais tem defaults sensatos.
 */
@Serializable
data class RunSimulationRequest(
    val goalId: String,  // Long da meta, como string para compatibilidade HTTP
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
    val seed: Long? = null,
    // Fontes de incerteza opcionais da Secao 6.2 (default 0 = desligadas, o
    // que preserva o comportamento e os resultados das versoes anteriores):
    // choque de despesa inesperada (Poisson + Exponencial) e variacao de renda.
    val unexpectedExpenseAnnualFrequency: Double = 0.0,
    val unexpectedExpenseMeanAmount: Double = 0.0,
    val incomeVolatilityAnnual: Double = 0.0,
)

/**
 * Response: resultado agregado da simulacao + metadados.
 */
@Serializable
data class SimulationResultResponse(
    val id: String,
    val goalId: String,
    val numSimulations: Int,
    val seed: Long,
    val targetAmount: Double,
    val successProbability: Double,
    val mean: Double,
    val median: Double,
    val standardDeviation: Double,
    val percentiles: Map<String, Double>, // chaves "P5", "P10", ... (string para JSON-friendly)
    val worstCase: Double,
    val bestCase: Double,
    val meanReal: Double,
    val histogram: List<HistogramBucketDto>,
    // Bandas de percentil mes a mes para o fan chart. Default vazio para
    // manter compatibilidade com simulacoes persistidas antes deste campo.
    val trajectory: List<TrajectoryBandDto> = emptyList(),
    val executionTimeMs: Long,
    val createdAt: String, // ISO-8601
    // Premissas da rodada (a "estrategia") - permitem comparar simulacoes lado
    // a lado. Nulo apenas para registros cujas premissas nao puderam ser lidas.
    val inputs: SimulationInputsDto? = null,
)

/**
 * Premissas com que uma simulacao foi executada. Mesmo formato para a rota
 * classica e para a calibrada por IA (que informa `calibrated = true`: o
 * aporte foi derivado das predicoes de renda e despesa).
 */
@Serializable
data class SimulationInputsDto(
    val initialCapital: Double,
    val monthlyContribution: Double,
    val expectedReturnAnnual: Double,
    val volatilityAnnual: Double,
    val horizonMonths: Int,
    val targetAmount: Double,
    val unemploymentProbAnnual: Double = 0.0,
    val unemploymentDurationMonths: Int = 6,
    val inflationAnnual: Double = 0.0,
    val unexpectedExpenseAnnualFrequency: Double = 0.0,
    val unexpectedExpenseMeanAmount: Double = 0.0,
    val incomeVolatilityAnnual: Double = 0.0,
    val numSimulations: Int = 10_000,
    val calibrated: Boolean = false,
)

@Serializable
data class HistogramBucketDto(
    val rangeStart: Double,
    val rangeEnd: Double,
    val count: Int,
)

/**
 * Banda de percentis do patrimonio em um mes da simulacao (fan chart).
 * monthIndex 0 = inicio; 1..horizonte = meses subsequentes.
 */
@Serializable
data class TrajectoryBandDto(
    val monthIndex: Int,
    val p10: Double,
    val p25: Double,
    val p50: Double,
    val p75: Double,
    val p90: Double,
)

/**
 * Response simplificada para listagem (sem o histograma, mais leve).
 */
@Serializable
data class SimulationSummaryResponse(
    val id: String,
    val goalId: String,
    val successProbability: Double,
    val mean: Double,
    val median: Double,
    val targetAmount: Double,
    val createdAt: String,
    val inputs: SimulationInputsDto? = null,
)
