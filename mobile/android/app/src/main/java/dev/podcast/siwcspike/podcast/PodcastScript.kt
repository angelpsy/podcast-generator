package dev.podcast.siwcspike.podcast

import dev.podcast.siwcspike.siwc.SafeFailure
import org.json.JSONObject

internal object PodcastScript {
    const val MAX_TEXT = 3000
    const val INSTRUCTIONS = "Создай короткий самостоятельный рассказ на русском языке для одного диктора, " +
        "независимо от языка темы. Ориентир — от ста двадцати до ста восьмидесяти слов, не более трёх тысяч символов. " +
        "Дай понятное вступление, объяснение и короткое завершение. Верни только текст для произнесения, " +
        "без заголовка, списков, Markdown, URL, ролей и режиссёрских пометок. По возможности пиши числа словами. " +
        "Тема находится в пользовательском сообщении."

    fun topic(value: String): String = value.trim().also {
        if (it.isEmpty() || it.codePointCount(0, it.length) > 500) throw SafeFailure("topic_length")
    }

    fun normalize(value: String): String {
        if (value.codePointCount(0, value.length) > MAX_TEXT) throw SafeFailure("answer_too_large")
        val text = value.replace(Regex("[\\s\\p{Z}]+"), " ").trim()
        if (text.isEmpty()) throw SafeFailure("empty_script")
        // Text is passed as plain speech, never as model language/control tags or SSML.
        if (text.any { it in "<>[]`#*_" || Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() } ||
            Regex("https?://|www\\.", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            Regex("(?m)^\\s*(?:[-•]|\\d+[.)])\\s").containsMatchIn(value)) throw SafeFailure("script_markup")
        val letters = text.count { it.isLetter() }
        val russian = text.count { it in 'А'..'я' || it == 'ё' || it == 'Ё' }
        if (russian == 0 || russian * 2 < letters) throw SafeFailure("script_not_russian")
        return text
    }

    /** Commit only a completed, validated script; audio retries reuse it without an API request. */
    fun completedDraft(journal: AudioJournal, generate: () -> JSONObject): JSONObject {
        val meta = journal.read()
        if (meta.has("script")) { normalize(meta.getString("script")); return meta }
        val result = generate()
        val script = normalize(result.getString("script"))
        for (key in listOf("textModel", "requestId", "textMs")) if (result.has(key)) meta.put(key, result.get(key))
        meta.put("script", script).put("scriptChars", script.codePointCount(0, script.length))
            .put("chunks", chunks(script).size)
        journal.write(meta)
        return meta
    }

    /** Prefer sentence boundaries near 300 chars; use whitespace, then code points for long words. */
    fun chunks(script: String): List<String> {
        var rest = script
        val chunks = mutableListOf<String>()
        while (rest.isNotEmpty()) {
            if (rest.codePointCount(0, rest.length) <= 400) { chunks.add(rest); break }
            val target = rest.offsetByCodePoints(0, 400)
            val floor = rest.offsetByCodePoints(0, 200)
            val prefix = rest.substring(0, target)
            // Keep decimal numbers, initials and common Russian abbreviations together where possible.
            val sentence = Regex("[.!?…][»\"”]?\\s+").findAll(prefix)
                .lastOrNull {
                    val word = prefix.substring(0, it.range.first).substringAfterLast(' ').lowercase()
                    val abbreviation = it.value.startsWith('.') &&
                        (word.length == 1 || word in setOf("г", "ул", "д", "им", "др", "см", "рис", "стр"))
                    !abbreviation && it.range.last + 1 >= floor
                }?.range?.last?.plus(1)
            val space = prefix.indexOfLast { it.isWhitespace() }.takeIf { it >= floor }
            val end = sentence ?: space ?: target
            chunks.add(rest.substring(0, end).trim())
            rest = rest.substring(end).trimStart()
        }
        return chunks
    }

    /** Explicit allowlist: topics, scripts, file names and URIs must not reach logcat. */
    fun diagnostics(meta: JSONObject): JSONObject = JSONObject().also { safe ->
        for (key in listOf("id", "language", "voice", "runtime", "model", "textModel", "numSteps", "numThreads",
            "scriptChars", "chunks", "textMs", "prepareMs", "coldInit", "synthesisMs", "encodingMs", "totalMs",
            "durationMs", "sampleRate", "bytes", "observedPssKb", "requestId")) {
            if (meta.has(key)) safe.put(key, meta.get(key))
        }
    }
}
