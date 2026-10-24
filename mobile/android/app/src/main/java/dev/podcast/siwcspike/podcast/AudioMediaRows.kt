package dev.podcast.siwcspike.podcast

import android.database.Cursor
import java.io.IOException

/** A failed query is not an empty result. Never discard recovery state on an unknown result. */
internal object AudioMediaRows {
    private fun required(cursor: Cursor?): Cursor = cursor ?: throw IOException("media_query_failed")

    fun find(cursor: Cursor?, uriForId: (Long) -> String): String? = required(cursor).use {
        if (it.moveToFirst()) uriForId(it.getLong(0)) else null
    }

    fun isPublished(cursor: Cursor?): Boolean = required(cursor).use {
        it.moveToFirst() && it.getInt(0) == 0
    }

    fun deleteOwned(cursor: Cursor?, owner: String, delete: () -> Int) {
        required(cursor).use {
            if (!it.moveToFirst()) return // Only a successful empty query confirms absence.
            check(it.getString(0) == owner) { "not_owned_audio" }
        }
        check(delete() > 0) { "media_delete_failed" }
    }
}
