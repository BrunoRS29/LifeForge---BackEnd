package com.lifeforge.plugins

import com.lifeforge.config.AppContainer
import com.lifeforge.routes.apiDocsRoutes
import com.lifeforge.routes.assetRoutes
import com.lifeforge.routes.authRoutes
import com.lifeforge.routes.expenseRoutes
import com.lifeforge.routes.goalRoutes
import com.lifeforge.routes.incomeRoutes
import com.lifeforge.routes.optimizationRoutes
import com.lifeforge.routes.predictionRoutes
import com.lifeforge.routes.simulationCalibratedRoutes
import com.lifeforge.routes.simulationRoutes
import com.lifeforge.routes.userRoutes
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Plugin de roteamento.
 *
 * Sprint 5: adicionei [predictionRoutes] (POST /predictions/) e
 * [simulationCalibratedRoutes] (POST /simulation/run-calibrated). Tambem
 * estendi o /health para reportar status do microsservico ML.
 */
fun Application.configureRouting(container: AppContainer) {
    routing {
        // Healthcheck publico - agora consulta o ML tambem
        get("/health") {
            // Consulta paralela para nao serializar latencia desnecessariamente
            val mlOk = coroutineScope {
                async { container.mlClient.health() }
            }
            call.respond(
                mapOf(
                    "status" to "ok",
                    "service" to "lifeforge-backend",
                    "ml" to if (mlOk.await()) "ok" else "down",
                )
            )
        }

        // Documentacao OpenAPI/Swagger (publico): GET /docs e /openapi.yaml
        apiDocsRoutes()

        // Auth (publico)
        authRoutes(container.userRepository, container.jwtService)

        // CRUD existentes (Sprint 1)
        userRoutes(container.userRepository)
        goalRoutes(container.goalRepository)
        incomeRoutes(container.incomeRepository, container.incomeScheduleRepository)
        expenseRoutes(container.expenseRepository, container.expenseScheduleRepository)
        assetRoutes(container.assetRepository)

        // Simulacao - rota classica (Sprint 2) e calibrada (Sprint 5)
        simulationRoutes(
            simulationRepository = container.simulationRepository,
            goalRepository = container.goalRepository,
            engine = container.monteCarloEngine,
        )
        simulationCalibratedRoutes(
            simulationRepository = container.simulationRepository,
            goalRepository = container.goalRepository,
            engine = container.monteCarloEngine,
            predictionService = container.mlPredictionService,
        )

        // Otimizacao (Sprint 3)
        optimizationRoutes(
            goalRepository = container.goalRepository,
            optimizationEngine = container.optimizationEngine,
            rebalancingAdvisor = container.rebalancingAdvisor,
        )

        // Predicoes (Sprint 5)
        predictionRoutes(
            predictionService = container.mlPredictionService,
            predictionRepository = container.predictionRepository,
        )
    }
}
