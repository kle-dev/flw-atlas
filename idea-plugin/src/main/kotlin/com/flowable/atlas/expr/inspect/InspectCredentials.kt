package com.flowable.atlas.expr.inspect

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * PasswordSafe storage for the Flowable Inspect basic-auth credentials (the playground's "Evaluate
 * Against App"), keyed by the normalized app base URL — the exact counterpart of [DesignCredentials]
 * for the Design connection. Saved after a successful evaluation, so the password only has to be
 * typed once per app. The safe is backed by the OS keychain, which can block or prompt — call
 * [load]/[save]/[clear] only off the EDT.
 */
object InspectCredentials {

    private fun normalize(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    private fun attributes(baseUrl: String): CredentialAttributes =
        CredentialAttributes(generateServiceName("Flowable Atlas Inspect", normalize(baseUrl)))

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
}
