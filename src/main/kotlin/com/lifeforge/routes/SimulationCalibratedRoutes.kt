package com.lifeforge.routes

import com.lifeforge.domain.model.Simulation
import com.lifeforge.domain.repository.GoalRepository
import com.lifeforge.domain.repository.SimulationRepository
import com.lifeforge.domain.repository.UserProfileRepository
import com.lifeforge.domain.repository.UserRepository
import com.lifeforge.dto.CalibrationSummaryResponse
import com.lifeforge.dto.ErrorResponse
import com.lifeforge.dto.HistogramBucketDto
import com.lifeforge.dto.RunCalibratedSimulationRequest
import com.lifeforge.dto.RunCalibratedSimulationResponse
import com.lifeforge.dto.SimulationResultResponse
import com.lifeforge.dto.TrajectoryBandDto
import com.lifeforge.engine.montecarlo.MonteCarloEngine
import com.lifeforge.engine.montecarlo.MonteCarloParameters
import com.lifeforge.engine.montecarlo.MonteCarloResult
import com.lifeforge.engine.statistics.ReferenceData
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

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
    userRepository: UserRepository,
    userProfileRepository: UserProfileRepository,
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

                // 3. Parametros base (sem monthlyContribution). As premissas de
                // longo prazo omitidas pelo app (null) sao preenchidas pela base
                // de referencia calibrada ao perfil do usuario: o perfil de risco
                // (User) define retorno/volatilidade; o vinculo (perfil estendido,
                // JSONB) define a probabilidade de desemprego.
                val riskProfile = userRepository.findById(userId)?.riskProfile
                val employmentType = userProfileRepository.get(userId)?.employmentType()
                val preset = ReferenceData.presetFor(riskProfile, employmentType)
                // Choque de despesa inesperada (Proposta 6.2): frequencia vem da
                // base; a magnitude media e uma fracao da renda mensal prevista.
                val baseParams = request.toBaseParameters(
                    preset = preset,
                    seed = request.seed ?: System.currentTimeMillis(),
                    unexpectedExpenseAnnualFrequency = ReferenceData.unexpectedExpenseAnnualFrequency,
                    unexpectedExpenseMeanAmount = ReferenceData.unexpectedExpenseMeanFractionOfIncome *
                        incomeOutcome.response.expectedMonthlyIncome,
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
                // calibrados ja com o monthlyContribution derivado.
                //
                // buildJsonObject em vez de encodeToString(mapOf(...)): o mapa
                // mistura Double/Int/Long, virando Map<String, Any>, e o kotlinx
                // NAO tem serializer para Any -> estourava SerializationException
                // (500) em todo run-calibrated que chegava ate aqui.
                val parametersJson = buildJsonObject {
                    put("initialCapital", calibration.parameters.initialCapital)
                    put("monthlyContribution", calibration.parameters.monthlyContribution)
                    put("expectedReturnAnnual", calibration.parameters.expectedReturnAnnual)
                    put("volatilityAnnual", calibration.parameters.volatilityAnnual)
                    put("horizonMonths", calibration.parameters.horizonMonths)
                    put("targetAmount", calibration.parameters.targetAmount)
                    put("unemploymentProbAnnual", calibration.parameters.unemploymentProbAnnual)
                    put("unemploymentDurationMonths", calibration.parameters.unemploymentDurationMonths)
                    put("inflationAnnual", calibration.parameters.inflationAnnual)
                    put("unexpectedExpenseAnnualFrequency", calibration.parameters.unexpectedExpenseAnnualFrequency)
                    put("unexpectedExpenseMeanAmount", calibration.parameters.unexpectedExpenseMeanAmount)
                    put("numSimulations", calibration.parameters.numSimulations)
                    put("seed", calibration.parameters.seed)
                    // referencias as predicoes que originaram a calibracao
                    put("incomePredictionId", incomeOutcome.prediction.id)
                    put("expensePredictionId", expenseOutcome.prediction.id)
                }

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

// Campos de premissa sao opcionais (null = usar preset). So validamos quando
// o app de fato enviou um valor; os valores do preset sao sempre validos.
private fun validate(req: RunCalibratedSimulationRequest): ErrorResponse? = when {
    req.initialCapital < 0.0 ->
        ErrorResponse("VALIDATION", "initialCapital deve ser >= 0")
    req.volatilityAnnual != null && req.volatilityAnnual < 0.0 ->
        ErrorResponse("VALIDATION", "volatilityAnnual deve ser >= 0")
    req.targetAmount <= 0.0 ->
        ErrorResponse("VALIDATION", "targetAmount deve ser > 0")
    req.horizonMonths <= 0 ->
        ErrorResponse("VALIDATION", "horizonMonths deve ser > 0")
    req.unemploymentProbAnnual != null && req.unemploymentProbAnnual !in 0.0..1.0 ->
        ErrorResponse("VALIDATION", "unemploymentProbAnnual deve estar em [0, 1]")
    req.unemploymentDurationMonths != null && req.unemploymentDurationMonths < 0 ->
        ErrorResponse("VALIDATION", "unemploymentDurationMonths deve ser >= 0")
    req.numSimulations <= 0 ->
        ErrorResponse("VALIDATION", "numSimulations deve ser > 0")
    req.incomeHorizonMonths !in 1..60 ->
        ErrorResponse("VALIDATION", "incomeHorizonMonths deve estar em [1, 60]")
    else -> null
}

/**
 * Extrai o vinculo (employmentType) do blob JSON do perfil estendido. O app
 * grava o NOME do enum (ex.: "CLT", "CIVIL_SERVANT"), que casa com as chaves
 * de [ReferenceData.byEmploymentType]. Retorna null se ausente/nao-string.
 */
private fun JsonElement.employmentType(): String? =
    ((this as? JsonObject)?.get("employmentType") as? JsonPrimitive)?.contentOrNull

/**
 * Constroi os parametros base da engine resolvendo as premissas de longo
 * prazo: usa o valor enviado pelo app quando presente, senao o [preset]
 * calibrado ao perfil do usuario. `internal` para ser coberto por teste.
 */
internal fun RunCalibratedSimulationRequest.toBaseParameters(
    preset: ReferenceData.CalibrationPreset,
    seed: Long,
    unexpectedExpenseAnnualFrequency: Double = 0.0,
    unexpectedExpenseMeanAmount: Double = 0.0,
): MonteCarloParameters = MonteCarloParameters(
    initialCapital = initialCapital,
    monthlyContribution = 0.0,  // sera sobrescrito pela calibracao
    expectedReturnAnnual = expectedReturnAnnual ?: preset.expectedReturnAnnual,
    volatilityAnnual = volatilityAnnual ?: preset.volatilityAnnual,
    horizonMonths = horizonMonths,
    targetAmount = targetAmount,
    unemploymentProbAnnual = unemploymentProbAnnual ?: preset.unemploymentProbAnnual,
    unemploymentDurationMonths = unemploymentDurationMonths ?: preset.unemploymentDurationMonths,
    inflationAnnual = inflationAnnual ?: preset.inflationAnnual,
    numSimulations = numSimulations,
    seed = seed,
    unexpectedExpenseAnnualFrequency = unexpectedExpenseAnnualFrequency,
    unexpectedExpenseMeanAmount = unexpectedExpenseMeanAmount,
)

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
    trajectory = trajectory.map {
        TrajectoryBandDto(it.monthIndex, it.p10, it.p25, it.p50, it.p75, it.p90)
    },
    executionTimeMs = executionTimeMs,
    createdAt = createdAt,
)
