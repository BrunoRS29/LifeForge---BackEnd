package com.lifeforge.data.repository

import com.lifeforge.config.DatabaseFactory.dbQuery
import com.lifeforge.data.tables.Predictions
import com.lifeforge.data.tables.Users
import com.lifeforge.domain.model.Prediction
import com.lifeforge.domain.repository.PredictionRepository
import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll

/**
 * Implementacao Exposed/PostgreSQL do [PredictionRepository].
 *
 * Segue o mesmo padrao das outras impls (IncomeRepositoryImpl etc.):
 *  - dbQuery {} para nao bloquear a thread
 *  - WHERE inclui userId em toda consulta para tenant isolation
 *  - private fun ResultRow.to<Entidade>() centraliza o mapping
 */
class PredictionRepositoryImpl : PredictionRepository {

    override suspend fun create(
        userId: Long,
        modelName: String,
        input: JsonElement,
        output: JsonElement,
        errorMetric: BigDecimal?,
    ): Prediction = dbQuery {
        val now = Instant.now()
        val id = Predictions.insertAndGetId { row ->
            row[Predictions.userId] = EntityID(userId, Users)
            row[Predictions.modelName] = modelName
            row[Predictions.input] = input
            row[Predictions.output] = output
            row[Predictions.errorMetric] = errorMetric
            row[Predictions.createdAt] = now
        }

        Prediction(
            id = id.value,
            userId = userId,
            modelName = modelName,
            input = input,
            output = output,
            errorMetric = errorMetric,
            createdAt = now,
        )
    }

    override suspend fun findAllByUser(userId: Long, limit: Int): List<Prediction> =
        dbQuery {
            Predictions
                .selectAll()
                .where { Predictions.userId eq userId }
                .orderBy(Predictions.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { it.toPrediction() }
        }

    override suspend fun findById(id: Long, userId: Long): Prediction? = dbQuery {
        Predictions
            .selectAll()
            .where { (Predictions.id eq id) and (Predictions.userId eq userId) }
            .singleOrNull()
            ?.toPrediction()
    }

    override suspend fun findLatestByUserAndModel(
        userId: Long,
        modelName: String,
    ): Prediction? = dbQuery {
        Predictions
            .selectAll()
            .where {
                (Predictions.userId eq userId) and (Predictions.modelName eq modelName)
            }
            .orderBy(Predictions.createdAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toPrediction()
    }

    private fun ResultRow.toPrediction(): Prediction = Prediction(
        id = this[Predictions.id].value,
        userId = this[Predictions.userId].value,
        modelName = this[Predictions.modelName],
        input = this[Predictions.input],
        output = this[Predictions.output],
        errorMetric = this[Predictions.errorMetric],
        createdAt = this[Predictions.createdAt],
    )
}
