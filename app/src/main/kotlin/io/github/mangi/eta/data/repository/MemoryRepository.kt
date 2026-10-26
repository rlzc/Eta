package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.MemoryDao
import io.github.mangi.eta.data.db.MemoryChunkEntity
import io.github.mangi.eta.data.db.MemoryEntity
import io.github.mangi.eta.data.db.MemoryLinkEntity
import io.github.mangi.eta.data.db.MemoryPropertyEntity
import io.github.mangi.eta.data.db.MemorySpaceDao
import io.github.mangi.eta.data.db.MemorySpaceEntity
import io.github.mangi.eta.data.db.MemoryTagEntity
import io.github.mangi.eta.data.db.MemoryTagRefEntity
import io.github.mangi.eta.data.db.MemoryTitleRow
import java.util.UUID

/**
 * 结构化记忆库的读写入口（Operit 记忆库模型的 Eta 实现）。
 *
 * 空间约定：
 * - [GLOBAL_SPACE_ID]：现实/工作记忆，所有会话可读
 * - `character:<id>`：角色私有剧情空间
 */
internal class MemoryRepository(
    private val memoryDao: MemoryDao,
    private val spaceDao: MemorySpaceDao,
    private val embeddingProvider: MemoryEmbeddingProvider? = null,
) {
    constructor(context: Context) : this(
        EtaDatabase.get(context).memoryDao(),
        EtaDatabase.get(context).memorySpaceDao(),
    )

    companion object {
        const val GLOBAL_SPACE_ID = "global"
        const val CHARACTER_SPACE_PREFIX = "character:"
        const val SOURCE_USER = "user_input"
        const val SOURCE_AGENT = "agent_tool"
        const val SOURCE_AUTO = "chat_summary"
        const val SOURCE_IMPORT = "import"

        fun characterSpaceId(characterId: String): String = CHARACTER_SPACE_PREFIX + characterId

        fun isCharacterSpace(spaceId: String): Boolean = spaceId.startsWith(CHARACTER_SPACE_PREFIX)

        private const val MAX_SNAPSHOTS = 32
    }

    // ---------- 空间 ----------

    suspend fun ensureSpace(id: String, name: String) {
        if (spaceDao.find(id) != null) return
        val now = System.currentTimeMillis()
        spaceDao.upsert(MemorySpaceEntity(id = id, name = name, createdAt = now, updatedAt = now))
    }

    suspend fun ensureDefaultSpace() = ensureSpace(GLOBAL_SPACE_ID, "现实与工作")

    suspend fun ensureCharacterSpace(characterId: String, name: String) =
        ensureSpace(characterSpaceId(characterId), name)

    suspend fun listSpaces(): List<MemorySpaceEntity> {
        ensureDefaultSpace()
        return spaceDao.list()
    }

    suspend fun findSpace(id: String): MemorySpaceEntity? = spaceDao.find(id)

    suspend fun deleteSpace(id: String) {
        if (id == GLOBAL_SPACE_ID) return
        memoryDao.deleteBySpace(id)
        spaceDao.delete(id)
    }

    // ---------- 记忆条目 ----------

    suspend fun createMemory(
        spaceId: String,
        title: String,
        content: String,
        tags: List<String> = emptyList(),
        folderPath: String? = null,
        importance: Float = 0.5f,
        credibility: Float = 0.5f,
        source: String = SOURCE_AGENT,
        contentType: String = "text/plain",
    ): Long {
        require(title.isNotBlank()) { "记忆标题不能为空" }
        val now = System.currentTimeMillis()
        val id = memoryDao.insertMemory(
            MemoryEntity(
                spaceId = spaceId,
                uuid = UUID.randomUUID().toString(),
                title = title.trim(),
                content = content,
                contentType = contentType,
                source = source,
                credibility = credibility.coerceIn(0f, 1f),
                importance = importance.coerceIn(0f, 1f),
                folderPath = folderPath?.trim()?.takeIf(String::isNotEmpty),
                embedding = embedText(title.trim(), content),
                createdAt = now,
                updatedAt = now,
                lastAccessedAt = now,
            ),
        )
        replaceTags(spaceId, id, tags)
        return id
    }

    suspend fun updateMemory(
        id: Long,
        title: String? = null,
        content: String? = null,
        tags: List<String>? = null,
        folderPath: String? = null,
        importance: Float? = null,
        credibility: Float? = null,
    ): Boolean {
        val current = memoryDao.findById(id) ?: return false
        val now = System.currentTimeMillis()
        val nextTitle = title?.trim()?.takeIf(String::isNotEmpty) ?: current.title
        val nextContent = content ?: current.content
        val nextEmbedding =
            if (title != null || content != null) {
                embedText(nextTitle, nextContent) ?: current.embedding
            } else {
                current.embedding
            }
        memoryDao.updateMemory(
            current.copy(
                title = nextTitle,
                content = nextContent,
                folderPath = folderPath?.trim()?.takeIf(String::isNotEmpty) ?: current.folderPath,
                importance = importance?.coerceIn(0f, 1f) ?: current.importance,
                credibility = credibility?.coerceIn(0f, 1f) ?: current.credibility,
                embedding = nextEmbedding,
                updatedAt = now,
            ),
        )
        if (tags != null) replaceTags(current.spaceId, id, tags)
        return true
    }

    suspend fun deleteMemory(id: Long): Boolean {
        if (memoryDao.findById(id) == null) return false
        memoryDao.clearTagRefs(id)
        memoryDao.clearProperties(id)
        memoryDao.deleteLinksFor(id)
        memoryDao.deleteChunks(id)
        memoryDao.deleteById(id)
        return true
    }

    suspend fun moveMemory(id: Long, targetSpaceId: String): Boolean {
        val current = memoryDao.findById(id) ?: return false
        if (current.spaceId == targetSpaceId) return true
        memoryDao.moveToSpace(id, targetSpaceId, System.currentTimeMillis())
        return true
    }

    suspend fun findById(id: Long): MemoryEntity? = memoryDao.findById(id)

    /**
     * 导入长文档：正文整体存一条记忆，同时按 [chunkSize] 切块写入 memory_chunks，
     * 供后续按块读取或向量化。
     */
    suspend fun importDocument(
        spaceId: String,
        title: String,
        content: String,
        tags: List<String> = emptyList(),
        folderPath: String? = null,
        chunkSize: Int = 2_000,
    ): Long {
        require(title.isNotBlank()) { "文档标题不能为空" }
        require(content.isNotBlank()) { "文档内容不能为空" }
        val now = System.currentTimeMillis()
        val id =
            memoryDao.insertMemory(
                MemoryEntity(
                    spaceId = spaceId,
                    uuid = UUID.randomUUID().toString(),
                    title = title.trim(),
                    content = content,
                    contentType = "text/markdown",
                    source = SOURCE_IMPORT,
                    folderPath = folderPath?.trim()?.takeIf(String::isNotEmpty),
                    isDocument = true,
                    embedding = embedText(title.trim(), content.take(4_000)),
                    createdAt = now,
                    updatedAt = now,
                    lastAccessedAt = now,
                ),
            )
        replaceTags(spaceId, id, tags)
        val size = chunkSize.coerceIn(200, 20_000)
        content.chunked(size).forEachIndexed { index, chunk ->
            memoryDao.insertChunk(
                MemoryChunkEntity(memoryId = id, chunkIndex = index, content = chunk),
            )
        }
        return id
    }

    suspend fun listChunks(memoryId: Long): List<MemoryChunkEntity> = memoryDao.listChunks(memoryId)
    suspend fun findByTitle(spaceId: String, title: String): MemoryEntity? =
        memoryDao.findByTitle(spaceId, title)

    suspend fun listBySpace(spaceId: String, limit: Int = 200, offset: Int = 0): List<MemoryEntity> =
        memoryDao.listBySpace(spaceId, limit, offset)

    suspend fun listTitles(spaceId: String): List<MemoryTitleRow> = memoryDao.titleRows(spaceId)

    suspend fun listFolderPaths(spaceId: String): List<String> = memoryDao.listFolderPaths(spaceId)

    suspend fun count(spaceId: String): Int = memoryDao.count(spaceId)

    suspend fun listTagNames(memoryId: Long): List<String> = memoryDao.listTagNames(memoryId)

    suspend fun listProperties(memoryId: Long): List<MemoryPropertyEntity> =
        memoryDao.listProperties(memoryId)

    suspend fun setProperty(memoryId: Long, key: String, value: String) =
        memoryDao.upsertProperty(MemoryPropertyEntity(memoryId, key, value))

    suspend fun touch(ids: List<Long>) {
        if (ids.isNotEmpty()) memoryDao.touch(ids, System.currentTimeMillis())
    }

    // ---------- 链接 ----------

    suspend fun link(
        spaceId: String,
        sourceId: Long,
        targetId: Long,
        type: String,
        weight: Float = 1f,
        description: String = "",
    ): Long = memoryDao.insertLink(
        MemoryLinkEntity(
            spaceId = spaceId,
            sourceId = sourceId,
            targetId = targetId,
            type = type,
            weight = weight.coerceAtLeast(0f),
            description = description,
        ),
    )

    suspend fun listLinks(spaceId: String): List<MemoryLinkEntity> = memoryDao.listLinks(spaceId)

    suspend fun findLink(id: Long): MemoryLinkEntity? = memoryDao.findLink(id)

    suspend fun outgoingLinks(memoryId: Long): List<MemoryLinkEntity> = memoryDao.outgoingLinks(memoryId)

    suspend fun backLinks(memoryId: Long): List<MemoryLinkEntity> = memoryDao.backLinks(memoryId)

    suspend fun deleteLink(id: Long): Boolean {
        if (memoryDao.findLink(id) == null) return false
        memoryDao.deleteLink(id)
        return true
    }

    suspend fun updateLink(id: Long, type: String, weight: Float, description: String): Boolean {
        if (memoryDao.findLink(id) == null) return false
        memoryDao.updateLink(id, type, weight.coerceAtLeast(0f), description)
        return true
    }

    // ---------- 检索 ----------

    /**
     * 三路检索（关键词/标签/链接）。
     *
     * [snapshotId] 用于同一轮任务内去重：同一个快照里已经返回过的记忆不会再返回。
     * 传空表示不启用去重。
     */
    suspend fun search(
        spaceId: String,
        query: String,
        folderPath: String? = null,
        config: MemorySearchConfig = MemorySearchConfig(),
        snapshotId: String? = null,
    ): MemorySearchOutcome {
        val rows = memoryDao.searchRows(spaceId)
        val tagsByMemory = memoryDao.tagRows(spaceId).groupBy({ it.memoryId }, { it.name })
        val links = memoryDao.listLinks(spaceId)
        val excluded = snapshotSeen(snapshotId)
        val queryVector =
            embeddingProvider?.let { provider ->
                runCatching { provider.embed(query) }.getOrNull()?.takeIf { it.isNotEmpty() }
            }
        val vectors =
            if (queryVector != null) {
                memoryDao.embeddingRows(spaceId)
                    .mapNotNull { row ->
                        MemoryVectorCodec.decode(row.embedding)?.let { row.id to it }
                    }
                    .toMap()
            } else {
                emptyMap()
            }
        val effectiveConfig =
            if (queryVector != null) {
                config.copy(vectorWeight = config.vectorWeight.takeIf { it > 0f } ?: 1.0f)
            } else {
                config.copy(vectorWeight = 0f)
            }
        val outcome = MemorySearchEngine.search(
            query = query,
            rows = rows,
            tagsByMemory = tagsByMemory,
            links = links,
            config = effectiveConfig,
            folderPath = folderPath,
            excludedIds = excluded,
            queryEmbedding = queryVector,
            embeddings = vectors,
        )
        if (!snapshotId.isNullOrBlank()) {
            recordSnapshot(snapshotId, outcome.hits.map { it.id })
        }
        if (outcome.hits.isNotEmpty()) touch(outcome.hits.map { it.id })
        return outcome
    }

    suspend fun searchAll(spaceId: String, limit: Int = 40): List<MemorySearchHit> =
        search(
            spaceId = spaceId,
            query = "*",
            config = MemorySearchConfig(limit = limit),
        ).hits

    // ---------- 查询快照 ----------

    private data class SnapshotState(
        val seen: MutableSet<Long> = HashSet(),
        var lastAccessAt: Long = System.currentTimeMillis(),
    )

    private val snapshots =
        object : LinkedHashMap<String, SnapshotState>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SnapshotState>?): Boolean =
                size > MAX_SNAPSHOTS
        }

    private fun snapshotSeen(snapshotId: String?): Set<Long> {
        if (snapshotId.isNullOrBlank()) return emptySet()
        synchronized(snapshots) {
            val state = snapshots.getOrPut(snapshotId) { SnapshotState() }
            state.lastAccessAt = System.currentTimeMillis()
            return state.seen.toSet()
        }
    }

    private fun recordSnapshot(snapshotId: String, ids: List<Long>) {
        if (ids.isEmpty()) return
        synchronized(snapshots) {
            val state = snapshots.getOrPut(snapshotId) { SnapshotState() }
            state.seen.addAll(ids)
            state.lastAccessAt = System.currentTimeMillis()
        }
    }

    fun forgetSnapshot(snapshotId: String) {
        synchronized(snapshots) { snapshots.remove(snapshotId) }
    }

    // ---------- 内部 ----------

    private suspend fun embedText(title: String, content: String): ByteArray? {
        val provider = embeddingProvider ?: return null
        val vector = runCatching { provider.embed("$title\n$content") }.getOrNull() ?: return null
        if (vector.isEmpty()) return null
        return MemoryVectorCodec.encode(vector)
    }

    private suspend fun replaceTags(spaceId: String, memoryId: Long, tags: List<String>) {
        memoryDao.clearTagRefs(memoryId)
        tags.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { name ->
                val tag = memoryDao.findTag(spaceId, name)
                    ?: run {
                        memoryDao.insertTag(MemoryTagEntity(spaceId = spaceId, name = name))
                        memoryDao.findTag(spaceId, name)
                    }
                if (tag != null) memoryDao.insertTagRef(MemoryTagRefEntity(memoryId, tag.id))
            }
    }
}
