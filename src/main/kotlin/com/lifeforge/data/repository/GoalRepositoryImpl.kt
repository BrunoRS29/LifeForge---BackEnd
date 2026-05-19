package com.lifeforge.data.repository

import com.lifeforge.config.DatabaseFactory.dbQuery
import com.lifeforge.data.tables.Goals
import com.lifeforge.data.tables.Users
import com.lifeforge.domain.model.Goal
import com.lifeforge.domain.model.GoalCategory
import com.lifeforge.domain.repository.GoalRepository
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

class GoalRepositoryImpl : GoalRepository {

    override suspend fun create(
        userId: Long,
        name: String,
        category: GoalCategory,
        targetAmount: BigDecimal,
        targetDate: Instant,
        priority: Int
    ): Goal = dbQuery {
        val now = Instant.now()
        val id = Goals.insertAndGetId { row ->
            row[Goals.userId] = EntityID(userId, Users)
            row[Goals.name] = name
            row[Goals.category] = category.name
            row[Goals.targetAmount] = targetAmount
            row[Goals.targetDate] = targetDate
            row[Goals.priority] = priority
            row[Goals.createdAt] = now
            row[Goals.updatedAt] = now
        }
        Goal(
            id = id.value,
            userId = userId,
            name = name,
            category = category,
            targetAmount = targetAmount,
            targetDate = targetDate,
            priority = priority,
            createdAt = now,
            updatedAt = now
        )
    }

    override suspend fun findAllByUser(userId: Long): List<Goal> = dbQuery {
        Goals.selectAll()
            .where { Goals.userId eq userId }
            .orderBy(Goals.priority to SortOrder.ASC, Goals.createdAt to SortOrder.DESC)
            .map { it.toGoal() }
    }

    override suspend fun findById(id: Long, userId: Long): Goal? = dbQuery {
        Goals.selectAll()
            .where { (Goals.id eq id) and (Goals.userId eq userId) }
            .singleOrNull()
            ?.toGoal()
    }

    override suspend fun update(
        id: Long,
        userId: Long,
        name: String,
        category: GoalCategory,
        targetAmount: BigDecimal,
        targetDate: Instant,
        priority: Int
    ): Goal? = dbQuery {
        val updated = Goals.update({ (Goals.id eq id) and (Goals.userId eq userId) }) {
            it[Goals.name] = name
            it[Goals.category] = category.name
            it[Goals.targetAmount] = targetAmount
            it[Goals.targetDate] = targetDate
            it[Goals.priority] = priority
            it[Goals.updatedAt] = Instant.now()
        }
        if (updated > 0) {
            Goals.selectAll()
                .where { Goals.id eq id }
                .singleOrNull()
                ?.toGoal()
        } else null
    }

    override suspend fun delete(id: Long, userId: Long): Boolean = dbQuery {
        Goals.deleteWhere { (Goals.id eq id) and (Goals.userId eq userId) } > 0
    }

    private fun ResultRow.toGoal(): Goal = Goal(
        id = this[Goals.id].value,
        userId = this[Goals.userId].value,
        name = this[Goals.name],
        category = GoalCategory.valueOf(this[Goals.category]),
        targetAmount = this[Goals.targetAmount],
        targetDate = this[Goals.targetDate],
        priority = this[Goals.priority],
        createdAt = this[Goals.createdAt],
        updatedAt = this[Goals.updatedAt]
    )
}
