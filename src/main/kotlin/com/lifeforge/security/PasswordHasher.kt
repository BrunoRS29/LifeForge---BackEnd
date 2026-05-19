package com.lifeforge.security

import at.favre.lib.crypto.bcrypt.BCrypt

/**
 * Wrapper sobre BCrypt para hash e verificacao de senhas.
 *
 * BCrypt e o algoritmo recomendado para hash de senhas porque:
 *  - Tem custo computacional ajustavel (cost factor)
 *  - Inclui salt automaticamente
 *  - E resistente a ataques por GPU
 *
 * Cost 12 e o equilibrio padrao entre seguranca e performance em 2025+.
 */
object PasswordHasher {

    private const val COST = 12

    fun hash(rawPassword: String): String =
        BCrypt.withDefaults().hashToString(COST, rawPassword.toCharArray())

    fun verify(rawPassword: String, hash: String): Boolean =
        BCrypt.verifyer().verify(rawPassword.toCharArray(), hash).verified
}
