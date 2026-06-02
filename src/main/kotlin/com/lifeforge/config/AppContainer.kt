package com.lifeforge.config

import com.lifeforge.data.repository.AssetRepositoryImpl
import com.lifeforge.data.repository.ExpenseRepositoryImpl
import com.lifeforge.data.repository.ExpenseScheduleRepositoryImpl
import com.lifeforge.data.repository.GoalRepositoryImpl
import com.lifeforge.data.repository.IncomeRepositoryImpl
import com.lifeforge.data.repository.IncomeScheduleRepositoryImpl
import com.lifeforge.data.repository.PredictionRepositoryImpl
import com.lifeforge.data.repository.SimulationRepositoryImpl
import com.lifeforge.data.repository.UserRepositoryImpl
import com.lifeforge.domain.repository.AssetRepository
import com.lifeforge.domain.repository.ExpenseRepository
import com.lifeforge.domain.repository.ExpenseScheduleRepository
import com.lifeforge.domain.repository.GoalRepository
import com.lifeforge.domain.repository.IncomeRepository
import com.lifeforge.domain.repository.IncomeScheduleRepository
import com.lifeforge.domain.repository.PredictionRepository
import com.lifeforge.domain.repository.SimulationRepository
import com.lifeforge.domain.repository.UserRepository
import com.lifeforge.engine.montecarlo.MonteCarloEngine
import com.lifeforge.engine.optimization.OptimizationEngine
import com.lifeforge.engine.optimization.RebalancingAdvisor
import com.lifeforge.ml.MlClient
import com.lifeforge.ml.MlClientConfig
import com.lifeforge.ml.MlPredictionService
import com.lifeforge.security.JwtService
import io.ktor.server.config.ApplicationConfig

/**
 * Container simples de DI.
 *
 * Sprint 5: adicionei MlClient, MlPredictionService e PredictionRepository.
 * MlClient implementa AutoCloseable - o ciclo de vida e gerenciado em
 * [com.lifeforge.module] via stop hook.
 */
class AppContainer(config: ApplicationConfig) : AutoCloseable {

    val jwtService: JwtService = JwtService(config)

    // ----- Repositorios CRUD existentes (Sprints anteriores) -----
    val userRepository: UserRepository = UserRepositoryImpl()
    val goalRepository: GoalRepository = GoalRepositoryImpl()
    val incomeRepository: IncomeRepository = IncomeRepositoryImpl()
    val expenseRepository: ExpenseRepository = ExpenseRepositoryImpl()
    val assetRepository: AssetRepository = AssetRepositoryImpl()
    val simulationRepository: SimulationRepository = SimulationRepositoryImpl()

    // ----- Schedules recorrentes (Sprint 6): geram Incomes/Expenses -----
    val incomeScheduleRepository: IncomeScheduleRepository =
        IncomeScheduleRepositoryImpl(incomeRepository)
    val expenseScheduleRepository: ExpenseScheduleRepository =
        ExpenseScheduleRepositoryImpl(expenseRepository)

    // ----- Engines (Sprints 2 e 3) -----
    val monteCarloEngine: MonteCarloEngine = MonteCarloEngine()
    val optimizationEngine: OptimizationEngine = OptimizationEngine(monteCarloEngine)
    val rebalancingAdvisor: RebalancingAdvisor = RebalancingAdvisor()

    // ----- ML / Predicoes (Sprint 5) -----
    val predictionRepository: PredictionRepository = PredictionRepositoryImpl()

    val mlClient: MlClient = MlClient(MlClientConfig.fromAppConfig(config))

    val mlPredictionService: MlPredictionService = MlPredictionService(
        mlClient = mlClient,
        incomeRepository = incomeRepository,
        expenseRepository = expenseRepository,
        predictionRepository = predictionRepository,
    )

    /** Liberado no stop hook da aplicacao (ver Application.kt). */
    override fun close() {
        mlClient.close()
    }
}
