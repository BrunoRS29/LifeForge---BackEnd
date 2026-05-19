package com.lifeforge.routes

import com.lifeforge.domain.model.Income
import com.lifeforge.domain.model.IncomeType
import com.lifeforge.domain.repository.IncomeRepository
import com.lifeforge.dto.ErrorResponse
import com.lifeforge.dto.IncomeDto
import com.lifeforge.dto.IncomeRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.math.BigDecimal
import java.time.Instant

/**
 * Endpoints de rendas. Sao registros transacionais (sem update),
 * o usuario remove e adiciona se quiser corrigir.
 */
fun Route.incomeRoutes(repository: IncomeRepository) {
    authenticate("auth-jwt") {
        route("/api/v1/incomes") {

            get {
                call.respond(repository.findAllByUser(call.userId()).map { it.toDto() })
            }

            post {
                val userId = call.userId()
                val req = call.receive<IncomeRequest>()
                val type = runCatching { IncomeType.valueOf(req.incomeType) }.getOrNull()
                val amount = runCatching { BigDecimal(req.amount) }.getOrNull()
                val receivedAt = runCatching { Instant.parse(req.receivedAt) }.getOrNull()

                if (req.source.isBlank() || type == null || amount == null || amount <= BigDecimal.ZERO || receivedAt == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("VALIDATION", "Dados da renda invalidos")
                    )
                    return@post
                }
                val income = repository.create(
                    userId = userId,
                    source = req.source.trim(),
                    amount = amount,
                    incomeType = type,
                    recurring = req.recurring,
                    receivedAt = receivedAt
                )
                call.respond(HttpStatusCode.Created, income.toDto())
            }

            get("/{id}") {
                val userId = call.userId()
                val id = call.parameters["id"]?.toLongOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "ID invalido"))
                    return@get
                }
                val income = repository.findById(id, userId)
                if (income == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Renda nao encontrada"))
                else call.respond(income.toDto())
            }

            delete("/{id}") {
                val userId = call.userId()
                val id = call.parameters["id"]?.toLongOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "ID invalido"))
                    return@delete
                }
                if (repository.delete(id, userId)) call.respond(HttpStatusCode.NoContent)
                else call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Renda nao encontrada"))
            }
        }
    }
}

private fun Income.toDto(): IncomeDto = IncomeDto(
    id = id,
    userId = userId,
    source = source,
    amount = amount.toPlainString(),
    incomeType = incomeType.name,
    recurring = recurring,
    receivedAt = receivedAt.toString(),
    createdAt = createdAt.toString()
)
