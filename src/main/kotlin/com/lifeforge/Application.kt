package com.lifeforge

import com.lifeforge.config.AppContainer
import com.lifeforge.config.DatabaseFactory
import com.lifeforge.plugins.configureHTTP
import com.lifeforge.plugins.configureRouting
import com.lifeforge.plugins.configureSecurity
import com.lifeforge.plugins.configureSerialization
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain

/**
 * Entry point do servidor Ktor.
 * O `EngineMain.main` carrega application.conf e chama Application.module().
 */
fun main(args: Array<String>) = EngineMain.main(args)

fun Application.module() {
    // 1. Inicializa banco (HikariCP + Exposed + cria tabelas se nao existem)
    DatabaseFactory.init(environment.config)

    // 2. Container manual de dependencias
    val container = AppContainer(environment.config)

    // 3. Plugins do Ktor
    configureSerialization()
    configureSecurity(container.jwtService)
    configureHTTP()
    configureRouting(container)
}
