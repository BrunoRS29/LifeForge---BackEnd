package com.lifeforge.data.repository

import com.lifeforge.domain.model.Expense
import com.lifeforge.domain.model.ExpenseCategory
import com.lifeforge.domain.model.ExpenseSchedule
import com.lifeforge.domain.model.Income
import com.lifeforge.domain.model.IncomeSchedule
import com.lifeforge.domain.model.IncomeType
import com.lifeforge.domain.model.RecurrenceType
import com.lifeforge.domain.repository.ExpenseRepository
import com.lifeforge.domain.repository.IncomeRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest

/**
 * Testa materialize() (geracao de registros individuais) com fakes que apenas
 * registram os create(). Nao toca banco: materialize so depende do repo de
 * linha + logica pura.
 */
class ScheduleMaterializeTest : StringSpec({

    fun plusMonths(i: Int, from: Instant): Instant =
        from.atZone(ZoneOffset.UTC).plusMonths(i.toLong()).toInstant()

    class RecordingIncomeRepo : IncomeRepository {
        val created = mutableListOf<Income>()
        override suspend fun create(
            userId: Long, source: String, amount: BigDecimal,
            incomeType: IncomeType, recurring: Boolean, receivedAt: Instant, scheduleId: Long?,
        ): Income = Income(
            id = created.size + 1L, userId = userId, source = source, amount = amount,
            incomeType = incomeType, recurring = recurring, receivedAt = receivedAt,
            createdAt = Instant.now(), scheduleId = scheduleId,
        ).also { created += it }
        override suspend fun findAllByUser(userId: Long) = created
        override suspend fun findById(id: Long, userId: Long): Income? = null
        override suspend fun update(
            id: Long, userId: Long, source: String, amount: BigDecimal,
            incomeType: IncomeType, recurring: Boolean, receivedAt: Instant,
        ): Income? = null
        override suspend fun findByScheduleId(userId: Long, scheduleId: Long) =
            created.filter { it.scheduleId == scheduleId }
        override suspend fun delete(id: Long, userId: Long) = false
        override suspend fun deleteByScheduleId(userId: Long, scheduleId: Long, futureAfter: Instant?) = 0
    }

    class RecordingExpenseRepo : ExpenseRepository {
        val created = mutableListOf<Expense>()
        override suspend fun create(
            userId: Long, description: String, amount: BigDecimal,
            category: ExpenseCategory, recurring: Boolean, spentAt: Instant, scheduleId: Long?,
        ): Expense = Expense(
            id = created.size + 1L, userId = userId, description = description, amount = amount,
            category = category, recurring = recurring, spentAt = spentAt,
            createdAt = Instant.now(), scheduleId = scheduleId,
        ).also { created += it }
        override suspend fun findAllByUser(userId: Long) = created
        override suspend fun findById(id: Long, userId: Long): Expense? = null
        override suspend fun update(
            id: Long, userId: Long, description: String, amount: BigDecimal,
            category: ExpenseCategory, recurring: Boolean, spentAt: Instant,
        ): Expense? = null
        override suspend fun findByScheduleId(userId: Long, scheduleId: Long) =
            created.filter { it.scheduleId == scheduleId }
        override suspend fun delete(id: Long, userId: Long) = false
        override suspend fun deleteByScheduleId(userId: Long, scheduleId: Long, futureAfter: Instant?) = 0
    }

    "IncomeSchedule.materialize MONTHLY gera 1 registro por mes ligado ao schedule" {
        runTest {
            val repo = RecordingIncomeRepo()
            val sut = IncomeScheduleRepositoryImpl(repo)
            val now = Instant.now()
            val start = now.atZone(ZoneOffset.UTC).minusMonths(18).toInstant()
            val schedule = IncomeSchedule(
                id = 42L, userId = 1L, source = "Salario",
                amountPerOccurrence = BigDecimal("5000.00"), incomeType = IncomeType.SALARY,
                recurrence = RecurrenceType.MONTHLY, startDate = start,
                endDate = null, installmentsTotal = null, createdAt = now,
            )
            val generated = sut.materialize(schedule)
            generated.size shouldBeGreaterThanOrEqualTo 12 // cumpre o minimo da IA
            generated.all { it.scheduleId == 42L } shouldBe true
            generated.first().receivedAt shouldBe start
            repo.created shouldHaveSize generated.size
        }
    }

    "ExpenseSchedule.materialize INSTALLMENTS gera exatamente N parcelas mensais" {
        runTest {
            val repo = RecordingExpenseRepo()
            val sut = ExpenseScheduleRepositoryImpl(repo)
            val start = Instant.parse("2025-03-01T00:00:00Z")
            val schedule = ExpenseSchedule(
                id = 7L, userId = 1L, description = "Notebook 12x",
                amountPerOccurrence = BigDecimal("500.00"), category = ExpenseCategory.OTHER,
                recurrence = RecurrenceType.INSTALLMENTS, startDate = start,
                endDate = null, installmentsTotal = 12, createdAt = Instant.now(),
            )
            val generated = sut.materialize(schedule)
            generated shouldHaveSize 12
            generated.all { it.scheduleId == 7L } shouldBe true
            generated.map { it.spentAt } shouldBe (0 until 12).map { plusMonths(it, start) }
        }
    }

    "materialize com onlyAfter gera apenas ocorrencias futuras (FUTURE_ONLY)" {
        runTest {
            val repo = RecordingIncomeRepo()
            val sut = IncomeScheduleRepositoryImpl(repo)
            val now = Instant.now()
            val start = now.atZone(ZoneOffset.UTC).minusMonths(6).toInstant()
            val schedule = IncomeSchedule(
                id = 1L, userId = 1L, source = "Salario",
                amountPerOccurrence = BigDecimal("100"), incomeType = IncomeType.SALARY,
                recurrence = RecurrenceType.MONTHLY, startDate = start,
                endDate = null, installmentsTotal = null, createdAt = now,
            )
            val generated = sut.materialize(schedule, onlyAfter = now)
            generated.isNotEmpty() shouldBe true
            generated.all { it.receivedAt.isAfter(now) } shouldBe true
        }
    }
})
