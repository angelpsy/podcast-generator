package dev.podcast.siwcspike.podcast

import org.junit.Assert.*
import org.junit.Test

class TopicTreeTest {
    private fun n(id: String, parent: String?, kind: String = "SECTION", order: Int = 0, topic: String = "t") =
        TopicNodeRecord(id, topic, parent, kind, id, position = order)
    private val tree = listOf(n("root", null, "ROOT"), n("a", "root"), n("b", "root", order = 1),
        n("q", "a", "QUESTION"), n("c", "b"), n("d", "c"), n("leaf", "d", "QUESTION"))
    private fun rejects(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: TopicFailure) { assertEquals(code, e.code) }
    }
    @Test fun rootDepthIsZeroAndFourLevelsAreAllowed() {
        assertEquals(5, TopicTree.path(tree, "leaf").size)
        TopicTree.checkMove(tree, "q", "d")
    }
    @Test fun cannotMoveRoot() { rejects("root_locked") { TopicTree.checkMove(tree, "root", "a") } }
    @Test fun cannotMoveIntoSelf() { rejects("cycle") { TopicTree.checkMove(tree, "a", "a") } }
    @Test fun cannotMoveIntoDescendant() { rejects("cycle") { TopicTree.checkMove(tree, "b", "d") } }
    @Test fun cannotAddChildrenUnderQuestion() { rejects("question_has_no_children") { TopicTree.checkParent(tree, "q") } }
    @Test fun cannotAddFifthLevel() {
        val nodes = tree.map { if (it.id == "leaf") it.copy(kind = "SECTION") else it }
        rejects("max_depth") { TopicTree.checkParent(nodes, "leaf") }
    }
    @Test fun movingSubtreeChecksItsDeepestDescendant() {
        val nodes = tree + n("extra", "a", "QUESTION")
        rejects("max_depth") { TopicTree.checkMove(nodes, "a", "d") }
    }
    @Test fun canMoveSubtreeUpWithoutChangingItsOrder() {
        TopicTree.checkMove(tree, "c", "root")
        assertEquals(listOf("c", "d", "leaf"), TopicTree.subtree(tree, "c").map { it.id })
    }
    @Test fun cannotMoveBetweenTopics() {
        rejects("cross_topic_move") { TopicTree.checkMove(tree + n("other", null, "ROOT", topic = "other-topic"), "a", "other") }
    }
    @Test fun missingNodeIsExplicitFailure() { rejects("node_not_found") { TopicTree.path(tree, "missing") } }
    @Test fun damagedCycleDoesNotHangPathOrTraversal() {
        val nodes = listOf(n("a", "b"), n("b", "a"))
        rejects("invalid_tree") { TopicTree.path(nodes, "a") }
        rejects("invalid_tree") { TopicTree.subtree(nodes, "a") }
    }
    @Test fun playlistUsesDepthFirstOrderMainFirstAndDeduplicates() {
        val links = listOf(NodePodcastRecord("root", "extra", "EXTRA", "ru_standard", 0),
            NodePodcastRecord("root", "main", "MAIN", "ru_standard", 99, 1),
            NodePodcastRecord("a", "extra", "MAIN", "ru_standard", 0, 1),
            NodePodcastRecord("q", "question", "MAIN", "ru_standard", 0, 1),
            NodePodcastRecord("b", "last", "MAIN", "ru_standard", 0, 1))
        assertEquals(listOf("main", "extra", "question", "last"), TopicTree.playlist(tree, links, "root"))
    }
    @Test fun branchPlaylistExcludesParentsAndSiblings() {
        val links = listOf(NodePodcastRecord("root", "root-audio", "MAIN", "ru_standard", 0, 1),
            NodePodcastRecord("q", "question", "MAIN", "ru_standard", 0, 1),
            NodePodcastRecord("b", "sibling", "MAIN", "ru_standard", 0, 1))
        assertEquals(listOf("question"), TopicTree.playlist(tree, links, "a"))
        assertTrue(TopicTree.playlist(tree, links, "leaf").isEmpty())
    }
    @Test fun siblingOrderIsStableEvenWithOutOfOrderRows() {
        assertEquals(listOf("a", "b"), TopicTree.children(tree.reversed(), "root").map { it.id })
    }
    @Test fun validatesUnicodeCodePointsAndTrims() {
        assertEquals("😀", TopicTree.text(" 😀 ", 1))
        rejects("text_length") { TopicTree.text("😀😀", 1) }
        rejects("text_length") { TopicTree.text("  ", 200) }
        assertEquals("", TopicTree.text("  ", 2000, false))
    }
}
