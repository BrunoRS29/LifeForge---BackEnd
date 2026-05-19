package com.lifeforge.engine.optimization

import com.lifeforge.domain.model.AssetType
import com.lifeforge.domain.model.RiskProfile
import com.lifeforge.engine.optimization.RiskProfileCalibration.AssetClassCalibration
import kotlin.math.ln
import kotlin.math.min

/**
 * Heuristica de rebalanceamento de carteira (Tarefa 3.2 do TCC).
 *
 * Sugere uma alocacao entre classes de ativos (renda fixa, acoes, FIIs)
 * combinando tres dimensoes:
 *
 *   1. PERFIL DE RISCO do usuario (conservador / moderado / agressivo)
 *      - define a alocacao "ancora" e o score de risco base
 *
 *   2. PROGRESSO em direcao a meta (capital atual / meta)
 *      - quando progresso -> 1, migra para conservador (preserva ganhos)
 *
 *   3. HORIZONTE restante (meses ate a meta)
 *      - mais tempo -> tolera mais risco (volatilidade dilui no longo prazo)
 *      - pouco tempo -> reduz risco (nao da tempo de recuperar drawdowns)
 *
 * Modelo (educacional, transparencia para o TCC)
 * ----------------------------------------------
 *
 *   risco_base = PROFILE_BASE_RISK_SCORE[perfil]
 *   fator_tempo = ln(meses + 1) / ln(361)         # normalizado ~30 anos = 1.0
 *   risco_efetivo = risco_base
 *                 + alpha_tempo * (fator_tempo - 0.5)
 *                 - alpha_progresso * (progresso - 0.5)
 *   risco_efetivo = clamp(risco_efetivo, 0.0, 1.0)
 *
 * Com risco_efetivo em [0, 1], interpolamos entre uma carteira "ancora
 * conservadora" (risco=0) e uma "ancora agressiva" (risco=1) para obter
 * os pesos finais.
 *
 * Limitacoes (documentadas no TCC):
 *   - Nao considera correlacao entre classes
 *   - Nao otimiza por fronteira eficiente de Markowitz
 *   - Nao considera tributacao ou custos de transacao
 *   - Heuristica determinista, nao baseada em ML (Sprint 5 amplia isso)
 */
