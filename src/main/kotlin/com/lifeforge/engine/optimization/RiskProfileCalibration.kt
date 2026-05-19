package com.lifeforge.engine.optimization

import com.lifeforge.domain.model.AssetType
import com.lifeforge.domain.model.RiskProfile

/**
 * Distribuicoes de probabilidade calibradas para o motor de simulacao e
 * otimizacao.
 *
 * Estes valores sao defaults razoaveis baseados em historicos de longo prazo
 * do mercado brasileiro (CDI, Ibovespa, IFIX) e servem como pontos de partida
 * quando o usuario nao informa parametros customizados de cada classe de ativo.
 *
 * Os numeros NAO representam recomendacao de investimento, sao apenas
 * calibracoes para alimentar a Engine de Monte Carlo. A precisao melhora
 * quando o usuario refina manualmente (Sprint 4) ou quando o microservico
 * de IA preditiva (Sprint 5) recalibra com dados pessoais.
 *
 * Referencias para os valores default:
 *   - Renda fixa pos-fixada: media historica do CDI (2014-2024)
 *   - Acoes (Ibovespa total return): media + desvio-padrao 20 anos
 *   - FII (IFIX): media historica desde 2011
 *   - Cripto: aproximacao do BTC (alta volatilidade, retorno especulativo)
 *
 * Todos os valores sao expressos em base ANUAL.
 */
object RiskProfileCalibration {

    /**
     * Calibracao de uma classe de ativo: retorno esperado e volatilidade,
     * ambos anualizados.
     *
     * @param expectedReturnAnnual retorno medio anual (ex: 0.10 = 10% a.a.)
     * @param volatilityAnnual desvio padrao anual dos retornos (ex: 0.25 = 25%)
     */
    data class AssetClassCalibration(
        val expectedReturnAnnual: Double,
        val volatilityAnnual: Double,
    ) {
        init {
            require(volatilityAnnual >= 0.0) { "volatilityAnnual deve ser >= 0" }
        }
    }

    /**
     * Defaults por classe de ativo. Numeros conservadores para evitar viés
     * otimista em projecoes de longo prazo.
     */
    val ASSET_CLASS_DEFAULTS: Map<AssetType, AssetClassCalibration> = mapOf(
        AssetType.FIXED_INCOME       to AssetClassCalibration(0.105, 0.030),
        AssetType.STOCKS             to AssetClassCalibration(0.120, 0.250),
        AssetType.REAL_ESTATE_FUND   to AssetClassCalibration(0.100, 0.150),
        AssetType.REAL_ESTATE        to AssetClassCalibration(0.080, 0.120),
        AssetType.CRYPTO             to AssetClassCalibration(0.300, 0.800),
        AssetType.OTHER              to AssetClassCalibration(0.080, 0.150),
    )

    /**
     * Alocacao alvo por perfil de risco. Sao as "ancoras" usadas pelo
     * [RebalancingAdvisor]; o algoritmo ajusta em torno delas considerando
     * progresso e horizonte.
     *
     * Pesos somam 1.0 (validado em init).
     */
    data class TargetAllocation(
        val weights: Map<AssetType, Double>,
    ) {
        init {
            val total = weights.values.sum()
            require(kotlin.math.abs(total - 1.0) < 1e-6) {
                "Pesos devem somar 1.0, recebido: $total"
            }
            require(weights.values.all { it in 0.0..1.0 }) {
                "Cada peso deve estar em [0, 1]"
            }
        }
    }

    /**
     * Ancoras de alocacao por perfil. Modelo classico de tres camadas
     * (renda fixa, acoes, FIIs) que cobre a maioria dos investidores PF
     * brasileiros. Cripto fica de fora dos defaults por ser exotica.
     */
    val PROFILE_ANCHOR_ALLOCATIONS: Map<RiskProfile, TargetAllocation> = mapOf(
        RiskProfile.CONSERVATIVE to TargetAllocation(
            mapOf(
                AssetType.FIXED_INCOME     to 0.80,
                AssetType.REAL_ESTATE_FUND to 0.15,
                AssetType.STOCKS           to 0.05,
            )
        ),
        RiskProfile.MODERATE to TargetAllocation(
            mapOf(
                AssetType.FIXED_INCOME     to 0.50,
                AssetType.REAL_ESTATE_FUND to 0.20,
                AssetType.STOCKS           to 0.30,
            )
        ),
        RiskProfile.AGGRESSIVE to TargetAllocation(
            mapOf(
                AssetType.FIXED_INCOME     to 0.20,
                AssetType.REAL_ESTATE_FUND to 0.20,
                AssetType.STOCKS           to 0.60,
            )
        ),
    )

    /**
     * Score de risco "base" por perfil, em [0, 1].
     * Usado pelo [RebalancingAdvisor] como ponto de partida antes de aplicar
     * ajustes por progresso e horizonte.
     */
    val PROFILE_BASE_RISK_SCORE: Map<RiskProfile, Double> = mapOf(
        RiskProfile.CONSERVATIVE to 0.20,
        RiskProfile.MODERATE     to 0.50,
        RiskProfile.AGGRESSIVE   to 0.80,
    )

    /**
     * Retorno e volatilidade EQUIVALENTES de uma carteira, dada uma alocacao.
     *
     * Formulas:
     *   E[R_p]   = sum_i (w_i * mu_i)                       (linearidade da esperanca)
     *   Var[R_p] = sum_i (w_i^2 * sigma_i^2)                (assumindo independencia entre ativos)
     *   sigma_p  = sqrt(Var[R_p])
     *
     * IMPORTANTE: a formula de variancia ASSUME correlacao zero entre as
     * classes de ativo. Na pratica acoes e FIIs tem correlacao positiva
     * (~0.4-0.6) e ambos tem correlacao baixa-negativa com renda fixa. Para
     * o TCC essa simplificacao e aceitavel e esta documentada como limitacao
     * (Secao 3.3 do documento tecnico). Uma matriz de covariancia completa
     * seria implementada em trabalhos futuros.
     */
    fun computePortfolioStats(
        weights: Map<AssetType, Double>,
        calibrations: Map<AssetType, AssetClassCalibration> = ASSET_CLASS_DEFAULTS,
    ): AssetClassCalibration {
        val expectedReturn = weights.entries.sumOf { (asset, w) ->
            val cal = calibrations[asset]
                ?: error("Classe de ativo sem calibracao: $asset")
            w * cal.expectedReturnAnnual
        }
        val variance = weights.entries.sumOf { (asset, w) ->
            val cal = calibrations.getValue(asset)
            w * w * cal.volatilityAnnual * cal.volatilityAnnual
        }
        return AssetClassCalibration(
            expectedReturnAnnual = expectedReturn,
            volatilityAnnual = kotlin.math.sqrt(variance),
        )
    }
}
