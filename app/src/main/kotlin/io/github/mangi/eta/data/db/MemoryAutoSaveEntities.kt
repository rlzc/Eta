package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 自动保存候选：一轮对话结束后排队，稍后由模型提取成结构化记忆。
 *
 * 状态机：pending → processing → done / failed（可重试）。
 */
@Entity(
    tableName = "memory_auto_save_candidates",
    indices = [Index("status"), Index("conversation_id")],
)
internal data class MemoryAutoSaveCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "user_text") val userText: String,
    @ColumnInfo(name = "assistant_text") val assistantText: String,
    val status: String = STATUS_PENDING,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_PROCESSING = "processing"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
        const val MAX_ATTEMPTS = 3
    }
}

@Dao
internal interface MemoryAutoSaveDao {
    @Insert
    suspend fun insert(candidate: MemoryAutoSaveCandidateEntity): Long

    @Query("SELECT * FROM memory_auto_save_candidates WHERE status = 'pending' ORDER BY created_at ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<MemoryAutoSaveCandidateEntity>

    @Query("SELECT * FROM memory_auto_save_candidates ORDER BY created_at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MemoryAutoSaveCandidateEntity>

    @Query("SELECT * FROM memory_auto_save_candidates WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): MemoryAutoSaveCandidateEntity?

    @Query(
        "UPDATE memory_auto_save_candidates SET status = :status, " +
            "attempt_count = attempt_count + :attemptDelta, last_error = :error, updated_at = :now " +
            "WHERE id = :id",
    )
    suspend fun mark(id: Long, status: String, error: String, now: Long, attemptDelta: Int = 0)

    @Query("SELECT COUNT(*) FROM memory_auto_save_candidates WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("DELETE FROM memory_auto_save_candidates WHERE status = 'done' AND updated_at < :before")
    suspend fun pruneDone(before: Long)
}
