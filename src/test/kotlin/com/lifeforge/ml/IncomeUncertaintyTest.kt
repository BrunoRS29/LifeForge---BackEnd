package com.lifeforge.ml

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.sqrt

/**
 * Incerteza da renda -> variacao relativa do aporte (simulacao calibrada).
 * Regressao: um historico CLT com 13o salario e dividendos virava 66% a.a. de
 * volatilidade da carteira inteira.
 */
class IncomeUncertaintyTest : StringSpec({

    "desvio da renda dividido pelo aporte" {
        // Conta de demonstracao: residuo ~R$ 2.069/mes, aporte de R$ 3.931,80.
        IncomeUncertainty.contributionVariationMonthly(2069.0, 3931.8) shouldBe ((2069.0 / 3931.8) plusOrMinus 1e-12)
    }

    "escala anual que o motor recebe volta ao mensal ao dividir por raiz de 12" {
        val annual = IncomeUncertainty.contributionVariationAnnual(500.0, 3500.0)
        (annual / sqrt(12.0)) shouldBe ((500.0 / 3500.0) plusOrMinus 1e-12)
    }

    "limitado a 100% ao mes para o truncamento em zero nao inflar o aporte medio" {
        IncomeUncertainty.contributionVariationMonthly(5000.0, 300.0) shouldBe 1.0
    }

    "sem aporte ou sem incerteza, o aporte nao varia" {
        IncomeUncertainty.contributionVariationMonthly(500.0, 0.0) shouldBe 0.0
        IncomeUncertainty.contributionVariationMonthly(0.0, 3500.0) shouldBe 0.0
    }

    "volatilidade relativa anual da base de referencia vira desvio em R$ por mes" {
        // CLT: 5% a.a. sobre R$ 6.000 -> 0,05 / raiz(12) * 6000 ~ R$ 86,60/mes.
        IncomeUncertainty.incomeStdFromRelativeAnnual(0.05, 6000.0) shouldBe (86.6025 plusOrMinus 1e-3)
    }
})
