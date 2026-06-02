package com.lifeforge.data.repository

import com.lifeforge.config.DatabaseFactory.dbQuery
import com.lifeforge.data.tables.IncomeSchedules
import com.lifeforge.data.tables.Users
import com.lifeforge.domain.model.Income
import com.lifeforge.domain.model.IncomeSchedule
import com.lifeforge.domain.model.IncomeType
import com.lifeforge.domain.model.RecurrenceCalculator
import com.lifeforge.domain.model.RecurrenceType
import com.lifeforge.domain.model.ScheduleAffect
import com.lifeforge.domain.repository.IncomeRepository
import com.lifeforge.domain.repository.IncomeScheduleRepository
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.time.Instant

/**
 * Schedule de receita = "molde" recorrente. Esta impl orquestra o ciclo de
 * vida: persiste o template E materializa os Incomes individuais (que sao o
 * que a IA consome) via [IncomeRepository].
 *
 * [materialize] depende apenas de [IncomeRepository] e de logica pura
 * ([RecurrenceCalculator]), entao e testavel unitariamente com um fake de
 * repositorio (sem banco).
 *
 * Nota sobre transacoes: createAndMaterialize/updateAndRematerialize fazem
 * operacoes em transacoes sequenciais (nao aninhadas) - o schedule e
 * persistido, depois cada Income e criado em sua propria transacao. Nao e
 * 100% atomico, mas e simples e suficiente para o escopo do TCC.
 */
class IncomeScheduleRepositoryImpl(
    private val incomeRepository: IncomeRepository,
) : IncomeScheduleRepository {

    override suspend fun createAndMaterialize(
        userId: Long,
        source: String,
        amountPerOccurrence: BigDecimal,
        incomeType: IncomeType,
        recurrence: RecurrenceType,
        startDate: Instant,
        endDate: Instant?,
        installmentsTotal: Int?,
    ): IncomeSchedule {
        val now = Instant.now()
        val schedule = dbQuery {
            val id = IncomeSchedules.insertAndGetId { row ->
                row[IncomeSchedules.userId] = EntityID(userId, Users)
                row[IncomeSchedules.sourceColumn] = source
                row[IncomeSchedules.amountPerOccurrence] = amountPerOccurrence
                row[IncomeSchedules.incomeType] = incomeType.name
                row[IncomeSchedules.recurrence] = recurrence.name
                row[IncomeSchedules.startDate] = startDate
                row[IncomeSchedules.endDate] = endDate
                row[IncomeSchedules.installmentsTotal] = installmentsTotal
                row[IncomeSchedules.createdAt] = now
            }
            IncomeSchedule(
                id = id.value, userId = userId, source = source,
                amountPerOccurrence = amountPerOccurrence, incomeType = incomeType,
                recurrence = recurrence, startDate = startDate, endDate = endDate,
                installmentsTotal = installmentsTotal, createdAt = now,
            )
        }
        materialize(schedule)
        return schedule
    }

    override suspend fun findAllByUser(userId: Long): List<IncomeSchedule> = dbQuery {
        IncomeSchedules.selectAll()
            .where { IncomeSchedules.userId eq userId }
            .orderBy(IncomeSchedules.createdAt, SortOrder.DESC)
            .map { it.toSchedule() }
    }

    override suspend fun findById(id: Long, userId: Long): IncomeSchedule? = dbQuery {
        IncomeSchedules.selectAll()
            .where { (IncomeSchedules.id eq id) and (IncomeSchedules.userId eq userId) }
            .singleOrNull()
            ?.toSchedule()
    }

    override suspend fun updateAndRematerialize(
        id: Long,
        userId: Long,
        source: String,
        amountPerOccurrence: BigDecimal,
        incomeType: IncomeType,
        recurrence: RecurrenceType,
        startDate: Instant,
        endDate: Instant?,
        installmentsTotal: Int?,
        affect: ScheduleAffect,
    ): IncomeSchedule? {
        val existing = findById(id, userId) ?: return null
        val cutoff = if (affect == ScheduleAffect.FUTURE_ONLY) Instant.now() else null

        // 1. remove os registros gerados conforme o escopo
        incomeRepository.deleteByScheduleId(userId, id, futureAfter = cutoff)

        // 2. atualiza o template
        val updated = dbQuery {
            IncomeSchedules.update({ (IncomeSchedules.id eq id) and (IncomeSchedules.userId eq userId) }) { row ->
                row[IncomeSchedules.sourceColumn] = source
                row[IncomeSchedules.amountPerOccurrence] = amountPerOccurrence
                row[IncomeSchedules.incomeType] = incomeType.name
                row[IncomeSchedules.recurrence] = recurrence.name
                row[IncomeSchedules.startDate] = startDate
                row[IncomeSchedules.endDate] = endDate
                row[IncomeSchedules.installmentsTotal] = installmentsTotal
            }
            IncomeSchedule(
                id = id, userId = userId, source = source,
                amountPerOccurrence = amountPerOccurrence, incomeType = incomeType,
                recurrence = recurrence, startDate = startDate, endDate = endDate,
                installmentsTotal = installmentsTotal, createdAt = existing.createdAt,
            )
        }

        // 3. regenera (FUTURE_ONLY -> so ocorrencias apos o cutoff)
        materialize(updated, onlyAfter = cutoff)
        return updated
    }

    override suspend fun delete(id: Long, userId: Long, affect: ScheduleAffect): Boolean {
        findById(id, userId) ?: return false
        // ALL: apaga todos os vinculados. FUTURE_ONLY: apaga so os futuros; ao
        // deletar o schedule, os passados viram avulsos (FK ON DELETE SET NULL).
        val cutoff = if (affect == ScheduleAffect.FUTURE_ONLY) Instant.now() else null
        incomeRepository.deleteByScheduleId(userId, id, futureAfter = cutoff)
        return dbQuery {
            IncomeSchedules.deleteWhere {
                (IncomeSchedules.id eq id) and (IncomeSchedules.userId eq userId)
            } > 0
        }
    }

    override suspend fun materialize(schedule: IncomeSchedule, onlyAfter: Instant?): List<Income> {
        val dates = RecurrenceCalculator.occurrences(
            recurrence = schedule.recurrence,
            startDate = schedule.startDate,
            endDate = schedule.endDate,
            installmentsTotal = schedule.installmentsTotal,
        ).filter { onlyAfter == null || it.isAfter(onlyAfter) }

        return dates.map { date ->
            incomeRepository.create(
                userId = schedule.userId,
                source = schedule.source,
                amount = schedule.amountPerOccurrence,
                incomeType = schedule.incomeType,
                recurring = schedule.recurrence != RecurrenceType.ONE_TIME,
                receivedAt = date,
                scheduleId = schedule.id,
            )
        }
    }

    private fun ResultRow.toSchedule(): IncomeSchedule = IncomeSchedule(
        id = this[IncomeSchedules.id].value,
        userId = this[IncomeSchedules.userId].value,
        source = this[IncomeSchedules.sourceColumn],
        amountPerOccurrence = this[IncomeSchedules.amountPerOccurrence],
        incomeType = IncomeType.valueOf(this[IncomeSchedules.incomeType]),
        recurrence = RecurrenceType.valueOf(this[IncomeSchedules.recurrence]),
        startDate = this[IncomeSchedules.startDate],
        endDate = this[IncomeSchedules.endDate],
        installmentsTotal = this[IncomeSchedules.installmentsTotal],
        createdAt = this[IncomeSchedules.createdAt],
    )
}
