package dev.podcast.siwcspike.siwc

/** Keeps one-time OAuth code exchange out of a background/cached app process. */
internal class ForegroundGate {
    private val monitor = Object()
    @Volatile var foreground = false
        private set

    fun setForeground(value: Boolean) = synchronized(monitor) {
        foreground = value
        monitor.notifyAll()
    }

    fun await(timeoutMillis: Long, cancelled: () -> Boolean) = synchronized(monitor) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (true) {
            if (cancelled()) throw SafeFailure("cancelled")
            if (foreground) return@synchronized
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) throw SafeFailure("app_return_timeout")
            monitor.wait(minOf(remaining, 100))
        }
    }
}
