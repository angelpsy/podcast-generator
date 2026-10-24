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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
    private class Job(val retry: Boolean, val topic: String, val model: String, val sample: Boolean) {
        val cancelled = AtomicBoolean(false)
        var phase = "PreparingVoice"
        fun check() { if (cancelled.get()) throw CancellationException() }
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val root = File(context.noBackupFilesDir, "podcast").apply { mkdirs() }
    private val draft = File(root, "draft")
    private val draftJournal = AtomicAudioJournal(File(draft, "meta.json"))
    private val store = LatestAudioStore(AtomicAudioJournal(File(root, "current.json")), AndroidAudioMedia(context))
    private val tts = LocalTtsEngine(context)
    private val siwc = SiwcCoordinator.get(context)
    private val encoder = M4aEncoder(context)
    private val listeners = linkedSetOf<(String) -> Unit>()
    private var foreground = false
    private var activeJob: Job? = null
    private var player: ExoPlayer? = null
    private var playerUri = ""
    private var revision = 0L
    private val state = JSONObject().put("stage", "Loading").put("busy", true)
        .put("error", "").put("script", "").put("inputTopic", "").put("selectedModel", "").put("playback", "Stopped")
        .put("canRetry", false).put("positionMs", 0).put("durationMs", 0)

    init {
        worker.execute {
            var error = ""
            try { store.recover() } catch (_: Exception) { error = "storage_recovery_failed" }
            val current = try { store.current() } catch (_: Exception) { null }
            val pending = try { PodcastDraft.read(draftJournal) }
                catch (_: Exception) { error = "storage_recovery_failed"; null }
            val retry = pending != null && pending.getString("id") != current?.optString("id")
            // Only a confirmed publication makes its private draft obsolete. A read failure must not delete it.
            if (pending != null && !retry) draft.deleteRecursively()
            val input = runCatching { AtomicAudioJournal(File(root, "input.json")).read() }.getOrDefault(JSONObject())
            main.post {
                state.put("busy", false).put("stage", if (retry) "DraftReady" else if (current != null) "Ready" else "Idle")
                    .put("error", error).put("canRetry", retry).put("episode", current ?: JSONObject.NULL)
                    .put("inputTopic", input.optString("topic")).put("selectedModel", input.optString("model"))
                    .put("script", if (retry) pending?.optString("script").orEmpty() else "")
                emit()
            }
        }
    }
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
        if (!value) { player?.pause(); cancelOnMain() }
    } }
    fun cancel() { main.post { cancelOnMain() } }
    private fun cancelOnMain() {
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
    fun retry() = start("", "", retry = true)

    private fun start(topic: String, model: String, retry: Boolean = false, sample: Boolean = false) { main.post {
        if (state.optBoolean("busy")) return@post
        if (!foreground) { state.put("error", "app_not_foreground"); emit(); return@post }
        val cleanTopic = try { if (retry) "" else PodcastScript.topic(topic) }
            catch (e: SafeFailure) { state.put("error", e.safeCode); emit(); return@post }
        player?.stop(); player?.clearMediaItems(); playerUri = ""
        val job = Job(retry, cleanTopic, model, sample)
        activeJob = job
        state.put("busy", true).put("error", "").put("stage", "PreparingVoice")
            .put("playback", "Stopped").put("positionMs", 0).put("canRetry", false)
        if (!retry) state.put("script", "")
        emit()
        worker.execute { generate(job) }
    } }

    private fun progress(job: Job, phase: String, chunk: Int = 0, total: Int = 0) {
        job.phase = phase
        Log.i("PodcastSpike", JSONObject().put("stage", phase).put("chunk", chunk).toString())
        main.post {
            if (activeJob === job && !job.cancelled.get()) {
                state.put("stage", phase).put("chunk", chunk).put("chunks", total); emit()
            }
        }
    }

    private fun generate(job: Job) {
        var meta: JSONObject? = null
        val started = SystemClock.elapsedRealtime()
        try {
            store.recover()
            job.check()
            if (!job.retry) {
                check(draft.deleteRecursively() || !draft.exists()) { "draft_cleanup_failed" }
                check(draft.mkdirs()) { "draft_create_failed" }
                val id = UUID.randomUUID().toString()
                meta = JSONObject().put("schema", 2).put("id", id).put("name", "podcast-${System.currentTimeMillis()}-${id.take(8)}.m4a")
                    .put("language", "ru").put("voice", 0).put("runtime", "sherpa-onnx 2.0.0")
                    .put("model", "supertonic-3-int8-2026-05-11").put("numSteps", 8).put("numThreads", 2)
                    .put("topic", job.topic).put("preferredModel", job.model)
                if (job.sample) meta.put("script", PodcastScript.normalize(RussianSample.text))
                else AtomicAudioJournal(File(root, "input.json")).write(JSONObject().put("topic", job.topic).put("model", job.model))
                draftJournal.write(meta)
            } else {
                meta = PodcastDraft.read(draftJournal) ?: throw SafeFailure("no_draft")
            }
            var details = requireNotNull(meta)
            val previous = store.current()
            if (previous?.optString("id") == details.getString("id")) {
                finish(job, previous, store.cleanupPending()); return
            }
            check(root.usableSpace > 32L * 1024 * 1024) { "not_enough_space" }
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
                    details = PodcastScript.completedDraft(draftJournal) {
                        job.check()
                        progress(job, "GeneratingText")
                        val textStart = SystemClock.elapsedRealtime()
                        val result = siwc.generateScript(details.optString("preferredModel"), details.getString("topic"),
                            PodcastScript.INSTRUCTIONS, PodcastScript.MAX_TEXT, job.cancelled).get()
                        job.check()
                        result.put("textMs", SystemClock.elapsedRealtime() - textStart)
                    }
                    meta = details
                    job.check()
                    val script = details.getString("script")
                    val textModel = details.optString("textModel")
                    main.post { if (activeJob === job && !job.cancelled.get()) {
                        state.put("script", script).put("selectedModel", textModel); emit()
                    } }
                    var maxPss = Debug.getPss()
                    val synthesisStart = SystemClock.elapsedRealtime()
                    val audio = tts.synthesize(script, wav, job::check) { chunk, total ->
                        maxPss = maxOf(maxPss, Debug.getPss())
                        progress(job, "Synthesizing", chunk, total)
                    }
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
                e is LinkageError -> "native_runtime_unavailable"
                e is OutOfMemoryError -> "out_of_memory"
                e.message == "not_enough_space" -> "not_enough_space"
                e.message == "aac_unavailable" -> "aac_unavailable"
                else -> "${job.phase.lowercase()}_failed"
            }
            val inspectionErrors = setOf("invalid_m4a_tracks", "invalid_audio_codec", "missing_aac_config", "not_aac_lc",
                "invalid_audio_channels", "empty_m4a", "missing_audio_samples", "m4a_rename_failed")
            val detail = e.message?.takeIf { it in inspectionErrors }.orEmpty()
            Log.w("PodcastSpike", "failure=$error type=${e.javaClass.simpleName} detail=$detail")
            File(draft, "speech.part").delete()
            main.post {
                if (activeJob !== job) return@post
                encoder.cancel()
                File(draft, "audio.part.m4a").delete()
                activeJob = null
                state.put("stage", if (error == "cancelled") "Cancelled" else "Failed")
                    .put("busy", false).put("error", error).put("canRetry", savedDraft != null)
                    .put("script", savedDraft?.optString("script").orEmpty())
                    .put("episode", current ?: JSONObject.NULL)
                emit()
            }
        }
    }

    private fun finish(job: Job, episode: JSONObject, cleanupPending: Boolean) {
        draft.deleteRecursively()
        val log = PodcastScript.diagnostics(episode)
        Log.i("PodcastSpike", "completed=$log cleanupPending=$cleanupPending")
        main.post {
            if (activeJob !== job) return@post
            activeJob = null
            state.put("stage", "Ready").put("busy", false).put("canRetry", false)
                .put("episode", episode).put("error", if (cleanupPending) "cleanup_pending" else "")
                .put("script", "")
                .put("durationMs", episode.optLong("durationMs"))
            emit()
        }
    }

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(context).build().also { p ->
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        p.setHandleAudioBecomingNoisy(true)
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state.put("playback", if (isPlaying) "Playing" else "Paused"); emit()
                if (isPlaying) main.post(object : Runnable {
                    override fun run() {
                        state.put("positionMs", p.currentPosition)
                        if (p.duration > 0) state.put("durationMs", p.duration)
                        emit()
                        if (p.isPlaying) main.postDelayed(this, 500)
                    }
                })
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    state.put("playback", "Ended").put("positionMs", p.currentPosition); emit()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                state.put("error", "playback_failed"); emit()
            }
        })
        player = p
    }
    fun play() { main.post {
        if (!foreground || state.optBoolean("busy")) return@post
        val uri = state.optJSONObject("episode")?.optString("uri").orEmpty()
        if (uri.isEmpty()) return@post
        try {
            context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.close() ?: error("missing_file")
            val p = ensurePlayer()
            val mediaChanged = playerUri != uri
            if (mediaChanged) {
                p.setMediaItem(MediaItem.fromUri(uri)); playerUri = uri
            }
            state.put("error", ""); playPodcast(p, mediaChanged)
        } catch (_: Exception) { state.put("error", "file_unavailable"); emit() }
    } }
    fun pause() { main.post { player?.pause() } }
    fun restart() { main.post { player?.seekTo(0); state.put("positionMs", 0); emit() } }
    fun openExternal() { main.post {
        if (state.optBoolean("busy")) return@post
        val uriString = state.optJSONObject("episode")?.optString("uri").orEmpty()
        if (uriString.isEmpty()) return@post
        player?.pause()
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
