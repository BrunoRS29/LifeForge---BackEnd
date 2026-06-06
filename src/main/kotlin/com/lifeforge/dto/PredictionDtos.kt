package com.lifeforge.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs do contrato publico de predicoes - consumidos pelo app Android.
 *
 * Diferem dos [com.lifeforge.ml.MlClientDtos]:
 *  - Adicionam ID e timestamps do banco
 *  - Padronizam camelCase no JSON (sem @SerialName em snake_case)
 *  - Filtram alguns campos internos (metricas vao para endpoint proprio)
 *
 * Manter dois conjuntos eh "feio" mas correto: o cliente do app nao deve
 * saber que existe um microsservico Python por tras (encapsulamento) e o
 * cliente Python pode evoluir sem quebrar o app.
 */

// ============================================================================
// Predict income (request publica)
// ============================================================================

/**
 * Request do POST /api/v1/predictions/income.
 *
 * Note que o usuario NAO envia historico: o backend le do banco.
 * Esse e o ponto chave - simplificamos a vida do cliente Android.
 */
@Serializable
data class PredictIncomeRequest(
    val horizonMonths: Int,
)

@Serializable
data class PredictIncomePointResponse(
    val monthIndex: Int,
    val predictedAmount: Double,
)

@Serializable
data class PredictIncomeResponse(
    val predictionId: Long,
    val modelName: String,
    val horizonMonths: Int,
    val projection: List<PredictIncomePointResponse>,
    val expectedMonthlyIncome: Double,
    val annualGrowthRate: Double,
    val residualVolatilityMonthly: Double,
    val mae: Double,
    val rmse: Double,
    val r2: Double,
    val createdAt: String,
)

// ============================================================================
// Predict expenses (request publica)
// ============================================================================

@Serializable
data class PredictExpensesRequest(
    val horizonMonths: Int = 1,
)

@Serializable
data class PredictExpensesCategoryResponse(
    val category: String,
    val predictedAmount: Double,
)

@Serializable
data class PredictExpensesResponse(
    val predictionId: Long,
    val modelName: String,
    val horizonMonths: Int,
    val byCategory: List<PredictExpensesCategoryResponse>,
    val expectedMonthlyExpense: Double,
    val mae: Double,
    val rmse: Double,
    val r2: Double,
    val createdAt: String,
)

// ============================================================================
// Predict wealth (serie temporal de patrimonio - request publica)
// ============================================================================

@Serializable
data class PredictWealthRequest(
    val horizonMonths: Int = 12,
)

/** Ponto da serie historica reconstruida (parte "real" do grafico). */
@Serializable
data class WealthHistoryPointResponse(
    val monthIndex: Int,
    val amount: Double,
)

/** Ponto da projecao futura (parte "projetada" do grafico). */
@Serializable
data class PredictWealthPointResponse(
    val monthIndex: Int,
    val predictedAmount: Double,
)

@Serializable
data class PredictWealthResponse(
    val predictionId: Long,
    val modelName: String,
    val horizonMonths: Int,
    val history: List<WealthHistoryPointResponse>,
    val projection: List<PredictWealthPointResponse>,
    val expectedFinalWealth: Double,
    val monthlyGrowthRate: Double,
    val mae: Double,
    val rmse: Double,
    val r2: Double,
    val createdAt: String,
)

// ============================================================================
// Listagem (auditoria de predicoes do usuario)
// ============================================================================

/**
 * Item da listagem GET /api/v1/predictions.
 * Nao inclui o output completo (que pode ser grande) - so metadata.
 */
@Serializable
data class PredictionSummaryResponse(
    val id: Long,
    val modelName: String,
    val errorMetric: Double?,
    val createdAt: String,
)

// ============================================================================
// Simulacao calibrada (POST /api/v1/simulation/run-calibrated)
// ============================================================================

/**
 * Request da simulacao calibrada por IA.
 *
 * Comparada ao [RunSimulationRequest], REMOVE `monthlyContribution`:
 * esse valor sera derivado das predicoes. Outros campos sao mantidos
 * (sao caracteristicas de mercado / da meta, nao da renda do usuario).
 *
 * `incomeHorizonMonths` controla o horizonte usado para PREDIZER a
 * renda - tipicamente proximo dos `horizonMonths` da simulacao, mas
 * cap-ado em 60 pelo servico Python.
 */
@Serializable
data class RunCalibratedSimulationRequest(
    val goalId: String,
    val initialCapital: Double,
    val expectedReturnAnnual: Double,
    val volatilityAnnual: Double,
    val horizonMonths: Int,
    val targetAmount: Double,
    val unemploymentProbAnnual: Double = 0.0,
    val unemploymentDurationMonths: Int = 6,
    val inflationAnnual: Double = 0.0,
    val numSimulations: Int = 10_000,
    val seed: Long? = null,
    /** Horizonte usado APENAS para calcular a media da renda projetada. */
    val incomeHorizonMonths: Int = 12,
)

/**
 * Sumario da calibracao para incluir na response.
 *
 * Os valores intermediarios ajudam o app a explicar para o usuario
 * "sua renda projetada foi X, suas despesas Y, sobra Z por mes".
 */
@Serializable
data class CalibrationSummaryResponse(
    val incomePredictionId: Long,
    val expensePredictionId: Long,
    val predictedMonthlyIncome: Double,
    val predictedMonthlyExpense: Double,
    val rawMonthlyContribution: Double,
    val appliedMonthlyContribution: Double,
    val appliedVolatilityAnnual: Double,
)

/**
 * Response da simulacao calibrada: simulacao normal + sumario da calibracao.
 *
 * Reaproveita o [SimulationResultResponse] ja existente do Sprint 2 - so
 * adicionamos a calibracao por composicao para nao duplicar campos.
 */
@Serializable
data class RunCalibratedSimulationResponse(
    val simulation: SimulationResultResponse,
    val calibration: CalibrationSummaryResponse,
)
