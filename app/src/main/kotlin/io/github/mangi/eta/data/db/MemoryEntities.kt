package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 记忆空间：一组独立记忆的容器。
 *
 * 约定：
 * - `global` 为现实/工作记忆的默认空间，所有会话可读
 * - `character:<id>` 为角色私有剧情空间，只在对应角色会话可读写
 */
@Serializable
@Entity(
    tableName = "memory_spaces",
    indices = [Index(value = ["name"], unique = true)],
)
internal data class MemorySpaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "auto_update_enabled") val autoUpdateEnabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** 一条结构化记忆。正文以纯文本/Markdown 存，分块内容另存 [MemoryChunkEntity]。 */
@Serializable
@Entity(
    tableName = "memories",
    indices = [
        Index("space_id"),
        Index("folder_path"),
        Index(value = ["space_id", "title"]),
        Index("updated_at"),
    ],
)
internal data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: String,
    val uuid: String,
    val title: String,
    val content: String,
    @ColumnInfo(name = "content_type") val contentType: String = "text/plain",
    val source: String = "unknown",
    val credibility: Float = 0.5f,
    val importance: Float = 0.5f,
    @ColumnInfo(name = "folder_path") val folderPath: String? = null,
    @ColumnInfo(name = "document_path") val documentPath: String? = null,
    @ColumnInfo(name = "is_document") val isDocument: Boolean = false,
    /** 预留：向量嵌入。第一版不写入，检索走关键词/标签/链接。 */
    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_accessed_at") val lastAccessedAt: Long,
) {
    override fun equals(other: Any?): Boolean = this === other || (other is MemoryEntity && other.id == id)
    override fun hashCode(): Int = id.hashCode()
}

/** 层级标签，parentId 为空表示顶层标签。 */
@Serializable
@Entity(
    tableName = "memory_tags",
    indices = [Index(value = ["space_id", "name"], unique = true), Index("parent_id")],
)
internal data class MemoryTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: String,
    val name: String,
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,
)

@Entity(
    tableName = "memory_tag_refs",
    primaryKeys = ["memory_id", "tag_id"],
    indices = [Index("tag_id")],
)
internal data class MemoryTagRefEntity(
    @ColumnInfo(name = "memory_id") val memoryId: Long,
    @ColumnInfo(name = "tag_id") val tagId: Long,
)

@Entity(
    tableName = "memory_properties",
    primaryKeys = ["memory_id", "property_key"],
)
internal data class MemoryPropertyEntity(
    @ColumnInfo(name = "memory_id") val memoryId: Long,
    @ColumnInfo(name = "property_key") val propertyKey: String,
    @ColumnInfo(name = "property_value") val propertyValue: String,
)

/** 记忆之间的有向关系，反向查询用 target_id 索引。 */
@Serializable
@Entity(
    tableName = "memory_links",
    indices = [Index("source_id"), Index("target_id"), Index("space_id")],
)
internal data class MemoryLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    @ColumnInfo(name = "target_id") val targetId: Long,
    val type: String,
    val weight: Float = 1f,
    val description: String = "",
)

/** 长文档的分块，保留以便按块读取与后续向量检索。 */
@Serializable
@Entity(
    tableName = "memory_chunks",
    indices = [Index(value = ["memory_id", "chunk_index"], unique = true)],
)
internal data class MemoryChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "memory_id") val memoryId: Long,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    val content: String,
    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray? = null,
)
