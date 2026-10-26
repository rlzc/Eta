package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.db.MemoryAutoSaveCandidateEntity
import io.github.mangi.eta.data.db.MemoryAutoSaveDao

internal data class MemoryAnalysisInput(
    val conversationId: String,
    val spaceId: String,
    val userText: String,
    val assistantText: String,
    val existingTitles: List<String>,
)

/** 把一轮对话变成记忆分析 JSON。实现可以是 LLM，也可以是规则或测试用的假实现。 */
internal fun interface MemoryCandidateAnalyzer {
    suspend fun analyze(input: MemoryAnalysisInput): String
}

/**
 * 自动保存：对话结束后把本轮内容排队，稍后由 [MemoryCandidateAnalyzer] 提取成结构化记忆。
 *
 * 与 Operit 一致，保存发生在回复定稿之后，不参与本轮 prompt 构建。
 */
internal class MemoryAutoSaveService(
    private val repository: MemoryRepository,
    private val dao: MemoryAutoSaveDao,
) {
    companion object {
        const val MIN_TEXT_CHARS = 40
        const val MAX_CANDIDATES_PER_RUN = 5
        private const val DEDUPE_WINDOW = 30
        private const val PRUNE_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }

    /** 入队；内容过短、与最近候选重复或队列已满时返回 null。 */
    suspend fun enqueue(
        conversationId: String,
        spaceId: String,
        userText: String,
        assistantText: String,
    ): Long? {
        val user = userText.trim()
        val assistant = assistantText.trim()
        if (conversationId.isBlank() || assistant.length < MIN_TEXT_CHARS) return null
        if (user.isEmpty() && assistant.isEmpty()) return null
        if (dao.countByStatus(MemoryAutoSaveCandidateEntity.STATUS_PENDING) >= MAX_CANDIDATES_PER_RUN * 4) {
            return null
        }
        val duplicate =
            dao.recent(DEDUPE_WINDOW).any {
                it.conversationId == conversationId &&
                    it.assistantText == assistant.take(4_000) &&
                    it.status != MemoryAutoSaveCandidateEntity.STATUS_DONE
            }
        if (duplicate) return null

        val now = System.currentTimeMillis()
        return dao.insert(
            MemoryAutoSaveCandidateEntity(
                conversationId = conversationId,
                spaceId = spaceId,
                userText = user.take(4_000),
                assistantText = assistant.take(4_000),
                status = MemoryAutoSaveCandidateEntity.STATUS_PENDING,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** 处理待办候选，返回成功应用的条数。 */
    suspend fun processPending(
        analyzer: MemoryCandidateAnalyzer,
        limit: Int = MAX_CANDIDATES_PER_RUN,
    ): Int {
        val candidates = dao.pending(limit)
        var applied = 0
        candidates.forEach { candidate ->
            val now = System.currentTimeMillis()
            dao.mark(
                id = candidate.id,
                status = MemoryAutoSaveCandidateEntity.STATUS_PROCESSING,
                error = "",
                now = now,
            )
            try {
                val spaceId = candidate.spaceId.ifBlank { MemoryRepository.GLOBAL_SPACE_ID }
                repository.ensureSpace(spaceId, spaceId)
                val existingTitles = repository.listTitles(spaceId).map { it.title }
                val raw =
                    analyzer.analyze(
                        MemoryAnalysisInput(
                            conversationId = candidate.conversationId,
                            spaceId = spaceId,
                            userText = candidate.userText,
                            assistantText = candidate.assistantText,
                            existingTitles = existingTitles,
                        ),
                    )
                val plan = MemoryAnalysisParser.parse(raw)
                if (applyPlan(spaceId, plan) > 0) applied++
                dao.mark(
                    id = candidate.id,
                    status = MemoryAutoSaveCandidateEntity.STATUS_DONE,
                    error = "",
                    now = System.currentTimeMillis(),
                )
            } catch (error: Exception) {
                val attempts = candidate.attemptCount + 1
                val status =
                    if (attempts >= MemoryAutoSaveCandidateEntity.MAX_ATTEMPTS) {
                        MemoryAutoSaveCandidateEntity.STATUS_FAILED
                    } else {
                        MemoryAutoSaveCandidateEntity.STATUS_PENDING
                    }
                dao.mark(
                    id = candidate.id,
                    status = status,
                    error = error.message.orEmpty().take(500),
                    now = System.currentTimeMillis(),
                    attemptDelta = 1,
                )
            }
        }
        return applied
    }

    /** 把解析结果写入记忆库，返回实际写入/更新的条目数。 */
    suspend fun applyPlan(spaceId: String, plan: MemoryAnalysisPlan): Int {
        if (plan.isEmpty) return 0
        var changed = 0

        val drafts = buildList {
            plan.main?.let(::add)
            addAll(plan.newMemories)
        }
        drafts.forEach { draft ->
            val existing = repository.findByTitle(spaceId, draft.title)
            if (existing == null) {
                runCatching {
                    repository.createMemory(
                        spaceId = spaceId,
                        title = draft.title,
                        content = draft.content,
                        tags = draft.tags,
                        folderPath = draft.folderPath,
                        importance = draft.importance,
                        source = MemoryRepository.SOURCE_AUTO,
                    )
                }.onSuccess { changed++ }
            } else {
                runCatching {
                    repository.updateMemory(
                        id = existing.id,
                        content = draft.content,
                        tags = draft.tags,
                        folderPath = draft.folderPath,
                        importance = draft.importance,
                    )
                }.onSuccess { if (it) changed++ }
            }
        }

        plan.updates.forEach { update ->
            val existing = repository.findByTitle(spaceId, update.titleToUpdate) ?: return@forEach
            runCatching {
                repository.updateMemory(
                    id = existing.id,
                    content = update.newContent,
                    importance = update.newImportance,
                )
            }.onSuccess { if (it) changed++ }
        }

        plan.merges.forEach { merge ->
            val existing = repository.findByTitle(spaceId, merge.newTitle)
            val targetId =
                if (existing == null) {
                    runCatching {
                        repository.createMemory(
                            spaceId = spaceId,
                            title = merge.newTitle,
                            content = merge.newContent,
                            tags = merge.tags,
                            folderPath = merge.folderPath,
                            importance = merge.importance,
                            source = MemoryRepository.SOURCE_AUTO,
                        )
                    }.getOrNull()
                } else {
                    runCatching {
                        repository.updateMemory(
                            id = existing.id,
                            content = merge.newContent,
                            tags = merge.tags,
                            folderPath = merge.folderPath,
                            importance = merge.importance,
                        )
                    }.getOrNull()?.let { existing.id }
                }
            if (targetId == null) return@forEach
            merge.sourceTitles
                .filter { it != merge.newTitle }
                .forEach { sourceTitle ->
                    repository.findByTitle(spaceId, sourceTitle)?.let { source ->
                        runCatching { repository.deleteMemory(source.id) }
                    }
                }
            changed++
        }

        plan.links.forEach { link ->
            val source = repository.findByTitle(spaceId, link.sourceTitle) ?: return@forEach
            val target = repository.findByTitle(spaceId, link.targetTitle) ?: return@forEach
            runCatching {
                repository.link(
                    spaceId = spaceId,
                    sourceId = source.id,
                    targetId = target.id,
                    type = link.type,
                    weight = link.weight,
                    description = link.description,
                )
            }.onSuccess { changed++ }
        }

        return changed
    }

    suspend fun pendingCount(): Int =
        dao.countByStatus(MemoryAutoSaveCandidateEntity.STATUS_PENDING)

    /**
     * 给未分类记忆分配目录；没有未分类条目时直接返回，不调用模型。
     */
    suspend fun autoCategorize(
        spaceId: String,
        classifier: MemoryFolderClassifier,
        limit: Int = 20,
    ): Int {
        val candidates =
            repository.listBySpace(spaceId, limit = 200)
                .filter { it.folderPath.isNullOrBlank() }
                .take(limit)
        if (candidates.isEmpty()) return 0
        val folders = repository.listFolderPaths(spaceId)
        val mapping =
            runCatching {
                classifier.classify(
                    candidates.map { MemoryFolderCandidate(it.id, it.title, it.content.take(200)) },
                    folders,
                )
            }.getOrDefault(emptyMap())
        if (mapping.isEmpty()) return 0
        var updated = 0
        mapping.forEach { (id, folder) ->
            if (runCatching { repository.updateMemory(id, folderPath = folder) }.getOrDefault(false)) {
                updated++
            }
        }
        return updated
    }

    suspend fun pruneDone(now: Long = System.currentTimeMillis()) {
        dao.pruneDone(now - PRUNE_AFTER_MS)
    }
}
