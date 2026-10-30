package dev.podcast.siwcspike.podcast

import android.view.WindowManager
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.annotations.ReactModule
import dev.podcast.siwcspike.siwc.NativePodcastSpec
import org.json.JSONObject

@ReactModule(name = PodcastModule.NAME)
class PodcastModule(context: ReactApplicationContext) : NativePodcastSpec(context), LifecycleEventListener {
    companion object { const val NAME = "Podcast" }
    private val coordinator = PodcastCoordinator.get(context)
    private val listener: (String) -> Unit = { raw ->
        if (JSONObject(raw).optBoolean("busy")) reactApplicationContext.currentActivity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else reactApplicationContext.currentActivity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        emitOnSnapshot(raw)
    }
    override fun getName() = NAME
    override fun initialize() {
        super.initialize()
        coordinator.subscribe(listener)
        reactApplicationContext.addLifecycleEventListener(this)
    }
    override fun invalidate() {
        coordinator.unsubscribe(listener)
        coordinator.setForeground(false)
        reactApplicationContext.removeLifecycleEventListener(this)
        super.invalidate()
    }
    override fun onHostResume() = coordinator.setForeground(true)
    override fun onHostPause() {
        reactApplicationContext.currentActivity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        coordinator.setForeground(false)
    }
    override fun onHostDestroy() = coordinator.setForeground(false)
    override fun getSnapshot(promise: Promise) = coordinator.snapshot { promise.resolve(it) }
    override fun previewGeneration(request: String, promise: Promise) = coordinator.previewGeneration(request) { value, error -> if (error == null) promise.resolve(value) else promise.reject(error, "Не удалось проверить очередь") }
    override fun enqueueGeneration(request: String, promise: Promise) = coordinator.enqueueGeneration(request, "ui") { value, error -> if (error == null) promise.resolve(value) else promise.reject(error, "Не удалось сохранить очередь") }
    override fun resumeQueue(id: String) = coordinator.resumeQueue(id, "ui")
    override fun cancelTask(id: String) = coordinator.cancelTask(id, "ui")
    override fun create(topic: String, model: String) = coordinator.create(topic, model)
    override fun runRussianSample() = coordinator.runSample()
    override fun retryAudio() = coordinator.retry()
    override fun selectEpisode(id: String) = coordinator.select(id)
    override fun deleteEpisode(id: String) = coordinator.delete(id)
    override fun searchLibrary(query: String) = coordinator.search(query)
    override fun recoverLibrary() = coordinator.recoverLibrary("ui")
    override fun cancel() = coordinator.cancel("ui")
    override fun play() = coordinator.play()
    override fun pause() = coordinator.pause()
    override fun restartPlayback() = coordinator.restart()
    override fun openExternal() = coordinator.openExternal()
}
