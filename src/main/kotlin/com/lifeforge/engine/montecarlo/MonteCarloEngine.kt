package com.lifeforge.engine.montecarlo

import com.lifeforge.engine.statistics.RandomGenerators.nextBernoulli
import com.lifeforge.engine.statistics.RandomGenerators.nextExponential
import com.lifeforge.engine.statistics.RandomGenerators.nextNormal
import com.lifeforge.engine.statistics.RandomGenerators.nextPoisson
import com.lifeforge.engine.statistics.Statistics
import java.util.stream.IntStream
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * Engine de Simulacao de Monte Carlo para projecao de patrimonio.
 *
 * Esta classe e o nucleo tecnico do TCC. Executa N simulacoes (default 10k)
 * variando estocasticamente as principais variaveis financeiras:
 *
 *  - retorno mensal da carteira: amostrado de Normal(media, desvio)
 *  - evento de desemprego: amostrado de Bernoulli(prob_mensal) a cada mes
 *  - despesa inesperada: numero de eventos/mes ~ Poisson(lambda_mensal) e
 *    magnitude de cada evento ~ Exponencial(media) (Proposta, Secao 6.2)
 *
 * Modelo matematico (formula determinstica subjacente):
 *
 *   P(t+1) = P(t) * (1 + r_t) + A_t
 *
 * Onde:
 *   P(t)  = patrimonio no mes t
 *   r_t   = retorno mensal (variavel aleatoria)
 *   A_t   = aporte do mes t (zero se desempregado)
 *
 * A simulacao executa essa recorrencia [horizonMonths] vezes para cada
 * uma das N simulacoes, gerando uma distribuicao de patrimonios finais.
 */
class MonteCarloEngine {

    /**
     * Executa a simulacao de Monte Carlo de forma sincrona.
     *
     * Para 10k simulacoes com horizonte de 240 meses (20 anos), executa em
     * ~200-500ms em uma JVM moderna. Como a chamada e CPU-bound, deve ser
     * invocada de Dispatchers.Default no contexto de coroutine para nao
     * bloquear o event loop do servidor HTTP.
     *
     * @param parameters parametros calibrados da simulacao
     * @return [MonteCarloResult] com agregados, percentis e histograma
     */
    fun run(parameters: MonteCarloParameters): MonteCarloResult {
        val finalCapitals = DoubleArray(parameters.numSimulations)

        // Para o fan chart guardamos a trajetoria mes a mes de uma AMOSTRA das
        // simulacoes - nao de todas: 10k x 240 doubles explodiria memoria e o
        // payload JSON. Algumas centenas de amostras ja estabilizam os percentis.
        val trajectorySampleSize = minOf(parameters.numSimulations, TRAJECTORY_SAMPLE_SIZE)
        val sampledPaths = Array(trajectorySampleSize) {
            DoubleArray(parameters.horizonMonths + 1)
        }

        val executionTime = measureTimeMillis {
            // As N simulacoes sao independentes -> paralelizamos pelos nucleos
            // disponiveis. Cada simulacao recebe um RNG proprio, semeado de
            // forma deterministica a partir de (seed, indice), de modo que o
            // resultado independe da ordem de execucao e a reprodutibilidade por
            // seed e preservada. As escritas vao para indices distintos de
            // finalCapitals/sampledPaths, sem condicao de corrida.
            IntStream.range(0, parameters.numSimulations).parallel().forEach { sim ->
                val rng = Random(simSeed(parameters.seed, sim))
                val path = if (sim < trajectorySampleSize) sampledPaths[sim] else null
                finalCapitals[sim] = simulateSingle(parameters, rng, path)
            }
        }

        return aggregate(finalCapitals, sampledPaths, parameters, executionTime)
    }

