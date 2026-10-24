package dev.podcast.siwcspike.podcast

import android.database.Cursor
import java.lang.reflect.Proxy

/** One-column Cursor without an Android runtime or mocking dependency. null value means zero rows. */
internal class TestMediaCursor(private val value: Any?) {
    var closed = false
    val cursor = Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, _ ->
        when (method.name) {
            "moveToFirst" -> value != null
            "getLong" -> (value as Number).toLong()
            "getInt" -> (value as Number).toInt()
            "getString" -> value as String
            "close" -> { closed = true; null }
            "toString" -> "TestMediaCursor"
            else -> error("Unexpected Cursor call: ${method.name}")
        }
    } as Cursor
}
