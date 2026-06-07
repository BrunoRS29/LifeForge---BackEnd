package com.lifeforge.data.repository

import com.lifeforge.config.DatabaseFactory.dbQuery
import com.lifeforge.data.tables.UserProfiles
import com.lifeforge.data.tables.Users
import com.lifeforge.domain.repository.UserProfileRepository
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * Persistencia do perfil estendido como blob JSONB (1:1 com o usuario).
 * O contrato de campos vive no app; aqui apenas guardamos/devolvemos o JSON.
 */
class UserProfileRepositoryImpl : UserProfileRepository {

    override suspend fun get(userId: Long): JsonElement? = dbQuery {
        UserProfiles.selectAll()
            .where { UserProfiles.userId eq userId }
            .singleOrNull()
            ?.get(UserProfiles.data)
    }

    override suspend fun upsert(userId: Long, data: JsonElement): JsonElement = dbQuery {
        val exists = UserProfiles.selectAll()
            .where { UserProfiles.userId eq userId }
            .singleOrNull() != null

        if (exists) {
            UserProfiles.update({ UserProfiles.userId eq userId }) { row ->
                row[UserProfiles.data] = data
                row[UserProfiles.updatedAt] = Instant.now()
            }
        } else {
            UserProfiles.insert { row ->
                row[UserProfiles.userId] = EntityID(userId, Users)
                row[UserProfiles.data] = data
                row[UserProfiles.updatedAt] = Instant.now()
            }
        }
        data
    }
}
