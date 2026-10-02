package com.lifeforge.routes

import com.lifeforge.domain.model.IncomeType
import com.lifeforge.domain.model.MonthlyAmounts
import com.lifeforge.domain.repository.AssetRepository
import com.lifeforge.domain.repository.ExpenseRepository
import com.lifeforge.domain.repository.GoalRepository
import com.lifeforge.domain.repository.IncomeRepository
import com.lifeforge.domain.repository.UserProfileRepository
import com.lifeforge.dto.DashboardResponse
import com.lifeforge.ml.ColdStartCalibration
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.ZoneId

/**
 * Painel consolidado (GET /api/v1/dashboard) - Secao 10 da proposta. Agrega no
 * servidor os mesmos totais que o app compoe no cliente, util para consumo
 * externo e para inspecao/transparencia.
 *
 * - Renda mensal = salario + renda estimada dos ativos (currentValue *
 *   expectedReturn anual / 12). O salario e o informado no perfil estendido
 *   (fonte de verdade); sem ele, o inferido dos lancamentos de salario.
 * - Despesa mensal = cada serie recorrente ativa uma vez + media recente dos
 *   lancamentos pontuais (ver [MonthlyAmounts]).
 */
fun Route.dashboardRoutes(
    incomeRepository: IncomeRepository,
    expenseRepository: ExpenseRepository,
    assetRepository: AssetRepository,
    goalRepository: GoalRepository,
    userProfileRepository: UserProfileRepository,
    clock: Clock = Clock.systemUTC(),
) {
    authenticate("auth-jwt") {
        get("/api/v1/dashboard") {
            val userId = call.userId()
            val incomes = incomeRepository.findAllByUser(userId)
            val expenses = expenseRepository.findAllByUser(userId)
            val assets = assetRepository.findAllByUser(userId)
            val goals = goalRepository.findAllByUser(userId)
            val profile = userProfileRepository.get(userId)
            val now = clock.instant()

            val zero = BigDecimal.ZERO
            val totalAssets = assets.fold(zero) { acc, a -> acc + a.currentValue }
            val monthlyAssetIncome = assets.fold(zero) { acc, a ->
                acc + a.currentValue.multiply(a.expectedReturn)
                    .divide(BigDecimal(12), 2, RoundingMode.HALF_UP)
            }
            val profileSalary = ColdStartCalibration
                .parseAmount(ColdStartCalibration.profileString(profile, "monthlySalary"))
                ?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP) }
            val inferredSalary = MonthlyAmounts.monthly(
                incomes.filter { it.incomeType == IncomeType.SALARY }.map {
                    MonthlyAmounts.Entry(
                        key = MonthlyAmounts.seriesKey(it.source, it.incomeType.name),
                        amount = it.amount,
                        at = it.receivedAt,
                        recurring = it.recurring,
                    )
                },
                now,
                ZONE,
            )
            val monthlyIncome = (profileSalary ?: inferredSalary) + monthlyAssetIncome
            val monthlyExpenses = MonthlyAmounts.monthly(
                expenses.map {
                    MonthlyAmounts.Entry(
                        key = MonthlyAmounts.seriesKey(it.description, it.category.name),
                        amount = it.amount,
                        at = it.spentAt,
                        recurring = it.recurring,
                    )
                },
                now,
                ZONE,
            )
            val savingsRate = if (monthlyIncome > zero) {
                (monthlyIncome - monthlyExpenses)
                    .divide(monthlyIncome, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal(100))
            } else {
                zero
            }
            val totalGoalTarget = goals.fold(zero) { acc, g -> acc + g.targetAmount }

            call.respond(
                DashboardResponse(
                    totalAssets = totalAssets.toPlainString(),
                    monthlyIncome = monthlyIncome.toPlainString(),
                    monthlyExpenses = monthlyExpenses.toPlainString(),
                    savingsRate = savingsRate.toPlainString(),
                    goalsCount = goals.size,
                    totalGoalTarget = totalGoalTarget.toPlainString(),
                )
            )
        }
    }
}

/** Mesmo fuso do app: o "mes corrente" e o do usuario brasileiro. */
private val ZONE: ZoneId = ZoneId.of("America/Sao_Paulo")
