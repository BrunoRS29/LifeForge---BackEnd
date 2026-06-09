// build.gradle.kts
// Configuracao do projeto backend LifeForge
// Stack: Ktor 3.x + Exposed (ORM) + PostgreSQL + JWT + BCrypt

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val kotlinVersion: String by project
val ktorVersion: String by project
val exposedVersion: String by project
val logbackVersion: String by project
val postgresVersion: String by project
val hikariVersion: String by project
val bcryptVersion: String by project
val kotestVersion: String by project

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("io.ktor.plugin") version "3.0.0"
    application
    jacoco
}

group = "com.lifeforge"
version = "0.1.0"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server core
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")

    // Serializacao JSON
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")

    // Autenticacao JWT
    implementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktorVersion")

    // CORS, status pages, request validation
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-request-validation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers-jvm:$ktorVersion")
    // Rate limiting (protege /auth contra forca bruta)
    implementation("io.ktor:ktor-server-rate-limit-jvm:$ktorVersion")

    // ORM Exposed (sobre PostgreSQL)
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")

    // PostgreSQL JDBC + connection pool
    implementation("org.postgresql:postgresql:$postgresVersion")
    implementation("com.zaxxer:HikariCP:$hikariVersion")

    // Hash de senhas (bcrypt)
    implementation("at.favre.lib:bcrypt:$bcryptVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // Testes
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("com.h2database:h2:2.2.224") // banco em memoria para testes

    // ---------- Ktor Client (Sprint 5) ----------
    // Cliente HTTP usado pelo MlClient para falar com o microsservico Python.
    // CIO eh o engine pure-Kotlin assincrono (sem deps nativas).
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")

    // ContentNegotiation + kotlinx.serialization para JSON
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")

    // Logging (usado pelo Logging plugin, embora desligado por padrao no MlClient)
    implementation("io.ktor:ktor-client-logging-jvm:$ktorVersion")

    // ---------- Test ----------
    // MockEngine para testes do MlClient sem precisar de servidor real
    testImplementation("io.ktor:ktor-client-mock-jvm:$ktorVersion")
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.test {
    useJUnitPlatform()
    // Gera o relatorio de cobertura (JaCoCo) ao final dos testes - criterio
    // 12.3 do TCC (cobertura > 70% no motor de simulacao).
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        csv.required.set(true)
        html.required.set(true)
    }
}

ktor {
    fatJar {
        archiveFileName.set("lifeforge-backend.jar")
    }
}
