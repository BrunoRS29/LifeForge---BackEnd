package com.lifeforge.domain.model

import kotlinx.serialization.json.JsonElement
import java.math.BigDecimal
import java.time.Instant

/**
 * Entidades de dominio. Sao data classes puras, sem dependencia de framework.
 * As conversoes entre essas entidades, as tabelas Exposed e os DTOs ficam
 * isoladas nas camadas data/ e dto/.
 */

// Perfil de risco do usuario
enum class RiskProfile { CONSERVATIVE, MODERATE, AGGRESSIVE }

// Categoria da meta de vida
enum class GoalCategory {
    RETIREMENT,
    REAL_ESTATE,
    FINANCIAL_INDEPENDENCE,
    EDUCATION,
    TRAVEL,
    CUSTOM
}

enum class IncomeType { SALARY, BONUS, DIVIDEND, RENT, OTHER }

enum class ExpenseCategory {
    HOUSING, FOOD, TRANSPORT, HEALTH, EDUCATION, LEISURE, OTHER
}

enum class AssetType {
    FIXED_INCOME, STOCKS, REAL_ESTATE_FUND, CRYPTO, REAL_ESTATE, OTHER
}

data class User(
    val id: Long,
    val email: String,
    val name: String,
    val riskProfile: RiskProfile,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class Goal(
    val id: Long,
    val userId: Long,
    val name: String,
    val category: GoalCategory,
    val targetAmount: BigDecimal,
    val targetDate: Instant,
    val priority: Int,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class Income(
    val id: Long,
    val userId: Long,
    val source: String,
    val amount: BigDecimal,
    val incomeType: IncomeType,
    val recurring: Boolean,
    val receivedAt: Instant,
    val createdAt: Instant
)

data class Expense(
    val id: Long,
    val userId: Long,
    val description: String,
    val amount: BigDecimal,
    val category: ExpenseCategory,
    val recurring: Boolean,
    val spentAt: Instant,
    val createdAt: Instant
)

data class Asset(
    val id: Long,
    val userId: Long,
    val name: String,
    val assetType: AssetType,
    val currentValue: BigDecimal,
    val expectedReturn: BigDecimal,
    val volatility: BigDecimal,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class Simulation(
    val id: Long,
    val goalId: Long,
    val parameters: JsonElement,
    val result: JsonElement,
    val createdAt: Instant
)

data class Prediction(
    val id: Long,
    val userId: Long,
    val modelName: String,
    val input: JsonElement,
    val output: JsonElement,
    val errorMetric: BigDecimal?,
    val createdAt: Instant
)
