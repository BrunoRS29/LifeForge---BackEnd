package com.lifeforge.dto

import kotlinx.serialization.Serializable

/**
 * DTOs da base de estatisticas de referencia (GET /api/v1/reference-data).
 * Expoe as premissas de calibracao (inflacao, retornos por perfil, risco de
 * desemprego por vinculo, etc.) para o app e para inspecao/transparencia.
 */

@Serializable
data class RiskProfileStatsDto(
    val expectedReturnAnnual: Double,
    val volatilityAnnual: Double,
)

@Serializable
data class EmploymentStatsDto(
    val unemploymentProbAnnual: Double,
    val incomeVolatilityAnnual: Double,
)

@Serializable
data class ReferenceDataResponse(
    val inflationAnnualMean: Double,
    val inflationAnnualStdDev: Double,
    val salaryGrowthAnnualMean: Double,
    val salaryGrowthAnnualStdDev: Double,
    val selicAnnual: Double,
    val riskFreeAnnual: Double,
    val unemploymentDurationMonths: Int,
    val defaultUnemploymentProbAnnual: Double,
    val unexpectedExpenseAnnualFrequency: Double,
    val unexpectedExpenseMeanFractionOfIncome: Double,
    val lifeExpectancyYears: Int,
    val byRiskProfile: Map<String, RiskProfileStatsDto>,
    val byEmploymentType: Map<String, EmploymentStatsDto>,
)
