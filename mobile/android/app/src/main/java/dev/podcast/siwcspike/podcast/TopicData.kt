package dev.podcast.siwcspike.podcast

import androidx.room.*

@Entity(tableName = "topics")
data class TopicRecord(@PrimaryKey val id: String, val rootNodeId: String, val title: String,
    val goal: String, val outlineDepth: Int = 2, val revision: Long = 0,
    val createdAt: Long, val updatedAt: Long,
    @ColumnInfo(defaultValue = "'ru_standard'") val languagePolicy: String = "ru_standard",
    @ColumnInfo(defaultValue = "''") val contentSettings: String = "")

@Entity(tableName = "topic_nodes", foreignKeys = [
    ForeignKey(entity = TopicRecord::class, parentColumns = ["id"], childColumns = ["topicId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = TopicNodeRecord::class, parentColumns = ["id", "topicId"],
        childColumns = ["parentId", "topicId"], onDelete = ForeignKey.CASCADE)
], indices = [Index(value = ["id", "topicId"], unique = true), Index(value = ["parentId", "topicId"]),
    Index(value = ["topicId", "rootSlot"], unique = true)])
data class TopicNodeRecord(@PrimaryKey val id: String, val topicId: String, val parentId: String?,
    val kind: String, val title: String, val description: String = "", val position: Int = 0,
    val revision: Long = 0, val rootSlot: Int? = null)

@Entity(tableName = "node_podcasts", primaryKeys = ["nodeId", "podcastId"], foreignKeys = [
    ForeignKey(entity = TopicNodeRecord::class, parentColumns = ["id"], childColumns = ["nodeId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = PodcastRecord::class, parentColumns = ["id"], childColumns = ["podcastId"], onDelete = ForeignKey.CASCADE)
], indices = [Index(value = ["podcastId"]), Index(value = ["nodeId", "profile", "mainSlot"], unique = true)])
data class NodePodcastRecord(val nodeId: String, val podcastId: String, val role: String,
    val profile: String, val position: Int, val mainSlot: Int? = null)

data class PodcastTopicMembership(val podcastId: String, val topicId: String, val title: String)

@Dao
interface TopicDao {
    @Insert fun insert(topic: TopicRecord)
    @Update fun update(topic: TopicRecord)
    @Insert fun insert(node: TopicNodeRecord)
    @Update fun update(node: TopicNodeRecord)
    @Upsert fun link(link: NodePodcastRecord)
    @Query("SELECT * FROM topics ORDER BY updatedAt DESC, id") fun all(): List<TopicRecord>
    @Query("SELECT * FROM topics WHERE id = :id") fun topic(id: String): TopicRecord?
    @Query("SELECT * FROM topic_nodes WHERE topicId = :id ORDER BY position, id") fun nodes(id: String): List<TopicNodeRecord>
    @Query("SELECT * FROM node_podcasts WHERE nodeId IN (SELECT id FROM topic_nodes WHERE topicId = :id)")
    fun links(id: String): List<NodePodcastRecord>
    @Query("SELECT DISTINCT node_podcasts.podcastId AS podcastId, topics.id AS topicId, topics.title AS title FROM node_podcasts JOIN topic_nodes ON topic_nodes.id = node_podcasts.nodeId JOIN topics ON topics.id = topic_nodes.topicId ORDER BY topics.title, topics.id")
    fun podcastMemberships(): List<PodcastTopicMembership>
    @Query("DELETE FROM topics WHERE id = :id") fun deleteTopic(id: String)
    @Query("DELETE FROM topic_nodes WHERE id = :id") fun deleteNode(id: String)
    @Query("DELETE FROM node_podcasts WHERE nodeId = :node AND podcastId = :podcast") fun unlink(node: String, podcast: String)
    @Query("UPDATE topics SET revision = revision + 1, updatedAt = :now WHERE id IN (SELECT topicId FROM topic_nodes JOIN node_podcasts ON topic_nodes.id = node_podcasts.nodeId WHERE podcastId = :podcast)")
    fun invalidatePodcast(podcast: String, now: Long)
    @Query("SELECT * FROM podcasts WHERE status IN ('READY', 'FILE_MISSING') AND searchTitle LIKE :pattern ESCAPE '\\' ORDER BY createdAt DESC, id LIMIT 51 OFFSET :offset")
    fun searchPodcasts(pattern: String, offset: Int): List<PodcastRecord>
}
