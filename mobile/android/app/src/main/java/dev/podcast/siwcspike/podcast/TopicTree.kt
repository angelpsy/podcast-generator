package dev.podcast.siwcspike.podcast

class TopicFailure(val code: String) : IllegalArgumentException(code)

/** Pure rules shared by mutations, target picker and playlist traversal. Root depth is zero. */
object TopicTree {
    const val MAX_DEPTH = 4
    const val MAX_NODES = 200
    fun text(value: String, max: Int, required: Boolean = true): String = value.trim().also {
        if ((required && it.isEmpty()) || it.codePointCount(0, it.length) > max) throw TopicFailure("text_length")
    }
    fun node(nodes: List<TopicNodeRecord>, id: String) = nodes.find { it.id == id } ?: throw TopicFailure("node_not_found")
    fun children(nodes: List<TopicNodeRecord>, id: String) = nodes.filter { it.parentId == id }
        .sortedWith(compareBy({ it.position }, { it.id }))
    fun path(nodes: List<TopicNodeRecord>, id: String): List<TopicNodeRecord> {
        val result = mutableListOf<TopicNodeRecord>()
        val seen = hashSetOf<String>()
        var current: TopicNodeRecord? = node(nodes, id)
        while (current != null) {
            if (!seen.add(current.id)) throw TopicFailure("invalid_tree")
            result.add(current)
            current = current.parentId?.let { node(nodes, it) }
        }
        return result.reversed()
    }
    fun subtree(nodes: List<TopicNodeRecord>, id: String): List<TopicNodeRecord> {
        val visited = hashSetOf<String>()
        val result = mutableListOf<TopicNodeRecord>()
        fun visit(current: TopicNodeRecord) {
            if (!visited.add(current.id)) throw TopicFailure("invalid_tree")
            result.add(current); children(nodes, current.id).forEach(::visit)
        }
        visit(node(nodes, id)); return result
    }
    fun checkParent(nodes: List<TopicNodeRecord>, parentId: String) {
        if (node(nodes, parentId).kind == "QUESTION") throw TopicFailure("question_has_no_children")
        if (path(nodes, parentId).size > MAX_DEPTH) throw TopicFailure("max_depth")
    }
    fun checkMove(nodes: List<TopicNodeRecord>, id: String, parentId: String) {
        val moving = node(nodes, id)
        if (moving.parentId == null) throw TopicFailure("root_locked")
        val parent = node(nodes, parentId)
        if (parent.topicId != moving.topicId) throw TopicFailure("cross_topic_move")
        val descendants = subtree(nodes, id)
        if (descendants.any { it.id == parentId }) throw TopicFailure("cycle")
        checkParent(nodes, parentId)
        val ownDepth = path(nodes, id).size - 1
        val height = descendants.maxOf { path(nodes, it.id).size - 1 - ownDepth }
        if (path(nodes, parentId).size + height > MAX_DEPTH) throw TopicFailure("max_depth")
    }
    fun orderedLinks(links: List<NodePodcastRecord>) = links.sortedWith(compareBy<NodePodcastRecord>(
        { if (it.role == "MAIN") 0 else 1 }, { it.position }, { it.podcastId }))
    fun playlist(nodes: List<TopicNodeRecord>, links: List<NodePodcastRecord>, rootId: String): List<String> {
        val seen = linkedSetOf<String>()
        subtree(nodes, rootId).forEach { n -> orderedLinks(links.filter { it.nodeId == n.id }).forEach { seen.add(it.podcastId) } }
        return seen.toList()
    }
}
