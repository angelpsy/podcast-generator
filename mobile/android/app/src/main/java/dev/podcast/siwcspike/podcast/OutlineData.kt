package dev.podcast.siwcspike.podcast

import androidx.room.*

@Entity(tableName = "outline_proposals", foreignKeys = [
    ForeignKey(entity = TopicRecord::class, parentColumns = ["id"], childColumns = ["topicId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = TopicNodeRecord::class, parentColumns = ["id", "topicId"], childColumns = ["nodeId", "topicId"], onDelete = ForeignKey.CASCADE)
], indices = [Index(value = ["topicId"]), Index(value = ["nodeId", "topicId"])])
data class OutlineProposalRecord(@PrimaryKey val id: String, val topicId: String, val nodeId: String,
    val baseRevision: Long, val reviewedRevision: Long, val mode: String, val depth: Int, val maxNodes: Int,
    val model: String, val input: String, val status: String, val payload: String = "", val error: String = "",
    val actualModel: String = "", val createdAt: Long)

@Dao
interface OutlineDao {
    @Insert fun insert(record: OutlineProposalRecord)
    @Update fun update(record: OutlineProposalRecord)
    @Query("SELECT * FROM outline_proposals WHERE id = :id") fun find(id: String): OutlineProposalRecord?
    @Query("SELECT * FROM outline_proposals WHERE topicId = :topic AND nodeId = :node ORDER BY createdAt DESC, id DESC LIMIT 10")
    fun list(topic: String, node: String): List<OutlineProposalRecord>
    @Query("UPDATE outline_proposals SET status = 'INTERRUPTED', error = 'outline_interrupted' WHERE status = 'RUNNING'") fun recover()
}
