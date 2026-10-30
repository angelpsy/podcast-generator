package dev.podcast.siwcspike.podcast

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dev.podcast.siwcspike.MainActivity
import org.json.JSONObject
import java.util.concurrent.Executors

/** Owns playback independently of React/Activity and resolves all input through the library. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    companion object { const val REMOVE = "remove-library-item" }
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val dao by lazy { PodcastDatabases.get(this).podcasts() }
    private var restored = false
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).setSeekBackIncrementMs(15_000).setSeekForwardIncrementMs(15_000).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        session = MediaSession.Builder(this, player).setCallback(Callback())
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        addSession(session)
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) { persist() }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && session.sessionExtras.getString("error") == "file_unavailable") notice("")
            }
            override fun onPlayerError(error: PlaybackException) {
                val failed = player.currentMediaItem?.mediaId
                val resume = player.playWhenReady
                notice(if (player.mediaItemCount > 1) "queue_items_skipped" else "file_unavailable")
                // A broken item is removed from a multi-item queue, so repeat cannot loop over it.
                main.post {
                    if (destroyed || player.currentMediaItem?.mediaId != failed) return@post
                    if (player.mediaItemCount > 1) {
                        val index = player.currentMediaItemIndex
                        val hasNext = index + 1 < player.mediaItemCount
                        val wrap = player.repeatMode == Player.REPEAT_MODE_ALL
                        player.removeMediaItem(index)
                        if (hasNext || wrap) {
                            player.seekToDefaultPosition(if (hasNext) index else 0)
                            player.prepare()
                            if (resume) player.play()
                        } else player.pause()
                    } else player.pause() // Retain the last URI for an explicit P-03 retry.
                }
            }
        })
        io.execute {
            try {
                val saved = SavedPlayback.parse(dao.playback())
                val items = resolve(saved.ids)
                val ids = items.map { it.mediaId }
                main.post {
                    if (destroyed) return@post
                    player.setMediaItems(items, saved.resolvedIndex(ids), saved.resolvedPosition(ids))
                    player.setPlaybackSpeed(saved.speed); player.repeatMode = saved.repeat
                    restored = true; persist() // Restore paused; no prepare/play on app startup.
                }
            } catch (_: Exception) { main.post { if (!destroyed) { notice("playback_restore_failed") } } }
        }
        main.post(object : Runnable {
            override fun run() { if (!destroyed) { persist(); main.postDelayed(this, 5000) } }
        })
    }

    private fun resolve(ids: List<String>): List<MediaItem> {
        require(ids.size <= 1000)
        var skipped = false
        val result = ids.distinct().mapNotNull { id ->
            val record = dao.record(id)
            if (record == null || record.status !in setOf("READY", "FILE_MISSING")) { skipped = true; return@mapNotNull null }
            val meta = dao.asset(id)?.let { JSONObject(it.journal).optJSONObject("current") }
            val uri = meta?.optString("uri").orEmpty()
            if (uri.isEmpty()) { skipped = true; return@mapNotNull null }
            // A null query is unknown; don't reclassify or remove the saved queue on provider failure.
            if (!AndroidAudioMedia(this).isPublished(uri)) {
                dao.status(id, "FILE_MISSING"); skipped = true; return@mapNotNull null
            }
            dao.status(id, "READY")
            MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(MediaMetadata.Builder()
                .setTitle(record.title).setArtist("Podcast Generator").setDurationMs(meta!!.optLong("durationMs"))
                .build()).build()
        }
        main.post { if (!destroyed) notice(if (skipped) "queue_items_skipped" else "") }
        return result
    }
    private fun notice(code: String) { session.setSessionExtras(Bundle().apply { putString("error", code) }) }
    private fun persist() {
        if (!restored || destroyed) return
        val saved = SavedPlayback((0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId },
            player.currentMediaItem?.mediaId.orEmpty(), player.currentPosition.coerceAtLeast(0),
            player.playbackParameters.speed, player.repeatMode).json()
        io.execute {
            try { dao.playback(PlaybackRecord(snapshot = saved)) }
            catch (_: Exception) { main.post { if (!destroyed) notice("playback_save_failed") } }
        }
    }
    private inner class Callback : MediaSession.Callback {
        override fun onConnect(s: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val own = controller.uid == applicationInfo.uid
            val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            if (!own) commands.remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM)
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            if (own) sessionCommands.add(SessionCommand(REMOVE, Bundle.EMPTY))
            return MediaSession.ConnectionResult.AcceptedResultBuilder(s, controller)
                .setAvailablePlayerCommands(commands.build()).setAvailableSessionCommands(sessionCommands.build()).build()
        }
        override fun onAddMediaItems(s: MediaSession, c: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
            if (c.uid != applicationInfo.uid) return Futures.immediateFailedFuture(SecurityException())
            val future = SettableFuture.create<List<MediaItem>>()
            io.execute {
                try {
                    val items = resolve(mediaItems.map { it.mediaId })
                    main.post { if (!destroyed) restored = true }
                    future.set(items)
                }
                catch (e: Exception) {
                    main.post { if (!destroyed) notice("queue_load_failed") }
                    future.setException(e)
                }
            }
            return future
        }
        override fun onPlaybackResumption(s: MediaSession, c: MediaSession.ControllerInfo):
            ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            io.execute {
                try {
                    val saved = SavedPlayback.parse(dao.playback())
                    val items = resolve(saved.ids)
                    val ids = items.map { it.mediaId }
                    main.post { if (!destroyed) restored = true }
                    future.set(MediaSession.MediaItemsWithStartPosition(items,
                        saved.resolvedIndex(ids), saved.resolvedPosition(ids)))
                } catch (e: Exception) { future.setException(e) }
            }
            return future
        }
        override fun onCustomCommand(s: MediaSession, c: MediaSession.ControllerInfo,
            command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (c.uid != applicationInfo.uid || command.customAction != REMOVE)
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            val id = args.getString("id")
            for (index in player.mediaItemCount - 1 downTo 0)
                if (player.getMediaItemAt(index).mediaId == id) player.removeMediaItem(index)
            persist()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onDestroy() {
        persist(); destroyed = true; main.removeCallbacksAndMessages(null)
        session.release(); player.release(); io.shutdown(); super.onDestroy()
    }
}
