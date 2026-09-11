package com.flowable.atlas.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as a secret written down. The rules that keep this quiet are the ones worth pinning: a key
 * that talks *about* a secret, a value that is an expression, and a non-string value are none of it.
 */
class SecretScanTest {

    @Test
    fun aLiteralUnderASecretKeyIsFoundByItsPath() {
        val doc = mapOf(
            "key" to "customerService",
            "config" to mapOf("authentication" to mapOf("type" to "basic", "username" to "demo", "password" to "changeme")),
            "operations" to listOf(
                mapOf("key" to "findAll", "config" to mapOf("url" to "/api/customers")),
                mapOf("key" to "sync", "config" to mapOf("apiKey" to "abc123", "tokenUrl" to "https://idp.example.com/token")),
            ),
        )
        assertEquals(listOf("config.authentication.password", "operations[1].config.apiKey"), SecretScan.scan(doc))
    }

    @Test
    fun expressionsAndDescriptionsOfASecretAreNotSecrets() {
        val doc = mapOf(
            "password" to "\${propertyConfigurationService.getProperty('db.password')}",
            "token" to "{{env.token}}",
            "passwordField" to "pwd", "credentialsType" to "apiKey", "tokenExpiry" to "3600", "secretProvider" to "vault",
            "secretEnabled" to true, "apiKey" to "",
        )
        assertTrue(SecretScan.scan(doc).isEmpty())
    }

    @Test
    fun credentialsInsideAUrlAreFoundWhateverTheKeyIsCalled() {
        val doc = mapOf("config" to mapOf("baseUrl" to "https://svc:s3cret@api.example.com/v1", "url" to "https://api.example.com"))
        assertEquals(listOf("config.baseUrl (credentials in URL)"), SecretScan.scan(doc))
    }

    @Test
    fun fieldInjectionsAreJudgedByTheirLiteralValueOnly() {
        assertEquals(listOf("password", "requestUrl"),
            SecretScan.secretFields(linkedMapOf("password" to "hunter2", "requestUrl" to "http://u:p@host/x", "requestMethod" to "GET")))
        assertFalse(SecretScan.isSecretKey("tokenHeaderName"))
        assertTrue(SecretScan.isSecretKey("clientSecret"))
        assertTrue(SecretScan.isSecretKey("api-key"))
    }
}
