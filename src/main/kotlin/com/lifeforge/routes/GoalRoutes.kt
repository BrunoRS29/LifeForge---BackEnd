package com.lifeforge.routes

import com.lifeforge.domain.model.Goal
import com.lifeforge.domain.model.GoalCategory
import com.lifeforge.domain.repository.GoalRepository
import com.lifeforge.dto.ErrorResponse
import com.lifeforge.dto.GoalDto
import com.lifeforge.dto.GoalRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.math.BigDecimal
import java.time.Instant

/**
 * Endpoints autenticados de metas:
 *  GET    /api/v1/goals
 *  POST   /api/v1/goals
 *  GET    /api/v1/goals/{id}
 *  PUT    /api/v1/goals/{id}
 *  DELETE /api/v1/goals/{id}
 *
 * O isolamento por usuario e feito sempre via filtro userId no repositorio,
 * de forma que um usuario nunca consegue acessar metas de outro.
 */
fun Route.goalRoutes(repository: GoalRepository) {
    authenticate("auth-jwt") {
        route("/api/v1/goals") {

            get {
                val userId = call.userId()
                val goals = repository.findAllByUser(userId).map { it.toDto() }
                call.respond(goals)
            }

            post {
                val userId = call.userId()
                val req = call.receive<GoalRequest>()
                val parsed = parseGoalRequest(req) ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("VALIDATION", "Dados da meta invalidos")
                    )
                    return@post
                }
                val goal = repository.create(
                    userId = userId,
                    name = parsed.name,
                    category = parsed.category,
                    targetAmount = parsed.targetAmount,
                    targetDate = parsed.targetDate,
                    priority = parsed.priority
                )
                call.respond(HttpStatusCode.Created, goal.toDto())
            }

            get("/{id}") {
                val userId = call.userId()
                val id = call.parameters["id"]?.toLongOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "ID invalido"))
                    return@get
                }
                val goal = repository.findById(id, userId)
                if (goal == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Meta nao encontrada"))
                } else {
                    call.respond(goal.toDto())
                }
            }

            put("/{id}") {
                val userId = call.userId()
                val id = call.parameters["id"]?.toLongOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "ID invalido"))
                    return@put
                }
                val req = call.receive<GoalRequest>()
                val parsed = parseGoalRequest(req) ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("VALIDATION", "Dados da meta invalidos")
                    )
                    return@put
                }
                val goal = repository.update(
                    id = id,
                    userId = userId,
                    name = parsed.name,
                    category = parsed.category,
                    targetAmount = parsed.targetAmount,
                    targetDate = parsed.targetDate,
                    priority = parsed.priority
                )
                if (goal == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Meta nao encontrada"))
                } else {
                    call.respond(goal.toDto())
                }
            }

            delete("/{id}") {
                val userId = call.userId()
                val id = call.parameters["id"]?.toLongOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "ID invalido"))
                    return@delete
                }
                val deleted = repository.delete(id, userId)
                if (deleted) call.respond(HttpStatusCode.NoContent)
                else call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Meta nao encontrada"))
            }
        }
    }
}

private data class ParsedGoal(
    val name: String,
    val category: GoalCategory,
    val targetAmount: BigDecimal,
    val targetDate: Instant,
    val priority: Int
)

private fun parseGoalRequest(req: GoalRequest): ParsedGoal? {
    if (req.name.isBlank()) return null
    val category = runCatching { GoalCategory.valueOf(req.category) }.getOrNull() ?: return null
    val amount = runCatching { BigDecimal(req.targetAmount) }.getOrNull() ?: return null
    if (amount <= BigDecimal.ZERO) return null
    val date = runCatching { Instant.parse(req.targetDate) }.getOrNull() ?: return null
    return ParsedGoal(req.name.trim(), category, amount, date, req.priority.coerceAtLeast(1))
}

private fun Goal.toDto(): GoalDto = GoalDto(
    id = id,
    userId = userId,
    name = name,
    category = category.name,
    targetAmount = targetAmount.toPlainString(),
    targetDate = targetDate.toString(),
    priority = priority,
    createdAt = createdAt.toString()
)
