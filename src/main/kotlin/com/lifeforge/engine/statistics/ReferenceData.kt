package com.lifeforge.engine.statistics

import com.lifeforge.domain.model.RiskProfile

/**
 * Base de estatisticas de referencia (calibracao).
 *
 * O ponto central do projeto (proposta, Secao 6.2) e a Simulacao de Monte
 * Carlo com distribuicoes "calibradas com base em dados historicos e na
 * literatura". Para DEMOCRATIZAR o uso, o app NAO pede ao usuario que informe
 * retorno, volatilidade, inflacao ou risco de desemprego: esses valores vem
 * desta base, derivada de dados publicos brasileiros e da literatura, e podem
 * ser refinados pela IA (microsservico de predicao) com o historico do usuario.
 *
 * Todos os valores sao ANUAIS (fracoes: 0.045 = 4,5%), salvo indicacao.
 *
 * Fontes (ver docs/estatisticas-referencia.md):
 *  - Inflacao (IPCA) e SELIC: Banco Central do Brasil / IBGE.
 *  - Renda fixa: CDI/Tesouro (ANBIMA / Tesouro Direto).
 *  - Renda variavel: Ibovespa (B3), retorno e volatilidade de longo prazo.
 *  - Desemprego e expectativa de vida: IBGE (PNAD Continua / Tabuas de Vida).
 *  - Distribuicoes de Monte Carlo: GLASSERMAN, Monte Carlo Methods in
 *    Financial Engineering (2003); HULL, Options, Futures and Other Derivatives.
 *
 * NB: sao premissas-base de longo prazo, nao previsoes. Ficam centralizadas
 * aqui para serem auditaveis e versionaveis (boa pratica para o TCC).
 */
object ReferenceData {

    /** Media e desvio-padrao de uma variavel (para distribuicoes Normais). */
    data class Distribution(val mean: Double, val stdDev: Double)

    /** Retorno e volatilidade anuais tipicos de uma carteira por perfil. */
    data class RiskProfileStats(val expectedReturnAnnual: Double, val volatilityAnnual: Double)

    /** Estatisticas de risco de renda por tipo de vinculo de trabalho. */
    data class EmploymentStats(val unemploymentProbAnnual: Double, val incomeVolatilityAnnual: Double)

    /**
     * Conjunto de parametros prontos para alimentar a engine de Monte Carlo /
     * a projecao, escolhidos a partir do perfil de risco e do vinculo.
     */
    data class CalibrationPreset(
        val expectedReturnAnnual: Double,
        val volatilityAnnual: Double,
        val inflationAnnual: Double,
        val salaryGrowthAnnual: Double,
        val unemploymentProbAnnual: Double,
        val unemploymentDurationMonths: Int,
    )

    // ---- Economia ----
    /** IPCA de longo prazo (meta + tolerancia historica). */
    val inflation = Distribution(mean = 0.045, stdDev = 0.025)
    /** Taxa basica de juros (SELIC) de referencia. */
    val selicAnnual = 0.105
    /** Retorno livre de risco (CDI/Tesouro Selic). */
    val riskFreeAnnual = 0.10

    // ---- Carreira / renda ----
    /** Crescimento salarial nominal anual (inflacao + ganho real medio). */
    val salaryGrowth = Distribution(mean = 0.06, stdDev = 0.03)
    /** Duracao tipica de um periodo de desemprego (meses). */
    val unemploymentDurationMonths = 6

    // ---- Choques (despesas inesperadas) ----
    /** Frequencia anual de despesas inesperadas (lambda de Poisson). */
    val unexpectedExpenseAnnualFrequency = 1.5
    /** Magnitude media de cada despesa inesperada, em fracao da renda mensal. */
    val unexpectedExpenseMeanFractionOfIncome = 0.5

    // ---- Demografia ----
    /** Expectativa de vida ao nascer (IBGE), util para horizonte de aposentadoria. */
    val lifeExpectancyYears = 77

    /**
     * Retorno x volatilidade por perfil de risco (carteira-tipo). Valores
     * nominais de longo prazo: conservador ~ renda fixa; arrojado ~ maior
     * exposicao a renda variavel.
     */
    val byRiskProfile: Map<RiskProfile, RiskProfileStats> = mapOf(
        RiskProfile.CONSERVATIVE to RiskProfileStats(expectedReturnAnnual = 0.09, volatilityAnnual = 0.03),
        RiskProfile.MODERATE to RiskProfileStats(expectedReturnAnnual = 0.11, volatilityAnnual = 0.10),
        RiskProfile.AGGRESSIVE to RiskProfileStats(expectedReturnAnnual = 0.13, volatilityAnnual = 0.18),
    )

    /**
     * Risco de renda por vinculo. Chaves = nomes do enum `EmploymentType` do
     * app (CLT, PJ, ENTREPRENEUR, SELF_EMPLOYED, CIVIL_SERVANT). Informais e
     * autonomos tem maior probabilidade de interrupcao de renda; servidores, a
     * menor.
     */
    val byEmploymentType: Map<String, EmploymentStats> = mapOf(
        "CLT" to EmploymentStats(unemploymentProbAnnual = 0.08, incomeVolatilityAnnual = 0.05),
        "PJ" to EmploymentStats(unemploymentProbAnnual = 0.12, incomeVolatilityAnnual = 0.12),
        "ENTREPRENEUR" to EmploymentStats(unemploymentProbAnnual = 0.15, incomeVolatilityAnnual = 0.20),
        "SELF_EMPLOYED" to EmploymentStats(unemploymentProbAnnual = 0.15, incomeVolatilityAnnual = 0.18),
        "CIVIL_SERVANT" to EmploymentStats(unemploymentProbAnnual = 0.01, incomeVolatilityAnnual = 0.02),
    )

    /** Probabilidade anual de desemprego quando o vinculo e desconhecido. */
    const val DEFAULT_UNEMPLOYMENT_PROB_ANNUAL = 0.10

    /**
     * Monta um [CalibrationPreset] a partir do perfil de risco e (opcional) do
     * vinculo. Usa MODERATE quando o perfil e nulo e a probabilidade default de
     * desemprego quando o vinculo e desconhecido.
     */
    fun presetFor(riskProfile: RiskProfile?, employmentType: String? = null): CalibrationPreset {
        val rp = byRiskProfile[riskProfile ?: RiskProfile.MODERATE]
            ?: byRiskProfile.getValue(RiskProfile.MODERATE)
        val emp = employmentType?.let { byEmploymentType[it] }
        return CalibrationPreset(
            expectedReturnAnnual = rp.expectedReturnAnnual,
            volatilityAnnual = rp.volatilityAnnual,
            inflationAnnual = inflation.mean,
            salaryGrowthAnnual = salaryGrowth.mean,
            unemploymentProbAnnual = emp?.unemploymentProbAnnual ?: DEFAULT_UNEMPLOYMENT_PROB_ANNUAL,
            unemploymentDurationMonths = unemploymentDurationMonths,
        )
    }
}
