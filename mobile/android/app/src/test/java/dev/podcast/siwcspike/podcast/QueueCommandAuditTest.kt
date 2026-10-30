package dev.podcast.siwcspike.podcast

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class QueueCommandAuditTest {
    private fun fixture(test: (File, MutableList<String>, QueueCommandAudit) -> Unit) {
        val root = Files.createTempDirectory("queue-audit").toFile()
        try { val lines = mutableListOf<String>(); test(root, lines, QueueCommandAudit(File(root, "audit.jsonl"), { lines.add(it) }, 1600)) }
        finally { root.deleteRecursively() }
    }
    @Test fun requestAndResultShareIdAndDistinguishAllFromSingleTask() = fixture { root, lines, audit ->
        val all = audit.begin("resume", "ui"); audit.event(all, "applied", 8, mapOf("QUEUED" to 8))
        val id = "12345678-1234-1234-1234-123456789abc"
        audit.event(audit.begin("cancel", "test", id), "applied", 1)
        val request = JSONObject(lines[0]); val result = JSONObject(lines[1])
        assertEquals(request.getString("commandId"), result.getString("commandId"))
        assertEquals("all", result.getString("scope")); assertEquals(8, result.getInt("affected"))
        assertEquals(8, result.getJSONObject("counts").getInt("QUEUED"))
        assertEquals("task", JSONObject(lines.last()).getString("scope"))
        assertEquals(id, JSONObject(lines.last()).getString("targetId"))
        assertEquals(lines, File(root, "audit.jsonl").readLines())
    }
    @Test fun unknownInputsAndCountKeysCannotLeakPrivateData() = fixture { _, lines, audit ->
        val secret = "private@example.test?access_token=secret"
        audit.event(audit.begin(secret, secret, secret), secret, counts = mapOf(secret to 3))
        assertFalse(lines.joinToString().contains("private")); assertFalse(lines.joinToString().contains("secret"))
        val row = JSONObject(lines.last()); assertEquals("unknown", row.getString("source")); assertEquals("invalid", row.getString("targetId"))
        assertFalse(row.has("title")); assertFalse(row.has("script")); assertFalse(row.has("request"))
    }
    @Test fun journalRotatesAndRetainsTheLatestCommands() = fixture { root, _, audit ->
        repeat(20) { audit.event(audit.begin("pause", "lifecycle"), "applied", 0) }
        val current = File(root, "audit.jsonl"); val previous = File(root, "audit.jsonl.1")
        assertTrue(current.length() <= 1600); assertTrue(previous.length() <= 1600)
        assertEquals(2, root.listFiles()!!.size)
        assertEquals("applied", JSONObject(current.readLines().last()).getString("result"))
    }
    @Test fun diskOrLogFailureDoesNotBreakQueueCommands() = fixture { root, _, _ ->
        val blocker = File(root, "not-a-directory").apply { writeText("x") }
        val audit = QueueCommandAudit(File(blocker, "audit.jsonl"), { error("log unavailable") })
        audit.event(audit.begin("resume", "ui"), "applied", 0)
    }
}
