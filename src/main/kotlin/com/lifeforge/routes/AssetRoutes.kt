package com.lifeforge.routes

import com.lifeforge.domain.model.Asset
import com.lifeforge.domain.model.AssetType
import com.lifeforge.domain.repository.AssetRepository
import com.lifeforge.dto.AssetDto
import com.lifeforge.dto.AssetRequest
import com.lifeforge.dto.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.math.BigDecimal

/**
 * Endpoints de ativos. Diferente de rendas/despesas (que sao transacoes
 * pontuais), ativos sao posicoes patrimoniais que evoluem ao longo do
 * tempo, entao expomos PUT para atualizar valor/retorno/volatilidade.
 */
fun Route.assetRoutes(repository: AssetRepository) {
    authenticate("auth-jwt") {
        route("/api/v1/assets") {

            get {
                call.respond(repository.findAllByUser(call.userId()).map { it.toDto() })
            }

            post {
                val userId = call.userId()
                val req = call.receive<AssetRequest>()
                val parsed = parseAssetRequest(req) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION", "Dados do ativo invalidos"))
                    return@post
                }
                val asset = repository.create(
                    userId = userId,
                    name = parsed.name,
                    assetType = parsed.assetType,
                    currentValue = parsed.currentValue,
                    expectedReturn = parsed.expectedReturn,
                    volatility = parsed.volatility
                )
                call.respond(HttpStatusCode.Created, asset.toDto())
            }

            get("/{id}") {
                val userId = call.userId()
                val id = call.parameters["id"]?.toLongOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "ID invalido"))
                    return@get
                }
                val asset = repository.findById(id, userId)
                if (asset == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Ativo nao encontrado"))
                else call.respond(asset.toDto())
            }

            put("/{id}") {
                val userId = call.userId()
                val id = call.parameters["id"]?.toLongOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "ID invalido"))
                    return@put
                }
                val req = call.receive<AssetRequest>()
                val parsed = parseAssetRequest(req) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION", "Dados do ativo invalidos"))
                    return@put
                }
                val asset = repository.update(
                    id = id,
                    userId = userId,
                    name = parsed.name,
                    assetType = parsed.assetType,
                    currentValue = parsed.currentValue,
                    expectedReturn = parsed.expectedReturn,
                    volatility = parsed.volatility
                )
                if (asset == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Ativo nao encontrado"))
                else call.respond(asset.toDto())
            }

            delete("/{id}") {
                val userId = call.userId()
                val id = call.parameters["id"]?.toLongOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "ID invalido"))
                    return@delete
                }
                if (repository.delete(id, userId)) call.respond(HttpStatusCode.NoContent)
                else call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Ativo nao encontrado"))
            }
        }
    }
}

private data class ParsedAsset(
    val name: String,
    val assetType: AssetType,
    val currentValue: BigDecimal,
    val expectedReturn: BigDecimal,
    val volatility: BigDecimal
)

private fun parseAssetRequest(req: AssetRequest): ParsedAsset? {
    if (req.name.isBlank()) return null
    val type = runCatching { AssetType.valueOf(req.assetType) }.getOrNull() ?: return null
    val value = runCatching { BigDecimal(req.currentValue) }.getOrNull() ?: return null
    val ret = runCatching { BigDecimal(req.expectedReturn) }.getOrNull() ?: return null
    val vol = runCatching { BigDecimal(req.volatility) }.getOrNull() ?: return null
    if (value < BigDecimal.ZERO || vol < BigDecimal.ZERO) return null
    return ParsedAsset(req.name.trim(), type, value, ret, vol)
}

private fun Asset.toDto(): AssetDto = AssetDto(
    id = id,
    userId = userId,
    name = name,
    assetType = assetType.name,
    currentValue = currentValue.toPlainString(),
    expectedReturn = expectedReturn.toPlainString(),
    volatility = volatility.toPlainString(),
    createdAt = createdAt.toString()
)
