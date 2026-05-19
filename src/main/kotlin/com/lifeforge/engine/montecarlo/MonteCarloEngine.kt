package com.lifeforge.engine.montecarlo

import com.lifeforge.engine.statistics.RandomGenerators.nextBernoulli
import com.lifeforge.engine.statistics.RandomGenerators.nextNormal
import com.lifeforge.engine.statistics.Statistics
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
        val random = Random(parameters.seed)

        val executionTime = measureTimeMillis {
            // Cada iteracao do laco externo e UMA simulacao independente.
            // Cada iteracao do laco interno e UM mes dessa simulacao.
            for (sim in 0 until parameters.numSimulations) {
                finalCapitals[sim] = simulateSingle(parameters, random)
            }
        }

        return aggregate(finalCapitals, parameters, executionTime)
    }

    /**
     * Executa uma unica simulacao (uma trajetoria estocastica completa).
     *
     * Isolada como funcao privada para facilitar testes e futura paralelizacao.
     */
    private fun simulateSingle(params: MonteCarloParameters, random: Random): Double {
        var capital = params.initialCapital
        var unemployedMonthsRemaining = 0

        // Pre-calcula valores derivados uma vez por simulacao.
        val expectedReturnMonthly = params.expectedReturnMonthly
        val volatilityMonthly = params.volatilityMonthly
        val unemploymentProbMonthly = params.unemploymentProbMonthly

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

            // 5. Garante que o patrimonio nao fique negativo (limite de ruina).
            //    Se cair a zero (drawdown extremo), permanece em zero ate aporte futuro.
            if (capital < 0.0) capital = 0.0
        }

        return capital
    }

    /**
     * Agrega os N resultados brutos em estatisticas descritivas.
     */
    private fun aggregate(
        finalCapitals: DoubleArray,
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
            executionTimeMs = executionTimeMs,
        )
    }
}
