package dev.podcast.siwcspike.siwc

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import dev.podcast.siwcspike.podcast.PodcastModule

class SiwcPackage : BaseReactPackage() {
    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
        when (name) {
            SiwcModule.NAME -> SiwcModule(reactContext)
            PodcastModule.NAME -> PodcastModule(reactContext)
            else -> null
        }

    override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
        mapOf(
            SiwcModule.NAME to ReactModuleInfo(SiwcModule.NAME, SiwcModule::class.java.name, false, false, false, true),
            PodcastModule.NAME to ReactModuleInfo(PodcastModule.NAME, PodcastModule::class.java.name, false, false, false, true)
        )
    }
}
