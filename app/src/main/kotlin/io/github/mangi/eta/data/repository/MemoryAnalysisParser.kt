package io.github.mangi.eta.data.repository

import org.json.JSONArray
import org.json.JSONObject

internal data class MemoryDraft(
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val folderPath: String? = null,
    val importance: Float = 0.5f,
    /** 备份恢复时携带的空间；为空表示写入全局空间。 */
    val spaceId: String? = null,
)

internal data class MemoryUpdateDraft(
    val titleToUpdate: String,
    val newContent: String?,
    val newImportance: Float?,
)

internal data class MemoryLinkDraft(
    val sourceTitle: String,
    val targetTitle: String,
    val type: String,
    val description: String,
    val weight: Float,
)

internal data class MemoryMergeDraft(
    val sourceTitles: List<String>,
    val newTitle: String,
    val newContent: String,
    val tags: List<String> = emptyList(),
    val folderPath: String? = null,
    val importance: Float = 0.5f,
)

internal data class MemoryAnalysisPlan(
    val main: MemoryDraft? = null,
    val newMemories: List<MemoryDraft> = emptyList(),
    val updates: List<MemoryUpdateDraft> = emptyList(),
    val merges: List<MemoryMergeDraft> = emptyList(),
    val links: List<MemoryLinkDraft> = emptyList(),
) {
    val isEmpty: Boolean
        get() = main == null && newMemories.isEmpty() && updates.isEmpty() && merges.isEmpty() && links.isEmpty()
}

/**
 * 解析模型返回的记忆分析 JSON。
 *
 * 协议与 Operit 一致：`main` / `new` / `update` / `merge` / `links`。
 * 解析是尽力而为的：单个条目非法只跳过该条，不整体失败。
 */
internal object MemoryAnalysisParser {
    private const val MAX_ITEMS = 32
    private const val MAX_TITLE = 200
    private const val MAX_CONTENT = 20_000

    fun parse(raw: String): MemoryAnalysisPlan {
        val json = extractJsonObject(raw) ?: return MemoryAnalysisPlan()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return MemoryAnalysisPlan()
        if (root.length() == 0) return MemoryAnalysisPlan()

        val main = root.optJSONObject("main")?.let(::toDraft)
        val newMemories = root.optJSONArray("new").objects().mapNotNull(::toDraft).take(MAX_ITEMS)
        val updates = root.optJSONArray("update").objects().mapNotNull(::toUpdate).take(MAX_ITEMS)
        val merges = root.optJSONArray("merge").objects().mapNotNull(::toMerge).take(MAX_ITEMS)
        val links = root.optJSONArray("links").objects().mapNotNull(::toLink).take(MAX_ITEMS)

        return MemoryAnalysisPlan(
            main = main,
            newMemories = newMemories,
            updates = updates,
            merges = merges,
            links = links,
        )
    }

    private fun toDraft(json: JSONObject): MemoryDraft? {
        val title = json.optString("title").trim().take(MAX_TITLE)
        val content = json.optString("content").trim().take(MAX_CONTENT)
        if (title.isEmpty() || content.isEmpty()) return null
        return MemoryDraft(
            title = title,
            content = content,
            tags = json.optJSONArray("tags").stringList(),
            folderPath = json.optString("folder").takeIf { it.isNotBlank() }
                ?: json.optString("folder_path").takeIf { it.isNotBlank() },
            importance = json.optDouble("importance", 0.5).toFloat().coerceIn(0f, 1f),
        )
    }

    private fun toUpdate(json: JSONObject): MemoryUpdateDraft? {
        val title =
            json.optString("title_to_update").trim().ifEmpty { json.optString("title").trim() }
        if (title.isEmpty()) return null
        val content = json.optString("new_content").trim().takeIf { it.isNotEmpty() }
        val importance =
            json.optDouble("new_importance", Double.NaN).takeUnless { it.isNaN() }?.toFloat()?.coerceIn(0f, 1f)
        if (content == null && importance == null) return null
        return MemoryUpdateDraft(title, content, importance)
    }

    private fun toMerge(json: JSONObject): MemoryMergeDraft? {
        val sources = json.optJSONArray("source_titles").stringList()
        if (sources.isEmpty()) return null
        val title = json.optString("new_title").trim().ifEmpty { json.optString("title").trim() }
        val content = json.optString("new_content").trim().ifEmpty { json.optString("content").trim() }
        if (title.isEmpty() || content.isEmpty()) return null
        return MemoryMergeDraft(
            sourceTitles = sources,
            newTitle = title.take(MAX_TITLE),
            newContent = content.take(MAX_CONTENT),
            tags =
                json.optJSONArray("new_tags").stringList().ifEmpty {
                    json.optJSONArray("tags").stringList()
                },
            folderPath = json.optString("folder").takeIf { it.isNotBlank() }
                ?: json.optString("folder_path").takeIf { it.isNotBlank() },
            importance = json.optDouble("importance", 0.5).toFloat().coerceIn(0f, 1f),
        )
    }

    private fun toLink(json: JSONObject): MemoryLinkDraft? {        val source = json.optString("source_title").trim()
        val target = json.optString("target_title").trim()
        if (source.isEmpty() || target.isEmpty() || source == target) return null
        return MemoryLinkDraft(
            sourceTitle = source,
            targetTitle = target,
            type = json.optString("type").trim().ifEmpty { "related" },
            description = json.optString("description").trim(),
            weight = json.optDouble("weight", 1.0).toFloat().coerceIn(0f, 2f),
        )
    }

    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optJSONObject(index) }
    }

    private fun JSONArray?.stringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optString(index).trim().takeIf { it.isNotEmpty() }
        }
    }
}
