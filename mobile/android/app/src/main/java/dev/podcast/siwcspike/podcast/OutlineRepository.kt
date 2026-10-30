package dev.podcast.siwcspike.podcast

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Callable

class OutlineRepository(private val db: PodcastDatabase) {
    private val dao = db.outlines()
    private val topics = db.topics()
    private fun <T> tx(block: () -> T): T = db.runInTransaction(Callable { block() })
    private fun proposal(id: String) = dao.find(id) ?: throw TopicFailure("proposal_not_found")
    private fun topic(id: String) = topics.topic(id) ?: throw TopicFailure("topic_not_found")
    private fun integer(o: JSONObject, name: String, min: Int, max: Int): Int {
        val n = o.getDouble(name)
        if (!n.isFinite() || n % 1 != 0.0 || n !in min.toDouble()..max.toDouble()) throw TopicFailure("outline_limits")
        return n.toInt()
    }
    fun create(request: JSONObject): OutlineProposalRecord = tx {
        val t = topic(request.getString("topicId")); val nodes = topics.nodes(t.id)
        val n = TopicTree.node(nodes, request.getString("nodeId"))
        if (request.getDouble("expectedRevision") != t.revision.toDouble()) throw TopicFailure("revision_conflict")
        val remainingDepth = 4 - (TopicTree.path(nodes, n.id).size - 1)
        val depth = integer(request, "depth", 1, remainingDepth)
        val mode = request.getString("mode").also { if (it !in setOf("APPEND", "REPLACE")) throw TopicFailure("invalid_request") }
        val max = integer(request, "maxNodes", 1, 50)
        val removed = if (mode == "REPLACE") TopicTree.subtree(nodes, n.id).size - 1 else 0
        if (nodes.size - removed >= 200) throw TopicFailure("node_limit")
        val model = TopicTree.text(request.getString("model"), 200)
        val input = JSONObject().put("topicTitle", t.title).put("goal", t.goal).put("targetTitle", n.title)
            .put("targetDescription", n.description).put("path", JSONArray(TopicTree.path(nodes, n.id).map { it.title }))
            .put("existingChildren", JSONArray(TopicTree.children(nodes, n.id).take(30).map { it.title }))
            .put("language", LanguagePolicy.tts(t.languagePolicy)).put("languagePolicy", t.languagePolicy).put("mode", mode).put("maxDepth", depth).put("maxNodes", minOf(max, 200 - nodes.size + removed))
            .put("intent", TopicTree.text(request.optString("intent"), 1000, false)).put("promptVersion", "outline-v1")
        OutlineProposalRecord(UUID.randomUUID().toString(), t.id, n.id, t.revision, t.revision, mode, depth,
            input.getInt("maxNodes"), model, input.toString(), "RUNNING", createdAt = System.currentTimeMillis()).also(dao::insert)
    }
    fun complete(id: String, raw: String, model: String): Boolean = tx {
        val p = dao.find(id) ?: return@tx false
        if (p.status != "RUNNING") return@tx false
        val parsed = OutlineFormat.parse(raw, p.depth, p.maxNodes)
        dao.update(p.copy(payload = parsed.json().toString(), actualModel = model, status = "READY")); true
    }
    fun fail(id: String, error: String, cancelled: Boolean) = tx {
        dao.find(id)?.takeIf { it.status == "RUNNING" }?.let { dao.update(it.copy(status = if (cancelled) "CANCELLED" else "FAILED", error = error)) }
    }
    fun recover() = tx { dao.recover() }
    fun exists(id: String) = dao.find(id) != null
    fun list(topic: String, node: String, busy: Boolean): JSONObject = tx {
        topic(topic); TopicTree.node(topics.nodes(topic), node)
        JSONObject().put("busy", busy).put("items", JSONArray().apply { dao.list(topic, node).forEach { p ->
            put(JSONObject().put("id", p.id).put("status", p.status).put("mode", p.mode).put("error", p.error)
                .put("depth", p.depth).put("maxNodes", p.maxNodes).put("model", p.actualModel.ifEmpty { p.model }))
        } })
    }
    private data class Diff(val nodes: List<TopicNodeRecord>, val target: TopicNodeRecord,
        val removed: List<TopicNodeRecord>, val tree: OutlineTree)
    private fun diff(p: OutlineProposalRecord): Diff {
        val nodes = topics.nodes(p.topicId); val n = TopicTree.node(nodes, p.nodeId)
        val tree = OutlineFormat.parse(p.payload, p.depth, p.maxNodes)
        val newCount = OutlineFormat.flatten(tree).length()
        val removed = if (p.mode == "REPLACE") TopicTree.subtree(nodes, n.id).drop(1) else emptyList()
        if (nodes.size - removed.size + newCount > 200) throw TopicFailure("node_limit")
        val remaining = 4 - (TopicTree.path(nodes, n.id).size - 1)
        OutlineFormat.parse(p.payload, remaining, p.maxNodes)
        if (p.mode == "APPEND") {
            val old = TopicTree.children(nodes, n.id).map { OutlineFormat.normalize(it.title) }.toSet()
            if (tree.children.any { OutlineFormat.normalize(it.title) in old }) throw TopicFailure("outline_duplicate_title")
        }
        return Diff(nodes, n, removed, tree)
    }
    fun preview(id: String): JSONObject = tx {
        val p = proposal(id); val t = topic(p.topicId)
        if (p.status != "READY") throw TopicFailure("proposal_not_ready")
        val tree = OutlineFormat.parse(p.payload, p.depth, p.maxNodes)
        val nodes = topics.nodes(t.id); val n = TopicTree.node(nodes, p.nodeId)
        val removed = if (p.mode == "REPLACE") TopicTree.subtree(nodes, n.id).drop(1) else emptyList()
        val removedIds = removed.map { it.id }.toSet()
        val affectedLinks = topics.links(t.id).count { it.nodeId in removedIds }
        val invalid = try { diff(p); "" } catch (e: TopicFailure) { e.code }
        JSONObject().put("id", id).put("mode", p.mode).put("currentRevision", t.revision).put("baseRevision", p.baseRevision)
            .put("reviewedRevision", p.reviewedRevision).put("stale", p.reviewedRevision != t.revision)
            .put("error", invalid).put("canApply", invalid.isEmpty() && p.reviewedRevision == t.revision)
            .put("input", JSONObject(p.input)).put("currentTitle", n.title).put("currentGoal", t.goal)
            .put("currentDescription", n.description).put("currentPath", JSONArray(TopicTree.path(nodes, n.id).map { it.title }))
            .put("title", tree.title).put("description", tree.description).put("newNodes", OutlineFormat.flatten(tree))
            .put("removedNodes", JSONArray(removed.map { node -> JSONObject().put("title", TopicTree.path(nodes, node.id).joinToString(" / ") { it.title }) }))
            .put("removedLinks", affectedLinks).put("convertQuestion", n.kind == "QUESTION")
    }
    fun review(id: String, revision: Long): JSONObject = tx {
        val p = proposal(id); val t = topic(p.topicId)
        if (p.status != "READY") throw TopicFailure("proposal_not_ready")
        if (t.revision != revision) throw TopicFailure("revision_conflict")
        diff(p)
        dao.update(p.copy(reviewedRevision = revision)); preview(id)
    }
    fun discard(id: String) = tx {
        val p = proposal(id)
        if (p.status != "READY") throw TopicFailure("proposal_not_ready")
        dao.update(p.copy(status = "DISCARDED"))
    }
    fun apply(id: String, revision: Long): JSONObject = tx {
        val p = proposal(id); val t = topic(p.topicId)
        if (p.status == "APPLIED") return@tx TopicRepository(db).snapshot(t.id, p.nodeId)
        if (p.status != "READY") throw TopicFailure("proposal_not_ready")
        if (t.revision != revision || p.reviewedRevision != revision) throw TopicFailure("revision_conflict")
        val d = diff(p)
        if (p.mode == "REPLACE") TopicTree.children(d.nodes, d.target.id).forEach { topics.deleteNode(it.id) }
        if (d.target.kind == "QUESTION") topics.update(d.target.copy(kind = "SECTION", revision = d.target.revision + 1))
        fun insert(items: List<OutlineItem>, parent: String, offset: Int = 0) {
            items.forEachIndexed { index, item ->
                val node = TopicNodeRecord(UUID.randomUUID().toString(), t.id, parent, item.kind, item.title, item.description, offset + index)
                topics.insert(node); insert(item.children, node.id)
            }
        }
        insert(d.tree.children, d.target.id, if (p.mode == "APPEND") TopicTree.children(d.nodes, d.target.id).size else 0)
        topics.update(t.copy(revision = t.revision + 1, updatedAt = System.currentTimeMillis()))
        dao.update(p.copy(status = "APPLIED"))
        TopicRepository(db).snapshot(t.id, p.nodeId)
    }
}
