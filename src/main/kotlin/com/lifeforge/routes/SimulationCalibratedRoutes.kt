package com.lifeforge.routes

import com.lifeforge.domain.model.Simulation
import com.lifeforge.domain.repository.GoalRepository
import com.lifeforge.domain.repository.SimulationRepository
import com.lifeforge.dto.CalibrationSummaryResponse
import com.lifeforge.dto.ErrorResponse
import com.lifeforge.dto.HistogramBucketDto
import com.lifeforge.dto.RunCalibratedSimulationRequest
import com.lifeforge.dto.RunCalibratedSimulationResponse
import com.lifeforge.dto.SimulationResultResponse
import com.lifeforge.engine.montecarlo.MonteCarloEngine
import com.lifeforge.engine.montecarlo.MonteCarloParameters
import com.lifeforge.engine.montecarlo.MonteCarloResult
import com.lifeforge.ml.MlPredictionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Rota da Sprint 5 que executa Monte Carlo com parametros calibrados por IA.
 *
 *   POST /api/v1/simulation/run-calibrated
 *
 * Fluxo:
 *   1. Valida e busca a meta do usuario
 *   2. Chama [MlPredictionService.predictIncomeFor]
 *   3. Chama [MlPredictionService.predictExpensesFor]
 *   4. Aplica [MlPredictionService.calibrate] para derivar parametros
 *   5. Executa a engine Monte Carlo (Dispatchers.Default - CPU bound)
 *   6. Persiste a simulacao na tabela `simulations`
 *   7. Responde com simulacao + sumario da calibracao
 *
 * Vive em arquivo separado de [simulationRoutes] (Sprint 2) para isolar a
 * dependencia do MlPredictionService - o endpoint legado nao precisa dele.
 */
fun Route.simulationCalibratedRoutes(
    simulationRepository: SimulationRepository,
    goalRepository: GoalRepository,
    engine: MonteCarloEngine,
    predictionService: MlPredictionService,
) {
    val json = Json { ignoreUnknownKeys = true }

    authenticate("auth-jwt") {
        route("/api/v1/simulation") {

            post("/run-calibrated") {
                val userId = call.userId()
                val request = call.receive<RunCalibratedSimulationRequest>()

                validate(request)?.let { error ->
                    return@post call.respond(HttpStatusCode.BadRequest, error)
                }

                val goalId = request.goalId.toLongOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("VALIDATION", "goalId invalido"),
                    )
                val goal = goalRepository.findById(goalId, userId)
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("NOT_FOUND", "Meta nao encontrada"),
                    )

                // 1+2. Predicoes (qualquer erro do ML vai para o helper)
                val incomeOutcome = runCatching {
                    predictionService.predictIncomeFor(userId, request.incomeHorizonMonths)
                }.getOrElse {
                    call.respondMlError(it)
                    return@post
                }
                val expenseOutcome = runCatching {
                    predictionService.predictExpensesFor(userId, horizonMonths = 1)
                }.getOrElse {
                    call.respondMlError(it)
                    return@post
                }

                // 3. Parametros base (sem monthlyContribution)
                val baseParams = MonteCarloParameters(
                    initialCapital = request.initialCapital,
                    monthlyContribution = 0.0,  // sera sobrescrito pela calibracao
                    expectedReturnAnnual = request.expectedReturnAnnual,
                    volatilityAnnual = request.volatilityAnnual,
                    horizonMonths = request.horizonMonths,
                    targetAmount = request.targetAmount,
                    unemploymentProbAnnual = request.unemploymentProbAnnual,
                    unemploymentDurationMonths = request.unemploymentDurationMonths,
                    inflationAnnual = request.inflationAnnual,
                    numSimulations = request.numSimulations,
                    seed = request.seed ?: System.currentTimeMillis(),
                )

                // 4. Calibracao
                val calibration = predictionService.calibrate(
                    base = baseParams,
                    income = incomeOutcome.response,
                    expense = expenseOutcome.response,
                )

                // 5. Engine Monte Carlo (CPU-bound -> Dispatchers.Default)
                val result = withContext(Dispatchers.Default) {
                    engine.run(calibration.parameters)
                }

                // 6. Persiste a simulacao - usa `goalId` e armazena parametros
                // calibrados ja com o monthlyContribution derivado
                val parametersJson = Json.parseToJsonElement(
                    json.encodeToString(
                        // serializa os parametros efetivos (ja calibrados)
                        mapOf(
                            "initialCapital" to calibration.parameters.initialCapital,
                            "monthlyContribution" to calibration.parameters.monthlyContribution,
                            "expectedReturnAnnual" to calibration.parameters.expectedReturnAnnual,
                            "volatilityAnnual" to calibration.parameters.volatilityAnnual,
                            "horizonMonths" to calibration.parameters.horizonMonths,
                            "targetAmount" to calibration.parameters.targetAmount,
                            "unemploymentProbAnnual" to calibration.parameters.unemploymentProbAnnual,
                            "unemploymentDurationMonths" to calibration.parameters.unemploymentDurationMonths,
                            "inflationAnnual" to calibration.parameters.inflationAnnual,
                            "numSimulations" to calibration.parameters.numSimulations,
                            "seed" to calibration.parameters.seed,
                            // referencias as predicoes que originaram a calibracao
                            "incomePredictionId" to incomeOutcome.prediction.id,
                            "expensePredictionId" to expenseOutcome.prediction.id,
                        )
                    )
                )

                val resultDto = result.toResponseDto(
                    simulationId = 0L,
                    goalId = goalId,
                    createdAt = Instant.now().toString(),
                )
                val resultJson = Json.parseToJsonElement(json.encodeToString(resultDto))

                val persisted = simulationRepository.create(
                    Simulation(
                        id = 0L,
                        goalId = goalId,
                        parameters = parametersJson,
                        result = resultJson,
                        createdAt = Instant.now(),
                    )
                )

                // 7. Response final
                call.respond(
                    HttpStatusCode.Created,
                    RunCalibratedSimulationResponse(
                        simulation = result.toResponseDto(
                            simulationId = persisted.id,
                            goalId = goalId,
                            createdAt = persisted.createdAt.toString(),
                        ),
                        calibration = CalibrationSummaryResponse(
                            incomePredictionId = incomeOutcome.prediction.id,
                            expensePredictionId = expenseOutcome.prediction.id,
                            predictedMonthlyIncome = calibration.predictedMonthlyIncome,
                            predictedMonthlyExpense = calibration.predictedMonthlyExpense,
                            rawMonthlyContribution = calibration.rawContribution,
                            appliedMonthlyContribution = calibration.appliedContribution,
                            appliedVolatilityAnnual = calibration.appliedVolatilityAnnual,
                        ),
                    ),
                )
            }
        }
    }
}

