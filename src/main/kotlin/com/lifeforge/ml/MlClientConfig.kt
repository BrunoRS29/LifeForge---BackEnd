package com.lifeforge.ml

import io.ktor.server.config.ApplicationConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Configuracao do MlClient lida do `application.conf`.
 *
 * Vive em uma classe propria (separada do AppContainer) para que seja
 * facil sobrescrever em testes - basta construir uma instancia manualmente
 * sem precisar de um ApplicationConfig real.
 *
 * @param baseUrl URL do microsservico Python (ex.: http://ml-service:8000).
 *                Trailing slashes sao removidos no init para que o
 *                construtor direto e o factory `fromAppConfig` se
 *                comportem do mesmo jeito.
 * @param requestTimeout timeout total da request HTTP
 * @param connectTimeout timeout para estabelecer a conexao TCP
 * @param maxRetries quantas tentativas de retry em falhas transitorias
 * @param retryBaseDelay delay base do backoff exponencial entre tentativas
 */
data class MlClientConfig(
    val baseUrl: String,
    val requestTimeout: Duration = 30.seconds,
    val connectTimeout: Duration = 5.seconds,
    val maxRetries: Int = 3,
    val retryBaseDelay: Duration = 200.milliseconds,
) {
    init {
        require(baseUrl.isNotBlank()) { "ML baseUrl nao pode ser vazio" }
        require(maxRetries >= 0) { "maxRetries deve ser >= 0" }
    }

    /**
     * Versao normalizada do baseUrl - sem trailing slashes.
     * O MlClient sempre concatena este valor com path relativo ("predict/income"),
     * entao precisamos garantir uma unica barra de separacao.
     */
    val normalizedBaseUrl: String = baseUrl.trimEnd('/')

    companion object {
        /**
         * Le a configuracao do bloco `ml { ... }` do `application.conf`.
         *
         * Defaults sao aplicados se o bloco estiver ausente - util para
         * desenvolvimento local sem container do ML.
         */
        fun fromAppConfig(config: ApplicationConfig): MlClientConfig {
            val baseUrl = config.propertyOrNull("ml.baseUrl")?.getString()
                ?: "http://localhost:8000"
            val requestTimeoutMs = config.propertyOrNull("ml.requestTimeoutMs")
                ?.getString()?.toLong() ?: 30_000L
            val connectTimeoutMs = config.propertyOrNull("ml.connectTimeoutMs")
                ?.getString()?.toLong() ?: 5_000L
            val maxRetries = config.propertyOrNull("ml.maxRetries")
                ?.getString()?.toInt() ?: 3
            val retryBaseDelayMs = config.propertyOrNull("ml.retryBaseDelayMs")
                ?.getString()?.toLong() ?: 200L

            return MlClientConfig(
                // Mantemos a normalizacao aqui tambem por simetria, mesmo
                // sabendo que o init do data class ja faz a sua propria.
                baseUrl = baseUrl,
                requestTimeout = requestTimeoutMs.milliseconds,
                connectTimeout = connectTimeoutMs.milliseconds,
                maxRetries = maxRetries,
                retryBaseDelay = retryBaseDelayMs.milliseconds,
            )
        }
    }
}
