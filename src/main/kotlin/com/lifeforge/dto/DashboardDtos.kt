package com.lifeforge.dto

import kotlinx.serialization.Serializable

/**
 * Visao consolidada do painel (GET /api/v1/dashboard): agrega receitas,
 * despesas, ativos e metas do usuario num unico payload - paridade com a
 * Secao 10 da proposta. Valores financeiros como String (mesma convencao dos
 * demais DTOs); savingsRate em pontos percentuais.
 */
@Serializable
data class DashboardResponse(
    val totalAssets: String,
    val monthlyIncome: String,
    val monthlyExpenses: String,
    val savingsRate: String,
    val goalsCount: Int,
    val totalGoalTarget: String,
)
