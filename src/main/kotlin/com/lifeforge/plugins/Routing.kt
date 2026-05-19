package com.lifeforge.plugins

import com.lifeforge.config.AppContainer
import com.lifeforge.routes.assetRoutes
import com.lifeforge.routes.authRoutes
import com.lifeforge.routes.expenseRoutes
import com.lifeforge.routes.goalRoutes
import com.lifeforge.routes.incomeRoutes
import com.lifeforge.routes.optimizationRoutes
import com.lifeforge.routes.userRoutes
import com.lifeforge.routes.simulationRoutes
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Plugin de roteamento. Reune todas as rotas da API em um unico ponto.
 */
fun Application.configureRouting(container: AppContainer) {
    routing {
        // Healthcheck publico para Docker/load balancer
        get("/health") {
            call.respond(mapOf("status" to "ok", "service" to "lifeforge-backend"))
        }

        // Auth (publico)
        authRoutes(container.userRepository, container.jwtService)

        // Rotas autenticadas
        userRoutes(container.userRepository)
        goalRoutes(container.goalRepository)
        incomeRoutes(container.incomeRepository)
        expenseRoutes(container.expenseRepository)
        assetRoutes(container.assetRepository)
        simulationRoutes(
            simulationRepository = container.simulationRepository,
            goalRepository = container.goalRepository,
            engine = container.monteCarloEngine,
        )
        optimizationRoutes(
            goalRepository = container.goalRepository,
            optimizationEngine = container.optimizationEngine,
            rebalancingAdvisor = container.rebalancingAdvisor,
        )
    }
}
