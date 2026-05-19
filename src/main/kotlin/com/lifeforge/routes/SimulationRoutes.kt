package com.lifeforge.routes

import com.lifeforge.domain.model.Simulation
import com.lifeforge.domain.repository.GoalRepository
import com.lifeforge.domain.repository.SimulationRepository
import com.lifeforge.dto.HistogramBucketDto
import com.lifeforge.dto.RunSimulationRequest
import com.lifeforge.dto.SimulationResultResponse
import com.lifeforge.dto.SimulationSummaryResponse
import com.lifeforge.engine.montecarlo.MonteCarloEngine
import com.lifeforge.engine.montecarlo.MonteCarloParameters
import com.lifeforge.engine.montecarlo.MonteCarloResult
import com.lifeforge.routes.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Rotas HTTP para o motor de simulacao de Monte Carlo.
 *
 * Endpoints (todos sob /api/v1/simulation, exigem JWT):
 *   POST   /run                  -> executa nova simulacao e persiste
 *   GET    /{id}                 -> recupera resultado completo de uma simulacao
 *   GET    /by-goal/{goalId}     -> lista simulacoes de uma meta (resumo)
 *   DELETE /{id}                 -> remove uma simulacao
 *
 * Autorizacao: o usuario so pode acessar simulacoes de metas que possui.
 * A verificacao e feita comparando user_id da meta com o sub do JWT.
 */
fun Route.simulationRoutes(
    simulationRepository: SimulationRepository,
    goalRepository: GoalRepository,
    engine: MonteCarloEngine,
) {
    val json = Json { ignoreUnknownKeys = true }

    authenticate("auth-jwt") {
        route("/api/v1/simulation") {

            // POST /run - executa nova simulacao
            post("/run") {
                val userId = call.userId()
                val request = call.receive<RunSimulationRequest>()
                val goalId = request.goalId.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "goalId invalido"))

                val goal = goalRepository.findById(goalId, userId)
                if (goal == null) {
                    return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Meta nao encontrada"))
                }

                val parameters = MonteCarloParameters(
                    initialCapital = request.initialCapital,
                    monthlyContribution = request.monthlyContribution,
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

                val result = withContext(Dispatchers.Default) {
                    engine.run(parameters)
                }

                val parametersJson = Json.parseToJsonElement(json.encodeToString(request.copy(seed = parameters.seed)))
                val resultJson = Json.parseToJsonElement(json.encodeToString(result.toResponseDto(
                    simulationId = 0,
                    goalId = goalId,
                )))

                val simulation = Simulation(
                    id = 0,
                    goalId = goalId,
                    parameters = parametersJson,
                    result = resultJson,
                    createdAt = Instant.now(),
                )
                val created = simulationRepository.create(simulation)

                call.respond(
                    HttpStatusCode.Created,
                    result.toResponseDto(
                        simulationId = created.id,
                        goalId = goalId,
                        createdAt = created.createdAt.toString(),
                    ),
                )
            }

            // GET /{id} - resultado completo de uma simulacao
            get("/{id}") {
                val userId = call.userId()
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id invalido"))

                val simulation = simulationRepository.findById(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound)

                val goal = goalRepository.findById(simulation.goalId, userId)
                if (goal == null) {
                    return@get call.respond(HttpStatusCode.NotFound)
                }

                call.respondText(
                    text = json.encodeToString(simulation.result),
                    contentType = io.ktor.http.ContentType.Application.Json,
                )
            }

            // GET /by-goal/{goalId} - lista simulacoes de uma meta
            get("/by-goal/{goalId}") {
                val userId = call.userId()
                val goalId = call.parameters["goalId"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "goalId invalido"))

                val goal = goalRepository.findById(goalId, userId)
                if (goal == null) {
                    return@get call.respond(HttpStatusCode.NotFound)
                }

                val simulations = simulationRepository.findByGoalId(goalId)
                val summaries = simulations.map { sim ->
                    val parsed = json.decodeFromString<SimulationResultResponse>(json.encodeToString(sim.result))
                    SimulationSummaryResponse(
                        id = sim.id.toString(),
                        goalId = sim.goalId.toString(),
                        successProbability = parsed.successProbability,
                        mean = parsed.mean,
                        median = parsed.median,
                        targetAmount = parsed.targetAmount,
                        createdAt = sim.createdAt.toString(),
                    )
                }
                call.respond(summaries)
            }

            // DELETE /{id}
            delete("/{id}") {
                val userId = call.userId()
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id invalido"))

                val simulation = simulationRepository.findById(id)
                    ?: return@delete call.respond(HttpStatusCode.NotFound)

                val goal = goalRepository.findById(simulation.goalId, userId)
                if (goal == null) {
                    return@delete call.respond(HttpStatusCode.NotFound)
                }

                simulationRepository.deleteById(id)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

// ----- Helpers privados -----

/**
 * Converte um [MonteCarloResult] em [SimulationResultResponse].
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
