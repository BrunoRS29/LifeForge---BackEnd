package com.lifeforge.routes

import com.lifeforge.domain.model.RiskProfile
import com.lifeforge.domain.repository.UserRepository
import com.lifeforge.dto.AuthResponse
import com.lifeforge.dto.ErrorResponse
import com.lifeforge.dto.LoginRequest
import com.lifeforge.dto.RegisterRequest
import com.lifeforge.dto.UserDto
import com.lifeforge.security.JwtService
import com.lifeforge.security.PasswordHasher
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Rotas publicas de autenticacao:
 *  POST /api/v1/auth/register
 *  POST /api/v1/auth/login
 *
 * Ambas retornam um AuthResponse contendo o JWT e o usuario serializado.
 */
fun Route.authRoutes(
    userRepository: UserRepository,
    jwtService: JwtService
) {
    route("/api/v1/auth") {

        post("/register") {
            val req = call.receive<RegisterRequest>()
            val errors = validateRegister(req)
            if (errors.isNotEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION", errors.joinToString("; ")))
                return@post
            }
            val email = req.email.trim().lowercase()
            if (userRepository.findByEmail(email) != null) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("EMAIL_TAKEN", "Email ja cadastrado")
                )
                return@post
            }
            val riskProfile = req.riskProfile?.let { runCatching { RiskProfile.valueOf(it) }.getOrNull() }
                ?: RiskProfile.MODERATE

            val user = userRepository.create(
                email = email,
                name = req.name.trim(),
                passwordHash = PasswordHasher.hash(req.password),
                riskProfile = riskProfile
            )
            val token = jwtService.generateToken(user.id, user.email)
            call.respond(HttpStatusCode.Created, AuthResponse(token, user.toDto()))
        }

        post("/login") {
            val req = call.receive<LoginRequest>()
            if (req.email.isBlank() || req.password.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("VALIDATION", "Email e senha sao obrigatorios")
                )
                return@post
            }
            val email = req.email.trim().lowercase()
            val pair = userRepository.findPasswordHashByEmail(email)
            if (pair == null || !PasswordHasher.verify(req.password, pair.second)) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("INVALID_CREDENTIALS", "Email ou senha invalidos")
                )
                return@post
            }
            val (user, _) = pair
            val token = jwtService.generateToken(user.id, user.email)
            call.respond(HttpStatusCode.OK, AuthResponse(token, user.toDto()))
        }
    }
}

private fun validateRegister(req: RegisterRequest): List<String> = buildList {
    if (req.email.isBlank() || !req.email.contains("@")) add("Email invalido")
    if (req.name.isBlank()) add("Nome e obrigatorio")
    if (req.password.length < 8) add("Senha deve ter ao menos 8 caracteres")
}

private fun com.lifeforge.domain.model.User.toDto(): UserDto = UserDto(
    id = id,
    email = email,
    name = name,
    riskProfile = riskProfile.name,
    createdAt = createdAt.toString()
)
