package com.lifeforge.routes

import com.lifeforge.domain.model.RiskProfile
import com.lifeforge.domain.repository.GoalRepository
import com.lifeforge.dto.ErrorResponse
import com.lifeforge.dto.HistogramBucketDto
import com.lifeforge.dto.IterationStepDto
import com.lifeforge.dto.OptimizationResponse
import com.lifeforge.dto.OptimizeContributionRequest
import com.lifeforge.dto.OptimizeHorizonRequest
import com.lifeforge.dto.RebalanceRequest
import com.lifeforge.dto.RebalanceResponse
import com.lifeforge.dto.VerificationResultDto
import com.lifeforge.engine.montecarlo.MonteCarloResult
import com.lifeforge.engine.optimization.BaseConfig
import com.lifeforge.engine.optimization.IterationStep
import com.lifeforge.engine.optimization.OptimizationEngine
import com.lifeforge.engine.optimization.OptimizationRequest
import com.lifeforge.engine.optimization.OptimizationResult
import com.lifeforge.engine.optimization.RebalancingAdvisor
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Endpoints autenticados de otimizacao financeira (Sprint 3):
 *
 *   POST /api/v1/optimize/contribution  -> aporte mensal ideal
 *   POST /api/v1/optimize/horizon       -> prazo ajustado
 *   POST /api/v1/optimize/rebalance     -> alocacao recomendada
 *
 * Caracteristicas comuns:
 *   - Todas exigem JWT (auth-jwt)
 *   - Quando o request inclui goalId, valida posse contra o userId do JWT.
 *     Isso e uma checagem de autorizacao soft: a meta nao precisa coincidir
 *     com o targetAmount do request — o cliente decide se quer associar.
 *   - O calculo pesado roda em Dispatchers.Default para nao bloquear o
 *     event loop do Ktor (10k simulacoes podem levar 1-2s)
 *   - Erros de validacao retornam 400 com [ErrorResponse]
 *   - Otimizacoes infeasible retornam 200 com feasible=false e
 *     verification=null (o cliente decide como apresentar)
 */
