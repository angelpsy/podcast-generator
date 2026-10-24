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
import java.io.Reader
import java.io.StringReader
import java.util.Date

class ProtocolTest {
    companion object { private val key: RSAKey by lazy { RSAKeyGenerator(2048).keyID("test-key").generate() } }

    private fun token(aud: String = "client", signingKey: RSAKey = key): String {
        val claims = JWTClaimsSet.Builder().issuer(Protocol.ISSUER).subject("subject").audience(aud)
            .expirationTime(Date(System.currentTimeMillis() + 60_000)).claim("nonce", "expected")
            .claim("email", "person@example.test").build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims)
            .apply { sign(RSASSASigner(signingKey)) }.serialize()
    }

    private fun fails(code: String, action: () -> Unit) {
        try { action(); fail("Must fail closed") }
        catch (error: SafeFailure) { assertEquals(code, error.safeCode) }
    }

    @Test fun callbackRequiresExactPathSingleMatchingState() {
        assertNull(Protocol.callback("/favicon.ico?state=s", "s"))
        assertNull(Protocol.callback("/auth/callback?state=wrong&code=secret", "s"))
        assertNull(Protocol.callback("/auth/callback?state=s&state=s&code=secret", "s"))
        assertNull(Protocol.callback("/callback?state=s&code=secret", "s"))
        assertEquals("code with space", Protocol.callback("/auth/callback?state=s&code=code+with+space", "s")?.get("code"))
    }

    @Test fun verifiesSignatureAudienceAndNonceBeforeIdentity() {
        val jwks = JWKSet(key.toPublicJWK()).toString()
        assertEquals("subject", Protocol.validateIdToken(token(), jwks, "client", "expected").getString("subject"))
        fails("id_nonce") { Protocol.validateIdToken(token(), jwks, "client", "other") }
        fails("id_claims") { Protocol.validateIdToken(token("other"), jwks, "client", "expected") }
        val attacker = RSAKeyGenerator(2048).keyID("test-key").generate()
        fails("id_signature") { Protocol.validateIdToken(token(signingKey = attacker), jwks, "client", "expected") }
    }

    @Test fun rejectsExpiredAndUnsignedTokens() {
        val expired = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
            JWTClaimsSet.Builder().issuer(Protocol.ISSUER).subject("subject").audience("client")
                .expirationTime(Date(0)).claim("nonce", "expected").build())
            .apply { sign(RSASSASigner(key)) }.serialize()
        fails("id_claims") { Protocol.validateIdToken(expired, JWKSet(key.toPublicJWK()).toString(), "client", "expected") }
        fails("id_invalid") { Protocol.validateIdToken("eyJhbGciOiJub25lIn0.eyJzdWIiOiJhIn0.", "{}", "client", "expected") }
    }

    @Test fun readsAccountCatalogInsteadOfApiDataArray() {
        val result = Protocol.models(JSONObject("""{"models":[
          {"slug":"one","display_name":"One","visibility":"list"},
          {"slug":"hidden","display_name":"Hidden","visibility":"hidden"}]}"""))
        assertEquals(1, result.length())
        assertEquals("one", result.getJSONObject(0).getString("slug"))
    }

    @Test fun sseHandlesSingleCharacterChunksMultilineAndTerminalStop() {
        val text = "data: {\"type\":\"response.output_text.delta\",\n" +
            "data: \"delta\":\"Привет\"}\n\n" +
            "data: {\"type\":\"response.completed\"}\n\n" +
            "data: invalid after terminal\n\n"
        val source = StringReader(text)
        val fragmented = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int) = source.read(buffer, offset, minOf(length, 1))
            override fun close() = source.close()
        }
        val types = mutableListOf<String>()
        Protocol.readSse(fragmented.buffered()) {
            types.add(it.getString("type"))
            if (types.size == 1) assertEquals("Привет", it.getString("delta"))
            it.getString("type") != "response.completed"
        }
        assertEquals(listOf("response.output_text.delta", "response.completed"), types)
    }

    @Test fun doneAndEofDoNotInventCompletion() {
        val types = mutableListOf<String>()
        Protocol.readSse(StringReader("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\ndata: [DONE]\n\n").buffered()) {
            types.add(it.getString("type")); true
        }
        assertEquals(listOf("response.output_text.delta"), types)
        Protocol.readSse(StringReader("data: {\"type\":\"response.completed\"}\n").buffered()) {
            fail("An unterminated event must not confirm completion"); true
        }
    }
}
