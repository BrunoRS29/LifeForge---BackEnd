package com.lifeforge.engine.montecarlo

import com.lifeforge.engine.statistics.HistogramBucket

/**
 * Resultado agregado de uma simulacao de Monte Carlo.
 *
 * NAO contem o array bruto dos N resultados (10k+ doubles) por design:
 *  - Persistir 10k doubles por simulacao explode o tamanho do banco
 *  - O frontend nao precisa dos valores brutos, apenas dos agregados
 *  - Para reproduzir a simulacao, basta usar a mesma seed (campo [seed])
 *
 * Se for necessario recuperar todos os valores, rode novamente com a seed.
 *
 * @param numSimulations quantidade de cenarios efetivamente executados
 * @param seed semente usada (permite reproducao exata)
 * @param targetAmount meta usada para calcular [successProbability]
 * @param successProbability fracao de simulacoes que atingiram a meta, em [0, 1]
 * @param mean patrimonio final medio (nominal)
 * @param median patrimonio final mediano (P50)
 * @param standardDeviation desvio padrao dos patrimonios finais
 * @param percentiles mapa percentil -> valor (P5, P10, P25, P50, P75, P90, P95)
 * @param worstCase pior cenario (P5)
 * @param bestCase melhor cenario (P95)
 * @param meanReal media deflacionada pela inflacao (poder de compra real)
 * @param histogram distribuicao em buckets para visualizacao
 * @param trajectory bandas de percentil mes a mes (P10..P90) para o fan chart
 * @param executionTimeMs tempo de execucao da simulacao em milissegundos
 */
data class MonteCarloResult(
    val numSimulations: Int,
    val seed: Long,
    val targetAmount: Double,
    val successProbability: Double,
    val mean: Double,
    val median: Double,
    val standardDeviation: Double,
    val percentiles: Map<Double, Double>,
    val worstCase: Double,
    val bestCase: Double,
    val meanReal: Double,
    val histogram: List<HistogramBucket>,
    val trajectory: List<TrajectoryBand>,
    val executionTimeMs: Long,
) {
    companion object {
        /**
         * Percentis padroes calculados pela engine. Cobre o intervalo P10-P90
         * (faixa de 80% de confianca) usado na visualizacao "fan chart".
         */
        val DEFAULT_PERCENTILES = listOf(5.0, 10.0, 25.0, 50.0, 75.0, 90.0, 95.0)
    }
}

/**
 * Banda de percentis do patrimonio em um instante (mes) da simulacao.
 *
 * Usada para desenhar o "fan chart" (gráfico de faixa): a cada mes mostra-se
 * o intervalo P10-P90 (80% dos cenarios) com a mediana (P50) ao centro,
 * permitindo visualizar como a incerteza se abre ao longo do tempo.
 *
 * @param monthIndex 0 = inicio (capital inicial), 1..horizonte = meses seguintes
 */
data class TrajectoryBand(
    val monthIndex: Int,
    val p10: Double,
    val p25: Double,
    val p50: Double,
    val p75: Double,
    val p90: Double,
)
