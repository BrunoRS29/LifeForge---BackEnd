package com.lifeforge.domain.repository

import com.lifeforge.domain.model.Asset
import com.lifeforge.domain.model.AssetType
import com.lifeforge.domain.model.Expense
import com.lifeforge.domain.model.ExpenseCategory
import com.lifeforge.domain.model.Goal
import com.lifeforge.domain.model.GoalCategory
import com.lifeforge.domain.model.Income
import com.lifeforge.domain.model.IncomeType
import com.lifeforge.domain.model.RiskProfile
import com.lifeforge.domain.model.User
import java.math.BigDecimal
import java.time.Instant

/**
 * Contratos de persistencia da camada de dominio.
 *
 * Implementacoes concretas com Exposed/JDBC ficam em
 * [com.lifeforge.data.repository] (Clean Architecture: dominio nao
 * conhece infraestrutura).
 *
 * Convencao geral: operacoes que afetam dados de um usuario recebem
 * `userId` explicitamente para garantir o tenant isolation no banco —
 * cada `WHERE` inclui `user_id = :userId` na consulta.
 */

// ============================================================================
// User
// ============================================================================

interface UserRepository {
    suspend fun findById(id: Long): User?
    suspend fun findByEmail(email: String): User?

    /**
     * Retorna o par (User, passwordHash) para autenticacao. `null` se
     * o email nao existir. Separado de [findByEmail] para que rotas
     * que so precisam dos dados do usuario nao carreguem o hash.
     */
    suspend fun findPasswordHashByEmail(email: String): Pair<User, String>?

    suspend fun create(
        email: String,
        name: String,
        passwordHash: String,
        riskProfile: RiskProfile,
    ): User

    /**
     * Atualiza apenas o campo `riskProfile` do usuario.
     * Retorna `true` se a linha foi atualizada, `false` se o usuario
     * nao existir.
     */
    suspend fun updateRiskProfile(userId: Long, profile: RiskProfile): Boolean
}

// ============================================================================
// Goals
// ============================================================================

interface GoalRepository {
    suspend fun findAllByUser(userId: Long): List<Goal>
    suspend fun findById(id: Long, userId: Long): Goal?

    suspend fun create(
        userId: Long,
        name: String,
        category: GoalCategory,
        targetAmount: BigDecimal,
        targetDate: Instant,
        priority: Int,
    ): Goal

    suspend fun update(
        id: Long,
        userId: Long,
        name: String,
        category: GoalCategory,
        targetAmount: BigDecimal,
        targetDate: Instant,
        priority: Int,
    ): Goal?

    suspend fun delete(id: Long, userId: Long): Boolean
}

// ============================================================================
// Incomes
// ============================================================================

interface IncomeRepository {
    suspend fun create(
        userId: Long,
        source: String,
        amount: BigDecimal,
        incomeType: IncomeType,
        recurring: Boolean,
        receivedAt: Instant,
    ): Income

    suspend fun findAllByUser(userId: Long): List<Income>
    suspend fun findById(id: Long, userId: Long): Income?
    suspend fun delete(id: Long, userId: Long): Boolean
}

// ============================================================================
// Expenses
// ============================================================================

interface ExpenseRepository {
    suspend fun create(
        userId: Long,
        description: String,
        amount: BigDecimal,
        category: ExpenseCategory,
        recurring: Boolean,
        spentAt: Instant,
    ): Expense

    suspend fun findAllByUser(userId: Long): List<Expense>
    suspend fun findById(id: Long, userId: Long): Expense?
    suspend fun delete(id: Long, userId: Long): Boolean
}

// ============================================================================
// Assets
// ============================================================================

interface AssetRepository {
    suspend fun create(
        userId: Long,
        name: String,
        assetType: AssetType,
        currentValue: BigDecimal,
        expectedReturn: BigDecimal,
        volatility: BigDecimal,
    ): Asset

    suspend fun findAllByUser(userId: Long): List<Asset>
    suspend fun findById(id: Long, userId: Long): Asset?

    suspend fun update(
        id: Long,
        userId: Long,
        name: String,
        assetType: AssetType,
        currentValue: BigDecimal,
        expectedReturn: BigDecimal,
        volatility: BigDecimal,
    ): Asset?

    suspend fun delete(id: Long, userId: Long): Boolean
}