class RebalancingAdvisor(
    private val calibrations: Map<AssetType, AssetClassCalibration> =
        RiskProfileCalibration.ASSET_CLASS_DEFAULTS,
) {

    /**
     * Configuracao dos coeficientes da heuristica. Exposta para tuning
     * em testes ou ajuste fino.
     */
    data class Tuning(
        /** Peso do fator tempo (default: ate +/- 0.30 ao redor do risco base) */
        val timeWeight: Double = 0.30,
        /** Peso do fator progresso (default: ate +/- 0.40 ao redor do risco base) */
        val progressWeight: Double = 0.40,
        /** Horizonte em meses que satura o fator tempo em 1.0 (default 30 anos) */
        val saturationMonths: Int = 360,
    )

    /**
     * Resultado do rebalanceamento.
     *
     * @param weights pesos por classe de ativo (somam 1.0)
     * @param expectedReturnAnnual retorno esperado anual da carteira
     * @param volatilityAnnual volatilidade anual da carteira (assume independencia)
     * @param riskScore score de risco efetivo usado na geracao [0, 1]
     * @param rationale explicacao textual da decisao (para a UI)
     */
    data class Recommendation(
        val weights: Map<AssetType, Double>,
        val expectedReturnAnnual: Double,
        val volatilityAnnual: Double,
        val riskScore: Double,
        val rationale: String,
    )

    /**
     * Gera a recomendacao de alocacao.
     *
     * @param riskProfile perfil declarado do usuario
     * @param currentCapital patrimonio atual (>= 0)
     * @param targetAmount meta a ser atingida (> 0)
     * @param monthsToGoal meses restantes ate a meta (> 0)
     */
    fun recommend(
        riskProfile: RiskProfile,
        currentCapital: Double,
        targetAmount: Double,
        monthsToGoal: Int,
        tuning: Tuning = Tuning(),
    ): Recommendation {
        require(currentCapital >= 0.0) { "currentCapital deve ser >= 0" }
        require(targetAmount > 0.0) { "targetAmount deve ser > 0" }
        require(monthsToGoal > 0) { "monthsToGoal deve ser > 0" }

        val baseRisk = RiskProfileCalibration.PROFILE_BASE_RISK_SCORE.getValue(riskProfile)

        // Progresso clampado: se ja ultrapassou a meta, conta como 1.0.
        val progress = min(currentCapital / targetAmount, 1.0)

        // Fator tempo logaritmico: cresce rapido nos primeiros anos e satura
        // em ~30 anos. Curva log captura melhor o "valor marginal do tempo"
        // do que uma escala linear.
        val timeFactor = (ln((monthsToGoal + 1).toDouble()) / ln((tuning.saturationMonths + 1).toDouble()))
            .coerceIn(0.0, 1.0)

        val riskScore = (
            baseRisk +
                tuning.timeWeight * (timeFactor - 0.5) -
                tuning.progressWeight * (progress - 0.5)
            ).coerceIn(0.0, 1.0)

        // Interpola entre as ancoras conservadora e agressiva pelo riskScore.
        val conservativeAnchor =
            RiskProfileCalibration.PROFILE_ANCHOR_ALLOCATIONS.getValue(RiskProfile.CONSERVATIVE).weights
        val aggressiveAnchor =
            RiskProfileCalibration.PROFILE_ANCHOR_ALLOCATIONS.getValue(RiskProfile.AGGRESSIVE).weights

        val allClasses = (conservativeAnchor.keys + aggressiveAnchor.keys)
        val rawWeights = allClasses.associateWith { asset ->
            val w0 = conservativeAnchor[asset] ?: 0.0
            val w1 = aggressiveAnchor[asset] ?: 0.0
            (1.0 - riskScore) * w0 + riskScore * w1
        }

        // Renormaliza por seguranca (a interpolacao convexa entre dois mapas
        // que somam 1.0 ja deveria somar 1.0, mas ponto-flutuante pode
        // introduzir erros de ~1e-16).
        val total = rawWeights.values.sum()
        val normalized = rawWeights.mapValues { (_, w) -> w / total }

        val portfolio = RiskProfileCalibration.computePortfolioStats(normalized, calibrations)

        return Recommendation(
            weights = normalized,
            expectedReturnAnnual = portfolio.expectedReturnAnnual,
            volatilityAnnual = portfolio.volatilityAnnual,
            riskScore = riskScore,
            rationale = buildRationale(
                riskProfile, baseRisk, progress, timeFactor, riskScore, monthsToGoal,
            ),
        )
    }

    /**
     * Texto curto para a UI explicando "por que esta alocacao". Mantem o
     * tom direto e concreto — sem jargao desnecessario.
     */
    private fun buildRationale(
        profile: RiskProfile,
        baseRisk: Double,
        progress: Double,
        timeFactor: Double,
        riskScore: Double,
        monthsToGoal: Int,
    ): String {
        val timeHint = when {
            monthsToGoal >= 240 -> "horizonte longo permite assumir mais risco"
            monthsToGoal >= 60  -> "horizonte medio mantem equilibrio"
            else                -> "horizonte curto exige preservacao"
        }
        val progressHint = when {
            progress >= 0.80 -> "perto da meta, foco em preservar"
            progress >= 0.40 -> "progresso intermediario"
            else             -> "longe da meta, busca crescimento"
        }
        val direction = when {
            riskScore > baseRisk + 0.05 -> "acima do perfil base"
            riskScore < baseRisk - 0.05 -> "abaixo do perfil base"
            else                        -> "alinhado ao perfil base"
        }
        return "Perfil ${profile.name.lowercase()} ($direction): $timeHint; $progressHint."
    }
}
