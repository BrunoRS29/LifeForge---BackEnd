package com.lifeforge.routes

import com.lifeforge.data.repository.AssetRepositoryImpl
import com.lifeforge.data.repository.ExpenseRepositoryImpl
import com.lifeforge.data.repository.GoalRepositoryImpl
import com.lifeforge.data.repository.IncomeRepositoryImpl
import com.lifeforge.data.repository.UserProfileRepositoryImpl
import com.lifeforge.data.repository.UserRepositoryImpl
import com.lifeforge.data.tables.Assets
import com.lifeforge.data.tables.ExpenseSchedules
import com.lifeforge.data.tables.Expenses
import com.lifeforge.data.tables.Goals
import com.lifeforge.data.tables.IncomeSchedules
import com.lifeforge.data.tables.Incomes
import com.lifeforge.data.tables.UserProfiles
import com.lifeforge.data.tables.Users
import com.lifeforge.domain.model.AssetType
import com.lifeforge.domain.model.ExpenseCategory
import com.lifeforge.domain.model.IncomeType
import com.lifeforge.domain.model.RiskProfile
import com.lifeforge.dto.DashboardResponse
import com.lifeforge.plugins.configureHTTP
import com.lifeforge.plugins.configureSecurity
import com.lifeforge.plugins.configureSerialization
import com.lifeforge.security.JwtService
import io.kotest.matchers.bigdecimal.shouldBeEqualIgnoringScale
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test

/**
 * GET /api/v1/dashboard com 18 meses de historico em que aluguel, escola e
 * salario foram lancados todo mes como recorrentes (como na importacao de
 * extratos). Regressao: o painel somava os 18 meses e mostrava uma despesa
 * mensal de dezenas de milhares e taxa de poupanca negativa.
 */
class DashboardRoutesTest {

    private val testJson = Json { ignoreUnknownKeys = true }
    private val clock = Clock.fixed(Instant.parse("2026-10-02T15:00:00Z"), ZoneOffset.UTC)
    private val months = (17 downTo 0).map { YearMonth.of(2026, 9).minusMonths(it.toLong()) }

    @Test
    fun `series recorrentes contam uma vez por mes`() = testApplication {
        val token = setupTestApp("dashTest1", profileSalary = null)
        val dash = client().get("/api/v1/dashboard") { bearerAuth(token) }

        dash.status shouldBe HttpStatusCode.OK
        val body = dash.body<DashboardResponse>()
        // 2800 (aluguel) + 950 (escola) + 1400 (media dos pontuais)
        BigDecimal(body.monthlyExpenses) shouldBeEqualIgnoringScale BigDecimal("5150")
        // salario inferido uma vez (valor mais recente) + 40000 * 0,12 / 12 dos ativos
        BigDecimal(body.monthlyIncome) shouldBeEqualIgnoringScale BigDecimal("9900")
        // (9900 - 5150) / 9900 = 47,98%
        BigDecimal(body.savingsRate) shouldBeEqualIgnoringScale BigDecimal("47.98")
    }

    @Test
    fun `salario do perfil prevalece sobre o inferido`() = testApplication {
        val token = setupTestApp("dashTest2", profileSalary = "12.000,00")
        val body = client().get("/api/v1/dashboard") { bearerAuth(token) }.body<DashboardResponse>()

        BigDecimal(body.monthlyIncome) shouldBeEqualIgnoringScale BigDecimal("12400")
    }

    // =================================================================
    // Helpers
    // =================================================================

    private suspend fun ApplicationTestBuilder.setupTestApp(dbName: String, profileSalary: String?): String {
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
            TransactionManager.defaultDatabase = db
            transaction(db) {
                SchemaUtils.create(Users, UserProfiles, Goals, IncomeSchedules, Incomes, ExpenseSchedules, Expenses, Assets)
            }
            val jwt = JwtService(environment.config)
            val incomes = IncomeRepositoryImpl()
            val expenses = ExpenseRepositoryImpl()
            val assets = AssetRepositoryImpl()
            val profiles = UserProfileRepositoryImpl()
            token = runBlocking {
                val user = UserRepositoryImpl().create(
                    email = "$dbName@test.com",
                    name = dbName,
                    passwordHash = "test-hash",
                    riskProfile = RiskProfile.MODERATE,
                )
                months.forEachIndexed { i, m ->
                    val day5 = m.atDay(5).atStartOfDay().toInstant(ZoneOffset.UTC)
                    val day10 = m.atDay(10).atStartOfDay().toInstant(ZoneOffset.UTC)
                    val salary = if (i < 12) "9000" else "9500"
                    incomes.create(user.id, "Salário", BigDecimal(salary), IncomeType.SALARY, true, day5)
                    expenses.create(user.id, "Aluguel", BigDecimal("2800"), ExpenseCategory.HOUSING, true, day10)
                    expenses.create(user.id, "Escola infantil", BigDecimal("950"), ExpenseCategory.EDUCATION, true, day10)
                    expenses.create(user.id, "Supermercado", BigDecimal("1400"), ExpenseCategory.FOOD, false, day10)
                }
                assets.create(
                    userId = user.id, name = "Tesouro", assetType = AssetType.FIXED_INCOME,
                    currentValue = BigDecimal("40000"), expectedReturn = BigDecimal("0.12"),
                    volatility = BigDecimal("0.005"),
                )
                if (profileSalary != null) {
                    profiles.upsert(user.id, buildJsonObject { put("monthlySalary", JsonPrimitive(profileSalary)) })
                }
                jwt.generateToken(user.id, user.email)
            }
            configureSerialization()
            configureHTTP()
            configureSecurity(jwt)
            routing {
                dashboardRoutes(incomes, expenses, assets, GoalRepositoryImpl(), profiles, clock)
            }
        }
        startApplication()
        return token
    }

    private fun ApplicationTestBuilder.client() = createClient {
        install(ContentNegotiation) { json(testJson) }
    }
}
