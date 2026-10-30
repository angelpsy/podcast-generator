package dev.podcast.siwcspike.podcast

import androidx.room.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Callable

@Entity(tableName = "playlists")
data class PlaylistRecord(@PrimaryKey val id: String, val title: String, val revision: Long = 0, val createdAt: Long, val updatedAt: Long)
@Entity(tableName = "playlist_entries", primaryKeys = ["playlistId", "podcastId"], foreignKeys = [
    ForeignKey(entity = PlaylistRecord::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = PodcastRecord::class, parentColumns = ["id"], childColumns = ["podcastId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("podcastId")])
data class PlaylistEntry(val playlistId: String, val podcastId: String, val position: Int)
@Dao interface PlaylistDao {
    @Insert fun insert(row: PlaylistRecord)
    @Update fun update(row: PlaylistRecord)
    @Insert fun insert(row: PlaylistEntry)
    @Update fun update(row: PlaylistEntry)
    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC, id") fun all(): List<PlaylistRecord>
    @Query("SELECT * FROM playlists WHERE id=:id") fun find(id: String): PlaylistRecord?
    @Query("SELECT * FROM playlist_entries WHERE playlistId=:id ORDER BY position, podcastId") fun entries(id: String): List<PlaylistEntry>
    @Query("DELETE FROM playlists WHERE id=:id") fun delete(id: String)
    @Query("DELETE FROM playlist_entries WHERE playlistId=:id AND podcastId=:podcast") fun remove(id: String, podcast: String)
    @Query("UPDATE playlists SET revision=revision+1, updatedAt=:now WHERE id IN (SELECT playlistId FROM playlist_entries WHERE podcastId=:podcast)") fun invalidatePodcast(podcast: String, now: Long)
}
class PlaylistRepository(private val db: PodcastDatabase) {
    private val dao = db.playlists()
    private fun <T> tx(block: () -> T): T = db.runInTransaction(Callable { block() })
    private fun row(id: String) = dao.find(id) ?: throw TopicFailure("playlist_not_found")
    private fun base(p: PlaylistRecord) = JSONObject().put("id", p.id).put("title", p.title).put("revision", p.revision)
    private fun episode(id: String): JSONObject {
        val p = db.podcasts().record(id) ?: throw TopicFailure("podcast_not_found")
        val meta = db.podcasts().asset(id)?.let { JSONObject(it.journal).optJSONObject("current") }
        return JSONObject().put("id", id).put("title", p.title).put("status", p.status).put("durationMs", meta?.optLong("durationMs") ?: 0)
            .put("profile", meta?.optString("languagePolicy", "ru_standard") ?: "ru_standard")
    }
    fun list(): JSONObject = tx {
        JSONObject().put("manual", JSONArray(dao.all().map { p -> base(p).put("count", dao.entries(p.id).size) }))
            .put("topics", TopicRepository(db).list().getJSONArray("topics"))
    }
    fun detail(id: String): JSONObject = tx {
        base(row(id)).put("items", JSONArray(dao.entries(id).map { episode(it.podcastId).put("position", it.position) }))
    }
    fun playIds(id: String): List<String> = tx {
        row(id); dao.entries(id).map { it.podcastId }.filter { db.podcasts().record(it)?.status == "READY" }
            .also { if (it.isEmpty()) throw TopicFailure("empty_playlist"); if (it.size > 1000) throw TopicFailure("playlist_limit") }
    }
    fun topicDetail(topic: String, node: String, allProfiles: Boolean): JSONObject = tx {
        val snapshot = TopicRepository(db).snapshot(topic, node)
        val t = db.topics().topic(topic) ?: throw TopicFailure("topic_not_found")
        val selected = snapshot.getString("selectedNodeId")
        val links = db.topics().links(topic).filter { allProfiles || it.profile == t.languagePolicy }
        snapshot.put("playlist", JSONArray(TopicTree.playlist(db.topics().nodes(topic), links, selected).map(::episode)))
    }
    fun topicIds(topic: String, node: String, allProfiles: Boolean): List<String> = tx {
        val items = topicDetail(topic, node, allProfiles).getJSONArray("playlist")
        (0 until items.length()).map { items.getJSONObject(it) }.filter { it.getString("status") == "READY" }.map { it.getString("id") }
            .also { if (it.isEmpty()) throw TopicFailure("empty_playlist"); if (it.size > 1000) throw TopicFailure("playlist_limit") }
    }
    fun mutate(request: JSONObject): JSONObject = tx {
        val action = request.getString("action")
        if (action == "create" || action == "copyTopic") {
            val title = TopicTree.text(request.getString("title"), 200); val now = System.currentTimeMillis()
            val ids = if (action == "copyTopic") {
                val topic = request.getString("topicId"); val t = db.topics().topic(topic) ?: throw TopicFailure("topic_not_found")
                if (request.getDouble("expectedRevision") != t.revision.toDouble()) throw TopicFailure("revision_conflict")
                val a = topicDetail(topic, request.optString("nodeId"), request.optBoolean("allProfiles")).getJSONArray("playlist")
                (0 until a.length()).map { a.getJSONObject(it).getString("id") }
            } else emptyList()
            if (ids.size > 1000) throw TopicFailure("playlist_limit")
            val p = PlaylistRecord(UUID.randomUUID().toString(), title, createdAt = now, updatedAt = now)
            dao.insert(p); ids.forEachIndexed { i, id -> dao.insert(PlaylistEntry(p.id, id, i)) }; return@tx detail(p.id)
        }
        val p = row(request.getString("id")); val revision = request.getDouble("expectedRevision")
        if (revision != p.revision.toDouble()) throw TopicFailure("revision_conflict")
        val items = dao.entries(p.id)
        when (action) {
            "rename" -> dao.update(p.copy(title = TopicTree.text(request.getString("title"), 200)))
            "delete" -> { dao.delete(p.id); return@tx JSONObject().put("deleted", true) }
            "add" -> {
                val id = request.getString("podcastId")
                if (items.any { it.podcastId == id }) return@tx detail(p.id)
                val record = db.podcasts().record(id) ?: throw TopicFailure("podcast_not_found")
                if (record.status !in setOf("READY", "FILE_MISSING")) throw TopicFailure("podcast_not_ready")
                if (items.size >= 1000) throw TopicFailure("playlist_limit")
                dao.insert(PlaylistEntry(p.id, id, (items.maxOfOrNull { it.position } ?: -1) + 1))
            }
            "remove" -> dao.remove(p.id, request.getString("podcastId"))
            "move" -> {
                val id = request.getString("podcastId"); val item = items.find { it.podcastId == id } ?: throw TopicFailure("podcast_not_linked")
                val position = request.getDouble("position")
                if (!position.isFinite() || position % 1 != 0.0 || position !in 0.0..(items.size - 1).toDouble()) throw TopicFailure("invalid_position")
                val next = items.filter { it.podcastId != id }.toMutableList(); next.add(position.toInt(), item)
                next.forEachIndexed { index, entry -> dao.update(entry.copy(position = index)) }
            }
            else -> throw TopicFailure("invalid_request")
        }
        val renamed = dao.find(p.id)!!
        dao.update(renamed.copy(revision = p.revision + 1, updatedAt = System.currentTimeMillis())); detail(p.id)
    }
}
