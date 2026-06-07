package com.lifeforge.engine.statistics

import com.lifeforge.domain.model.RiskProfile
import com.lifeforge.engine.montecarlo.MonteCarloEngine
import com.lifeforge.engine.montecarlo.MonteCarloParameters
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe

/**
 * Testes da base de estatisticas de referencia: faixas plausiveis,
 * monotonicidade por perfil/vinculo e, sobretudo, que um preset gera uma
 * simulacao de Monte Carlo valida (a base realmente calibra a engine).
 */
class ReferenceDataTest : StringSpec({

    "premissas economicas ficam em faixas plausiveis" {
        ReferenceData.inflation.mean shouldBeGreaterThan 0.0
        ReferenceData.inflation.mean shouldBeLessThan 0.20
        ReferenceData.salaryGrowth.mean shouldBeGreaterThan 0.0
        ReferenceData.lifeExpectancyYears shouldBeGreaterThan 60
    }

    "retorno e volatilidade crescem do conservador ao arrojado" {
        val c = ReferenceData.byRiskProfile.getValue(RiskProfile.CONSERVATIVE)
        val m = ReferenceData.byRiskProfile.getValue(RiskProfile.MODERATE)
        val a = ReferenceData.byRiskProfile.getValue(RiskProfile.AGGRESSIVE)
        c.expectedReturnAnnual shouldBeLessThan m.expectedReturnAnnual
        m.expectedReturnAnnual shouldBeLessThan a.expectedReturnAnnual
        c.volatilityAnnual shouldBeLessThan m.volatilityAnnual
        m.volatilityAnnual shouldBeLessThan a.volatilityAnnual
    }

    "risco de desemprego: servidor < CLT < autonomo" {
        val servidor = ReferenceData.byEmploymentType.getValue("CIVIL_SERVANT").unemploymentProbAnnual
        val clt = ReferenceData.byEmploymentType.getValue("CLT").unemploymentProbAnnual
        val autonomo = ReferenceData.byEmploymentType.getValue("SELF_EMPLOYED").unemploymentProbAnnual
        servidor shouldBeLessThan clt
        clt shouldBeLessThan autonomo
    }

    "preset usa MODERATE quando o perfil e nulo" {
        val preset = ReferenceData.presetFor(null)
        preset.expectedReturnAnnual shouldBe
            ReferenceData.byRiskProfile.getValue(RiskProfile.MODERATE).expectedReturnAnnual
        preset.unemploymentProbAnnual shouldBe ReferenceData.DEFAULT_UNEMPLOYMENT_PROB_ANNUAL
    }

    "preset por vinculo aplica a probabilidade de desemprego do vinculo" {
        val preset = ReferenceData.presetFor(RiskProfile.MODERATE, "CLT")
        preset.unemploymentProbAnnual shouldBe
            ReferenceData.byEmploymentType.getValue("CLT").unemploymentProbAnnual
    }

    "um preset gera uma simulacao de Monte Carlo valida" {
        val preset = ReferenceData.presetFor(RiskProfile.MODERATE, "CLT")
        val params = MonteCarloParameters(
            initialCapital = 10_000.0,
            monthlyContribution = 1_000.0,
            expectedReturnAnnual = preset.expectedReturnAnnual,
            volatilityAnnual = preset.volatilityAnnual,
            horizonMonths = 120,
            targetAmount = 300_000.0,
            unemploymentProbAnnual = preset.unemploymentProbAnnual,
            unemploymentDurationMonths = preset.unemploymentDurationMonths,
            inflationAnnual = preset.inflationAnnual,
            numSimulations = 2_000,
            seed = 42L,
        )
        val result = MonteCarloEngine().run(params)
        result.successProbability shouldBeGreaterThanOrEqualTo 0.0
        result.successProbability shouldBeLessThanOrEqualTo 1.0
        result.numSimulations shouldBe 2_000
    }
})
