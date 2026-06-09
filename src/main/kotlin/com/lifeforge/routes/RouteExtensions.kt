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

/**
 * Paginacao opcional via query params `limit` (1..500) e `offset` (>= 0). Sem
 * `limit`, devolve a lista inteira a partir de `offset`. O corte e aplicado
 * sobre a lista ja carregada - limita o tamanho da RESPOSTA; o push-down para
 * SQL (LIMIT/OFFSET) fica como evolucao para volumes muito grandes.
 */
fun <T> ApplicationCall.paginate(items: List<T>): List<T> {
    val limit = request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500)
    val offset = (request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
    val from = offset.coerceAtMost(items.size)
    val to = if (limit != null) (from + limit).coerceAtMost(items.size) else items.size
    return items.subList(from, to)
}
