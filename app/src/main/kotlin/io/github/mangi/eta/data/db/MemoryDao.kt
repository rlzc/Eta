package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 检索用的轻量投影，避免把正文全量载入内存。 */
internal data class MemorySearchRow(
    val id: Long,
    val title: String,
    val content: String,
    val folderPath: String?,
    val importance: Float,
    val updatedAt: Long,
)

internal data class MemoryTitleRow(
    val id: Long,
    val title: String,
    val folderPath: String?,
)

internal data class MemoryTagRow(
    val memoryId: Long,
    val name: String,
)

internal data class MemoryEmbeddingRow(
    val id: Long,
    val embedding: ByteArray?,
)

@Dao
internal interface MemorySpaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(space: MemorySpaceEntity)

    @Query("SELECT * FROM memory_spaces ORDER BY created_at ASC")
    suspend fun list(): List<MemorySpaceEntity>

    @Query("SELECT * FROM memory_spaces ORDER BY created_at ASC")
    fun observe(): Flow<List<MemorySpaceEntity>>

    @Query("SELECT * FROM memory_spaces WHERE id = :id LIMIT 1")
    suspend fun find(id: String): MemorySpaceEntity?

    @Query("DELETE FROM memory_spaces WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
internal interface MemoryDao {
    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): MemoryEntity?

    @Query("SELECT * FROM memories WHERE space_id = :spaceId AND title = :title LIMIT 1")
    suspend fun findByTitle(spaceId: String, title: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE space_id = :spaceId ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")
    suspend fun listBySpace(spaceId: String, limit: Int, offset: Int): List<MemoryEntity>

    @Query(
        "SELECT id, title, content, folder_path AS folderPath, importance, updated_at AS updatedAt " +
            "FROM memories WHERE space_id = :spaceId",
    )
    suspend fun searchRows(spaceId: String): List<MemorySearchRow>

    @Query("SELECT id, title, folder_path AS folderPath FROM memories WHERE space_id = :spaceId ORDER BY title")
    suspend fun titleRows(spaceId: String): List<MemoryTitleRow>

    @Query(
        "SELECT DISTINCT folder_path FROM memories WHERE space_id = :spaceId " +
            "AND folder_path IS NOT NULL AND folder_path != '' ORDER BY folder_path",
    )
    suspend fun listFolderPaths(spaceId: String): List<String>

    @Query("SELECT COUNT(*) FROM memories WHERE space_id = :spaceId")
    suspend fun count(spaceId: String): Int

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memories WHERE space_id = :spaceId")
    suspend fun deleteBySpace(spaceId: String)

    @Query("UPDATE memories SET space_id = :spaceId, updated_at = :now WHERE id = :id")
    suspend fun moveToSpace(id: Long, spaceId: String, now: Long)

    @Query("UPDATE memories SET last_accessed_at = :now WHERE id IN (:ids)")
    suspend fun touch(ids: List<Long>, now: Long)

    // ---------- 标签 ----------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: MemoryTagEntity): Long

    @Query("SELECT * FROM memory_tags WHERE space_id = :spaceId AND name = :name LIMIT 1")
    suspend fun findTag(spaceId: String, name: String): MemoryTagEntity?

    @Query("SELECT * FROM memory_tags WHERE space_id = :spaceId ORDER BY name")
    suspend fun listTags(spaceId: String): List<MemoryTagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagRef(ref: MemoryTagRefEntity)

    @Query("DELETE FROM memory_tag_refs WHERE memory_id = :memoryId")
    suspend fun clearTagRefs(memoryId: Long)

    @Query(
        "SELECT t.name FROM memory_tags t INNER JOIN memory_tag_refs r ON t.id = r.tag_id " +
            "WHERE r.memory_id = :memoryId ORDER BY t.name",
    )
    suspend fun listTagNames(memoryId: Long): List<String>

    @Query(
        "SELECT r.memory_id FROM memory_tag_refs r INNER JOIN memory_tags t ON t.id = r.tag_id " +
            "WHERE t.space_id = :spaceId AND t.name IN (:names)",
    )
    suspend fun memoryIdsByTagNames(spaceId: String, names: List<String>): List<Long>

    @Query(
        "SELECT r.memory_id AS memoryId, t.name AS name FROM memory_tag_refs r " +
            "INNER JOIN memory_tags t ON t.id = r.tag_id WHERE t.space_id = :spaceId",
    )
    suspend fun tagRows(spaceId: String): List<MemoryTagRow>

    @Query("SELECT id, embedding FROM memories WHERE space_id = :spaceId AND embedding IS NOT NULL")
    suspend fun embeddingRows(spaceId: String): List<MemoryEmbeddingRow>

    // ---------- 属性 ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProperty(property: MemoryPropertyEntity)

    @Query("SELECT * FROM memory_properties WHERE memory_id = :memoryId ORDER BY property_key")
    suspend fun listProperties(memoryId: Long): List<MemoryPropertyEntity>

    @Query("DELETE FROM memory_properties WHERE memory_id = :memoryId")
    suspend fun clearProperties(memoryId: Long)

    // ---------- 链接 ----------

    @Insert
    suspend fun insertLink(link: MemoryLinkEntity): Long

    @Query("SELECT * FROM memory_links WHERE id = :id LIMIT 1")
    suspend fun findLink(id: Long): MemoryLinkEntity?

    @Query("SELECT * FROM memory_links WHERE space_id = :spaceId")
    suspend fun listLinks(spaceId: String): List<MemoryLinkEntity>

    @Query("SELECT * FROM memory_links WHERE source_id = :memoryId")
    suspend fun outgoingLinks(memoryId: Long): List<MemoryLinkEntity>

    @Query("SELECT * FROM memory_links WHERE target_id = :memoryId")
    suspend fun backLinks(memoryId: Long): List<MemoryLinkEntity>

    @Query("DELETE FROM memory_links WHERE id = :id")
    suspend fun deleteLink(id: Long)

    @Query("DELETE FROM memory_links WHERE source_id = :memoryId OR target_id = :memoryId")
    suspend fun deleteLinksFor(memoryId: Long)

    @Query("UPDATE memory_links SET type = :type, weight = :weight, description = :description WHERE id = :id")
    suspend fun updateLink(id: Long, type: String, weight: Float, description: String)

    // ---------- 文档分块 ----------

    @Insert
    suspend fun insertChunk(chunk: MemoryChunkEntity): Long

    @Query("SELECT * FROM memory_chunks WHERE memory_id = :memoryId ORDER BY chunk_index")
    suspend fun listChunks(memoryId: Long): List<MemoryChunkEntity>

    @Query("SELECT * FROM memory_chunks WHERE memory_id = :memoryId AND chunk_index = :index LIMIT 1")
    suspend fun findChunk(memoryId: Long, index: Int): MemoryChunkEntity?

    @Query("DELETE FROM memory_chunks WHERE memory_id = :memoryId")
    suspend fun deleteChunks(memoryId: Long)
}
