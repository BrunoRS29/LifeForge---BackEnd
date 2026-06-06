package com.lifeforge.engine.montecarlo

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.Locale
import kotlin.math.pow

/**
 * Gera os artefatos de analise do motor exigidos pelos criterios do TCC e,
 * de quebra, ASSERTA que o motor se comporta como a teoria preve:
 *
 *  - 12.3: analise de sensibilidade dos parametros (one-at-a-time)
 *  - 12.2: comparacao Monte Carlo x projecao deterministica (formula 6.1)
 *
 * Os relatorios markdown sao escritos em `build/reports/analysis/` e foram
 * versionados em `docs/` a partir desta execucao. Rodar `gradlew test`
 * regenera os relatorios.
 */
class EngineAnalysisTest : StringSpec({

    val engine = MonteCarloEngine()
    val outDir = File("build/reports/analysis").apply { mkdirs() }

    fun base(over: MonteCarloParameters.() -> MonteCarloParameters = { this }): MonteCarloParameters =
        MonteCarloParameters(
            initialCapital = 50_000.0,
            monthlyContribution = 2_000.0,
            expectedReturnAnnual = 0.08,
            volatilityAnnual = 0.15,
            horizonMonths = 240,
            targetAmount = 1_000_000.0,
            inflationAnnual = 0.04,
            numSimulations = 20_000,
            seed = 42L,
        ).over()

    fun money(v: Double) = "R\$ " + String.format(Locale.US, "%,.0f", v)
    fun pct(v: Double) = String.format(Locale.US, "%.1f%%", v * 100.0)

    "analise de sensibilidade dos parametros (TCC 12.3)" {
        val sb = StringBuilder()
        sb.appendLine("# Análise de sensibilidade — Motor de Monte Carlo")
        sb.appendLine()
        sb.appendLine(
            "> Artefato gerado por `EngineAnalysisTest`. **Baseline**: capital R\$ 50.000, " +
                "aporte R\$ 2.000/mês, retorno 8% a.a., volatilidade 15% a.a., inflação 4% a.a., " +
                "horizonte 240 meses (20 anos), meta R\$ 1.000.000, 20.000 simulações, seed fixa (42)."
        )
        sb.appendLine()
        sb.appendLine(
            "Metodologia *one-at-a-time*: cada tabela varia UM parâmetro e mantém os demais no " +
                "baseline. Métrica principal: probabilidade de atingir a meta."
        )
        sb.appendLine()

        fun sweep(
            titulo: String,
            valores: List<Double>,
            rotulo: (Double) -> String,
            build: (Double) -> MonteCarloParameters,
        ): List<Double> {
            sb.appendLine("## $titulo")
            sb.appendLine()
            sb.appendLine("| $titulo | P(sucesso) | Mediana | P10 | P90 |")
            sb.appendLine("|---|---|---|---|---|")
            val probs = valores.map { v ->
                val r = engine.run(build(v))
                sb.appendLine(
                    "| ${rotulo(v)} | ${pct(r.successProbability)} | ${money(r.median)} | " +
                        "${money(r.percentiles.getValue(10.0))} | ${money(r.percentiles.getValue(90.0))} |"
                )
                r.successProbability
            }
            sb.appendLine()
            return probs
        }

        val ret = sweep(
            "Retorno esperado (a.a.)", listOf(0.04, 0.06, 0.08, 0.10, 0.12),
            { "${(it * 100).toInt()}%" },
        ) { base { copy(expectedReturnAnnual = it) } }

        val vol = sweep(
            "Volatilidade (a.a.)", listOf(0.05, 0.10, 0.15, 0.20, 0.30),
            { "${(it * 100).toInt()}%" },
        ) { base { copy(volatilityAnnual = it) } }

        val ap = sweep(
            "Aporte mensal", listOf(1_000.0, 1_500.0, 2_000.0, 2_500.0, 3_000.0),
            { "R\$ ${it.toInt()}" },
        ) { base { copy(monthlyContribution = it) } }

        val des = sweep(
            "Prob. desemprego (a.a.)", listOf(0.0, 0.05, 0.10, 0.20),
            { "${(it * 100).toInt()}%" },
        ) { base { copy(unemploymentProbAnnual = it) } }

        sb.appendLine("## Leitura")
        sb.appendLine()
        sb.appendLine("- **Retorno** e **aporte** elevam a probabilidade de sucesso (relação direta).")
        sb.appendLine(
            "- **Volatilidade** e **desemprego** reduzem a probabilidade (relação inversa); a " +
                "volatilidade ainda alarga a faixa P10–P90, ou seja, aumenta a incerteza."
        )
        sb.appendLine("- No baseline, o parâmetro de maior alavancagem é o **aporte mensal**.")

        File(outDir, "analise-sensibilidade.md").writeText(sb.toString())

        // Assercoes de sanidade: o motor segue a teoria financeira.
        ret.first() shouldBeLessThan ret.last()     // retorno 4% < 12%
        ap.first() shouldBeLessThan ap.last()        // aporte 1000 < 3000
        des.first() shouldBeGreaterThan des.last()   // 0% desemprego > 20%
        vol.first() shouldBeGreaterThan vol.last()   // vol baixa favorece meta acima da mediana
    }

    "comparacao Monte Carlo x projecao deterministica (TCC 12.2)" {
        fun deterministic(p: MonteCarloParameters): Double {
            val r = (1.0 + p.expectedReturnAnnual).pow(1.0 / 12.0) - 1.0
            val n = p.horizonMonths.toDouble()
            val growth = (1.0 + r).pow(n)
            return p.initialCapital * growth + p.monthlyContribution * (growth - 1.0) / r
        }

        val sb = StringBuilder()
        sb.appendLine("# Comparação: projeção determinística × Monte Carlo")
        sb.appendLine()
        sb.appendLine(
            "> Artefato gerado por `EngineAnalysisTest`. A projeção determinística usa a fórmula " +
                "de juros compostos com aportes (Seção 6.1): P(t) = P₀·(1+r)ᵗ + A·[((1+r)ᵗ − 1)/r]. " +
                "O Monte Carlo (20.000 cenários, mesmo baseline da análise de sensibilidade) " +
                "adiciona a camada estocástica."
        )
        sb.appendLine()
        sb.appendLine(
            "| Cenário | Determinístico | MC — média | MC — mediana | MC — P10 | MC — P90 | P(sucesso) |"
        )
        sb.appendLine("|---|---|---|---|---|---|---|")

        for (v in listOf(0.0, 0.10, 0.15, 0.25)) {
            val p = base { copy(volatilityAnnual = v) }
            val det = deterministic(p)
            val mc = engine.run(p)
            sb.appendLine(
                "| ${(v * 100).toInt()}% vol | ${money(det)} | ${money(mc.mean)} | " +
                    "${money(mc.median)} | ${money(mc.percentiles.getValue(10.0))} | " +
                    "${money(mc.percentiles.getValue(90.0))} | ${pct(mc.successProbability)} |"
            )
        }
        sb.appendLine()
        sb.appendLine("## Leitura")
        sb.appendLine()
        sb.appendLine(
            "- **Sem volatilidade**, o Monte Carlo converge para o determinístico — validação " +
                "de que o motor implementa corretamente a fórmula 6.1."
        )
        sb.appendLine(
            "- **Com volatilidade**, a *média* do MC permanece próxima do determinístico, mas a " +
                "*mediana* cai (assimetria / volatility drag) e abre-se a faixa P10–P90: a incerteza " +
                "que a projeção determinística simplesmente ignora."
        )
        sb.appendLine(
            "- O valor agregado do Monte Carlo é **quantificar risco** (P10, P90, probabilidade " +
                "de sucesso), e não entregar um único número pontual — núcleo da hipótese do TCC."
        )

        File(outDir, "comparacao-montecarlo-deterministico.md").writeText(sb.toString())

        // Validacao 1: sem volatilidade, a media do MC == projecao deterministica.
        val detV0 = deterministic(base { copy(volatilityAnnual = 0.0) })
        val mcV0 = engine.run(base { copy(volatilityAnnual = 0.0) })
        mcV0.mean shouldBe (detV0 plusOrMinus detV0 * 0.005)

        // Validacao 2: com volatilidade, o deterministico cai dentro da faixa do MC.
        val p = base()
        val mc = engine.run(p)
        val det = deterministic(p)
        det shouldBeGreaterThan mc.percentiles.getValue(5.0)
        det shouldBeLessThan mc.percentiles.getValue(95.0)
    }
})
