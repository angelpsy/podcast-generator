package dev.podcast.siwcspike.podcast

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class SherpaAudioCallbackTest {
    @Test fun exposesExactConcreteMethodLookedUpBySherpaJni() {
        val callback = SherpaAudioCallback {}
        val method = callback.javaClass.getDeclaredMethod("invoke", FloatArray::class.java)
        assertEquals(java.lang.Integer::class.java, method.returnType)
        assertEquals(1, method.invoke(callback, floatArrayOf(0f)))
    }
    @Test fun cancellationReturnsStopWithoutThrowingThroughJni() {
        val callback = SherpaAudioCallback { throw CancellationException() }
        assertEquals(0, callback(floatArrayOf(0f)))
    }
}
