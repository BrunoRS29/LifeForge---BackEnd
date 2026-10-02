package com.lifeforge.ml

import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Recuo da calibracao por IA ("partida a frio") - TCC, Secoes 4.7 e 5.2.
 *
 * Os modelos preditivos exigem um historico minimo (6 receitas para a
 * regressao de renda, 12 despesas para a Random Forest). Abaixo disso - ou com
 * o microsservico indisponivel - a simulacao calibrada nao deve simplesmente
 * falhar: ela recua para
 *
 *  1. os dados declarados pelo proprio usuario no perfil estendido (salario e
 *     aporte mensal), que pela estrategia de sobreposicao prevalecem sobre
 *     qualquer padrao estatistico;
 *  2. medias simples do historico ja registrado (ultimos 12 meses);
 *  3. a base de estatisticas de referencia, que fornece a volatilidade de renda
 *     tipica do vinculo de trabalho (papel que a volatilidade residual da
 *     regressao cumpriria).
 *
 * Funcoes puras: sem acesso a banco nem a rede, para serem testadas isoladamente.
 */
object ColdStartCalibration {

    /** Origem de cada insumo da calibracao, devolvida ao app por transparencia. */
    enum class Source { ML_MODEL, PROFILE, HISTORY_AVERAGE }

    /** Janela das medias simples: o passado recente representa melhor a situacao atual. */
    const val AVERAGE_WINDOW_MONTHS = 12L

    /**
     * Media mensal de lancamentos: soma dos valores dividida pelo numero de meses
     * distintos com lancamento, considerando apenas os ultimos
     * [AVERAGE_WINDOW_MONTHS] meses ate [now] (inclusive). Lancamentos futuros -
     * por exemplo, parcelas agendadas - nao entram, pela mesma razao que nao
     * entram no treino dos modelos.
     *
     * @return null quando nao ha lancamento na janela.
     */
    fun monthlyAverage(entries: List<Pair<Instant, BigDecimal>>, now: Instant): Double? {
        val currentMonth = YearMonth.from(now.atZone(ZoneOffset.UTC))
        val firstMonth = currentMonth.minusMonths(AVERAGE_WINDOW_MONTHS - 1)
        val recent = entries.filter { (at, _) ->
            !at.isAfter(now) && !YearMonth.from(at.atZone(ZoneOffset.UTC)).isBefore(firstMonth)
        }
        if (recent.isEmpty()) return null
        val months = recent.map { (at, _) -> YearMonth.from(at.atZone(ZoneOffset.UTC)) }.distinct().size
        return recent.sumOf { (_, amount) -> amount.toDouble() } / months
    }

    /**
     * Converte um valor monetario digitado no perfil em numero. O app grava o
     * texto como o usuario digitou: "8.500,00" (pt-BR), "8500", "1500.50" ou
     * "R$ 1.200". Mesma heuristica do `parseCurrencyInput` do aplicativo:
     * com virgula, pontos sao milhar e a virgula e decimal; sem virgula, um
     * ponto seguido de mais de dois digitos e separador de milhar.
     *
     * @return null para texto vazio, invalido ou valor nao positivo.
     */
    fun parseAmount(raw: String?): Double? {
        val trimmed = raw?.replace("R$", "")?.replace(" ", "")?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val normalized = if (trimmed.contains(',')) {
            trimmed.replace(".", "").replace(",", ".")
        } else {
            val lastDot = trimmed.lastIndexOf('.')
            if (lastDot >= 0 && trimmed.length - lastDot - 1 > 2) trimmed.replace(".", "") else trimmed
        }
        return normalized.toDoubleOrNull()?.takeIf { it > 0.0 }
    }

    /** Le um campo textual do JSON do perfil estendido (coluna jsonb). */
    fun profileString(profile: JsonElement?, key: String): String? =
        ((profile as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
}
