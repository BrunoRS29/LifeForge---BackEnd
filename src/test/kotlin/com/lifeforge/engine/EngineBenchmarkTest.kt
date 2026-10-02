package com.lifeforge.engine

import com.lifeforge.engine.montecarlo.MonteCarloEngine
import com.lifeforge.engine.montecarlo.MonteCarloParameters
import com.lifeforge.engine.optimization.BaseConfig
import com.lifeforge.engine.optimization.OptimizationEngine
import com.lifeforge.engine.optimization.OptimizationRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlin.math.sqrt

/**
 * Benchmark dos tempos de processamento do motor - TCC, Secao 4.9.4 (Tabela 6)
 * e objetivo especifico (a): responder em menos de dois segundos.
 *
 * Metodologia: para cada configuracao, 5 execucoes de aquecimento (JIT) e
 * 30 execucoes medidas, reportando media e desvio-padrao de dois tempos:
 *  - motor: o campo `executionTimeMs`, medido pelo proprio motor em torno do
 *    laco de simulacao (o que o TCC cita);
 *  - total: a chamada completa (`run`/`findOptimalContribution`), incluindo a
 *    agregacao (ordenacao, percentis, histograma e bandas do fan chart).
 *
 * Cenario: o mesmo baseline da analise de sensibilidade (capital R$ 50.000,
 * aporte R$ 2.000/mes, retorno 8% a.a., volatilidade 15% a.a., inflacao 4%,
 * horizonte 240 meses, meta R$ 1.000.000, semente 42). A otimizacao usa os
 * padroes da API: 2.000 cenarios por passo da busca binaria e verificacao final
 * com 10.000 cenarios.
 *
 * Lento por natureza, fica FORA do `gradlew test`: rode com `gradlew benchmark`
 * (a tarefa liga a propriedade `lifeforge.benchmark` e desliga o JaCoCo, cuja
 * instrumentacao distorceria as medidas). O relatorio sai em
 * `build/reports/analysis/tempos-processamento.md`.
 */
