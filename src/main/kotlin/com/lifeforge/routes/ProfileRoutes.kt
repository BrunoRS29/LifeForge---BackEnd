package com.lifeforge.routes

import com.lifeforge.domain.repository.UserProfileRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Perfil estendido do usuario autenticado — parametros opcionais que melhoram
 * as projecoes (dados pessoais, profissionais, moradia, tributacao, etc.).
 *
 *  GET /api/v1/profile  -> blob JSON salvo (ou {} se ainda vazio)
 *  PUT /api/v1/profile  -> substitui o perfil pelo corpo enviado
 *
 * O corpo e um blob livre (todos os campos sao opcionais); o contrato tipado
 * vive no app, por isso aqui recebemos/devolvemos [JsonElement] diretamente.
 */
fun Route.profileRoutes(repository: UserProfileRepository) {
    authenticate("auth-jwt") {
        route("/api/v1/profile") {

            get {
                val userId = call.userId()
                val data = repository.get(userId) ?: JsonObject(emptyMap())
                call.respond(data)
            }

            put {
                val userId = call.userId()
                val body = call.receive<JsonElement>()
                val saved = repository.upsert(userId, body)
                call.respond(HttpStatusCode.OK, saved)
            }
        }
    }
}
