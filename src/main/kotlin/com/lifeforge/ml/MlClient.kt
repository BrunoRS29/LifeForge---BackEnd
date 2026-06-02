package com.lifeforge.ml

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Cliente HTTP para o microsservico Python de ML.
 *
 * Caracteristicas:
 *  - Timeout configuravel (conexao + request total)
 *  - Retry com backoff exponencial em erros 5xx, IO e timeouts
 *  - NAO faz retry em 4xx (erro do cliente nao melhora retentando)
 *  - Logs estruturados em SLF4J (volume controlado: somente erros e retries)
 *  - Conversao de erros HTTP em [MlClientException] tipadas
 *
 * Como instanciar:
 *
 *     val config = MlClientConfig.fromAppConfig(env.config)
 *     val mlClient = MlClient(config)
 *     // ... use mlClient ...
 *     mlClient.close() // no shutdown da aplicacao
 *
 * Em testes, injetar um [HttpClientEngine] mock (ex.: `MockEngine`).
 */
class MlClient(
    private val config: MlClientConfig,
    engine: HttpClientEngine? = null,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(MlClient::class.java)

    /** Json compartilhado - precisa ignorar campos extras para resiliencia a evolucao do Python. */
    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    /**
     * HttpClient do Ktor. Se `engine` for fornecido (testes), usa ele;
     * caso contrario, usa CIO (motor pure-Kotlin coroutine-based).
     */
    private val client: HttpClient = if (engine != null) {
        HttpClient(engine) { configureClient() }
    } else {
        HttpClient(CIO) { configureClient() }
    }

    private fun io.ktor.client.HttpClientConfig<*>.configureClient() {
        expectSuccess = false  // nao lanca em 4xx/5xx - tratamos manualmente

        install(ContentNegotiation) {
            json(jsonFormat)
        }

        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeout.inWholeMilliseconds
            connectTimeoutMillis = config.connectTimeout.inWholeMilliseconds
        }

        install(Logging) {
            level = LogLevel.NONE  // log custom abaixo evita ruido
            logger = object : Logger {
                override fun log(message: String) {}
            }
        }

        defaultRequest {
            // Usamos normalizedBaseUrl para garantir uma barra unica entre
            // host e path. Sem isso, baseUrl="http://x///" + "/" geraria
            // "http://x/////predict/income" (problema visto no teste de
            // trailing slash, ver MlClientTest).
            url(config.normalizedBaseUrl + "/")
            headers.append("User-Agent", "lifeforge-backend/0.1")
        }
    }

    // ========================================================================
    // API publica
    // ========================================================================

    /**
     * Verifica se o servico ML esta respondendo.
     * Util para healthcheck composto do backend.
     */
    suspend fun health(): Boolean = try {
        val response = client.get("health")
        response.status == HttpStatusCode.OK
    } catch (e: Exception) {
        log.warn("ML health check falhou: {}", e.message)
        false
    }

    /** POST /predict/income */
    suspend fun predictIncome(
        request: IncomePredictionRequestDto,
    ): IncomePredictionResponseDto =
        postJson("predict/income", request)

    /** POST /predict/expenses */
    suspend fun predictExpenses(
        request: ExpensePredictionRequestDto,
    ): ExpensePredictionResponseDto =
        postJson("predict/expenses", request)

    /** GET /models/metrics */
    suspend fun modelsMetrics(): ModelsMetricsResponseDto =
        getJson("models/metrics")

    override fun close() {
        client.close()
    }

    // ========================================================================
    // Internos
    // ========================================================================

    /**
     * POST generico com serializacao + retry + tratamento de erro.
     *
     * inline reified para que o Ktor consiga descobrir o tipo de retorno
     * em runtime sem precisarmos passar Class<T> manualmente.
     */
    private suspend inline fun <reified Req, reified Res> postJson(
        path: String,
        body: Req,
    ): Res {
        val response: HttpResponse = withRetry(path) {
            client.post(path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return handleResponse(response)
    }

    private suspend inline fun <reified Res> getJson(path: String): Res {
        val response: HttpResponse = withRetry(path) {
            client.get(path)
        }
        return handleResponse(response)
    }

    /**
     * Loop de retry com backoff exponencial.
     *
     * Retry policy:
     *  - Refaz em IOException, HttpRequestTimeoutException e respostas 5xx
     *  - Nao refaz em 2xx, 3xx ou 4xx
     *  - Backoff: base * 2^(n-1)  (200ms, 400ms, 800ms, ...)
     */
    private suspend fun withRetry(
        operation: String,
        block: suspend () -> HttpResponse,
    ): HttpResponse {
        var lastException: Throwable? = null

        repeat(config.maxRetries + 1) { attempt ->
            try {
                val response = block()
                if (response.status.value < 500) {
                    return response
                }
                // 5xx - retry se ainda restam tentativas
                lastException = MlInternalError(
                    "ML respondeu ${response.status} em $operation"
                )
                log.warn(
                    "ML 5xx em {} (tentativa {}/{}): status={}",
                    operation, attempt + 1, config.maxRetries + 1, response.status,
                )
            } catch (e: HttpRequestTimeoutException) {
                lastException = e
                log.warn(
                    "ML timeout em {} (tentativa {}/{}): {}",
                    operation, attempt + 1, config.maxRetries + 1, e.message,
                )
            } catch (e: IOException) {
                lastException = e
                log.warn(
                    "ML IOException em {} (tentativa {}/{}): {}",
                    operation, attempt + 1, config.maxRetries + 1, e.message,
                )
            }

            if (attempt < config.maxRetries) {
                val delayMs = config.retryBaseDelay.inWholeMilliseconds * (1L shl attempt)
                delay(delayMs)
            }
        }

        throw MlUnavailableError(
            "Falha em $operation apos ${config.maxRetries + 1} tentativas",
            lastException,
        )
    }

    /**
     * Converte a [HttpResponse] em [Res] ou lanca [MlClientException].
     *
     * - 2xx: desserializa o corpo como Res
     * - 4xx: tenta desserializar como MlErrorResponse e lanca MlValidationError
     * - 5xx: ja foi tratado pelo retry (nao chega aqui normalmente)
     *
     * Sobre o catch: o `response.body()` do Ktor com plugin ContentNegotiation
     * faz a desserializacao automaticamente. Quando o JSON e invalido o Ktor
     * embrulha a `SerializationException` numa `JsonConvertException` que NAO
     * eh subclasse de SerializationException. Precisamos pegar as duas
     * explicitamente para garantir que JSON malformado vire MlInternalError
     * em vez de vazar para o chamador.
     */
    private suspend inline fun <reified Res> handleResponse(
        response: HttpResponse,
    ): Res {
        return when (response.status.value) {
            in 200..299 -> try {
                response.body()
            } catch (e: JsonConvertException) {
                throw MlInternalError(
                    "Resposta do ML em formato inesperado: ${e.message}",
                    e,
                )
            } catch (e: SerializationException) {
                throw MlInternalError(
                    "Resposta do ML em formato inesperado: ${e.message}",
                    e,
                )
            }
            in 400..499 -> {
                val errorBody = runCatching {
                    jsonFormat.decodeFromString(
                        MlErrorResponse.serializer(),
                        response.bodyAsText(),
                    )
                }.getOrElse {
                    MlErrorResponse(
                        error = "ML_BAD_REQUEST",
                        message = "Erro ${response.status} sem corpo estruturado",
                    )
                }
                throw MlValidationError(errorBody.error, errorBody.message)
            }
            else -> throw MlInternalError(
                "Status inesperado ${response.status} do ML"
            )
        }
    }
}
