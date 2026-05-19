package com.lifeforge.engine.statistics

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Testes da camada de estatistica descritiva (percentis, media, desvio, histograma).
 *
 * Valores esperados foram cross-checados com numpy:
 *   numpy.percentile([1..10], 50, method="linear") == 5.5
 *   numpy.std([1..10], ddof=1) ~= 3.0277
 */
class StatisticsTest : StringSpec({

    val arr = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)

    "media de [1..10] = 5.5" {
        Statistics.mean(arr) shouldBe 5.5
    }

    "mediana de [1..10] = 5.5 (interpolacao linear)" {
        Statistics.median(arr) shouldBe 5.5
    }

    "desvio padrao amostral de [1..10] aproxima 3.0277 (cross-check com numpy)" {
        Statistics.standardDeviation(arr) shouldBe (3.02765 plusOrMinus 0.001)
    }

    "percentil 0 retorna o minimo" {
        Statistics.percentile(arr, 0.0) shouldBe 1.0
    }

    "percentil 100 retorna o maximo" {
        Statistics.percentile(arr, 100.0) shouldBe 10.0
    }

    "percentil 25 (Q1) usando metodo linear" {
        // Cross-check numpy: numpy.percentile([1..10], 25, method="linear") == 3.25
        Statistics.percentile(arr, 25.0) shouldBe (3.25 plusOrMinus 1e-9)
    }

    "percentil 75 (Q3) usando metodo linear" {
        // Cross-check numpy: numpy.percentile([1..10], 75, method="linear") == 7.75
        Statistics.percentile(arr, 75.0) shouldBe (7.75 plusOrMinus 1e-9)
    }

    "multiplos percentis em uma passada retornam mesmos valores" {
        val p = Statistics.multiplePercentiles(arr, listOf(25.0, 50.0, 75.0))
        p[25.0] shouldBe (3.25 plusOrMinus 1e-9)
        p[50.0] shouldBe 5.5
        p[75.0] shouldBe (7.75 plusOrMinus 1e-9)
    }

    "histograma com 5 buckets de [1..10] distribui corretamente" {
        val hist = Statistics.histogram(arr, bucketCount = 5)
        hist shouldHaveSize 5

        // Soma das contagens = total de elementos
        hist.sumOf { it.count } shouldBe arr.size

        // Cobre todo o range
        hist.first().rangeStart shouldBe 1.0
        hist.last().rangeEnd shouldBe 10.0
    }

    "histograma com array constante retorna um unico bucket" {
        val constant = doubleArrayOf(5.0, 5.0, 5.0)
        val hist = Statistics.histogram(constant)
        hist shouldHaveSize 1
        hist[0].count shouldBe 3
    }

    "array vazio lanca IllegalArgumentException" {
        shouldThrow<IllegalArgumentException> {
            Statistics.mean(doubleArrayOf())
        }
        shouldThrow<IllegalArgumentException> {
            Statistics.percentile(doubleArrayOf(), 50.0)
        }
    }

    "percentil fora de [0,100] lanca excecao" {
        shouldThrow<IllegalArgumentException> { Statistics.percentile(arr, -1.0) }
        shouldThrow<IllegalArgumentException> { Statistics.percentile(arr, 101.0) }
    }

    "array com um elemento: desvio padrao = 0 (caso degenerado)" {
        Statistics.standardDeviation(doubleArrayOf(42.0)) shouldBe 0.0
    }
})
