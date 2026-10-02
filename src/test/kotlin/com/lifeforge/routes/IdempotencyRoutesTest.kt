package com.lifeforge.routes

import com.lifeforge.data.repository.GoalRepositoryImpl
import com.lifeforge.data.repository.UserRepositoryImpl
import com.lifeforge.data.tables.Goals
import com.lifeforge.data.tables.Users
import com.lifeforge.domain.model.RiskProfile
import com.lifeforge.dto.GoalDto
import com.lifeforge.dto.GoalRequest
import com.lifeforge.plugins.configureHTTP
import com.lifeforge.plugins.configureSecurity
import com.lifeforge.plugins.configureSerialization
import com.lifeforge.security.JwtService
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test

/**
 * Reenvio de criacoes pelo app offline-first: a mesma `Idempotency-Key`
 * devolve o registro ja criado em vez de duplica-lo.
 */
class IdempotencyRoutesTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private val request = GoalRequest(
        name = "Reserva de emergencia",
        category = "CUSTOM",
        targetAmount = "30000.00",
        targetDate = "2030-01-01T00:00:00Z",
        priority = 1,
    )

    @Test
    fun `mesma chave devolve o registro ja criado sem duplicar`() = testApplication {
        val token = setupTestApp("idemTest1")
        val client = jsonClient()

        val first = client.post("/api/v1/goals") {
            bearerAuth(token); header("Idempotency-Key", "lifeforge-goal-1-123")
            contentType(ContentType.Application.Json); setBody(request)
        }
        val retry = client.post("/api/v1/goals") {
            bearerAuth(token); header("Idempotency-Key", "lifeforge-goal-1-123")
            contentType(ContentType.Application.Json); setBody(request)
        }

        first.status shouldBe HttpStatusCode.Created
        retry.status shouldBe HttpStatusCode.Created
        retry.body<GoalDto>().id shouldBe first.body<GoalDto>().id
        client.get("/api/v1/goals") { bearerAuth(token) }.body<List<GoalDto>>() shouldHaveSize 1
    }

    @Test
    fun `sem chave cada requisicao cria um registro`() = testApplication {
        val token = setupTestApp("idemTest2")
        val client = jsonClient()

        val a = client.post("/api/v1/goals") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(request)
        }.body<GoalDto>()
        val b = client.post("/api/v1/goals") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(request)
        }.body<GoalDto>()

        a.id shouldNotBe b.id
    }

    @Test
    fun `chave expira depois do prazo de validade`() {
        var now = Instant.parse("2026-10-02T12:00:00Z")
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId?): Clock = this
            override fun instant(): Instant = now
        }
        val registry = IdempotencyRegistry(ttl = Duration.ofHours(24), clock = clock)

        registry.remember(userId = 1, scope = "goal", key = "k", resourceId = 42)
        registry.find(1, "goal", "k") shouldBe 42L
        registry.find(2, "goal", "k").shouldBeNull() // outro usuario
        registry.find(1, "income", "k").shouldBeNull() // outro recurso

        now = now.plus(Duration.ofHours(25))
        registry.find(1, "goal", "k").shouldBeNull()
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
            // Os repositorios usam o banco padrao do Exposed (o primeiro conectado
            // na JVM): aponta-o para o H2 deste teste, isolando os casos.
            TransactionManager.defaultDatabase = db
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
            // Registro proprio por teste: isola as chaves entre os casos.
            routing { goalRoutes(GoalRepositoryImpl(), IdempotencyRegistry()) }
        }
        startApplication()
        return token
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(testJson) }
    }
}
