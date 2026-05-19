package com.lifeforge.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.config.ApplicationConfig
import java.util.Date

/**
 * Servico responsavel por:
 *  - Gerar tokens JWT assinados com HMAC256 a partir do segredo configurado
 *  - Construir o JWTVerifier usado pelo plugin de autenticacao do Ktor
 *
 * Os parametros (secret, issuer, audience, expiracao) vem do application.conf,
 * permitindo override por variaveis de ambiente em producao.
 */
class JwtService(config: ApplicationConfig) {

    private val secret: String = config.property("jwt.secret").getString()
    private val issuer: String = config.property("jwt.issuer").getString()
    val audience: String = config.property("jwt.audience").getString()
    val realm: String = config.property("jwt.realm").getString()
    private val expirationMs: Long = config.property("jwt.expirationMs").getString().toLong()

    val verifier: JWTVerifier = JWT
        .require(Algorithm.HMAC256(secret))
        .withAudience(audience)
        .withIssuer(issuer)
        .build()

    /**
     * Gera um token JWT contendo o id e o email do usuario nas claims.
     */
    fun generateToken(userId: Long, email: String): String =
        JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim("userId", userId)
            .withClaim("email", email)
            .withExpiresAt(Date(System.currentTimeMillis() + expirationMs))
            .sign(Algorithm.HMAC256(secret))
}
