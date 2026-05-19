package com.lifeforge

import com.lifeforge.security.PasswordHasher
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/**
 * Testes do hash de senhas. Sao rapidos (nao tocam o banco) e validam o
 * comportamento esperado de bcrypt.
 *
 * Testes mais completos (autenticacao end-to-end via HTTP) virao na Sprint 2,
 * apos a configuracao de banco de testes (H2 em memoria) estar pronta.
 */
class PasswordHasherTest : StringSpec({

    "hash produz string no formato bcrypt" {
        val hash = PasswordHasher.hash("senha-super-secreta")
        // Hashes BCrypt comecam com $2a$, $2b$ ou $2y$
        (hash.startsWith("\$2a\$") || hash.startsWith("\$2b\$") || hash.startsWith("\$2y\$")) shouldBe true
    }

    "verify retorna true para senha correta" {
        val raw = "minha-senha-123"
        val hash = PasswordHasher.hash(raw)
        PasswordHasher.verify(raw, hash) shouldBe true
    }

    "verify retorna false para senha incorreta" {
        val hash = PasswordHasher.hash("certa")
        PasswordHasher.verify("errada", hash) shouldBe false
    }

    "hashes da mesma senha sao diferentes (salt aleatorio)" {
        val raw = "mesma-senha"
        val h1 = PasswordHasher.hash(raw)
        val h2 = PasswordHasher.hash(raw)
        (h1 == h2) shouldBe false
        // mas ambos verificam corretamente
        PasswordHasher.verify(raw, h1) shouldBe true
        PasswordHasher.verify(raw, h2) shouldBe true
    }
})
