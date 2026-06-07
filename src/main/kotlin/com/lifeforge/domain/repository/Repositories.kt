package com.lifeforge.domain.repository

import com.lifeforge.domain.model.Asset
import com.lifeforge.domain.model.AssetType
import com.lifeforge.domain.model.Expense
import com.lifeforge.domain.model.ExpenseCategory
import com.lifeforge.domain.model.ExpenseSchedule
import com.lifeforge.domain.model.Goal
import com.lifeforge.domain.model.GoalCategory
import com.lifeforge.domain.model.Income
import com.lifeforge.domain.model.IncomeSchedule
import com.lifeforge.domain.model.IncomeType
import com.lifeforge.domain.model.RecurrenceType
import com.lifeforge.domain.model.RiskProfile
import com.lifeforge.domain.model.ScheduleAffect
import com.lifeforge.domain.model.User
import kotlinx.serialization.json.JsonElement
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
// User profile (parametros estendidos, blob JSON 1:1 com o usuario)
// ============================================================================

interface UserProfileRepository {
    /** Blob JSON do perfil do usuario, ou null se ainda nao foi preenchido. */
    suspend fun get(userId: Long): JsonElement?

    /** Cria ou substitui o perfil do usuario. Retorna o blob salvo. */
    suspend fun upsert(userId: Long, data: JsonElement): JsonElement
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
        scheduleId: Long? = null,
    ): Income

    /** Atualiza os campos editaveis de um registro (preserva scheduleId/createdAt). */
    suspend fun update(
        id: Long,
        userId: Long,
        source: String,
        amount: BigDecimal,
        incomeType: IncomeType,
        recurring: Boolean,
        receivedAt: Instant,
    ): Income?

    suspend fun findAllByUser(userId: Long): List<Income>
    suspend fun findById(id: Long, userId: Long): Income?
    suspend fun findByScheduleId(userId: Long, scheduleId: Long): List<Income>
    suspend fun delete(id: Long, userId: Long): Boolean

    /** Remove TODAS as receitas do usuario. Retorna a quantidade removida. */
    suspend fun deleteAllByUser(userId: Long): Int

    /**
     * Remove registros gerados por um schedule.
     *  - futureAfter == null -> remove TODOS os vinculados ao schedule
     *  - futureAfter != null -> remove apenas os com receivedAt > futureAfter
     * Retorna a quantidade removida.
     */
    suspend fun deleteByScheduleId(userId: Long, scheduleId: Long, futureAfter: Instant?): Int
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
        scheduleId: Long? = null,
    ): Expense

    /** Atualiza os campos editaveis de um registro (preserva scheduleId/createdAt). */
    suspend fun update(
        id: Long,
        userId: Long,
        description: String,
        amount: BigDecimal,
        category: ExpenseCategory,
        recurring: Boolean,
        spentAt: Instant,
    ): Expense?

    suspend fun findAllByUser(userId: Long): List<Expense>
    suspend fun findById(id: Long, userId: Long): Expense?
    suspend fun findByScheduleId(userId: Long, scheduleId: Long): List<Expense>
    suspend fun delete(id: Long, userId: Long): Boolean

    /** Remove TODAS as despesas do usuario. Retorna a quantidade removida. */
    suspend fun deleteAllByUser(userId: Long): Int

    /** Ver [IncomeRepository.deleteByScheduleId]: futureAfter==null remove todos. */
    suspend fun deleteByScheduleId(userId: Long, scheduleId: Long, futureAfter: Instant?): Int
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

// ============================================================================
// Income schedules (templates recorrentes que geram Incomes)
// ============================================================================

interface IncomeScheduleRepository {
    /** Persiste o schedule e materializa os Incomes correspondentes. */
    suspend fun createAndMaterialize(
        userId: Long,
        source: String,
        amountPerOccurrence: BigDecimal,
        incomeType: IncomeType,
        recurrence: RecurrenceType,
        startDate: Instant,
        endDate: Instant?,
        installmentsTotal: Int?,
    ): IncomeSchedule

    suspend fun findAllByUser(userId: Long): List<IncomeSchedule>
    suspend fun findById(id: Long, userId: Long): IncomeSchedule?

    /** Atualiza o schedule e regenera os Incomes (todos ou so futuros). */
    suspend fun updateAndRematerialize(
        id: Long,
        userId: Long,
        source: String,
        amountPerOccurrence: BigDecimal,
        incomeType: IncomeType,
        recurrence: RecurrenceType,
        startDate: Instant,
        endDate: Instant?,
        installmentsTotal: Int?,
        affect: ScheduleAffect,
    ): IncomeSchedule?

    /** Remove o schedule; FUTURE_ONLY mantem registros passados como avulsos. */
    suspend fun delete(id: Long, userId: Long, affect: ScheduleAffect): Boolean

    /** Gera os Incomes individuais do schedule (opcionalmente so apos `onlyAfter`). */
    suspend fun materialize(schedule: IncomeSchedule, onlyAfter: Instant? = null): List<Income>
}

// ============================================================================
// Expense schedules (templates recorrentes que geram Expenses)
// ============================================================================

interface ExpenseScheduleRepository {
    suspend fun createAndMaterialize(
        userId: Long,
        description: String,
        amountPerOccurrence: BigDecimal,
        category: ExpenseCategory,
        recurrence: RecurrenceType,
        startDate: Instant,
        endDate: Instant?,
        installmentsTotal: Int?,
    ): ExpenseSchedule

    suspend fun findAllByUser(userId: Long): List<ExpenseSchedule>
    suspend fun findById(id: Long, userId: Long): ExpenseSchedule?

    suspend fun updateAndRematerialize(
        id: Long,
        userId: Long,
        description: String,
        amountPerOccurrence: BigDecimal,
        category: ExpenseCategory,
        recurrence: RecurrenceType,
        startDate: Instant,
        endDate: Instant?,
        installmentsTotal: Int?,
        affect: ScheduleAffect,
    ): ExpenseSchedule?

    suspend fun delete(id: Long, userId: Long, affect: ScheduleAffect): Boolean

    suspend fun materialize(schedule: ExpenseSchedule, onlyAfter: Instant? = null): List<Expense>
}
