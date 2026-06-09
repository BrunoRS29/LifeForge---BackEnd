package com.lifeforge.routes

import com.lifeforge.domain.model.RiskProfile
import com.lifeforge.dto.RunCalibratedSimulationRequest
import com.lifeforge.engine.statistics.ReferenceData
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Testa a resolucao das premissas da simulacao calibrada: quando o app OMITE
 * retorno/volatilidade/desemprego/inflacao (null), os valores vem da base de
 * referencia calibrada ao perfil (presetFor); quando o app ENVIA, o valor do
 * app prevalece. E o cerne da "simulacao de 1 toque".
 */
class CalibratedDefaultsTest : StringSpec({

    fun request(
        expectedReturnAnnual: Double? = null,
        volatilityAnnual: Double? = null,
        unemploymentProbAnnual: Double? = null,
        unemploymentDurationMonths: Int? = null,
        inflationAnnual: Double? = null,
    ) = RunCalibratedSimulationRequest(
        goalId = "1",
        initialCapital = 10_000.0,
        expectedReturnAnnual = expectedReturnAnnual,
        volatilityAnnual = volatilityAnnual,
        horizonMonths = 120,
        targetAmount = 300_000.0,
        unemploymentProbAnnual = unemploymentProbAnnual,
        unemploymentDurationMonths = unemploymentDurationMonths,
        inflationAnnual = inflationAnnual,
    )

    "premissas omitidas usam o preset do perfil (arrojado + CLT)" {
        val preset = ReferenceData.presetFor(RiskProfile.AGGRESSIVE, "CLT")
        val params = request().toBaseParameters(preset, seed = 1L)

        params.expectedReturnAnnual shouldBe preset.expectedReturnAnnual        // 0.13
        params.volatilityAnnual shouldBe preset.volatilityAnnual                // 0.18
        params.unemploymentProbAnnual shouldBe preset.unemploymentProbAnnual    // 0.08 (CLT)
        params.unemploymentDurationMonths shouldBe preset.unemploymentDurationMonths
        params.inflationAnnual shouldBe preset.inflationAnnual                  // 0.045
        params.seed shouldBe 1L
    }

    "valores enviados pelo app tem prioridade sobre o preset" {
        val preset = ReferenceData.presetFor(RiskProfile.CONSERVATIVE, "CIVIL_SERVANT")
        val params = request(
            expectedReturnAnnual = 0.20,
            volatilityAnnual = 0.25,
            unemploymentProbAnnual = 0.50,
            unemploymentDurationMonths = 3,
            inflationAnnual = 0.07,
        ).toBaseParameters(preset, seed = 2L)

        params.expectedReturnAnnual shouldBe 0.20
        params.volatilityAnnual shouldBe 0.25
        params.unemploymentProbAnnual shouldBe 0.50
        params.unemploymentDurationMonths shouldBe 3
        params.inflationAnnual shouldBe 0.07
    }

    "resolucao parcial: so o que falta vem do preset" {
        val preset = ReferenceData.presetFor(RiskProfile.MODERATE, null)
        // App manda so o retorno; o resto vem do preset (vinculo desconhecido).
        val params = request(expectedReturnAnnual = 0.15).toBaseParameters(preset, seed = 3L)

        params.expectedReturnAnnual shouldBe 0.15
        params.volatilityAnnual shouldBe preset.volatilityAnnual
        params.unemploymentProbAnnual shouldBe ReferenceData.DEFAULT_UNEMPLOYMENT_PROB_ANNUAL
    }

    "parametros do choque de despesa sao repassados para a engine" {
        val preset = ReferenceData.presetFor(RiskProfile.MODERATE, "CLT")
        val params = request().toBaseParameters(
            preset = preset,
            seed = 1L,
            unexpectedExpenseAnnualFrequency = 1.5,
            unexpectedExpenseMeanAmount = 2_500.0,
        )

        params.unexpectedExpenseAnnualFrequency shouldBe 1.5
        params.unexpectedExpenseMeanAmount shouldBe 2_500.0
    }

    "choque desativado por padrao quando o helper nao recebe os parametros" {
        val preset = ReferenceData.presetFor(RiskProfile.MODERATE, "CLT")
        val params = request().toBaseParameters(preset, seed = 1L)

        params.unexpectedExpenseAnnualFrequency shouldBe 0.0
        params.unexpectedExpenseMeanAmount shouldBe 0.0
    }

    // ----- Contrato de validacao da rota run-calibrated -----

    "validate aceita um request valido (premissas nulas usam preset)" {
        validate(request()) shouldBe null
    }

    "validate rejeita targetAmount <= 0" {
        validate(request().copy(targetAmount = 0.0)) shouldNotBe null
    }

    "validate rejeita horizonMonths <= 0" {
        validate(request().copy(horizonMonths = 0)) shouldNotBe null
    }

    "validate rejeita volatilidade negativa quando informada" {
        validate(request(volatilityAnnual = -0.1)) shouldNotBe null
    }

    "validate rejeita prob de desemprego fora de [0,1]" {
        validate(request(unemploymentProbAnnual = 1.5)) shouldNotBe null
    }

    "validate rejeita incomeHorizonMonths fora de [1,60]" {
        validate(request().copy(incomeHorizonMonths = 0)) shouldNotBe null
    }
})
