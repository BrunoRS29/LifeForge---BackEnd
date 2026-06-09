package com.lifeforge.plugins

import com.lifeforge.dto.ErrorResponse
import io.ktor.http.*
import io.ktor.serialization.JsonConvertException
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.seconds

fun Application.configureHTTP() {

    install(DefaultHeaders) {
        header("X-API-Version", "v1")
    }

    install(CallLogging) {
        level = Level.INFO
    }

    install(CORS) {
        // Em producao, defina CORS_ALLOWED_HOSTS (lista separada por virgula,
        // ex.: "app.lifeforge.com,admin.lifeforge.com"). Sem a variavel, libera
        // qualquer origem - conveniente apenas para desenvolvimento local.
        val allowedHosts = System.getenv("CORS_ALLOWED_HOSTS")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (allowedHosts.isNullOrEmpty()) {
            anyHost()
        } else {
            allowedHosts.forEach { host -> allowHost(host, schemes = listOf("https", "http")) }
        }
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
    }

    // Rate limiting nomeado "auth": protege login/registro contra forca bruta.
    // Aplicado seletivamente nas rotas de auth (ver plugins/Routing.kt).
    install(RateLimit) {
        register(RateLimitName("auth")) {
            rateLimiter(limit = 20, refillPeriod = 60.seconds)
        }
    }

    install(StatusPages) {

        exception<Throwable> { call: ApplicationCall, cause: Throwable ->
            // Loga o stack trace completo (diagnostico) mas NAO o vaza ao
            // cliente: a resposta carrega apenas uma mensagem generica.
            call.application.log.error("Unhandled exception (500)", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "Erro interno do servidor")
            )
        }
    }
}