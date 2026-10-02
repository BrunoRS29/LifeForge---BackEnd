package com.lifeforge.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Valores mensais do painel (renda e despesa do mes) a partir dos lancamentos.
 * Espelha `MonthlyAmounts` do app Android: servidor e cliente mostram o mesmo
 * ritmo mensal.
 *
 * - Recorrentes: cada SERIE conta uma unica vez. Lancamentos com a mesma
 *   descricao (normalizada: sem acentos, caixa ou espacos extras) e a mesma
 *   categoria/tipo formam uma serie, cujo valor mensal e o do lancamento mais
 *   recente. Quem lanca o aluguel todo mes marcando "recorrente" (ou importa o
 *   extrato assim) nao ve a despesa multiplicada pelo numero de meses. Uma
 *   serie com varios meses deixa de contar se nao aparece ha mais de
 *   [ACTIVE_MONTHS] meses (foi encerrada); um lancamento recorrente unico vale
 *   como modelo mensal enquanto existir.
 * - Pontuais: media mensal dos [RECENT_MONTHS] meses mais recentes com dados.
 *
 * Em ambos os casos so entram lancamentos ate o fim do mes corrente: as
 * ocorrencias futuras que os agendamentos materializam (ate 12 meses a frente,
 * ver [RecurrenceCalculator]) nao inflam o mes nem deslocam a media.
 *
 * Funcoes puras (sem IO), testadas isoladamente.
 */
object MonthlyAmounts {

    const val ACTIVE_MONTHS = 3L
    const val RECENT_MONTHS = 3

    data class Entry(
        /** Identifica a serie: descricao + categoria/tipo (ver [seriesKey]). */
        val key: String,
        val amount: BigDecimal,
        val at: Instant,
        val recurring: Boolean,
    )

    /** Recorrentes (uma vez por serie ativa) + media recente dos pontuais. */
    fun monthly(entries: List<Entry>, now: Instant, zone: ZoneId): BigDecimal =
        recurringTotal(entries, now, zone) + recentAverage(entries.filterNot { it.recurring }, now, zone)

    fun recurringTotal(entries: List<Entry>, now: Instant, zone: ZoneId): BigDecimal {
        val currentMonth = YearMonth.from(now.atZone(zone))
        val cutoff = endOfMonth(currentMonth, zone)
        return entries
            .filter { it.recurring && it.at.isBefore(cutoff) }
            .groupBy { it.key }
            .values
            .mapNotNull { series ->
                val latest = series.maxBy { it.at }
                val lastMonth = YearMonth.from(latest.at.atZone(zone))
                val active = series.size == 1 || !lastMonth.plusMonths(ACTIVE_MONTHS).isBefore(currentMonth)
                latest.amount.takeIf { active }
            }
            .fold(BigDecimal.ZERO) { acc, amount -> acc + amount }
    }

    /**
     * Media mensal sobre os [months] meses mais recentes COM dados (ate o fim do
     * mes corrente). Ignora meses vazios: um historico com lacunas nao dilui o
     * valor.
     */
    fun recentAverage(entries: List<Entry>, now: Instant, zone: ZoneId, months: Int = RECENT_MONTHS): BigDecimal {
        val cutoff = endOfMonth(YearMonth.from(now.atZone(zone)), zone)
        val byMonth = entries
            .filter { it.at.isBefore(cutoff) }
            .groupBy { YearMonth.from(it.at.atZone(zone)) }
            .mapValues { (_, list) -> list.fold(BigDecimal.ZERO) { acc, e -> acc + e.amount } }
        val recent = byMonth.keys.sortedDescending().take(months)
        if (recent.isEmpty()) return BigDecimal.ZERO
        val total = recent.fold(BigDecimal.ZERO) { acc, month -> acc + byMonth.getValue(month) }
        return total.divide(BigDecimal(recent.size), 2, RoundingMode.HALF_UP)
    }

    /** Primeiro instante do mes seguinte (limite exclusivo). */
    private fun endOfMonth(month: YearMonth, zone: ZoneId): Instant =
        month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()

    /** Chave da serie: "Aluguel " e "aluguel" (HOUSING) sao a mesma serie. */
    fun seriesKey(text: String, kind: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .trim()
            .replace(Regex("\\s+"), " ")
        return "$kind|$normalized"
    }
}
