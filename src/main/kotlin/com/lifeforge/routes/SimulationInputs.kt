package com.lifeforge.routes

import com.lifeforge.dto.SimulationInputsDto
import com.lifeforge.engine.montecarlo.MonteCarloParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Conversoes das premissas de uma simulacao para o contrato da API
 * ([SimulationInputsDto]), usadas pelas rotas classica e calibrada.
 */

private val lenientJson = Json { ignoreUnknownKeys = true }

/** Premissas efetivamente usadas pelo motor numa rodada. */
internal fun MonteCarloParameters.toInputsDto(calibrated: Boolean): SimulationInputsDto = SimulationInputsDto(
    initialCapital = initialCapital,
    monthlyContribution = monthlyContribution,
    expectedReturnAnnual = expectedReturnAnnual,
    volatilityAnnual = volatilityAnnual,
    horizonMonths = horizonMonths,
    targetAmount = targetAmount,
    unemploymentProbAnnual = unemploymentProbAnnual,
    unemploymentDurationMonths = unemploymentDurationMonths,
    inflationAnnual = inflationAnnual,
    unexpectedExpenseAnnualFrequency = unexpectedExpenseAnnualFrequency,
    unexpectedExpenseMeanAmount = unexpectedExpenseMeanAmount,
    incomeVolatilityAnnual = incomeVolatilityAnnual,
    numSimulations = numSimulations,
    calibrated = calibrated,
)

/**
 * Le as premissas gravadas na coluna `parameters` (jsonb). A rota classica
 * grava o proprio request e a calibrada um objeto com as mesmas chaves e as
 * referencias das predicoes - o que a identifica como calibrada. Devolve null
 * se o registro nao tiver as chaves minimas (dados antigos/corrompidos).
 */
internal fun JsonElement.toStoredInputsOrNull(): SimulationInputsDto? = runCatching {
    val calibrated = (this as? JsonObject)?.let { "incomePredictionId" in it || "contributionSource" in it } ?: false
    lenientJson.decodeFromJsonElement(SimulationInputsDto.serializer(), this).copy(calibrated = calibrated)
}.getOrNull()
