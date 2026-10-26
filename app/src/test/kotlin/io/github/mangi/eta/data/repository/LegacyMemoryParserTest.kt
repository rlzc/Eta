package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMemoryParserTest {
    @Test
    fun splitsByTopLevelAndSecondLevelHeadings() {
        val content =
            """
            # 核心记忆
            用户叫小熙，在做 PLC 项目。

            ## 工作
            每周五交周报，项目代号网关。

            ## 生活
            喜欢喝美式。
            """.trimIndent()

        val drafts = LegacyMemoryParser.parse(content)

        assertEquals(3, drafts.size)
        assertEquals(listOf("核心记忆", "工作", "生活"), drafts.map { it.title })
        assertTrue(drafts[1].content.contains("周报"))
        assertEquals("旧版记忆", drafts[1].folderPath)
    }

    @Test
    fun keepsPrefaceWithoutHeading() {
        val content =
            """
            一些没有标题的旧内容。

            # 有标题
            正文。
            """.trimIndent()

        val drafts = LegacyMemoryParser.parse(content)

        assertEquals(2, drafts.size)
        assertEquals("未分类记忆", drafts[0].title)
        assertTrue(drafts[0].content.contains("没有标题"))
    }

    @Test
    fun duplicateTitlesGetSuffix() {
        val content =
            """
            # 项目
            第一段。

            # 项目
            第二段。
            """.trimIndent()

        val drafts = LegacyMemoryParser.parse(content)

        assertEquals(listOf("项目", "项目 (2)"), drafts.map { it.title })
    }

    @Test
    fun emptySectionsAreSkipped() {
        val content =
            """
            # 空段

            # 有内容
            正文。
            """.trimIndent()

        val drafts = LegacyMemoryParser.parse(content)

        assertEquals(1, drafts.size)
        assertEquals("有内容", drafts[0].title)
    }

    @Test
    fun blankInputProducesNothing() {
        assertTrue(LegacyMemoryParser.parse("").isEmpty())
        assertTrue(LegacyMemoryParser.parse("   \n\n ").isEmpty())
    }
}
