package io.github.mangi.eta.data.repository

internal data class MemoryFolderCandidate(
    val id: Long,
    val title: String,
    val snippet: String,
)

/**
 * 给未分类记忆分配目录。实现可以是 LLM，也可以是测试用的假实现。
 */
internal fun interface MemoryFolderClassifier {
    suspend fun classify(
        candidates: List<MemoryFolderCandidate>,
        existingFolders: List<String>,
    ): Map<Long, String>
}
