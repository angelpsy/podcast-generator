package dev.podcast.siwcspike.podcast

import android.content.Context
import dev.podcast.siwcspike.siwc.SafeFailure
import dev.podcast.siwcspike.siwc.SiwcCoordinator
import org.json.JSONObject
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** One foreground-only request, independent of catalog edits and UI components. No auto retry. */
class OutlineCoordinator private constructor(context: Context) {
    companion object {
        @Volatile private var instance: OutlineCoordinator? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: OutlineCoordinator(context.applicationContext).also { instance = it }
        }
    }
    private val repo = OutlineRepository(PodcastDatabases.get(context))
    private val siwc = SiwcCoordinator.get(context)
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var signal: AtomicBoolean? = null
    @Volatile private var activeId = ""
    @Volatile private var foreground = false
    init { worker.execute { runCatching { repo.recover() } } }
    fun busy() = signal != null
    @Synchronized fun foreground(value: Boolean) { foreground = value; if (!value) cancel() }
    @Synchronized fun cancel(id: String = activeId) {
        if (id == activeId) signal?.let { siwc.cancelScript(it) }
    }
    fun cancelIfDeleted() { val id = activeId; if (id.isNotEmpty() && !repo.exists(id)) cancel(id) }
    @Synchronized fun start(request: JSONObject, created: (String) -> Unit, failed: (String) -> Unit) {
        if (signal != null) { failed("outline_busy"); return }
        if (!foreground) { failed("app_not_foreground"); return }
        val cancelled = AtomicBoolean(false); signal = cancelled
        worker.execute {
            var id = ""
            try {
                if (cancelled.get()) throw SafeFailure("cancelled")
                repo.recover()
                val p = repo.create(request); id = p.id; activeId = id; created(id)
                if (cancelled.get() || !repo.exists(id)) throw SafeFailure("cancelled")
                val result = siwc.generateScript(p.model, p.input, OutlineFormat.INSTRUCTIONS.replace("на русском языке", "на выбранном языке") + "\nЯзык результата: " + LanguagePolicy.instruction(JSONObject(p.input).optString("languagePolicy", "ru_standard")), OutlineFormat.MAX_CHARS,
                    cancelled, OutlineFormat.format(), requirePreferredModel = true).get(150, TimeUnit.SECONDS)
                if (cancelled.get()) throw SafeFailure("cancelled")
                repo.complete(id, result.getString("script"), result.getString("textModel"))
            } catch (error: Exception) {
                val cause = if (error is ExecutionException) error.cause ?: error else error
                val code = when {
                    cancelled.get() -> "cancelled"
                    cause is SafeFailure -> cause.safeCode
                    cause is TopicFailure -> cause.code
                    cause is TimeoutException -> "outline_timeout"
                    else -> "outline_request_failed"
                }
                siwc.cancelScript(cancelled)
                if (id.isNotEmpty()) runCatching { repo.fail(id, code, code == "cancelled") } else failed(code)
            } finally { synchronized(this) { activeId = ""; signal = null } }
        }
    }
}
