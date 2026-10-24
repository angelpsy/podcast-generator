package dev.podcast.siwcspike.podcast

import androidx.annotation.Keep

/** sherpa JNI looks up invoke(float[]): java.lang.Integer on the concrete class.
 * Kotlin 2.x indy lambdas only expose the erased invoke(Object): Object method.
 * Keep a named class with a typed method; never let exceptions escape into JNI.
 */
@Keep
class SherpaAudioCallback(private val checkpoint: () -> Unit) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = try {
        checkpoint()
        1
    } catch (_: Exception) {
        0
    }
}
