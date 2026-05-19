package com.lifeforge.domain.repository

import com.lifeforge.domain.model.Simulation

/**
 * Contrato de persistencia para simulacoes.
 *
 * Mora em [com.lifeforge.domain.repository] (camada de dominio) seguindo Clean
 * Architecture: a implementacao concreta com Exposed fica em [com.lifeforge.data].
 */
interface SimulationRepository {
    suspend fun create(simulation: Simulation): Simulation
    suspend fun findById(id: Long): Simulation?
    suspend fun findByGoalId(goalId: Long): List<Simulation>
    suspend fun deleteById(id: Long): Boolean
}
