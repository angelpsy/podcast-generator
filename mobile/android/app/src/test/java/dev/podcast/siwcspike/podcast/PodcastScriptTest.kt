package dev.podcast.siwcspike.podcast

import dev.podcast.siwcspike.siwc.SafeFailure
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PodcastScriptTest {
    private class Journal : AudioJournal {
        var json = """{"id":"episode","topic":"Исходная тема"}"""
        override fun read() = JSONObject(json)
        override fun write(state: JSONObject) { json = state.toString() }
    }
    private fun fails(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (e: SafeFailure) { assertEquals(code, e.safeCode) }
    }

    @Test fun topicLimitsCountCodePointsAfterTrim() {
        assertEquals("Тема", PodcastScript.topic("  Тема  "))
        assertEquals(1000, PodcastScript.topic("😀".repeat(500)).length)
        fails("topic_length") { PodcastScript.topic("😀".repeat(501)) }
        fails("topic_length") { PodcastScript.topic(" \n ") }
    }

    @Test fun normalizesWhitespaceWithoutSilentlyTruncatingOrExecutingTags() {
        assertEquals("Русский текст. Ещё фраза.", PodcastScript.normalize(" Русский\tтекст.\n\nЕщё фраза. "))
        fails("answer_too_large") { PodcastScript.normalize("я".repeat(3001)) }
        for (text in listOf("<ru>Русский текст", "[pause] Русский текст", "# Заголовок", "Русский текст https://example.test",
            "- Первый пункт", "Русский\u0000текст", "**Русский** текст")) {
            fails("script_markup") { PodcastScript.normalize(text) }
        }
        fails("script_not_russian") { PodcastScript.normalize("English text only.") }
        fails("empty_script") { PodcastScript.normalize("  ") }
    }

    @Test fun chunkingPreservesWordsNumbersNamesAndPunctuation() {
        val script = PodcastScript.normalize(("В г. Тбилиси живёт А. Иванов. Число 3.14 и имя James встречаются в тексте. " +
            "Предложение заканчивается здесь! ").repeat(10))
        val chunks = PodcastScript.chunks(script)
        assertTrue(chunks.size > 1)
        assertEquals(script, chunks.joinToString(" "))
        assertTrue(chunks.all { it.codePointCount(0, it.length) <= 500 })
        assertTrue(chunks.none { it.endsWith("3.") })
    }

    @Test fun longWordDoesNotSplitSurrogatePairsOrLoseCharacters() {
        val text = "я😀".repeat(400)
        val chunks = PodcastScript.chunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.codePointCount(0, it.length) <= 500 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }

    @Test fun completedScriptSurvivesAudioFailureAndRetrySkipsOpenAI() {
        val journal = Journal()
        var calls = 0
        val first = PodcastScript.completedDraft(journal) {
            calls++
            JSONObject().put("script", "Русский законченный рассказ.").put("textModel", "available-model").put("textMs", 123)
        }
        // Simulate an audio failure/restart by re-reading the durable draft in a new invocation.
        val retried = PodcastScript.completedDraft(journal) { calls++; error("Must not send a second request") }
        assertEquals(1, calls)
        assertEquals(first.toString(), retried.toString())
        assertEquals("Исходная тема", retried.getString("topic"))
    }

    @Test fun failedOrInvalidTextIsNotCommittedAndManualRetryCanRegenerate() {
        val journal = Journal()
        fails("stream_interrupted") { PodcastScript.completedDraft(journal) { throw SafeFailure("stream_interrupted") } }
        assertFalse(journal.read().has("script"))
        fails("script_markup") { PodcastScript.completedDraft(journal) { JSONObject().put("script", "<ru>Текст") } }
        assertFalse(journal.read().has("script"))
        val result = PodcastScript.completedDraft(journal) { JSONObject().put("script", "Готовый рассказ.") }
        assertEquals("Готовый рассказ.", result.getString("script"))
    }

    @Test fun diagnosticAllowlistExcludesTopicScriptAndIdentifiers() {
        val raw = JSONObject().put("topic", "private-topic").put("script", "private-script").put("uri", "private-uri")
            .put("name", "private-name").put("email", "private-email").put("access_token", "private-token")
            .put("textMs", 50).put("textModel", "model").put("requestId", "req_123")
        val safe = PodcastScript.diagnostics(raw)
        assertFalse(safe.toString().contains("private-"))
        assertEquals(50, safe.getInt("textMs"))
        assertEquals("req_123", safe.getString("requestId"))
    }
}
