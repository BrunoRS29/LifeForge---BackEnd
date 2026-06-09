package com.lifeforge.routes

import com.lifeforge.domain.repository.AssetRepository
import com.lifeforge.domain.repository.ExpenseRepository
import com.lifeforge.domain.repository.GoalRepository
import com.lifeforge.domain.repository.IncomeRepository
import com.lifeforge.dto.DashboardResponse
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Painel consolidado (GET /api/v1/dashboard) - Secao 10 da proposta. Agrega no
 * servidor os totais que o app tambem compoe no cliente, util para consumo
 * externo e para inspecao/transparencia.
 *
 * A renda mensal considera os lancamentos RECORRENTES de receita mais a renda
 * estimada dos ativos (currentValue * expectedReturn anual / 12); as despesas,
 * os lancamentos recorrentes de despesa.
 */
fun Route.dashboardRoutes(
    incomeRepository: IncomeRepository,
    expenseRepository: ExpenseRepository,
    assetRepository: AssetRepository,
    goalRepository: GoalRepository,
) {
    authenticate("auth-jwt") {
        get("/api/v1/dashboard") {
            val userId = call.userId()
            val incomes = incomeRepository.findAllByUser(userId)
            val expenses = expenseRepository.findAllByUser(userId)
            val assets = assetRepository.findAllByUser(userId)
            val goals = goalRepository.findAllByUser(userId)

            val zero = BigDecimal.ZERO
            val totalAssets = assets.fold(zero) { acc, a -> acc + a.currentValue }
            val monthlyAssetIncome = assets.fold(zero) { acc, a ->
                acc + a.currentValue.multiply(a.expectedReturn)
                    .divide(BigDecimal(12), 2, RoundingMode.HALF_UP)
            }
            val monthlyIncome = incomes.filter { it.recurring }
                .fold(zero) { acc, i -> acc + i.amount } + monthlyAssetIncome
            val monthlyExpenses = expenses.filter { it.recurring }
                .fold(zero) { acc, e -> acc + e.amount }
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
