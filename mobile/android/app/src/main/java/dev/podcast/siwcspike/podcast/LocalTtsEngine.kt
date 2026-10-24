package dev.podcast.siwcspike.podcast

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object RussianSample {
    val paragraphs = listOf(
        "Сегодня мы проверяем, как телефон создаёт небольшой подкаст на русском языке. Текст превращается в голос прямо на устройстве. Для озвучки не нужен интернет, а готовую запись можно открыть в другом приложении.",
        "Представьте тихое утро у моря. Солнце медленно поднимается над горизонтом, волны касаются берега, а прохладный ветер шевелит листья. Сделайте короткую паузу и прислушайтесь к этим звукам.",
        "В этом примере три фрагмента. Проверим числа: двадцать пять, сто двадцать восемь и две тысячи двадцать шесть. Город Тбилиси и имя Александр помогают оценить произношение. Это конец тестовой записи."
    )
    val text = paragraphs.joinToString("\n\n")
}

/** Accessed by one worker. Never free a native model concurrently with inference. */
class LocalTtsEngine(private val context: Context) {
    private var tts: OfflineTts? = null
    fun prepare(checkCancelled: () -> Unit): Boolean {
        if (tts != null) return false
        val prefix = "tts/supertonic3/"
        val manifest = JSONObject(context.assets.open(prefix + "manifest.json").bufferedReader().use { it.readText() })
        val hashes = manifest.getJSONObject("files")
        for (name in hashes.keys()) {
            checkCancelled()
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open(prefix + name).use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    checkCancelled()
                    digest.update(buffer, 0, n)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == hashes.getString(name)) { "model_hash_mismatch" }
        }
        tts = OfflineTts(context.assets, OfflineTtsConfig(model = OfflineTtsModelConfig(
            supertonic = OfflineTtsSupertonicModelConfig(
                durationPredictor = prefix + "duration_predictor.int8.onnx",
                textEncoder = prefix + "text_encoder.int8.onnx",
                vectorEstimator = prefix + "vector_estimator.int8.onnx",
                vocoder = prefix + "vocoder.int8.onnx",
                ttsJson = prefix + "tts.json",
                unicodeIndexer = prefix + "unicode_indexer.bin",
                voiceStyle = prefix + "voice.bin",
            ), numThreads = 2, debug = false, provider = "cpu"
        )))
        checkCancelled()
        return true
    }

    fun synthesize(script: String, file: File, checkCancelled: () -> Unit, progress: (Int, Int) -> Unit): JSONObject {
        val engine = checkNotNull(tts)
        val chunks = PodcastScript.chunks(PodcastScript.normalize(script))
        val rate = engine.sampleRate()
        val deadline = SystemClock.elapsedRealtime() + 120_000
        fun checkpoint() {
            checkCancelled()
            check(SystemClock.elapsedRealtime() < deadline) { "tts_timeout" }
        }
        val partial = File(file.parentFile, "speech.part")
        var duration: Long
        PcmWav(partial, rate).use { wav ->
            chunks.forEachIndexed { index, text ->
                checkpoint()
                progress(index + 1, chunks.size)
                val audio = engine.generateWithConfigAndCallback(text,
                    GenerationConfig(sid = 0, speed = 1f, numSteps = 8, extra = mapOf("lang" to "ru")),
                    SherpaAudioCallback(::checkpoint))
                checkpoint()
                check(audio.sampleRate == rate && audio.samples.isNotEmpty()) { "invalid_tts_audio" }
                wav.append(audio.samples)
            }
            duration = wav.finish()
        }
        check(partial.renameTo(file)) { "wav_rename_failed" }
        return JSONObject().put("sampleRate", rate).put("pcmDurationMs", duration).put("wavBytes", file.length())
    }
}
