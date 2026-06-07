package com.lifeforge.routes

import com.lifeforge.dto.ChildCostBracketDto
import com.lifeforge.dto.EmploymentStatsDto
import com.lifeforge.dto.ReferenceDataResponse
import com.lifeforge.dto.RiskProfileStatsDto
import com.lifeforge.engine.statistics.ReferenceData
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Base de estatisticas de referencia (calibracao das simulacoes).
 *
 *   GET /api/v1/reference-data  -> premissas de longo prazo (publico)
 *
 * Publico de proposito: e util ja no onboarding e da transparencia sobre as
 * premissas usadas nas projecoes/Monte Carlo.
 */
fun Route.referenceRoutes() {
    get("/api/v1/reference-data") {
        call.respond(ReferenceData.toResponse())
    }
}

private fun ReferenceData.toResponse(): ReferenceDataResponse = ReferenceDataResponse(
    inflationAnnualMean = inflation.mean,
    inflationAnnualStdDev = inflation.stdDev,
    salaryGrowthAnnualMean = salaryGrowth.mean,
    salaryGrowthAnnualStdDev = salaryGrowth.stdDev,
    selicAnnual = selicAnnual,
    riskFreeAnnual = riskFreeAnnual,
    unemploymentDurationMonths = unemploymentDurationMonths,
    defaultUnemploymentProbAnnual = DEFAULT_UNEMPLOYMENT_PROB_ANNUAL,
    unexpectedExpenseAnnualFrequency = unexpectedExpenseAnnualFrequency,
    unexpectedExpenseMeanFractionOfIncome = unexpectedExpenseMeanFractionOfIncome,
    lifeExpectancyYears = lifeExpectancyYears,
    vehicleDepreciationAnnual = vehicleDepreciationAnnual,
    realEstateAppreciationAnnual = realEstateAppreciationAnnual,
    safeWithdrawalRate = safeWithdrawalRate,
    byRiskProfile = byRiskProfile.entries.associate { (profile, stats) ->
        profile.name to RiskProfileStatsDto(stats.expectedReturnAnnual, stats.volatilityAnnual)
    },
    byEmploymentType = byEmploymentType.mapValues { (_, stats) ->
        EmploymentStatsDto(stats.unemploymentProbAnnual, stats.incomeVolatilityAnnual)
    },
    childCostByAge = childCostByAge.map { ChildCostBracketDto(it.ageMaxInclusive, it.monthlyCost) },
)
