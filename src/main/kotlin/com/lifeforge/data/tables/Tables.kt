package com.lifeforge.data.tables

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * Definicoes das tabelas do PostgreSQL via Exposed DSL.
 *
 * Todas as tabelas seguem o padrao LongIdTable (chave primaria `id BIGSERIAL`)
 * e timestamps em UTC. Campos JSON usam jsonb nativo do PostgreSQL.
 */

object Users : LongIdTable("users") {
    val email = varchar("email", 255).uniqueIndex()
    val name = varchar("name", 120)
    val passwordHash = varchar("password_hash", 255)
    // Perfil de risco: CONSERVATIVE | MODERATE | AGGRESSIVE
    val riskProfile = varchar("risk_profile", 32).default("MODERATE")
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
}

object Goals : LongIdTable("goals") {
    val userId = reference("user_id", Users).index()
    val name = varchar("name", 200)
    // Categoria: RETIREMENT | REAL_ESTATE | FINANCIAL_INDEPENDENCE | EDUCATION | TRAVEL | CUSTOM
    val category = varchar("category", 64)
    val targetAmount = decimal("target_amount", precision = 18, scale = 2)
    val targetDate = timestamp("target_date")
    val priority = integer("priority").default(1)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
}

object Incomes : LongIdTable("incomes") {
    val userId = reference("user_id", Users).index()
    val sourceColumn = varchar("source", 200)
    val amount = decimal("amount", precision = 18, scale = 2)
    // SALARY | BONUS | DIVIDEND | RENT | OTHER
    val incomeType = varchar("income_type", 32)
    val recurring = bool("recurring").default(false)
    val receivedAt = timestamp("received_at")
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
}

object Expenses : LongIdTable("expenses") {
    val userId = reference("user_id", Users).index()
    val description = varchar("description", 200)
    val amount = decimal("amount", precision = 18, scale = 2)
    // HOUSING | FOOD | TRANSPORT | HEALTH | EDUCATION | LEISURE | OTHER
    val category = varchar("category", 64)
    val recurring = bool("recurring").default(false)
    val spentAt = timestamp("spent_at")
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
}

object Assets : LongIdTable("assets") {
    val userId = reference("user_id", Users).index()
    val name = varchar("name", 200)
    // FIXED_INCOME | STOCKS | REAL_ESTATE_FUND | CRYPTO | REAL_ESTATE | OTHER
    val assetType = varchar("asset_type", 32)
    val currentValue = decimal("current_value", precision = 18, scale = 2)
    val expectedReturn = decimal("expected_return", precision = 8, scale = 4) // taxa anual (ex: 0.08 = 8%)
    val volatility = decimal("volatility", precision = 8, scale = 4)          // desvio padrao anualizado
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
}

object Simulations : LongIdTable("simulations") {
    val goalId = reference("goal_id", Goals).index()
    // Parametros usados na simulacao (snapshot)
    val parameters = jsonb<JsonElement>("parameters", Json)
    // Resultados agregados (P5, P10, P50, P90, P95, mean, success_probability...)
    val result = jsonb<JsonElement>("result", Json)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
}

object Predictions : LongIdTable("predictions") {
    val userId = reference("user_id", Users).index()
    // INCOME_REGRESSION | EXPENSE_RANDOM_FOREST | PATRIMONY_ARIMA
    val modelName = varchar("model_name", 64)
    val input = jsonb<JsonElement>("input", Json)
    val output = jsonb<JsonElement>("output", Json)
    val errorMetric = decimal("error_metric", precision = 12, scale = 6).nullable()
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
}
