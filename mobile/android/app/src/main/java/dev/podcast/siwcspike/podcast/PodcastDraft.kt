package dev.podcast.siwcspike.podcast

import org.json.JSONObject

/** Read the durable draft, not a JSONObject that may never have reached disk. */
internal object PodcastDraft {
    fun read(journal: AudioJournal): JSONObject? = journal.read().takeIf {
        val topic = it.optString("topic").trim()
        it.optInt("schema") == 2 && it.optString("id").isNotBlank() &&
            it.optString("name").isNotBlank() && topic.isNotEmpty() && topic.codePointCount(0, topic.length) <= 500
    }

    fun resumable(journal: AudioJournal, currentId: String?): JSONObject? =
        read(journal)?.takeUnless { it.getString("id") == currentId }
}
