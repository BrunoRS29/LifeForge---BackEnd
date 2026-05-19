package com.lifeforge.plugins

import com.lifeforge.security.JwtService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond

fun Application.configureSecurity(jwtService: JwtService) {
    install(Authentication) {
        jwt("auth-jwt") {
            realm = jwtService.realm
            verifier(jwtService.verifier)
            validate { credential ->
                if (credential.payload.audience.contains(jwtService.audience) &&
                    credential.payload.getClaim("userId").asLong() != null
                ) {
                    JWTPrincipal(credential.payload)
                } else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token invalido ou expirado"))
            }
        }
    }
}
