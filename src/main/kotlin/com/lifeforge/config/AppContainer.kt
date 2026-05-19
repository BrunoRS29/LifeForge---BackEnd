package com.lifeforge.config

import com.lifeforge.data.repository.AssetRepositoryImpl
import com.lifeforge.data.repository.ExpenseRepositoryImpl
import com.lifeforge.data.repository.GoalRepositoryImpl
import com.lifeforge.data.repository.IncomeRepositoryImpl
import com.lifeforge.data.repository.UserRepositoryImpl
import com.lifeforge.data.repository.SimulationRepositoryImpl
import com.lifeforge.domain.repository.AssetRepository
import com.lifeforge.domain.repository.ExpenseRepository
import com.lifeforge.domain.repository.GoalRepository
import com.lifeforge.domain.repository.IncomeRepository
import com.lifeforge.domain.repository.UserRepository
import com.lifeforge.domain.repository.SimulationRepository
import com.lifeforge.engine.montecarlo.MonteCarloEngine
import com.lifeforge.engine.optimization.OptimizationEngine
import com.lifeforge.engine.optimization.RebalancingAdvisor
import com.lifeforge.security.JwtService
import io.ktor.server.config.ApplicationConfig

/**
 * Container simples de dependencias.
 *
 * Para o backend Ktor, optei por DI manual no lugar de Koin: e suficiente
 * para a Sprint 1 e mantem o numero de bibliotecas baixo. O Android e que
 * usara Hilt (conforme especificacao do TCC).
 */
class AppContainer(config: ApplicationConfig) {
    val jwtService: JwtService = JwtService(config)

    val userRepository: UserRepository = UserRepositoryImpl()
    val goalRepository: GoalRepository = GoalRepositoryImpl()
    val incomeRepository: IncomeRepository = IncomeRepositoryImpl()
    val expenseRepository: ExpenseRepository = ExpenseRepositoryImpl()
    val assetRepository: AssetRepository = AssetRepositoryImpl()
    val simulationRepository: SimulationRepository = SimulationRepositoryImpl()
    val monteCarloEngine: MonteCarloEngine = MonteCarloEngine()
    val optimizationEngine: OptimizationEngine = OptimizationEngine(monteCarloEngine)
    val rebalancingAdvisor: RebalancingAdvisor = RebalancingAdvisor()
}
