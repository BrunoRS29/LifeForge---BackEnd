package com.lifeforge.ml

/**
 * Hierarquia de excecoes do MlClient.
 *
 * O codigo das rotas mapeia cada subtipo para um HTTP status no Ktor:
 *  - [MlValidationError]    -> 422 (proxy do erro 422 do Python)
 *  - [MlUnavailableError]   -> 503 (servico fora do ar / timeout)
 *  - [MlInternalError]      -> 502 (erro interno do Python ou JSON malformado)
 *
 * Separar dessa forma permite tratar de modo diferente em cima:
 *  - Validation eh problema do cliente (dados insuficientes do usuario)
 *  - Unavailable eh problema operacional (retry pode resolver)
 *  - Internal eh bug e deve gerar alerta
 */
sealed class MlClientException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Erro 4xx do microsservico - normalmente dados insuficientes ou invalidos.
 * Inclui o codigo de erro estruturado retornado pelo Python.
 */
class MlValidationError(
    code: String,
    message: String,
) : MlClientException(code, message)

/**
 * Servico indisponivel apos esgotar retries.
 * Causas tipicas: container caido, rede instavel, timeout.
 */
class MlUnavailableError(
    message: String,
    cause: Throwable? = null,
) : MlClientException("ML_UNAVAILABLE", message, cause)

/**
 * Erro 5xx do microsservico ou resposta malformada.
 */
class MlInternalError(
    message: String,
    cause: Throwable? = null,
) : MlClientException("ML_INTERNAL", message, cause)
