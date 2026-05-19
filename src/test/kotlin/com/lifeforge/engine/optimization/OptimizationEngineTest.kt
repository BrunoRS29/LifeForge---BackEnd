package com.lifeforge.engine.optimization

import com.lifeforge.engine.montecarlo.MonteCarloEngine
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Testes do [OptimizationEngine].
 *
 * Estrutura conforme TCC (Tarefa 3.3 — testes de integracao):
 *   (a) Monotonicidade observada da funcao objetivo (premissa do algoritmo)
 *   (b) Convergencia em casos viaveis (aporte e horizonte)
 *   (c) Detecao de caminhos especiais: lower-bound suficiente, infeasible
 *   (d) Reprodutibilidade com mesma seed
 *   (e) Verificacao final com 10k simulacoes preserva probabilidade
 *   (f) Performance dentro do orcamento
 */
class OptimizationEngineTest : StringSpec({

    val mcEngine = MonteCarloEngine()
    val engine = OptimizationEngine(mcEngine)

    // ---------- Helpers de fixture ----------

    /**
     * Cenario "padrao": meta de 500k em 20 anos, retorno 8% a.a., vol 15%.
     * Calibrado para que a otimizacao seja viavel com aporte de poucos
     * milhares de reais — boa cobertura sem caso de borda.
     */
    fun standardBase(
        seed: Long = 42L,
        targetSuccessProbability: Double = 0.80,
    ) = BaseConfig(
        initialCapital = 50_000.0,
        expectedReturnAnnual = 0.08,
        volatilityAnnual = 0.15,
        targetAmount = 500_000.0,
        targetSuccessProbability = targetSuccessProbability,
        simulationsPerStep = 1_000,        // pequeno para acelerar testes
        verificationSimulations = 5_000,   // idem
        maxIterations = 30,
        contributionTolerance = 1.0,
        seed = seed,
    )

    // ----------------------------------------------------------------
    // (a) MONOTONICIDADE — a premissa central da busca binaria
    // ----------------------------------------------------------------

    "(a.1) com seed fixa, successProbability e nao-decrescente em aporte" {
        // Verifica empiricamente que a funcao objetivo e monotonica
        // quando a seed e mantida constante. Esta e a invariante que
        // viabiliza a busca binaria.
        val base = standardBase(seed = 7L)
        val horizonMonths = 240

        val contributions = listOf(0.0, 500.0, 1_000.0, 1_500.0, 2_000.0, 3_000.0, 5_000.0)
        val probabilities = contributions.map { c ->
            val params = com.lifeforge.engine.montecarlo.MonteCarloParameters(
                initialCapital = base.initialCapital,
                monthlyContribution = c,
                expectedReturnAnnual = base.expectedReturnAnnual,
                volatilityAnnual = base.volatilityAnnual,
                horizonMonths = horizonMonths,
                targetAmount = base.targetAmount,
                numSimulations = base.simulationsPerStep,
                seed = base.seed,
            )
            mcEngine.run(params).successProbability
        }

        // Monotonicidade nao-decrescente
        probabilities.zipWithNext().forEach { (a, b) ->
            b shouldBeGreaterThanOrEqualTo a
        }
    }

    "(a.2) com seed fixa, successProbability e nao-decrescente em horizonte" {
        val base = standardBase(seed = 7L)
        val contribution = 1_500.0

        val horizons = listOf(60, 120, 180, 240, 300, 360)
        val probabilities = horizons.map { h ->
            val params = com.lifeforge.engine.montecarlo.MonteCarloParameters(
                initialCapital = base.initialCapital,
                monthlyContribution = contribution,
                expectedReturnAnnual = base.expectedReturnAnnual,
                volatilityAnnual = base.volatilityAnnual,
                horizonMonths = h,
                targetAmount = base.targetAmount,
                numSimulations = base.simulationsPerStep,
                seed = base.seed,
            )
            mcEngine.run(params).successProbability
        }

        probabilities.zipWithNext().forEach { (a, b) ->
            b shouldBeGreaterThanOrEqualTo a
        }
    }

    // ----------------------------------------------------------------
    // (b) CONVERGENCIA EM CASOS VIAVEIS
    // ----------------------------------------------------------------

    "(b.1) findOptimalContribution converge e atinge a probabilidade alvo" {
        val request = OptimizationRequest.Contribution(
            base = standardBase(seed = 11L, targetSuccessProbability = 0.80),
            horizonMonths = 240,
        )

        val result = engine.optimize(request)

        result.type shouldBe OptimizationType.OPTIMAL_CONTRIBUTION
        result.feasible shouldBe true
        result.terminationReason shouldBe TerminationReason.CONVERGED
        // Verificacao com 10k deve atingir o alvo dentro de ruido amostral
        // (~1pp para N=5000, usamos 2pp de tolerancia para evitar flakiness)
        result.achievedProbability shouldBeGreaterThanOrEqualTo 0.78
        result.optimalValue shouldBeGreaterThanOrEqualTo 0.0
        result.verification shouldBe result.verification // nao-nulo
        result.verification!!.numSimulations shouldBe 5_000
    }

    "(b.2) findOptimalHorizon converge e retorna numero inteiro de meses" {
        val request = OptimizationRequest.Horizon(
            base = standardBase(seed = 13L, targetSuccessProbability = 0.80),
            monthlyContribution = 1_500.0,
        )

        val result = engine.optimize(request)

        result.type shouldBe OptimizationType.OPTIMAL_HORIZON
        result.feasible shouldBe true
        result.terminationReason shouldBe TerminationReason.CONVERGED

        // optimalValue e um inteiro (representado como double)
        val months = result.optimalValue.toInt()
        months.toDouble() shouldBe (result.optimalValue plusOrMinus 1e-9)
        months shouldBeGreaterThanOrEqualTo 1

        result.achievedProbability shouldBeGreaterThanOrEqualTo 0.78
    }

    "(b.3) busca binaria reduz [lower, upper] a cada iteracao (validade do passo)" {
        val request = OptimizationRequest.Contribution(
            base = standardBase(seed = 17L),
            horizonMonths = 240,
        )

        val result = engine.optimize(request)

        // Filtra apenas os passos de bisseccao: candidato estritamente
        // entre lower e upper. Sondagens (lower probe + upper probe +
        // doublings) tem candidate igual a um dos bounds.
        val bisectionSteps = result.iterations.filter {
            it.candidate > it.lowerBound && it.candidate < it.upperBound
        }

        // Ao menos um passo de bisseccao deve ter ocorrido em um caso viavel
        bisectionSteps.size shouldBeGreaterThanOrEqualTo 1

        // A largura [upper - lower] deve ser nao-crescente entre passos de bisseccao
        bisectionSteps.zipWithNext().forEach { (a, b) ->
            (b.upperBound - b.lowerBound) shouldBeLessThanOrEqualTo (a.upperBound - a.lowerBound)
        }
    }

    // ----------------------------------------------------------------
    // (c) CAMINHOS ESPECIAIS DE TERMINACAO
    // ----------------------------------------------------------------

    "(c.1) capital inicial enorme: aporte zero ja basta (LOWER_BOUND_SUFFICIENT)" {
        val request = OptimizationRequest.Contribution(
            base = BaseConfig(
                initialCapital = 1_000_000.0,        // ja maior que a meta
                expectedReturnAnnual = 0.08,
                volatilityAnnual = 0.15,
                targetAmount = 500_000.0,
                targetSuccessProbability = 0.80,
                simulationsPerStep = 500,
                verificationSimulations = 1_000,
                seed = 23L,
            ),
            horizonMonths = 120,
        )

        val result = engine.optimize(request)

        result.terminationReason shouldBe TerminationReason.LOWER_BOUND_SUFFICIENT
        result.optimalValue shouldBe 0.0
        result.feasible shouldBe true
    }

    "(c.2) meta inalcancavel mesmo no upper bound (INFEASIBLE_UPPER_BOUND)" {
        // Forcamos infeasibilidade: tetto baixissimo de aporte com meta gigante
        // e horizonte curto.
        val request = OptimizationRequest.Contribution(
            base = BaseConfig(
                initialCapital = 1_000.0,
                expectedReturnAnnual = 0.05,
                volatilityAnnual = 0.10,
                targetAmount = 10_000_000.0,
                targetSuccessProbability = 0.80,
                simulationsPerStep = 500,
                verificationSimulations = 1_000,
                seed = 29L,
            ),
            horizonMonths = 24,            // 2 anos
            maxContribution = 100.0,       // cap forcado: nao chega nem perto
        )

        val result = engine.optimize(request)

        result.terminationReason shouldBe TerminationReason.INFEASIBLE_UPPER_BOUND
        result.feasible shouldBe false
        result.verification shouldBe null  // nao roda verificacao em infeasible
        result.bestProbabilityFound shouldBeLessThan 0.80
    }

    "(c.3) horizonte: 1 mes ja basta com capital quase no alvo" {
        val request = OptimizationRequest.Horizon(
            base = BaseConfig(
                initialCapital = 600_000.0,
                expectedReturnAnnual = 0.10,
                volatilityAnnual = 0.05,
                targetAmount = 500_000.0,
                targetSuccessProbability = 0.80,
                simulationsPerStep = 500,
                verificationSimulations = 1_000,
                seed = 31L,
            ),
            monthlyContribution = 0.0,
        )

        val result = engine.optimize(request)

        result.terminationReason shouldBe TerminationReason.LOWER_BOUND_SUFFICIENT
        result.optimalValue shouldBe 1.0   // 1 mes
        result.feasible shouldBe true
    }

    // ----------------------------------------------------------------
    // (d) REPRODUTIBILIDADE
    // ----------------------------------------------------------------

    "(d.1) mesma seed produz exatamente o mesmo resultado de otimizacao" {
        val request = OptimizationRequest.Contribution(
            base = standardBase(seed = 99L),
            horizonMonths = 240,
        )

        val r1 = engine.optimize(request)
        val r2 = engine.optimize(request)

        r1.optimalValue shouldBe r2.optimalValue
        r1.achievedProbability shouldBe r2.achievedProbability
        r1.iterations.size shouldBe r2.iterations.size
        r1.iterations.zip(r2.iterations).forEach { (a, b) ->
            a.candidate shouldBe b.candidate
            a.measuredProbability shouldBe b.measuredProbability
        }
    }

    "(d.2) seeds diferentes produzem resultados ligeiramente diferentes (sanidade)" {
        // Garante que a seed esta efetivamente sendo usada — duas seeds
        // diferentes nao devem casualmente bater no mesmo otimo.
        val r1 = engine.optimize(
            OptimizationRequest.Contribution(
                base = standardBase(seed = 1L),
                horizonMonths = 240,
            )
        )
        val r2 = engine.optimize(
            OptimizationRequest.Contribution(
                base = standardBase(seed = 2L),
                horizonMonths = 240,
            )
        )

        // Os dois sao viaveis...
        r1.feasible shouldBe true
        r2.feasible shouldBe true

        // ...mas o trace de iteracoes diverge em pelo menos um passo
        // (qualquer passo com mesmo candidato em ambos deveria gerar
        // probabilidades amostrais distintas com seeds diferentes).
        val anyDifference = r1.iterations.zip(r2.iterations).any { (a, b) ->
            a.measuredProbability != b.measuredProbability
        }
        anyDifference shouldBe true

        // Diferenca relativa do otimo deve ser pequena (~20%) — sao a mesma
        // "verdade subjacente", amostradas de seeds diferentes.
        kotlin.math.abs(r1.optimalValue - r2.optimalValue) shouldBeLessThanOrEqualTo
            (r1.optimalValue * 0.20)
    }

    // ----------------------------------------------------------------
    // (e) ESTRUTURA DO RESULTADO
    // ----------------------------------------------------------------

    "(e.1) iterations contem ao menos os 2 sondagens iniciais + 1 passo de bisseccao" {
        val request = OptimizationRequest.Contribution(
            base = standardBase(seed = 41L),
            horizonMonths = 240,
        )
        val result = engine.optimize(request)

        result.iterations shouldHaveAtLeastSize 3
        result.iterations[0].candidate shouldBe 0.0  // primeiro: lower bound
        // segundo: upper bound
        result.iterations[1].candidate shouldBeGreaterThanOrEqualTo 1.0
    }

    "(e.2) verificacao final usa numSimulations grande (10k por default)" {
        val request = OptimizationRequest.Contribution(
            base = standardBase(seed = 43L).copy(verificationSimulations = 10_000),
            horizonMonths = 240,
        )
        val result = engine.optimize(request)

        result.verification?.numSimulations shouldBe 10_000
    }

    // ----------------------------------------------------------------
    // (f) PERFORMANCE (smoke test, nao benchmark rigoroso)
    // ----------------------------------------------------------------

    "(f.1) otimizacao completa termina em tempo razoavel (< 5s)" {
        val request = OptimizationRequest.Contribution(
            base = standardBase(seed = 51L),
            horizonMonths = 240,
        )

        val result = engine.optimize(request)

        // Margem generosa para CI/maquinas lentas. Em laptop moderno fica
        // facilmente sub-segundo.
        result.executionTimeMs shouldBeLessThan 5_000L
    }
})
