package com.lifeforge.routes

import com.lifeforge.domain.model.ExpenseCategory
import com.lifeforge.domain.model.IncomeType
import com.lifeforge.domain.repository.ExpenseRepository
import com.lifeforge.domain.repository.IncomeRepository
import com.lifeforge.dto.ImportRequest
import com.lifeforge.dto.ImportResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.math.BigDecimal
import java.time.Instant

/**
 * Importacao em lote de extratos bancarios (Receitas + Despesas de uma vez).
 *
 *   POST /api/v1/finance/import
 *
 * O parsing e a classificacao dos extratos acontecem NO APP (cada banco tem
 * seu formato e a deteccao de transferencias internas precisa do conjunto de
 * arquivos carregados). Aqui apenas persistimos o lote ja classificado,
 * reaproveitando os mesmos repositorios do CRUD avulso.
 *
 * Itens invalidos sao contados em `skipped` em vez de abortar o lote inteiro:
 * um extrato grande nao deve falhar por causa de uma unica linha ruim.
 */
fun Route.financeImportRoutes(
    incomeRepository: IncomeRepository,
    expenseRepository: ExpenseRepository,
) {
    authenticate("auth-jwt") {
        route("/api/v1/finance") {
            post("/import") {
                val userId = call.userId()
                val req = call.receive<ImportRequest>()

                var incomesCreated = 0
                var expensesCreated = 0
                var skipped = 0

                for (inc in req.incomes) {
                    val type = runCatching { IncomeType.valueOf(inc.incomeType) }.getOrNull()
                    val amount = runCatching { BigDecimal(inc.amount) }.getOrNull()
                    val receivedAt = runCatching { Instant.parse(inc.receivedAt) }.getOrNull()
                    if (inc.source.isBlank() || type == null || amount == null ||
                        amount <= BigDecimal.ZERO || receivedAt == null
                    ) {
                        skipped++
                        continue
                    }
                    incomeRepository.create(
                        userId = userId,
                        source = inc.source.trim(),
                        amount = amount,
                        incomeType = type,
                        recurring = inc.recurring,
                        receivedAt = receivedAt,
                    )
                    incomesCreated++
                }

                for (exp in req.expenses) {
                    val category = runCatching { ExpenseCategory.valueOf(exp.category) }.getOrNull()
                    val amount = runCatching { BigDecimal(exp.amount) }.getOrNull()
                    val spentAt = runCatching { Instant.parse(exp.spentAt) }.getOrNull()
                    if (exp.description.isBlank() || category == null || amount == null ||
                        amount <= BigDecimal.ZERO || spentAt == null
                    ) {
                        skipped++
                        continue
                    }
                    expenseRepository.create(
                        userId = userId,
                        description = exp.description.trim(),
                        amount = amount,
                        category = category,
                        recurring = exp.recurring,
                        spentAt = spentAt,
                    )
                    expensesCreated++
                }

                call.respond(
                    HttpStatusCode.Created,
                    ImportResponse(
                        incomesCreated = incomesCreated,
                        expensesCreated = expensesCreated,
                        skipped = skipped,
                    ),
                )
            }
        }
    }
}
