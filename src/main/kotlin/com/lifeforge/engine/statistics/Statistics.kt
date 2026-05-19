package com.lifeforge.engine.statistics

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Funcoes estatisticas descritivas usadas pela Engine de Monte Carlo.
 *
 * Todas as funcoes operam em [DoubleArray] e sao puras (sem efeitos colaterais).
 * Para entradas grandes (10k+ elementos), o calculo de percentis ordena uma copia
 * do array; em hot paths considerar reusar o array ja ordenado.
 */
object Statistics {

    /**
     * Media aritmetica.
     *
     * Formula: mean = sum(x_i) / N
     */
    fun mean(values: DoubleArray): Double {
        require(values.isNotEmpty()) { "values nao pode ser vazio" }
        return values.sum() / values.size
    }

    /**
     * Desvio padrao amostral (Bessel correction com N-1).
     *
     * Formula: sigma = sqrt( sum((x_i - mean)^2) / (N - 1) )
     *
     * Para N == 1 retorna 0.0 (variancia indefinida).
     */
    fun standardDeviation(values: DoubleArray): Double {
        require(values.isNotEmpty()) { "values nao pode ser vazio" }
        if (values.size == 1) return 0.0

        val m = mean(values)
        var sumSquaredDiff = 0.0
        for (v in values) {
            val diff = v - m
            sumSquaredDiff += diff * diff
        }
        return sqrt(sumSquaredDiff / (values.size - 1))
    }

    /**
     * Percentil via interpolacao linear (metodo "linear" / type 7 do R/numpy).
     *
     * Formula:
     *   rank = (p / 100) * (N - 1)
     *   floor = floor(rank); frac = rank - floor
     *   percentil = sorted[floor] + frac * (sorted[floor+1] - sorted[floor])
     *
     * Mais preciso que indexacao direta para N pequenos.
     *
     * @param values array de entrada (sera ordenado internamente)
     * @param p percentil em [0, 100]
     */
    fun percentile(values: DoubleArray, p: Double): Double {
        require(values.isNotEmpty()) { "values nao pode ser vazio" }
        require(p in 0.0..100.0) { "p deve estar em [0, 100], recebido: $p" }

        val sorted = values.sortedArray()
        return percentileSorted(sorted, p)
    }

    /**
     * Versao otimizada de [percentile] para quando o array ja esta ordenado.
     * Util para calcular varios percentis sem reordenar a cada chamada.
     */
    fun percentileSorted(sorted: DoubleArray, p: Double): Double {
        require(sorted.isNotEmpty()) { "sorted nao pode ser vazio" }
        require(p in 0.0..100.0) { "p deve estar em [0, 100], recebido: $p" }

        if (sorted.size == 1) return sorted[0]

        val rank = (p / 100.0) * (sorted.size - 1)
        val lowerIndex = floor(rank).toInt()
        val frac = rank - lowerIndex

        return if (lowerIndex >= sorted.size - 1) {
            sorted.last()
        } else {
            sorted[lowerIndex] + frac * (sorted[lowerIndex + 1] - sorted[lowerIndex])
        }
    }

    /**
     * Mediana (percentil 50).
     */
    fun median(values: DoubleArray): Double = percentile(values, 50.0)

    /**
     * Calcula multiplos percentis em uma unica passada (ordena o array uma vez).
     *
     * @param values array de entrada
     * @param percentiles lista de percentis desejados em [0, 100]
     * @return mapa percentil -> valor
     */
    fun multiplePercentiles(values: DoubleArray, percentiles: List<Double>): Map<Double, Double> {
        require(values.isNotEmpty()) { "values nao pode ser vazio" }
        val sorted = values.sortedArray()
        return percentiles.associateWith { p -> percentileSorted(sorted, p) }
    }

    /**
     * Gera um histograma com [bucketCount] bins de largura uniforme entre o min e max.
     *
     * Util para visualizacao da distribuicao de resultados sem precisar enviar
     * os 10k valores brutos para o frontend.
     *
     * @param values array de entrada
     * @param bucketCount numero de bins (default: 50)
     * @return lista de [HistogramBucket] ordenada por faixa crescente
     */
    fun histogram(values: DoubleArray, bucketCount: Int = 50): List<HistogramBucket> {
        require(values.isNotEmpty()) { "values nao pode ser vazio" }
        require(bucketCount > 0) { "bucketCount deve ser > 0" }

        val min = values.min()
        val max = values.max()

        // Edge case: todos os valores iguais. Retorna um unico bucket.
        if (min == max) {
            return listOf(HistogramBucket(min, max, values.size))
        }

        val width = (max - min) / bucketCount
        val counts = IntArray(bucketCount)

        for (v in values) {
            // Indice = floor((v - min) / width); valor max cai no ultimo bucket
            val idx = ((v - min) / width).toInt().coerceAtMost(bucketCount - 1)
            counts[idx]++
        }

        return (0 until bucketCount).map { i ->
            HistogramBucket(
                rangeStart = min + i * width,
                rangeEnd = min + (i + 1) * width,
                count = counts[i],
            )
        }
    }
}

/**
 * Bucket de histograma: faixa [rangeStart, rangeEnd) com [count] elementos.
 */
data class HistogramBucket(
    val rangeStart: Double,
    val rangeEnd: Double,
    val count: Int,
)
