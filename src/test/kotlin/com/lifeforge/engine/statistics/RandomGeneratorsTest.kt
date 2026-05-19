package com.lifeforge.engine.statistics

import com.lifeforge.engine.statistics.RandomGenerators.nextBernoulli
import com.lifeforge.engine.statistics.RandomGenerators.nextExponential
import com.lifeforge.engine.statistics.RandomGenerators.nextLogNormal
import com.lifeforge.engine.statistics.RandomGenerators.nextNormal
import com.lifeforge.engine.statistics.RandomGenerators.nextPoisson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Testes dos geradores de variaveis aleatorias.
 *
 * Validamos duas propriedades essenciais para o uso em Monte Carlo:
 *   1. REPRODUTIBILIDADE: mesma seed -> mesma sequencia
 *   2. CALIBRACAO ESTATISTICA: media e variancia amostrais convergem para os
 *      valores teoricos quando N e grande
 *
 * Tolerancia: usamos N = 100_000 e tolerancia ~1% para a media. Em testes
 * estatisticos perfeitos seriamos mais rigorosos (Kolmogorov-Smirnov), mas
 * para o escopo do TCC validar momentos amostrais e suficiente.
 */
class RandomGeneratorsTest : StringSpec({

    "Normal: mesma seed produz mesma sequencia (reprodutibilidade)" {
        val r1 = Random(42)
        val r2 = Random(42)
        repeat(100) {
            r1.nextNormal(0.0, 1.0) shouldBe r2.nextNormal(0.0, 1.0)
        }
    }

    "Normal: media amostral converge para a teorica (lei dos grandes numeros)" {
        val random = Random(123)
        val n = 100_000
        val targetMean = 5.0
        val targetStdDev = 2.0

        val samples = DoubleArray(n) { random.nextNormal(targetMean, targetStdDev) }
        val sampleMean = samples.average()
        val sampleVariance = samples.map { (it - sampleMean) * (it - sampleMean) }.average()
        val sampleStdDev = sqrt(sampleVariance)

        sampleMean shouldBe (targetMean plusOrMinus 0.05)
        sampleStdDev shouldBe (targetStdDev plusOrMinus 0.05)
    }

    "Normal: stdDev = 0 retorna sempre a media (caso deterministico)" {
        val random = Random(1)
        repeat(100) {
            random.nextNormal(7.5, 0.0) shouldBe 7.5
        }
    }

    "Normal: stdDev negativo lanca IllegalArgumentException" {
        shouldThrow<IllegalArgumentException> {
            Random(1).nextNormal(0.0, -1.0)
        }
    }

    "LogNormal: amostras sao sempre positivas" {
        val random = Random(7)
        repeat(10_000) {
            random.nextLogNormal(0.0, 1.0) shouldBeGreaterThan 0.0
        }
    }

    "LogNormal: media amostral aproxima exp(mu + sigma^2/2)" {
        val random = Random(99)
        val mu = 0.0
        val sigma = 0.5
        val theoreticalMean = exp(mu + sigma * sigma / 2.0) // ~1.1331

        val n = 200_000
        val samples = DoubleArray(n) { random.nextLogNormal(mu, sigma) }
        val sampleMean = samples.average()

        // Tolerancia maior aqui: distribuicao tem cauda pesada, exige N maior
        sampleMean shouldBe (theoreticalMean plusOrMinus 0.02)
    }

    "Bernoulli: frequencia amostral converge para p" {
        val random = Random(31)
        val p = 0.3
        val n = 100_000
        val successes = (0 until n).count { random.nextBernoulli(p) }
        val frequency = successes.toDouble() / n

        frequency shouldBe (p plusOrMinus 0.01)
    }

    "Bernoulli: p = 0 nunca retorna true" {
        val random = Random(1)
        repeat(1_000) { random.nextBernoulli(0.0) shouldBe false }
    }

    "Bernoulli: p = 1 sempre retorna true" {
        val random = Random(1)
        repeat(1_000) { random.nextBernoulli(1.0) shouldBe true }
    }

    "Bernoulli: p fora de [0,1] lanca IllegalArgumentException" {
        shouldThrow<IllegalArgumentException> { Random(1).nextBernoulli(1.5) }
        shouldThrow<IllegalArgumentException> { Random(1).nextBernoulli(-0.1) }
    }

    "Poisson: media amostral converge para lambda" {
        val random = Random(17)
        val lambda = 3.5
        val n = 50_000
        val samples = IntArray(n) { random.nextPoisson(lambda) }
        val sampleMean = samples.average()

        sampleMean shouldBe (lambda plusOrMinus 0.05)
    }

    "Poisson: variancia amostral aproxima lambda (propriedade da distribuicao)" {
        val random = Random(17)
        val lambda = 4.0
        val n = 50_000
        val samples = IntArray(n) { random.nextPoisson(lambda) }
        val mean = samples.average()
        val variance = samples.map { (it - mean) * (it - mean) }.average()

        variance shouldBe (lambda plusOrMinus 0.1)
    }

    "Poisson: lambda <= 0 lanca IllegalArgumentException" {
        shouldThrow<IllegalArgumentException> { Random(1).nextPoisson(0.0) }
        shouldThrow<IllegalArgumentException> { Random(1).nextPoisson(-1.0) }
    }

    "Exponencial: media amostral converge para 1/lambda" {
        val random = Random(53)
        val lambda = 2.0
        val expectedMean = 1.0 / lambda // = 0.5
        val n = 100_000
        val samples = DoubleArray(n) { random.nextExponential(lambda) }
        val sampleMean = samples.average()

        sampleMean shouldBe (expectedMean plusOrMinus 0.005)
    }

    "Exponencial: amostras sao sempre nao-negativas" {
        val random = Random(53)
        repeat(10_000) {
            random.nextExponential(1.0) shouldBeGreaterThan 0.0
        }
    }
})
