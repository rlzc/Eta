package io.github.mangi.eta.data.repository

import java.io.File

/**
 * 旧版单文件 `MEMORY.md` 的解析器。
 *
 * 按一级/二级标题切段，每段转成一条结构化记忆。没有标题的前言也会保留为一条。
 * 解析是纯函数，便于单测。
 */
internal object LegacyMemoryParser {
    private const val MAX_TITLE = 200
    private const val MAX_CONTENT = 20_000
    private const val FALLBACK_TITLE = "未分类记忆"

    fun parse(content: String): List<MemoryDraft> {
        if (content.isBlank()) return emptyList()
        val drafts = ArrayList<MemoryDraft>()
        val seenTitles = HashSet<String>()
        var currentHeading: LegacyHeading? = null
        val currentBody = StringBuilder()
        var preface = StringBuilder()

        fun flush() {
            val body = currentBody.toString().trim()
            val heading = currentHeading
            if (heading == null) {
                if (preface.isNotBlank()) {
                    addDraft(drafts, seenTitles, FALLBACK_TITLE, preface.toString().trim(), null)
                }
                preface = StringBuilder()
            } else if (body.isNotEmpty()) {
                addDraft(drafts, seenTitles, heading.title, body, heading.spaceId)
            }
            currentBody.clear()
        }

        content.lineSequence().forEach { line ->
            val heading = parseHeading(line)
            if (heading != null) {
                flush()
                currentHeading = heading
            } else if (currentHeading == null) {
                preface.appendLine(line)
            } else {
                currentBody.appendLine(line)
            }
        }
        flush()
        return drafts
    }

    private data class LegacyHeading(val title: String, val spaceId: String?)

    private fun parseHeading(line: String): LegacyHeading? {
        val trimmed = line.trimEnd()
        val tagged = TAGGED_HEADING.matchEntire(trimmed)
        if (tagged != null) {
            val spaceId = tagged.groupValues[1].trim()
            val title = tagged.groupValues[2].trim()
            if (title.isNotEmpty()) return LegacyHeading(title.take(MAX_TITLE), spaceId.takeIf { it.isNotEmpty() })
        }
        val match = HEADING.matchEntire(trimmed) ?: return null
        val title = match.groupValues[1].trim()
        if (title.isEmpty()) return null
        return LegacyHeading(title.take(MAX_TITLE), null)
    }

    private fun addDraft(
        drafts: MutableList<MemoryDraft>,
        seenTitles: MutableSet<String>,
        rawTitle: String,
        body: String,
        spaceId: String?,
    ) {
        if (body.isBlank()) return
        val key = (spaceId ?: "") + "\u0000" + rawTitle
        val title = uniqueTitle(key, rawTitle, seenTitles)
        drafts += MemoryDraft(
            title = title,
            content = body.take(MAX_CONTENT),
            tags = listOf("旧版记忆"),
            folderPath = "旧版记忆",
            importance = 0.5f,
            spaceId = spaceId,
        )
    }

    private fun uniqueTitle(key: String, raw: String, seen: MutableSet<String>): String {
        val base = raw.take(MAX_TITLE)
        if (seen.add(key)) return base
        var index = 2
        while (true) {
            val candidate = "$base ($index)"
            if (seen.add("$key\u0000$index")) return candidate
            index++
        }
    }

    private val HEADING = Regex("^#{1,2}\\s+(.+)$")
    private val TAGGED_HEADING = Regex("^#{1,2}\\s+\\[([^\\]]+)]\\s+(.+)$")
}

/**
 * 把旧 `MEMORY.md` 迁移到结构化记忆库。
 *
 * 幂等：迁移成功后写标记文件；旧文件保留不删，旧备份导出仍然可用。
 */
internal class LegacyMemoryMigration(
    private val memoryDir: File,
    private val repository: MemoryRepository,
) {
    companion object {
        const val FILE_NAME = "MEMORY.md"
        const val BACKUP_NAME = "MEMORY.md.bak"
        const val MARKER_NAME = ".migrated_to_library_v1"
        private const val MAX_LEGACY_BYTES = 1024 * 1024
    }

    suspend fun run(force: Boolean = false): Int {
        val marker = File(memoryDir, MARKER_NAME)
        if (!force && marker.exists()) return 0
        val legacy = File(memoryDir, FILE_NAME)
        if (!legacy.exists()) {
            memoryDir.mkdirs()
            marker.writeText("no-legacy-file")
            return 0
        }
        if (legacy.length() > MAX_LEGACY_BYTES) {
            // 超过安全上限时不迁移，避免把异常文件读进内存。
            return 0
        }
        val content = runCatching { legacy.readText() }.getOrElse { return 0 }
        val drafts = LegacyMemoryParser.parse(content)
        if (drafts.isEmpty()) {
            marker.writeText("empty-legacy-file")
            return 0
        }

        repository.ensureDefaultSpace()
        var created = 0
        drafts.forEach { draft ->
            val spaceId = draft.spaceId?.takeIf { it.isNotBlank() } ?: MemoryRepository.GLOBAL_SPACE_ID
            repository.ensureSpace(spaceId, spaceId)
            if (repository.findByTitle(spaceId, draft.title) == null) {
                runCatching {
                    repository.createMemory(
                        spaceId = spaceId,
                        title = draft.title,
                        content = draft.content,
                        tags = draft.tags,
                        folderPath = draft.folderPath,
                        importance = draft.importance,
                        source = MemoryRepository.SOURCE_IMPORT,
                    )
                }.onSuccess { created++ }
            }
        }

        val backup = File(memoryDir, BACKUP_NAME)
        if (!backup.exists()) {
            runCatching { legacy.copyTo(backup, overwrite = false) }
        }
        marker.writeText("migrated=${drafts.size} created=$created")
        return created
    }
}
