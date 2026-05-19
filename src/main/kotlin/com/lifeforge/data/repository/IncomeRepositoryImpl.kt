package com.lifeforge.data.repository

import com.lifeforge.config.DatabaseFactory.dbQuery
import com.lifeforge.data.tables.Incomes
import com.lifeforge.data.tables.Users
import com.lifeforge.domain.model.Income
import com.lifeforge.domain.model.IncomeType
import com.lifeforge.domain.repository.IncomeRepository
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.math.BigDecimal
import java.time.Instant

class IncomeRepositoryImpl : IncomeRepository {

    override suspend fun create(
        userId: Long,
        source: String,
        amount: BigDecimal,
        incomeType: IncomeType,
        recurring: Boolean,
        receivedAt: Instant
    ): Income = dbQuery {

        val now = Instant.now()

        val id = Incomes.insertAndGetId { row ->
            row[Incomes.userId] = EntityID(userId, Users)
            row[Incomes.sourceColumn] = source
            row[Incomes.amount] = amount
            row[Incomes.incomeType] = incomeType.name
            row[Incomes.recurring] = recurring
            row[Incomes.receivedAt] = receivedAt
            row[Incomes.createdAt] = now
        }

        Income(
            id = id.value,
            userId = userId,
            source = source,
            amount = amount,
            incomeType = incomeType,
            recurring = recurring,
            receivedAt = receivedAt,
            createdAt = now
        )
    }

    override suspend fun findAllByUser(userId: Long): List<Income> = dbQuery {
        Incomes
            .selectAll()
            .where { Incomes.userId eq userId }
            .orderBy(Incomes.receivedAt, SortOrder.DESC)
            .map { it.toIncome() }
    }

    override suspend fun findById(id: Long, userId: Long): Income? = dbQuery {
        Incomes
            .selectAll()
            .where { (Incomes.id eq id) and (Incomes.userId eq userId) }
            .singleOrNull()
            ?.toIncome()
    }

    override suspend fun delete(id: Long, userId: Long): Boolean = dbQuery {
        Incomes.deleteWhere {
            (Incomes.id eq id) and (Incomes.userId eq userId)
        } > 0
    }

    private fun ResultRow.toIncome(): Income = Income(
        id = this[Incomes.id].value,
        userId = this[Incomes.userId].value,
        source = this[Incomes.sourceColumn],
        amount = this[Incomes.amount],
        incomeType = IncomeType.valueOf(this[Incomes.incomeType]),
        recurring = this[Incomes.recurring],
        receivedAt = this[Incomes.receivedAt],
        createdAt = this[Incomes.createdAt]
    )
}