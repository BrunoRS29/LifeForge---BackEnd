package com.lifeforge.dto

import kotlinx.serialization.Serializable

/**
 * Data Transfer Objects para a camada HTTP.
 * Usam datas/decimais como String (formato ISO-8601 e numero) para evitar
 * dependencias de serializadores customizados na primeira sprint.
 */

// ========== AUTH ==========

@Serializable
data class RegisterRequest(
    val email: String,
    val name: String,
    val password: String,
    val riskProfile: String? = null // CONSERVATIVE | MODERATE | AGGRESSIVE
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val token: String,
    val user: UserDto
)

@Serializable
data class UserDto(
    val id: Long,
    val email: String,
    val name: String,
    val riskProfile: String,
    val createdAt: String
)

/**
 * Body do PATCH /api/v1/users/me/risk-profile.
 *
 * Endpoint dedicado em vez de PATCH /me generico para tornar a intencao
 * explicita — perfil de risco e o unico campo do usuario editavel nesta
 * sprint. Nome/email teriam fluxos proprios (verificacao de unicidade,
 * confirmacao por email) que ficam fora do escopo do TCC.
 */
@Serializable
data class UpdateRiskProfileRequest(
    val riskProfile: String     // CONSERVATIVE | MODERATE | AGGRESSIVE
)

// ========== GOALS ==========

@Serializable
data class GoalRequest(
    val name: String,
    val category: String,        // GoalCategory
    val targetAmount: String,    // BigDecimal serializado como string
    val targetDate: String,      // ISO-8601 (ex: "2040-12-31T00:00:00Z")
    val priority: Int = 1
)

@Serializable
data class GoalDto(
    val id: Long,
    val userId: Long,
    val name: String,
    val category: String,
    val targetAmount: String,
    val targetDate: String,
    val priority: Int,
    val createdAt: String
)

// ========== INCOMES ==========

@Serializable
data class IncomeRequest(
    val source: String,
    val amount: String,
    val incomeType: String,     // IncomeType
    val recurring: Boolean = false,
    val receivedAt: String      // ISO-8601
)

@Serializable
data class IncomeDto(
    val id: Long,
    val userId: Long,
    val source: String,
    val amount: String,
    val incomeType: String,
    val recurring: Boolean,
    val receivedAt: String,
    val createdAt: String
)

// ========== EXPENSES ==========

@Serializable
data class ExpenseRequest(
    val description: String,
    val amount: String,
    val category: String,       // ExpenseCategory
    val recurring: Boolean = false,
    val spentAt: String         // ISO-8601
)

@Serializable
data class ExpenseDto(
    val id: Long,
    val userId: Long,
    val description: String,
    val amount: String,
    val category: String,
    val recurring: Boolean,
    val spentAt: String,
    val createdAt: String
)

// ========== INCOME SCHEDULES (Sprint 6) ==========

@Serializable
data class IncomeScheduleRequest(
    val source: String,
    val amountPerOccurrence: String,    // BigDecimal serializado como string
    val incomeType: String,             // IncomeType
    val recurrence: String,             // ONE_TIME | MONTHLY | INSTALLMENTS
    val startDate: String,              // ISO-8601
    val endDate: String? = null,        // ISO-8601, null = indefinido (MONTHLY)
    val installmentsTotal: Int? = null  // obrigatorio se INSTALLMENTS
)

@Serializable
data class IncomeScheduleDto(
    val id: Long,
    val userId: Long,
    val source: String,
    val amountPerOccurrence: String,
    val incomeType: String,
    val recurrence: String,
    val startDate: String,
    val endDate: String?,
    val installmentsTotal: Int?,
    val createdAt: String,
    val generatedCount: Int             // quantos Incomes este schedule gerou
)

// ========== EXPENSE SCHEDULES (Sprint 6) ==========

@Serializable
data class ExpenseScheduleRequest(
    val description: String,
    val amountPerOccurrence: String,
    val category: String,               // ExpenseCategory
    val recurrence: String,             // ONE_TIME | MONTHLY | INSTALLMENTS
    val startDate: String,
    val endDate: String? = null,
    val installmentsTotal: Int? = null
)

@Serializable
data class ExpenseScheduleDto(
    val id: Long,
    val userId: Long,
    val description: String,
    val amountPerOccurrence: String,
    val category: String,
    val recurrence: String,
    val startDate: String,
    val endDate: String?,
    val installmentsTotal: Int?,
    val createdAt: String,
    val generatedCount: Int
)

// ========== ASSETS ==========

@Serializable
data class AssetRequest(
    val name: String,
    val assetType: String,      // AssetType
    val currentValue: String,
    val expectedReturn: String,
    val volatility: String
)

@Serializable
data class AssetDto(
    val id: Long,
    val userId: Long,
    val name: String,
    val assetType: String,
    val currentValue: String,
    val expectedReturn: String,
    val volatility: String,
    val createdAt: String
)

// ========== ERROS ==========

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String
)
