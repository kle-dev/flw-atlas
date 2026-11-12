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

    @Test
    fun aUrlWhoseCredentialsAreExpressionsIsWhatTheFindingAsksFor() {
        val doc = mapOf("config" to mapOf("baseUrl" to "https://\${svcUser}:\${svcPassword}@host/api"))
        assertTrue("an expression-valued URL is the fix, not the finding", SecretScan.scan(doc).isEmpty())
        assertTrue(SecretScan.secretFields(mapOf("requestUrl" to "https://\${u}:\${p}@host")).isEmpty())
    }

    @Test
    fun keysThatTalkAboutASecretAreNotSecrets() {
        for (k in listOf("tokenizerModel", "maxTokens", "useTokenAuth", "passwordPolicyDescription",
                         "privateKeyAlias", "secretQuestion", "tokenValidity", "apiKeyLabel", "hasCredentials")) {
            assertFalse(k, SecretScan.isSecretKey(k))
        }
        for (k in listOf("password", "apiKey", "clientSecret", "privateKey", "authToken", "credentials", "accessToken")) {
            assertTrue(k, SecretScan.isSecretKey(k))
        }
    }

    @Test
    fun aUrlLosesItsCredentialsAndNothingElse() {
        assertEquals("https://***@api.example.com/v1", SecretScan.redactUrl("https://svc:s3cret@api.example.com/v1"))
        assertEquals("/orders?access_token=***&page=2&api_key=***",
            SecretScan.redactUrl("/orders?access_token=abc&page=2&api_key=xyz"))
        // the fix is not the leak: credentials and a token written as expressions stay as they are
        val expr = "https://\${u}:\${p}@host/x?token={{env.token}}"
        assertEquals(expr, SecretScan.redactUrl(expr))
        // a templated pagination value is judged whole, not cut at its first space and masked half-way
        val paged = "https://crm.example.com/leads?\$orderby=createdon desc&\$skiptoken=<cookie pagenumber='\${page}' />"
        assertEquals(paged, SecretScan.redactUrl(paged))
        // and a URL with nothing to mask is the very same string, so a golden without credentials never moves
        val plain = "{{endpoints.platform}}/platform-api/search/case-instances?caseDefinitionKey=DEMO-C001&size=50"
        assertTrue(plain === SecretScan.redactUrl(plain))
    }

    @Test
    fun aFieldValueIsMaskedByWhatTheFieldIs() {
        assertEquals(SecretScan.REDACTED, SecretScan.redactFieldValue("password", "hunter2"))
        assertEquals("\${vault.pw}", SecretScan.redactFieldValue("password", "\${vault.pw}"))
        assertEquals("https://***@host/x?api_key=***", SecretScan.redactFieldValue("requestUrl", "https://u:p@host/x?api_key=k"))
        assertEquals("Accept: text/plain\nAuthorization: ***\nX-Api-Key: ***",
            SecretScan.redactFieldValue("requestHeaders", "Accept: text/plain\nAuthorization: Basic dTpw\nX-Api-Key: k1"))
        assertEquals("Authorization: Bearer \${token}", SecretScan.redactFieldValue("requestHeaders", "Authorization: Bearer \${token}"))
        // a script body is no query string: only a URL's userinfo is masked there
        val script = "def u = 'https://u:p@host/x?token=t'"
        assertEquals("def u = 'https://***@host/x?token=t'", SecretScan.redactFieldValue("script", script))
        assertEquals(42, SecretScan.redactFieldValue("password", 42))
        // a request body: the secret-named pair is masked, the rest reads as the JSON it is
        assertEquals("""{"user": "demo", "apiKey": "***", "tokenType": "Bearer", "secret": "${'$'}{vault.s}"}""",
            SecretScan.redactFieldValue("requestBody", """{"user": "demo", "apiKey": "k1", "tokenType": "Bearer", "secret": "${'$'}{vault.s}"}"""))
        val body = """{"orderId": "42"}"""
        assertTrue(body === SecretScan.redactJsonPairs(body))
    }

    @Test
    fun aHandlersDefaultIsMaskedOnlyWhenItIsACredential() {
        assertEquals(SecretScan.REDACTED, SecretScan.redactParamDefault("Authorization", "header", "Bearer abc"))
        assertEquals(SecretScan.REDACTED, SecretScan.redactParamDefault("api_key", "query", "k1"))
        // `Authorization` as a query parameter is a name like any other, and a page size is no secret
        assertEquals("x", SecretScan.redactParamDefault("Authorization", "query", "x"))
        assertEquals("50", SecretScan.redactParamDefault("size", "query", "50"))
        assertEquals("\${auth.default}", SecretScan.redactParamDefault("Authorization", "header", "\${auth.default}"))
    }
}
