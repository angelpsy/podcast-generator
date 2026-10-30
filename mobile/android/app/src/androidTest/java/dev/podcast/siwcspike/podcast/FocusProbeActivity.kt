package dev.podcast.siwcspike.podcast

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView

/** Separate test APK/UID. Requests focus without playing sound, then returns it automatically. */
class FocusProbeActivity : Activity() {
    private var request: AudioFocusRequest? = null
    private val main = Handler(Looper.getMainLooper())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Проверка временной передачи аудиофокуса…"; textSize = 20f })
    }
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (!isTopResumedActivity || request != null) return
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ }, main).build()
        request = focus
        getSystemService(AudioManager::class.java).requestAudioFocus(focus)
        main.postDelayed({ finishAndRemoveTask() }, 2500)
    }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        request?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        super.onDestroy()
    }
}
