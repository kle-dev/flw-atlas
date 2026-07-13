package com.flowable.atlas.design

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * PasswordSafe storage for the "Pull from Flowable Design" basic-auth credentials, keyed by the
 * normalized server base URL — two projects against the same Design server share one entry.
 * Username and password both live in the [Credentials] record (never in the VCS-shared project
 * settings XML). The safe is backed by the OS keychain, which can block or prompt — call
 * [load]/[save]/[clear] only off the EDT.
 */
object DesignCredentials {

    private fun attributes(baseUrl: String): CredentialAttributes =
        CredentialAttributes(generateServiceName("Flowable Atlas Design", DesignClient.normalizeBaseUrl(baseUrl)))

    fun load(baseUrl: String): Credentials? = PasswordSafe.instance.get(attributes(baseUrl))

    fun save(baseUrl: String, username: String, password: String) {
        PasswordSafe.instance.set(attributes(baseUrl), Credentials(username, password))
    }

    fun clear(baseUrl: String) {
        PasswordSafe.instance.set(attributes(baseUrl), null)
    }
}
