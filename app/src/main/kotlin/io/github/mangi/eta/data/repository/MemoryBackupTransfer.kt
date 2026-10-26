package io.github.mangi.eta.data.repository

/**
 * 记忆库与备份文本之间的转换。
 *
 * 备份格式保持旧字段 `memoryMd` 不变：
 * - 前半段是旧版 `MEMORY.md` 原文，导入后照常写回旧文件；
 * - 后半段以 `# 记忆库` 开头，每条记忆写成 `## [空间] 标题`。
 *
 * 这样旧备份（没有记忆库段）仍能导入，新备份也能恢复空间归属。
 */
internal object MemoryBackupTransfer {
    const val LIBRARY_HEADING = "# 记忆库"
    private const val MAX_ENTRIES_PER_SPACE = 2_000

    /** 拆分备份文本，返回 legacy 与 library 两段。 */
    fun split(markdown: String): Pair<String, String> {
        val marker = "\n$LIBRARY_HEADING"
        val index = markdown.indexOf(marker)
        if (index < 0) {
            return if (markdown.startsWith("$LIBRARY_HEADING\n")) "" to markdown else markdown to ""
        }
        return markdown.substring(0, index).trimEnd() to markdown.substring(index + 1)
    }

    /** 导出全部空间为 Markdown；没有条目时返回空字符串。 */
    suspend fun exportLibrary(repository: MemoryRepository): String {
        val spaces = repository.listSpaces()
        val body = StringBuilder()
        spaces.forEach { space ->
            val entries = repository.listBySpace(space.id, limit = MAX_ENTRIES_PER_SPACE)
            entries.forEach { memory ->
                body.appendLine("## [${space.id}] ${memory.title}")
                body.appendLine(memory.content)
                body.appendLine()
            }
        }
        if (body.isEmpty()) return ""
        return buildString {
            appendLine(LIBRARY_HEADING)
            append(body)
        }
    }

    /** 从备份的 library 段恢复条目，返回新建数量。 */
    suspend fun importLibrary(repository: MemoryRepository, libraryMarkdown: String): Int {
        if (libraryMarkdown.isBlank()) return 0
        val drafts = LegacyMemoryParser.parse(libraryMarkdown).filter { !it.spaceId.isNullOrBlank() }
        if (drafts.isEmpty()) return 0
        var created = 0
        drafts.forEach { draft ->
            val spaceId = requireNotNull(draft.spaceId)
            repository.ensureSpace(spaceId, spaceId)
            if (repository.findByTitle(spaceId, draft.title) == null) {
                runCatching {
                    repository.createMemory(
                        spaceId = spaceId,
                        title = draft.title,
                        content = draft.content,
                        tags = draft.tags.filter { it != "旧版记忆" },
                        folderPath = null,
                        importance = draft.importance,
                        source = MemoryRepository.SOURCE_IMPORT,
                    )
                }.onSuccess { created++ }
            }
        }
        return created
    }
}