    /**
     * Executa uma unica simulacao (uma trajetoria estocastica completa).
     *
     * Isolada como funcao privada para facilitar testes e futura paralelizacao.
     */
    private fun simulateSingle(
        params: MonteCarloParameters,
        random: Random,
        path: DoubleArray? = null,
    ): Double {
        var capital = params.initialCapital
        var unemployedMonthsRemaining = 0

        // path != null apenas para a amostra usada no fan chart. Indice 0 = mes
        // inicial (capital inicial); indice month+1 = patrimonio ao fim do mes.
        path?.set(0, capital)

        // Pre-calcula valores derivados uma vez por simulacao.
        val expectedReturnMonthly = params.expectedReturnMonthly
        val volatilityMonthly = params.volatilityMonthly
        val unemploymentProbMonthly = params.unemploymentProbMonthly
        val shockMonthlyLambda = params.unexpectedExpenseMonthlyFrequency
        val shockMeanAmount = params.unexpectedExpenseMeanAmount
        val shocksEnabled = shockMonthlyLambda > 0.0 && shockMeanAmount > 0.0

        for (month in 0 until params.horizonMonths) {
            // 1. Sorteia retorno do mes (Normal).
            //    Equivalente matematico: r_t ~ N(mu_m, sigma_m)
            val monthlyReturn = random.nextNormal(expectedReturnMonthly, volatilityMonthly)

            // 2. Verifica evento de desemprego (Bernoulli mensal).
            //    Se ja esta desempregado, decrementa o contador; caso contrario,
            //    sorteia novo evento.
            if (unemployedMonthsRemaining > 0) {
                unemployedMonthsRemaining--
            } else if (params.unemploymentProbAnnual > 0.0 &&
                random.nextBernoulli(unemploymentProbMonthly)) {
                unemployedMonthsRemaining = params.unemploymentDurationMonths
            }

            // 3. Define aporte do mes: zero se desempregado, contribuicao normal caso contrario.
            val contribution = if (unemployedMonthsRemaining > 0) 0.0
                else params.monthlyContribution

            // 4. Atualiza patrimonio segundo a recorrencia P(t+1) = P(t)*(1+r) + A
            //    O retorno incide sobre o capital existente, depois adiciona o aporte.
            capital = capital * (1.0 + monthlyReturn) + contribution

            // 4b. Choque de despesa inesperada (Proposta 6.2): o numero de eventos
            //     no mes ~ Poisson(lambda_mensal) e cada evento custa ~ Exponencial(
            //     media). E uma saida de caixa, entao subtrai do patrimonio.
            if (shocksEnabled) {
                val numShocks = random.nextPoisson(shockMonthlyLambda)
                if (numShocks > 0) {
                    var shockTotal = 0.0
                    repeat(numShocks) {
                        shockTotal += random.nextExponential(1.0 / shockMeanAmount)
                    }
                    capital -= shockTotal
                }
            }

            // 5. Garante que o patrimonio nao fique negativo (limite de ruina).
            //    Se cair a zero (drawdown extremo), permanece em zero ate aporte futuro.
            if (capital < 0.0) capital = 0.0

            // Registra o patrimonio ao fim deste mes (para o fan chart).
            path?.set(month + 1, capital)
        }

        return capital
    }

    /**
     * Agrega os N resultados brutos em estatisticas descritivas.
     */
    private fun aggregate(
        finalCapitals: DoubleArray,
        sampledPaths: Array<DoubleArray>,
        params: MonteCarloParameters,
        executionTimeMs: Long,
    ): MonteCarloResult {
        val sorted = finalCapitals.sortedArray()

        // Calcula todos os percentis padrao em uma unica passada (sorted ja ordenado).
        val percentiles = MonteCarloResult.DEFAULT_PERCENTILES.associateWith { p ->
            Statistics.percentileSorted(sorted, p)
        }

        // Probabilidade de sucesso = fracao de simulacoes >= meta.
        val successCount = finalCapitals.count { it >= params.targetAmount }
        val successProbability = successCount.toDouble() / params.numSimulations

        val mean = Statistics.mean(finalCapitals)
        val stdDev = Statistics.standardDeviation(finalCapitals)

        return MonteCarloResult(
            numSimulations = params.numSimulations,
            seed = params.seed,
            targetAmount = params.targetAmount,
            successProbability = successProbability,
            mean = mean,
            median = percentiles.getValue(50.0),
            standardDeviation = stdDev,
            percentiles = percentiles,
            worstCase = percentiles.getValue(5.0),
            bestCase = percentiles.getValue(95.0),
            meanReal = mean / params.inflationDeflator,
            histogram = Statistics.histogram(finalCapitals, bucketCount = 50),
            trajectory = buildTrajectory(sampledPaths, params.horizonMonths),
            executionTimeMs = executionTimeMs,
        )
    }

    /**
     * Constroi as bandas de percentil mes a mes a partir das trajetorias
     * amostradas. Para cada mes, ordena os patrimonios da amostra e extrai
     * P10/P25/P50/P75/P90 - a "abertura do leque" que o fan chart desenha.
     */
    private fun buildTrajectory(
        sampledPaths: Array<DoubleArray>,
        horizonMonths: Int,
    ): List<TrajectoryBand> {
        if (sampledPaths.isEmpty()) return emptyList()
        val sampleSize = sampledPaths.size

        return (0..horizonMonths).map { month ->
            // Coluna = patrimonio de cada trajetoria amostrada neste mes.
            val column = DoubleArray(sampleSize) { i -> sampledPaths[i][month] }
            column.sort()
            TrajectoryBand(
                monthIndex = month,
                p10 = Statistics.percentileSorted(column, 10.0),
                p25 = Statistics.percentileSorted(column, 25.0),
                p50 = Statistics.percentileSorted(column, 50.0),
                p75 = Statistics.percentileSorted(column, 75.0),
                p90 = Statistics.percentileSorted(column, 90.0),
            )
        }
    }

    /**
     * Semente deterministica por simulacao, a partir da seed global e do
     * indice. A mistura por constantes de um gerador linear-congruente
     * descorrelaciona substreams de indices vizinhos, tornando a execucao
     * paralela reprodutivel e independente da ordem.
     */
    private fun simSeed(seed: Long, sim: Int): Long =
        seed * 6364136223846793005L + sim.toLong() * 1442695040888963407L + 1L

    companion object {
        /**
         * Numero maximo de trajetorias completas guardadas para o fan chart.
         * Os percentis estabilizam bem com algumas centenas de amostras, entao
         * limitamos o uso de memoria/payload independentemente de numSimulations.
         */
        const val TRAJECTORY_SAMPLE_SIZE = 500
    }
}
