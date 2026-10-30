package dev.podcast.siwcspike.podcast

import org.json.JSONArray
import org.json.JSONObject

/** Only stable library IDs and playback settings are persisted; never URIs or auth data. */
data class SavedPlayback(val ids: List<String> = emptyList(), val currentId: String = "",
    val position: Long = 0, val speed: Float = 1f, val repeat: Int = 0) {
    fun json() = JSONObject().put("ids", JSONArray(ids)).put("currentId", currentId)
        .put("position", position.coerceAtLeast(0)).put("speed", speed).put("repeat", repeat).toString()
    fun resolvedIndex(available: List<String>) = available.indexOf(currentId).coerceAtLeast(0)
    fun resolvedPosition(available: List<String>) = if (currentId in available) position else 0L
    companion object {
        fun parse(raw: String?): SavedPlayback {
            if (raw == null) return SavedPlayback()
            val json = JSONObject(raw)
            val array = json.getJSONArray("ids")
            require(array.length() <= 1000)
            val ids = (0 until array.length()).map { array.getString(it) }.distinct()
            return SavedPlayback(ids, json.optString("currentId"), json.optLong("position").coerceAtLeast(0),
                json.optDouble("speed", 1.0).toFloat().takeIf { it.isFinite() && it in 0.5f..2f } ?: 1f,
                json.optInt("repeat").takeIf { it in 0..2 } ?: 0)
        }
    }
}
