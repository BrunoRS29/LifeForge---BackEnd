package com.lifeforge.data.repository

import com.lifeforge.config.DatabaseFactory.dbQuery
import com.lifeforge.data.tables.ExpenseSchedules
import com.lifeforge.data.tables.Expenses
import com.lifeforge.data.tables.Users
import com.lifeforge.domain.model.Expense
import com.lifeforge.domain.model.ExpenseCategory
import com.lifeforge.domain.repository.ExpenseRepository
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.time.Instant

class ExpenseRepositoryImpl : ExpenseRepository {

    override suspend fun create(
        userId: Long,
        description: String,
        amount: BigDecimal,
        category: ExpenseCategory,
        recurring: Boolean,
        spentAt: Instant,
        scheduleId: Long?
    ): Expense = dbQuery {
        val now = Instant.now()
        val id = Expenses.insertAndGetId { row ->
            row[Expenses.userId] = EntityID(userId, Users)
            row[Expenses.description] = description
            row[Expenses.amount] = amount
            row[Expenses.category] = category.name
            row[Expenses.recurring] = recurring
            row[Expenses.spentAt] = spentAt
            row[Expenses.scheduleId] = scheduleId?.let { EntityID(it, ExpenseSchedules) }
            row[Expenses.createdAt] = now
        }
        Expense(
            id = id.value,
            userId = userId,
            description = description,
            amount = amount,
            category = category,
            recurring = recurring,
            spentAt = spentAt,
            createdAt = now,
            scheduleId = scheduleId
        )
    }

    override suspend fun update(
        id: Long,
        userId: Long,
        description: String,
        amount: BigDecimal,
        category: ExpenseCategory,
        recurring: Boolean,
        spentAt: Instant,
    ): Expense? = dbQuery {
        val updated = Expenses.update({ (Expenses.id eq id) and (Expenses.userId eq userId) }) {
            it[Expenses.description] = description
            it[Expenses.amount] = amount
            it[Expenses.category] = category.name
            it[Expenses.recurring] = recurring
            it[Expenses.spentAt] = spentAt
        }
        if (updated > 0) {
            Expenses.selectAll()
                .where { (Expenses.id eq id) and (Expenses.userId eq userId) }
                .singleOrNull()
                ?.toExpense()
        } else null
    }

    override suspend fun findAllByUser(userId: Long): List<Expense> = dbQuery {
        Expenses.selectAll()
            .where { Expenses.userId eq userId }
            .orderBy(Expenses.spentAt to SortOrder.DESC)
            .map { it.toExpense() }
    }

    override suspend fun findById(id: Long, userId: Long): Expense? = dbQuery {
        Expenses.selectAll()
            .where { (Expenses.id eq id) and (Expenses.userId eq userId) }
            .singleOrNull()
            ?.toExpense()
    }

    override suspend fun delete(id: Long, userId: Long): Boolean = dbQuery {
        Expenses.deleteWhere { (Expenses.id eq id) and (Expenses.userId eq userId) } > 0
    }

    override suspend fun findByScheduleId(userId: Long, scheduleId: Long): List<Expense> = dbQuery {
        Expenses.selectAll()
            .where {
                (Expenses.userId eq userId) and
                    (Expenses.scheduleId eq EntityID(scheduleId, ExpenseSchedules))
            }
            .orderBy(Expenses.spentAt to SortOrder.ASC)
            .map { it.toExpense() }
    }

    override suspend fun deleteByScheduleId(
        userId: Long,
        scheduleId: Long,
        futureAfter: Instant?
    ): Int = dbQuery {
        Expenses.deleteWhere {
            val base = (Expenses.userId eq userId) and
                (Expenses.scheduleId eq EntityID(scheduleId, ExpenseSchedules))
            if (futureAfter == null) base else base and (Expenses.spentAt greater futureAfter)
        }
    }

    private fun ResultRow.toExpense(): Expense = Expense(
        id = this[Expenses.id].value,
        userId = this[Expenses.userId].value,
        description = this[Expenses.description],
        amount = this[Expenses.amount],
        category = ExpenseCategory.valueOf(this[Expenses.category]),
        recurring = this[Expenses.recurring],
        spentAt = this[Expenses.spentAt],
        createdAt = this[Expenses.createdAt],
        scheduleId = this[Expenses.scheduleId]?.value
    )
}
