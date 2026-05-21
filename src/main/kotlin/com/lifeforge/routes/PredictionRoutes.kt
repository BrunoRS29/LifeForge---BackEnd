package com.lifeforge.routes

import com.lifeforge.domain.repository.PredictionRepository
import com.lifeforge.dto.ErrorResponse
import com.lifeforge.dto.PredictExpensesCategoryResponse
import com.lifeforge.dto.PredictExpensesRequest
import com.lifeforge.dto.PredictExpensesResponse
import com.lifeforge.dto.PredictIncomePointResponse
import com.lifeforge.dto.PredictIncomeRequest
import com.lifeforge.dto.PredictIncomeResponse
import com.lifeforge.dto.PredictionSummaryResponse
import com.lifeforge.ml.MlClientException
import com.lifeforge.ml.MlInternalError
import com.lifeforge.ml.MlPredictionService
import com.lifeforge.ml.MlUnavailableError
import com.lifeforge.ml.MlValidationError
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Rotas HTTP para o subsistema de predicoes (Sprint 5).
 *
 * Endpoints (todos sob /api/v1/predictions, exigem JWT):
 *   POST /income      -> roda regressao linear e persiste resultado
 *   POST /expenses    -> roda random forest e persiste resultado
 *   GET  /            -> lista predicoes recentes do usuario (auditoria)
 *
 * Erros do microsservico Python sao mapeados em HTTP status:
 *  - MlValidationError  -> 422 (proxy do erro 422 do Python)
 *  - MlUnavailableError -> 503
 *  - MlInternalError    -> 502
 */
fun Route.predictionRoutes(
    predictionService: MlPredictionService,
    predictionRepository: PredictionRepository,
) {
    authenticate("auth-jwt") {
        route("/api/v1/predictions") {

            // ----------------------------------------------------------------
            // POST /api/v1/predictions/income
            // ----------------------------------------------------------------
            post("/income") {
                val userId = call.userId()
                val request = call.receive<PredictIncomeRequest>()

                if (request.horizonMonths !in 1..60) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            "VALIDATION",
                            "horizonMonths deve estar em [1, 60]",
                        ),
                    )
                }

                runCatching {
                    predictionService.predictIncomeFor(userId, request.horizonMonths)
                }
                    .onSuccess { outcome ->
                        val r = outcome.response
                        call.respond(
                            HttpStatusCode.Created,
                            PredictIncomeResponse(
                                predictionId = outcome.prediction.id,
                                modelName = r.modelName,
                                horizonMonths = r.horizonMonths,
                                projection = r.projection.map {
                                    PredictIncomePointResponse(
                                        monthIndex = it.monthIndex,
                                        predictedAmount = it.predictedAmount,
                                    )
                                },
                                expectedMonthlyIncome = r.expectedMonthlyIncome,
                                annualGrowthRate = r.annualGrowthRate,
                                residualVolatilityMonthly = r.residualVolatilityMonthly,
                                mae = r.metrics.mae,
                                rmse = r.metrics.rmse,
                                r2 = r.metrics.r2,
                                createdAt = outcome.prediction.createdAt.toString(),
                            ),
                        )
                    }
                    .onFailure { call.respondMlError(it) }
            }

            // ----------------------------------------------------------------
            // POST /api/v1/predictions/expenses
            // ----------------------------------------------------------------
            post("/expenses") {
                val userId = call.userId()
                val request = call.receive<PredictExpensesRequest>()

                if (request.horizonMonths !in 1..12) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            "VALIDATION",
                            "horizonMonths deve estar em [1, 12]",
                        ),
                    )
                }

                runCatching {
                    predictionService.predictExpensesFor(userId, request.horizonMonths)
                }
                    .onSuccess { outcome ->
                        val r = outcome.response
                        call.respond(
                            HttpStatusCode.Created,
                            PredictExpensesResponse(
                                predictionId = outcome.prediction.id,
                                modelName = r.modelName,
                                horizonMonths = r.horizonMonths,
                                byCategory = r.byCategory.map {
                                    PredictExpensesCategoryResponse(
                                        category = it.category,
                                        predictedAmount = it.predictedAmount,
                                    )
                                },
                                expectedMonthlyExpense = r.expectedMonthlyExpense,
                                mae = r.metrics.mae,
                                rmse = r.metrics.rmse,
                                r2 = r.metrics.r2,
                                createdAt = outcome.prediction.createdAt.toString(),
                            ),
                        )
                    }
                    .onFailure { call.respondMlError(it) }
            }

            // ----------------------------------------------------------------
            // GET /api/v1/predictions
            // ----------------------------------------------------------------
            get {
                val userId = call.userId()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                    ?.coerceIn(1, 200) ?: 50
                val items = predictionRepository.findAllByUser(userId, limit)
                    .map {
                        PredictionSummaryResponse(
                            id = it.id,
                            modelName = it.modelName,
                            errorMetric = it.errorMetric?.toDouble(),
                            createdAt = it.createdAt.toString(),
                        )
                    }
                call.respond(items)
            }
        }
    }
}

/**
 * Helper compartilhado: mapeia [MlClientException] em status HTTP coerente.
 * Vive como extension function de [io.ktor.server.application.ApplicationCall]
 * para que tanto PredictionRoutes quanto SimulationRoutes (rota calibrada)
 * usem o mesmo mapping sem duplicar logica.
 */
suspend fun io.ktor.server.application.ApplicationCall.respondMlError(error: Throwable) {
    when (error) {
        is MlValidationError -> respond(
            HttpStatusCode.UnprocessableEntity,
            ErrorResponse(error.code, error.message ?: "Erro de validacao no ML"),
        )
        is MlUnavailableError -> respond(
            HttpStatusCode.ServiceUnavailable,
            ErrorResponse(error.code, error.message ?: "Servico de ML indisponivel"),
        )
        is MlInternalError -> respond(
            HttpStatusCode.BadGateway,
            ErrorResponse(error.code, error.message ?: "Erro no servico de ML"),
        )
        is MlClientException -> respond(
            HttpStatusCode.BadGateway,
            ErrorResponse(error.code, error.message ?: "Erro no servico de ML"),
        )
        else -> respond(
            HttpStatusCode.InternalServerError,
            ErrorResponse("INTERNAL_ERROR", error.message ?: "Erro interno"),
        )
    }
}
