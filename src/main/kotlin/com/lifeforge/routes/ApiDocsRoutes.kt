package com.lifeforge.routes

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Documentacao da API via OpenAPI/Swagger (requisito da Secao 10 e do criterio
 * 12.1 do TCC - "endpoints REST operacionais e documentados").
 *
 *   GET /openapi.yaml -> a especificacao OpenAPI 3.0 (lida de resources)
 *   GET /docs         -> Swagger UI renderizando a spec acima
 *
 * Optamos por servir a spec estatica + Swagger UI manualmente (em vez de um
 * plugin) para nao acoplar a uma versao especifica do ktor-server-swagger e
 * para ter controle total do que e exposto. A spec vive em
 * `resources/openapi/documentation.yaml` e e carregada uma unica vez no
 * registro da rota.
 *
 * Rotas publicas (sem JWT): documentacao deve ser acessivel sem login.
 */
fun Route.apiDocsRoutes() {
    val loader = Thread.currentThread().contextClassLoader
        ?: ApiDocsMarker::class.java.classLoader
    val spec: String = loader.getResource(OPENAPI_RESOURCE)?.readText()
        ?: FALLBACK_SPEC

    get("/openapi.yaml") {
        call.respondText(spec, ContentType.parse("application/yaml"))
    }

    get("/docs") {
        call.respondText(swaggerUiHtml(), ContentType.Text.Html)
    }
}

/** Marcador apenas para obter um ClassLoader confiavel do modulo. */
private object ApiDocsMarker

private const val OPENAPI_RESOURCE = "openapi/documentation.yaml"

private fun swaggerUiHtml(): String = """
    <!DOCTYPE html>
    <html lang="pt-BR">
    <head>
      <meta charset="UTF-8">
      <title>LifeForge API - Swagger UI</title>
      <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
      <style>body { margin: 0 } </style>
    </head>
    <body>
      <div id="swagger-ui"></div>
      <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js" crossorigin></script>
      <script>
        window.onload = function () {
          window.ui = SwaggerUIBundle({
            url: '/openapi.yaml',
            dom_id: '#swagger-ui',
            deepLinking: true,
            presets: [SwaggerUIBundle.presets.apis],
          });
        };
      </script>
    </body>
    </html>
""".trimIndent()

private const val FALLBACK_SPEC = """
openapi: 3.0.3
info:
  title: LifeForge API
  version: 0.1.0
paths: {}
"""
