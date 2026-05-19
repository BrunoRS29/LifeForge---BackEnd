package com.lifeforge.config

import com.lifeforge.data.tables.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Inicializa o pool de conexoes (HikariCP) e o Exposed Database.
 *
 * Tambem expoe o helper `dbQuery` para executar transacoes em coroutines
 * sem bloquear a thread principal.
 *
 * Em ambiente de desenvolvimento, faz a criacao automatica das tabelas via
 * SchemaUtils.create. Em producao real, isso seria substituido por Flyway
 * ou Liquibase para versionamento de schema.
 */
object DatabaseFactory {

    fun init(config: ApplicationConfig) {
        val driverClassName = config.property("postgres.driverClassName").getString()
        val jdbcUrl = config.property("postgres.url").getString()
        val username = config.property("postgres.user").getString()
        val password = config.property("postgres.password").getString()
        val poolSize = config.property("postgres.maximumPoolSize").getString().toInt()

        val dataSource = hikari(driverClassName, jdbcUrl, username, password, poolSize)
        Database.connect(dataSource)

        transaction {
            SchemaUtils.create(
                Users,
                Goals,
                Incomes,
                Expenses,
                Assets,
                Simulations,
                Predictions
            )
        }
    }

    private fun hikari(
        driver: String,
        url: String,
        user: String,
        pass: String,
        poolSize: Int
    ): HikariDataSource {
        val cfg = HikariConfig().apply {
            driverClassName = driver
            jdbcUrl = url
            username = user
            password = pass
            maximumPoolSize = poolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        return HikariDataSource(cfg)
    }

    /**
     * Executa um bloco em uma transacao do Exposed sem bloquear a thread.
     * Todos os repositorios chamam este helper.
     */
    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
