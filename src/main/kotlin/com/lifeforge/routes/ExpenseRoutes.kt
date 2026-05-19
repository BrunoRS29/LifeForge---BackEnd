package com.lifeforge.routes

import com.lifeforge.domain.model.Expense
import com.lifeforge.domain.model.ExpenseCategory
import com.lifeforge.domain.repository.ExpenseRepository
import com.lifeforge.dto.ErrorResponse
import com.lifeforge.dto.ExpenseDto
import com.lifeforge.dto.ExpenseRequest
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

fun Route.expenseRoutes(repository: ExpenseRepository) {
    authenticate("auth-jwt") {
        route("/api/v1/expenses") {

            get {
                call.respond(repository.findAllByUser(call.userId()).map { it.toDto() })
            }

            post {
                val userId = call.userId()
                val req = call.receive<ExpenseRequest>()
                val category = runCatching { ExpenseCategory.valueOf(req.category) }.getOrNull()
                val amount = runCatching { BigDecimal(req.amount) }.getOrNull()
                val spentAt = runCatching { Instant.parse(req.spentAt) }.getOrNull()

                if (req.description.isBlank() || category == null || amount == null || amount <= BigDecimal.ZERO || spentAt == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("VALIDATION", "Dados da despesa invalidos")
                    )
                    return@post
                }
                val expense = repository.create(
                    userId = userId,
                    description = req.description.trim(),
                    amount = amount,
                    category = category,
                    recurring = req.recurring,
                    spentAt = spentAt
                )
                call.respond(HttpStatusCode.Created, expense.toDto())
            }

            get("/{id}") {
                val userId = call.userId()
                val id = call.parameters["id"]?.toLongOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "ID invalido"))
                    return@get
                }
                val expense = repository.findById(id, userId)
                if (expense == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Despesa nao encontrada"))
                else call.respond(expense.toDto())
            }

            delete("/{id}") {
                val userId = call.userId()
                val id = call.parameters["id"]?.toLongOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "ID invalido"))
                    return@delete
                }
                if (repository.delete(id, userId)) call.respond(HttpStatusCode.NoContent)
                else call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Despesa nao encontrada"))
            }
        }
    }
}

private fun Expense.toDto(): ExpenseDto = ExpenseDto(
    id = id,
    userId = userId,
    description = description,
    amount = amount.toPlainString(),
    category = category.name,
    recurring = recurring,
    spentAt = spentAt.toString(),
    createdAt = createdAt.toString()
)