fun Route.optimizationRoutes(
    goalRepository: GoalRepository,
    optimizationEngine: OptimizationEngine,
    rebalancingAdvisor: RebalancingAdvisor,
) {
    authenticate("auth-jwt") {
        route("/api/v1/optimize") {

            // -------- POST /contribution --------
            post("/contribution") {
                val userId = call.userId()
                val req = call.receive<OptimizeContributionRequest>()

                // Validacao de input — falha cedo com mensagem clara
                val validationError = validateContribution(req)
                if (validationError != null) {
                    return@post call.respond(HttpStatusCode.BadRequest, validationError)
                }

                // Validacao de posse de meta, se goalId foi fornecido
                if (req.goalId != null) {
                    val goalId = req.goalId.toLongOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("VALIDATION", "goalId invalido")
                        )
                    if (goalRepository.findById(goalId, userId) == null) {
                        return@post call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("NOT_FOUND", "Meta nao encontrada")
                        )
                    }
                }

                val effectiveSeed = req.seed ?: System.currentTimeMillis()
                val baseConfig = BaseConfig(
                    initialCapital = req.initialCapital,
                    expectedReturnAnnual = req.expectedReturnAnnual,
                    volatilityAnnual = req.volatilityAnnual,
                    targetAmount = req.targetAmount,
                    targetSuccessProbability = req.targetSuccessProbability,
                    unemploymentProbAnnual = req.unemploymentProbAnnual,
                    unemploymentDurationMonths = req.unemploymentDurationMonths,
                    inflationAnnual = req.inflationAnnual,
                    simulationsPerStep = req.simulationsPerStep,
                    verificationSimulations = req.verificationSimulations,
                    seed = effectiveSeed,
                )

                val result = withContext(Dispatchers.Default) {
                    optimizationEngine.findOptimalContribution(
                        OptimizationRequest.Contribution(
                            base = baseConfig,
                            horizonMonths = req.horizonMonths,
                            maxContribution = req.maxContribution,
                        )
                    )
                }

                call.respond(result.toResponseDto(effectiveSeed))
            }

            // -------- POST /horizon --------
            post("/horizon") {
                val userId = call.userId()
                val req = call.receive<OptimizeHorizonRequest>()

                val validationError = validateHorizon(req)
                if (validationError != null) {
                    return@post call.respond(HttpStatusCode.BadRequest, validationError)
                }

                if (req.goalId != null) {
                    val goalId = req.goalId.toLongOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("VALIDATION", "goalId invalido")
                        )
                    if (goalRepository.findById(goalId, userId) == null) {
                        return@post call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("NOT_FOUND", "Meta nao encontrada")
                        )
                    }
                }

                val effectiveSeed = req.seed ?: System.currentTimeMillis()
                val baseConfig = BaseConfig(
                    initialCapital = req.initialCapital,
                    expectedReturnAnnual = req.expectedReturnAnnual,
                    volatilityAnnual = req.volatilityAnnual,
                    targetAmount = req.targetAmount,
                    targetSuccessProbability = req.targetSuccessProbability,
                    unemploymentProbAnnual = req.unemploymentProbAnnual,
                    unemploymentDurationMonths = req.unemploymentDurationMonths,
                    inflationAnnual = req.inflationAnnual,
                    simulationsPerStep = req.simulationsPerStep,
                    verificationSimulations = req.verificationSimulations,
                    seed = effectiveSeed,
                )

                val result = withContext(Dispatchers.Default) {
                    optimizationEngine.findOptimalHorizon(
                        OptimizationRequest.Horizon(
                            base = baseConfig,
                            monthlyContribution = req.monthlyContribution,
                            maxHorizonMonths = req.maxHorizonMonths,
                        )
                    )
                }

                call.respond(result.toResponseDto(effectiveSeed))
            }

            // -------- POST /rebalance --------
            post("/rebalance") {
                // userId nao e usado — rebalance nao consulta banco — mas
                // permanece autenticado para uniformidade da superficie HTTP.
                call.userId()
                val req = call.receive<RebalanceRequest>()

                val profile = parseRiskProfile(req.riskProfile)
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            "VALIDATION",
                            "riskProfile deve ser CONSERVATIVE, MODERATE ou AGGRESSIVE"
                        )
                    )
                if (req.currentCapital < 0.0) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("VALIDATION", "currentCapital deve ser >= 0")
                    )
                }
                if (req.targetAmount <= 0.0) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("VALIDATION", "targetAmount deve ser > 0")
                    )
                }
                if (req.monthsToGoal <= 0) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("VALIDATION", "monthsToGoal deve ser > 0")
                    )
                }

                val rec = rebalancingAdvisor.recommend(
                    riskProfile = profile,
                    currentCapital = req.currentCapital,
                    targetAmount = req.targetAmount,
                    monthsToGoal = req.monthsToGoal,
                )

                call.respond(
                    RebalanceResponse(
                        weights = rec.weights.mapKeys { (asset, _) -> asset.name },
                        expectedReturnAnnual = rec.expectedReturnAnnual,
                        volatilityAnnual = rec.volatilityAnnual,
                        riskScore = rec.riskScore,
                        rationale = rec.rationale,
                    )
                )
            }
        }
    }
}

// ========== Helpers privados ==========

