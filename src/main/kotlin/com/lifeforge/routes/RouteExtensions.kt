package com.lifeforge.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal

/**
 * Helpers compartilhados pelas rotas autenticadas.
 */

/**
 * Extrai o userId da claim do JWT.
 * Como o plugin de Authentication ja valida que userId existe, nunca sera nulo
 * dentro de um bloco `authenticate("auth-jwt")`.
 */
fun ApplicationCall.userId(): Long {
    val principal = principal<JWTPrincipal>()
        ?: error("Endpoint autenticado sem JWTPrincipal")
    return principal.payload.getClaim("userId").asLong()
}
