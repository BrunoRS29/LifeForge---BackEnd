package com.lifeforge.ml

import com.lifeforge.domain.model.Expense
import com.lifeforge.domain.model.ExpenseCategory
import com.lifeforge.domain.model.Income
import com.lifeforge.domain.model.IncomeType
import com.lifeforge.domain.model.Prediction
import com.lifeforge.domain.repository.ExpenseRepository
import com.lifeforge.domain.repository.IncomeRepository
import com.lifeforge.domain.repository.PredictionRepository
import com.lifeforge.engine.montecarlo.MonteCarloParameters
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.math.BigDecimal
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement

/**
 * Testes do [MlPredictionService] usando MockEngine para o ML
 * e fakes para os repositorios. Foca em:
 *  - happy path: chama o ML, persiste, devolve outcome
 *  - validacao previa: historico curto nao chega a chamar o ML
 *  - calibracao: monthlyContribution = income - expense, capado em 0
 *  - calibracao: volatilidade combina renda e mercado (pega o maior)
 */
class MlPredictionServiceTest : StringSpec({

    // ----------------------------------------------------------------
    // Fakes
    // ----------------------------------------------------------------

    class FakeIncomeRepo(private val items: List<Income>) : IncomeRepository {
        override suspend fun create(
            userId: Long, source: String, amount: java.math.BigDecimal,
            incomeType: IncomeType, recurring: Boolean, receivedAt: Instant,
            scheduleId: Long?,
        ): Income = throw NotImplementedError()
        override suspend fun findAllByUser(userId: Long): List<Income> = items
        override suspend fun findById(id: Long, userId: Long): Income? = null
        override suspend fun findByScheduleId(userId: Long, scheduleId: Long): List<Income> = emptyList()
        override suspend fun delete(id: Long, userId: Long): Boolean = false
        override suspend fun deleteByScheduleId(userId: Long, scheduleId: Long, futureAfter: Instant?): Int = 0
    }

    class FakeExpenseRepo(private val items: List<Expense>) : ExpenseRepository {
        override suspend fun create(
            userId: Long, description: String, amount: java.math.BigDecimal,
            category: ExpenseCategory, recurring: Boolean, spentAt: Instant,
            scheduleId: Long?,
        ): Expense = throw NotImplementedError()
        override suspend fun findAllByUser(userId: Long): List<Expense> = items
        override suspend fun findById(id: Long, userId: Long): Expense? = null
        override suspend fun findByScheduleId(userId: Long, scheduleId: Long): List<Expense> = emptyList()
        override suspend fun delete(id: Long, userId: Long): Boolean = false
        override suspend fun deleteByScheduleId(userId: Long, scheduleId: Long, futureAfter: Instant?): Int = 0
    }

    class FakePredictionRepo : PredictionRepository {
        val created = mutableListOf<Prediction>()
        private var seq = 0L
        override suspend fun create(
            userId: Long, modelName: String,
            input: JsonElement, output: JsonElement, errorMetric: BigDecimal?,
        ): Prediction {
            val p = Prediction(
                id = ++seq,
                userId = userId,
                modelName = modelName,
                input = input,
                output = output,
                errorMetric = errorMetric,
                createdAt = Instant.now(),
            )
            created += p
            return p
        }
        override suspend fun findAllByUser(userId: Long, limit: Int): List<Prediction> = created
        override suspend fun findById(id: Long, userId: Long): Prediction? =
            created.firstOrNull { it.id == id && it.userId == userId }
        override suspend fun findLatestByUserAndModel(
            userId: Long, modelName: String,
        ): Prediction? = created.lastOrNull {
            it.userId == userId && it.modelName == modelName
        }
    }

    // ----------------------------------------------------------------
    // Builders de payload
    // ----------------------------------------------------------------

    fun incomeHistory(n: Int): List<Income> = List(n) { i ->
        Income(
            id = i.toLong() + 1,
            userId = 1L,
            source = "salario",
            amount = BigDecimal("5000.00"),
            incomeType = IncomeType.SALARY,
            recurring = true,
            receivedAt = Instant.parse("2024-01-05T00:00:00Z").plusSeconds(86400L * 30 * i),
            createdAt = Instant.now(),
        )
    }

    fun expenseHistory(n: Int): List<Expense> = List(n) { i ->
        Expense(
            id = i.toLong() + 1,
            userId = 1L,
            description = "Mercado",
            amount = BigDecimal("1500.00"),
            category = ExpenseCategory.FOOD,
            recurring = false,
            spentAt = Instant.parse("2024-01-05T00:00:00Z").plusSeconds(86400L * 30 * i),
            createdAt = Instant.now(),
        )
    }

    val incomeOkBody = """
    {
      "model_name": "INCOME_REGRESSION",
      "horizon_months": 12,
      "projection": [{"month_index": 1, "predicted_amount": 5100.0}],
      "expected_monthly_income": 5100.0,
      "annual_growth_rate": 0.10,
      "residual_volatility_monthly": 200.0,
      "metrics": {"mae": 50.0, "rmse": 70.0, "r2": 0.9, "n_train": 18, "n_test": 6}
    }
    """.trimIndent()

    val expenseOkBody = """
    {
      "model_name": "EXPENSE_RANDOM_FOREST",
      "horizon_months": 1,
      "by_category": [{"category": "FOOD", "predicted_amount": 1500.0}],
      "expected_monthly_expense": 1500.0,
      "metrics": {"mae": 100.0, "rmse": 150.0, "r2": 0.6, "n_train": 12, "n_test": 4}
    }
    """.trimIndent()

    fun mockClient(handler: io.ktor.client.engine.mock.MockRequestHandler): MlClient =
        MlClient(
            config = MlClientConfig(
                baseUrl = "http://mock",
                retryBaseDelay = 1.milliseconds,
            ),
            engine = MockEngine(handler),
        )

    // ----------------------------------------------------------------
    // Testes
    // ----------------------------------------------------------------

    "predictIncomeFor: historico curto lanca InsufficientData antes do HTTP" {
        runTest {
            var httpCalls = 0
            val client = mockClient { _ ->
                httpCalls++
                respond("{}", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val service = MlPredictionService(
                client,
                FakeIncomeRepo(incomeHistory(3)), // < 6
                FakeExpenseRepo(emptyList()),
                FakePredictionRepo(),
            )

            client.use {
                val ex = shouldThrow<MlValidationError> {
                    service.predictIncomeFor(userId = 1L, horizonMonths = 6)
                }
                ex.code shouldBe "INSUFFICIENT_DATA"
            }
            httpCalls shouldBe 0
        }
    }

    "predictIncomeFor: happy path persiste predicao com mae em errorMetric" {
        runTest {
            val predRepo = FakePredictionRepo()
            val client = mockClient { _ ->
                respond(incomeOkBody, HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val service = MlPredictionService(
                client,
                FakeIncomeRepo(incomeHistory(12)),
                FakeExpenseRepo(emptyList()),
                predRepo,
            )

            client.use {
                val outcome = service.predictIncomeFor(userId = 1L, horizonMonths = 6)
                outcome.response.expectedMonthlyIncome shouldBe 5100.0
                outcome.prediction.modelName shouldBe "INCOME_REGRESSION"
                outcome.prediction.errorMetric!!.toDouble() shouldBe 50.0
            }
            predRepo.created.size shouldBe 1
        }
    }

    "predictIncomeFor: recebimentos futuros nao contam para o minimo (filtro temporal)" {
        runTest {
            var httpCalls = 0
            val client = mockClient { _ ->
                httpCalls++
                respond(incomeOkBody, HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val now = Instant.now()
            // 5 recebimentos passados + 10 futuros: apenas os 5 passados contam
            // (< 6) -> deve lancar antes de qualquer chamada HTTP ao ML.
            val past = List(5) { i ->
                Income(
                    id = i + 1L, userId = 1L, source = "salario",
                    amount = BigDecimal("5000.00"), incomeType = IncomeType.SALARY,
                    recurring = true, receivedAt = now.minusSeconds(86_400L * 30 * (i + 1)),
                    createdAt = now,
                )
            }
            val future = List(10) { i ->
                Income(
                    id = 100L + i, userId = 1L, source = "salario",
                    amount = BigDecimal("5000.00"), incomeType = IncomeType.SALARY,
                    recurring = true, receivedAt = now.plusSeconds(86_400L * 30 * (i + 1)),
                    createdAt = now,
                )
            }
            val service = MlPredictionService(
                client, FakeIncomeRepo(past + future),
                FakeExpenseRepo(emptyList()), FakePredictionRepo(),
            )
            client.use {
                val ex = shouldThrow<MlValidationError> {
                    service.predictIncomeFor(userId = 1L, horizonMonths = 6)
                }
                ex.code shouldBe "INSUFFICIENT_DATA"
            }
            httpCalls shouldBe 0
        }
    }

    "predictExpensesFor: historico < 12 lanca InsufficientData" {
        runTest {
            val client = mockClient { _ -> respond("{}", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")) }
            val service = MlPredictionService(
                client,
                FakeIncomeRepo(emptyList()),
                FakeExpenseRepo(expenseHistory(5)),
                FakePredictionRepo(),
            )
            client.use {
                shouldThrow<MlValidationError> {
                    service.predictExpensesFor(userId = 1L)
                }
            }
        }
    }

    // ----------------------------------------------------------------
    // Calibracao
    // ----------------------------------------------------------------

    "calibrate: monthlyContribution = income - expense quando positivo" {
        val client = mockClient { _ -> respond("{}", HttpStatusCode.OK) }
        val service = MlPredictionService(
            client, FakeIncomeRepo(emptyList()),
            FakeExpenseRepo(emptyList()), FakePredictionRepo(),
        )
        client.use {
            val base = MonteCarloParameters(
                initialCapital = 10_000.0,
                monthlyContribution = 0.0,
                expectedReturnAnnual = 0.08,
                volatilityAnnual = 0.15,
                horizonMonths = 120,
                targetAmount = 1_000_000.0,
            )
            val income = IncomePredictionResponseDto(
                modelName = "INCOME_REGRESSION", horizonMonths = 12,
                projection = emptyList(),
                expectedMonthlyIncome = 5000.0,
                annualGrowthRate = 0.10,
                residualVolatilityMonthly = 100.0,
                metrics = ModelMetricsDto(0.0, 0.0, 0.0, 1, 1),
            )
            val expense = ExpensePredictionResponseDto(
                modelName = "EXPENSE_RANDOM_FOREST", horizonMonths = 1,
                byCategory = emptyList(),
                expectedMonthlyExpense = 3500.0,
                metrics = ModelMetricsDto(0.0, 0.0, 0.0, 1, 1),
            )
            val calibration = service.calibrate(base, income, expense)
            calibration.appliedContribution shouldBe 1500.0
            calibration.rawContribution shouldBe 1500.0
        }
    }

    "calibrate: contribuicao capada em zero quando despesa > renda" {
        val client = mockClient { _ -> respond("{}", HttpStatusCode.OK) }
        val service = MlPredictionService(
            client, FakeIncomeRepo(emptyList()),
            FakeExpenseRepo(emptyList()), FakePredictionRepo(),
        )
        client.use {
            val base = MonteCarloParameters(
                initialCapital = 0.0, monthlyContribution = 0.0,
                expectedReturnAnnual = 0.08, volatilityAnnual = 0.15,
                horizonMonths = 12, targetAmount = 1000.0,
            )
            val income = IncomePredictionResponseDto(
                "INCOME_REGRESSION", 12, emptyList(),
                expectedMonthlyIncome = 2000.0,
                annualGrowthRate = 0.0,
                residualVolatilityMonthly = 0.0,
                metrics = ModelMetricsDto(0.0, 0.0, 0.0, 1, 1),
            )
            val expense = ExpensePredictionResponseDto(
                "EXPENSE_RANDOM_FOREST", 1, emptyList(),
                expectedMonthlyExpense = 2500.0,
                metrics = ModelMetricsDto(0.0, 0.0, 0.0, 1, 1),
            )
            val calibration = service.calibrate(base, income, expense)
            calibration.rawContribution shouldBe -500.0
            calibration.appliedContribution shouldBe 0.0
        }
    }

    "calibrate: volatilidade pega o maior entre mercado e renda anualizada" {
        val client = mockClient { _ -> respond("{}", HttpStatusCode.OK) }
        val service = MlPredictionService(
            client, FakeIncomeRepo(emptyList()),
            FakeExpenseRepo(emptyList()), FakePredictionRepo(),
        )
        client.use {
            val base = MonteCarloParameters(
                initialCapital = 1000.0, monthlyContribution = 0.0,
                expectedReturnAnnual = 0.08, volatilityAnnual = 0.05, // baixa
                horizonMonths = 12, targetAmount = 1000.0,
            )
            // Renda com volatilidade alta:
            // sigma_anual = 500 * sqrt(12) / 5000 = 500 * 3.464 / 5000 ~ 0.346
            val income = IncomePredictionResponseDto(
                "INCOME_REGRESSION", 12, emptyList(),
                expectedMonthlyIncome = 5000.0,
                annualGrowthRate = 0.0,
                residualVolatilityMonthly = 500.0,
                metrics = ModelMetricsDto(0.0, 0.0, 0.0, 1, 1),
            )
            val expense = ExpensePredictionResponseDto(
                "EXPENSE_RANDOM_FOREST", 1, emptyList(),
                expectedMonthlyExpense = 1500.0,
                metrics = ModelMetricsDto(0.0, 0.0, 0.0, 1, 1),
            )
            val cal = service.calibrate(base, income, expense)
            cal.appliedVolatilityAnnual shouldBe (0.346 plusOrMinus 0.01)
            // Confirma que pegou a maior, nao o base 0.05
            cal.appliedVolatilityAnnual shouldBeGreaterThanOrEqualTo base.volatilityAnnual
        }
    }
})
