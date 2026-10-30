package dev.podcast.siwcspike.podcast

import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import dev.podcast.siwcspike.siwc.NativePlaybackSpec

@ReactModule(name = PlaybackModule.NAME)
class PlaybackModule(context: ReactApplicationContext) : NativePlaybackSpec(context), LifecycleEventListener {
    companion object { const val NAME = "Playback" }
    private val client = PlaybackClient.get(context)
    private val listener: (String) -> Unit = { emitOnSnapshot(it) }
    override fun getName() = NAME
    override fun initialize() { super.initialize(); client.subscribe(listener); reactApplicationContext.addLifecycleEventListener(this) }
    override fun invalidate() { client.unsubscribe(listener); reactApplicationContext.removeLifecycleEventListener(this); super.invalidate() }
    override fun onHostResume() = client.foreground(true)
    override fun onHostPause() = client.foreground(false)
    override fun onHostDestroy() = client.foreground(false)
    override fun getSnapshot(promise: Promise) = client.snapshot { promise.resolve(it) }
    override fun playEpisode(id: String) = client.playEpisode(id)
    override fun playLibrary() = client.playLibrary()
    override fun play() = client.play()
    override fun pause() = client.pause()
    override fun seek(positionMs: Double) = client.seek(positionMs)
    override fun skip(deltaMs: Double) = client.skip(deltaMs)
    override fun previous() = client.previous()
    override fun next() = client.next()
    override fun setSpeed(speed: Double) = client.speed(speed)
    override fun setRepeat(mode: Double) = client.repeat(mode)
}
