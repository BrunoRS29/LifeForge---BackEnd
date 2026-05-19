package com.lifeforge.routes

import com.lifeforge.domain.model.RiskProfile
import com.lifeforge.domain.repository.UserRepository
import com.lifeforge.dto.ErrorResponse
import com.lifeforge.dto.UpdateRiskProfileRequest
import com.lifeforge.dto.UserDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route

/**
 * Endpoints relativos ao usuario autenticado.
 *
 *  GET   /api/v1/users/me                   -> retorna os dados do usuario atual
 *  PATCH /api/v1/users/me/risk-profile      -> atualiza apenas o perfil de risco
 */
fun Route.userRoutes(repository: UserRepository) {
    authenticate("auth-jwt") {
        route("/api/v1/users") {

            // GET /me
            get("/me") {
                val userId = call.userId()
                val user = repository.findById(userId)
                if (user == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("NOT_FOUND", "Usuario nao encontrado"),
                    )
                } else {
                    call.respond(user.toDto())
                }
            }

            // PATCH /me/risk-profile
            //
            // Atualiza somente o campo `riskProfile`. Endpoint dedicado em vez
            // de PATCH /me generico para tornar a intencao explicita e simples
            // de validar — outros campos (email, name) tem regras proprias
            // (verificacao, unicidade) que justificam endpoints separados se
            // forem implementados futuramente.
            patch("/me/risk-profile") {
                val userId = call.userId()
                val body = call.receive<UpdateRiskProfileRequest>()

                val newProfile = try {
                    RiskProfile.valueOf(body.riskProfile)
                } catch (e: IllegalArgumentException) {
                    return@patch call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            "VALIDATION",
                            "riskProfile invalido. Valores aceitos: ${RiskProfile.entries.joinToString()}",
                        ),
                    )
                }

                val updated = repository.updateRiskProfile(userId, newProfile)
                if (!updated) {
                    return@patch call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("NOT_FOUND", "Usuario nao encontrado"),
                    )
                }

                // Retorna o user atualizado para o cliente sincronizar o cache local.
                val user = repository.findById(userId)
                if (user != null) {
                    call.respond(user.toDto())
                } else {
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

private fun com.lifeforge.domain.model.User.toDto(): UserDto = UserDto(
    id = id,
    email = email,
    name = name,
    riskProfile = riskProfile.name,
    createdAt = createdAt.toString(),
)
