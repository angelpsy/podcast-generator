package dev.podcast.siwcspike.podcast

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.podcast.siwcspike.siwc.SiwcCoordinator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Explicit, separately selected stage-zero checks. API requests use the installed native SIWC client. */
class StageZeroChecks : Instrumentation() {
    private lateinit var mode: String
    private var navigationStep = ""
    private val folder by lazy { File(targetContext.getExternalFilesDir(null), "stage0").apply { mkdirs() } }
    override fun onCreate(arguments: Bundle?) { mode = arguments?.getString("mode") ?: "outline"; super.onCreate(arguments); start() }

    private fun status(phase: String, values: JSONObject = JSONObject()) {
        values.put("phase", phase).put("mode", mode)
        sendStatus(0, Bundle().apply { putString("progress", values.toString()) })
    }
    private fun write(name: String, value: JSONObject) = AtomicAudioJournal(File(folder, name)).write(value)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun words(text: String) = Regex("[\\p{L}\\p{N}]+(?:[-’'][\\p{L}\\p{N}]+)*").findAll(text).count()

    override fun onStart() {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        val journal = File(targetContext.noBackupFilesDir, "podcast/current.json")
        val before = journal.takeIf { it.exists() }?.readBytes()
        try {
            val activityClass = if (mode == "navigation") StageZeroNavigationActivity::class.java
                else Class.forName("dev.podcast.siwcspike.MainActivity")
            val activity = startActivitySync(Intent(targetContext, activityClass)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val report = when (mode) {
                "outline", "outline-fallback" -> outline()
                "schema-control" -> schemaControl()
                "room" -> StageZeroRoomProbe.run(targetContext)
                "navigation" -> navigation(activity)
                "texts" -> texts()
                "english" -> speech("english", ENGLISH, "en")
                "long-tech" -> speech("long-tech", File(folder, "long-tech.txt").readText(), "ru")
                "long-science" -> speech("long-science", File(folder, "long-science.txt").readText(), "ru")
                "cancel" -> cancellation()
                else -> error("Unknown stage-zero mode")
            }
            if (before != null) check(journal.readBytes().contentEquals(before)) { "Main podcast journal changed" }
            report.put("mode", mode).put("result", "PASS").put("mainJournalUnchanged", true)
            write("$mode-report.json", report)
            result.putString("report", report.toString())
            code = Activity.RESULT_OK
            runOnMainSync { if (!activity.isDestroyed) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            val report = JSONObject().put("mode", mode).put("result", "FAIL")
                .put("exception", cause.javaClass.simpleName)
                .put("error", (cause as? dev.podcast.siwcspike.siwc.SafeFailure)?.safeCode ?: "stage_zero_failed")
                .put("location", e.stackTrace.firstOrNull { it.className.startsWith(javaClass.name) }?.toString())
            if (mode == "navigation") report.put("step", navigationStep)
            write("$mode-report.json", report)
            result.putString("report", report.toString())
        }
        finish(code, result)
    }

    private fun client(): SiwcCoordinator {
        val client = SiwcCoordinator.get(targetContext)
        val loaded = CompletableFuture<Unit>()
        client.load { _, _ -> loaded.complete(Unit) }
        loaded.get(15, TimeUnit.SECONDS)
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (JSONObject(client.snapshot()).optBoolean("busy") && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        check(JSONObject(client.snapshot()).optBoolean("planEnabled")) { "ChatGPT plan not connected" }
        return client
    }

    private fun nodes(text: String): List<AccessibilityNodeInfo> =
        uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty().filter {
            it.isVisibleToUser && (it.text?.toString()?.equals(text, ignoreCase = true) == true ||
                it.contentDescription?.toString()?.equals(text, ignoreCase = true) == true)
        }

    private fun awaitText(text: String) {
        navigationStep = text
        status("AwaitNavigation", JSONObject().put("expected", text))
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (nodes(text).isNotEmpty()) {
                uiAutomation.waitForIdle(350, 5000)
                if (nodes(text).isNotEmpty()) return
            }
            Thread.sleep(100)
        }
        error("Navigation UI timeout: $text")
    }

    private fun click(text: String) {
        awaitText(text)
        var node: AccessibilityNodeInfo? = nodes(text).first()
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Navigation control did not click" }
    }

    private fun navigation(activity: Activity): JSONObject {
        awaitText("Stage 0 · Топики")
        click("Увеличить счётчик")
        awaitText("Счётчик: 1")
        click("Подкасты"); awaitText("Stage 0 · Подкасты")
        click("Плейлисты"); awaitText("Stage 0 · Плейлисты")
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); awaitText("Stage 0 · Подкасты")
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); awaitText("Stage 0 · Топики")
        awaitText("Счётчик: 1")
        click("Открыть топик"); awaitText("Экран детали: compat-topic")
        click("Ещё один экран"); awaitText("Экран детали: compat-topic-next")
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); awaitText("Экран детали: compat-topic")
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); awaitText("Stage 0 · Топики")
        awaitText("Счётчик: 1")
        click("Переключить тему"); awaitText("Тема: тёмная"); awaitText("Счётчик: 1")
        sendKeyDownUpSync(KeyEvent.KEYCODE_HOME)
        val homeDeadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < homeDeadline &&
            uiAutomation.rootInActiveWindow?.packageName?.toString() == targetContext.packageName) Thread.sleep(100)
        check(uiAutomation.rootInActiveWindow?.packageName?.toString()?.let { it != targetContext.packageName } == true)
        runOnMainSync {
            targetContext.startActivity(Intent(targetContext, StageZeroNavigationActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        awaitText("Stage 0 · Топики"); awaitText("Счётчик: 1"); awaitText("Тема: тёмная")
        click("Открыть топик"); awaitText("Экран детали: compat-topic")
        val monitor = addMonitor(StageZeroNavigationActivity::class.java.name, null, false)
        try {
            runOnMainSync { activity.recreate() }
            check(monitor.waitForActivityWithTimeout(15_000) != null) { "Navigation Activity was not recreated" }
            awaitText("Stage 0 · Топики")
        } finally { removeMonitor(monitor) }
        return JSONObject().put("nativeStack", true).put("tabs", true).put("androidBack", true)
            .put("routeParameters", true).put("tabStatePreserved", true).put("themeSwitch", true)
            .put("backgroundResume", true).put("activityRecreated", true).put("renderer", "Fabric")
            .put("jsEngine", "Hermes")
    }

    private fun generate(topic: String, instructions: String, limit: Int, format: JSONObject? = null): JSONObject {
        status("RequestingText")
        return client().generateScript("", topic, instructions, limit, AtomicBoolean(false), format).get(150, TimeUnit.SECONDS)
    }

    private fun outline(): JSONObject {
        fun obj(properties: JSONObject, required: JSONArray) = JSONObject().put("type", "object")
            .put("properties", properties).put("required", required).put("additionalProperties", false)
        fun str() = JSONObject().put("type", "string")
        val section = obj(JSONObject().put("title", str()).put("questions", JSONObject().put("type", "array")
            .put("minItems", 2).put("maxItems", 2).put("items", str())), JSONArray(listOf("title", "questions")))
        val schema = obj(JSONObject().put("title", str()).put("sections", JSONObject().put("type", "array")
            .put("minItems", 2).put("maxItems", 2).put("items", section)), JSONArray(listOf("title", "sections")))
        val format = JSONObject().put("type", "json_schema").put("name", "stage_zero_outline_v1")
            .put("strict", true).put("schema", schema)
        val started = SystemClock.elapsedRealtime()
        val response = generate("Клиент-серверное взаимодействие", "Составь структуру темы на русском: ровно два раздела, " +
            "в каждом ровно два вопроса. Верни только JSON с полями title, sections; у раздела title, questions. " +
            "Не создавай сценарий или аудио. Никаких дополнительных полей и Markdown.", 12_000,
            if (mode == "outline") format else null)
        val tree = JSONObject(response.getString("script"))
        check(tree.keys().asSequence().toSet() == setOf("title", "sections"))
        check(tree.getString("title").isNotBlank())
        val sections = tree.getJSONArray("sections")
        check(sections.length() == 2)
        for (i in 0 until sections.length()) {
            val s = sections.getJSONObject(i)
            check(s.keys().asSequence().toSet() == setOf("title", "questions"))
            check(s.getString("title").isNotBlank() && s.getJSONArray("questions").length() == 2)
            for (j in 0..1) check(s.getJSONArray("questions").getString(j).isNotBlank())
        }
        write("outline.json", tree)
        return JSONObject().put("model", response.getString("textModel")).put("strictSchemaRequested", mode == "outline")
            .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("sections", 2).put("questions", 4)
            .put("generatedAudio", false)
    }

    private fun texts(): JSONObject {
        val report = JSONObject()
        for ((name, topic, size) in listOf(
            Triple("long-tech", "Как работают замыкания в JavaScript: подробное объяснение для подготовки к интервью", "800–950"),
            Triple("long-science", "Как учёные узнали, из чего состоят звёзды: увлекательный научно-популярный рассказ", "1350–1550"))) {
            val file = File(folder, "$name.txt")
            if (!file.exists()) {
                val started = SystemClock.elapsedRealtime()
                val response = generate(topic, "Создай самостоятельный связный рассказ на русском для одного диктора. " +
                    "Объём $size слов. Организацию подбери по теме; не навязывай список ошибок или фиксированное число блоков. " +
                    "Нужны содержательные детали, ясные объяснения и естественные переходы. Для научпопа передавай " +
                    "границы известного, не выдумывай факты и цитаты. Верни только произносимый текст без заголовков, " +
                    "Markdown, списков, кода, URL и режиссёрских указаний. Числа по возможности словами.", 20_000)
                file.writeText(response.getString("script"))
                write("$name-text-meta.json", JSONObject().put("model", response.getString("textModel"))
                    .put("elapsedMs", SystemClock.elapsedRealtime() - started))
            }
            val text = file.readText()
            val count = words(text)
            check(count >= if (name == "long-tech") 650 else 1100) { "Long text below experimental minimum" }
            check(text.codePointCount(0, text.length) <= 20_000)
            val metrics = JSONObject().put("words", count).put("chars", text.codePointCount(0, text.length))
                .put("sha256", hash(text.toByteArray()))
            report.put(name, metrics); status("TextSaved", metrics)
        }
        return report
    }

    private fun schemaControl(): JSONObject {
        val schema = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("properties", JSONObject().put("schema_result", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("format_enforced")))))
            .put("required", JSONArray(listOf("schema_result")))
        val format = JSONObject().put("type", "json_schema").put("name", "stage_zero_control_v1")
            .put("strict", true).put("schema", schema)
        val response = generate("Ответь одним словом hello.", "Ответь обычным текстом, не JSON.", 1000, format)
        val parsed = JSONObject(response.getString("script"))
        check(parsed.keys().asSequence().toSet() == setOf("schema_result"))
        check(parsed.getString("schema_result") == "format_enforced")
        return JSONObject().put("model", response.getString("textModel"))
            .put("promptRequestedPlainText", true).put("schemaOnlyLiteralReturned", true)
    }

    private fun speech(name: String, source: String, language: String): JSONObject {
        val text = source.replace(Regex("[\\s\\p{Z}]+"), " ").trim()
        check(text.isNotEmpty() && text.codePointCount(0, text.length) <= 20_000)
        check(!Regex("[<>\\[\\]`#*_]|https?://").containsMatchIn(text)) { "Script has speech markup" }
        val engine = LocalTtsEngine(targetContext)
        val pss = AtomicLong(Debug.getPss())
        val thermal = AtomicInteger(0)
        val sampler = Executors.newSingleThreadScheduledExecutor()
        sampler.scheduleAtFixedRate({
            pss.accumulateAndGet(Debug.getPss(), ::maxOf)
            thermal.accumulateAndGet(targetContext.getSystemService(PowerManager::class.java).currentThermalStatus, ::maxOf)
        }, 0, 1, TimeUnit.SECONDS)
        val started = SystemClock.elapsedRealtime()
        try {
            status("PreparingVoice")
            engine.prepare {}
            val prepareMs = SystemClock.elapsedRealtime() - started
            val wav = File(folder, "$name.wav")
            val synthesisStart = SystemClock.elapsedRealtime()
            val chunkTimes = JSONArray()
            var last = synthesisStart
            val chunks = PodcastScript.chunks(text)
            val audio = engine.synthesizeChunks(chunks, language, wav, 1_200_000, {}, { index, total ->
                if (index > 1) chunkTimes.put(SystemClock.elapsedRealtime() - last)
                last = SystemClock.elapsedRealtime()
                status("Synthesizing", JSONObject().put("chunk", index).put("chunks", total).put("pssKb", pss.get()))
            })
            chunkTimes.put(SystemClock.elapsedRealtime() - last)
            val synthesisMs = SystemClock.elapsedRealtime() - synthesisStart
            status("Encoding")
            val output = File(folder, "$name.m4a").apply { delete() }
            val future = CompletableFuture<Unit>()
            val encoder = M4aEncoder(targetContext)
            val encodeStart = SystemClock.elapsedRealtime()
            runOnMainSync { encoder.start(wav, output, future) }
            future.get(300, TimeUnit.SECONDS)
            val info = M4aEncoder.inspect(output)
            info.put("words", words(text)).put("chars", text.codePointCount(0, text.length)).put("language", language)
                .put("chunks", chunks.size).put("chunkMs", chunkTimes).put("prepareMs", prepareMs)
                .put("synthesisMs", synthesisMs).put("encodingMs", SystemClock.elapsedRealtime() - encodeStart)
                .put("totalMs", SystemClock.elapsedRealtime() - started).put("observedPeakPssKb", pss.get())
                .put("maxThermalStatus", thermal.get()).put("wavBytes", audio.getLong("wavBytes"))
                .put("sha256", hash(output.readBytes()))
            check(info.getLong("durationMs") > 0)
            return info
        } finally { sampler.shutdownNow() }
    }

    private fun cancellation(): JSONObject {
        val engine = LocalTtsEngine(targetContext)
        engine.prepare {}
        val cancelled = AtomicBoolean(false)
        val timer = Executors.newSingleThreadScheduledExecutor()
        var requestedAt = 0L
        val started = SystemClock.elapsedRealtime()
        timer.schedule({ requestedAt = SystemClock.elapsedRealtime(); cancelled.set(true) }, 1500, TimeUnit.MILLISECONDS)
        val output = File(folder, "cancel.wav").apply { delete() }
        var failed = false
        try {
            engine.synthesizeChunks(PodcastScript.chunks(ENGLISH), "en", output, 120_000,
                { if (cancelled.get()) throw java.util.concurrent.CancellationException() }, { _, _ -> })
        } catch (_: java.util.concurrent.CancellationException) { failed = true }
        finally { timer.shutdownNow() }
        check(failed && !output.exists()) { "Cancelled audio became complete" }
        val delay = SystemClock.elapsedRealtime() - requestedAt
        val partial = File(folder, "speech.part")
        val partialBytes = partial.takeIf { it.exists() }?.length() ?: 0
        partial.delete()
        return JSONObject().put("totalMs", SystemClock.elapsedRealtime() - started)
            .put("cancellationDelayMs", delay).put("partialBytes", partialBytes).put("completedWav", false)
    }

    companion object {
        val ENGLISH = "A client and a server have different jobs. The client asks for information. The server receives the request " +
            "and sends a response. A web browser is one example of a client. HTTP defines the messages that these systems exchange. " +
            "An API gives a program a clear way to request data. JavaScript can send a request and use the result when it arrives. " +
            "The user does not need to know where the server is. However, the program must handle a slow connection or a missing response. " +
            "Now consider a different question. How can we learn about a star that is very far away? Light carries information. " +
            "When scientists separate starlight into its colours, they can see patterns. These patterns help them identify some of the " +
            "elements in the star. They compare observations with careful measurements made on Earth. A useful explanation connects " +
            "the evidence to the idea. It also tells us what we do not yet know."
    }
}
