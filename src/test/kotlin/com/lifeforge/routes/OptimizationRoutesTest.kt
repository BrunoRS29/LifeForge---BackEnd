package com.lifeforge.routes

import com.lifeforge.config.AppContainer
import com.lifeforge.data.repository.GoalRepositoryImpl
import com.lifeforge.data.repository.UserRepositoryImpl
import com.lifeforge.data.tables.Goals
import com.lifeforge.data.tables.Users
import com.lifeforge.domain.model.GoalCategory
import com.lifeforge.domain.model.RiskProfile
import com.lifeforge.dto.OptimizationResponse
import com.lifeforge.dto.OptimizeContributionRequest
import com.lifeforge.dto.OptimizeHorizonRequest
import com.lifeforge.dto.RebalanceRequest
import com.lifeforge.dto.RebalanceResponse
import com.lifeforge.engine.montecarlo.MonteCarloEngine
import com.lifeforge.engine.optimization.OptimizationEngine
import com.lifeforge.engine.optimization.RebalancingAdvisor
import com.lifeforge.plugins.configureSecurity
import com.lifeforge.plugins.configureSerialization
import com.lifeforge.security.JwtService
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

/**
 * Testes de integracao das rotas de otimizacao (Sprint 3).
 *
 * Estrategia:
 *   - H2 em memoria como banco de teste (banco de dados separado por classe
 *     via DB_CLOSE_DELAY=-1 + nome unico evita interferencia entre runs)
 *   - Schema MINIMO: somente Users e Goals — as rotas de otimizacao nao
 *     tocam tabelas com jsonb (Simulations, Predictions), entao evitamos o
 *     incompatibilidade do H2 com tipos PostgreSQL.
 *   - JWT token gerado diretamente pelo JwtService — sem passar pelo
 *     AuthRoutes — para isolar o teste das rotas de otimizacao.
 *   - Configuracao Ktor montada manualmente (nao usa Application.module())
 *     pelo mesmo motivo: evita inicializar o schema completo.
 *
 * Cobertura:
 *   1. POST /optimize/contribution
 *      - 401 sem token
 *      - 200 com payload valido (sem goalId)
 *      - 400 com payload invalido (targetAmount <= 0)
 *      - 200 com goalId pertencente ao usuario
 *      - 404 com goalId pertencente a OUTRO usuario
 *      - 200 com infeasibility (feasible=false, verification=null)
 *
 *   2. POST /optimize/horizon
 *      - 200 com payload valido
 *      - 400 com payload invalido
 *
 *   3. POST /optimize/rebalance
 *      - 200 com perfil valido
 *      - 400 com perfil invalido
 *      - pesos somam 1.0 dentro de tolerancia
 */
class OptimizationRoutesTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    // -----------------------------------------------------------------
    // POST /optimize/contribution
    // -----------------------------------------------------------------

    @Test
    fun `contribution sem token retorna 401`() = testApplication {
        setupTestApp(dbName = "optTest1")
        val client = jsonClient()

        val response = client.post("/api/v1/optimize/contribution") {
            contentType(ContentType.Application.Json)
            setBody(validContributionRequest())
        }

        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `contribution com payload valido retorna 200 e estrutura completa`() = testApplication {
        val ctx = setupTestApp(dbName = "optTest2")
        val client = jsonClient()
        val (_, token) = ctx.createUserAndToken("alice@test.com")

        val response = client.post("/api/v1/optimize/contribution") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(validContributionRequest())
        }

        response.status shouldBe HttpStatusCode.OK
        val body = response.body<OptimizationResponse>()
        body.type shouldBe "OPTIMAL_CONTRIBUTION"
        body.feasible shouldBe true
        body.optimalValue shouldBeGreaterThanOrEqualTo 0.0
        body.iterations shouldHaveAtLeastSize 3
        body.verification shouldNotBe null
        body.verification!!.numSimulations shouldBe 5_000
        body.seed shouldBe 42L  // request fixou seed=42
    }

    @Test
    fun `contribution com targetAmount invalido retorna 400`() = testApplication {
        val ctx = setupTestApp(dbName = "optTest3")
        val client = jsonClient()
        val (_, token) = ctx.createUserAndToken("bob@test.com")

        val invalidReq = validContributionRequest().copy(targetAmount = -100.0)
        val response = client.post("/api/v1/optimize/contribution") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(invalidReq)
        }

        response.status shouldBe HttpStatusCode.BadRequest
        response.bodyAsText().shouldContain("targetAmount")
    }

    @Test
    fun `contribution com goalId do proprio usuario retorna 200`() = testApplication {
        val ctx = setupTestApp(dbName = "optTest4")
        val client = jsonClient()
        val (userId, token) = ctx.createUserAndToken("carol@test.com")
        val goalId = ctx.createGoal(userId, name = "Aposentadoria")

        val req = validContributionRequest().copy(goalId = goalId.toString())
        val response = client.post("/api/v1/optimize/contribution") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(req)
        }

        response.status shouldBe HttpStatusCode.OK
        val body = response.body<OptimizationResponse>()
        body.feasible shouldBe true
    }

    @Test
    fun `contribution com goalId de outro usuario retorna 404`() = testApplication {
        val ctx = setupTestApp(dbName = "optTest5")
        val client = jsonClient()
        val (aliceId, _) = ctx.createUserAndToken("alice2@test.com")
        val (_, bobToken) = ctx.createUserAndToken("bob2@test.com")
        val aliceGoalId = ctx.createGoal(aliceId, name = "Casa propria")

        // Bob tenta otimizar contra a meta da Alice
        val req = validContributionRequest().copy(goalId = aliceGoalId.toString())
        val response = client.post("/api/v1/optimize/contribution") {
            bearerAuth(bobToken)
            contentType(ContentType.Application.Json)
            setBody(req)
        }

        response.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `contribution infeasible retorna 200 com feasible=false e verification=null`() = testApplication {
        val ctx = setupTestApp(dbName = "optTest6")
        val client = jsonClient()
        val (_, token) = ctx.createUserAndToken("dan@test.com")

        // Cenario absurdo: meta de 100M em 1 ano, capital quase zero, cap de R$50/mes
        val infeasibleReq = OptimizeContributionRequest(
            initialCapital = 1_000.0,
            expectedReturnAnnual = 0.05,
            volatilityAnnual = 0.10,
            targetAmount = 100_000_000.0,
            horizonMonths = 12,
            targetSuccessProbability = 0.80,
            maxContribution = 50.0,    // cap forcado, nao da pra atingir
            simulationsPerStep = 500,
            verificationSimulations = 500,
            seed = 7L,
        )

        val response = client.post("/api/v1/optimize/contribution") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(infeasibleReq)
        }

        response.status shouldBe HttpStatusCode.OK
        val body = response.body<OptimizationResponse>()
        body.feasible shouldBe false
        body.verification shouldBe null
        body.terminationReason shouldBe "INFEASIBLE_UPPER_BOUND"
    }

    // -----------------------------------------------------------------
    // POST /optimize/horizon
    // -----------------------------------------------------------------

    @Test
    fun `horizon com payload valido retorna 200 e horizonte inteiro`() = testApplication {
        val ctx = setupTestApp(dbName = "optTest7")
        val client = jsonClient()
        val (_, token) = ctx.createUserAndToken("eve@test.com")

        val req = OptimizeHorizonRequest(
            initialCapital = 50_000.0,
            expectedReturnAnnual = 0.08,
            volatilityAnnual = 0.15,
            targetAmount = 500_000.0,
            monthlyContribution = 1_500.0,
            targetSuccessProbability = 0.80,
            simulationsPerStep = 500,
            verificationSimulations = 2_000,
            seed = 99L,
        )

        val response = client.post("/api/v1/optimize/horizon") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(req)
        }

        response.status shouldBe HttpStatusCode.OK
        val body = response.body<OptimizationResponse>()
        body.type shouldBe "OPTIMAL_HORIZON"
        body.feasible shouldBe true

        // horizonte e inteiro — representado como Double mas sem parte fracionaria
        val months = body.optimalValue.toInt()
        months.toDouble() shouldBe (body.optimalValue plusOrMinus 1e-9)
        months shouldBeGreaterThan 0
    }

    @Test
    fun `horizon com monthlyContribution negativo retorna 400`() = testApplication {
        val ctx = setupTestApp(dbName = "optTest8")
        val client = jsonClient()
        val (_, token) = ctx.createUserAndToken("frank@test.com")

        val req = OptimizeHorizonRequest(
            initialCapital = 50_000.0,
            expectedReturnAnnual = 0.08,
            volatilityAnnual = 0.15,
            targetAmount = 500_000.0,
            monthlyContribution = -100.0,   // invalido
            simulationsPerStep = 500,
            verificationSimulations = 2_000,
        )

        val response = client.post("/api/v1/optimize/horizon") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(req)
        }

        response.status shouldBe HttpStatusCode.BadRequest
        response.bodyAsText().shouldContain("monthlyContribution")
    }

    // -----------------------------------------------------------------
    // POST /optimize/rebalance
    // -----------------------------------------------------------------

    @Test
    fun `rebalance com perfil valido retorna 200 e pesos somam 1`() = testApplication {
        val ctx = setupTestApp(dbName = "optTest9")
        val client = jsonClient()
        val (_, token) = ctx.createUserAndToken("grace@test.com")

        val req = RebalanceRequest(
            riskProfile = "MODERATE",
            currentCapital = 50_000.0,
            targetAmount = 500_000.0,
            monthsToGoal = 240,
        )

        val response = client.post("/api/v1/optimize/rebalance") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(req)
        }

        response.status shouldBe HttpStatusCode.OK
        val body = response.body<RebalanceResponse>()

        body.weights.values.sum() shouldBe (1.0 plusOrMinus 1e-9)
        body.weights.values.forEach { it shouldBeGreaterThanOrEqualTo 0.0 }
        body.riskScore shouldBeGreaterThanOrEqualTo 0.0
        body.expectedReturnAnnual shouldBeGreaterThan 0.0
        body.rationale.shouldContain("moderate")
    }

    @Test
    fun `rebalance perfil agressivo aloca mais em STOCKS que conservador`() = testApplication {
        val ctx = setupTestApp(dbName = "optTest10")
        val client = jsonClient()
        val (_, token) = ctx.createUserAndToken("henry@test.com")

        suspend fun call(profile: String): RebalanceResponse =
            client.post("/api/v1/optimize/rebalance") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(
                    RebalanceRequest(
                        riskProfile = profile,
                        currentCapital = 50_000.0,
                        targetAmount = 500_000.0,
                        monthsToGoal = 240,
                    )
                )
            }.body()

        val conservative = call("CONSERVATIVE")
        val aggressive = call("AGGRESSIVE")

        val stocksConservative = conservative.weights["STOCKS"] ?: 0.0
        val stocksAggressive = aggressive.weights["STOCKS"] ?: 0.0
        stocksAggressive shouldBeGreaterThan stocksConservative
    }

    @Test
    fun `rebalance com perfil invalido retorna 400`() = testApplication {
        val ctx = setupTestApp(dbName = "optTest11")
        val client = jsonClient()
        val (_, token) = ctx.createUserAndToken("ian@test.com")

        val req = RebalanceRequest(
            riskProfile = "INSANE",   // nao existe
            currentCapital = 50_000.0,
            targetAmount = 500_000.0,
            monthsToGoal = 240,
        )

        val response = client.post("/api/v1/optimize/rebalance") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(req)
        }

        response.status shouldBe HttpStatusCode.BadRequest
        response.bodyAsText().shouldContain("riskProfile")
    }

    // =================================================================
    // Helpers de teste
    // =================================================================

    /**
     * Contexto compartilhado de teste — guarda referencias aos servicos
     * inicializados no module Ktor para que os testes possam criar usuarios
     * e gerar tokens diretamente, sem passar pelo AuthRoutes.
     */
    private class TestContext(
        val jwtService: JwtService,
    ) {
        /**
         * Cria usuario diretamente via repositorio e gera JWT valido.
         * Retorna (userId, token).
         */
        fun createUserAndToken(email: String): Pair<Long, String> = runBlocking {
            val user = UserRepositoryImpl().create(
                email = email,
                name = email.substringBefore("@"),
                passwordHash = "test-hash-irrelevant",  // login nao e exercitado
                riskProfile = RiskProfile.MODERATE,
            )
            val token = jwtService.generateToken(user.id, user.email)
            user.id to token
        }

        /**
         * Cria meta diretamente via repositorio. Retorna o goalId.
         */
        fun createGoal(userId: Long, name: String = "Meta de teste"): Long = runBlocking {
            GoalRepositoryImpl().create(
                userId = userId,
                name = name,
                category = GoalCategory.RETIREMENT,
                targetAmount = BigDecimal("500000.00"),
                targetDate = Instant.now().plus(7300, ChronoUnit.DAYS), // ~20 anos
                priority = 1,
            ).id
        }
    }

    /**
     * Inicializa H2 + Ktor com schema minimo (Users + Goals) e registra
     * apenas as rotas de otimizacao. Retorna o TestContext para que os
     * testes manipulem o estado diretamente.
     *
     * @param dbName nome unico do banco H2 — evita interferencia entre testes
     */
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

        // Container "manual" inacessivel a partir daqui (esta no `application` block).
        // Por isso retornamos um TestContext capturado via referencia compartilhada.
        lateinit var ctx: TestContext

        application {
            // (1) H2 em memoria com schema minimo
            val db = Database.connect(jdbcUrl, driver = "org.h2.Driver")
            transaction(db) {
                exec("DROP DOMAIN IF EXISTS jsonb CASCADE;")
                exec("CREATE DOMAIN jsonb AS JSON;")
                
                SchemaUtils.create(Users)
                SchemaUtils.create(Goals)

                addLogger(StdOutSqlLogger)
            }

            // (2) Servicos do dominio
            val jwt = JwtService(environment.config)
            val mcEngine = MonteCarloEngine()
            val optEngine = OptimizationEngine(mcEngine)
            val advisor = RebalancingAdvisor()
            val goalRepo = GoalRepositoryImpl()
            ctx = TestContext(jwt)

            // (3) Plugins minimos
            configureSerialization()
            configureSecurity(jwt)
            routing {
                optimizationRoutes(
                    goalRepository = goalRepo,
                    optimizationEngine = optEngine,
                    rebalancingAdvisor = advisor,
                )
            }
        }

        // Forca a inicializacao da application chamando algo que exija ela ativa.
        // Em Ktor 3.x, application{} e diferido ate a primeira request — entao
        // garantimos a inicializacao com uma chamada dummy ao /health (nao
        // existe nesse setup, mas qualquer chamada dispara o lifecycle).
        // Alternativa: usar startApplication() explicitamente.
        startApplication()
        assertNotNull(ctx) // garante que o bloco application{} ja rodou
        return ctx
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) {
            json(testJson)
        }
    }

    /**
     * Request padrao usado em multiplos testes — cenario viavel padrao.
     * simulationsPerStep e verificationSimulations baixos para acelerar testes.
     */
    private fun validContributionRequest(): OptimizeContributionRequest =
        OptimizeContributionRequest(
            initialCapital = 50_000.0,
            expectedReturnAnnual = 0.08,
            volatilityAnnual = 0.15,
            targetAmount = 500_000.0,
            horizonMonths = 240,
            targetSuccessProbability = 0.80,
            simulationsPerStep = 500,
            verificationSimulations = 5_000,
            seed = 42L,
        )
}
