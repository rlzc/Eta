package io.github.mangi.eta.agent.memory

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.MemoryAutoSaveService
import io.github.mangi.eta.data.repository.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 自动保存轮询器：定期把排队中的对话候选交给模型提取成记忆。
 *
 * 只在存在待处理候选时调用模型，默认 10 分钟一轮，避免空转消耗额度。
 */
internal object MemoryAutoSaveScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private const val POLL_INTERVAL_MS = 10 * 60 * 1000L

    fun start(context: Context) {
        if (loopJob?.isActive == true) return
        val appContext = context.applicationContext
        loopJob =
            scope.launch {
                while (isActive) {
                    delay(POLL_INTERVAL_MS)
                    runCatching { runOnce(appContext) }
                }
            }
    }

    /** 立即处理一轮，返回应用的条目数。测试与手动触发也可用。 */
    suspend fun runOnce(context: Context): Int {
        if (!AgentMemoryRepository.isEnabled()) return 0
        val database = EtaDatabase.get(context)
        val repository = MemoryRepository(database.memoryDao(), database.memorySpaceDao())
        val service = MemoryAutoSaveService(repository, database.memoryAutoSaveDao())
        var applied = 0
        if (service.pendingCount() > 0) {
            applied = service.processPending(LlmMemoryCandidateAnalyzer())
            runCatching { service.pruneDone() }
        }
        runCatching {
            repository.listSpaces().forEach { space ->
                service.autoCategorize(space.id, LlmMemoryFolderClassifier())
            }
        }
        return applied
    }
}
