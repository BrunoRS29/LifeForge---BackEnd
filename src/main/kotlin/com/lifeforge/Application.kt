package com.lifeforge

import com.lifeforge.config.AppContainer
import com.lifeforge.config.DatabaseFactory
import com.lifeforge.plugins.configureHTTP
import com.lifeforge.plugins.configureRouting
import com.lifeforge.plugins.configureSecurity
import com.lifeforge.plugins.configureSerialization
import io.ktor.events.EventDefinition
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.netty.EngineMain

/**
 * Entry point do servidor Ktor.
 * O `EngineMain.main` carrega application.conf e chama Application.module().
 *
 * Sprint 5: registra stop hook para fechar o MlClient (libera conexoes HTTP).
 */
fun main(args: Array<String>) = EngineMain.main(args)

fun Application.module() {
    // 1. Inicializa banco (HikariCP + Exposed + cria tabelas se nao existem)
    DatabaseFactory.init(environment.config)

    // 2. Container manual de dependencias
    val container = AppContainer(environment.config)

    // 3. Stop hook - libera recursos do MlClient ao desligar o servidor.
    //    Caso contrario, em deploy com hot-reload acumularia conexoes TCP.
    monitor.subscribe(ApplicationStopped) { container.close() }

    // 4. Plugins do Ktor
    configureSerialization()
    configureSecurity(container.jwtService)
    configureHTTP()
    configureRouting(container)
}
