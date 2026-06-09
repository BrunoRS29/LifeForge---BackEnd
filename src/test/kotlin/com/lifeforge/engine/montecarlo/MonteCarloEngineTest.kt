package com.lifeforge.engine.montecarlo

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.pow

/**
 * Testes unitarios da [MonteCarloEngine].
 *
 * Cobertura conforme TCC (Tarefa 2.3):
 *   (a) Volatilidade zero -> resultado deterministico (todas simulacoes iguais)
 *   (b) Seed fixa -> resultado reproduzivel
 *   (c) Distribuicao dos resultados segue o esperado estatisticamente
 *   (d) Performance com 10.000 iteracoes < 2 segundos
 */
class MonteCarloEngineTest : StringSpec({

    val engine = MonteCarloEngine()

    "(a) volatilidade zero produz resultado deterministico identico a juros compostos" {
        // Sem volatilidade, sem desemprego, sem inflacao: a simulacao deve
        // colapsar para a formula classica de juros compostos com aporte:
        //   P(t) = P0 * (1+r)^t + A * ((1+r)^t - 1) / r
        val params = MonteCarloParameters(
            initialCapital = 10_000.0,
            monthlyContribution = 1_000.0,
            expectedReturnAnnual = 0.10,
            volatilityAnnual = 0.0, // <- determinismo
            horizonMonths = 120, // 10 anos
            targetAmount = 250_000.0,
            numSimulations = 100, // pequeno: todas devem dar igual
            seed = 1L,
        )

        val result = engine.run(params)

        // Calculo deterministico esperado (juros compostos com aporte).
        val r = (1.0 + 0.10).pow(1.0 / 12.0) - 1.0
        val n = 120
        val expected = 10_000.0 * (1.0 + r).pow(n) +
            1_000.0 * (((1.0 + r).pow(n) - 1.0) / r)

        result.mean shouldBe (expected plusOrMinus 0.5)
        result.standardDeviation shouldBe (0.0 plusOrMinus 1e-6)
        result.percentiles[5.0]!! shouldBe (expected plusOrMinus 0.5)
        result.percentiles[95.0]!! shouldBe (expected plusOrMinus 0.5)
    }

    "(e) choque de despesa inesperada reduz o patrimonio medio final" {
        // Mesma config base; a unica diferenca e ligar o choque de despesa
        // (Poisson + Exponencial). Como o choque so retira dinheiro, o
        // patrimonio medio com choque deve ser menor que sem choque.
        val base = MonteCarloParameters(
            initialCapital = 10_000.0,
            monthlyContribution = 1_000.0,
            expectedReturnAnnual = 0.10,
            volatilityAnnual = 0.15,
            horizonMonths = 120,
            targetAmount = 250_000.0,
            numSimulations = 5_000,
            seed = 7L,
        )
        val comChoque = base.copy(
            unexpectedExpenseAnnualFrequency = 1.5,
            unexpectedExpenseMeanAmount = 3_000.0,
        )

        val semChoque = engine.run(base)
        val resultadoComChoque = engine.run(comChoque)

        resultadoComChoque.mean shouldBeLessThan semChoque.mean
        resultadoComChoque.successProbability shouldBeGreaterThanOrEqualTo 0.0
        resultadoComChoque.successProbability shouldBeLessThanOrEqualTo 1.0
    }

    "(f) choque desativado (frequencia/magnitude zero) nao altera o resultado" {
        val params = MonteCarloParameters(
            initialCapital = 10_000.0,
            monthlyContribution = 1_000.0,
            expectedReturnAnnual = 0.08,
            volatilityAnnual = 0.12,
            horizonMonths = 120,
            targetAmount = 250_000.0,
            numSimulations = 2_000,
            seed = 99L,
        )
        // Default (sem choque) vs. explicitamente zerado: random stream
        // identico (nenhum sorteio extra), entao a media e exatamente igual.
        val zerado = params.copy(
            unexpectedExpenseAnnualFrequency = 0.0,
            unexpectedExpenseMeanAmount = 0.0,
        )
        engine.run(params).mean shouldBe engine.run(zerado).mean
    }

    "(g) variacao de renda introduz dispersao no patrimonio final" {
        // Sem volatilidade de mercado nem desemprego, a unica fonte de incerteza
        // passa a ser a variacao de renda (Secao 6.2).
        val base = MonteCarloParameters(
            initialCapital = 10_000.0,
            monthlyContribution = 2_000.0,
            expectedReturnAnnual = 0.08,
            volatilityAnnual = 0.0,
            horizonMonths = 120,
            targetAmount = 300_000.0,
            numSimulations = 5_000,
            seed = 11L,
        )
        val comVariacao = base.copy(incomeVolatilityAnnual = 0.30)

        val semVariacao = engine.run(base)
        val resultadoComVariacao = engine.run(comVariacao)

        // Sem variacao, e deterministico (dispersao ~ 0).
        semVariacao.standardDeviation shouldBeLessThan 1.0
        // Com variacao de renda, surge dispersao no patrimonio final.
        resultadoComVariacao.standardDeviation shouldBeGreaterThan semVariacao.standardDeviation
        resultadoComVariacao.successProbability shouldBeGreaterThanOrEqualTo 0.0
        resultadoComVariacao.successProbability shouldBeLessThanOrEqualTo 1.0
    }

    "(b) mesma seed produz exatamente o mesmo resultado (reprodutibilidade)" {
        val params = MonteCarloParameters(
            initialCapital = 50_000.0,
            monthlyContribution = 2_000.0,
            expectedReturnAnnual = 0.08,
            volatilityAnnual = 0.15,
            horizonMonths = 240,
            targetAmount = 1_000_000.0,
            numSimulations = 10_000,
            seed = 42L, // <- seed fixa
        )

        val r1 = engine.run(params)
        val r2 = engine.run(params)

        r1.mean shouldBe r2.mean
        r1.median shouldBe r2.median
        r1.successProbability shouldBe r2.successProbability
        r1.percentiles shouldBe r2.percentiles
    }

    "(b.2) seeds diferentes produzem resultados diferentes" {
        val base = MonteCarloParameters(
            initialCapital = 50_000.0,
            monthlyContribution = 2_000.0,
            expectedReturnAnnual = 0.08,
            volatilityAnnual = 0.15,
            horizonMonths = 240,
            targetAmount = 1_000_000.0,
            numSimulations = 1_000,
            seed = 1L,
        )

        val r1 = engine.run(base)
        val r2 = engine.run(base.copy(seed = 2L))

        // Resultados devem ser diferentes, mas proximos (mesma distribuicao)
        (r1.mean != r2.mean) shouldBe true
    }

    "(c) probabilidade de sucesso fica entre 0 e 1, percentis sao monotonicos" {
        val params = MonteCarloParameters(
            initialCapital = 20_000.0,
            monthlyContribution = 1_500.0,
            expectedReturnAnnual = 0.09,
            volatilityAnnual = 0.20,
            horizonMonths = 180,
            targetAmount = 500_000.0,
            numSimulations = 10_000,
            seed = 77L,
        )

        val result = engine.run(params)

        // Probabilidade no intervalo valido
        result.successProbability shouldBeGreaterThanOrEqualTo 0.0
        result.successProbability shouldBeLessThanOrEqualTo 1.0

        // Percentis devem ser monotonicamente crescentes
        val p = result.percentiles
        p[5.0]!! shouldBeLessThanOrEqualTo p[10.0]!!
        p[10.0]!! shouldBeLessThanOrEqualTo p[25.0]!!
        p[25.0]!! shouldBeLessThanOrEqualTo p[50.0]!!
        p[50.0]!! shouldBeLessThanOrEqualTo p[75.0]!!
        p[75.0]!! shouldBeLessThanOrEqualTo p[90.0]!!
        p[90.0]!! shouldBeLessThanOrEqualTo p[95.0]!!

        // Mediana e P50 sao a mesma coisa
        result.median shouldBe p[50.0]!!

        // Histograma com 50 buckets cobre todos os resultados
        result.histogram shouldHaveSize 50
        result.histogram.sumOf { it.count } shouldBe params.numSimulations
    }

    "(c.2) cenario otimista (alta probabilidade de sucesso) reflete na metrica" {
        // Aporte alto e meta baixa -> sucesso quase garantido
        val optimistic = MonteCarloParameters(
            initialCapital = 100_000.0,
            monthlyContribution = 5_000.0,
            expectedReturnAnnual = 0.10,
            volatilityAnnual = 0.10,
            horizonMonths = 240,
            targetAmount = 200_000.0,
            numSimulations = 10_000,
            seed = 100L,
        )

        engine.run(optimistic).successProbability shouldBeGreaterThan 0.95
    }

    "(c.3) cenario pessimista (baixa probabilidade de sucesso) reflete na metrica" {
        // Aporte baixo e meta inalcancavel -> sucesso quase impossivel
        val pessimistic = MonteCarloParameters(
            initialCapital = 1_000.0,
            monthlyContribution = 100.0,
            expectedReturnAnnual = 0.05,
            volatilityAnnual = 0.15,
            horizonMonths = 60,
            targetAmount = 1_000_000.0,
            numSimulations = 10_000,
            seed = 200L,
        )

        engine.run(pessimistic).successProbability shouldBeLessThan 0.05
    }

    "(c.4) inflacao reduz o patrimonio real abaixo do nominal" {
        val params = MonteCarloParameters(
            initialCapital = 50_000.0,
            monthlyContribution = 2_000.0,
            expectedReturnAnnual = 0.10,
            volatilityAnnual = 0.0,
            horizonMonths = 120,
            targetAmount = 500_000.0,
            inflationAnnual = 0.05, // 5% a.a.
            numSimulations = 100,
            seed = 1L,
        )

        val result = engine.run(params)

        // Em 10 anos com 5% a.a., o deflator e (1.05)^10 ~= 1.629
        val expectedDeflator = 1.05.pow(10.0)
        val expectedReal = result.mean / expectedDeflator

        result.meanReal shouldBe (expectedReal plusOrMinus 1.0)
        result.meanReal shouldBeLessThan result.mean
    }

    "(d) performance: 10.000 simulacoes x 240 meses executam em menos de 2 segundos" {
        val params = MonteCarloParameters(
            initialCapital = 50_000.0,
            monthlyContribution = 2_000.0,
            expectedReturnAnnual = 0.08,
            volatilityAnnual = 0.15,
            horizonMonths = 240,
            targetAmount = 1_000_000.0,
            unemploymentProbAnnual = 0.05,
            inflationAnnual = 0.04,
            numSimulations = 10_000,
            seed = 1L,
        )

        val result = engine.run(params)

        // Criterio do TCC (Tarefa 2.3.d)
        result.executionTimeMs shouldBeLessThan 2_000L

        // Sanity check: numero correto de simulacoes
        result.numSimulations shouldBe 10_000
    }

    "validacao: parametros invalidos lancam excecao no construtor" {
        // initialCapital negativo
        runCatching {
            MonteCarloParameters(
                initialCapital = -1.0,
                monthlyContribution = 100.0,
                expectedReturnAnnual = 0.08,
                volatilityAnnual = 0.15,
                horizonMonths = 12,
                targetAmount = 1000.0,
            )
        }.isFailure shouldBe true

        // horizonMonths zero
        runCatching {
            MonteCarloParameters(
                initialCapital = 1000.0,
                monthlyContribution = 100.0,
                expectedReturnAnnual = 0.08,
                volatilityAnnual = 0.15,
                horizonMonths = 0,
                targetAmount = 1000.0,
            )
        }.isFailure shouldBe true

        // probabilidade de desemprego > 1
        runCatching {
            MonteCarloParameters(
                initialCapital = 1000.0,
                monthlyContribution = 100.0,
                expectedReturnAnnual = 0.08,
                volatilityAnnual = 0.15,
                horizonMonths = 12,
                targetAmount = 1000.0,
                unemploymentProbAnnual = 1.5,
            )
        }.isFailure shouldBe true
    }

    "(e) trajetoria do fan chart: horizonte+1 bandas, comeca no capital inicial e e monotonica" {
        val params = MonteCarloParameters(
            initialCapital = 10_000.0,
            monthlyContribution = 1_000.0,
            expectedReturnAnnual = 0.08,
            volatilityAnnual = 0.15,
            horizonMonths = 120,
            targetAmount = 250_000.0,
            numSimulations = 2_000,
            seed = 7L,
        )

        val result = engine.run(params)

        // Uma banda por mes, incluindo o mes 0 (capital inicial).
        result.trajectory shouldHaveSize 121
        result.trajectory.first().monthIndex shouldBe 0
        result.trajectory.last().monthIndex shouldBe 120

        // No mes 0 todas as trajetorias valem o capital inicial (leque fechado).
        result.trajectory.first().p10 shouldBe (10_000.0 plusOrMinus 1e-6)
        result.trajectory.first().p50 shouldBe (10_000.0 plusOrMinus 1e-6)
        result.trajectory.first().p90 shouldBe (10_000.0 plusOrMinus 1e-6)

        // Em cada mes os percentis sao monotonicamente crescentes.
        result.trajectory.forEach { band ->
            band.p10 shouldBeLessThanOrEqualTo band.p25
            band.p25 shouldBeLessThanOrEqualTo band.p50
            band.p50 shouldBeLessThanOrEqualTo band.p75
            band.p75 shouldBeLessThanOrEqualTo band.p90
        }

        // O leque abre ao longo do tempo: largura P10-P90 no fim > no comeco.
        val startWidth = result.trajectory.first().let { it.p90 - it.p10 }
        val endWidth = result.trajectory.last().let { it.p90 - it.p10 }
        endWidth shouldBeGreaterThan startWidth
    }

    "(e.2) volatilidade zero colapsa o leque (P10 == P90 em todos os meses)" {
        val params = MonteCarloParameters(
            initialCapital = 10_000.0,
            monthlyContribution = 1_000.0,
            expectedReturnAnnual = 0.10,
            volatilityAnnual = 0.0,
            horizonMonths = 60,
            targetAmount = 100_000.0,
            numSimulations = 100,
            seed = 1L,
        )

        val result = engine.run(params)

        result.trajectory.forEach { band ->
            band.p90 shouldBe (band.p10 plusOrMinus 1e-6)
        }
    }
})
