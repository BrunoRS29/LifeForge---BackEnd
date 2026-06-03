package com.lifeforge.domain.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneOffset

/**
 * Testes da logica pura de datas de ocorrencia (sem IO).
 */
class RecurrenceCalculatorTest : StringSpec({

    fun plusMonths(i: Long, from: Instant): Instant =
        from.atZone(ZoneOffset.UTC).plusMonths(i).toInstant()

    "ONE_TIME gera exatamente 1 ocorrencia na startDate" {
        val start = Instant.parse("2024-05-10T00:00:00Z")
        RecurrenceCalculator.occurrences(
            RecurrenceType.ONE_TIME, start, endDate = null, installmentsTotal = null,
        ) shouldBe listOf(start)
    }

    "INSTALLMENTS gera exatamente N parcelas mensais consecutivas" {
        val start = Instant.parse("2025-03-01T00:00:00Z")
        val occ = RecurrenceCalculator.occurrences(
            RecurrenceType.INSTALLMENTS, start, endDate = null, installmentsTotal = 12,
        )
        occ.size shouldBe 12
        occ shouldBe (0 until 12).map { plusMonths(it.toLong(), start) }
    }

    "MONTHLY com endDate limita ao fim (nao ultrapassa)" {
        val start = Instant.parse("2024-01-01T00:00:00Z")
        val end = Instant.parse("2024-06-01T00:00:00Z")
        val occ = RecurrenceCalculator.occurrences(
            RecurrenceType.MONTHLY, start, endDate = end, installmentsTotal = null,
        )
        occ.size shouldBe 6 // jan..jun
        occ.last() shouldBe end
    }

    "MONTHLY indefinido cobre passado + ~12 meses futuros" {
        val now = Instant.now()
        val start = now.atZone(ZoneOffset.UTC).minusMonths(24).toInstant()
        val occ = RecurrenceCalculator.occurrences(
            RecurrenceType.MONTHLY, start, endDate = null, installmentsTotal = null, now = now,
        )
        occ.size shouldBeGreaterThanOrEqualTo 12
        occ.any { it.isAfter(now) } shouldBe true
        occ.first() shouldBe start
    }

    "count concorda com occurrences().size" {
        val start = Instant.parse("2025-01-01T00:00:00Z")
        RecurrenceCalculator.count(RecurrenceType.INSTALLMENTS, start, null, 8) shouldBe 8
    }
})
