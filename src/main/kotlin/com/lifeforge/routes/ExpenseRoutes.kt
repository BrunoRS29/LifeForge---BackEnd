package com.lifeforge.routes

import com.lifeforge.domain.model.Expense
import com.lifeforge.domain.model.ExpenseCategory
import com.lifeforge.domain.model.ExpenseSchedule
import com.lifeforge.domain.model.RecurrenceType
import com.lifeforge.domain.model.ScheduleAffect
import com.lifeforge.domain.repository.ExpenseRepository
import com.lifeforge.domain.repository.ExpenseScheduleRepository
import com.lifeforge.dto.ErrorResponse
import com.lifeforge.dto.ExpenseDto
import com.lifeforge.dto.ExpenseRequest
import com.lifeforge.dto.ExpenseScheduleDto
import com.lifeforge.dto.ExpenseScheduleRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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
 * Endpoints de despesas:
 *  - /api/v1/expenses            -> lancamentos avulsos (CRUD transacional)
 *  - /api/v1/expenses/schedules  -> templates recorrentes (Sprint 6), incl.
 *                                   INSTALLMENTS para compras parceladas
 */
fun Route.expenseRoutes(
    repository: ExpenseRepository,
    scheduleRepository: ExpenseScheduleRepository,
) {
    authenticate("auth-jwt") {
        route("/api/v1/expenses") {

            get {
                // Paginacao opcional via ?limit=&offset= (ver ApplicationCall.paginate).
                call.respond(call.paginate(repository.findAllByUser(call.userId()).map { it.toDto() }))
            }

            post {
                val userId = call.userId()
                val req = call.receive<ExpenseRequest>()
                val category = runCatching { ExpenseCategory.valueOf(req.category) }.getOrNull()
                val amount = runCatching { BigDecimal(req.amount) }.getOrNull()
                val spentAt = runCatching { Instant.parse(req.spentAt) }.getOrNull()

                if (req.description.isBlank() || category == null || amount == null || amount <= BigDecimal.ZERO || spentAt == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION", "Dados da despesa invalidos"))
                    return@post
                }
                val expense = repository.create(
                    userId = userId,
                    description = req.description.trim(),
                    amount = amount,
                    category = category,
                    recurring = req.recurring,
                    spentAt = spentAt,
                )
                call.respond(HttpStatusCode.Created, expense.toDto())
            }

            // DELETE /api/v1/expenses -> remove TODAS as despesas do usuario
            delete {
                val deleted = repository.deleteAllByUser(call.userId())
                call.respond(mapOf("deleted" to deleted))
            }

            // -------- Schedules recorrentes --------
            route("/schedules") {

                get {
                    val userId = call.userId()
                    val schedules = scheduleRepository.findAllByUser(userId).map { schedule ->
                        schedule.toDto(repository.findByScheduleId(userId, schedule.id).size)
                    }
                    call.respond(schedules)
                }

                post {
                    val userId = call.userId()
                    val req = call.receive<ExpenseScheduleRequest>()
                    val parsed = parseExpenseSchedule(req) ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION", "Dados do schedule invalidos"))
                        return@post
                    }
                    val schedule = scheduleRepository.createAndMaterialize(
                        userId = userId,
                        description = parsed.description,
                        amountPerOccurrence = parsed.amount,
                        category = parsed.category,
                        recurrence = parsed.recurrence,
                        startDate = parsed.startDate,
                        endDate = parsed.endDate,
                        installmentsTotal = parsed.installmentsTotal,
                    )
                    val count = repository.findByScheduleId(userId, schedule.id).size
                    call.respond(HttpStatusCode.Created, schedule.toDto(count))
                }

                put("/{id}") {
                    val userId = call.userId()
                    val id = call.parameters["id"]?.toLongOrNull() ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "ID invalido"))
                        return@put
                    }
                    val req = call.receive<ExpenseScheduleRequest>()
                    val parsed = parseExpenseSchedule(req) ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION", "Dados do schedule invalidos"))
                        return@put
                    }
                    val updated = scheduleRepository.updateAndRematerialize(
                        id = id,
                        userId = userId,
                        description = parsed.description,
                        amountPerOccurrence = parsed.amount,
                        category = parsed.category,
                        recurrence = parsed.recurrence,
                        startDate = parsed.startDate,
                        endDate = parsed.endDate,
                        installmentsTotal = parsed.installmentsTotal,
                        affect = call.affectParam(),
                    )
                    if (updated == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Schedule nao encontrado"))
                    } else {
                        call.respond(updated.toDto(repository.findByScheduleId(userId, id).size))
                    }
                }

                delete("/{id}") {
                    val userId = call.userId()
                    val id = call.parameters["id"]?.toLongOrNull() ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "ID invalido"))
                        return@delete
                    }
                    if (scheduleRepository.delete(id, userId, call.affectParam())) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Schedule nao encontrado"))
                    }
                }
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

            put("/{id}") {
                val userId = call.userId()
                val id = call.parameters["id"]?.toLongOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "ID invalido"))
                    return@put
                }
                val req = call.receive<ExpenseRequest>()
                val category = runCatching { ExpenseCategory.valueOf(req.category) }.getOrNull()
                val amount = runCatching { BigDecimal(req.amount) }.getOrNull()
                val spentAt = runCatching { Instant.parse(req.spentAt) }.getOrNull()
                if (req.description.isBlank() || category == null || amount == null || amount <= BigDecimal.ZERO || spentAt == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION", "Dados da despesa invalidos"))
                    return@put
                }
                val updated = repository.update(
                    id = id,
                    userId = userId,
                    description = req.description.trim(),
                    amount = amount,
                    category = category,
                    recurring = req.recurring,
                    spentAt = spentAt,
                )
                if (updated == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Despesa nao encontrada"))
                else call.respond(updated.toDto())
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
    createdAt = createdAt.toString(),
)

