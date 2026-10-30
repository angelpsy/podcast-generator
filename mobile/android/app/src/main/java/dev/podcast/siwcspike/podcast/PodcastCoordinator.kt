package dev.podcast.siwcspike.podcast

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.podcast.siwcspike.siwc.SafeFailure
import dev.podcast.siwcspike.siwc.SiwcCoordinator
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One foreground job: completed SIWC text -> local speech -> saved M4A. Tokens stay in SIWC. */
class PodcastCoordinator private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: PodcastCoordinator? = null
        fun get(context: Context): PodcastCoordinator = instance ?: synchronized(this) {
            instance ?: PodcastCoordinator(context.applicationContext).also { instance = it }
        }
    }
    private class Job(val retry: Boolean, val topic: String, val model: String, val sample: Boolean, val task: GenerationTask? = null) {
        val cancelled = AtomicBoolean(false)
        var phase = "PreparingVoice"
        fun check() { if (cancelled.get()) throw CancellationException() }
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val queueWorker = Executors.newSingleThreadExecutor()
    private val root = File(context.noBackupFilesDir, "podcast").apply { mkdirs() }
    private val audit = QueueCommandAudit(File(root, "queue-audit.jsonl"), { Log.i("QueueAudit", it) })
    private val store = PodcastLibrary(PodcastDatabases.get(context), root, AndroidAudioMedia(context))
    private val queue = GenerationQueue(PodcastDatabases.get(context))
    private var queueRunning = false
    private var dispatching = false
    private val draftJournal get() = store.draftJournal
    private var searchQuery = ""
    private val tts = LocalTtsEngine(context)
    private val siwc = SiwcCoordinator.get(context)
    private val encoder = M4aEncoder(context)
    private val listeners = linkedSetOf<(String) -> Unit>()
    private var foreground = false
    private var activeJob: Job? = null
    private var revision = 0L
    private val state = JSONObject().put("stage", "Loading").put("busy", true)
        .put("error", "").put("script", "").put("inputTopic", "").put("selectedModel", "").put("playback", "Stopped")
        .put("canRetry", false).put("positionMs", 0).put("durationMs", 0)

    init { worker.execute {
        val command = audit.begin("recover", "startup")
        audit.event(command, if (loadLibrary()) "applied" else "failed", counts = auditCounts())
    } }
    private fun loadLibrary(): Boolean {
        try {
            store.initialize()
            queue.recover()
            val input = runCatching { AtomicAudioJournal(File(root, "input.json")).read() }.getOrDefault(JSONObject())
            publishLibrary(input = input)
            return true
        } catch (_: Exception) {
            main.post { state.put("busy", false).put("stage", "StorageError")
                .put("error", "storage_recovery_failed").put("storageReady", false); emit() }
            return false
        }
    }
    private fun publishLibrary(error: String = "", input: JSONObject? = null) {
        val current = store.current()
        val pending = PodcastDraft.resumable(draftJournal, current?.optString("id"))
        val rows = store.list(searchQuery)
        val selectedId = store.selectedId
        val queueState = queue.json()
        main.post {
            state.put("busy", false).put("storageReady", true)
                .put("stage", if (pending != null) "DraftReady" else if (current != null) "Ready" else "Idle")
                .put("error", error).put("canRetry", pending != null).put("episode", current ?: JSONObject.NULL)
                .put("generationQueue", queueState).put("selectedId", selectedId).put("library", rows)
                .put("script", pending?.optString("script").orEmpty())
                .put("positionMs", 0).put("durationMs", current?.optLong("durationMs") ?: 0)
            input?.let { state.put("inputTopic", it.optString("topic")).put("selectedModel", it.optString("model")) }
            emit(); pump()
        }
    }
    private fun auditCounts() = runCatching { queue.auditCounts() }.getOrNull()
    private fun publishQueue() {
        val rows = queue.json(); main.post { state.put("generationQueue", rows); emit() }
    }
    fun previewGeneration(raw: String, done: (String?, String?) -> Unit) { queueWorker.execute {
        try { require(raw.length <= 16000); done(queue.preview(JSONObject(raw)).toString(), null) }
        catch (e: TopicFailure) { done(null, e.code) } catch (e: SafeFailure) { done(null, e.safeCode) } catch (_: Exception) { done(null, "invalid_request") }
    } }
    fun enqueueGeneration(raw: String, source: String = "internal", done: (String?, String?) -> Unit) { queueWorker.execute {
        val command = audit.begin("enqueue", source)
        try { require(raw.length <= 16000); val result = queue.enqueue(JSONObject(raw)); publishQueue(); done(result.toString(), null)
            audit.event(command, "applied", result.getInt("added"), auditCounts())
            main.post { queueRunning = true; pump() } }
        catch (e: TopicFailure) { audit.event(command, "failed"); done(null, e.code) } catch (e: SafeFailure) { audit.event(command, "failed"); done(null, e.safeCode) } catch (_: Exception) { audit.event(command, "failed"); done(null, "generation_storage_failed") }
    } }
    fun resumeQueue(id: String, source: String = "internal") { main.post {
        val command = audit.begin("resume", source, id)
        if (activeJob != null || dispatching || state.optBoolean("busy")) { audit.event(command, "rejected_busy"); return@post }
        worker.execute { try { val affected = queue.resume(id); audit.event(command, "applied", affected, auditCounts()); publishQueue(); main.post { queueRunning = true; pump() } }
            catch (_: Exception) { audit.event(command, "failed"); main.post { state.put("error", "generation_storage_failed"); emit() } } }
    } }
    fun cancelTask(id: String, source: String = "internal") { main.post {
        val command = audit.begin("cancel", source, id)
        if (activeJob?.task?.id == id) { audit.event(command, "paused", 1); cancelOnMain(source) }
        else queueWorker.execute {
            try {
                val removed = queue.cancel(id)
                if (!removed && queue.find(id)?.status == "RUNNING") main.post { if (activeJob?.task?.id == id) cancelOnMain(source) }
                audit.event(command, "applied", if (removed) 1 else 0, auditCounts()); publishQueue()
            } catch (_: Exception) { audit.event(command, "failed"); main.post { state.put("error", "generation_storage_failed"); emit() } }
        }
    } }
    private fun pump() {
        if (!queueRunning || dispatching || activeJob != null || state.optBoolean("busy") || !foreground) return
        dispatching = true
        worker.execute {
            try { val next = queue.next(); main.post {
                if (!queueRunning || !foreground || next == null) { dispatching = false; return@post }
                start(next.title, next.model, queueTask = next)
            } } catch (_: Exception) { main.post { dispatching = false; queueRunning = false; state.put("error", "generation_storage_failed"); emit() } }
        }
    }
    fun recoverLibrary(source: String = "internal") { main.post {
        val command = audit.begin("recover", source)
        if (state.optBoolean("busy")) { audit.event(command, "rejected_busy"); return@post }
        state.put("busy", true); emit()
        worker.execute { audit.event(command, if (loadLibrary()) "applied" else "failed", counts = auditCounts()) }
    } }
    fun search(query: String) { main.post {
        searchQuery = query.take(500)
        worker.execute {
            try { val rows = store.list(searchQuery); main.post { state.put("library", rows); emit() } }
            catch (_: Exception) { main.post { state.put("error", "storage_recovery_failed"); emit() } }
        }
    } }
    fun select(id: String) { libraryAction { store.select(id); publishLibrary() } }
    fun delete(id: String) {
        PlaybackClient.get(context).remove(id) { removed ->
            if (removed) libraryAction { queue.cancel(id); store.delete(id); publishLibrary() }
            else { state.put("error", "storage_operation_failed"); emit() }
        }
    }
    private fun libraryAction(action: () -> Unit) { main.post {
        if (state.optBoolean("busy")) return@post
        state.put("busy", true).put("playback", "Stopped"); emit()
        worker.execute {
            try { action() } catch (_: Exception) {
                try { publishLibrary("storage_operation_failed") } catch (_: Exception) {
                    main.post { state.put("busy", false).put("error", "storage_recovery_failed"); emit() }
                }
            }
        }
    } }
    fun subscribe(listener: (String) -> Unit) { main.post { listeners.add(listener) } }
    fun unsubscribe(listener: (String) -> Unit) { main.post { listeners.remove(listener) } }
    fun snapshot(callback: (String) -> Unit) { main.post { callback(state.toString()) } }
    private fun emit() {
        state.put("revision", ++revision)
        val raw = state.toString()
        listeners.toList().forEach { it(raw) }
    }
    fun setForeground(value: Boolean) { main.post {
        foreground = value
        if (!value) cancelOnMain("lifecycle")
    } }
    fun cancel(source: String = "internal") { main.post { cancelOnMain(source) } }
    private fun cancelOnMain(source: String = "internal") {
        val command = audit.begin("pause", source)
        queueRunning = false
        worker.execute {
            try { val affected = queue.pause(); audit.event(command, "applied", affected, auditCounts()); publishQueue() }
            catch (_: Exception) { audit.event(command, "failed"); main.post { state.put("error", "generation_storage_failed"); emit() } }
        }
        activeJob?.let {
            it.cancelled.set(true)
            siwc.cancelScript(it.cancelled)
            state.put("stage", "Cancelling")
            encoder.cancel()
            emit()
        }
    }

    fun runSample() = start("Проверка русской озвучки", "", sample = true)
    fun create(topic: String, model: String) = start(topic, model)
    fun retry() { main.post {
        val id = store.selectedId
        worker.execute { val task = queue.find(id); if (task == null) start("", "", retry = true) else resumeQueue(id, "library_retry") }
    } }

    private fun start(topic: String, model: String, retry: Boolean = false, sample: Boolean = false, queueTask: GenerationTask? = null) { main.post {
        dispatching = false
        if (queueTask != null && (!queueRunning || !foreground)) return@post
        if (state.optBoolean("busy") || !state.optBoolean("storageReady")) return@post
        if (!foreground) { state.put("error", "app_not_foreground"); emit(); return@post }
        val cleanTopic = try { if (retry) "" else PodcastScript.topic(topic) }
            catch (e: SafeFailure) { state.put("error", e.safeCode); emit(); return@post }
        val job = Job(retry, cleanTopic, model, sample, queueTask)
        activeJob = job
        state.put("busy", true).put("error", "").put("stage", "PreparingVoice")
            .put("playback", "Stopped").put("positionMs", 0).put("canRetry", false)
        if (!retry) state.put("script", "")
        emit()
        worker.execute { generate(job) }
    } }

    private fun progress(job: Job, phase: String, chunk: Int = 0, total: Int = 0) {
        job.phase = phase
        job.task?.let { queue.phase(it.id, phase); publishQueue() }
        Log.i("PodcastSpike", JSONObject().put("stage", phase).put("chunk", chunk).toString())
        main.post {
            if (activeJob === job && !job.cancelled.get()) {
                state.put("stage", phase).put("chunk", chunk).put("chunks", total); emit()
            }
        }
    }

    private fun generate(job: Job) {
        var meta: JSONObject? = null
        var jobDirectory: File? = null
        val started = SystemClock.elapsedRealtime()
        try {
            store.recover()
            job.check()
            job.task?.let { task ->
                if (!queue.running(task.id)) {
                    main.post { if (activeJob === job) { activeJob = null; state.put("busy", false).put("stage", "Idle"); emit(); pump() } }
                    return
                }
                audit.event(audit.begin("start", "runner", task.id), "started", 1, auditCounts())
                if (store.hasRecord(task.id)) store.select(task.id); publishQueue()
            }
            val retry = job.retry || (job.task != null && store.hasDraft(job.task.id))
            if (!retry) {
                val id = job.task?.id ?: UUID.randomUUID().toString()
                meta = JSONObject().put("schema", 2).put("id", id).put("name", "podcast-${System.currentTimeMillis()}-${id.take(8)}.m4a")
                    .put("language", "ru").put("voice", 0).put("runtime", "sherpa-onnx 2.0.0")
                    .put("model", "supertonic-3-int8-2026-05-11").put("numSteps", 8).put("numThreads", 2)
                    .put("topic", job.topic).put("preferredModel", job.model)
                job.task?.let { task ->
                    meta.put("languagePolicy", task.profile).put("language", LanguagePolicy.tts(task.profile))
                        .put("contentSettings", JSONObject(task.settings)).put("generationInput", JSONObject(task.input))
                        .put("sourceTopicId", task.topicId).put("sourceNodeId", task.nodeId).put("promptVersion", "episode-v1")
                }
                if (job.sample) meta.put("script", PodcastScript.normalize(RussianSample.text))
                else if (job.task == null || JSONObject(job.task.input).optString("scope") == "STANDALONE") AtomicAudioJournal(File(root, "input.json")).write(JSONObject().put("topic", job.topic).put("model", job.model))
                store.begin(meta)
            } else {
                meta = PodcastDraft.read(draftJournal) ?: throw SafeFailure("no_draft")
            }
            val draft = store.draft
            jobDirectory = draft
            check(draft.mkdirs() || draft.isDirectory) { "draft_create_failed" }
            var details = requireNotNull(meta)
            val previous = store.current()
            if (previous?.optString("id") == details.getString("id")) {
                finish(job, previous, store.cleanupPending()); return
            }
            check(root.usableSpace > if (details.has("contentSettings")) EpisodeScript.requiredBytes("x".repeat(ContentSettings.parse(details.getJSONObject("contentSettings")).limits.third)) else 32L * 1024 * 1024) { "not_enough_space" }
            val codecFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 1)
            check(MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(codecFormat) != null) { "aac_unavailable" }
            val wav = File(draft, "speech.wav")
            val encoded = File(draft, "audio.m4a")
            if (!encoded.exists()) {
                if (!wav.exists()) {
                    progress(job, "PreparingVoice")
                    val prepareStart = SystemClock.elapsedRealtime()
                    val cold = tts.prepare(job::check)
                    details.put("coldInit", cold).put("prepareMs", SystemClock.elapsedRealtime() - prepareStart)
                    draftJournal.write(details)
                    val structured = details.has("contentSettings")
                    val generateText = {
                        job.check(); progress(job, "GeneratingText")
                        val textStart = SystemClock.elapsedRealtime()
                        val result = if (structured) siwc.generateScript(details.optString("preferredModel"), details.getJSONObject("generationInput").toString(),
                            EpisodeScript.instructions(details.getString("languagePolicy")), EpisodeScript.RAW_LIMIT, job.cancelled,
                            EpisodeScript.format(), requirePreferredModel = true, responseTimeoutSeconds = 240).get(300, TimeUnit.SECONDS)
                        else siwc.generateScript(details.optString("preferredModel"), details.getString("topic"), PodcastScript.INSTRUCTIONS, PodcastScript.MAX_TEXT, job.cancelled).get()
                        job.check(); result.put("textMs", SystemClock.elapsedRealtime() - textStart)
                    }
                    details = if (structured) EpisodeScript.complete(draftJournal, generateText) else PodcastScript.completedDraft(draftJournal, generateText)
                    meta = details
                    job.check()
                    val script = details.getString("script")
                    val textModel = details.optString("textModel")
                    main.post { if (activeJob === job && !job.cancelled.get()) {
                        state.put("script", script).put("selectedModel", textModel); emit()
                    } }
                    var maxPss = Debug.getPss()
                    val synthesisStart = SystemClock.elapsedRealtime()
                    val report: (Int, Int) -> Unit = { chunk, total ->
                        maxPss = maxOf(maxPss, Debug.getPss())
                        progress(job, "Synthesizing", chunk, total)
                    }
                    val audio = if (details.has("contentSettings")) tts.synthesizeDurable(script, details.getString("language"), draft, wav, job::check, report)
                        else tts.synthesize(script, wav, job::check, report)
                    audio.keys().forEach { details.put(it, audio.get(it)) }
                    details.put("synthesisMs", SystemClock.elapsedRealtime() - synthesisStart)
                        .put("observedPssKb", maxOf(maxPss, Debug.getPss()))
                    draftJournal.write(details)
                }
                job.check()
                progress(job, "Encoding")
                val encodeStart = SystemClock.elapsedRealtime()
                val partial = File(draft, "audio.part.m4a")
                check(!partial.exists() || partial.delete())
                val future = CompletableFuture<Unit>()
                main.post {
                    if (job.cancelled.get()) future.completeExceptionally(CancellationException())
                    else encoder.start(wav, partial, future)
                }
                future.get(120, TimeUnit.SECONDS)
                job.check()
                val info = M4aEncoder.inspect(partial)
                info.keys().forEach { details.put(it, info.get(it)) }
                details.put("encodingMs", SystemClock.elapsedRealtime() - encodeStart)
                check(partial.renameTo(encoded)) { "m4a_rename_failed" }
                draftJournal.write(details)
            } else {
                val info = M4aEncoder.inspect(encoded)
                info.keys().forEach { details.put(it, info.get(it)) }
            }
            job.check()
            progress(job, "Saving")
            details.put("totalMs", SystemClock.elapsedRealtime() - started)
            val episode = store.save(encoded, details, job::check)
            finish(job, episode, store.cleanupPending())
        } catch (e: Throwable) {
            // A complete publication wins over a cancellation/crash immediately after publish.
            try { store.recover() } catch (_: Exception) { /* Journal remains recoverable. */ }
            val current = try { store.current() } catch (_: Exception) { null }
            if (meta != null && current?.optString("id") == meta.optString("id")) {
                finish(job, current, true); return
            }
            val savedDraft = try { PodcastDraft.resumable(draftJournal, current?.optString("id")) }
                catch (_: Exception) { null }
            val cause = if (e is ExecutionException) e.cause ?: e else e
            val error = when {
                job.cancelled.get() || e is CancellationException -> "cancelled"
                cause is SafeFailure -> cause.safeCode
                cause is TopicFailure -> "invalid_script_schema"
                e is LinkageError -> "native_runtime_unavailable"
                e is OutOfMemoryError -> "out_of_memory"
                e.message == "not_enough_space" -> "not_enough_space"
                e.message == "aac_unavailable" -> "aac_unavailable"
                e.message == "checkpoint_mismatch" -> "checkpoint_mismatch"
                e.message == "tts_timeout" -> "tts_timeout"
                else -> "${job.phase.lowercase()}_failed"
            }
            siwc.cancelScript(job.cancelled)
            val inspectionErrors = setOf("invalid_m4a_tracks", "invalid_audio_codec", "missing_aac_config", "not_aac_lc",
                "invalid_audio_channels", "empty_m4a", "missing_audio_samples", "m4a_rename_failed")
            val detail = e.message?.takeIf { it in inspectionErrors }.orEmpty()
            Log.w("PodcastSpike", "failure=$error type=${e.javaClass.simpleName} detail=$detail")
            jobDirectory?.let { File(it, "speech.part").delete() }
            if (job.task != null) {
                val localError = error in setOf("script_markup", "script_language", "empty_script", "answer_too_large", "invalid_script_schema", "response_refused")
                val pause = !localError
                queue.stop(job.task.id, error, pause)
                audit.event(audit.begin("stop", "runner", job.task.id), if (pause) "paused" else "failed", 1, auditCounts())
                if (pause) {
                    val command = audit.begin("pause", "runner")
                    audit.event(command, "applied", queue.pause(), auditCounts()); main.post { queueRunning = false }
                }
                publishQueue()
            }
            val rows = runCatching { store.list(searchQuery) }.getOrDefault(org.json.JSONArray())
            val failedDraft = jobDirectory
            main.post {
                if (activeJob !== job) return@post
                encoder.cancel()
                failedDraft?.let { File(it, "audio.part.m4a").delete() }
                activeJob = null
                state.put("stage", if (error == "cancelled") "Cancelled" else "Failed")
                    .put("busy", false).put("error", error).put("canRetry", savedDraft != null)
                    .put("script", savedDraft?.optString("script").orEmpty())
                    .put("episode", current ?: JSONObject.NULL).put("library", rows)
                emit(); pump()
            }
        }
    }

    private fun finish(job: Job, episode: JSONObject, cleanupPending: Boolean) {
        job.task?.let { queue.complete(it.id); audit.event(audit.begin("complete", "runner", it.id), "completed", 1, auditCounts()); publishQueue() }
        store.completeDraft()
        val rows = store.list(searchQuery)
        val log = PodcastScript.diagnostics(episode)
        Log.i("PodcastSpike", "completed=$log cleanupPending=$cleanupPending")
        main.post {
            if (activeJob !== job) return@post
            activeJob = null
            state.put("stage", "Ready").put("busy", false).put("canRetry", false)
                .put("episode", episode).put("error", if (cleanupPending) "cleanup_pending" else "")
                .put("script", "")
                .put("durationMs", episode.optLong("durationMs")).put("library", rows)
            emit(); pump()
        }
    }

    fun play() { main.post {
        state.optJSONObject("episode")?.optString("id")?.let { PlaybackClient.get(context).playEpisode(it) }
    } }
    fun pause() = PlaybackClient.get(context).pause()
    fun restart() = PlaybackClient.get(context).seek(0.0)
    fun openExternal() { main.post {
        if (state.optBoolean("busy")) return@post
        val uriString = state.optJSONObject("episode")?.optString("uri").orEmpty()
        if (uriString.isEmpty()) return@post
        PlaybackClient.get(context).pause()
        try {
            val uri = Uri.parse(uriString)
            context.contentResolver.openFileDescriptor(uri, "r")?.close() ?: error("missing_file")
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri("Podcast", uri)
            context.startActivity(Intent.createChooser(intent, "Открыть аудио")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            Log.i("PodcastSpike", "external_open_requested")
        } catch (_: android.content.ActivityNotFoundException) {
            state.put("error", "external_player_unavailable"); emit()
        } catch (_: Exception) { state.put("error", "file_unavailable"); emit() }
    } }
}
