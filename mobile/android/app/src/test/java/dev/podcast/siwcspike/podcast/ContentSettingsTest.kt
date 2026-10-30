package dev.podcast.siwcspike.podcast

import dev.podcast.siwcspike.siwc.SafeFailure
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContentSettingsTest {
    private fun raw(text: String = "Свет звезды помогает учёным понять её состав.", tags: JSONArray = JSONArray()) = JSONObject().put("title", "Рассказ").put("summary", "Обзор")
        .put("segments", JSONArray().put(JSONObject().put("heading", "Начало").put("text", text))).put("tags", tags).toString()
    private fun rejects(code: String, action: () -> Unit) { try { action(); fail("Expected $code") } catch (e: SafeFailure) { assertEquals(code, e.safeCode) } }
    @Test fun defaultIsDetailedAndIndependentDepthSurvivesRoundTrip() {
        assertEquals(Triple(700, 1100, 12000), ContentSettings().limits)
        val shortDeep = ContentSettings("BRIEF", "DEEP", "Одна центральная идея", "Начинающий")
        assertEquals(shortDeep, ContentSettings.parse(shortDeep.json())); assertEquals(3000, shortDeep.limits.third)
        assertEquals(20000, ContentSettings("DEEP", "OVERVIEW").limits.third)
    }
    @Test fun invalidPoliciesAndSettingsAreRejected() {
        rejects("invalid_language") { LanguagePolicy.validate("en_standard") }
        rejects("invalid_content_settings") { ContentSettings.parse(JSONObject().put("volume", "HUGE")) }
        rejects("invalid_content_settings") { ContentSettings.parse(JSONObject().put("immersion", "HUGE")) }
        rejects("invalid_content_settings") { ContentSettings.parse(JSONObject().put("intent", "x".repeat(1001))) }
    }
    @Test fun languageAndTtsAreCoupled() {
        assertEquals("ru", LanguagePolicy.tts("ru_simple")); assertEquals("en", LanguagePolicy.tts("en_simple_ste"))
        assertTrue(EpisodeScript.instructions("en_simple_ste").contains("ASD-STE100"))
    }
    @Test fun neutralPromptDoesNotForceFixedSections() {
        assertTrue(EpisodeScript.instructions("ru_standard").contains("Не навязывай фиксированное число блоков"))
        assertTrue(EpisodeScript.instructions("ru_simple").contains("Простота языка не уменьшает глубину"))
    }
    @Test fun metadataAndTagsAreNotSpokenAndBadTagsAreDropped() {
        val parsed = EpisodeScript.parse(raw(tags = JSONArray().put("свет").put("bad_url/").put(42)), ContentSettings(), "ru_standard")
        assertFalse(parsed.getString("script").contains("Начало")); assertFalse(parsed.getString("script").contains("Рассказ"))
        assertEquals(1, parsed.getJSONArray("tags").length()); assertEquals(ContentHash.of(parsed.getString("script")), parsed.getString("scriptHash"))
    }
    @Test fun grossLanguageMismatchBlocksTtsButTechnicalNamesAreAllowed() {
        rejects("script_language") { EpisodeScript.parse(raw("This is a short English story."), ContentSettings(), "ru_standard") }
        rejects("script_language") { EpisodeScript.parse(raw(), ContentSettings(), "en_simple_ste") }
        EpisodeScript.parse(raw("Клиент отправляет HTTP request в API. Сервер проверяет запрос и возвращает ответ."), ContentSettings(), "ru_simple")
        EpisodeScript.parse(raw("A client sends an HTTP request. The server returns a response."), ContentSettings(), "en_simple_ste")
    }
    @Test fun markupAndHardLimitAreErrorsAndWordRangeIsOnlyWarning() {
        rejects("script_markup") { EpisodeScript.parse(raw("Рассказ содержит <speak>разметку</speak>."), ContentSettings(), "ru_standard") }
        rejects("answer_too_large") { EpisodeScript.parse(raw("Слово ".repeat(501)), ContentSettings("BRIEF"), "ru_standard") }
        val parsed = EpisodeScript.parse(raw(), ContentSettings(), "ru_standard")
        assertTrue(parsed.getJSONArray("qualityWarnings").toString().contains("word_range"))
        assertEquals("Свет звезды помогает учёным понять её состав.", parsed.getString("script"))
    }
    @Test fun wordCounterTreatsHyphensApostrophesDecimalsAndIdentifiersConsistently() {
        assertEquals(6, EpisodeScript.words("клиент-сервер HTTP API JavaScript can't 3.14"))
        assertEquals(3, EpisodeScript.words("Ёлка — это дерево."))
    }
    @Test fun longEnglishSentencesProduceWarningWithoutTruncation() {
        val text = "word ".repeat(26).trim() + "."
        val parsed = EpisodeScript.parse(raw(text), ContentSettings("BRIEF"), "en_simple_ste")
        assertEquals(text, parsed.getString("script")); assertTrue(parsed.getJSONArray("qualityWarnings").toString().contains("long_sentences"))
    }
    private class Journal(var value: JSONObject) : AudioJournal {
        var fail = false
        override fun read() = JSONObject(value.toString())
        override fun write(state: JSONObject) { if (fail) error("disk"); value = JSONObject(state.toString()) }
    }
    private fun journal() = Journal(JSONObject().put("languagePolicy", "ru_standard").put("contentSettings", ContentSettings().json()))
    @Test fun completedScriptIsCommittedBeforeAudioAndReusedWithoutNewRequest() {
        val journal = journal(); var calls = 0
        fun generate() = JSONObject().put("script", raw()).put("textModel", "chosen").also { calls++ }
        val first = EpisodeScript.complete(journal, ::generate)
        val second = EpisodeScript.complete(journal, ::generate)
        assertEquals(1, calls); assertEquals(first.toString(), second.toString())
    }
    @Test fun failedAtomicWriteNeverCreatesResumableTextInMemoryOnly() {
        val journal = journal(); journal.fail = true
        try { EpisodeScript.complete(journal) { JSONObject().put("script", raw()) }; fail() } catch (_: IllegalStateException) { }
        assertFalse(journal.read().has("script"))
    }
    @Test fun savedScriptHashMismatchIsRejectedWithoutRequest() {
        val journal = journal(); EpisodeScript.complete(journal) { JSONObject().put("script", raw()) }
        journal.value.put("script", "Другой текст.")
        rejects("checkpoint_mismatch") { EpisodeScript.complete(journal) { error("Network must not run") } }
    }
    @Test fun diskBudgetAccountsForFragmentsAssemblyAndPublication() {
        assertTrue(EpisodeScript.requiredBytes("слово ".repeat(1500)) > 300L * 1024 * 1024)
        assertTrue(EpisodeScript.requiredBytes("x".repeat(20000)) > EpisodeScript.requiredBytes("x".repeat(3000)))
    }
}
