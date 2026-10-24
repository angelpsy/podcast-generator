package dev.podcast.siwcspike.siwc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Process-owned session; replacing a React context only replaces event subscribers. */
internal class SiwcCoordinator private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: SiwcCoordinator? = null
        fun get(context: Context): SiwcCoordinator = instance ?: synchronized(this) {
            instance ?: SiwcCoordinator(context.applicationContext).also { instance = it }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val store = CredentialStore(context)
    private var credentials: JSONObject? = null
    @Volatile private var initialized = false
    @Volatile private var server: ServerSocket? = null
    @Volatile private var callbackSocket: java.net.Socket? = null
    @Volatile private var call: Call? = null
    private var discovery: JSONObject? = null
    private var revision = 0L
    private var operationId = ""
    private val state = JSONObject().put("stage", "SignedOut").put("busy", false)
        .put("email", "").put("planEnabled", false).put("models", JSONArray())
        .put("answer", "").put("error", "").put("requestId", "").put("httpStatus", 0)
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS).build()

    fun subscribe(listener: (String) -> Unit) { listeners.add(listener) }
    fun unsubscribe(listener: (String) -> Unit) { listeners.remove(listener) }
    @Synchronized fun snapshot(): String = state.toString()

    @Synchronized private fun update(stage: String? = null, change: (JSONObject) -> Unit = {}) {
        if (stage != null) { state.put("stage", stage); Log.i("SiwcSpike", stage) }
        change(state)
        state.put("revision", ++revision).put("operationId", operationId)
            .put("busy", busy.get()).put("updatedAt", System.currentTimeMillis())
            .put("signedIn", credentials?.let { connected(it) } ?: false)
        val json = state.toString()
        listeners.forEach { listener -> main.post { if (listeners.contains(listener)) listener(json) } }
    }

    private fun account(): JSONObject = credentials ?: throw SafeFailure("storage_unavailable")
    private fun planEnabled(c: JSONObject) = c.optString("scope").split(' ').contains(Protocol.PLAN_SCOPE)
    private fun connected(c: JSONObject) = c.optString("access_token").isNotEmpty()

    private fun initialize() {
        if (initialized) return
        credentials = store.load()
        initialized = true
        val c = account()
        update(if (connected(c)) { if (planEnabled(c)) "Ready" else "SignedInWithoutPlan" } else "SignedOut") {
            it.put("email", if (connected(c)) c.optString("email") else "")
                .put("planEnabled", connected(c) && planEnabled(c))
        }
    }

    fun load(done: (String?, String?) -> Unit) {
        if (initialized) { done(snapshot(), null); return }
        executor.execute {
            try { initialize(); done(snapshot(), null) }
            catch (_: Exception) { update("Failed") { it.put("error", "storage_unavailable") }; done(snapshot(), null) }
        }
    }

    private fun run(stage: String, done: (String?) -> Unit, work: () -> Unit) {
        if (!busy.compareAndSet(false, true)) { done("operation_busy"); return }
        cancelled.set(false)
        executor.execute {
            operationId = UUID.randomUUID().toString()
            try {
                initialize()
                update(stage) { it.put("error", "").put("requestId", "").put("httpStatus", 0) }
                work()
            } catch (error: Exception) {
                val safe = if (cancelled.get()) "cancelled" else when (error) {
                    is SafeFailure -> error.safeCode
                    is java.io.InterruptedIOException -> "network_timeout"
                    is java.io.IOException -> "network_error"
                    else -> "invalid_response"
                }
                Log.i("SiwcSpike", "Failure: $safe")
                update(if (safe == "cancelled") "Cancelled" else "Failed") { it.put("error", safe) }
            } finally {
                server?.runCatching { close() }; server = null
                callbackSocket?.runCatching { close() }; callbackSocket = null
                call = null
                busy.set(false)
                update()
                done(null) // Expected protocol errors are represented in the safe snapshot.
            }
        }
    }

    fun cancel() {
        if (!busy.get()) return
        cancelled.set(true)
        server?.runCatching { close() }
        callbackSocket?.runCatching { close() }
        call?.cancel()
    }
    private fun checkCancelled() { if (cancelled.get()) throw SafeFailure("cancelled") }

    private fun execute(request: Request): Response {
        checkCancelled()
        val active = http.newCall(request)
        call = active
        checkCancelled()
        return active.execute()
    }

    private fun safeHttpError(response: Response): Nothing {
        val json = runCatching { JSONObject(response.body?.string()?.take(65_536) ?: "{}") }.getOrNull()
        val raw = json?.optJSONObject("error")?.optString("code")
            ?: json?.optString("error") ?: ""
        val code = if (raw.matches(Regex("subscription_sharing_[a-z_]{1,80}|chatpass_v2_[a-z_]{1,80}")) ||
            raw in setOf("invalid_grant", "invalid_client", "invalid_refresh_token", "refresh_token_reused")) raw
            else "http_${response.code}"
        val requestId = response.header("x-request-id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) } ?: ""
        update { it.put("httpStatus", response.code).put("requestId", requestId) }
        throw SafeFailure(code)
    }

    private fun json(request: Request): JSONObject = execute(request).use { response ->
        if (!response.isSuccessful) safeHttpError(response)
        val body = response.body ?: throw SafeFailure("empty_response")
        if (body.contentLength() > 2_097_152) throw SafeFailure("response_too_large")
        val text = StringBuilder()
        body.charStream().use { reader ->
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count == -1) break
                text.append(buffer, 0, count)
                if (text.length > 2_097_152) throw SafeFailure("response_too_large")
            }
        }
        JSONObject(text.toString())
    }

    private fun config(): JSONObject {
        discovery?.let { return it }
        return json(Request.Builder().url("${Protocol.ISSUER}/.well-known/openid-configuration").build()).also {
            if (it.getString("issuer") != Protocol.ISSUER) throw SafeFailure("discovery_issuer")
            for (name in listOf("authorization_endpoint", "token_endpoint", "jwks_uri", "revocation_endpoint")) {
                val uri = Uri.parse(it.getString(name))
                if (uri.scheme != "https" || uri.host != "auth.openai.com" || uri.port !in listOf(-1, 443) ||
                    uri.userInfo != null || uri.fragment != null) throw SafeFailure("discovery_endpoint")
            }
            discovery = it
        }
    }

    private fun tokenRequest(form: FormBody): JSONObject = json(Request.Builder()
        .url(config().getString("token_endpoint")).post(form).build())

    private fun validate(token: String, clientId: String, nonce: String?): JSONObject {
        val jwks = json(Request.Builder().url(config().getString("jwks_uri")).build()).toString()
        return try { Protocol.validateIdToken(token, jwks, clientId, nonce) }
        catch (error: SafeFailure) {
            if (error.safeCode != "id_unknown_key") throw error
            val fresh = json(Request.Builder().url(config().getString("jwks_uri")).header("Cache-Control", "no-cache").build())
            Protocol.validateIdToken(token, fresh.toString(), clientId, nonce)
        }
    }

    private fun saveTokens(response: JSONObject, c: JSONObject, identity: JSONObject? = null) {
        if (!response.getString("token_type").equals("Bearer", ignoreCase = true)) throw SafeFailure("token_type")
        val access = response.getString("access_token").takeIf { it.isNotEmpty() } ?: throw SafeFailure("empty_access_token")
        val next = JSONObject(c.toString()).put("access_token", access)
            .put("expires_at", System.currentTimeMillis() + response.getLong("expires_in") * 1000)
            .put("scope", response.optString("scope", c.optString("scope")))
        for (name in listOf("refresh_token", "id_token")) if (response.has(name)) next.put(name, response.getString(name))
        identity?.let { next.put("subject", it.getString("subject")).put("email", it.getString("email")) }
        store.save(next)
        credentials = next
    }

    fun signIn(done: (String?) -> Unit) = run("Authorizing", done) {
        val c = account()
        val nonce = random()
        val state = random()
        val verifier = random()
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        server = listener
        listener.soTimeout = 1000
        val redirect = "http://127.0.0.1:${listener.localPort}/auth/callback"
        val priorId = c.optString("client_id")
        val uri = Uri.parse(config().getString("authorization_endpoint")).buildUpon()
            .appendQueryParameter("client_id", priorId.ifEmpty { "dynamic_agent_client" })
            .appendQueryParameter("ext_agent_host_id", c.getString("host_id"))
            .appendQueryParameter("response_type", "code").appendQueryParameter("redirect_uri", redirect)
            .appendQueryParameter("scope", Protocol.SCOPE).appendQueryParameter("resource", Protocol.RESOURCE)
            .appendQueryParameter("state", state).appendQueryParameter("nonce", nonce)
            .appendQueryParameter("code_challenge_method", "S256").appendQueryParameter("code_challenge", challenge)
        if (priorId.isEmpty()) uri.appendQueryParameter("agent_name_hint", "Podcast Generator SIWC Spike")
        else if (c.optString("id_token").isNotEmpty()) uri.appendQueryParameter("id_token_hint", c.getString("id_token"))
        checkCancelled()
        main.post {
            if (!cancelled.get()) try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri.build()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { cancelled.set(true); listener.runCatching { close() } }
        }
        val callback = awaitCallback(listener, state)
        listener.close(); server = null
        if (callback.containsKey("error")) throw SafeFailure("consent_denied")
        val code = callback["code"]?.takeIf { it.isNotEmpty() } ?: throw SafeFailure("callback_code")
        val id = callback["client_id"] ?: priorId
        if (id.isBlank() || id == "dynamic_agent_client" || (priorId.isNotEmpty() && id != priorId)) throw SafeFailure("callback_client")
        // Retain the issued ID even if the following exchange is interrupted.
        c.put("client_id", id); store.save(c)
        update("Validating")
        val tokens = tokenRequest(FormBody.Builder().add("grant_type", "authorization_code").add("client_id", id)
            .add("code", code).add("code_verifier", verifier).add("redirect_uri", redirect).add("resource", Protocol.RESOURCE).build())
        val identity = validate(tokens.getString("id_token"), id, nonce)
        if (c.optString("subject").isNotEmpty() && c.getString("subject") != identity.getString("subject")) throw SafeFailure("account_mismatch")
        checkCancelled()
        // A fresh exchange must explicitly grant plan usage; don't inherit an older grant.
        if (!tokens.has("scope")) tokens.put("scope", "")
        saveTokens(tokens, c, identity)
        update(if (planEnabled(account())) "Ready" else "SignedInWithoutPlan") {
            it.put("email", identity.getString("email")).put("planEnabled", planEnabled(account())).put("models", JSONArray())
        }
    }

    private fun awaitCallback(listener: ServerSocket, state: String): Map<String, String> {
        val deadline = SystemClock.elapsedRealtime() + 300_000
        while (SystemClock.elapsedRealtime() < deadline) {
            checkCancelled()
            val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
            callbackSocket = socket
            socket.use {
                it.soTimeout = 5000
                try {
                    val input = it.getInputStream()
                    val header = StringBuilder()
                    val headerDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 5000)
                    while (!header.endsWith("\r\n\r\n")) {
                        val remaining = headerDeadline - SystemClock.elapsedRealtime()
                        if (remaining <= 0) throw SafeFailure("callback_header_timeout")
                        it.soTimeout = remaining.toInt()
                        val b = input.read()
                        if (b == -1 || header.length >= 16384) throw SafeFailure("callback_header")
                        header.append(b.toChar())
                    }
                    val request = header.toString().substringBefore("\r\n").split(' ')
                    val fields = if (request.size == 3 && request[0] == "GET") Protocol.callback(request[1], state) else null
                    val text = if (fields != null) "Sign-in received. Return to the app." else "Invalid callback."
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    it.getOutputStream().write(("HTTP/1.1 ${if (fields != null) "200 OK" else "400 Bad Request"}\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                        "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                    if (fields != null) return fields
                } catch (_: Exception) { checkCancelled() }
                finally { callbackSocket = null }
            }
        }
        throw SafeFailure("authorization_timeout")
    }

    private fun refresh() {
        val c = account()
        val token = c.optString("refresh_token").takeIf { it.isNotEmpty() } ?: throw SafeFailure("sign_in_required")
        try {
            val response = tokenRequest(FormBody.Builder().add("grant_type", "refresh_token")
                .add("client_id", c.getString("client_id")).add("refresh_token", token).add("resource", Protocol.RESOURCE).build())
            val identity = if (response.has("id_token")) validate(response.getString("id_token"), c.getString("client_id"), null) else null
            if (identity != null && identity.getString("subject") != c.getString("subject")) throw SafeFailure("account_mismatch")
            saveTokens(response, c, identity)
            update { it.put("planEnabled", planEnabled(account())) }
        } catch (error: SafeFailure) {
            if (error.safeCode in setOf("invalid_grant", "invalid_refresh_token", "refresh_token_reused")) clearTokens()
            throw error
        }
    }

    private fun authorized(build: (String) -> Request): Response {
        if (!connected(account())) throw SafeFailure("sign_in_required")
        if (!planEnabled(account())) throw SafeFailure("plan_not_enabled")
        if (account().optLong("expires_at") <= System.currentTimeMillis() + 60_000) refresh()
        if (!planEnabled(account())) throw SafeFailure("plan_not_enabled")
        var response = execute(build(account().getString("access_token")))
        if (response.code == 401 && account().optString("refresh_token").isNotEmpty()) {
            response.close(); refresh()
            if (!planEnabled(account())) throw SafeFailure("plan_not_enabled")
            response = execute(build(account().getString("access_token")))
        }
        return response
    }

    fun listModels(done: (String?) -> Unit) = run("LoadingModels", done) {
        authorized { token -> Request.Builder().url("${Protocol.RESOURCE}/models").header("Authorization", "Bearer $token").build() }.use {
            if (!it.isSuccessful) safeHttpError(it)
            val models = Protocol.models(JSONObject(it.body?.string() ?: throw SafeFailure("empty_response")))
            update("Ready") { state -> state.put("models", models) }
        }
    }

    fun sendPrompt(model: String, text: String, done: (String?) -> Unit) = run("Streaming", done) {
        val available = synchronized(this) { state.getJSONArray("models").toString() }
        val models = JSONArray(available)
        if ((0 until models.length()).none { models.getJSONObject(it).getString("slug") == model }) throw SafeFailure("select_model")
        if (text.isBlank() || text.length > 4000) throw SafeFailure("prompt_length")
        update { it.put("answer", "").put("model", model) }
        val body = JSONObject().put("model", model).put("store", false).put("stream", true)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
            .toString().toRequestBody("application/json".toMediaType())
        authorized { token -> Request.Builder().url("${Protocol.RESOURCE}/responses")
            .header("Authorization", "Bearer $token").header("Accept", "text/event-stream").post(body).build() }.use { response ->
            if (!response.isSuccessful) safeHttpError(response)
            val mediaType = response.body?.contentType()
            val contentType = mediaType?.let { "${it.type}/${it.subtype}" }
                ?.takeIf { it.length <= 96 && it.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+")) } ?: "unknown"
            val prefix = response.peekBody(512).string().trimStart()
            val format = when {
                prefix.startsWith("data:") || prefix.startsWith("event:") || prefix.startsWith(":") -> "sse"
                prefix.startsWith("{") -> "json"
                prefix.startsWith("<") -> "html"
                else -> "other"
            }
            Log.i("SiwcSpike", "Response HTTP ${response.code}; content_type=$contentType; header_present=${response.header("Content-Type") != null}; format=$format")
            update { it.put("httpStatus", response.code).put("responseContentType", contentType).put("responseFormat", format)
                .put("requestId", response.header("x-request-id")?.takeIf { id -> id.matches(Regex("[A-Za-z0-9_-]{1,128}")) } ?: "") }
            if (!Protocol.isEventStream(response.header("Content-Type"), prefix)) throw SafeFailure("expected_event_stream")
            var terminal = false
            var outcome = ""
            val answer = StringBuilder()
            val reader = response.body?.charStream()?.buffered() ?: throw SafeFailure("empty_response")
            Protocol.readSse(reader) { event ->
                checkCancelled()
                if (!terminal) when (event.optString("type")) {
                    "response.output_text.delta" -> {
                        answer.append(event.getString("delta"))
                        if (answer.length > 100_000) throw SafeFailure("answer_too_large")
                        update { it.put("answer", answer.toString()) }
                    }
                    "response.completed" -> { terminal = true; outcome = "Completed" }
                    "response.incomplete" -> { terminal = true; outcome = "Incomplete" }
                    "response.failed", "error" -> {
                        val error = event.optJSONObject("response")?.optJSONObject("error") ?: event.optJSONObject("error")
                        val code = error?.optString("code") ?: event.optString("code")
                        throw SafeFailure(if (code.matches(Regex("subscription_sharing_[a-z_]{1,80}"))) code else "response_failed")
                    }
                }
                !terminal
            }
            if (!terminal) throw SafeFailure("stream_interrupted")
            update(outcome)
        }
    }

    private fun clearTokens() {
        val c = account()
        for (name in listOf("access_token", "refresh_token", "id_token", "expires_at", "scope")) c.remove(name)
        store.save(c)
        update("SignedOut") { it.put("email", "").put("planEnabled", false).put("models", JSONArray()).put("answer", "") }
    }

    fun signOut(done: (String?) -> Unit) = run("SigningOut", done) {
        var revoked = true
        val c = account()
        if (c.optString("refresh_token").isNotEmpty()) {
            revoked = runCatching {
                execute(Request.Builder().url(config().getString("revocation_endpoint"))
                    .post(FormBody.Builder().add("token", c.getString("refresh_token"))
                        .add("token_type_hint", "refresh_token").add("client_id", c.getString("client_id")).build()).build())
                    .use { it.isSuccessful }
            }.getOrDefault(false)
        }
        clearTokens()
        if (!revoked) update { it.put("error", "revocation_unconfirmed") }
    }

    private fun random(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let {
        Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
