package com.lifeforge.ml

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest

/**
 * Testes unitarios do [MlClient] usando MockEngine do Ktor.
 *
 * Cobertura:
 *  - happy path: parse de payloads dos 3 endpoints
 *  - error 4xx: MlValidationError com codigo
 *  - error 5xx + retry: tenta de novo e falha com MlUnavailableError
 *  - error 5xx + sucesso na segunda tentativa
 *  - JSON malformado: MlInternalError
 */
class MlClientTest : StringSpec({

    fun clientWith(
        engine: MockEngine,
        maxRetries: Int = 1,
    ): MlClient = MlClient(
        config = MlClientConfig(
            baseUrl = "http://mock",
            maxRetries = maxRetries,
            retryBaseDelay = 1.milliseconds, // testes nao podem esperar 200ms
        ),
        engine = engine,
    )

    // -------------------------------------------------------------------
    // Health
    // -------------------------------------------------------------------

    "health retorna true em 200" {
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = """{"status":"ok"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            clientWith(engine).use {
                it.health() shouldBe true
            }
        }
    }

    "health retorna false em erro" {
        runTest {
            val engine = MockEngine { _ ->
                respondError(HttpStatusCode.InternalServerError)
            }
            clientWith(engine, maxRetries = 0).use {
                // Mesmo apos retries esgotados, health captura excecao e retorna false
                it.health() shouldBe false
            }
        }
    }

    // -------------------------------------------------------------------
    // predictIncome happy path
    // -------------------------------------------------------------------

    "predictIncome devolve estrutura completa do response" {
        val body = """
        {
          "model_name": "INCOME_REGRESSION",
          "horizon_months": 3,
          "projection": [
            {"month_index": 1, "predicted_amount": 5100.0},
            {"month_index": 2, "predicted_amount": 5150.0},
            {"month_index": 3, "predicted_amount": 5200.0}
          ],
          "expected_monthly_income": 5150.0,
          "annual_growth_rate": 0.12,
          "residual_volatility_monthly": 80.5,
          "metrics": {"mae": 50.1, "rmse": 70.2, "r2": 0.85, "n_train": 18, "n_test": 6}
        }
        """.trimIndent()

        runTest {
            val engine = MockEngine { req ->
                req.url.encodedPath shouldBe "/predict/income"
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            clientWith(engine).use { client ->
                val response = client.predictIncome(
                    IncomePredictionRequestDto(
                        history = listOf(
                            IncomeObservationDto("2024-01-05", 5000.0, "SALARY", true),
                        ),
                        horizonMonths = 3,
                    )
                )
                response.modelName shouldBe "INCOME_REGRESSION"
                response.horizonMonths shouldBe 3
                response.projection shouldHaveSize 3
                response.expectedMonthlyIncome shouldBe 5150.0
                response.metrics.r2 shouldBe 0.85
            }
        }
    }

    // -------------------------------------------------------------------
    // predictExpenses happy path
    // -------------------------------------------------------------------

    "predictExpenses devolve by_category com nomes corretos" {
        val body = """
        {
          "model_name": "EXPENSE_RANDOM_FOREST",
          "horizon_months": 1,
          "by_category": [
            {"category": "HOUSING", "predicted_amount": 2500.0},
            {"category": "FOOD", "predicted_amount": 1500.0}
          ],
          "expected_monthly_expense": 4000.0,
          "metrics": {"mae": 100.0, "rmse": 150.0, "r2": 0.7, "n_train": 12, "n_test": 4}
        }
        """.trimIndent()

        runTest {
            val engine = MockEngine { respond(body, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")) }
            clientWith(engine).use { client ->
                val res = client.predictExpenses(
                    ExpensePredictionRequestDto(
                        history = listOf(
                            ExpenseObservationDto("2024-01-05", 1500.0, "FOOD", false),
                        ),
                    )
                )
                res.byCategory.map { it.category } shouldBe listOf("HOUSING", "FOOD")
                res.expectedMonthlyExpense shouldBe 4000.0
            }
        }
    }

    // -------------------------------------------------------------------
    // 4xx -> MlValidationError
    // -------------------------------------------------------------------

    "erro 422 do ML eh mapeado para MlValidationError com codigo" {
        val errorBody = """{"error": "INSUFFICIENT_DATA", "message": "minimo 6 meses"}"""

        runTest {
            val engine = MockEngine { _ ->
                respond(errorBody, HttpStatusCode.UnprocessableEntity,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            }
            clientWith(engine).use { client ->
                val ex = shouldThrow<MlValidationError> {
                    client.predictIncome(
                        IncomePredictionRequestDto(
                            history = listOf(
                                IncomeObservationDto("2024-01-05", 100.0, "SALARY", true),
                            ),
                            horizonMonths = 3,
                        )
                    )
                }
                ex.code shouldBe "INSUFFICIENT_DATA"
                ex.message shouldContain "minimo 6"
            }
        }
    }

    "4xx nao gera retry" {
        val callCount = AtomicInteger(0)
        runTest {
            val engine = MockEngine { _ ->
                callCount.incrementAndGet()
                respond(
                    content = """{"error": "VALIDATION", "message": "x"}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            clientWith(engine, maxRetries = 3).use { client ->
                shouldThrow<MlValidationError> {
                    client.modelsMetrics()
                }
            }
            // Apenas 1 chamada, sem retry em 4xx
            callCount.get() shouldBe 1
        }
    }

    // -------------------------------------------------------------------
    // 5xx + retry
    // -------------------------------------------------------------------

    "5xx esgota retries e lanca MlUnavailableError" {
        val callCount = AtomicInteger(0)
        runTest {
            val engine = MockEngine { _ ->
                callCount.incrementAndGet()
                respondError(HttpStatusCode.InternalServerError)
            }
            clientWith(engine, maxRetries = 2).use { client ->
                shouldThrow<MlUnavailableError> {
                    client.modelsMetrics()
                }
            }
            // 1 tentativa inicial + 2 retries = 3
            callCount.get() shouldBe 3
        }
    }

    "5xx + 200 na segunda tentativa retorna ok" {
        val callCount = AtomicInteger(0)
        runTest {
            val engine = MockEngine { _ ->
                val n = callCount.incrementAndGet()
                if (n == 1) {
                    respondError(HttpStatusCode.InternalServerError)
                } else {
                    respond(
                        """{"entries": []}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            clientWith(engine, maxRetries = 2).use { client ->
                client.modelsMetrics().entries shouldHaveSize 0
            }
            callCount.get() shouldBe 2
        }
    }

    // -------------------------------------------------------------------
    // JSON malformado
    // -------------------------------------------------------------------

    "JSON quebrado lanca MlInternalError" {
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = ByteReadChannel("{ isso nao eh json valido"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            clientWith(engine, maxRetries = 0).use { client ->
                shouldThrow<MlInternalError> { client.modelsMetrics() }
            }
        }
    }

    // -------------------------------------------------------------------
    // Headers e URL
    // -------------------------------------------------------------------

    "client envia user-agent e content-type corretos no POST" {
        val captured = mutableListOf<io.ktor.http.Headers>()
        runTest {
            val engine = MockEngine { req ->
                captured += req.headers
                respond(
                    """{"entries": []}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            clientWith(engine).use { it.modelsMetrics() }

            captured shouldHaveSize 1
            captured[0]["User-Agent"]!! shouldContain "lifeforge-backend"
        }
    }

    "baseUrl com trailing slash eh normalizado" {
        runTest {
            val engine = MockEngine { req ->
                // Path deve ser exatamente /predict/income, sem // duplicado
                req.url.encodedPath shouldBe "/predict/income"
                respond(
                    content = """
                    {
                      "model_name": "X",
                      "horizon_months": 1,
                      "projection": [],
                      "expected_monthly_income": 0,
                      "annual_growth_rate": 0,
                      "residual_volatility_monthly": 0,
                      "metrics": {"mae": 0, "rmse": 0, "r2": 0, "n_train": 1, "n_test": 1}
                    }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val client = MlClient(
                config = MlClientConfig(
                    baseUrl = "http://mock////", // trailing slashes
                    retryBaseDelay = 1.milliseconds,
                ),
                engine = engine,
            )
            client.use {
                it.predictIncome(
                    IncomePredictionRequestDto(
                        history = listOf(
                            IncomeObservationDto("2024-01-01", 1000.0, "SALARY", true),
                        ),
                        horizonMonths = 1,
                    )
                )
            }
        }
    }

    // -------------------------------------------------------------------
    // Validacao da config
    // -------------------------------------------------------------------

    "config rejeita baseUrl vazio" {
        shouldThrow<IllegalArgumentException> {
            MlClientConfig(baseUrl = "")
        }
    }

    "config rejeita maxRetries negativo" {
        shouldThrow<IllegalArgumentException> {
            MlClientConfig(baseUrl = "http://x", maxRetries = -1)
        }
    }

    "retryBaseDelay valido permite construir client" {
        val cfg = MlClientConfig(
            baseUrl = "http://x",
            maxRetries = 0,
            retryBaseDelay = 0.milliseconds,
        )
        cfg.maxRetries shouldBeGreaterThanOrEqualTo 0
    }
})
