package dev.podcast.siwcspike.siwc

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Date

internal class SafeFailure(val safeCode: String) : Exception(safeCode)

internal object Protocol {
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"

    fun equal(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** Some SIWC responses omit Content-Type despite carrying normal SSE records. */
    fun isEventStream(contentType: String?, prefix: String): Boolean {
        val type = contentType?.substringBefore(';')?.trim()
        if (type.equals("text/event-stream", ignoreCase = true)) return true
        if (!type.isNullOrEmpty()) return false
        val start = prefix.trimStart()
        return start.startsWith("data:") || start.startsWith("event:") || start.startsWith(":")
    }

    /** Invalid and unrelated requests leave the pending authorization alive. */
    fun callback(target: String, expectedState: String): Map<String, String>? = try {
        val uri = URI(target)
        if (!target.startsWith("/") || uri.rawPath != "/auth/callback" || uri.rawFragment != null) null
        else {
            val fields = linkedMapOf<String, String>()
            var valid = true
            (uri.rawQuery ?: "").split('&').forEach {
                val pair = it.split('=', limit = 2)
                val name = URLDecoder.decode(pair[0], "UTF-8")
                val value = URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                if (fields.put(name, value) != null) valid = false
            }
            if (valid && equal(fields["state"] ?: "", expectedState)) fields else null
        }
    } catch (_: Exception) { null }

    fun models(json: JSONObject): JSONArray {
        val source = json.getJSONArray("models")
        val result = JSONArray()
        for (i in 0 until source.length()) {
            val item = source.getJSONObject(i)
            if (item.optString("visibility") == "list") result.put(JSONObject()
                .put("slug", item.getString("slug"))
                .put("name", item.getString("display_name")))
        }
        return result
    }

    fun validateIdToken(token: String, jwks: String, clientId: String, nonce: String?): JSONObject {
        try {
            val jwt = SignedJWT.parse(token)
            val algorithm = jwt.header.algorithm
            if (algorithm != JWSAlgorithm.RS256 && algorithm != JWSAlgorithm.ES256) throw SafeFailure("id_algorithm")
            val kid = jwt.header.keyID ?: throw SafeFailure("id_kid")
            val key = JWKSet.parse(jwks).getKeyByKeyId(kid) ?: throw SafeFailure("id_unknown_key")
            if (key.algorithm != null && key.algorithm != algorithm) throw SafeFailure("id_algorithm")
            val verified = when {
                algorithm == JWSAlgorithm.RS256 && key is RSAKey -> jwt.verify(RSASSAVerifier(key.toRSAPublicKey()))
                algorithm == JWSAlgorithm.ES256 && key is ECKey -> jwt.verify(ECDSAVerifier(key.toECPublicKey()))
                else -> false
            }
            if (!verified) throw SafeFailure("id_signature")
            val c = jwt.jwtClaimsSet
            val now = Date()
            if (c.issuer != ISSUER || !c.audience.contains(clientId) || c.subject.isNullOrBlank() ||
                c.expirationTime == null || !c.expirationTime.after(now) ||
                (c.notBeforeTime != null && c.notBeforeTime.time > now.time + 60_000) ||
                (c.issueTime != null && c.issueTime.time > now.time + 60_000)) throw SafeFailure("id_claims")
            val azp = c.getStringClaim("azp")
            if ((c.audience.size > 1 && azp != clientId) || (azp != null && azp != clientId)) throw SafeFailure("id_audience")
            if (nonce != null && !equal(c.getStringClaim("nonce") ?: "", nonce)) throw SafeFailure("id_nonce")
            return JSONObject().put("subject", c.subject).put("email", c.getStringClaim("email") ?: "")
        } catch (error: SafeFailure) { throw error }
        catch (_: Exception) { throw SafeFailure("id_invalid") }
    }

    /** SSE records can span arbitrary network chunks and contain several data lines. */
    fun readSse(reader: BufferedReader, accept: (JSONObject) -> Boolean) {
        val data = StringBuilder()
        fun dispatch(): Boolean {
            if (data.isNotEmpty()) {
                val raw = data.toString().trimEnd('\n')
                data.setLength(0)
                if (raw != "[DONE]") return accept(JSONObject(raw))
            }
            return true
        }
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty() && !dispatch()) return
            else if (line.startsWith("data:")) {
                val value = line.substring(5).removePrefix(" ")
                data.append(value).append('\n')
                if (data.length > 1_048_576) throw SafeFailure("stream_event_too_large")
            }
        }
        // EOF without the blank record delimiter is an interrupted SSE event.
    }
}
