package dev.podcast.siwcspike.podcast

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

class AtomicAudioJournal(file: File) : AudioJournal {
    private val atomic = AtomicFile(file)
    override fun read(): JSONObject =
        if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists())
            JSONObject(atomic.openRead().bufferedReader().use { it.readText() }) else JSONObject()

    override fun write(state: JSONObject) {
        val stream = atomic.startWrite()
        try {
            stream.write(state.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }
}

class AndroidAudioMedia(private val context: Context) : AudioMedia {
    companion object { const val FOLDER = "Podcasts/PodcastGenerator/" }
    private val resolver = context.contentResolver
    private val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override fun find(name: String): String? {
        // Include own pending items; never search/delete the entire folder by filename prefix.
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(name, FOLDER, context.packageName))
        }
        return AudioMediaRows.find(resolver.query(collection, arrayOf(MediaStore.Audio.Media._ID), args, null)) { id ->
            Uri.withAppendedPath(collection, id.toString()).toString()
        }
    }
    override fun create(name: String): String = resolver.insert(collection, ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
        put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    })?.toString() ?: error("media_insert_failed")

    override fun copy(uri: String, source: File, checkCancelled: () -> Unit) {
        val target = Uri.parse(uri)
        resolver.openOutputStream(target, "w")?.use { output ->
            source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            output.flush()
        } ?: error("media_write_failed")
        resolver.openFileDescriptor(target, "r")?.use {
            check(it.statSize == source.length()) { "media_size_mismatch" }
        } ?: error("media_read_failed")
    }
    private fun includePending() = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }
    override fun isPublished(uri: String): Boolean = AudioMediaRows.isPublished(resolver.query(Uri.parse(uri),
        arrayOf(MediaStore.MediaColumns.IS_PENDING), includePending(), null))

    override fun publish(uri: String) {
        check(resolver.update(Uri.parse(uri), ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null) == 1) { "media_publish_failed" }
    }
    override fun delete(uri: String) {
        val target = Uri.parse(uri)
        check(target.scheme == "content" && target.authority == "media")
        AudioMediaRows.deleteOwned(resolver.query(target, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
            includePending(), null), context.packageName) { resolver.delete(target, null, null) }
    }
}
