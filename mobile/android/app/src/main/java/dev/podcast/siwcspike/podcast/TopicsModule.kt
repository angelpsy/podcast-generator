package dev.podcast.siwcspike.podcast

import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.annotations.ReactModule
import dev.podcast.siwcspike.siwc.NativeTopicsSpec
import org.json.JSONObject
import java.util.concurrent.Executors

@ReactModule(name = TopicsModule.NAME)
class TopicsModule(context: ReactApplicationContext) : NativeTopicsSpec(context), LifecycleEventListener {
    companion object { const val NAME = "Topics" }
    private val worker = Executors.newSingleThreadExecutor()
    private val outlines = OutlineCoordinator.get(context)
    private val outlineRepository by lazy { OutlineRepository(PodcastDatabases.get(context)) }
    override fun initialize() { super.initialize(); reactApplicationContext.addLifecycleEventListener(this) }
    override fun onHostResume() = outlines.foreground(true)
    override fun onHostPause() = outlines.foreground(false)
    override fun onHostDestroy() = outlines.foreground(false)
    private val repository by lazy { TopicRepository(PodcastDatabases.get(context)) }
    override fun getName() = NAME
    private fun task(promise: Promise, block: () -> JSONObject) { worker.execute {
        try { promise.resolve(block().toString()) }
        catch (e: TopicFailure) { promise.reject(e.code, "Не удалось изменить топик") }
        catch (_: org.json.JSONException) { promise.reject("invalid_request", "Некорректная команда") }
        catch (_: Exception) { promise.reject("topic_storage_failed", "Не удалось прочитать или сохранить структуру") }
    } }
    override fun getTopics(promise: Promise) = task(promise) { repository.list() }
    override fun getTopic(id: String, nodeId: String, promise: Promise) = task(promise) { repository.snapshot(id, nodeId) }
    override fun mutate(request: String, promise: Promise) = task(promise) {
        if (request.length > 16_000) throw TopicFailure("invalid_request")
        val json = JSONObject(request)
        if (json.optInt("schema") != 1) throw TopicFailure("invalid_request")
        repository.mutate(json).also { outlines.cancelIfDeleted() }
    }
    override fun moveTargets(topicId: String, nodeId: String, promise: Promise) = task(promise) { repository.moveTargets(topicId, nodeId) }
    override fun searchPodcasts(query: String, offset: Double, promise: Promise) = task(promise) {
        if (!offset.isFinite() || offset % 1.0 != 0.0 || offset !in 0.0..100_000.0) throw TopicFailure("invalid_search")
        repository.search(query, offset.toInt())
    }
    override fun play(topicId: String, nodeId: String, promise: Promise) { worker.execute {
        try {
            val ids = repository.playlist(topicId, nodeId)
            PlaybackClient.get(reactApplicationContext).playQueue(ids) { ok ->
                if (ok) promise.resolve(null) else promise.reject("queue_load_failed", "Не удалось загрузить очередь")
            }
        } catch (e: TopicFailure) { promise.reject(e.code, "Не удалось открыть плейлист") }
        catch (_: Exception) { promise.reject("topic_storage_failed", "Не удалось прочитать плейлист") }
    } }
    private fun revision(value: Double): Long {
        if (!value.isFinite() || value % 1 != 0.0 || value !in 0.0..9_007_199_254_740_991.0) throw TopicFailure("invalid_request")
        return value.toLong()
    }
    override fun requestOutline(request: String, promise: Promise) { worker.execute {
        try {
            if (request.length > 16_000) throw TopicFailure("invalid_request")
            val json = JSONObject(request)
            if (json.optInt("schema") != 1) throw TopicFailure("invalid_request")
            outlines.start(json, { promise.resolve(it) }, { promise.reject(it, "Не удалось запустить запрос структуры") })
        } catch (_: Exception) { promise.reject("invalid_request", "Некорректные параметры структуры") }
    } }
    override fun getOutlines(topicId: String, nodeId: String, promise: Promise) = task(promise) { outlineRepository.list(topicId, nodeId, outlines.busy()) }
    override fun previewOutline(id: String, promise: Promise) = task(promise) { outlineRepository.preview(id) }
    override fun reviewOutline(id: String, value: Double, promise: Promise) = task(promise) { outlineRepository.review(id, revision(value)) }
    override fun applyOutline(id: String, value: Double, promise: Promise) = task(promise) { outlineRepository.apply(id, revision(value)) }
    override fun discardOutline(id: String, promise: Promise) { worker.execute {
        try { outlineRepository.discard(id); promise.resolve(null) }
        catch (e: TopicFailure) { promise.reject(e.code, "Предложение недоступно") }
        catch (_: Exception) { promise.reject("topic_storage_failed", "Не удалось сохранить действие") }
    } }
    override fun cancelOutline(id: String) = outlines.cancel(id)
    override fun invalidate() { reactApplicationContext.removeLifecycleEventListener(this); outlines.foreground(false); worker.shutdown(); super.invalidate() }
}
