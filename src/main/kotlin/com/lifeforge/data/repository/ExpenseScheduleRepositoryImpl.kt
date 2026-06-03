package com.lifeforge.data.repository

import com.lifeforge.config.DatabaseFactory.dbQuery
import com.lifeforge.data.tables.ExpenseSchedules
import com.lifeforge.data.tables.Users
import com.lifeforge.domain.model.Expense
import com.lifeforge.domain.model.ExpenseCategory
import com.lifeforge.domain.model.ExpenseSchedule
import com.lifeforge.domain.model.RecurrenceCalculator
import com.lifeforge.domain.model.RecurrenceType
import com.lifeforge.domain.model.ScheduleAffect
import com.lifeforge.domain.repository.ExpenseRepository
import com.lifeforge.domain.repository.ExpenseScheduleRepository
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.time.Instant

/**
 * Schedule de despesa = "molde" recorrente. Suporta INSTALLMENTS para
 * compras parceladas (ex: 12x no cartao). Espelha [IncomeScheduleRepositoryImpl];
 * [materialize] e testavel com fake de [ExpenseRepository].
 */
class ExpenseScheduleRepositoryImpl(
    private val expenseRepository: ExpenseRepository,
) : ExpenseScheduleRepository {

    override suspend fun createAndMaterialize(
        userId: Long,
        description: String,
        amountPerOccurrence: BigDecimal,
        category: ExpenseCategory,
        recurrence: RecurrenceType,
        startDate: Instant,
        endDate: Instant?,
        installmentsTotal: Int?,
    ): ExpenseSchedule {
        val now = Instant.now()
        val schedule = dbQuery {
            val id = ExpenseSchedules.insertAndGetId { row ->
                row[ExpenseSchedules.userId] = EntityID(userId, Users)
                row[ExpenseSchedules.description] = description
                row[ExpenseSchedules.amountPerOccurrence] = amountPerOccurrence
                row[ExpenseSchedules.category] = category.name
                row[ExpenseSchedules.recurrence] = recurrence.name
                row[ExpenseSchedules.startDate] = startDate
                row[ExpenseSchedules.endDate] = endDate
                row[ExpenseSchedules.installmentsTotal] = installmentsTotal
                row[ExpenseSchedules.createdAt] = now
            }
            ExpenseSchedule(
                id = id.value, userId = userId, description = description,
                amountPerOccurrence = amountPerOccurrence, category = category,
                recurrence = recurrence, startDate = startDate, endDate = endDate,
                installmentsTotal = installmentsTotal, createdAt = now,
            )
        }
        materialize(schedule)
        return schedule
    }

    override suspend fun findAllByUser(userId: Long): List<ExpenseSchedule> = dbQuery {
        ExpenseSchedules.selectAll()
            .where { ExpenseSchedules.userId eq userId }
            .orderBy(ExpenseSchedules.createdAt, SortOrder.DESC)
            .map { it.toSchedule() }
    }

    override suspend fun findById(id: Long, userId: Long): ExpenseSchedule? = dbQuery {
        ExpenseSchedules.selectAll()
            .where { (ExpenseSchedules.id eq id) and (ExpenseSchedules.userId eq userId) }
            .singleOrNull()
            ?.toSchedule()
    }

    override suspend fun updateAndRematerialize(
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
    ): ExpenseSchedule? {
        val existing = findById(id, userId) ?: return null
        val cutoff = if (affect == ScheduleAffect.FUTURE_ONLY) Instant.now() else null

        expenseRepository.deleteByScheduleId(userId, id, futureAfter = cutoff)

        val updated = dbQuery {
            ExpenseSchedules.update({ (ExpenseSchedules.id eq id) and (ExpenseSchedules.userId eq userId) }) { row ->
                row[ExpenseSchedules.description] = description
                row[ExpenseSchedules.amountPerOccurrence] = amountPerOccurrence
                row[ExpenseSchedules.category] = category.name
                row[ExpenseSchedules.recurrence] = recurrence.name
                row[ExpenseSchedules.startDate] = startDate
                row[ExpenseSchedules.endDate] = endDate
                row[ExpenseSchedules.installmentsTotal] = installmentsTotal
            }
            ExpenseSchedule(
                id = id, userId = userId, description = description,
                amountPerOccurrence = amountPerOccurrence, category = category,
                recurrence = recurrence, startDate = startDate, endDate = endDate,
                installmentsTotal = installmentsTotal, createdAt = existing.createdAt,
            )
        }

        materialize(updated, onlyAfter = cutoff)
        return updated
    }

    override suspend fun delete(id: Long, userId: Long, affect: ScheduleAffect): Boolean {
        findById(id, userId) ?: return false
        val cutoff = if (affect == ScheduleAffect.FUTURE_ONLY) Instant.now() else null
        expenseRepository.deleteByScheduleId(userId, id, futureAfter = cutoff)
        return dbQuery {
            ExpenseSchedules.deleteWhere {
                (ExpenseSchedules.id eq id) and (ExpenseSchedules.userId eq userId)
            } > 0
        }
    }

    override suspend fun materialize(schedule: ExpenseSchedule, onlyAfter: Instant?): List<Expense> {
        val dates = RecurrenceCalculator.occurrences(
            recurrence = schedule.recurrence,
            startDate = schedule.startDate,
            endDate = schedule.endDate,
            installmentsTotal = schedule.installmentsTotal,
        ).filter { onlyAfter == null || it.isAfter(onlyAfter) }

        return dates.map { date ->
            expenseRepository.create(
                userId = schedule.userId,
                description = schedule.description,
                amount = schedule.amountPerOccurrence,
                category = schedule.category,
                // Ver IncomeScheduleRepositoryImpl: gerados nao entram no
                // somatorio "recorrente" do dashboard (evita N-contagem).
                recurring = false,
                spentAt = date,
                scheduleId = schedule.id,
            )
        }
    }

    private fun ResultRow.toSchedule(): ExpenseSchedule = ExpenseSchedule(
        id = this[ExpenseSchedules.id].value,
        userId = this[ExpenseSchedules.userId].value,
        description = this[ExpenseSchedules.description],
        amountPerOccurrence = this[ExpenseSchedules.amountPerOccurrence],
        category = ExpenseCategory.valueOf(this[ExpenseSchedules.category]),
        recurrence = RecurrenceType.valueOf(this[ExpenseSchedules.recurrence]),
        startDate = this[ExpenseSchedules.startDate],
        endDate = this[ExpenseSchedules.endDate],
        installmentsTotal = this[ExpenseSchedules.installmentsTotal],
        createdAt = this[ExpenseSchedules.createdAt],
    )
}
