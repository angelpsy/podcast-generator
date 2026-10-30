package dev.podcast.siwcspike.podcast

import androidx.room.*
import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.util.Locale

@Entity(tableName = "podcasts")
data class PodcastRecord(@PrimaryKey val id: String, val title: String, val searchTitle: String,
    val createdAt: Long, val status: String = "DRAFT")

// Publication and draft checkpoints are JSON to preserve the existing, tested generation protocol.
@Entity(tableName = "audio_assets", foreignKeys = [ForeignKey(entity = PodcastRecord::class,
    parentColumns = ["id"], childColumns = ["id"], onDelete = ForeignKey.CASCADE)])
data class AudioAssetRecord(@PrimaryKey val id: String, val journal: String)

@Entity(tableName = "generation_jobs", foreignKeys = [ForeignKey(entity = PodcastRecord::class,
    parentColumns = ["id"], childColumns = ["id"], onDelete = ForeignKey.CASCADE)])
data class GenerationJobRecord(@PrimaryKey val id: String, val metadata: String, val directory: String)

@Entity(tableName = "migration_state")
data class LibraryMigration(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "playback_state")
data class PlaybackRecord(@PrimaryKey val id: Int = 1, val snapshot: String)

@Dao
interface PodcastDao {
    @Upsert fun playback(record: PlaybackRecord)
    @Query("SELECT snapshot FROM playback_state WHERE id = 1") fun playback(): String?
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insert(record: PodcastRecord)
    @Upsert fun asset(record: AudioAssetRecord)
    @Upsert fun job(record: GenerationJobRecord)
    @Upsert fun marker(record: LibraryMigration)
    @Query("SELECT value FROM migration_state WHERE `key` = :key") fun marker(key: String): String?
    @Query("SELECT * FROM podcasts WHERE id = :id") fun record(id: String): PodcastRecord?
    @Query("SELECT * FROM audio_assets WHERE id = :id") fun asset(id: String): AudioAssetRecord?
    @Query("SELECT * FROM generation_jobs WHERE id = :id") fun job(id: String): GenerationJobRecord?
    @Query("SELECT * FROM podcasts ORDER BY createdAt DESC, id") fun all(): List<PodcastRecord>
    @Query("SELECT * FROM podcasts WHERE searchTitle LIKE :pattern ESCAPE '\\' ORDER BY createdAt DESC, id")
    fun search(pattern: String): List<PodcastRecord>
    @Query("UPDATE podcasts SET status = :status WHERE id = :id") fun status(id: String, status: String)
    @Query("DELETE FROM generation_jobs WHERE id = :id") fun deleteJob(id: String)
    @Query("DELETE FROM podcasts WHERE id = :id") fun delete(id: String)
}

@Database(entities = [PodcastRecord::class, AudioAssetRecord::class, GenerationJobRecord::class,
    LibraryMigration::class, PlaybackRecord::class, TopicRecord::class, TopicNodeRecord::class, NodePodcastRecord::class, OutlineProposalRecord::class, GenerationTask::class, PlaylistRecord::class, PlaylistEntry::class], version = 6, exportSchema = true)
abstract class PodcastDatabase : RoomDatabase() {
    abstract fun podcasts(): PodcastDao
    abstract fun topics(): TopicDao
    abstract fun outlines(): OutlineDao
    abstract fun generationQueue(): GenerationQueueDao
    abstract fun playlists(): PlaylistDao
}

object LibrarySearch {
    fun normalize(value: String) = value.lowercase(Locale.ROOT).replace('ё', 'е')
    fun pattern(value: String) = "%" + normalize(value.trim()).replace("\\", "\\\\")
        .replace("%", "\\%").replace("_", "\\_") + "%"
}

