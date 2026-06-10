package com.lifeforge.data.repository

import com.lifeforge.config.DatabaseFactory.dbQuery
import com.lifeforge.data.tables.Users
import com.lifeforge.domain.model.RiskProfile
import com.lifeforge.domain.model.User
import com.lifeforge.domain.repository.UserRepository
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * Implementacao do [UserRepository] com Exposed/JDBC.
 *
 * Sprint 4.3 (Final): adicionado [updateRiskProfile] para permitir
 * ao usuario alterar seu perfil de risco pelo app (PATCH no endpoint
 * dedicado em [com.lifeforge.routes.userRoutes]).
 */
class UserRepositoryImpl : UserRepository {

    override suspend fun findById(id: Long): User? = dbQuery {
        Users.selectAll()
            .where { Users.id eq id }
            .singleOrNull()
            ?.toUser()
    }

    override suspend fun findByEmail(email: String): User? = dbQuery {
        Users.selectAll()
            .where { Users.email eq email }
            .singleOrNull()
            ?.toUser()
    }

    override suspend fun findPasswordHashByEmail(email: String): Pair<User, String>? = dbQuery {
        Users.selectAll()
            .where { Users.email eq email }
            .singleOrNull()
            ?.let { it.toUser() to it[Users.passwordHash] }
    }

    override suspend fun create(
        email: String,
        name: String,
        passwordHash: String,
        riskProfile: RiskProfile,
    ): User = dbQuery {
        val now = Instant.now()
        val id = Users.insertAndGetId { row ->
            row[Users.email] = email
            row[Users.name] = name
            row[Users.passwordHash] = passwordHash
            row[Users.riskProfile] = riskProfile.name
            row[Users.createdAt] = now
            row[Users.updatedAt] = now
        }
        User(
            id = id.value,
            email = email,
            name = name,
            riskProfile = riskProfile,
            createdAt = now,
            updatedAt = now,
        )
    }

    override suspend fun updateRiskProfile(userId: Long, profile: RiskProfile): Boolean = dbQuery {
        val rows = Users.update({ Users.id eq userId }) { row ->
            row[Users.riskProfile] = profile.name
            row[Users.updatedAt] = Instant.now()
        }
        rows > 0
    }

    override suspend fun updateName(userId: Long, name: String): Boolean = dbQuery {
        val rows = Users.update({ Users.id eq userId }) { row ->
            row[Users.name] = name
            row[Users.updatedAt] = Instant.now()
        }
        rows > 0
    }

    private fun ResultRow.toUser(): User = User(
        id = this[Users.id].value,
        email = this[Users.email],
        name = this[Users.name],
        riskProfile = RiskProfile.valueOf(this[Users.riskProfile]),
        createdAt = this[Users.createdAt],
        updatedAt = this[Users.updatedAt],
    )
}
