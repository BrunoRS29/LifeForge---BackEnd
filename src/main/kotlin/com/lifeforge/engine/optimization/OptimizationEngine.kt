package com.lifeforge.engine.optimization

import com.lifeforge.engine.montecarlo.MonteCarloEngine
import com.lifeforge.engine.montecarlo.MonteCarloParameters
import kotlin.math.max
import kotlin.math.pow
import kotlin.system.measureTimeMillis

/**
 * Motor de otimizacao financeira do LifeForge.
 *
 * Implementa duas otimizacoes centrais especificadas no TCC (Secao 5.4):
 *
 *  1. APORTE IDEAL — dado horizonte e meta, qual o menor aporte mensal
 *     que atinge a meta com probabilidade >= alvo (ex: 80%)?
 *
 *  2. PRAZO AJUSTADO — dado aporte e meta, qual o menor horizonte (em meses)
 *     que atinge a meta com probabilidade >= alvo?
 *
 * Estrategia algoritmica
 * ----------------------
 *
 * Ambas as otimizacoes sao buscas binarias sobre uma funcao objetivo
 * monotonica:
 *
 *   - p_sucesso(aporte) e nao-decrescente em aporte (mais dinheiro injetado
 *     => maior probabilidade de atingir a meta)
 *   - p_sucesso(meses) e nao-decrescente em meses (mais tempo => mais
 *     juros compostos + mais aportes => maior probabilidade)
 *
 * A monotonicidade NAO seria perfeita se cada passo da busca usasse uma
 * seed diferente, porque o ruido amostral (~1pp para N=2000) poderia inverter
 * a comparacao em iteracoes proximas. Por isso REUTILIZAMOS A MESMA SEED
 * em todos os passos da busca, o que torna a relacao deterministicamente
 * monotonica.
 *
 * Complexidade
 * ------------
 *
 *   - Cada passo: O(N * H) onde N = simulationsPerStep, H = horizonMonths
 *   - Numero de passos: O(log2((upper - lower) / tol))
 *   - Para aporte: ~25 passos com tol=R$1 e upper=R$50k
 *   - Total tipico: 25 * 2000 * 240 = 12M single-step ops < 1s na JVM
 *
 * Construtor recebe a [MonteCarloEngine] por composicao para facilitar mocks
 * em testes e respeitar a separacao de responsabilidades (este motor nao
 * sabe COMO simular, apenas pede ao MC que simule).
 */
