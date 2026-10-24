package dev.podcast.siwcspike.siwc

import org.json.JSONObject

/** Runs on the coordinator's single executor. Pending tokens never reach inference or JS. */
internal class RefreshFlow(
    private val read: () -> JSONObject,
    private val persist: (JSONObject) -> Unit,
    private val clear: () -> Unit,
    private val exchange: (JSONObject) -> JSONObject,
    private val validate: (String, String) -> JSONObject,
    private val checkCancelled: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun run() {
        checkCancelled()
        var c = read()
        if (!c.has(PENDING)) {
            if (c.optString("refresh_token").isEmpty()) throw SafeFailure("sign_in_required")
            val response = try { exchange(c) }
            catch (error: SafeFailure) {
                // Only token-endpoint failures can invalidate the renewable session.
                if (error.safeCode in Protocol.terminalRefreshErrors) clear()
                throw error
            }
            c = JSONObject(c.toString()).put(PENDING, JSONObject()
                .put("response", response).put("received_at", now()))
            // Save before any cancellation check or JWKS request after rotation.
            persist(c)
        }
        checkCancelled()
        val pending = c.getJSONObject(PENDING)
        val response = pending.getJSONObject("response")
        val identity = if (response.has("id_token"))
            validate(response.getString("id_token"), c.getString("client_id")) else null
        if (identity != null && identity.getString("subject") != c.getString("subject"))
            throw SafeFailure("account_mismatch")
        checkCancelled()
        persist(promote(c, response, identity, pending.getLong("received_at")))
    }

    companion object {
        private const val PENDING = "pending_refresh"

        fun needsRefresh(c: JSONObject, now: Long): Boolean =
            c.has(PENDING) || c.optLong("expires_at") <= now + 60_000

        fun revocationToken(c: JSONObject): String =
            c.optJSONObject(PENDING)?.optJSONObject("response")?.optString("refresh_token")
                ?.takeIf { it.isNotEmpty() } ?: c.optString("refresh_token")

        fun withoutTokens(c: JSONObject): JSONObject = JSONObject(c.toString()).apply {
            for (name in listOf("access_token", "refresh_token", "id_token", "expires_at", "scope", PENDING)) remove(name)
        }

        fun promote(c: JSONObject, response: JSONObject, identity: JSONObject?, receivedAt: Long): JSONObject {
            if (!response.getString("token_type").equals("Bearer", ignoreCase = true)) throw SafeFailure("token_type")
            val access = response.getString("access_token").takeIf { it.isNotEmpty() }
                ?: throw SafeFailure("empty_access_token")
            val next = JSONObject(c.toString()).put("access_token", access)
                .put("expires_at", receivedAt + response.getLong("expires_in") * 1000)
                .put("scope", response.optString("scope", c.optString("scope")))
            for (name in listOf("refresh_token", "id_token")) if (response.has(name)) next.put(name, response.getString(name))
            identity?.let { next.put("subject", it.getString("subject")).put("email", it.getString("email")) }
            next.remove(PENDING)
            return next
        }
    }
}
