package com.lifeforge.routes

import com.lifeforge.domain.model.Income
import com.lifeforge.domain.model.IncomeSchedule
import com.lifeforge.domain.model.IncomeType
import com.lifeforge.domain.model.RecurrenceType
import com.lifeforge.domain.model.ScheduleAffect
import com.lifeforge.domain.repository.IncomeRepository
import com.lifeforge.domain.repository.IncomeScheduleRepository
import com.lifeforge.dto.ErrorResponse
import com.lifeforge.dto.IncomeDto
import com.lifeforge.dto.IncomeRequest
import com.lifeforge.dto.IncomeScheduleDto
import com.lifeforge.dto.IncomeScheduleRequest
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
 * Endpoints de rendas:
 *  - /api/v1/incomes            -> lancamentos avulsos (CRUD transacional)
 *  - /api/v1/incomes/schedules  -> templates recorrentes (Sprint 6) que
 *                                  materializam Incomes individuais
 *
 * Lancamentos pre-existentes sem schedule continuam validos como avulsos.
 */
fun Route.incomeRoutes(
    repository: IncomeRepository,
    scheduleRepository: IncomeScheduleRepository,
) {
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
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION", "Dados da renda invalidos"))
                    return@post
                }
                val income = repository.create(
                    userId = userId,
                    source = req.source.trim(),
                    amount = amount,
                    incomeType = type,
                    recurring = req.recurring,
                    receivedAt = receivedAt,
                )
                call.respond(HttpStatusCode.Created, income.toDto())
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
                    val req = call.receive<IncomeScheduleRequest>()
                    val parsed = parseIncomeSchedule(req) ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION", "Dados do schedule invalidos"))
                        return@post
                    }
                    val schedule = scheduleRepository.createAndMaterialize(
                        userId = userId,
                        source = parsed.source,
                        amountPerOccurrence = parsed.amount,
                        incomeType = parsed.type,
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
                    val req = call.receive<IncomeScheduleRequest>()
                    val parsed = parseIncomeSchedule(req) ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION", "Dados do schedule invalidos"))
                        return@put
                    }
                    val updated = scheduleRepository.updateAndRematerialize(
                        id = id,
                        userId = userId,
                        source = parsed.source,
                        amountPerOccurrence = parsed.amount,
                        incomeType = parsed.type,
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
    createdAt = createdAt.toString(),
)

private fun IncomeSchedule.toDto(generatedCount: Int): IncomeScheduleDto = IncomeScheduleDto(
    id = id,
    userId = userId,
    source = source,
    amountPerOccurrence = amountPerOccurrence.toPlainString(),
    incomeType = incomeType.name,
    recurrence = recurrence.name,
    startDate = startDate.toString(),
    endDate = endDate?.toString(),
    installmentsTotal = installmentsTotal,
    createdAt = createdAt.toString(),
    generatedCount = generatedCount,
)

private class ParsedIncomeSchedule(
    val source: String,
    val amount: BigDecimal,
    val type: IncomeType,
    val recurrence: RecurrenceType,
    val startDate: Instant,
    val endDate: Instant?,
    val installmentsTotal: Int?,
)

/** Valida e converte o request; null indica payload invalido (-> 400). */
private fun parseIncomeSchedule(req: IncomeScheduleRequest): ParsedIncomeSchedule? {
    val type = runCatching { IncomeType.valueOf(req.incomeType) }.getOrNull() ?: return null
    val recurrence = runCatching { RecurrenceType.valueOf(req.recurrence) }.getOrNull() ?: return null
    val amount = runCatching { BigDecimal(req.amountPerOccurrence) }.getOrNull() ?: return null
    val startDate = runCatching { Instant.parse(req.startDate) }.getOrNull() ?: return null
    val endDate = if (req.endDate == null) null
    else runCatching { Instant.parse(req.endDate) }.getOrNull() ?: return null

    if (req.source.isBlank() || amount <= BigDecimal.ZERO) return null
    when (recurrence) {
        RecurrenceType.INSTALLMENTS -> if ((req.installmentsTotal ?: 0) <= 0) return null
        RecurrenceType.MONTHLY -> if (endDate != null && endDate.isBefore(startDate)) return null
        RecurrenceType.ONE_TIME -> { /* sem regra extra */ }
    }
    return ParsedIncomeSchedule(
        source = req.source.trim(),
        amount = amount,
        type = type,
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
