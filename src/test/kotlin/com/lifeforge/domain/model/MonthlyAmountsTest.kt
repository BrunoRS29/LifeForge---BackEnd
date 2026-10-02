package com.lifeforge.domain.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.bigdecimal.shouldBeEqualIgnoringScale
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Ritmo mensal do painel: cada serie recorrente conta uma vez (nao N meses) e
 * os pontuais entram pela media recente. Mesmos casos do teste do app.
 */
class MonthlyAmountsTest : StringSpec({

    val zone = ZoneId.of("America/Sao_Paulo")
    val now = Instant.parse("2026-10-02T15:00:00Z")

    fun at(month: YearMonth, day: Int = 10): Instant =
        month.atDay(day).atTime(12, 0).atZone(zone).toInstant()

    fun entry(text: String, kind: String, amount: String, at: Instant, recurring: Boolean = true) =
        MonthlyAmounts.Entry(MonthlyAmounts.seriesKey(text, kind), BigDecimal(amount), at, recurring)

    fun months(count: Int): List<YearMonth> =
        (count - 1 downTo 0).map { YearMonth.of(2026, 9).minusMonths(it.toLong()) }

    "aluguel lancado todo mes como recorrente conta uma vez so" {
        val entries = months(18).flatMap { m ->
            listOf(
                entry("Aluguel", "HOUSING", "2800", at(m)),
                entry("Plano de saúde", "HEALTH", "380", at(m, 8)),
            )
        }
        MonthlyAmounts.recurringTotal(entries, now, zone) shouldBeEqualIgnoringScale BigDecimal("3180")
    }

    "usa o valor mais recente da serie" {
        val entries = listOf(
            entry("Aluguel", "HOUSING", "2500", at(YearMonth.of(2026, 8))),
            entry("Aluguel", "HOUSING", "2800", at(YearMonth.of(2026, 9))),
        )
        MonthlyAmounts.recurringTotal(entries, now, zone) shouldBeEqualIgnoringScale BigDecimal("2800")
    }

    "descricao com acento, caixa ou espacos diferentes e a mesma serie" {
        val entries = listOf(
            entry("Plano de saúde", "HEALTH", "380", at(YearMonth.of(2026, 8))),
            entry("  plano de  SAUDE ", "HEALTH", "390", at(YearMonth.of(2026, 9))),
        )
        MonthlyAmounts.recurringTotal(entries, now, zone) shouldBeEqualIgnoringScale BigDecimal("390")
    }

    "serie encerrada ha mais de tres meses deixa de contar; recorrente unico vale como modelo" {
        val gym = (1..5).map { entry("Academia", "HEALTH", "120", at(YearMonth.of(2026, it))) }
        val template = entry("Internet", "HOUSING", "120", at(YearMonth.of(2025, 2)))
        MonthlyAmounts.recurringTotal(gym + template, now, zone) shouldBeEqualIgnoringScale BigDecimal("120")
    }

    "ocorrencias de meses futuros nao entram, as do mes corrente sim" {
        val entries = listOf(
            entry("Aluguel", "HOUSING", "2800", at(YearMonth.of(2026, 9))),
            entry("Aluguel", "HOUSING", "3000", at(YearMonth.of(2026, 10), 10)),
            entry("Aluguel", "HOUSING", "9999", at(YearMonth.of(2026, 11))),
        )
        MonthlyAmounts.recurringTotal(entries, now, zone) shouldBeEqualIgnoringScale BigDecimal("3000")
    }

    "pontuais entram pela media dos tres meses mais recentes com dados" {
        val entries = listOf(
            entry("Mercado", "FOOD", "900", at(YearMonth.of(2026, 3)), recurring = false),
            entry("Mercado", "FOOD", "1200", at(YearMonth.of(2026, 7)), recurring = false),
            entry("Mercado", "FOOD", "1500", at(YearMonth.of(2026, 8)), recurring = false),
            entry("Mercado", "FOOD", "1800", at(YearMonth.of(2026, 9)), recurring = false),
            entry("Parcela", "OTHER", "5000", at(YearMonth.of(2027, 1)), recurring = false),
        )
        MonthlyAmounts.recentAverage(entries, now, zone) shouldBeEqualIgnoringScale BigDecimal("1500")
    }
})
