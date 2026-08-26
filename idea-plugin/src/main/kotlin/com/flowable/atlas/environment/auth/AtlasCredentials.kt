package com.flowable.atlas.environment.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * The IDE password safe, keyed by **base URL** — one store for every Flowable server Atlas signs in to.
 *
 * There were two, `DesignCredentials` and `InspectCredentials`, identical but for their service name.
 * Nothing about a password depends on whether the server behind the URL serves models or runs
 * processes, so the split bought nothing and cost the plugin a second place to look, a second thing to
 * clear, and — once one connection form served both kinds — a branch at every call site.
 *
 * Two records per URL, and they must stay two: PasswordSafe holds one [Credentials] per
 * [CredentialAttributes], so writing a token under the basic-auth name would overwrite the password,
 * and switching [AuthMode] back and forth would silently lose whichever secret you were not using.
 *
 * A shared server is a shared record on purpose. Two environments — or two projects — on one URL are
 * the same server and the same login: a password typed for DEV is the password QA needs when both
 * point at one Design instance.
 *
 * The safe is backed by the OS keychain, which can block or prompt: **never call any of this on the
 * EDT.**
 */
object AtlasCredentials {

    /** Account name of the token record — keychain backends want a non-null user. */
    private const val TOKEN_ACCOUNT = "access-token"

    private fun normalize(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    private fun attributes(baseUrl: String): CredentialAttributes =
        CredentialAttributes(generateServiceName("Flowable Atlas", normalize(baseUrl)))

    private fun tokenAttributes(baseUrl: String): CredentialAttributes =
        CredentialAttributes(generateServiceName("Flowable Atlas Token", normalize(baseUrl)))

    fun load(baseUrl: String): Credentials? =
        if (baseUrl.isBlank()) null else PasswordSafe.instance.get(attributes(baseUrl))

    fun save(baseUrl: String, username: String, password: String) {
        if (baseUrl.isBlank()) return
        PasswordSafe.instance.set(attributes(baseUrl), Credentials(username, password))
    }

    fun clear(baseUrl: String) {
        if (baseUrl.isBlank()) return
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
     * Everything needed to authenticate against [baseUrl]: the stored secret for [mode], **plus** any
     * browser session captured for it this IDE session.
     *
     * One function for both, because a credential alone is not the answer for an IdP-fronted server and
     * a session alone is not the answer for a local one — and a caller that assembled the two itself
     * would be the place the next asymmetry creeps back in.
     *
     * [username] comes from the connection, where it is authoritative; the record's own account name is
     * the fallback, which is what lets an environment the *project* shares — and which therefore has no
     * username of its own — still sign in as this developer.
     */
    fun contextFor(baseUrl: String, mode: AuthMode, username: String = ""): AuthContext {
        val session = BrowserSessions.get(baseUrl).orEmpty()
        val credential = when (mode) {
            AuthMode.BASIC -> load(baseUrl)?.let { stored ->
                val user = username.ifBlank { stored.userName.orEmpty() }
                val password = stored.getPasswordAsString().orEmpty()
                if (user.isBlank() && password.isBlank()) null
                else AuthContext.Credential.Basic(user, password)
            }
            AuthMode.ACCESS_TOKEN -> loadToken(baseUrl)?.let { AuthContext.Credential.Token(it) }
        }
        return AuthContext(credential, session)
    }
}
