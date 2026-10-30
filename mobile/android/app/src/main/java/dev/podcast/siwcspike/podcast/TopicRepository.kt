package dev.podcast.siwcspike.podcast

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Callable

/** Serial catalog worker; every read snapshot and mutation is a Room transaction. */
class TopicRepository(private val db: PodcastDatabase) {
    private val dao = db.topics()
    private val podcasts = db.podcasts()
    private fun <T> tx(block: () -> T): T = db.runInTransaction(Callable { block() })
    private fun topic(id: String) = dao.topic(id) ?: throw TopicFailure("topic_not_found")
    private fun int(json: JSONObject, key: String, max: Int = 200): Int {
        val value = json.getDouble(key)
        if (!value.isFinite() || value % 1.0 != 0.0 || value !in 0.0..max.toDouble()) throw TopicFailure("invalid_position")
        return value.toInt()
    }
    private fun touch(t: TopicRecord) = dao.update(t.copy(revision = t.revision + 1, updatedAt = System.currentTimeMillis()))
    fun list(): JSONObject = tx {
        JSONObject().put("schema", 1).put("topics", JSONArray().apply {
            dao.all().forEach { t -> put(topicJson(t).put("nodeCount", dao.nodes(t.id).size)
                .put("podcastCount", dao.links(t.id).map { it.podcastId }.distinct().size)) }
        })
    }
    private fun topicJson(t: TopicRecord) = JSONObject().put("id", t.id).put("rootNodeId", t.rootNodeId)
        .put("title", t.title).put("goal", t.goal).put("outlineDepth", t.outlineDepth).put("revision", t.revision).put("languagePolicy", t.languagePolicy).put("contentSettings", ContentSettings.parse(t.contentSettings.takeIf { it.isNotBlank() }?.let(::JSONObject)).json())
    fun snapshot(id: String, branchId: String = ""): JSONObject = tx { snapshotOnWorker(topic(id), branchId) }
    private fun snapshotOnWorker(t: TopicRecord, branchId: String): JSONObject {
        val nodes = dao.nodes(t.id)
        val branch = nodes.find { it.id == branchId }?.id ?: t.rootNodeId
        val links = dao.links(t.id)
        val records = links.map { it.podcastId }.distinct().mapNotNull { podcasts.record(it) }.associateBy { it.id }
        val durations = records.keys.associateWith { id -> podcasts.asset(id)?.let { JSONObject(it.journal).optJSONObject("current")?.optLong("durationMs") } ?: 0L }
        fun podcastJson(id: String): JSONObject {
            val p = records.getValue(id)
            return JSONObject().put("id", id).put("title", p.title).put("status", p.status)
                .put("createdAt", p.createdAt).put("durationMs", durations.getValue(id))
        }
        return JSONObject().put("schema", 1).put("topic", topicJson(t)).put("selectedNodeId", branch)
            .put("nodes", JSONArray().apply { nodes.forEach { n -> put(JSONObject().put("id", n.id)
                .put("parentId", n.parentId ?: JSONObject.NULL).put("kind", n.kind).put("title", n.title)
                .put("description", n.description).put("position", n.position).put("revision", n.revision)) } })
            .put("links", JSONArray().apply { links.forEach { l -> put(podcastJson(l.podcastId)
                .put("nodeId", l.nodeId).put("role", l.role).put("profile", l.profile).put("position", l.position)) } })
            .put("playlist", JSONArray().apply { TopicTree.playlist(nodes, links.filter { it.profile == t.languagePolicy }, branch).forEach { put(podcastJson(it)) } })
    }
    fun moveTargets(id: String, nodeId: String): JSONObject = tx {
        val nodes = dao.nodes(topic(id).id)
        TopicTree.node(nodes, nodeId)
        JSONObject().put("targets", JSONArray().apply { nodes.forEach { candidate ->
            try {
                TopicTree.checkMove(nodes, nodeId, candidate.id)
                put(JSONObject().put("id", candidate.id).put("title", TopicTree.path(nodes, candidate.id).joinToString(" / ") { it.title }))
            } catch (_: TopicFailure) { /* A target that violates a tree invariant is not offered. */ }
        } })
    }
    fun search(query: String, offset: Int): JSONObject = tx {
        if (offset !in 0..100_000 || query.length > 1000) throw TopicFailure("invalid_search")
        val found = dao.searchPodcasts(LibrarySearch.pattern(query), offset)
        JSONObject().put("hasMore", found.size > 50).put("items", JSONArray().apply {
            found.take(50).forEach {
                val duration = podcasts.asset(it.id)?.let { asset -> JSONObject(asset.journal).optJSONObject("current")?.optLong("durationMs") } ?: 0L
                put(JSONObject().put("id", it.id).put("title", it.title).put("status", it.status)
                    .put("createdAt", it.createdAt).put("durationMs", duration).put("profile", podcasts.asset(it.id)?.let { asset -> JSONObject(asset.journal).optJSONObject("current")?.optString("languagePolicy", "ru_standard") } ?: "ru_standard"))
            }
        })
    }
    fun playlist(id: String, nodeId: String): List<String> = tx {
        val t = topic(id); val nodes = dao.nodes(id)
        val ids = TopicTree.playlist(nodes, dao.links(id).filter { it.profile == t.languagePolicy }, nodeId.ifEmpty { t.rootNodeId })
            .filter { podcasts.record(it)?.status == "READY" }
        if (ids.isEmpty()) throw TopicFailure("empty_playlist")
        if (ids.size > 1000) throw TopicFailure("playlist_limit")
        ids
    }
    fun mutate(request: JSONObject): JSONObject = tx {
        val action = request.getString("action")
        if (action == "createTopic") {
            val id = UUID.randomUUID().toString(); val root = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val depth = int(request, "outlineDepth", 4).also { if (it < 1) throw TopicFailure("invalid_depth") }
            val title = TopicTree.text(request.getString("title"), 200)
            val goal = TopicTree.text(request.optString("goal"), 2000, false)
            val t = TopicRecord(id, root, title, goal, depth, 0, now, now, LanguagePolicy.validate(request.optString("languagePolicy", "ru_standard")), ContentSettings.parse(request.optJSONObject("contentSettings")).json().toString())
            dao.insert(t); dao.insert(TopicNodeRecord(root, id, null, "ROOT", title, goal, rootSlot = 1))
            return@tx snapshotOnWorker(t, root)
        }
        val t = topic(request.getString("topicId"))
        val expected = request.getDouble("expectedRevision")
        if (!expected.isFinite() || expected % 1.0 != 0.0 || expected != t.revision.toDouble()) throw TopicFailure("revision_conflict")
        if (action == "deleteTopic") { dao.deleteTopic(t.id); return@tx JSONObject().put("deleted", true) }
        val nodes = dao.nodes(t.id)
        fun node() = TopicTree.node(nodes, request.getString("nodeId"))
        fun normalize(parentId: String) {
            TopicTree.children(dao.nodes(t.id), parentId).forEachIndexed { i, n -> dao.update(n.copy(position = i)) }
        }
        when (action) {
            "editTopic" -> {
                val title = TopicTree.text(request.getString("title"), 200)
                val goal = TopicTree.text(request.optString("goal"), 2000, false)
                val depth = int(request, "outlineDepth", 4).also { if (it < 1) throw TopicFailure("invalid_depth") }
                dao.update(t.copy(title = title, goal = goal, outlineDepth = depth, languagePolicy = LanguagePolicy.validate(request.optString("languagePolicy", t.languagePolicy)), contentSettings = ContentSettings.parse(request.optJSONObject("contentSettings") ?: t.contentSettings.takeIf { it.isNotBlank() }?.let(::JSONObject)).json().toString()))
                val root = TopicTree.node(nodes, t.rootNodeId)
                dao.update(root.copy(title = title, description = goal, revision = root.revision + 1))
            }
            "addNode" -> {
                val parent = request.getString("parentId"); TopicTree.checkParent(nodes, parent)
                if (nodes.size >= TopicTree.MAX_NODES) throw TopicFailure("node_limit")
                val kind = request.getString("kind").also { if (it !in setOf("SECTION", "QUESTION")) throw TopicFailure("invalid_kind") }
                dao.insert(TopicNodeRecord(UUID.randomUUID().toString(), t.id, parent, kind,
                    TopicTree.text(request.getString("title"), 200), TopicTree.text(request.optString("description"), 2000, false),
                    TopicTree.children(nodes, parent).size))
            }
            "editNode" -> {
                val n = node(); if (n.parentId == null) throw TopicFailure("root_locked")
                val kind = request.getString("kind").also { if (it !in setOf("SECTION", "QUESTION")) throw TopicFailure("invalid_kind") }
                if (kind == "QUESTION" && TopicTree.children(nodes, n.id).isNotEmpty()) throw TopicFailure("question_has_no_children")
                dao.update(n.copy(title = TopicTree.text(request.getString("title"), 200),
                    description = TopicTree.text(request.optString("description"), 2000, false), kind = kind, revision = n.revision + 1))
            }
            "deleteNode" -> {
                val n = node(); if (n.parentId == null) throw TopicFailure("root_locked")
                dao.deleteNode(n.id); normalize(n.parentId)
            }
            "moveNode" -> {
                val n = node(); val parent = request.getString("parentId")
                TopicTree.checkMove(nodes, n.id, parent)
                val siblings = TopicTree.children(nodes, parent).filter { it.id != n.id }.toMutableList()
                val index = int(request, "position").also { if (it > siblings.size) throw TopicFailure("invalid_position") }
                siblings.add(index, n.copy(parentId = parent, revision = n.revision + 1))
                siblings.forEachIndexed { i, s -> dao.update(s.copy(position = i)) }
                if (n.parentId != parent) normalize(requireNotNull(n.parentId))
            }
            "attach", "makeMain", "detach", "moveLink" -> {
                val n = node(); val id = request.getString("podcastId")
                val all = dao.links(t.id).filter { it.nodeId == n.id }
                val existing = all.find { it.podcastId == id }
                when (action) {
                    "detach" -> dao.unlink(n.id, id)
                    "moveLink" -> {
                        val link = existing ?: throw TopicFailure("podcast_not_linked")
                        if (link.role == "MAIN") throw TopicFailure("main_is_first")
                        val extras = TopicTree.orderedLinks(all.filter { it.role == "EXTRA" && it.podcastId != id }).toMutableList()
                        val pos = int(request, "position").also { if (it > extras.size) throw TopicFailure("invalid_position") }
                        extras.add(pos, link); extras.forEachIndexed { i, l -> dao.link(l.copy(position = i)) }
                    }
                    else -> {
                        val p = podcasts.record(id) ?: throw TopicFailure("podcast_not_found")
                        if (p.status !in setOf("READY", "FILE_MISSING")) throw TopicFailure("podcast_not_ready")
                        val meta = podcasts.asset(id)?.let { JSONObject(it.journal).optJSONObject("current") }
                        if (meta == null || meta.optString("uri").isBlank()) throw TopicFailure("podcast_not_ready")
                        val profile = meta.optString("languagePolicy").ifEmpty { "ru_standard" }
                        LanguagePolicy.validate(profile)
                        if (action == "makeMain" && existing == null) throw TopicFailure("podcast_not_linked")
                        if (action == "attach" && existing != null) return@tx snapshotOnWorker(t, request.optString("viewNodeId"))
                        if (existing == null && all.size >= 50) throw TopicFailure("link_limit")
                        val primary = all.find { it.role == "MAIN" && it.profile == profile }
                        val makeMain = action == "makeMain" || primary == null
                        if (makeMain && primary != null && primary.podcastId != id)
                            dao.link(primary.copy(role = "EXTRA", mainSlot = null, position = all.count { it.role == "EXTRA" }))
                        dao.link(NodePodcastRecord(n.id, id, if (makeMain) "MAIN" else "EXTRA", profile,
                            if (makeMain) 0 else all.count { it.role == "EXTRA" }, if (makeMain) 1 else null))
                    }
                }
                val extras = TopicTree.orderedLinks(dao.links(t.id).filter { it.nodeId == n.id && it.role == "EXTRA" })
                extras.forEachIndexed { i, l -> dao.link(l.copy(position = i)) }
            }
            else -> throw TopicFailure("unknown_action")
        }
        // editTopic has already changed metadata; preserve it while incrementing the shared revision.
        touch(topic(t.id))
        snapshotOnWorker(topic(t.id), request.optString("viewNodeId"))
    }
}
