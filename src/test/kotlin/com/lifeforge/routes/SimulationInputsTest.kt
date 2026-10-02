package com.lifeforge.routes

import com.lifeforge.engine.montecarlo.MonteCarloParameters
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/**
 * Premissas devolvidas com cada simulacao (comparacao de estrategias): lidas
 * do JSON gravado pela rota classica e pela calibrada.
 */
class SimulationInputsTest : StringSpec({

    "premissas da rota classica: request gravado sem os campos com valor padrao" {
        val stored = Json.parseToJsonElement(
            """{"goalId":"7","initialCapital":50000.0,"monthlyContribution":2000.0,
               "expectedReturnAnnual":0.08,"volatilityAnnual":0.15,"horizonMonths":240,
               "targetAmount":1000000.0,"inflationAnnual":0.04,"seed":42}"""
        )

        val inputs = stored.toStoredInputsOrNull().shouldNotBeNull()

        inputs.monthlyContribution shouldBe 2000.0
        inputs.horizonMonths shouldBe 240
        inputs.unemploymentProbAnnual shouldBe 0.0
        inputs.numSimulations shouldBe 10_000
        inputs.calibrated shouldBe false
    }

    "premissas da rota calibrada sao identificadas pelas referencias das predicoes" {
        val stored = Json.parseToJsonElement(
            """{"initialCapital":10000.0,"monthlyContribution":1234.5,"expectedReturnAnnual":0.11,
               "volatilityAnnual":0.1,"horizonMonths":120,"targetAmount":500000.0,
               "unemploymentProbAnnual":0.08,"unemploymentDurationMonths":6,"inflationAnnual":0.045,
               "numSimulations":10000,"seed":1,"incomePredictionId":null,"expensePredictionId":3,
               "contributionSource":"PREDICTIONS"}"""
        )

        val inputs = stored.toStoredInputsOrNull().shouldNotBeNull()

        inputs.calibrated shouldBe true
        inputs.monthlyContribution shouldBe 1234.5
        inputs.unemploymentProbAnnual shouldBe 0.08
    }

    "registro sem as chaves minimas nao quebra a listagem" {
        Json.parseToJsonElement("""{"foo":1}""").toStoredInputsOrNull().shouldBeNull()
    }

    "premissas da rodada refletem os parametros efetivos do motor" {
        val params = MonteCarloParameters(
            initialCapital = 1.0, monthlyContribution = 2.0, expectedReturnAnnual = 0.1,
            volatilityAnnual = 0.2, horizonMonths = 12, targetAmount = 100.0, seed = 1L,
            incomeVolatilityAnnual = 0.05,
        )

        val inputs = params.toInputsDto(calibrated = true)

        inputs.incomeVolatilityAnnual shouldBe 0.05
        inputs.calibrated shouldBe true
    }
})
