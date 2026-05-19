package com.lifeforge.data.repository

import com.lifeforge.data.tables.Simulations
import com.lifeforge.domain.model.Simulation
import com.lifeforge.domain.repository.SimulationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * Implementacao do [SimulationRepository] usando Exposed + PostgreSQL.
 *
 * Usa [newSuspendedTransaction] em [Dispatchers.IO] para nao bloquear o
 * event loop do servidor durante operacoes de banco.
 */
class SimulationRepositoryImpl : SimulationRepository {

    override suspend fun create(simulation: Simulation): Simulation =
        newSuspendedTransaction(Dispatchers.IO) {
            val generatedId = Simulations.insert {
                it[goalId] = simulation.goalId
                it[parameters] = simulation.parameters
                it[result] = simulation.result
                it[createdAt] = simulation.createdAt
            } get Simulations.id

            simulation.copy(id = generatedId.value)
        }

    override suspend fun findById(id: Long): Simulation? =
        newSuspendedTransaction(Dispatchers.IO) {
            Simulations.selectAll()
                .where { Simulations.id eq id }
                .singleOrNull()
                ?.toSimulation()
        }

    override suspend fun findByGoalId(goalId: Long): List<Simulation> =
        newSuspendedTransaction(Dispatchers.IO) {
            Simulations.selectAll()
                .where { Simulations.goalId eq goalId }
                .orderBy(Simulations.createdAt, SortOrder.DESC)
                .map { it.toSimulation() }
        }

    override suspend fun deleteById(id: Long): Boolean =
        newSuspendedTransaction(Dispatchers.IO) {
            Simulations.deleteWhere { Simulations.id eq id } > 0
        }

    private fun ResultRow.toSimulation() = Simulation(
        id = this[Simulations.id].value,
        goalId = this[Simulations.goalId].value,
        parameters = this[Simulations.parameters],
        result = this[Simulations.result],
        createdAt = this[Simulations.createdAt],
    )
}