class EngineBenchmarkTest : StringSpec({

    val enabled = System.getProperty("lifeforge.benchmark") == "true"
    val warmup = 5
    val runs = 30

    val baseline = MonteCarloParameters(
        initialCapital = 50_000.0,
        monthlyContribution = 2_000.0,
        expectedReturnAnnual = 0.08,
        volatilityAnnual = 0.15,
        horizonMonths = 240,
        targetAmount = 1_000_000.0,
        inflationAnnual = 0.04,
        numSimulations = 10_000,
        seed = 42L,
    )

    data class Stats(val mean: Double, val sd: Double)

    fun stats(xs: List<Double>): Stats {
        val mean = xs.average()
        val sd = sqrt(xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1))
        return Stats(mean, sd)
    }

    fun fmt(s: Stats) = String.format(Locale.US, "%.0f ± %.0f", s.mean, s.sd)

    "tempos de processamento do motor (Tabela 6)".config(enabled = enabled) {
        val engine = MonteCarloEngine()
        val optimizer = OptimizationEngine(engine)
        val rows = mutableListOf<String>()

        // Primeira chamada numa JVM recem-iniciada (sem JIT): o pior caso que um
        // usuario veria logo apos o servidor subir.
        val coldStart = System.nanoTime()
        val coldResult = engine.run(baseline)
        val coldTotalMs = (System.nanoTime() - coldStart) / 1e6

        for (n in listOf(10_000, 20_000, 50_000)) {
            val params = baseline.copy(numSimulations = n)
            repeat(warmup) { engine.run(params) }
            val motor = mutableListOf<Double>()
            val total = mutableListOf<Double>()
            repeat(runs) {
                val t0 = System.nanoTime()
                val result = engine.run(params)
                total += (System.nanoTime() - t0) / 1e6
                motor += result.executionTimeMs.toDouble()
            }
            val m = stats(motor)
            rows += "| Simulação de Monte Carlo | ${String.format(Locale.US, "%,d", n).replace(',', '.')} " +
                "iterações · 240 meses | ${fmt(m)} | ${fmt(stats(total))} |"
            if (n == 10_000) m.mean shouldBeLessThan 2_000.0 // objetivo (a): < 2 s
        }

        val request = OptimizationRequest.Contribution(
            base = BaseConfig(
                initialCapital = baseline.initialCapital,
                expectedReturnAnnual = baseline.expectedReturnAnnual,
                volatilityAnnual = baseline.volatilityAnnual,
                targetAmount = baseline.targetAmount,
                inflationAnnual = baseline.inflationAnnual,
                seed = 42L,
            ),
            horizonMonths = baseline.horizonMonths,
        )
        repeat(warmup) { optimizer.findOptimalContribution(request) }
        val motor = mutableListOf<Double>()
        val total = mutableListOf<Double>()
        var steps = 0
        repeat(runs) {
            val t0 = System.nanoTime()
            val result = optimizer.findOptimalContribution(request)
            total += (System.nanoTime() - t0) / 1e6
            motor += result.executionTimeMs.toDouble()
            steps = result.iterations.size
        }
        rows += "| Otimização de aporte (busca binária) | $steps avaliações de 2.000 cenários " +
            "+ verificação de 10.000 | ${fmt(stats(motor))} | ${fmt(stats(total))} |"

        val cpu = cpuName()
        val sb = StringBuilder()
        sb.appendLine("# Tempos de processamento do motor")
        sb.appendLine()
        sb.appendLine(
            "> Artefato gerado por `EngineBenchmarkTest` (`gradlew benchmark`) em ${LocalDate.now()}. " +
                "Média ± desvio-padrão de $runs execuções, após $warmup de aquecimento. " +
                "Cenário: baseline da análise de sensibilidade (semente 42)."
        )
        sb.appendLine()
        sb.appendLine("| Operação | Configuração | Motor — `executionTimeMs` (ms) | Chamada completa (ms) |")
        sb.appendLine("|---|---|---|---|")
        rows.forEach { sb.appendLine(it) }
        sb.appendLine()
        sb.appendLine(
            "Primeira execução numa JVM recém-iniciada (sem otimização JIT), 10.000 iterações: " +
                "motor ${coldResult.executionTimeMs} ms; chamada completa " +
                String.format(Locale.US, "%.0f", coldTotalMs) + " ms."
        )
        sb.appendLine()
        sb.appendLine("## Ambiente")
        sb.appendLine()
        sb.appendLine("- Processador: $cpu")
        sb.appendLine("- Núcleos lógicos disponíveis à JVM: ${Runtime.getRuntime().availableProcessors()}")
        sb.appendLine("- JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
        sb.appendLine("- Sistema: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
        sb.appendLine()
        sb.appendLine(
            "A coluna *motor* mede o laço de simulação (campo `executionTimeMs`); a *chamada completa* " +
                "inclui a agregação dos resultados (ordenação, percentis, histograma e bandas do fan chart). " +
                "Na otimização, *motor* é o tempo total da busca binária com a verificação final."
        )
        val outDir = File("build/reports/analysis").apply { mkdirs() }
        File(outDir, "tempos-processamento.md").writeText(sb.toString())
        println(sb)
    }
})

/**
 * Nome legivel do processador (melhor esforco): no Windows consulta o WMI via
 * PowerShell; no Linux le /proc/cpuinfo; senao usa PROCESSOR_IDENTIFIER.
 */
private fun cpuName(): String {
    val windows = runCatching {
        val process = ProcessBuilder(
            "powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Processor).Name",
        ).redirectErrorStream(true).start()
        process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotBlank() && process.exitValue() == 0 }
    }.getOrNull()
    val linux = runCatching {
        File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("model name") }
            ?.substringAfter(':')?.trim()
    }.getOrNull()
    return windows ?: linux ?: System.getenv("PROCESSOR_IDENTIFIER") ?: "nao identificado"
}
