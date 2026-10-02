package com.lifeforge.routes

import com.lifeforge.data.repository.GoalRepositoryImpl
import com.lifeforge.data.repository.UserRepositoryImpl
import com.lifeforge.data.tables.Goals
import com.lifeforge.data.tables.Users
import com.lifeforge.domain.model.RiskProfile
import com.lifeforge.dto.ErrorResponse
import com.lifeforge.plugins.configureHTTP
import com.lifeforge.plugins.configureSecurity
import com.lifeforge.plugins.configureSerialization
import com.lifeforge.security.JwtService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
 * Contrato de erros da API: toda falha responde `ErrorResponse { error, message }`
 * com o status correto. Regressao contra corpo malformado virar 500 (erro do
 * cliente reportado como erro do servidor).
 */
class ErrorHandlingRoutesTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    @Test
    fun `JSON malformado retorna 400 VALIDATION em vez de 500`() = testApplication {
        val token = setupTestApp("errTest1")
        val client = jsonClient()

        val response = client.post("/api/v1/goals") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("{ isto nao e json")
        }

        response.status shouldBe HttpStatusCode.BadRequest
        response.body<ErrorResponse>().error shouldBe "VALIDATION"
    }

    @Test
    fun `campo obrigatorio ausente retorna 400 VALIDATION`() = testApplication {
        val token = setupTestApp("errTest2")
        val client = jsonClient()

        val response = client.post("/api/v1/goals") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name": "Viagem"}""")
        }

        response.status shouldBe HttpStatusCode.BadRequest
        response.body<ErrorResponse>().error shouldBe "VALIDATION"
    }

    @Test
    fun `id nao numerico retorna 400 INVALID_ID e recurso inexistente 404 com corpo padrao`() = testApplication {
        val token = setupTestApp("errTest3")
        val client = jsonClient()

        val invalid = client.get("/api/v1/goals/abc") { bearerAuth(token) }
        invalid.status shouldBe HttpStatusCode.BadRequest
        invalid.body<ErrorResponse>().error shouldBe "INVALID_ID"

        val missing = client.get("/api/v1/goals/987654") { bearerAuth(token) }
        missing.status shouldBe HttpStatusCode.NotFound
        val body = missing.body<ErrorResponse>()
        body.error shouldBe "NOT_FOUND"
        body.message shouldContain "não encontrada"
    }

    // =================================================================
    // Helpers
    // =================================================================

    private suspend fun ApplicationTestBuilder.setupTestApp(dbName: String): String {
        val jdbcUrl = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        environment {
            config = MapApplicationConfig(
                "jwt.secret" to "test-secret-must-be-long-enough-for-hmac256",
                "jwt.issuer" to "lifeforge-test",
                "jwt.audience" to "lifeforge-test",
                "jwt.realm" to "LifeForge Test",
                "jwt.expirationMs" to "3600000",
            )
        }
        lateinit var token: String
        application {
            val db = Database.connect(jdbcUrl, driver = "org.h2.Driver")
            transaction(db) { SchemaUtils.create(Users, Goals) }
            val jwt = JwtService(environment.config)
            token = runBlocking {
                val user = UserRepositoryImpl().create(
                    email = "$dbName@test.com",
                    name = dbName,
                    passwordHash = "test-hash",
                    riskProfile = RiskProfile.MODERATE,
                )
                jwt.generateToken(user.id, user.email)
            }
            configureSerialization()
            configureHTTP()
            configureSecurity(jwt)
            routing { goalRoutes(GoalRepositoryImpl()) }
        }
        startApplication()
        return token
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(testJson) }
    }
}
