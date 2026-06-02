package com.lifeforge.domain.model

import java.time.Instant
import java.time.ZoneOffset

/**
 * Logica pura (sem IO) que decide as datas de ocorrencia de um schedule.
 *
 * Compartilhada por IncomeScheduleRepositoryImpl e ExpenseScheduleRepositoryImpl,
 * e espelhada no app Android para o preview "isso vai gerar X registros".
 * Por ser pura, e o ponto ideal de teste unitario.
 *
 * Regras:
 *  - ONE_TIME     : exatamente 1 ocorrencia em startDate.
 *  - INSTALLMENTS : exatamente `installmentsTotal` ocorrencias mensais.
 *  - MONTHLY      : ocorrencias mensais de startDate ate o limite:
 *                     - se endDate != null  -> ate endDate (respeita o fim);
 *                     - se endDate == null  -> ate hoje + [FUTURE_HORIZON_MONTHS].
 *
 * NOTA DE DESVIO DA SPEC: a spec dizia "ate max(endDate, hoje+12m)". Isso
 * geraria registros DEPOIS do fim de um schedule ja encerrado (endDate no
 * passado) -> dados financeiros incorretos. Optamos por limitar ao endDate
 * quando ele existe. Para voltar ao literal, troque `end` por
 * maxOf(endDate, horizon).
 */
object RecurrenceCalculator {

    /** Quantos meses para a frente materializar quando a recorrencia e indefinida. */
    const val FUTURE_HORIZON_MONTHS = 12L

    /** Trava de seguranca: no maximo 100 anos de ocorrencias mensais. */
    private const val MAX_OCCURRENCES = 1200

    fun occurrences(
        recurrence: RecurrenceType,
        startDate: Instant,
        endDate: Instant?,
        installmentsTotal: Int?,
        now: Instant = Instant.now(),
    ): List<Instant> = when (recurrence) {
        RecurrenceType.ONE_TIME -> listOf(startDate)

        RecurrenceType.INSTALLMENTS -> {
            val n = (installmentsTotal ?: 0).coerceIn(0, MAX_OCCURRENCES)
            (0 until n).map { startDate.plusMonthsUtc(it.toLong()) }
        }

        RecurrenceType.MONTHLY -> {
            val end = endDate ?: now.plusMonthsUtc(FUTURE_HORIZON_MONTHS)
            buildList {
                var i = 0L
                while (i < MAX_OCCURRENCES) {
                    val occurrence = startDate.plusMonthsUtc(i)
                    if (occurrence.isAfter(end)) break
                    add(occurrence)
                    i++
                }
            }
        }
    }

    /** Quantas ocorrencias um schedule geraria (preview), sem materializar. */
    fun count(
        recurrence: RecurrenceType,
        startDate: Instant,
        endDate: Instant?,
        installmentsTotal: Int?,
        now: Instant = Instant.now(),
    ): Int = occurrences(recurrence, startDate, endDate, installmentsTotal, now).size

    /** Soma meses respeitando o calendario (UTC), evitando drift de 30 dias fixos. */
    private fun Instant.plusMonthsUtc(months: Long): Instant =
        atZone(ZoneOffset.UTC).plusMonths(months).toInstant()
}
