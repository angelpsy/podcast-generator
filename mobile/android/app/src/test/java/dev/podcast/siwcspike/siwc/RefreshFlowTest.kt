package dev.podcast.siwcspike.siwc

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Date

class RefreshFlowTest {
    companion object {
        private val key: RSAKey by lazy { RSAKeyGenerator(2048).keyID("test").generate() }
    }

    private fun idToken(subject: String = "subject", signingKey: RSAKey = key): String = SignedJWT(
        JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),
        JWTClaimsSet.Builder().issuer(Protocol.ISSUER).audience("client").subject(subject)
            .expirationTime(Date(System.currentTimeMillis() + 3_600_000)).build(),
    ).apply { sign(RSASSASigner(signingKey)) }.serialize()

    private inner class Harness {
        var clock = 1_000_000L
        var disk = JSONObject().put("host_id", "host").put("client_id", "client")
            .put("subject", "subject").put("email", "test@example.test")
            .put("access_token", "a1").put("refresh_token", "r1").put("id_token", "old-id")
            .put("scope", Protocol.PLAN_SCOPE).put("expires_at", clock + 9_000_000).toString()
        val sent = mutableListOf<String>()
        var validationCalls = 0
        var cancelled = false
        var tokenError: Exception? = null
        var jwksError: Exception? = null
        var cancelAfterExchange = false
        var cancelAfterValidation = false
        var issuedId = idToken()

        fun read() = JSONObject(disk)
        fun flow() = RefreshFlow(
            read = ::read,
            persist = { disk = it.toString() },
            clear = { disk = RefreshFlow.withoutTokens(read()).toString() },
            exchange = { c ->
                assertEquals("client", c.getString("client_id"))
                sent.add(c.getString("refresh_token"))
                tokenError?.let { throw it }
                if (cancelAfterExchange) cancelled = true
                JSONObject().put("token_type", "Bearer").put("access_token", "a2")
                    .put("refresh_token", "r2").put("id_token", issuedId).put("expires_in", 3600)
            },
            validate = { token, client ->
                validationCalls++
                jwksError?.let { throw it }
                val identity = Protocol.validateIdToken(token, JWKSet(key.toPublicJWK()).toString(), client, null)
                if (cancelAfterValidation) cancelled = true
                identity
            },
            checkCancelled = { if (cancelled) throw SafeFailure("cancelled") },
            now = { clock },
        )

        fun assertPending() {
            assertEquals("a1", read().getString("access_token"))
            assertEquals("r1", read().getString("refresh_token"))
            assertEquals("r2", RefreshFlow.revocationToken(read()))
            // Even an unexpired predecessor must not be used for another API call.
            assertTrue(RefreshFlow.needsRefresh(read(), clock))
        }

        fun assertPromoted() {
            assertEquals(listOf("r1"), sent)
            assertEquals("a2", read().getString("access_token"))
            assertEquals("r2", read().getString("refresh_token"))
            assertFalse(read().has("pending_refresh"))
        }
    }

    private fun fails(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") }
        catch (error: SafeFailure) { assertEquals(code, error.safeCode) }
    }

    @Test fun everyTerminalCodeSurvivesHttpParsingAndClearsOnlyTokens() {
        val codes = listOf("invalid_grant", "invalid_refresh_token", "token_expired",
            "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")
        for (code in codes) for (nested in listOf(false, true)) {
            val h = Harness()
            val body = JSONObject().put("error", if (nested) JSONObject().put("code", code) else code)
            h.tokenError = SafeFailure(Protocol.httpErrorCode(body, 400))
            fails(code) { h.flow().run() }
            val c = h.read()
            for (field in listOf("access_token", "refresh_token", "id_token", "scope", "expires_at", "pending_refresh"))
                assertFalse("$code retained $field", c.has(field))
            assertEquals("client", c.getString("client_id"))
            assertEquals("host", c.getString("host_id"))
            assertEquals("subject", c.getString("subject"))
            assertEquals(0, h.validationCalls)
        }
    }

    @Test fun networkServerAndClientConfigurationErrorsPreserveCredentials() {
        for (error in listOf(IOException("offline"), SafeFailure("http_503"), SafeFailure("invalid_client"))) {
            val h = Harness()
            val before = h.disk
            h.tokenError = error
            try { h.flow().run(); fail("Must fail") }
            catch (caught: Exception) { assertSame(error, caught) }
            assertEquals(before, h.disk)
        }
        assertEquals("http_500", Protocol.httpErrorCode(JSONObject().put("error", "private server message"), 500))
    }

    @Test fun jwksFailureResumesFromStoredResponseWithoutReusingOldRefreshToken() {
        val h = Harness()
        h.jwksError = IOException("offline")
        try { h.flow().run(); fail("Must fail") } catch (_: IOException) { }
        h.assertPending()
        val receivedAt = h.clock
        h.clock += 120_000
        h.jwksError = null
        // New flow instance reads only the serialized record, as after a process restart.
        h.flow().run()
        h.assertPromoted()
        assertEquals(receivedAt + 3_600_000, h.read().getLong("expires_at"))
        assertEquals(Protocol.PLAN_SCOPE, h.read().getString("scope"))
    }

    @Test fun cancellationImmediatelyAfterRotationStillPersistsReplacement() {
        val h = Harness()
        h.cancelAfterExchange = true
        fails("cancelled") { h.flow().run() }
        assertEquals(0, h.validationCalls)
        h.assertPending()
        h.cancelled = false
        h.flow().run()
        h.assertPromoted()
    }

    @Test fun cancellationAfterValidationDoesNotActivateUncommittedTokens() {
        val h = Harness()
        h.cancelAfterValidation = true
        fails("cancelled") { h.flow().run() }
        h.assertPending()
        h.cancelled = false
        h.cancelAfterValidation = false
        h.flow().run()
        h.assertPromoted()
    }

    @Test fun wrongIdentityAndInvalidSignatureNeverReplaceActiveCredentials() {
        val attacker = RSAKeyGenerator(2048).keyID("test").generate()
        for ((token, code) in listOf(idToken("other") to "account_mismatch",
            idToken(signingKey = attacker) to "id_signature")) {
            val h = Harness()
            h.issuedId = token
            fails(code) { h.flow().run() }
            h.assertPending()
            fails(code) { h.flow().run() }
            assertEquals(listOf("r1"), h.sent)
            assertEquals("subject", h.read().getString("subject"))
        }
    }

    @Test fun logoutUsesLatestRefreshTokenAndRemovesPendingResponse() {
        val h = Harness()
        h.cancelAfterExchange = true
        fails("cancelled") { h.flow().run() }
        assertEquals("r2", RefreshFlow.revocationToken(h.read()))
        val cleared = RefreshFlow.withoutTokens(h.read())
        assertEquals("", RefreshFlow.revocationToken(cleared))
        assertFalse(cleared.has("pending_refresh"))
        assertFalse(cleared.has("access_token"))
        assertEquals("client", cleared.getString("client_id"))
    }

    @Test fun freshSignInSupersedesPendingRefresh() {
        val h = Harness()
        h.cancelAfterExchange = true
        fails("cancelled") { h.flow().run() }
        val response = JSONObject().put("token_type", "Bearer").put("access_token", "a3")
            .put("refresh_token", "r3").put("id_token", "validated-new-id")
            .put("scope", "openid").put("expires_in", 3600)
        val signedIn = RefreshFlow.promote(h.read(), response, null, h.clock)
        assertFalse(signedIn.has("pending_refresh"))
        assertEquals("r3", RefreshFlow.revocationToken(signedIn))
        assertEquals("openid", signedIn.getString("scope"))
    }
}