class OptimizationEngine(
    private val monteCarloEngine: MonteCarloEngine,
) {

    private companion object {
        /**
         * Numero maximo de "dobras" do upper bound durante a sondagem inicial.
         *
         * O limite analitico [computeAnalyticalContributionUpperBound] usa
         * apenas a media dos retornos. Quando a volatilidade e alta e o
         * horizonte longo, o aporte verdadeiro para atingir uma probabilidade
         * elevada pode estar muito acima desse valor (a meta cai no quantil
         * da distribuicao final, nao na media).
         *
         * Em vez de declarar infeasible no primeiro upper insuficiente,
         * dobramos ate 6 vezes (= 64x do analitico). Se mesmo assim nao
         * atinge, a meta e genuinamente irrealista.
         *
         * Custo no pior caso: 6 simulacoes extras de 2k iteracoes ~ 0.5s.
         */
        const val MAX_UPPER_BOUND_DOUBLINGS = 6
    }

    /**
     * Despacha a requisicao para o algoritmo apropriado segundo o tipo.
     * E uma conveniencia para chamadas polimorficas (rotas HTTP, p.ex.).
     */
    fun optimize(request: OptimizationRequest): OptimizationResult = when (request) {
        is OptimizationRequest.Contribution -> findOptimalContribution(request)
        is OptimizationRequest.Horizon -> findOptimalHorizon(request)
    }

    // -----------------------------------------------------------------
    // 1. APORTE IDEAL
    // -----------------------------------------------------------------

    /**
     * Busca binaria do menor aporte mensal que atinge a probabilidade alvo.
     *
     * Algoritmo:
     *   1. Computa upper bound analitico inicial (juros compostos exatos
     *      + folga de 2x). Se o usuario forneceu maxContribution, usamos
     *      esse valor sem expansao.
     *   2. Verifica se zero ja basta (caso trivial: meta ja garantida pelo
     *      capital inicial + juros)
     *   3. Sondagem do upper com AUTO-DOUBLING: o limite analitico cobre
     *      apenas a media; com vol alta e horizonte longo, o aporte real
     *      para >= 80% pode estar bem acima. Dobramos ate atingir o alvo
     *      ou o teto absoluto (64x do analitico). Se o usuario fixou um
     *      cap, nao expandimos — respeitamos o limite imposto.
     *   4. Bissecciona [0, upper] ate |upper - lower| < tolerancia
     *   5. Roda verificacao com 10k simulacoes no valor otimo
     */
    fun findOptimalContribution(
        request: OptimizationRequest.Contribution,
    ): OptimizationResult {
        val base = request.base
        val iterations = mutableListOf<IterationStep>()
        var terminationReason = TerminationReason.CONVERGED
        var bestCandidate = 0.0
        var bestProbability = 0.0

        val executionTime = measureTimeMillis {
            val userImposedCap = request.maxContribution != null
            var currentUpper = request.maxContribution
                ?: computeAnalyticalContributionUpperBound(base, request.horizonMonths)

            // (1) Lower bound: sem aporte, ja atinge a meta?
            val probAtZero = simulateContribution(base, request.horizonMonths, 0.0)
            iterations += IterationStep(
                index = iterations.size,
                candidate = 0.0,
                measuredProbability = probAtZero,
                lowerBound = 0.0,
                upperBound = currentUpper,
            )

            if (probAtZero >= base.targetSuccessProbability) {
                bestCandidate = 0.0
                bestProbability = probAtZero
                terminationReason = TerminationReason.LOWER_BOUND_SUFFICIENT
                return@measureTimeMillis
            }

            // (2) Sondagem do upper bound com auto-doubling.
            //     Sondamos o currentUpper; se nao atinge o alvo E nao foi
            //     capado pelo usuario, dobramos e tentamos de novo, ate
            //     MAX_UPPER_BOUND_DOUBLINGS vezes.
            var probAtUpper = simulateContribution(base, request.horizonMonths, currentUpper)
            iterations += IterationStep(
                index = iterations.size,
                candidate = currentUpper,
                measuredProbability = probAtUpper,
                lowerBound = 0.0,
                upperBound = currentUpper,
            )

            var doublings = 0
            while (probAtUpper < base.targetSuccessProbability &&
                !userImposedCap &&
                doublings < MAX_UPPER_BOUND_DOUBLINGS
            ) {
                currentUpper *= 2.0
                probAtUpper = simulateContribution(base, request.horizonMonths, currentUpper)
                doublings++
                iterations += IterationStep(
                    index = iterations.size,
                    candidate = currentUpper,
                    measuredProbability = probAtUpper,
                    lowerBound = 0.0,
                    upperBound = currentUpper,
                )
            }

            // Se mesmo apos as expansoes nao atinge o alvo, meta e infeasible.
            if (probAtUpper < base.targetSuccessProbability) {
                bestCandidate = currentUpper
                bestProbability = probAtUpper
                terminationReason = TerminationReason.INFEASIBLE_UPPER_BOUND
                return@measureTimeMillis
            }

            // currentUpper agora atinge o alvo => candidato valido.
            bestCandidate = currentUpper
            bestProbability = probAtUpper

            // (3) Bisseccao classica.
            //     Invariante: prob(lower) < target <= prob(upper)
            //     Procuramos o menor candidato com prob >= target.
            var lower = 0.0
            var upper = currentUpper

            while (upper - lower > base.contributionTolerance &&
                iterations.size < base.maxIterations
            ) {
                val mid = (lower + upper) / 2.0
                val prob = simulateContribution(base, request.horizonMonths, mid)

                iterations += IterationStep(
                    index = iterations.size,
                    candidate = mid,
                    measuredProbability = prob,
                    lowerBound = lower,
                    upperBound = upper,
                )

                if (prob >= base.targetSuccessProbability) {
                    upper = mid
                    bestCandidate = mid
                    bestProbability = prob
                } else {
                    lower = mid
                }
            }

            terminationReason = if (upper - lower <= base.contributionTolerance) {
                TerminationReason.CONVERGED
            } else {
                TerminationReason.MAX_ITERATIONS
            }
        }

        // (4) Verificacao final com N grande (default 10k).
        val verification = if (terminationReason != TerminationReason.INFEASIBLE_UPPER_BOUND) {
            monteCarloEngine.run(
                buildMonteCarloParameters(
                    base = base,
                    horizonMonths = request.horizonMonths,
                    monthlyContribution = bestCandidate,
                    numSimulations = base.verificationSimulations,
                )
            )
        } else null

        return OptimizationResult(
            type = OptimizationType.OPTIMAL_CONTRIBUTION,
            feasible = terminationReason != TerminationReason.INFEASIBLE_UPPER_BOUND,
            optimalValue = bestCandidate,
            achievedProbability = verification?.successProbability ?: bestProbability,
            targetProbability = base.targetSuccessProbability,
            iterations = iterations,
            verification = verification,
            executionTimeMs = executionTime,
            terminationReason = terminationReason,
        )
    }

    /**
     * Limite superior analitico para a busca de aporte.
     *
     * Resolve a equacao deterministica de juros compostos com aporte:
     *
     *   target = P0 * (1 + r_m)^n + A * ((1 + r_m)^n - 1) / r_m
     *
     * Isolando A:
     *
     *   A = (target - P0 * (1+r_m)^n) * r_m / ((1+r_m)^n - 1)
     *
     * Multiplicamos por 2x como folga estocastica: a volatilidade pode exigir
     * aportes maiores que a media para garantir probabilidade alta. Se r_m
     * for zero ou negativo, fallback para target/n (deposito linear puro).
     *
     * Garantimos minimo de R$1 para evitar upper=0.
     */
    private fun computeAnalyticalContributionUpperBound(
        base: BaseConfig,
        horizonMonths: Int,
    ): Double {
        val rMonthly = (1.0 + base.expectedReturnAnnual).pow(1.0 / 12.0) - 1.0
        val n = horizonMonths
        val gap = base.targetAmount - base.initialCapital * (1.0 + rMonthly).pow(n)

        if (gap <= 0.0) {
            // Capital inicial ja cresce ate a meta sem aporte;
            // upper bound minimo simbolico (a busca encerra cedo).
            return 1.0
        }

        val deterministicContribution = if (rMonthly <= 1e-9) {
            gap / n
        } else {
            gap * rMonthly / ((1.0 + rMonthly).pow(n) - 1.0)
        }

        // Folga 2x para absorver volatilidade. Minimo de R$1 por seguranca.
        return max(deterministicContribution * 2.0, 1.0)
    }

    /**
     * Roda Monte Carlo com numero reduzido de simulacoes para um candidato
     * de aporte. Usado dentro do laco de busca binaria.
     */
    private fun simulateContribution(
        base: BaseConfig,
        horizonMonths: Int,
        contribution: Double,
    ): Double {
        val params = buildMonteCarloParameters(
            base = base,
            horizonMonths = horizonMonths,
            monthlyContribution = contribution,
            numSimulations = base.simulationsPerStep,
        )
        return monteCarloEngine.run(params).successProbability
    }

    // -----------------------------------------------------------------
    // 2. PRAZO AJUSTADO
    // -----------------------------------------------------------------

    /**
     * Busca binaria do menor horizonte (em meses) que atinge a probabilidade
     * alvo, dado um aporte mensal fixo.
     *
     * Diferente da busca por aporte (continua), aqui o espaco e DISCRETO
     * (numeros inteiros de meses). Bisseccao em inteiros termina quando
     * upper - lower <= 1.
     */
    fun findOptimalHorizon(
        request: OptimizationRequest.Horizon,
    ): OptimizationResult {
        val base = request.base
        val iterations = mutableListOf<IterationStep>()
        var terminationReason = TerminationReason.CONVERGED
        var bestCandidate = request.maxHorizonMonths
        var bestProbability = 0.0

        val executionTime = measureTimeMillis {
            // (1) Lower bound: 1 mes basta?
            val probAtMin = simulateHorizon(base, 1, request.monthlyContribution)
            iterations += IterationStep(
                index = 0,
                candidate = 1.0,
                measuredProbability = probAtMin,
                lowerBound = 1.0,
                upperBound = request.maxHorizonMonths.toDouble(),
            )

            if (probAtMin >= base.targetSuccessProbability) {
                bestCandidate = 1
                bestProbability = probAtMin
                terminationReason = TerminationReason.LOWER_BOUND_SUFFICIENT
                return@measureTimeMillis
            }

            // (2) Upper bound: o teto atinge a meta?
            val probAtMax = simulateHorizon(base, request.maxHorizonMonths, request.monthlyContribution)
            iterations += IterationStep(
                index = 1,
                candidate = request.maxHorizonMonths.toDouble(),
                measuredProbability = probAtMax,
                lowerBound = 1.0,
                upperBound = request.maxHorizonMonths.toDouble(),
            )

            if (probAtMax < base.targetSuccessProbability) {
                bestCandidate = request.maxHorizonMonths
                bestProbability = probAtMax
                terminationReason = TerminationReason.INFEASIBLE_UPPER_BOUND
                return@measureTimeMillis
            }

            // (3) Bisseccao em inteiros.
            //     Invariante: prob(lower) < target <= prob(upper)
            var lower = 1
            var upper = request.maxHorizonMonths
            var step = 2

            while (upper - lower > 1 && step < base.maxIterations) {
                val mid = (lower + upper) / 2
                val prob = simulateHorizon(base, mid, request.monthlyContribution)

                iterations += IterationStep(
                    index = step,
                    candidate = mid.toDouble(),
                    measuredProbability = prob,
                    lowerBound = lower.toDouble(),
                    upperBound = upper.toDouble(),
                )

                if (prob >= base.targetSuccessProbability) {
                    upper = mid
                    bestCandidate = mid
                    bestProbability = prob
                } else {
                    lower = mid
                }
                step++
            }

            terminationReason = if (upper - lower <= 1) {
                TerminationReason.CONVERGED
            } else {
                TerminationReason.MAX_ITERATIONS
            }
        }

        val verification = if (terminationReason != TerminationReason.INFEASIBLE_UPPER_BOUND) {
            monteCarloEngine.run(
                buildMonteCarloParameters(
                    base = base,
                    horizonMonths = bestCandidate,
                    monthlyContribution = request.monthlyContribution,
                    numSimulations = base.verificationSimulations,
                )
            )
        } else null

        return OptimizationResult(
            type = OptimizationType.OPTIMAL_HORIZON,
            feasible = terminationReason != TerminationReason.INFEASIBLE_UPPER_BOUND,
            optimalValue = bestCandidate.toDouble(),
            achievedProbability = verification?.successProbability ?: bestProbability,
            targetProbability = base.targetSuccessProbability,
            iterations = iterations,
            verification = verification,
            executionTimeMs = executionTime,
            terminationReason = terminationReason,
        )
    }

    private fun simulateHorizon(
        base: BaseConfig,
        horizonMonths: Int,
        contribution: Double,
    ): Double {
        val params = buildMonteCarloParameters(
            base = base,
            horizonMonths = horizonMonths,
            monthlyContribution = contribution,
            numSimulations = base.simulationsPerStep,
        )
        return monteCarloEngine.run(params).successProbability
    }

    // -----------------------------------------------------------------
    // Helper compartilhado
    // -----------------------------------------------------------------

    /**
     * Constroi os parametros do Monte Carlo, mantendo a SEED FIXA da
     * configuracao da otimizacao para garantir monotonicidade.
     */
    private fun buildMonteCarloParameters(
        base: BaseConfig,
        horizonMonths: Int,
        monthlyContribution: Double,
        numSimulations: Int,
    ): MonteCarloParameters = MonteCarloParameters(
        initialCapital = base.initialCapital,
        monthlyContribution = monthlyContribution,
        expectedReturnAnnual = base.expectedReturnAnnual,
        volatilityAnnual = base.volatilityAnnual,
        horizonMonths = horizonMonths,
        targetAmount = base.targetAmount,
        unemploymentProbAnnual = base.unemploymentProbAnnual,
        unemploymentDurationMonths = base.unemploymentDurationMonths,
        inflationAnnual = base.inflationAnnual,
        numSimulations = numSimulations,
        seed = base.seed,
    )
}
