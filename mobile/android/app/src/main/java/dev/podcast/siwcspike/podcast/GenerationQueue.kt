package dev.podcast.siwcspike.podcast

import androidx.room.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Callable

@Entity(tableName = "generation_queue", indices = [Index("batchId"), Index(value = ["nodeId", "profile"])])
data class GenerationTask(@PrimaryKey val id: String, val batchId: String, val title: String, val topicId: String,
    val nodeId: String, val profile: String, val settings: String, val input: String, val fingerprint: String,
    val model: String, val status: String = "QUEUED", val phase: String = "QUEUED", val error: String = "",
    val createdAt: Long, val attempt: Int = 0)
@Dao interface GenerationQueueDao {
    @Insert fun insert(task: GenerationTask)
    @Update fun update(task: GenerationTask)
    @Query("SELECT * FROM generation_queue WHERE id=:id") fun find(id: String): GenerationTask?
    @Query("SELECT * FROM generation_queue ORDER BY createdAt, id") fun all(): List<GenerationTask>
    @Query("SELECT * FROM generation_queue WHERE batchId=:id") fun batch(id: String): List<GenerationTask>
    @Query("UPDATE generation_queue SET status='PAUSED' WHERE status IN ('RUNNING','QUEUED')") fun pause(): Int
}
/** Durable queue and preview, no network/filesystem. Target IDs intentionally have no FK cascade. */
class GenerationQueue(private val db: PodcastDatabase) {
    private val dao = db.generationQueue(); private val topics = db.topics()
    private fun <T> tx(block: () -> T): T = db.runInTransaction(Callable { block() })
    private val activeStatuses = setOf("QUEUED", "RUNNING", "PAUSED", "FAILED")
    private fun source(topicId: String, nodeId: String, scope: String): JSONObject {
        val t = topics.topic(topicId) ?: throw TopicFailure("topic_not_found")
        val nodes = topics.nodes(topicId); val n = TopicTree.node(nodes, nodeId)
        return JSONObject().put("topicTitle", t.title).put("goal", t.goal).put("scope", scope)
            .put("title", n.title).put("description", n.description).put("nodeKind", n.kind)
            .put("path", JSONArray(TopicTree.path(nodes, n.id).map { p -> JSONObject().put("id", p.id).put("title", p.title).put("description", p.description) }))
            .put("children", JSONArray(if (scope == "OVERVIEW") TopicTree.children(nodes, n.id).map { JSONObject().put("id", it.id).put("title", it.title).put("description", it.description).put("kind", it.kind) } else emptyList<JSONObject>()))
    }
    private fun resolved(request: JSONObject): Pair<String, ContentSettings> {
        val topic = request.optString("topicId").takeIf { it.isNotBlank() }?.let { topics.topic(it) ?: throw TopicFailure("topic_not_found") }
        return LanguagePolicy.validate(request.optString("profile").ifBlank { topic?.languagePolicy ?: "ru_standard" }) to
            ContentSettings.parse(request.optJSONObject("settings") ?: topic?.contentSettings?.takeIf { it.isNotBlank() }?.let(::JSONObject))
    }
    private fun previewLocked(request: JSONObject): JSONObject {
        val (profile, settings) = resolved(request); val mode = request.getString("mode")
        val model = TopicTree.text(request.getString("model"), 200)
        val items = JSONArray(); var ready = 0; var active = 0; var overflow = 0
        if (mode == "STANDALONE") {
            val title = PodcastScript.topic(request.getString("title"))
            items.put(JSONObject().put("nodeId", "").put("title", title).put("source", JSONObject().put("title", title).put("scope", "STANDALONE")))
        } else {
            if (mode !in setOf("SINGLE", "BATCH")) throw TopicFailure("invalid_request")
            val t = topics.topic(request.getString("topicId")) ?: throw TopicFailure("topic_not_found")
            if (request.getDouble("expectedRevision") != t.revision.toDouble()) throw TopicFailure("revision_conflict")
            val nodes = topics.nodes(t.id); val target = TopicTree.node(nodes, request.getString("nodeId"))
            val selected = if (mode == "SINGLE") listOf(target) else TopicTree.subtree(nodes, target.id).filter { it.kind == "QUESTION" || (request.optBoolean("includeSections") && it.kind == "SECTION") }
            val links = topics.links(t.id); val pending = dao.all()
            selected.forEach { n ->
                val override = request.optJSONObject("overrides")?.optJSONObject(n.id)
                val itemProfile = LanguagePolicy.validate(override?.optString("profile")?.ifBlank { profile } ?: profile)
                val itemSettings = ContentSettings.parse(override?.optJSONObject("settings") ?: settings.json())
                when {
                    pending.any { it.nodeId == n.id && it.profile == itemProfile && it.status in activeStatuses } -> active++
                    !request.optBoolean("additional") && links.any { it.nodeId == n.id && it.profile == itemProfile && it.role == "MAIN" && db.podcasts().record(it.podcastId)?.status == "READY" } -> ready++
                    items.length() >= 20 -> overflow++
                    else -> items.put(JSONObject().put("nodeId", n.id).put("title", n.title).put("profile", itemProfile).put("settings", itemSettings.json()).put("source", source(t.id, n.id, if (n.kind == "QUESTION") "QUESTION" else "OVERVIEW")))
                }
            }
        }
        val result = JSONObject().put("profile", profile).put("settings", settings.json()).put("model", model)
            .put("items", items).put("skippedReady", ready).put("skippedActive", active).put("remaining", overflow)
        return result.put("token", ContentHash.of(result.toString()))
    }
    fun preview(request: JSONObject) = tx { previewLocked(request) }
    fun enqueue(request: JSONObject): JSONObject = tx {
        val batchId = request.getString("requestId").also { if (!it.matches(Regex("[A-Za-z0-9-]{8,80}"))) throw TopicFailure("invalid_request") }
        val existing = dao.batch(batchId)
        if (existing.isNotEmpty()) return@tx JSONObject().put("ids", JSONArray(existing.map { it.id })).put("added", 0)
        val p = previewLocked(request)
        if (p.getString("token") != request.getString("previewToken")) throw TopicFailure("revision_conflict")
        val items = p.getJSONArray("items"); if (items.length() == 0) throw TopicFailure("empty_generation_batch")
        val tasks = dao.all()
        if (tasks.count { it.status in activeStatuses } + items.length() > 100) throw TopicFailure("generation_queue_limit")
        // A batch reserves consecutive ordering timestamps. A quick next append
        // (or clock adjustment) must not place new items before that batch's tail.
        val ids = JSONArray(); val now = maxOf(System.currentTimeMillis(), (tasks.maxOfOrNull { it.createdAt } ?: 0L) + 1)
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i); val id = UUID.randomUUID().toString(); val input = item.getJSONObject("source")
            val settings = ContentSettings.parse(item.optJSONObject("settings") ?: p.getJSONObject("settings")); val limits = settings.limits
            val profile = item.optString("profile", p.getString("profile")); val fingerprint = ContentHash.of(input.toString())
            input.put("languagePolicy", profile).put("contentSettings", settings.json()).put("targetWordsMin", limits.first)
                .put("targetWordsMax", limits.second).put("hardMaxChars", limits.third).put("immersion", settings.immersion)
                .put("intent", settings.intent).put("audience", settings.audience).put("promptVersion", "episode-v1")
            dao.insert(GenerationTask(id, batchId, item.getString("title"), request.optString("topicId"), item.getString("nodeId"),
                profile, settings.json().toString(), input.toString(), fingerprint, p.getString("model"), createdAt = now + i))
            ids.put(id)
        }
        JSONObject().put("ids", ids).put("added", items.length())
    }
    fun next() = dao.all().firstOrNull { it.status == "QUEUED" }
    fun find(id: String) = dao.find(id)
    fun auditCounts() = dao.all().groupingBy { it.status }.eachCount()
    fun running(id: String): Boolean = tx {
        val task = dao.find(id)?.takeIf { it.status == "QUEUED" } ?: return@tx false
        dao.update(task.copy(status = "RUNNING", error = "", attempt = task.attempt + 1)); true
    }
    fun phase(id: String, value: String) { dao.find(id)?.let { dao.update(it.copy(phase = value)) } }
    fun pause() = dao.pause()
    fun stop(id: String, error: String, paused: Boolean) { dao.find(id)?.takeIf { it.status != "CANCELLED" }?.let { dao.update(it.copy(status = if (paused) "PAUSED" else "FAILED", error = error)) } }
    fun resume(id: String = "") = tx {
        val targets = dao.all().filter { (id.isEmpty() && it.status == "PAUSED") || (it.id == id && it.status in setOf("PAUSED", "FAILED", "CANCELLED")) }
        targets.forEach { dao.update(it.copy(status = "QUEUED", error = "")) }; targets.size
    }
    fun cancel(id: String): Boolean = tx {
        val task = dao.find(id)?.takeIf { it.status !in setOf("COMPLETED", "RUNNING") } ?: return@tx false
        dao.update(task.copy(status = "CANCELLED")); true
    }
    fun recover() = tx { dao.pause(); dao.all().filter { db.podcasts().record(it.id)?.status == "READY" && it.status != "COMPLETED" }.forEach { complete(it.id) } }
    fun complete(id: String) = tx {
        val task = dao.find(id) ?: return@tx
        if (task.status == "COMPLETED") return@tx
        var notice = ""
        if (task.nodeId.isNotEmpty()) {
            val old = JSONObject(task.input)
            val current = runCatching { source(task.topicId, task.nodeId, old.getString("scope")) }.getOrNull()
            if (current == null || ContentHash.of(current.toString()) != task.fingerprint) notice = "source_changed_detached"
            else {
                val links = topics.links(task.topicId).filter { it.nodeId == task.nodeId }
                if (links.size >= 50) notice = "link_limit_detached"
                else if (links.none { it.podcastId == id }) {
                    val previousMain = links.find { it.profile == task.profile && it.role == "MAIN" }
                    val main = previousMain == null || db.podcasts().record(previousMain.podcastId)?.status != "READY"
                    if (main && previousMain != null) topics.link(previousMain.copy(role = "EXTRA", mainSlot = null, position = links.count { it.role == "EXTRA" }))
                    topics.link(NodePodcastRecord(task.nodeId, id, if (main) "MAIN" else "EXTRA", task.profile,
                        if (main) 0 else links.count { it.role == "EXTRA" }, if (main) 1 else null))
                    topics.topic(task.topicId)?.let { topics.update(it.copy(revision = it.revision + 1, updatedAt = System.currentTimeMillis())) }
                }
            }
        }
        dao.update(task.copy(status = "COMPLETED", phase = "COMPLETED", error = notice))
    }
    fun json() = JSONArray().apply { dao.all().filter { it.status != "COMPLETED" && it.status != "CANCELLED" }.forEach { t ->
        put(JSONObject().put("id", t.id).put("title", t.title).put("status", t.status).put("phase", t.phase).put("error", t.error)
            .put("profile", t.profile).put("settings", JSONObject(t.settings)).put("hasScript", db.podcasts().job(t.id)?.let { JSONObject(it.metadata).has("script") } ?: false))
    } }
}
