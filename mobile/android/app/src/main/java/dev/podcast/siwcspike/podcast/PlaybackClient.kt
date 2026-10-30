package dev.podcast.siwcspike.podcast

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.*
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** UI connection only. Releasing this controller never pauses the service. Main looper only. */
class PlaybackClient private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: PlaybackClient? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: PlaybackClient(context.applicationContext).also { instance = it }
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private val pending = mutableListOf<Pair<(MediaController) -> Unit, () -> Unit>>()
    private val listeners = linkedSetOf<(String) -> Unit>()
    private var visible = false
    private var revision = 0L
    private var error = ""
    private var ticking = false
    private fun connect(failed: () -> Unit = {}, action: (MediaController) -> Unit = {}) {
        controller?.let { action(it); return }
        pending.add(action to failed)
        if (future != null) return
        val connection = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(c: MediaController, extras: Bundle) { error = extras.getString("error").orEmpty(); emit() }
                override fun onDisconnected(c: MediaController) {
                    if (controller === c) { controller = null; future = null; error = "playback_disconnected"; emit() }
                }
            }).buildAsync()
        future = connection
        connection.addListener({
            if (future !== connection) return@addListener
            try {
                val c = connection.get(); controller = c
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { emit() }
                })
                error = c.sessionExtras.getString("error").orEmpty()
                val actions = pending.toList(); pending.clear(); actions.forEach { it.first(c) }; emit()
            } catch (_: Exception) { future = null; val actions = pending.toList(); pending.clear(); actions.forEach { it.second() }; error = "playback_connection_failed"; emit() }
        }, context.mainExecutor)
    }
    fun subscribe(listener: (String) -> Unit) { main.post { listeners.add(listener); connect(); emit(); tick() } }
    fun unsubscribe(listener: (String) -> Unit) { main.post {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            future?.let { MediaController.releaseFuture(it) }; controller = null; future = null; pending.clear()
        }
    } }
    fun foreground(value: Boolean) { main.post {
        visible = value
        if (value) { connect(); tick() } else {
            future?.let { MediaController.releaseFuture(it) }
            controller = null; future = null
            val actions = pending.toList(); pending.clear(); actions.forEach { it.second() }
        }
    } }
    private fun tick() {
        if (ticking || !visible || listeners.isEmpty()) return
        ticking = true
        main.postDelayed({ ticking = false; if (visible && listeners.isNotEmpty()) { emit(); tick() } }, 500)
    }
    fun snapshot(callback: (String) -> Unit) { main.post { connect(); callback(json()) } }
    private fun emit() { val raw = json(); listeners.toList().forEach { it(raw) } }
    private fun json(): String {
        val c = controller
        val queue = JSONArray()
        if (c != null) for (i in 0 until c.mediaItemCount) {
            val item = c.getMediaItemAt(i)
            queue.put(JSONObject().put("id", item.mediaId).put("title", item.mediaMetadata.title?.toString().orEmpty()))
        }
        return JSONObject().put("revision", ++revision).put("connected", c != null)
            .put("playing", c?.isPlaying == true).put("id", c?.currentMediaItem?.mediaId.orEmpty())
            .put("title", c?.mediaMetadata?.title?.toString().orEmpty()).put("queue", queue)
            .put("index", c?.currentMediaItemIndex ?: 0).put("positionMs", c?.currentPosition?.coerceAtLeast(0) ?: 0)
            .put("durationMs", c?.duration?.takeIf { it > 0 } ?: c?.mediaMetadata?.durationMs ?: 0)
            .put("speed", c?.playbackParameters?.speed ?: 1f).put("repeat", c?.repeatMode ?: 0)
            .put("error", error.ifEmpty { if (c?.playerError != null) "file_unavailable" else "" }).toString()
    }
    private fun command(action: (MediaController) -> Unit) { main.post {
        connect { c -> try { action(c); emit() } catch (_: Exception) { error = "playback_command_failed"; emit() } }
    } }
    fun playEpisode(id: String) = command { c ->
        error = ""
        if (c.currentMediaItem?.mediaId != id) {
            c.setMediaItem(MediaItem.Builder().setMediaId(id).build()); playPodcast(c, true)
        } else playPodcast(c, false)
    }
    fun playQueue(ids: List<String>, complete: (Boolean) -> Unit) { main.post {
        if (ids.isEmpty() || ids.size > 1000) { complete(false); return@post }
        connect(failed = { complete(false) }) { c ->
            try {
                error = ""
                c.setMediaItems(ids.distinct().map { MediaItem.Builder().setMediaId(it).build() })
                playPodcast(c, true); complete(true); emit()
            } catch (_: Exception) { error = "queue_load_failed"; complete(false); emit() }
        }
    } }
    fun updateQueue(ids: List<String>, complete: (Boolean) -> Unit) { main.post {
        if (ids.isEmpty() || ids.size > 1000) { complete(false); return@post }
        connect(failed = { complete(false) }) { c ->
            try {
                val next = ids.distinct(); val index = next.indexOf(c.currentMediaItem?.mediaId)
                val position = if (index >= 0) c.currentPosition.coerceAtLeast(0) else 0L
                val playing = c.playWhenReady
                c.setMediaItems(next.map { MediaItem.Builder().setMediaId(it).build() }, index.coerceAtLeast(0), position)
                if (playing) playPodcast(c, true) else { c.prepare(); c.pause() }
                error = ""; complete(true); emit()
            } catch (_: Exception) { error = "queue_load_failed"; complete(false); emit() }
        }
    } }
    fun playLibrary() { io.execute {
        try {
            val ids = PodcastDatabases.get(context).podcasts().all().filter { it.status == "READY" }.map { it.id }
            command { c ->
                require(ids.isNotEmpty() && ids.size <= 1000)
                c.setMediaItems(ids.map { MediaItem.Builder().setMediaId(it).build() }); playPodcast(c, true)
            }
        } catch (_: Exception) { main.post { error = "queue_load_failed"; emit() } }
    } }
    fun play() = command { playPodcast(it, false) }
    fun pause() = command { it.pause() }
    fun seek(position: Double) = command { c ->
        require(position.isFinite()); c.seekTo(position.toLong().coerceIn(0, c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
    }
    fun skip(delta: Double) = command { c ->
        require(delta.isFinite() && delta in -60_000.0..60_000.0)
        c.seekTo((c.currentPosition + delta.toLong()).coerceIn(0, c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
    }
    fun previous() = command { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0) }
    fun next() = command { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    fun speed(value: Double) = command { require(value.isFinite() && value in 0.5..2.0); it.setPlaybackSpeed(value.toFloat()) }
    fun repeat(value: Double) = command { require(value in 0.0..2.0 && value % 1.0 == 0.0); it.repeatMode = value.toInt() }
    fun remove(id: String, complete: (Boolean) -> Unit) { main.post {
        connect(failed = { complete(false) }) { c ->
            val result = c.sendCustomCommand(SessionCommand(PlaybackService.REMOVE, Bundle.EMPTY), Bundle().apply { putString("id", id) })
            result.addListener({ complete(runCatching { result.get().resultCode == SessionResult.RESULT_SUCCESS }.getOrDefault(false)) }, context.mainExecutor)
        }
    } }
}