// ============================================================================
// Helpers locais
// ============================================================================

private fun validate(req: RunCalibratedSimulationRequest): ErrorResponse? = when {
    req.initialCapital < 0.0 ->
        ErrorResponse("VALIDATION", "initialCapital deve ser >= 0")
    req.volatilityAnnual < 0.0 ->
        ErrorResponse("VALIDATION", "volatilityAnnual deve ser >= 0")
    req.targetAmount <= 0.0 ->
        ErrorResponse("VALIDATION", "targetAmount deve ser > 0")
    req.horizonMonths <= 0 ->
        ErrorResponse("VALIDATION", "horizonMonths deve ser > 0")
    req.unemploymentProbAnnual !in 0.0..1.0 ->
        ErrorResponse("VALIDATION", "unemploymentProbAnnual deve estar em [0, 1]")
    req.numSimulations <= 0 ->
        ErrorResponse("VALIDATION", "numSimulations deve ser > 0")
    req.incomeHorizonMonths !in 1..60 ->
        ErrorResponse("VALIDATION", "incomeHorizonMonths deve estar em [1, 60]")
    else -> null
}

/**
 * Reaproveita o mapper que ja vivia no SimulationRoutes da Sprint 2.
 * Replica aqui para nao introduzir dependencia entre arquivos do mesmo
 * pacote (e a Sprint 2 nao precisa importar esta funcao).
 */
private fun MonteCarloResult.toResponseDto(
    simulationId: Long,
    goalId: Long,
    createdAt: String = Instant.now().toString(),
): SimulationResultResponse = SimulationResultResponse(
    id = simulationId.toString(),
    goalId = goalId.toString(),
    numSimulations = numSimulations,
    seed = seed,
    targetAmount = targetAmount,
    successProbability = successProbability,
    mean = mean,
    median = median,
    standardDeviation = standardDeviation,
    percentiles = percentiles.mapKeys { (p, _) -> "P${p.toInt()}" },
    worstCase = worstCase,
    bestCase = bestCase,
    meanReal = meanReal,
    histogram = histogram.map { HistogramBucketDto(it.rangeStart, it.rangeEnd, it.count) },
    executionTimeMs = executionTimeMs,
    createdAt = createdAt,
)
