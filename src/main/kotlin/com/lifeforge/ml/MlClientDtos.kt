package com.lifeforge.ml

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs que casam 1:1 com os schemas Pydantic do microsservico Python
 * (`ml-service/app/schemas.py`).
 *
 * Estes DTOs sao usados APENAS pelo MlClient para serializar/desserializar
 * chamadas HTTP ao microsservico. Nao sao expostos no contrato publico
 * da API Ktor - para isso ha [com.lifeforge.dto.PredictionDtos].
 *
 * Convencoes de nomeacao em snake_case com `@SerialName` para casar com o
 * JSON gerado pelo FastAPI sem ter que customizar o Json {} naming strategy
 * globalmente.
 */

// ============================================================================
// Compartilhados
// ============================================================================

@Serializable
data class ModelMetricsDto(
    val mae: Double,
    val rmse: Double,
    val r2: Double,
    @SerialName("n_train") val nTrain: Int,
    @SerialName("n_test") val nTest: Int,
)

@Serializable
data class MlErrorResponse(
    val error: String,
    val message: String,
)

// ============================================================================
// /predict/income
// ============================================================================

@Serializable
data class IncomeObservationDto(
    @SerialName("received_at") val receivedAt: String,  // ISO-8601 yyyy-MM-dd
    val amount: Double,
    @SerialName("income_type") val incomeType: String,
    val recurring: Boolean,
)

@Serializable
data class IncomePredictionRequestDto(
    val history: List<IncomeObservationDto>,
    @SerialName("horizon_months") val horizonMonths: Int,
)

@Serializable
data class IncomePredictionPointDto(
    @SerialName("month_index") val monthIndex: Int,
    @SerialName("predicted_amount") val predictedAmount: Double,
)

@Serializable
data class IncomePredictionResponseDto(
    @SerialName("model_name") val modelName: String,
    @SerialName("horizon_months") val horizonMonths: Int,
    val projection: List<IncomePredictionPointDto>,
    @SerialName("expected_monthly_income") val expectedMonthlyIncome: Double,
    @SerialName("annual_growth_rate") val annualGrowthRate: Double,
    @SerialName("residual_volatility_monthly") val residualVolatilityMonthly: Double,
    val metrics: ModelMetricsDto,
)

// ============================================================================
// /predict/expenses
// ============================================================================

@Serializable
data class ExpenseObservationDto(
    @SerialName("spent_at") val spentAt: String,
    val amount: Double,
    val category: String,
    val recurring: Boolean,
)

@Serializable
data class ExpensePredictionRequestDto(
    val history: List<ExpenseObservationDto>,
    @SerialName("horizon_months") val horizonMonths: Int = 1,
)

@Serializable
data class CategoryPredictionDto(
    val category: String,
    @SerialName("predicted_amount") val predictedAmount: Double,
)

@Serializable
data class ExpensePredictionResponseDto(
    @SerialName("model_name") val modelName: String,
    @SerialName("horizon_months") val horizonMonths: Int,
    @SerialName("by_category") val byCategory: List<CategoryPredictionDto>,
    @SerialName("expected_monthly_expense") val expectedMonthlyExpense: Double,
    val metrics: ModelMetricsDto,
)

// ============================================================================
// /predict/wealth
// ============================================================================

@Serializable
data class WealthObservationDto(
    @SerialName("month_index") val monthIndex: Int,
    val amount: Double,
)

@Serializable
data class WealthPredictionRequestDto(
    val history: List<WealthObservationDto>,
    @SerialName("horizon_months") val horizonMonths: Int = 12,
)

@Serializable
data class WealthPredictionPointDto(
    @SerialName("month_index") val monthIndex: Int,
    @SerialName("predicted_amount") val predictedAmount: Double,
)

@Serializable
data class WealthPredictionResponseDto(
    @SerialName("model_name") val modelName: String,
    @SerialName("horizon_months") val horizonMonths: Int,
    val projection: List<WealthPredictionPointDto>,
    @SerialName("expected_final_wealth") val expectedFinalWealth: Double,
    @SerialName("monthly_growth_rate") val monthlyGrowthRate: Double,
    val metrics: ModelMetricsDto,
)

// ============================================================================
// /models/metrics
// ============================================================================

@Serializable
data class ModelMetricsEntryDto(
    @SerialName("model_name") val modelName: String,
    @SerialName("fitted_at") val fittedAt: String,
    val metrics: ModelMetricsDto,
)

@Serializable
data class ModelsMetricsResponseDto(
    val entries: List<ModelMetricsEntryDto>,
)
