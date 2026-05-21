package com.lifeforge.domain.repository

import com.lifeforge.domain.model.Prediction
import java.math.BigDecimal
import kotlinx.serialization.json.JsonElement

/**
 * Contrato de persistencia para predicoes de ML.
 *
 * Decisao: vive em arquivo proprio em vez de dentro de
 * [com.lifeforge.domain.repository.Repositories] pois e uma adicao da
 * Sprint 5 e mantemos sprints anteriores intactos por diff-friendliness.
 *
 * Cada predicao eh um SNAPSHOT do que o microsservico Python retornou
 * para um dado usuario num dado momento. Eh apend-only - nao expomos
 * update/delete nesta sprint (poderiam ser uteis para purgar predicoes
 * antigas em producao, mas nao integram o escopo academico).
 */
interface PredictionRepository {

    /**
     * Persiste uma nova predicao.
     *
     * @param userId dono da predicao (FK)
     * @param modelName INCOME_REGRESSION | EXPENSE_RANDOM_FOREST | PATRIMONY_ARIMA
     * @param input JSON com os parametros enviados ao microsservico
     * @param output JSON com a resposta do microsservico
     * @param errorMetric metrica de avaliacao (tipicamente MAE) - facilita
     *                    queries diretas no banco sem precisar parsear o JSON
     */
    suspend fun create(
        userId: Long,
        modelName: String,
        input: JsonElement,
        output: JsonElement,
        errorMetric: BigDecimal?,
    ): Prediction

    /** Lista predicoes de um usuario, mais recentes primeiro. */
    suspend fun findAllByUser(userId: Long, limit: Int = 50): List<Prediction>

    /** Recupera uma predicao especifica respeitando o tenant. */
    suspend fun findById(id: Long, userId: Long): Prediction?

    /**
     * Recupera a predicao mais recente de um usuario para um modelo.
     * Usado pela rota de simulacao calibrada que reaproveita predicoes
     * frescas (mais barato que treinar de novo a cada simulacao).
     */
    suspend fun findLatestByUserAndModel(userId: Long, modelName: String): Prediction?
}
