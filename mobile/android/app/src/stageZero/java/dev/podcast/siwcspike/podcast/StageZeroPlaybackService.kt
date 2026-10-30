package dev.podcast.siwcspike.podcast

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONObject
import java.io.File

/** Stage-zero experiment only; not packaged in release. No access to the podcast journal. */
class StageZeroPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val handler = Handler(Looper.getMainLooper())
    private var started = 0L
    private var errors = 0
    private var lastError = ""
    private var samples = 0
    private var screenOffSamples = 0
    private var transitions = 0
    private val folder by lazy { File(getExternalFilesDir(null), "stage0").apply { mkdirs() } }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    errors++; lastError = error.errorCodeName; record()
                }
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) { transitions++ }
            })
        }
        session = MediaSession.Builder(this, player).setId("stage-zero-probe").build()
        // Shell-started experiment has no MediaController binding to invoke onGetSession.
        addSession(session)
        started = SystemClock.elapsedRealtime()
        handler.post(object : Runnable {
            override fun run() { record(); handler.postDelayed(this, 5000) }
        })
    }

    private fun record() {
        if (started == 0L) return
        samples++
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        if (!interactive) screenOffSamples++
        AtomicAudioJournal(File(folder, "playback.json")).write(JSONObject()
            .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("isPlaying", player.isPlaying)
            .put("state", player.playbackState).put("index", player.currentMediaItemIndex)
            .put("mediaId", player.currentMediaItem?.mediaId.orEmpty()).put("positionMs", player.currentPosition)
            .put("durationMs", player.duration).put("errors", errors).put("lastError", lastError)
            .put("samples", samples).put("screenOffSamples", screenOffSamples)
            .put("interactive", interactive).put("transitions", transitions).put("speed", player.playbackParameters.speed))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra("command")) {
            "play" -> {
                val names = intent.getStringExtra("files").orEmpty().split(',')
                val items = names.map { name ->
                    require(name.matches(Regex("[a-zA-Z0-9_-]+\\.m4a")))
                    val file = File(folder, name)
                    require(file.exists())
                    MediaItem.Builder().setMediaId(name).setUri(android.net.Uri.fromFile(file))
                        .setMediaMetadata(MediaMetadata.Builder().setTitle("Stage 0 · $name")
                            .setArtist("Podcast Generator").build()).build()
                }
                started = SystemClock.elapsedRealtime(); errors = 0; samples = 0; screenOffSamples = 0; transitions = 0
                player.repeatMode = if (intent.getBooleanExtra("repeat", false)) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                player.setMediaItems(items); player.prepare(); player.play(); record()
            }
            "pause" -> { player.pause(); record() }
            "resume" -> { playPodcast(player, false); record() }
            "seek" -> { player.seekTo(intent.getLongExtra("position", 0)); record() }
            "stop" -> { player.stop(); record(); stopSelf() }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        record(); handler.removeCallbacksAndMessages(null)
        player.release(); session.release(); super.onDestroy()
    }
}