private fun parseRiskProfile(value: String): RiskProfile? = try {
    RiskProfile.valueOf(value.uppercase())
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Validacao do request de aporte. Retorna null se valido, ou ErrorResponse
 * descrevendo a primeira falha encontrada.
 *
 * Nao replica as validacoes do BaseConfig.init: a ideia aqui e devolver 400
 * (ao inves de 500) com mensagem amigavel, antes de instanciar o BaseConfig.
 */
private fun validateContribution(req: OptimizeContributionRequest): ErrorResponse? = when {
    req.initialCapital < 0.0 ->
        ErrorResponse("VALIDATION", "initialCapital deve ser >= 0")
    req.volatilityAnnual < 0.0 ->
        ErrorResponse("VALIDATION", "volatilityAnnual deve ser >= 0")
    req.targetAmount <= 0.0 ->
        ErrorResponse("VALIDATION", "targetAmount deve ser > 0")
    req.horizonMonths <= 0 ->
        ErrorResponse("VALIDATION", "horizonMonths deve ser > 0")
    req.targetSuccessProbability !in 0.0..1.0 ->
        ErrorResponse("VALIDATION", "targetSuccessProbability deve estar em [0, 1]")
    req.unemploymentProbAnnual !in 0.0..1.0 ->
        ErrorResponse("VALIDATION", "unemploymentProbAnnual deve estar em [0, 1]")
    req.simulationsPerStep <= 0 ->
        ErrorResponse("VALIDATION", "simulationsPerStep deve ser > 0")
    req.verificationSimulations <= 0 ->
        ErrorResponse("VALIDATION", "verificationSimulations deve ser > 0")
    req.maxContribution != null && req.maxContribution <= 0.0 ->
        ErrorResponse("VALIDATION", "maxContribution deve ser > 0 quando informado")
    else -> null
}

private fun validateHorizon(req: OptimizeHorizonRequest): ErrorResponse? = when {
    req.initialCapital < 0.0 ->
        ErrorResponse("VALIDATION", "initialCapital deve ser >= 0")
    req.volatilityAnnual < 0.0 ->
        ErrorResponse("VALIDATION", "volatilityAnnual deve ser >= 0")
    req.targetAmount <= 0.0 ->
        ErrorResponse("VALIDATION", "targetAmount deve ser > 0")
    req.monthlyContribution < 0.0 ->
        ErrorResponse("VALIDATION", "monthlyContribution deve ser >= 0")
    req.targetSuccessProbability !in 0.0..1.0 ->
        ErrorResponse("VALIDATION", "targetSuccessProbability deve estar em [0, 1]")
    req.unemploymentProbAnnual !in 0.0..1.0 ->
        ErrorResponse("VALIDATION", "unemploymentProbAnnual deve estar em [0, 1]")
    req.maxHorizonMonths <= 0 ->
        ErrorResponse("VALIDATION", "maxHorizonMonths deve ser > 0")
    req.simulationsPerStep <= 0 ->
        ErrorResponse("VALIDATION", "simulationsPerStep deve ser > 0")
    req.verificationSimulations <= 0 ->
        ErrorResponse("VALIDATION", "verificationSimulations deve ser > 0")
    else -> null
}

// ========== Mappers de dominio para DTO ==========

private fun OptimizationResult.toResponseDto(seed: Long): OptimizationResponse =
    OptimizationResponse(
        type = type.name,
        feasible = feasible,
        optimalValue = optimalValue,
        achievedProbability = achievedProbability,
        targetProbability = targetProbability,
        terminationReason = terminationReason.name,
        iterations = iterations.map { it.toDto() },
        verification = verification?.toVerificationDto(),
        executionTimeMs = executionTimeMs,
        seed = seed,
    )

private fun IterationStep.toDto(): IterationStepDto = IterationStepDto(
    index = index,
    candidate = candidate,
    measuredProbability = measuredProbability,
    lowerBound = lowerBound,
    upperBound = upperBound,
)

private fun MonteCarloResult.toVerificationDto(): VerificationResultDto =
    VerificationResultDto(
        numSimulations = numSimulations,
        successProbability = successProbability,
        mean = mean,
        median = median,
        standardDeviation = standardDeviation,
        percentiles = percentiles.mapKeys { (p, _) -> "P${p.toInt()}" },
        worstCase = worstCase,
        bestCase = bestCase,
        meanReal = meanReal,
        histogram = histogram.map { HistogramBucketDto(it.rangeStart, it.rangeEnd, it.count) },
    )
