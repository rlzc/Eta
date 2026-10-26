package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.MemoryLibraryToolCatalog
import io.github.mangi.eta.data.db.MemoryLinkEntity
import io.github.mangi.eta.data.repository.MemoryRepository
import io.github.mangi.eta.data.repository.MemorySearchConfig
import io.github.mangi.eta.data.repository.MemorySearchEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * 记忆库工具的执行器。
 *
 * 空间语义：
 * - [defaultSpaceId] 是当前会话的默认空间（普通会话为 global，角色会话为角色私有空间）
 * - 工具参数里的 `space` 可以显式覆盖，因此角色也可以主动写 `global` 的现实/工作记忆
 */
internal class MemoryLibraryTools(
    private val repository: MemoryRepository,
    private val defaultSpaceId: String,
) {
    fun handles(name: String): Boolean = name in MemoryLibraryToolCatalog.names

    suspend fun execute(name: String, args: JSONObject): String? {
        if (!handles(name)) return null
        return when (name) {
            "query_memory" -> queryMemory(args)
            "get_memory_by_title" -> getMemoryByTitle(args)
            "create_memory" -> createMemory(args)
            "create_memory_document" -> createMemoryDocument(args)
            "update_memory" -> updateMemory(args)
            "delete_memory" -> deleteMemory(args)
            "move_memory" -> moveMemory(args)
            "link_memories" -> linkMemories(args)
            "query_memory_links" -> queryMemoryLinks(args)
            "update_memory_link" -> updateMemoryLink(args)
            "delete_memory_link" -> deleteMemoryLink(args)
            "list_memory_spaces" -> listMemorySpaces()
            else -> null
        }
    }

    private suspend fun queryMemory(args: JSONObject): String {
        val query = args.optString("query")
        val space = resolveSpace(args)
        val config =
            MemorySearchConfig(
                threshold = args.optDouble("threshold", 0.0).toFloat().coerceAtLeast(0f),
                limit = args.optInt("limit", 20).coerceIn(1, 200),
            )
        val outcome =
            repository.search(
                spaceId = space,
                query = query,
                folderPath = args.optString("folder_path").takeIf { it.isNotBlank() },
                config = config,
                snapshotId = args.optString("snapshot_id").takeIf { it.isNotBlank() },
            )
        val hits =
            JSONArray().also { array ->
                outcome.hits.forEach { hit ->
                    array.put(
                        JSONObject()
                            .put("id", hit.id)
                            .put("title", hit.title)
                            .put("snippet", MemorySearchEngine.snippet(hit.content))
                            .put("score", hit.score.toDouble())
                            .put("importance", hit.importance.toDouble())
                            .put("folder", hit.folderPath ?: JSONObject.NULL)
                            .put("tags", JSONArray(hit.tags))
                            .put("reason", hit.reason),
                    )
                }
            }
        return JSONObject()
            .put("ok", true)
            .put("space", space)
            .put("count", outcome.hits.size)
            .put("total_candidates", outcome.totalCandidates)
            .put("excluded_by_snapshot", outcome.excludedBySnapshot)
            .put("hits", hits)
            .toString()
    }

    private suspend fun getMemoryByTitle(args: JSONObject): String {
        val title = args.optString("title").trim()
        require(title.isNotEmpty()) { "缺少 title" }
        val space = resolveSpace(args)
        val memory = repository.findByTitle(space, title)
            ?: return JSONObject()
                .put("ok", false)
                .put("code", "MEMORY_NOT_FOUND")
                .put("message", "空间 $space 中没有标题为「$title」的记忆")
                .toString()
        val maxChars = args.optInt("max_chars", 12_000).coerceIn(1, 32_000)
        val content = memory.content
        val truncated = content.length > maxChars
        val body = if (truncated) content.take(maxChars) else content
        return JSONObject()
            .put("ok", true)
            .put("id", memory.id)
            .put("space", memory.spaceId)
            .put("title", memory.title)
            .put("content", body)
            .put("truncated", truncated)
            .put("total_chars", content.length)
            .put("folder", memory.folderPath ?: JSONObject.NULL)
            .put("importance", memory.importance.toDouble())
            .put("tags", JSONArray(repository.listTagNames(memory.id)))
            .toString()
    }

    private suspend fun createMemory(args: JSONObject): String {
        val title = args.optString("title").trim()
        val content = args.optString("content")
        require(title.isNotEmpty()) { "缺少 title" }
        require(content.isNotEmpty()) { "缺少 content" }
        val space = resolveWriteSpace(args)
        repository.ensureSpace(space, space)
        val id =
            repository.createMemory(
                spaceId = space,
                title = title,
                content = content,
                tags = args.optJSONArray("tags").toStringList(),
                folderPath = args.optString("folder_path").takeIf { it.isNotBlank() },
                importance = args.optDouble("importance", 0.5).toFloat().coerceIn(0f, 1f),
                source = MemoryRepository.SOURCE_AGENT,
            )
        return JSONObject()
            .put("ok", true)
            .put("id", id)
            .put("space", space)
            .toString()
    }

    private suspend fun createMemoryDocument(args: JSONObject): String {
        val title = args.optString("title").trim()
        val content = args.optString("content")
        require(title.isNotEmpty()) { "缺少 title" }
        require(content.isNotEmpty()) { "缺少 content" }
        val space = resolveWriteSpace(args)
        val id =
            repository.importDocument(
                spaceId = space,
                title = title,
                content = content,
                tags = args.optJSONArray("tags").toStringList(),
                folderPath = args.optString("folder_path").takeIf { it.isNotBlank() },
                chunkSize = args.optInt("chunk_size", 2_000),
            )
        return JSONObject()
            .put("ok", true)
            .put("id", id)
            .put("space", space)
            .put("chunks", repository.listChunks(id).size)
            .toString()
    }

    private suspend fun updateMemory(args: JSONObject): String {        val id = args.requiredId()
        val updated =
            repository.updateMemory(
                id = id,
                title = args.optString("title").takeIf { it.isNotBlank() },
                content = args.optString("content").takeIf { it.isNotEmpty() },
                tags = args.optJSONArray("tags").toStringListOrNull(),
                folderPath = args.optString("folder_path").takeIf { it.isNotBlank() },
                importance = args.optDouble("importance", Double.NaN).takeUnless { it.isNaN() }?.toFloat(),
            )
        return JSONObject()
            .put("ok", updated)
            .put("id", id)
            .apply { if (!updated) put("code", "MEMORY_NOT_FOUND") }
            .toString()
    }

    private suspend fun deleteMemory(args: JSONObject): String {
        val id = args.requiredId()
        val deleted = repository.deleteMemory(id)
        return JSONObject()
            .put("ok", deleted)
            .put("id", id)
            .apply { if (!deleted) put("code", "MEMORY_NOT_FOUND") }
            .toString()
    }

    private suspend fun moveMemory(args: JSONObject): String {
        val id = args.requiredId()
        val space = args.optString("space").trim()
        require(space.isNotEmpty()) { "缺少 space" }
        repository.ensureSpace(space, space)
        val moved = repository.moveMemory(id, space)
        return JSONObject()
            .put("ok", moved)
            .put("id", id)
            .put("space", space)
            .apply { if (!moved) put("code", "MEMORY_NOT_FOUND") }
            .toString()
    }

    private suspend fun linkMemories(args: JSONObject): String {
        val sourceId = args.optLong("source_id", -1L).also { require(it > 0) { "缺少 source_id" } }
        val targetId = args.optLong("target_id", -1L).also { require(it > 0) { "缺少 target_id" } }
        val type = args.optString("type").ifBlank { "related" }
        val source = repository.findById(sourceId)
        val target = repository.findById(targetId)
        require(source != null) { "source_id 不存在：$sourceId" }
        require(target != null) { "target_id 不存在：$targetId" }
        require(source.spaceId == target.spaceId) { "跨空间的记忆不能直接建链，请先移动" }
        val linkId =
            repository.link(
                spaceId = source.spaceId,
                sourceId = sourceId,
                targetId = targetId,
                type = type,
                weight = args.optDouble("weight", 1.0).toFloat().coerceIn(0f, 2f),
                description = args.optString("description"),
            )
        return JSONObject()
            .put("ok", true)
            .put("link_id", linkId)
            .put("space", source.spaceId)
            .toString()
    }

    private suspend fun queryMemoryLinks(args: JSONObject): String {
        val id = args.requiredId()
        val outgoing = renderLinks(repository.outgoingLinks(id))
        val incoming = renderLinks(repository.backLinks(id))
        return JSONObject()
            .put("ok", true)
            .put("id", id)
            .put("outgoing", outgoing)
            .put("incoming", incoming)
            .toString()
    }

    private suspend fun updateMemoryLink(args: JSONObject): String {
        val linkId = args.optLong("link_id", -1L)
        require(linkId > 0) { "缺少 link_id" }
        val existing =
            repository.findLink(linkId)
                ?: return JSONObject()
                    .put("ok", false)
                    .put("link_id", linkId)
                    .put("code", "LINK_NOT_FOUND")
                    .toString()
        val type = args.optString("type").takeIf { it.isNotBlank() } ?: existing.type
        val weight =
            args.optDouble("weight", Double.NaN)
                .takeUnless { it.isNaN() }
                ?.toFloat()
                ?.coerceIn(0f, 2f)
                ?: existing.weight
        val description = args.optString("description").takeIf { it.isNotEmpty() } ?: existing.description
        val updated = repository.updateLink(linkId, type, weight, description)
        return JSONObject().put("ok", updated).put("link_id", linkId).toString()
    }

    private suspend fun deleteMemoryLink(args: JSONObject): String {
        val linkId = args.optLong("link_id", -1L)
        require(linkId > 0) { "缺少 link_id" }
        val deleted = repository.deleteLink(linkId)
        return JSONObject()
            .put("ok", deleted)
            .put("link_id", linkId)
            .apply { if (!deleted) put("code", "LINK_NOT_FOUND") }
            .toString()
    }

    private fun renderLinks(links: List<MemoryLinkEntity>): JSONArray =
        JSONArray().also { array ->
            links.forEach { link ->
                array.put(
                    JSONObject()
                        .put("link_id", link.id)
                        .put("source_id", link.sourceId)
                        .put("target_id", link.targetId)
                        .put("type", link.type)
                        .put("weight", link.weight.toDouble())
                        .put("description", link.description),
                )
            }
        }

    private suspend fun listMemorySpaces(): String {        val spaces = repository.listSpaces()
        val array =
            JSONArray().also { result ->
                spaces.forEach { space ->
                    result.put(
                        JSONObject()
                            .put("id", space.id)
                            .put("name", space.name)
                            .put("count", repository.count(space.id)),
                    )
                }
            }
        return JSONObject()
            .put("ok", true)
            .put("default_space", defaultSpaceId)
            .put("spaces", array)
            .toString()
    }

    private fun resolveSpace(args: JSONObject): String =
        args.optString("space").trim().takeIf { it.isNotEmpty() } ?: defaultSpaceId

    private suspend fun resolveWriteSpace(args: JSONObject): String {
        val requested = args.optString("space").trim().takeIf { it.isNotEmpty() } ?: defaultSpaceId
        repository.ensureSpace(requested, requested)
        return requested
    }

    private fun JSONObject.requiredId(): Long {
        val id = optLong("id", -1L)
        require(id > 0) { "缺少有效的 id" }
        return id
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optString(index).trim().takeIf { it.isNotEmpty() } }
    }

    private fun JSONArray?.toStringListOrNull(): List<String>? {
        if (this == null) return null
        return toStringList()
    }
}
