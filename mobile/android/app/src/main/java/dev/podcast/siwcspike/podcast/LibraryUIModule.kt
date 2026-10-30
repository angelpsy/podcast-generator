package dev.podcast.siwcspike.podcast

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.annotations.ReactModule
import dev.podcast.siwcspike.siwc.NativeLibraryUISpec
import dev.podcast.siwcspike.siwc.SafeFailure
import org.json.JSONObject
import java.util.concurrent.Executors

object AppPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("app-preferences", Context.MODE_PRIVATE)
    fun read(context: Context) = JSONObject().put("theme", prefs(context).getString("theme", "system"))
        .put("profile", prefs(context).getString("profile", "ru_standard"))
        .put("model", prefs(context).getString("model", null) ?: runCatching { AtomicAudioJournal(java.io.File(context.noBackupFilesDir, "podcast/input.json")).read().optString("model").take(200) }.getOrDefault(""))
        .put("settings", ContentSettings.parse(prefs(context).getString("settings", null)?.let(::JSONObject)).json())
    fun save(context: Context, input: JSONObject): JSONObject {
        val old = read(context); input.keys().forEach { if (it !in setOf("theme", "profile", "model", "settings")) throw TopicFailure("invalid_request") }
        val theme = input.optString("theme", old.getString("theme")); if (theme !in setOf("system", "light", "dark")) throw TopicFailure("invalid_theme")
        val profile = LanguagePolicy.validate(input.optString("profile", old.getString("profile")))
        val model = input.optString("model", old.getString("model")); if (model.length > 200) throw TopicFailure("invalid_request")
        val settings = ContentSettings.parse(input.optJSONObject("settings") ?: old.getJSONObject("settings"))
        check(prefs(context).edit().putString("theme", theme).putString("profile", profile).putString("model", model).putString("settings", settings.json().toString()).commit())
        return read(context)
    }
    fun applyTheme(context: Context) {
        val mode = when (prefs(context).getString("theme", "system")) { "dark" -> AppCompatDelegate.MODE_NIGHT_YES; "light" -> AppCompatDelegate.MODE_NIGHT_NO; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
@ReactModule(name = LibraryUIModule.NAME)
class LibraryUIModule(context: ReactApplicationContext) : NativeLibraryUISpec(context) {
    companion object { const val NAME = "LibraryUI" }
    private val worker = Executors.newSingleThreadExecutor()
    private val repo by lazy { PlaylistRepository(PodcastDatabases.get(context)) }
    override fun getName() = NAME
    private fun task(promise: Promise, action: () -> JSONObject) { worker.execute {
        try { promise.resolve(action().toString()) }
        catch (e: TopicFailure) { promise.reject(e.code, "Не удалось выполнить действие") }
        catch (e: SafeFailure) { promise.reject(e.safeCode, "Некорректные настройки") }
        catch (_: Exception) { promise.reject("library_ui_failed", "Не удалось прочитать или сохранить данные") }
    } }
    override fun getPlaylists(promise: Promise) = task(promise) { repo.list() }
    override fun getPlaylist(id: String, promise: Promise) = task(promise) { repo.detail(id) }
    override fun mutatePlaylist(request: String, promise: Promise) = task(promise) {
        if (request.length > 16000) throw TopicFailure("invalid_request"); repo.mutate(JSONObject(request))
    }
    private fun play(promise: Promise, update: Boolean = false, action: () -> List<String>) { worker.execute {
        try {
            val ids = action(); val client = PlaybackClient.get(reactApplicationContext)
            val complete: (Boolean) -> Unit = { ok -> if (ok) promise.resolve(null) else promise.reject("queue_load_failed", "Не удалось открыть очередь") }
            if (update) client.updateQueue(ids, complete) else client.playQueue(ids, complete)
        }
        catch (e: TopicFailure) { promise.reject(e.code, "Нет доступных записей") } catch (_: Exception) { promise.reject("library_ui_failed", "Не удалось открыть очередь") }
    } }
    override fun updatePlaylistQueue(id: String, promise: Promise) = play(promise, true) { repo.playIds(id) }
    override fun updateTopicQueue(topic: String, node: String, allProfiles: Boolean, promise: Promise) = play(promise, true) { repo.topicIds(topic, node, allProfiles) }
    override fun playPlaylist(id: String, promise: Promise) = play(promise) { repo.playIds(id) }
    override fun getTopicPlaylist(topic: String, node: String, allProfiles: Boolean, promise: Promise) = task(promise) { repo.topicDetail(topic, node, allProfiles) }
    override fun playTopicPlaylist(topic: String, node: String, allProfiles: Boolean, promise: Promise) = play(promise) { repo.topicIds(topic, node, allProfiles) }
    override fun getPreferences(promise: Promise) = task(promise) { AppPreferences.read(reactApplicationContext) }
    override fun savePreferences(request: String, promise: Promise) = task(promise) {
        if (request.length > 8000) throw TopicFailure("invalid_request")
        val before = AppPreferences.read(reactApplicationContext).getString("theme")
        AppPreferences.save(reactApplicationContext, JSONObject(request)).also { next ->
            if (before != next.getString("theme")) Handler(Looper.getMainLooper()).postDelayed({ AppPreferences.applyTheme(reactApplicationContext) }, 250)
        }
    }
    override fun invalidate() { worker.shutdown(); super.invalidate() }
}
