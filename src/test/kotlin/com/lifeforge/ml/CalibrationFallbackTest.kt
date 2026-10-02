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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Recuo da calibracao por IA (partida a frio) - TCC, Secoes 4.7 e 5.2.
 *
 * Cobre: medias simples e leitura do perfil ([ColdStartCalibration]), a
 * resolucao dos insumos quando os modelos nao podem rodar e a calibracao com
 * recuo ([MlPredictionService.calibrateWithFallback]).
 */
class CalibrationFallbackTest : StringSpec({

    val now = Instant.parse("2026-06-15T12:00:00Z")

    // ----------------------------------------------------------------
    // Fakes
    // ----------------------------------------------------------------

    class FakeIncomeRepo(private val items: List<Income>) : IncomeRepository {
        override suspend fun create(
            userId: Long, source: String, amount: BigDecimal,
            incomeType: IncomeType, recurring: Boolean, receivedAt: Instant,
            scheduleId: Long?,
        ): Income = throw NotImplementedError()
        override suspend fun findAllByUser(userId: Long): List<Income> = items
        override suspend fun findById(id: Long, userId: Long): Income? = null
        override suspend fun update(
            id: Long, userId: Long, source: String, amount: BigDecimal,
            incomeType: IncomeType, recurring: Boolean, receivedAt: Instant,
        ): Income? = null
        override suspend fun findByScheduleId(userId: Long, scheduleId: Long): List<Income> = emptyList()
        override suspend fun delete(id: Long, userId: Long): Boolean = false
        override suspend fun deleteByScheduleId(userId: Long, scheduleId: Long, futureAfter: Instant?): Int = 0
        override suspend fun deleteAllByUser(userId: Long): Int = 0
    }

    class FakeExpenseRepo(private val items: List<Expense>) : ExpenseRepository {
        override suspend fun create(
            userId: Long, description: String, amount: BigDecimal,
            category: ExpenseCategory, recurring: Boolean, spentAt: Instant,
            scheduleId: Long?,
        ): Expense = throw NotImplementedError()
        override suspend fun findAllByUser(userId: Long): List<Expense> = items
        override suspend fun findById(id: Long, userId: Long): Expense? = null
        override suspend fun update(
            id: Long, userId: Long, description: String, amount: BigDecimal,
            category: ExpenseCategory, recurring: Boolean, spentAt: Instant,
        ): Expense? = null
        override suspend fun findByScheduleId(userId: Long, scheduleId: Long): List<Expense> = emptyList()
        override suspend fun delete(id: Long, userId: Long): Boolean = false
        override suspend fun deleteByScheduleId(userId: Long, scheduleId: Long, futureAfter: Instant?): Int = 0
        override suspend fun deleteAllByUser(userId: Long): Int = 0
    }

    class FakePredictionRepo : PredictionRepository {
        private var seq = 0L
        override suspend fun create(
            userId: Long, modelName: String,
            input: JsonElement, output: JsonElement, errorMetric: BigDecimal?,
        ): Prediction = Prediction(++seq, userId, modelName, input, output, errorMetric, Instant.now())
        override suspend fun findAllByUser(userId: Long, limit: Int): List<Prediction> = emptyList()
        override suspend fun findById(id: Long, userId: Long): Prediction? = null
        override suspend fun findLatestByUserAndModel(userId: Long, modelName: String): Prediction? = null
    }

    fun income(amount: String, daysAgo: Long) = Income(
        id = daysAgo, userId = 1L, source = "salario", amount = BigDecimal(amount),
        incomeType = IncomeType.SALARY, recurring = true,
        receivedAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS), createdAt = Instant.now(),
    )

    fun expense(amount: String, daysAgo: Long) = Expense(
        id = daysAgo, userId = 1L, description = "Mercado", amount = BigDecimal(amount),
        category = ExpenseCategory.FOOD, recurring = false,
        spentAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS), createdAt = Instant.now(),
    )

    /** Cliente cujo microsservico responde 503 a tudo (indisponivel). */
    fun unavailableMl(): MlClient = MlClient(
        config = MlClientConfig(baseUrl = "http://mock", maxRetries = 0, retryBaseDelay = 1.milliseconds),
        engine = MockEngine { respond("down", HttpStatusCode.ServiceUnavailable) },
    )

    /** Cliente que responde as predicoes com sucesso (renda 5.100, despesa 1.500). */
    fun healthyMl(): MlClient = MlClient(
        config = MlClientConfig(baseUrl = "http://mock", retryBaseDelay = 1.milliseconds),
        engine = MockEngine { request ->
            val body = if (request.url.encodedPath.endsWith("/predict/income")) {
                """{"model_name":"INCOME_REGRESSION","horizon_months":12,
                   "projection":[{"month_index":1,"predicted_amount":5100.0}],
                   "expected_monthly_income":5100.0,"annual_growth_rate":0.1,
                   "residual_volatility_monthly":200.0,
                   "metrics":{"mae":50.0,"rmse":70.0,"r2":0.9,"n_train":18,"n_test":6}}"""
            } else {
                """{"model_name":"EXPENSE_RANDOM_FOREST","horizon_months":1,
                   "by_category":[{"category":"FOOD","predicted_amount":1500.0}],
                   "expected_monthly_expense":1500.0,
                   "metrics":{"mae":100.0,"rmse":150.0,"r2":0.6,"n_train":12,"n_test":4}}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    fun service(ml: MlClient, incomes: List<Income>, expenses: List<Expense>) =
        MlPredictionService(ml, FakeIncomeRepo(incomes), FakeExpenseRepo(expenses), FakePredictionRepo())

    val base = MonteCarloParameters(
        initialCapital = 10_000.0, monthlyContribution = 0.0,
        expectedReturnAnnual = 0.11, volatilityAnnual = 0.03,
        horizonMonths = 120, targetAmount = 500_000.0, numSimulations = 10_000, seed = 42L,
    )

    // ----------------------------------------------------------------
    // ColdStartCalibration (funcoes puras)
    // ----------------------------------------------------------------

    "monthlyAverage divide pelo numero de meses distintos com lancamento" {
        val entries = listOf(
            Instant.parse("2026-05-05T00:00:00Z") to BigDecimal("1000"),
            Instant.parse("2026-05-20T00:00:00Z") to BigDecimal("500"),
            Instant.parse("2026-06-05T00:00:00Z") to BigDecimal("1500"),
        )
        ColdStartCalibration.monthlyAverage(entries, now) shouldBe (1500.0 plusOrMinus 1e-9)
    }

    "monthlyAverage ignora lancamentos futuros e anteriores a janela de 12 meses" {
        val entries = listOf(
            Instant.parse("2026-06-01T00:00:00Z") to BigDecimal("2000"),
            Instant.parse("2026-09-01T00:00:00Z") to BigDecimal("9999"), // futuro (agendado)
            Instant.parse("2024-01-01T00:00:00Z") to BigDecimal("9999"), // fora da janela
        )
        ColdStartCalibration.monthlyAverage(entries, now) shouldBe (2000.0 plusOrMinus 1e-9)
        ColdStartCalibration.monthlyAverage(emptyList(), now).shouldBeNull()
    }

    "parseAmount entende os formatos digitados no perfil" {
        ColdStartCalibration.parseAmount("8.500,00") shouldBe 8500.0
        ColdStartCalibration.parseAmount("8500") shouldBe 8500.0
        ColdStartCalibration.parseAmount("1500.50") shouldBe 1500.5
        ColdStartCalibration.parseAmount("R$ 1.200") shouldBe 1200.0
        ColdStartCalibration.parseAmount("").shouldBeNull()
        ColdStartCalibration.parseAmount("abc").shouldBeNull()
        ColdStartCalibration.parseAmount("0").shouldBeNull()
        ColdStartCalibration.parseAmount(null).shouldBeNull()
    }

    "profileString le campos textuais do perfil" {
        val profile = buildJsonObject { put("monthlySalary", "7.000,00"); put("age", 30) }
        ColdStartCalibration.profileString(profile, "monthlySalary") shouldBe "7.000,00"
        ColdStartCalibration.profileString(profile, "inexistente").shouldBeNull()
        ColdStartCalibration.profileString(null, "monthlySalary").shouldBeNull()
    }

    // ----------------------------------------------------------------
    // Resolucao dos insumos + calibracao com recuo
    // ----------------------------------------------------------------

    "historico curto: renda do perfil, despesa pela media e notas explicando o recuo" {
        val svc = service(
            ml = unavailableMl(), // nem chega a ser chamado: o minimo e checado antes do HTTP
            incomes = listOf(income("5000", 10), income("5000", 40)),
            expenses = listOf(expense("1200", 10), expense("800", 40)),
        )
        val profile = buildJsonObject { put("monthlySalary", "6.000,00") }

        val inputs = svc.resolveCalibrationInputs(userId = 1L, incomeHorizonMonths = 12, profile = profile)

        inputs.incomeSource shouldBe ColdStartCalibration.Source.PROFILE
        inputs.monthlyIncome shouldBe 6000.0
        inputs.expenseSource shouldBe ColdStartCalibration.Source.HISTORY_AVERAGE
        inputs.monthlyExpense.shouldNotBeNull()
        inputs.notes.shouldNotBeEmpty()
        inputs.notes.first() shouldContain "renda"

        val cal = svc.calibrateWithFallback(base, inputs, referenceIncomeVolatilityAnnual = 0.05)
            .shouldNotBeNull()
        cal.contributionSource shouldBe ContributionSource.PREDICTIONS
        cal.appliedContribution shouldBe ((6000.0 - inputs.monthlyExpense!!) plusOrMinus 1e-9)
        cal.parameters.monthlyContribution shouldBe cal.appliedContribution
        // A carteira mantem a volatilidade de mercado (3%); a volatilidade de renda
        // tipica do vinculo (5% a.a. sobre R$ 6.000) vira variacao do aporte.
        cal.appliedVolatilityAnnual shouldBe 0.03
        val incomeStd = 0.05 / kotlin.math.sqrt(12.0) * 6000.0
        cal.contributionVariationMonthly shouldBe ((incomeStd / cal.appliedContribution) plusOrMinus 1e-9)
        cal.fallbackNotes.shouldNotBeEmpty()
    }

    "sem salario no perfil, a renda vem da media simples do historico" {
        val svc = service(
            ml = unavailableMl(),
            incomes = listOf(income("4000", 5), income("4000", 35)),
            expenses = listOf(expense("1000", 5)),
        )
        val inputs = svc.resolveCalibrationInputs(1L, 12, profile = null)
        inputs.incomeSource shouldBe ColdStartCalibration.Source.HISTORY_AVERAGE
        inputs.monthlyIncome.shouldNotBeNull()
    }

    "sem renda nem despesa estimaveis, usa o aporte declarado no perfil" {
        val svc = service(ml = unavailableMl(), incomes = emptyList(), expenses = emptyList())
        val profile = buildJsonObject { put("monthlyContribution", "1.500,00") }

        val inputs = svc.resolveCalibrationInputs(1L, 12, profile)
        val cal = svc.calibrateWithFallback(base, inputs, referenceIncomeVolatilityAnnual = null)
            .shouldNotBeNull()

        cal.contributionSource shouldBe ContributionSource.PROFILE
        cal.appliedContribution shouldBe 1500.0
        // Sem vinculo conhecido, a volatilidade de mercado e mantida e o aporte nao varia.
        cal.appliedVolatilityAnnual shouldBe 0.03
        cal.contributionVariationMonthly shouldBe 0.0
    }

    "sem nenhum dado, nao ha como calibrar (rota responde 422)" {
        val svc = service(ml = unavailableMl(), incomes = emptyList(), expenses = emptyList())
        val inputs = svc.resolveCalibrationInputs(1L, 12, profile = null)
        svc.calibrateWithFallback(base, inputs, referenceIncomeVolatilityAnnual = 0.05).shouldBeNull()
    }

    "microsservico indisponivel com historico longo: recua para as medias e registra o motivo" {
        val incomes = (0 until 8).map { income("5000", 5L + 30L * it) }
        val expenses = (0 until 14).map { expense("1000", 5L + 25L * it) }
        val svc = service(ml = unavailableMl(), incomes = incomes, expenses = expenses)

        val inputs = svc.resolveCalibrationInputs(1L, 12, profile = null)

        inputs.incomePrediction.shouldBeNull()
        inputs.expensePrediction.shouldBeNull()
        inputs.incomeSource shouldBe ColdStartCalibration.Source.HISTORY_AVERAGE
        inputs.expenseSource shouldBe ColdStartCalibration.Source.HISTORY_AVERAGE
        inputs.notes.any { it.contains("indisponível") } shouldBe true
    }

    "com os dois modelos disponiveis, a calibracao com recuo equivale a calibracao por IA" {
        val incomes = (0 until 8).map { income("5000", 5L + 30L * it) }
        val expenses = (0 until 14).map { expense("1000", 5L + 25L * it) }
        val svc = service(ml = healthyMl(), incomes = incomes, expenses = expenses)

        val inputs = svc.resolveCalibrationInputs(1L, 12, profile = null)
        inputs.notes.shouldBeEmpty()
        val cal = svc.calibrateWithFallback(base, inputs, referenceIncomeVolatilityAnnual = 0.99)
            .shouldNotBeNull()
        val pure = svc.calibrate(base, inputs.incomePrediction!!.response, inputs.expensePrediction!!.response)

        cal.appliedContribution shouldBe pure.appliedContribution
        cal.appliedVolatilityAnnual shouldBe pure.appliedVolatilityAnnual
        cal.contributionVariationMonthly shouldBe pure.contributionVariationMonthly // ignora a referencia
        cal.incomeSource shouldBe ColdStartCalibration.Source.ML_MODEL
        cal.expenseSource shouldBe ColdStartCalibration.Source.ML_MODEL
    }
})
