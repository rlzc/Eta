package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.db.MemoryLinkEntity
import io.github.mangi.eta.data.db.MemorySearchRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySearchEngineTest {
    private fun row(
        id: Long,
        title: String,
        content: String,
        importance: Float = 0.5f,
        folderPath: String? = null,
        updatedAt: Long = id,
    ) = MemorySearchRow(
        id = id,
        title = title,
        content = content,
        folderPath = folderPath,
        importance = importance,
        updatedAt = updatedAt,
    )

    @Test
    fun keywordMatchRanksTitleHitAboveContentHit() {
        val rows = listOf(
            row(1, "PLC 工作项目", "内容与关键词无关"),
            row(2, "杂项", "今天处理了 PLC 网关的维护"),
        )

        val outcome = MemorySearchEngine.search(
            query = "PLC",
            rows = rows,
            tagsByMemory = emptyMap(),
            links = emptyList(),
            config = MemorySearchConfig(),
        )

        assertEquals(2, outcome.hits.size)
        assertEquals(1, outcome.hits.first().id)
    }

    @Test
    fun wildcardMatchesPartialToken() {
        val rows = listOf(
            row(1, "网络错误排查", "重试三次后恢复"),
            row(2, "会议记录", "下周三评审"),
        )

        val outcome = MemorySearchEngine.search(
            query = "网络*排查",
            rows = rows,
            tagsByMemory = emptyMap(),
            links = emptyList(),
            config = MemorySearchConfig(),
        )

        assertEquals(listOf(1L), outcome.hits.map { it.id })
    }

    @Test
    fun tagMatchReturnsMemoryWithoutKeywordInText() {
        val rows = listOf(row(1, "随手记", "没有相关字面"))
        val outcome = MemorySearchEngine.search(
            query = "工作",
            rows = rows,
            tagsByMemory = mapOf(1L to listOf("工作")),
            links = emptyList(),
            config = MemorySearchConfig(),
        )

        assertEquals(1, outcome.hits.size)
        assertTrue(outcome.hits.first().tags.contains("工作"))
        assertTrue(outcome.hits.first().reason.contains("标签"))
    }

    @Test
    fun linkExpansionPullsInRelatedMemory() {
        val rows = listOf(
            row(1, "PLC 网关", "Modbus 转发异常"),
            row(2, "排查记录", "更换交换机后恢复"),
        )
        val links = listOf(
            MemoryLinkEntity(
                id = 1,
                spaceId = "global",
                sourceId = 1,
                targetId = 2,
                type = "related",
                weight = 1f,
                description = "",
            ),
        )

        val outcome = MemorySearchEngine.search(
            query = "PLC",
            rows = rows,
            tagsByMemory = emptyMap(),
            links = links,
            config = MemorySearchConfig(edgeWeight = 0.5f),
        )

        assertEquals(2, outcome.hits.size)
        assertTrue(outcome.hits.any { it.id == 2L && it.reason == "关联扩展" })
    }

    @Test
    fun thresholdAndLimitAreApplied() {
        val rows = (1L..10L).map { row(it, "记忆 $it", "共同关键词") }
        val outcome = MemorySearchEngine.search(
            query = "共同",
            rows = rows,
            tagsByMemory = emptyMap(),
            links = emptyList(),
            config = MemorySearchConfig(limit = 3, threshold = 0.01f),
        )

        assertEquals(3, outcome.hits.size)
    }

    @Test
    fun emptyQueryListsAllByImportance() {
        val rows = listOf(
            row(1, "低", "a", importance = 0.2f),
            row(2, "高", "b", importance = 0.9f),
        )

        val outcome = MemorySearchEngine.search(
            query = "*",
            rows = rows,
            tagsByMemory = emptyMap(),
            links = emptyList(),
            config = MemorySearchConfig(),
        )

        assertEquals(listOf(2L, 1L), outcome.hits.map { it.id })
    }

    @Test
    fun folderFilterScopesResults() {
        val rows = listOf(
            row(1, "工作 A", "内容", folderPath = "工作"),
            row(2, "生活 B", "内容", folderPath = "生活"),
        )

        val outcome = MemorySearchEngine.search(
            query = "内容",
            rows = rows,
            tagsByMemory = emptyMap(),
            links = emptyList(),
            config = MemorySearchConfig(),
            folderPath = "生活",
        )

        assertEquals(listOf(2L), outcome.hits.map { it.id })
    }

    @Test
    fun excludedIdsAreSkippedAndCounted() {
        val rows = listOf(row(1, "A", "内容"), row(2, "B", "内容"))
        val outcome = MemorySearchEngine.search(
            query = "内容",
            rows = rows,
            tagsByMemory = emptyMap(),
            links = emptyList(),
            config = MemorySearchConfig(),
            excludedIds = setOf(1L),
        )

        assertEquals(listOf(2L), outcome.hits.map { it.id })
        assertEquals(1, outcome.excludedBySnapshot)
    }
}
