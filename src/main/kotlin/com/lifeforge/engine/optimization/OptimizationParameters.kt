package com.lifeforge.engine.optimization

/**
 * Parametros de entrada do [OptimizationEngine].
 *
 * Modelado como sealed interface porque temos dois modos distintos de
 * otimizacao com inputs estruturalmente diferentes:
 *
 *   - [Contribution]: fixa horizonte, descobre aporte ideal
 *   - [Horizon]: fixa aporte, descobre prazo minimo
 *
 * O [BaseConfig] embutido carrega os parametros estocasticos comuns a ambos
 * (capital, retorno, volatilidade, eventos de risco) alem das configuracoes
 * do otimizador (numero de simulacoes por passo, tolerancia, seed).
 */
sealed interface OptimizationRequest {
    val base: BaseConfig

    /**
     * Otimiza o aporte mensal minimo para atingir [BaseConfig.targetAmount]
     * com probabilidade >= [BaseConfig.targetSuccessProbability], dado um
     * horizonte fixo.
     *
     * @param horizonMonths horizonte fixo da otimizacao
     * @param maxContribution limite superior da busca; se nulo, calculado
     *        analiticamente a partir da formula de juros compostos
     */
    data class Contribution(
        override val base: BaseConfig,
        val horizonMonths: Int,
        val maxContribution: Double? = null,
    ) : OptimizationRequest {
        init {
            require(horizonMonths > 0) { "horizonMonths deve ser > 0" }
            if (maxContribution != null) {
                require(maxContribution > 0.0) { "maxContribution deve ser > 0" }
            }
        }
    }

    /**
     * Otimiza o horizonte minimo (em meses) para atingir
     * [BaseConfig.targetAmount] com probabilidade >=
     * [BaseConfig.targetSuccessProbability], dado um aporte fixo.
     *
     * @param monthlyContribution aporte mensal fixo
     * @param maxHorizonMonths teto da busca, default 600 meses (50 anos)
     */
    data class Horizon(
        override val base: BaseConfig,
        val monthlyContribution: Double,
        val maxHorizonMonths: Int = 600,
    ) : OptimizationRequest {
        init {
            require(monthlyContribution >= 0.0) { "monthlyContribution deve ser >= 0" }
            require(maxHorizonMonths > 0) { "maxHorizonMonths deve ser > 0" }
        }
    }
}

/**
 * Parametros compartilhados pelos dois modos de otimizacao.
 *
 * @param initialCapital patrimonio inicial
 * @param expectedReturnAnnual retorno medio anual da carteira
 * @param volatilityAnnual desvio padrao anual dos retornos
 * @param targetAmount meta a ser atingida
 * @param targetSuccessProbability probabilidade minima desejada de atingir a
 *        meta, em [0, 1] (default: 0.80, conforme TCC Secao 5.4)
 * @param unemploymentProbAnnual probabilidade anual de desemprego
 * @param unemploymentDurationMonths duracao tipica do desemprego em meses
 * @param inflationAnnual inflacao anual (afeta valor real, nao a busca)
 * @param simulationsPerStep numero de simulacoes em cada passo da busca
 *        binaria. Menor = mais rapido mas mais ruidoso. Default 2_000 da
 *        precisao de ~1pp na probabilidade.
 * @param verificationSimulations numero de simulacoes na rodada final de
 *        verificacao (apos convergencia). Default 10_000 conforme TCC.
 * @param maxIterations numero maximo de iteracoes da busca binaria.
 *        log2(10_000_000) ~ 23, entao 30 e folga generosa.
 * @param contributionTolerance criterio de parada para busca em aporte
 *        (em moeda corrente). Default R$ 1.00.
 * @param seed semente fixa usada em TODAS as simulacoes da otimizacao.
 *        Critico: reutilizar a seed entre passos elimina ruido amostral
 *        e garante monotonicidade da funcao success_prob(candidato).
 */
data class BaseConfig(
    val initialCapital: Double,
    val expectedReturnAnnual: Double,
    val volatilityAnnual: Double,
    val targetAmount: Double,
    val targetSuccessProbability: Double = 0.80,
    val unemploymentProbAnnual: Double = 0.0,
    val unemploymentDurationMonths: Int = 6,
    val inflationAnnual: Double = 0.0,
    val simulationsPerStep: Int = 2_000,
    val verificationSimulations: Int = 10_000,
    val maxIterations: Int = 30,
    val contributionTolerance: Double = 1.0,
    val seed: Long = System.currentTimeMillis(),
) {
    init {
        require(initialCapital >= 0.0) { "initialCapital deve ser >= 0" }
        require(volatilityAnnual >= 0.0) { "volatilityAnnual deve ser >= 0" }
        require(targetAmount > 0.0) { "targetAmount deve ser > 0" }
        require(targetSuccessProbability in 0.0..1.0) {
            "targetSuccessProbability deve estar em [0, 1]"
        }
        require(unemploymentProbAnnual in 0.0..1.0) {
            "unemploymentProbAnnual deve estar em [0, 1]"
        }
        require(simulationsPerStep > 0) { "simulationsPerStep deve ser > 0" }
        require(verificationSimulations > 0) { "verificationSimulations deve ser > 0" }
        require(maxIterations > 0) { "maxIterations deve ser > 0" }
        require(contributionTolerance > 0.0) { "contributionTolerance deve ser > 0" }
    }
}
