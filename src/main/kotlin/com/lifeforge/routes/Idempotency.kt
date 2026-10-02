package com.lifeforge.routes

import io.ktor.server.application.ApplicationCall
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Idempotencia das criacoes (cabecalho `Idempotency-Key`).
 *
 * O app offline-first reenvia uma criacao quando a resposta se perde no
 * caminho (timeout depois de o servidor gravar). Repetindo a mesma chave, o
 * reenvio recebe o registro ja criado em vez de duplica-lo.
 *
 * Escopo: por usuario e por tipo de recurso; validade de 24 h; armazenamento
 * em memoria (uma instancia do backend, como no ambiente do TCC). Em um
 * cluster, o mesmo contrato seria atendido por uma tabela ou por um cache
 * distribuido.
 */
class IdempotencyRegistry(
    private val ttl: Duration = Duration.ofHours(24),
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Entry(val resourceId: Long, val createdAt: Instant)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Id do recurso ja criado com esta chave, se houver e ainda for valido. */
    fun find(userId: Long, scope: String, key: String?): Long? {
        if (key.isNullOrBlank()) return null
        purgeExpired()
        return entries[compose(userId, scope, key)]?.resourceId
    }

    /** Registra o recurso criado com esta chave. Sem chave, nada a fazer. */
    fun remember(userId: Long, scope: String, key: String?, resourceId: Long) {
        if (key.isNullOrBlank()) return
        entries[compose(userId, scope, key)] = Entry(resourceId, Instant.now(clock))
    }

    private fun purgeExpired() {
        val limit = Instant.now(clock).minus(ttl)
        entries.entries.removeIf { it.value.createdAt.isBefore(limit) }
    }

    private fun compose(userId: Long, scope: String, key: String) = "$userId:$scope:$key"

    companion object {
        const val HEADER = "Idempotency-Key"

        /** Instancia compartilhada pelas rotas de criacao. */
        val shared = IdempotencyRegistry()
    }
}

/** Valor do cabecalho `Idempotency-Key` da requisicao (null se ausente). */
fun ApplicationCall.idempotencyKey(): String? =
    request.headers[IdempotencyRegistry.HEADER]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
