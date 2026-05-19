package com.lifeforge.engine.statistics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Geradores de variaveis aleatorias para a Engine de Monte Carlo.
 *
 * Todas as funcoes sao extensions de [Random] para permitir reprodutibilidade
 * via seed fixa: `Random(42).nextNormal(0.0, 1.0)` sempre retorna o mesmo valor.
 *
 * Implementacoes baseadas em algoritmos classicos:
 *  - Normal: Box-Muller transform
 *  - LogNormal: exp(Normal)
 *  - Bernoulli: comparacao com uniforme
 *  - Poisson: algoritmo de Knuth (eficiente para lambda < 30)
 *  - Exponencial: inverse CDF (-ln(U)/lambda)
 *
 * Referencia: Knuth, D.E. "The Art of Computer Programming, Vol 2", cap. 3.4.1
 */
object RandomGenerators {

    /**
     * Distribuicao Normal (Gaussiana) via Box-Muller transform.
     *
     * Formula:
     *   z0 = sqrt(-2 * ln(U1)) * cos(2 * pi * U2)
     *   x  = mean + z0 * stdDev
     *
     * Onde U1, U2 ~ Uniforme(0, 1) independentes.
     *
     * @param mean media (mu) da distribuicao
     * @param stdDev desvio padrao (sigma), deve ser >= 0
     */
    fun Random.nextNormal(mean: Double = 0.0, stdDev: Double = 1.0): Double {
        require(stdDev >= 0.0) { "stdDev deve ser >= 0, recebido: $stdDev" }
        if (stdDev == 0.0) return mean

        // U1 nao pode ser zero (ln(0) = -infinito). Coerce evita o edge case.
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        val z0 = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
        return mean + z0 * stdDev
    }

    /**
     * Distribuicao LogNormal: se X ~ Normal(mu, sigma), entao exp(X) ~ LogNormal.
     *
     * Util para modelar precos de ativos e retornos compostos, pois garante
     * valores estritamente positivos e captura a assimetria observada em
     * series financeiras.
     *
     * @param mu media do logaritmo (parametro da Normal subjacente)
     * @param sigma desvio padrao do logaritmo
     */
    fun Random.nextLogNormal(mu: Double = 0.0, sigma: Double = 1.0): Double {
        return exp(nextNormal(mu, sigma))
    }

    /**
     * Distribuicao Bernoulli: retorna true com probabilidade [p], false caso contrario.
     *
     * Modela eventos binarios: ocorre desemprego no mes? despesa inesperada?
     *
     * @param p probabilidade de sucesso, em [0, 1]
     */
    fun Random.nextBernoulli(p: Double): Boolean {
        require(p in 0.0..1.0) { "p deve estar em [0, 1], recebido: $p" }
        return nextDouble() < p
    }

    /**
     * Distribuicao Poisson via algoritmo de Knuth.
     *
     * Conta quantos eventos ocorrem em um intervalo, dado um lambda
     * (taxa media de eventos). Eficiente para lambda < ~30; para valores
     * maiores, deveria usar algoritmo de rejeicao (PA / PTRS).
     *
     * Algoritmo:
     *   L = exp(-lambda)
     *   k = 0; p = 1
     *   repete:
     *     k++
     *     p *= U(0,1)
     *   ate p < L
     *   retorna k - 1
     *
     * @param lambda taxa media de eventos (deve ser > 0)
     */
    fun Random.nextPoisson(lambda: Double): Int {
        require(lambda > 0.0) { "lambda deve ser > 0, recebido: $lambda" }
        if (lambda > 30.0) {
            // Aproximacao Normal: Poisson(lambda) ~ Normal(lambda, sqrt(lambda))
            // para lambda grande. Evita underflow em exp(-lambda).
            return nextNormal(lambda, sqrt(lambda)).coerceAtLeast(0.0).toInt()
        }
        val l = exp(-lambda)
        var k = 0
        var p = 1.0
        do {
            k++
            p *= nextDouble()
        } while (p > l)
        return k - 1
    }

    /**
     * Distribuicao Exponencial via inverse CDF.
     *
     * Modela tempo entre eventos (durabilidade de um emprego, intervalo entre
     * despesas inesperadas). Tem propriedade de ausencia de memoria.
     *
     * Formula: x = -ln(1 - U) / lambda, onde U ~ Uniforme(0, 1)
     *
     * @param lambda taxa (inverso da media), deve ser > 0
     */
    fun Random.nextExponential(lambda: Double): Double {
        require(lambda > 0.0) { "lambda deve ser > 0, recebido: $lambda" }
        val u = nextDouble().coerceAtMost(1.0 - 1e-12)
        return -ln(1.0 - u) / lambda
    }
}
