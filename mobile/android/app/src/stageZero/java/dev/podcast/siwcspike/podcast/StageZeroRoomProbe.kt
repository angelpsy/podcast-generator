package dev.podcast.siwcspike.podcast

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.util.UUID

@Entity(tableName = "probe_topics")
data class ProbeTopic(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(defaultValue = "'ru'") val language: String = "ru",
)

@Entity(tableName = "probe_questions", foreignKeys = [ForeignKey(
    entity = ProbeTopic::class, parentColumns = ["id"], childColumns = ["topic_id"], onDelete = ForeignKey.CASCADE,
)], indices = [Index("topic_id")])
data class ProbeQuestion(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "topic_id") val topicId: String,
    val title: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

@Dao
abstract class ProbeDao {
    @Insert abstract fun insertTopic(topic: ProbeTopic)
    @Insert abstract fun insertQuestions(questions: List<ProbeQuestion>)
    @Query("SELECT * FROM probe_topics ORDER BY sort_order") abstract fun topics(): List<ProbeTopic>
    @Query("SELECT * FROM probe_questions WHERE topic_id = :topicId ORDER BY sort_order")
    abstract fun questions(topicId: String): List<ProbeQuestion>
    @Query("UPDATE probe_topics SET title = :title WHERE id = :id") abstract fun rename(id: String, title: String)
    @Query("DELETE FROM probe_questions WHERE topic_id = :topicId") abstract fun clearQuestions(topicId: String)
    @Query("DELETE FROM probe_topics WHERE id = :id") abstract fun deleteTopic(id: String)
    @Transaction
    open fun replaceQuestions(topicId: String, questions: List<ProbeQuestion>) {
        clearQuestions(topicId)
        insertQuestions(questions)
    }
}

@Database(entities = [ProbeTopic::class, ProbeQuestion::class], version = 2, exportSchema = true)
abstract class StageZeroDatabase : RoomDatabase() { abstract fun probeDao(): ProbeDao }

/** Uses a unique cache DB, never the planned product DB or existing SIWC/podcast files. */
object StageZeroRoomProbe {
    fun run(context: Context): JSONObject {
        val file = File(context.cacheDir, "stage0-room-${UUID.randomUUID()}.db")
        var database: StageZeroDatabase? = null
        val migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE probe_topics ADD COLUMN language TEXT NOT NULL DEFAULT 'ru'")
            }
        }
        fun open() = Room.databaseBuilder(context, StageZeroDatabase::class.java, file.absolutePath)
            .addMigrations(migration).build()
        fun constraint(block: () -> Unit) {
            var failed = false
            try { block() } catch (_: SQLiteConstraintException) { failed = true }
            check(failed) { "Expected SQLite constraint failure" }
        }
        try {
            // A real version-1 SQLite fixture verifies Room migration and generated schema validation.
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                old.execSQL("CREATE TABLE probe_topics (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, sort_order INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE probe_questions (id TEXT NOT NULL PRIMARY KEY, topic_id TEXT NOT NULL, title TEXT NOT NULL, sort_order INTEGER NOT NULL, FOREIGN KEY(topic_id) REFERENCES probe_topics(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                old.execSQL("CREATE INDEX index_probe_questions_topic_id ON probe_questions(topic_id)")
                old.execSQL("INSERT INTO probe_topics VALUES ('legacy', 'Сохранённый топик', 0)")
                old.version = 1
            }
            database = open()
            val dao = database.probeDao()
            check(dao.topics().single() == ProbeTopic("legacy", "Сохранённый топик", 0, "ru"))
            check(database.openHelper.readableDatabase.version == 2)
            dao.insertTopic(ProbeTopic("science", "Научпоп", 1, "en"))
            val first = ProbeQuestion("first", "science", "Откуда берётся свет?", 0)
            val second = ProbeQuestion("second", "science", "Как измерить спектр?", 1)
            dao.insertQuestions(listOf(second, first))
            check(dao.questions("science") == listOf(first, second))
            constraint { dao.replaceQuestions("science", listOf(first, first)) }
            check(dao.questions("science") == listOf(first, second)) { "Transaction did not roll back" }
            constraint { dao.insertQuestions(listOf(ProbeQuestion("orphan", "missing", "Недопустимый вопрос", 0))) }
            dao.rename("science", "Научный рассказ")
            database.close()
            database = open()
            check(database.probeDao().topics().last().title == "Научный рассказ")
            check(database.probeDao().questions("science").size == 2)
            database.probeDao().deleteTopic("science")
            check(database.probeDao().questions("science").isEmpty()) { "Cascade did not delete questions" }
            return JSONObject().put("roomVersion", "2.8.5").put("kspVersion", "2.1.20-2.0.1")
                .put("generatedDatabaseClass", database.javaClass.name).put("migration1to2", true)
                .put("crudAndOrdering", true).put("transactionRollback", true).put("foreignKey", true)
                .put("cascadeDelete", true).put("closeAndReopen", true)
        } finally {
            database?.close()
            SQLiteDatabase.deleteDatabase(file)
        }
    }
}
