package com.lifeforge.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry

/**
 * Observabilidade: coleta metricas das requisicoes HTTP e da JVM via Micrometer
 * e as expoe no formato Prometheus em `GET /metrics` (publico). Permite plugar
 * Prometheus/Grafana para monitorar latencia, throughput e uso de recursos -
 * item de "nivel publicacao" do TCC.
 */
fun Application.configureMonitoring() {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    install(MicrometerMetrics) {
        this.registry = registry
    }

    routing {
        get("/metrics") {
            call.respond(registry.scrape())
        }
    }
}
