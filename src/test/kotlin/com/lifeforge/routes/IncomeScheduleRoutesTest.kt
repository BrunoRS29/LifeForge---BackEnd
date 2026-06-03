package com.lifeforge.routes

import com.lifeforge.data.repository.IncomeRepositoryImpl
import com.lifeforge.data.repository.IncomeScheduleRepositoryImpl
import com.lifeforge.data.repository.UserRepositoryImpl
import com.lifeforge.data.tables.IncomeSchedules
import com.lifeforge.data.tables.Incomes
import com.lifeforge.data.tables.Users
import com.lifeforge.domain.model.RiskProfile
import com.lifeforge.dto.IncomeScheduleDto
import com.lifeforge.dto.IncomeScheduleRequest
import com.lifeforge.plugins.configureSecurity
import com.lifeforge.plugins.configureSerialization
import com.lifeforge.security.JwtService
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test

/**
 * Teste de integracao da subrota POST /api/v1/incomes/schedules (Sprint 6).
 *
 * Objetivo principal: garantir que a rota EXISTE e materializa (regressao
 * contra o sintoma "Recurso nao encontrado"/404 que acontece quando o backend
 * roda uma build antiga sem essas rotas). H2 em memoria; as tabelas de schedule
 * nao usam jsonb, entao funcionam no MODE=PostgreSQL.
 */
class IncomeScheduleRoutesTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    @Test
    fun `POST schedules MONTHLY retorna 201 (nao 404) e materializa`() = testApplication {
        val ctx = setupTestApp("schedTest1")
        val client = jsonClient()
        val (_, token) = ctx.createUserAndToken("monthly@test.com")

        val response = client.post("/api/v1/incomes/schedules") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                IncomeScheduleRequest(
                    source = "Salario Empresa X",
                    amountPerOccurrence = "5000.00",
                    incomeType = "SALARY",
                    recurrence = "MONTHLY",
                    startDate = "2024-01-05T00:00:00Z",
                )
            )
        }

        response.status shouldBe HttpStatusCode.Created
        val dto = response.body<IncomeScheduleDto>()
        dto.recurrence shouldBe "MONTHLY"
        // Desde 01/2024 ate hoje + 12 meses: bem mais que 12 ocorrencias.
        dto.generatedCount shouldBeGreaterThanOrEqualTo 12
    }

    @Test
    fun `POST schedules INSTALLMENTS gera exatamente N parcelas`() = testApplication {
        val ctx = setupTestApp("schedTest2")
        val client = jsonClient()
        val (_, token) = ctx.createUserAndToken("parcelado@test.com")

        val response = client.post("/api/v1/incomes/schedules") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                IncomeScheduleRequest(
                    source = "Bonus parcelado",
                    amountPerOccurrence = "100.00",
                    incomeType = "BONUS",
                    recurrence = "INSTALLMENTS",
                    startDate = "2025-01-05T00:00:00Z",
                    installmentsTotal = 12,
                )
            )
        }

        response.status shouldBe HttpStatusCode.Created
        response.body<IncomeScheduleDto>().generatedCount shouldBe 12
    }

    @Test
    fun `POST schedules sem token retorna 401`() = testApplication {
        setupTestApp("schedTest3")
        val client = jsonClient()

        val response = client.post("/api/v1/incomes/schedules") {
            contentType(ContentType.Application.Json)
            setBody(
                IncomeScheduleRequest(
                    source = "x", amountPerOccurrence = "1", incomeType = "SALARY",
                    recurrence = "MONTHLY", startDate = "2024-01-05T00:00:00Z",
                )
            )
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    // =================================================================
    // Helpers
    // =================================================================

    private class TestContext(val jwtService: JwtService) {
        fun createUserAndToken(email: String): Pair<Long, String> = runBlocking {
            val user = UserRepositoryImpl().create(
                email = email,
                name = email.substringBefore("@"),
                passwordHash = "test-hash",
                riskProfile = RiskProfile.MODERATE,
            )
            user.id to jwtService.generateToken(user.id, user.email)
        }
    }

    private suspend fun ApplicationTestBuilder.setupTestApp(dbName: String): TestContext {
        val jdbcUrl = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        val testJwtConfig = mapOf(
            "jwt.secret" to "test-secret-must-be-long-enough-for-hmac256",
            "jwt.issuer" to "lifeforge-test",
            "jwt.audience" to "lifeforge-test",
            "jwt.realm" to "LifeForge Test",
            "jwt.expirationMs" to "3600000",
        )
        environment {
            config = MapApplicationConfig(*testJwtConfig.toList().toTypedArray())
        }

        lateinit var ctx: TestContext
        application {
            val db = Database.connect(jdbcUrl, driver = "org.h2.Driver")
            transaction(db) {
                // Ordem importa: tabelas referenciadas antes (FK).
                SchemaUtils.create(Users, IncomeSchedules, Incomes)
            }

            val jwt = JwtService(environment.config)
            val incomeRepo = IncomeRepositoryImpl()
            val scheduleRepo = IncomeScheduleRepositoryImpl(incomeRepo)
            ctx = TestContext(jwt)

            configureSerialization()
            configureSecurity(jwt)
            routing {
                incomeRoutes(incomeRepo, scheduleRepo)
            }
        }
        startApplication()
        return ctx
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(testJson) }
    }
}
