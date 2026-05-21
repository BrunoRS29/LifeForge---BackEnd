package com.lifeforge.ml

import com.lifeforge.domain.model.Expense
import com.lifeforge.domain.model.Income
import com.lifeforge.domain.model.Prediction
import com.lifeforge.domain.repository.ExpenseRepository
import com.lifeforge.domain.repository.IncomeRepository
import com.lifeforge.domain.repository.PredictionRepository
import com.lifeforge.engine.montecarlo.MonteCarloParameters
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Servico que une o microsservico Python ao restante do dominio.
 *
 * Responsabilidades:
 *  1. Buscar historico no PostgreSQL via Income/ExpenseRepository
 *  2. Converter para o DTO de input do ML
 *  3. Chamar MlClient (HTTP)
 *  4. Persistir o resultado em `predictions` (auditoria + cache)
 *  5. Expor helpers de calibracao - tipicamente convertem `predicted_income
 *     - predicted_expenses` em `monthlyContribution` da MonteCarloEngine
 *
 * Vive na camada de aplicacao (pacote `ml`) porque combina varios
 * componentes de dominio. Equivalente a "use case" da Clean Architecture
 * - se quisermos formalizar mais, isto vira `RunCalibratedSimulationUseCase`,
 * etc.
 */
class MlPredictionService(
    private val mlClient: MlClient,
    private val incomeRepository: IncomeRepository,
    private val expenseRepository: ExpenseRepository,
    private val predictionRepository: PredictionRepository,
) {

    // Json reaproveitado para serializar input/output das predicoes para jsonb.
    // ignoreUnknownKeys nao eh estritamente necessario aqui, mas mantemos
    // simetria com MlClient.
    private val json = Json { ignoreUnknownKeys = true }

    // ========================================================================
    // Predict income
    // ========================================================================

    /**
     * Executa a predicao de renda para o usuario.
     *
     * - Le todo o historico via [IncomeRepository]
     * - Monta o request, chama o microsservico
     * - Persiste em `predictions` como auditoria
     * - Retorna o DTO + Prediction persistida para a rota usar
     */
    suspend fun predictIncomeFor(
        userId: Long,
        horizonMonths: Int,
    ): PredictionOutcome<IncomePredictionResponseDto> {
        val history = incomeRepository.findAllByUser(userId)
        if (history.size < MIN_INCOME_OBSERVATIONS) {
            throw MlValidationError(
                code = "INSUFFICIENT_DATA",
                message = "Historico de renda precisa de >= $MIN_INCOME_OBSERVATIONS registros " +
                    "(atualmente ${history.size}).",
            )
        }

        val request = IncomePredictionRequestDto(
            history = history.map { it.toDto() },
            horizonMonths = horizonMonths,
        )

        val response = mlClient.predictIncome(request)

        val persisted = predictionRepository.create(
            userId = userId,
            modelName = MODEL_INCOME,
            input = request.toJsonElement(),
            output = response.toJsonElement(),
            errorMetric = response.metrics.mae.toBigDecimalScaled(),
        )

        return PredictionOutcome(prediction = persisted, response = response)
    }

    // ========================================================================
    // Predict expenses
    // ========================================================================

    suspend fun predictExpensesFor(
        userId: Long,
        horizonMonths: Int = 1,
    ): PredictionOutcome<ExpensePredictionResponseDto> {
        val history = expenseRepository.findAllByUser(userId)
        if (history.size < MIN_EXPENSE_OBSERVATIONS) {
            throw MlValidationError(
                code = "INSUFFICIENT_DATA",
                message = "Historico de despesas precisa de >= $MIN_EXPENSE_OBSERVATIONS " +
                    "registros (atualmente ${history.size}).",
            )
        }

        val request = ExpensePredictionRequestDto(
            history = history.map { it.toDto() },
            horizonMonths = horizonMonths,
        )

        val response = mlClient.predictExpenses(request)

        val persisted = predictionRepository.create(
            userId = userId,
            modelName = MODEL_EXPENSE,
            input = request.toJsonElement(),
            output = response.toJsonElement(),
            errorMetric = response.metrics.mae.toBigDecimalScaled(),
        )

        return PredictionOutcome(prediction = persisted, response = response)
    }

    // ========================================================================
    // Calibracao
    // ========================================================================

    /**
     * Produz parametros calibrados para a engine Monte Carlo a partir das
     * predicoes de renda + despesa do usuario.
     *
     * Substituicoes em relacao a um run "manual":
     *  - monthlyContribution = expected_monthly_income - expected_monthly_expense
     *      Capado em zero (nao faz sentido aporte negativo - se gasta mais do
     *      que ganha, a margem de aporte e zero, nao negativa).
     *
     *  - volatilityAnnual    = max(volatilityAnnualBase, sigma_renda_anualizada)
     *      Onde sigma_renda_anualizada = residual_volatility_monthly * sqrt(12).
     *      Permite que a incerteza da renda apareca no patrimonio terminal,
     *      mesmo que o usuario tenha colocado volatilidade baixa nos ativos.
     *
     *  - expectedReturnAnnual e demais parametros permanecem como vieram do
     *    request original (sao caracteristicas do mercado, nao do usuario).
     *
     * Importante para a justificativa academica: documentar essa formula no
     * Capitulo 4 do TCC mostra exatamente o que significa "input calibrado
     * por IA" - nao eh um adjetivo de marketing, eh uma transformacao
     * matematica reproduzivel.
     */
    fun calibrate(
        base: MonteCarloParameters,
        income: IncomePredictionResponseDto,
        expense: ExpensePredictionResponseDto,
    ): CalibrationResult {
        val rawContribution = income.expectedMonthlyIncome - expense.expectedMonthlyExpense
        val calibratedContribution = rawContribution.coerceAtLeast(0.0)

        val incomeVolatilityAnnual = income.residualVolatilityMonthly *
            Math.sqrt(12.0) / maxOf(income.expectedMonthlyIncome, 1.0)
        val combinedVolatility = maxOf(base.volatilityAnnual, incomeVolatilityAnnual)

        val calibrated = base.copy(
            monthlyContribution = calibratedContribution,
            volatilityAnnual = combinedVolatility,
        )

        return CalibrationResult(
            parameters = calibrated,
            predictedMonthlyIncome = income.expectedMonthlyIncome,
            predictedMonthlyExpense = expense.expectedMonthlyExpense,
            rawContribution = rawContribution,
            appliedContribution = calibratedContribution,
            appliedVolatilityAnnual = combinedVolatility,
        )
    }

    // ========================================================================
    // Helpers internos
    // ========================================================================

    private fun Income.toDto(): IncomeObservationDto = IncomeObservationDto(
        receivedAt = DateTimeFormatter.ISO_LOCAL_DATE.format(
            receivedAt.atZone(java.time.ZoneOffset.UTC).toLocalDate()
        ),
        amount = amount.toDouble(),
        incomeType = incomeType.name,
        recurring = recurring,
    )

    private fun Expense.toDto(): ExpenseObservationDto = ExpenseObservationDto(
        spentAt = DateTimeFormatter.ISO_LOCAL_DATE.format(
            spentAt.atZone(java.time.ZoneOffset.UTC).toLocalDate()
        ),
        amount = amount.toDouble(),
        category = category.name,
        recurring = recurring,
    )

    // Serializacao para jsonb. Usa reified via inline para resolver
    // KSerializer<T> automaticamente em tempo de compilacao.
    private inline fun <reified T> T.toJsonElement(): JsonElement =
        json.parseToJsonElement(json.encodeToString(this))

    private fun Double.toBigDecimalScaled(): BigDecimal =
        BigDecimal(this).setScale(6, RoundingMode.HALF_UP)

    private companion object {
        const val MODEL_INCOME = "INCOME_REGRESSION"
        const val MODEL_EXPENSE = "EXPENSE_RANDOM_FOREST"

        // Limites espelham os defaults do Python (config.py do ml-service).
        // Validamos aqui tambem para evitar uma chamada HTTP custosa quando
        // ja sabemos que vai falhar.
        const val MIN_INCOME_OBSERVATIONS = 6
        const val MIN_EXPENSE_OBSERVATIONS = 12
    }
}

/**
 * Wrapper de retorno: junta a [Prediction] persistida com o DTO original.
 * As rotas podem precisar dos dois - id para o response, DTO para o body.
 */
data class PredictionOutcome<T>(
    val prediction: Prediction,
    val response: T,
)

/**
 * Resultado da calibracao do Monte Carlo.
 *
 * Alem dos novos [parameters], devolve os valores intermediarios para
 * que a resposta da API explique ao usuario como o calculo foi feito
 * (transparencia eh requisito do TCC).
 */
data class CalibrationResult(
    val parameters: MonteCarloParameters,
    val predictedMonthlyIncome: Double,
    val predictedMonthlyExpense: Double,
    val rawContribution: Double,        // pode ser negativo
    val appliedContribution: Double,    // sempre >= 0
    val appliedVolatilityAnnual: Double,
)
