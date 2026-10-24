package dev.podcast.siwcspike.siwc

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.module.annotations.ReactModule

@ReactModule(name = SiwcModule.NAME)
class SiwcModule(context: ReactApplicationContext) : NativeSiwcSpec(context), LifecycleEventListener {
    companion object { const val NAME = "Siwc" }
    private val coordinator = SiwcCoordinator.get(context)
    private val listener: (String) -> Unit = { emitOnSnapshot(it) }

    override fun getName() = NAME
    override fun initialize() {
        super.initialize()
        coordinator.subscribe(listener)
        reactApplicationContext.addLifecycleEventListener(this)
    }
    override fun invalidate() {
        coordinator.unsubscribe(listener)
        reactApplicationContext.removeLifecycleEventListener(this)
        super.invalidate()
    }
    override fun onHostResume() = coordinator.setForeground(true)
    override fun onHostPause() = coordinator.setForeground(false)
    override fun onHostDestroy() = coordinator.setForeground(false)
    private fun completion(promise: Promise): (String?) -> Unit = { error ->
        if (error == null) promise.resolve(null) else promise.reject(error, error)
    }
    override fun getSnapshot(promise: Promise) = coordinator.load { snapshot, error ->
        if (error == null) promise.resolve(snapshot) else promise.reject(error, error)
    }
    override fun signIn(promise: Promise) = coordinator.signIn(completion(promise))
    override fun refreshSession(promise: Promise) = coordinator.refreshSession(completion(promise))
    override fun cancel() = coordinator.cancel()
    override fun listModels(promise: Promise) = coordinator.listModels(completion(promise))
    override fun sendPrompt(model: String, text: String, promise: Promise) = coordinator.sendPrompt(model, text, completion(promise))
    override fun signOut(promise: Promise) = coordinator.signOut(completion(promise))
}