private fun ExpenseSchedule.toDto(generatedCount: Int): ExpenseScheduleDto = ExpenseScheduleDto(
    id = id,
    userId = userId,
    description = description,
    amountPerOccurrence = amountPerOccurrence.toPlainString(),
    category = category.name,
    recurrence = recurrence.name,
    startDate = startDate.toString(),
    endDate = endDate?.toString(),
    installmentsTotal = installmentsTotal,
    createdAt = createdAt.toString(),
    generatedCount = generatedCount,
)

private class ParsedExpenseSchedule(
    val description: String,
    val amount: BigDecimal,
    val category: ExpenseCategory,
    val recurrence: RecurrenceType,
    val startDate: Instant,
    val endDate: Instant?,
    val installmentsTotal: Int?,
)

private fun parseExpenseSchedule(req: ExpenseScheduleRequest): ParsedExpenseSchedule? {
    val category = runCatching { ExpenseCategory.valueOf(req.category) }.getOrNull() ?: return null
    val recurrence = runCatching { RecurrenceType.valueOf(req.recurrence) }.getOrNull() ?: return null
    val amount = runCatching { BigDecimal(req.amountPerOccurrence) }.getOrNull() ?: return null
    val startDate = runCatching { Instant.parse(req.startDate) }.getOrNull() ?: return null
    val endDate = if (req.endDate == null) null
    else runCatching { Instant.parse(req.endDate) }.getOrNull() ?: return null

    if (req.description.isBlank() || amount <= BigDecimal.ZERO) return null
    when (recurrence) {
        RecurrenceType.INSTALLMENTS -> if ((req.installmentsTotal ?: 0) <= 0) return null
        RecurrenceType.MONTHLY -> if (endDate != null && endDate.isBefore(startDate)) return null
        RecurrenceType.ONE_TIME -> { /* sem regra extra */ }
    }
    return ParsedExpenseSchedule(
        description = req.description.trim(),
        amount = amount,
        category = category,
        recurrence = recurrence,
        startDate = startDate,
        endDate = endDate,
        installmentsTotal = if (recurrence == RecurrenceType.INSTALLMENTS) req.installmentsTotal else null,
    )
}

/** Le ?affect=ALL|FUTURE_ONLY (default conservador: FUTURE_ONLY). */
private fun ApplicationCall.affectParam(): ScheduleAffect =
    if (request.queryParameters["affect"]?.uppercase() == "ALL") ScheduleAffect.ALL
    else ScheduleAffect.FUTURE_ONLY
