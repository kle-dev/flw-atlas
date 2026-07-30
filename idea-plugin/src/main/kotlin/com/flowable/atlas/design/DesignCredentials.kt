package com.flowable.atlas.design

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * PasswordSafe storage for the "Pull from Flowable Design" credentials, keyed by the normalized server
 * base URL — two projects (or sub-project scopes) against the same Design server share one entry, while
 * keeping independent [DesignAuthMode]s.
 *
 * The two schemes get **separate** entries: PasswordSafe holds one [Credentials] record per
 * [CredentialAttributes], so storing a token under the basic-auth service name would overwrite the
 * password and switching modes back and forth would silently lose it. Username and password both live in
 * the basic-auth record (never in the VCS-shared project settings XML).
 *
 * The safe is backed by the OS keychain, which can block or prompt — call any function here only off the
 * EDT.
 */
object DesignCredentials {

    /** Account name of the token record — keychain backends want a non-null user. */
    private const val TOKEN_ACCOUNT = "access-token"

    private fun attributes(baseUrl: String): CredentialAttributes =
        CredentialAttributes(generateServiceName("Flowable Atlas Design", DesignClient.normalizeBaseUrl(baseUrl)))

    private fun tokenAttributes(baseUrl: String): CredentialAttributes =
        CredentialAttributes(generateServiceName("Flowable Atlas Design Token", DesignClient.normalizeBaseUrl(baseUrl)))

    fun load(baseUrl: String): Credentials? = PasswordSafe.instance.get(attributes(baseUrl))

    fun save(baseUrl: String, username: String, password: String) {
        PasswordSafe.instance.set(attributes(baseUrl), Credentials(username, password))
    }

    fun clear(baseUrl: String) {
        PasswordSafe.instance.set(attributes(baseUrl), null)
    }

    fun loadToken(baseUrl: String): String? =
        if (baseUrl.isBlank()) null
        else PasswordSafe.instance.get(tokenAttributes(baseUrl))?.getPasswordAsString()?.takeUnless { it.isBlank() }

    fun saveToken(baseUrl: String, token: String) {
        if (baseUrl.isBlank()) return
        PasswordSafe.instance.set(tokenAttributes(baseUrl), Credentials(TOKEN_ACCOUNT, token))
    }

    fun clearToken(baseUrl: String) {
        if (baseUrl.isBlank()) return
        PasswordSafe.instance.set(tokenAttributes(baseUrl), null)
    }

    /**
     * The [DesignClient.Auth] for [mode], or null when its secret is missing — the caller then opens the
     * Connections settings page.
     */
    fun loadAuth(baseUrl: String, mode: DesignAuthMode): DesignClient.Auth? = when (mode) {
        DesignAuthMode.BASIC -> load(baseUrl)?.let { credentials ->
            val username = credentials.userName
            val password = credentials.getPasswordAsString()
            if (username.isNullOrBlank() || password == null) null else DesignClient.Auth.Basic(username, password)
        }
        DesignAuthMode.ACCESS_TOKEN -> loadToken(baseUrl)?.let { DesignClient.Auth.Token(it) }
    }
}
