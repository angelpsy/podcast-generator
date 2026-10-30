package dev.podcast.siwcspike.podcast

import android.app.Activity
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.io.File

/** Test variant only. Starts playback through an app-owned controller while the Activity is visible. */
class StageZeroPlaybackActivity : Activity() {
    private var controller: MediaController? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Этап 0: подключаем системный плеер…"; textSize = 20f })
        val folder = File(getExternalFilesDir(null), "stage0")
        val names = intent.getStringExtra("files").orEmpty().split(',')
        val items = names.map { name ->
            require(name.matches(Regex("[a-zA-Z0-9_-]+\\.m4a")))
            val file = File(folder, name)
            require(file.exists())
            MediaItem.Builder().setMediaId(name).setUri(Uri.fromFile(file))
                .setMediaMetadata(MediaMetadata.Builder().setTitle("Stage 0 · $name")
                    .setArtist("Podcast Generator").build()).build()
        }
        val future = MediaController.Builder(this,
            SessionToken(this, ComponentName(this, StageZeroPlaybackService::class.java))).buildAsync()
        future.addListener({
            val active = future.get()
            if (isDestroyed) { active.release(); return@addListener }
            controller = active
            active.repeatMode = if (intent.getBooleanExtra("repeat", false)) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            active.setMediaItems(items); active.prepare(); active.play()
            setContentView(TextView(this).apply { text = "Этап 0: воспроизведение через MediaController"; textSize = 20f })
            // Prove that playback outlives this controller and Activity, not only Home with a bound UI.
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 5000)
        }, mainExecutor)
    }
    override fun onDestroy() { controller?.release(); controller = null; super.onDestroy() }
}
