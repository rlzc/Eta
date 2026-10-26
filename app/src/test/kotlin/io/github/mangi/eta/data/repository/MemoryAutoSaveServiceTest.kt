package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.room.Room
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.MemoryAutoSaveCandidateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MemoryAutoSaveServiceTest {
    private lateinit var database: EtaDatabase
    private lateinit var repository: MemoryRepository
    private lateinit var service: MemoryAutoSaveService

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        database = Room.inMemoryDatabaseBuilder(context, EtaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MemoryRepository(database.memoryDao(), database.memorySpaceDao())
        service = MemoryAutoSaveService(repository, database.memoryAutoSaveDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun enqueueSkipsShortReply() = runBlocking {
        val id = service.enqueue("conv-1", "global", "你好", "收到")
        assertNull(id)
        assertEquals(0, service.pendingCount())
    }

    @Test
    fun duplicateReplyIsNotEnqueuedTwice() = runBlocking {
        val reply = "这是一段足够长的回复内容，用来验证重复入队会被忽略，而不是堆积候选。这里再补充一些说明文字，确保长度超过自动保存的最小阈值。"
        val first = service.enqueue("conv-1", "global", "问题", reply)
        val second = service.enqueue("conv-1", "global", "问题", reply)
        assertNotNull(first)
        assertNull(second)
        assertEquals(1, service.pendingCount())
    }

    @Test
    fun processPendingAppliesParsedPlan() = runBlocking {
        val reply = "好的，我把你的工作安排记下来了：周五之前要交周报，项目代号是 PLC 网关。另外下周一还要和供应商确认交付时间，记得提前准备会议材料。"
        service.enqueue("conv-1", "global", "帮我记住工作安排", reply)
        val analyzer = MemoryCandidateAnalyzer {
            """
            {
              "main": {"title": "工作安排", "content": "周五前交周报，项目为 PLC 网关", "tags": ["工作"], "importance": 0.9},
              "new": [{"title": "PLC 网关", "content": "当前负责的维护项目"}],
              "links": [{"source_title": "工作安排", "target_title": "PLC 网关", "type": "related"}]
            }
            """.trimIndent()
        }

        val applied = service.processPending(analyzer)

        assertEquals(1, applied)
        assertEquals(0, service.pendingCount())
        val main = repository.findByTitle("global", "工作安排")
        assertNotNull(main)
        assertEquals(0.9f, main!!.importance, 0.001f)
        val linked = repository.findByTitle("global", "PLC 网关")
        assertNotNull(linked)
        val links = repository.outgoingLinks(main.id)
        assertEquals(1, links.size)
        assertEquals(linked!!.id, links.first().targetId)
    }

    @Test
    fun failedAnalysisRetriesThenMarksFailed() = runBlocking {
        val reply = "这是一段足够长的回复内容，用来验证分析失败后的重试计数与最终失败状态。这里再补充一些说明文字，确保长度超过自动保存的最小阈值。"
        service.enqueue("conv-1", "global", "问题", reply)
        val failing = MemoryCandidateAnalyzer { error("模型不可用") }

        repeat(MemoryAutoSaveCandidateEntity.MAX_ATTEMPTS) {
            service.processPending(failing)
        }

        val candidate = database.memoryAutoSaveDao().recent(1).first()
        assertEquals(MemoryAutoSaveCandidateEntity.STATUS_FAILED, candidate.status)
        assertEquals(MemoryAutoSaveCandidateEntity.MAX_ATTEMPTS, candidate.attemptCount)
        assertTrue(candidate.lastError.contains("模型不可用"))
    }

    @Test
    fun invalidJsonIsTreatedAsEmptyPlanAndStillCompletes() = runBlocking {
        val reply = "这是一段足够长的回复内容，用来验证无法解析的返回不会卡住队列。这里再补充一些说明文字，确保长度超过自动保存的最小阈值。"
        service.enqueue("conv-1", "global", "问题", reply)
        val garbage = MemoryCandidateAnalyzer { "抱歉，我无法输出 JSON" }

        service.processPending(garbage)

        val candidate = database.memoryAutoSaveDao().recent(1).first()
        assertEquals(MemoryAutoSaveCandidateEntity.STATUS_DONE, candidate.status)
        assertEquals(0, repository.count("global"))
    }

    @Test
    fun mergeRemovesSourceEntriesAndKeepsTarget() = runBlocking {
        repository.ensureDefaultSpace()
        repository.createMemory(MemoryRepository.GLOBAL_SPACE_ID, "A", "旧 A")
        repository.createMemory(MemoryRepository.GLOBAL_SPACE_ID, "B", "旧 B")
        val reply = "这两条记忆说的是同一件事，应该合并成一条，避免以后重复检索到两份内容不同的记录。"
        service.enqueue("conv-merge", MemoryRepository.GLOBAL_SPACE_ID, "整理一下", reply)
        val analyzer = MemoryCandidateAnalyzer {
            """{"merge":[{"source_titles":["A","B"],"new_title":"AB","new_content":"合并后的内容"}]}"""
        }

        service.processPending(analyzer)

        assertEquals(null, repository.findByTitle(MemoryRepository.GLOBAL_SPACE_ID, "A"))
        assertEquals(null, repository.findByTitle(MemoryRepository.GLOBAL_SPACE_ID, "B"))
        val merged = repository.findByTitle(MemoryRepository.GLOBAL_SPACE_ID, "AB")
        assertEquals("合并后的内容", merged?.content)
    }

    @Test
    fun autoCategorizeAssignsFolderToUncategorizedMemory() = runBlocking {
        repository.ensureDefaultSpace()
        repository.createMemory(MemoryRepository.GLOBAL_SPACE_ID, "PLC 网关", "Modbus 转发异常")
        val classifier = MemoryFolderClassifier { candidates, _ ->
            candidates.associate { it.id to "工作/项目A" }
        }

        val updated = service.autoCategorize(MemoryRepository.GLOBAL_SPACE_ID, classifier)

        assertEquals(1, updated)
        val stored = repository.findByTitle(MemoryRepository.GLOBAL_SPACE_ID, "PLC 网关")
        assertEquals("工作/项目A", stored?.folderPath)
    }

    @Test
    fun characterSpaceKeepsAutoSavedMemoriesSeparate() = runBlocking {
        val space = MemoryRepository.characterSpaceId("shentinglan")
        val reply = "他沉默了一会儿，说手稿的第三章又卡在凶手的动机上。窗外的雨下得很密，他起身去关窗，回来时顺手把稿纸翻到了下一页。"
        service.enqueue("conv-1", space, "继续写", reply)
        val analyzer = MemoryCandidateAnalyzer {
            """{"main": {"title": "第三章", "content": "手稿卡在凶手动机"}}"""
        }

        service.processPending(analyzer)

        assertEquals(1, repository.count(space))
        assertEquals(0, repository.count(MemoryRepository.GLOBAL_SPACE_ID))
    }
}