object PodcastDatabases {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `playback_state` (`id` INTEGER NOT NULL, `snapshot` TEXT NOT NULL, PRIMARY KEY(`id`))")
        }
    }
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `topics` (`id` TEXT NOT NULL, `rootNodeId` TEXT NOT NULL, `title` TEXT NOT NULL, `goal` TEXT NOT NULL, `outlineDepth` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `topic_nodes` (`id` TEXT NOT NULL, `topicId` TEXT NOT NULL, `parentId` TEXT, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `position` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `rootSlot` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`topicId`) REFERENCES `topics`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`parentId`, `topicId`) REFERENCES `topic_nodes`(`id`, `topicId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_topic_nodes_id_topicId` ON `topic_nodes` (`id`, `topicId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_topic_nodes_parentId_topicId` ON `topic_nodes` (`parentId`, `topicId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_topic_nodes_topicId_rootSlot` ON `topic_nodes` (`topicId`, `rootSlot`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `node_podcasts` (`nodeId` TEXT NOT NULL, `podcastId` TEXT NOT NULL, `role` TEXT NOT NULL, `profile` TEXT NOT NULL, `position` INTEGER NOT NULL, `mainSlot` INTEGER, PRIMARY KEY(`nodeId`, `podcastId`), FOREIGN KEY(`nodeId`) REFERENCES `topic_nodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`podcastId`) REFERENCES `podcasts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_node_podcasts_podcastId` ON `node_podcasts` (`podcastId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_node_podcasts_nodeId_profile_mainSlot` ON `node_podcasts` (`nodeId`, `profile`, `mainSlot`)")
        }
    }
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `outline_proposals` (`id` TEXT NOT NULL, `topicId` TEXT NOT NULL, `nodeId` TEXT NOT NULL, `baseRevision` INTEGER NOT NULL, `reviewedRevision` INTEGER NOT NULL, `mode` TEXT NOT NULL, `depth` INTEGER NOT NULL, `maxNodes` INTEGER NOT NULL, `model` TEXT NOT NULL, `input` TEXT NOT NULL, `status` TEXT NOT NULL, `payload` TEXT NOT NULL, `error` TEXT NOT NULL, `actualModel` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`topicId`) REFERENCES `topics`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`nodeId`, `topicId`) REFERENCES `topic_nodes`(`id`, `topicId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_outline_proposals_topicId` ON `outline_proposals` (`topicId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_outline_proposals_nodeId_topicId` ON `outline_proposals` (`nodeId`, `topicId`)")
        }
    }
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE topics ADD COLUMN languagePolicy TEXT NOT NULL DEFAULT 'ru_standard'")
            db.execSQL("ALTER TABLE topics ADD COLUMN contentSettings TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE TABLE IF NOT EXISTS generation_queue (id TEXT NOT NULL, batchId TEXT NOT NULL, title TEXT NOT NULL, topicId TEXT NOT NULL, nodeId TEXT NOT NULL, profile TEXT NOT NULL, settings TEXT NOT NULL, input TEXT NOT NULL, fingerprint TEXT NOT NULL, model TEXT NOT NULL, status TEXT NOT NULL, phase TEXT NOT NULL, error TEXT NOT NULL, createdAt INTEGER NOT NULL, attempt INTEGER NOT NULL, PRIMARY KEY(id))")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_generation_queue_batchId ON generation_queue(batchId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_generation_queue_nodeId_profile ON generation_queue(nodeId, profile)")
        }
    }
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS playlists (id TEXT NOT NULL, title TEXT NOT NULL, revision INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
            db.execSQL("CREATE TABLE IF NOT EXISTS playlist_entries (playlistId TEXT NOT NULL, podcastId TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(playlistId,podcastId), FOREIGN KEY(playlistId) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(podcastId) REFERENCES podcasts(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_entries_podcastId ON playlist_entries(podcastId)")
        }
    }
    @Volatile private var instance: PodcastDatabase? = null
    fun get(context: Context): PodcastDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, PodcastDatabase::class.java,
            File(context.noBackupFilesDir, "podcast/library.db").apply { parentFile!!.mkdirs() }.path)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { instance = it }
    }
}
