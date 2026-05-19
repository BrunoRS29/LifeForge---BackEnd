package com.lifeforge.engine.optimization

import com.lifeforge.domain.model.AssetType
import com.lifeforge.domain.model.RiskProfile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Testes do [RebalancingAdvisor].
 *
 * Foco em duas familias de propriedades:
 *
 *   INVARIANTES ESTRUTURAIS
 *   - pesos somam 1.0
 *   - cada peso esta em [0, 1]
 *   - todas as classes da ancora aparecem
 *   - riskScore esta em [0, 1]
 *
 *   DIRECOES ESPERADAS (monotonicidade da heuristica)
 *   - mais tempo -> mais risco
 *   - mais progresso -> menos risco
 *   - perfil agressivo > moderado > conservador (ceteris paribus)
 *   - retorno e volatilidade da carteira sobem juntos com o riskScore
 */
class RebalancingAdvisorTest : StringSpec({

    val advisor = RebalancingAdvisor()

    // ---------- Helpers ----------

    fun standardCall(
        profile: RiskProfile = RiskProfile.MODERATE,
        currentCapital: Double = 50_000.0,
        targetAmount: Double = 500_000.0,
        monthsToGoal: Int = 240,
    ) = advisor.recommend(profile, currentCapital, targetAmount, monthsToGoal)

    // ----------------------------------------------------------------
    // INVARIANTES ESTRUTURAIS
    // ----------------------------------------------------------------

    "pesos somam 1.0 dentro de tolerancia de ponto flutuante" {
        val rec = standardCall()
        rec.weights.values.sum() shouldBe (1.0 plusOrMinus 1e-9)
    }

    "cada peso individual esta em [0, 1]" {
        val rec = standardCall()
        rec.weights.values.forEach { it.shouldBeBetween(0.0, 1.0, 0.0) }
    }

    "riskScore esta em [0, 1]" {
        val rec = standardCall()
        rec.riskScore.shouldBeBetween(0.0, 1.0, 0.0)
    }

    "alocacao retorna ao menos as 3 classes principais (RF, acoes, FII)" {
        val rec = standardCall()
        rec.weights.keys.shouldContainAll(
            listOf(AssetType.FIXED_INCOME, AssetType.STOCKS, AssetType.REAL_ESTATE_FUND)
        )
    }

    "rationale contem o nome do perfil em letras minusculas" {
        val rec = standardCall(profile = RiskProfile.AGGRESSIVE)
        rec.rationale.shouldContain("aggressive")
    }

    // ----------------------------------------------------------------
    // DIRECOES ESPERADAS — PERFIL DE RISCO
    // ----------------------------------------------------------------

    "perfil agressivo tem riskScore maior que moderado, que tem maior que conservador" {
        // Mesmas condicoes de progresso e horizonte
        val conservative = standardCall(profile = RiskProfile.CONSERVATIVE)
        val moderate     = standardCall(profile = RiskProfile.MODERATE)
        val aggressive   = standardCall(profile = RiskProfile.AGGRESSIVE)

        moderate.riskScore shouldBeGreaterThan conservative.riskScore
        aggressive.riskScore shouldBeGreaterThan moderate.riskScore
    }

    "perfil agressivo aloca mais em acoes que perfil conservador (ceteris paribus)" {
        val conservative = standardCall(profile = RiskProfile.CONSERVATIVE)
        val aggressive   = standardCall(profile = RiskProfile.AGGRESSIVE)

        val stocksConservative = conservative.weights[AssetType.STOCKS] ?: 0.0
        val stocksAggressive   = aggressive.weights[AssetType.STOCKS] ?: 0.0

        stocksAggressive shouldBeGreaterThan stocksConservative
    }

    "perfil conservador aloca mais em renda fixa que perfil agressivo" {
        val conservative = standardCall(profile = RiskProfile.CONSERVATIVE)
        val aggressive   = standardCall(profile = RiskProfile.AGGRESSIVE)

        val rfConservative = conservative.weights[AssetType.FIXED_INCOME] ?: 0.0
        val rfAggressive   = aggressive.weights[AssetType.FIXED_INCOME] ?: 0.0

        rfConservative shouldBeGreaterThan rfAggressive
    }

    // ----------------------------------------------------------------
    // DIRECOES ESPERADAS — HORIZONTE
    // ----------------------------------------------------------------

    "horizonte mais longo aumenta o riskScore (ceteris paribus)" {
        val short = advisor.recommend(RiskProfile.MODERATE, 50_000.0, 500_000.0, 24)
        val long  = advisor.recommend(RiskProfile.MODERATE, 50_000.0, 500_000.0, 360)

        long.riskScore shouldBeGreaterThan short.riskScore
    }

    "horizonte curto reduz alocacao em acoes" {
        val short = advisor.recommend(RiskProfile.MODERATE, 50_000.0, 500_000.0, 24)
        val long  = advisor.recommend(RiskProfile.MODERATE, 50_000.0, 500_000.0, 360)

        val stocksShort = short.weights[AssetType.STOCKS] ?: 0.0
        val stocksLong  = long.weights[AssetType.STOCKS] ?: 0.0

        stocksShort shouldBeLessThan stocksLong
    }

    // ----------------------------------------------------------------
    // DIRECOES ESPERADAS — PROGRESSO
    // ----------------------------------------------------------------

    "progresso alto reduz o riskScore (preserva ganhos perto da meta)" {
        // Mesmo perfil e horizonte; muda apenas o capital atual
        val far  = advisor.recommend(RiskProfile.MODERATE, 50_000.0,  500_000.0, 240)
        val near = advisor.recommend(RiskProfile.MODERATE, 450_000.0, 500_000.0, 240)

        near.riskScore shouldBeLessThan far.riskScore
    }

    "progresso alto aumenta a fatia de renda fixa" {
        val far  = advisor.recommend(RiskProfile.MODERATE, 50_000.0,  500_000.0, 240)
        val near = advisor.recommend(RiskProfile.MODERATE, 450_000.0, 500_000.0, 240)

        val rfFar  = far.weights[AssetType.FIXED_INCOME] ?: 0.0
        val rfNear = near.weights[AssetType.FIXED_INCOME] ?: 0.0

        rfNear shouldBeGreaterThan rfFar
    }

    // ----------------------------------------------------------------
    // CONSISTENCIA DE METRICAS DE PORTFOLIO
    // ----------------------------------------------------------------

    "carteira agressiva tem retorno e volatilidade esperados maiores que conservadora" {
        val conservative = standardCall(profile = RiskProfile.CONSERVATIVE)
        val aggressive   = standardCall(profile = RiskProfile.AGGRESSIVE)

        aggressive.expectedReturnAnnual shouldBeGreaterThan conservative.expectedReturnAnnual
        aggressive.volatilityAnnual shouldBeGreaterThan conservative.volatilityAnnual
    }

    "tuning custom altera o resultado (timeWeight em zero zera o efeito do tempo)" {
        val zeroTime = RebalancingAdvisor.Tuning(timeWeight = 0.0, progressWeight = 0.40)

        val short = advisor.recommend(RiskProfile.MODERATE, 50_000.0, 500_000.0, 24,  zeroTime)
        val long  = advisor.recommend(RiskProfile.MODERATE, 50_000.0, 500_000.0, 360, zeroTime)

        // Com timeWeight=0, mudar so o horizonte nao deve mudar o riskScore
        short.riskScore shouldBe (long.riskScore plusOrMinus 1e-9)
    }

    // ----------------------------------------------------------------
    // VALIDACAO DE INPUTS
    // ----------------------------------------------------------------

    "currentCapital negativo e rejeitado" {
        try {
            advisor.recommend(RiskProfile.MODERATE, -1.0, 500_000.0, 240)
            error("deveria ter lancado")
        } catch (e: IllegalArgumentException) {
            e.message?.contains("currentCapital") shouldBe true
        }
    }

    "targetAmount nao-positivo e rejeitado" {
        try {
            advisor.recommend(RiskProfile.MODERATE, 50_000.0, 0.0, 240)
            error("deveria ter lancado")
        } catch (e: IllegalArgumentException) {
            e.message?.contains("targetAmount") shouldBe true
        }
    }

    "monthsToGoal nao-positivo e rejeitado" {
        try {
            advisor.recommend(RiskProfile.MODERATE, 50_000.0, 500_000.0, 0)
            error("deveria ter lancado")
        } catch (e: IllegalArgumentException) {
            e.message?.contains("monthsToGoal") shouldBe true
        }
    }
})
