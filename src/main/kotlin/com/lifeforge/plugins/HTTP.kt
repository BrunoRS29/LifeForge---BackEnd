package com.lifeforge.plugins

import com.lifeforge.dto.ErrorResponse
import io.ktor.http.*
import io.ktor.serialization.JsonConvertException
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.slf4j.event.Level

fun Application.configureHTTP() {

    install(DefaultHeaders) {
        header("X-API-Version", "v1")
    }

    install(CallLogging) {
        level = Level.INFO
    }

    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
    }

    install(StatusPages) {

        exception<Throwable> { call: ApplicationCall, cause: Throwable ->
            // Loga o stack trace - sem isto os 500 ficam silenciosos e
            // impossiveis de diagnosticar (vide bug do run-calibrated).
            call.application.log.error("Unhandled exception (500)", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", cause.message ?: "Erro interno")
            )
        }
    }
}