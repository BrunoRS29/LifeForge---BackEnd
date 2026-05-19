package com.lifeforge.data.repository

import com.lifeforge.config.DatabaseFactory.dbQuery
import com.lifeforge.data.tables.Assets
import com.lifeforge.data.tables.Users
import com.lifeforge.domain.model.Asset
import com.lifeforge.domain.model.AssetType
import com.lifeforge.domain.repository.AssetRepository
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

class AssetRepositoryImpl : AssetRepository {

    override suspend fun create(
        userId: Long,
        name: String,
        assetType: AssetType,
        currentValue: BigDecimal,
        expectedReturn: BigDecimal,
        volatility: BigDecimal
    ): Asset = dbQuery {
        val now = Instant.now()
        val id = Assets.insertAndGetId { row ->
            row[Assets.userId] = EntityID(userId, Users)
            row[Assets.name] = name
            row[Assets.assetType] = assetType.name
            row[Assets.currentValue] = currentValue
            row[Assets.expectedReturn] = expectedReturn
            row[Assets.volatility] = volatility
            row[Assets.createdAt] = now
            row[Assets.updatedAt] = now
        }
        Asset(
            id = id.value,
            userId = userId,
            name = name,
            assetType = assetType,
            currentValue = currentValue,
            expectedReturn = expectedReturn,
            volatility = volatility,
            createdAt = now,
            updatedAt = now
        )
    }

    override suspend fun findAllByUser(userId: Long): List<Asset> = dbQuery {
        Assets.selectAll()
            .where { Assets.userId eq userId }
            .orderBy(Assets.createdAt to SortOrder.DESC)
            .map { it.toAsset() }
    }

    override suspend fun findById(id: Long, userId: Long): Asset? = dbQuery {
        Assets.selectAll()
            .where { (Assets.id eq id) and (Assets.userId eq userId) }
            .singleOrNull()
            ?.toAsset()
    }

    override suspend fun update(
        id: Long,
        userId: Long,
        name: String,
        assetType: AssetType,
        currentValue: BigDecimal,
        expectedReturn: BigDecimal,
        volatility: BigDecimal
    ): Asset? = dbQuery {
        val updated = Assets.update({ (Assets.id eq id) and (Assets.userId eq userId) }) {
            it[Assets.name] = name
            it[Assets.assetType] = assetType.name
            it[Assets.currentValue] = currentValue
            it[Assets.expectedReturn] = expectedReturn
            it[Assets.volatility] = volatility
            it[Assets.updatedAt] = Instant.now()
        }
        if (updated > 0) {
            Assets.selectAll()
                .where { Assets.id eq id }
                .singleOrNull()
                ?.toAsset()
        } else null
    }

    override suspend fun delete(id: Long, userId: Long): Boolean = dbQuery {
        Assets.deleteWhere { (Assets.id eq id) and (Assets.userId eq userId) } > 0
    }

    private fun ResultRow.toAsset(): Asset = Asset(
        id = this[Assets.id].value,
        userId = this[Assets.userId].value,
        name = this[Assets.name],
        assetType = AssetType.valueOf(this[Assets.assetType]),
        currentValue = this[Assets.currentValue],
        expectedReturn = this[Assets.expectedReturn],
        volatility = this[Assets.volatility],
        createdAt = this[Assets.createdAt],
        updatedAt = this[Assets.updatedAt]
    )
}
