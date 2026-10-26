package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.room.Room
import io.github.mangi.eta.data.db.EtaDatabase
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
class MemoryRepositoryTest {
    private lateinit var database: EtaDatabase
    private lateinit var repository: MemoryRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        database = Room.inMemoryDatabaseBuilder(context, EtaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MemoryRepository(database.memoryDao(), database.memorySpaceDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createAndReadMemory() = runBlocking {
        repository.ensureDefaultSpace()
        val id = repository.createMemory(
            spaceId = MemoryRepository.GLOBAL_SPACE_ID,
            title = "工作项目",
            content = "负责 PLC 网关的维护",
            tags = listOf("工作", "PLC"),
            folderPath = "工作/项目",
            importance = 0.9f,
        )

        val stored = repository.findById(id)
        assertNotNull(stored)
        assertEquals("工作项目", stored!!.title)
        assertEquals("工作/项目", stored.folderPath)
        assertEquals(0.9f, stored.importance, 0.001f)
        assertEquals(listOf("PLC", "工作"), repository.listTagNames(id))
    }

    @Test
    fun updateMemoryReplacesTagsAndKeepsSpace() = runBlocking {
        repository.ensureDefaultSpace()
        val id = repository.createMemory(
            spaceId = MemoryRepository.GLOBAL_SPACE_ID,
            title = "旧标题",
            content = "旧内容",
            tags = listOf("旧标签"),
        )

        val updated = repository.updateMemory(
            id = id,
            title = "新标题",
            content = "新内容",
            tags = listOf("新标签"),
            importance = 0.3f,
        )

        assertTrue(updated)
        val stored = repository.findById(id)!!
        assertEquals("新标题", stored.title)
        assertEquals("新内容", stored.content)
        assertEquals(MemoryRepository.GLOBAL_SPACE_ID, stored.spaceId)
        assertEquals(listOf("新标签"), repository.listTagNames(id))
    }

    @Test
    fun deleteMemoryRemovesTagsLinksAndChunks() = runBlocking {
        repository.ensureDefaultSpace()
        val first = repository.createMemory(
            spaceId = MemoryRepository.GLOBAL_SPACE_ID,
            title = "A",
            content = "a",
            tags = listOf("t"),
        )
        val second = repository.createMemory(
            spaceId = MemoryRepository.GLOBAL_SPACE_ID,
            title = "B",
            content = "b",
        )
        repository.link(
            spaceId = MemoryRepository.GLOBAL_SPACE_ID,
            sourceId = first,
            targetId = second,
            type = "related",
        )

        assertTrue(repository.deleteMemory(first))
        assertNull(repository.findById(first))
        assertTrue(repository.outgoingLinks(first).isEmpty())
        assertTrue(repository.backLinks(first).isEmpty())
        assertNotNull(repository.findById(second))
    }

    @Test
    fun characterSpaceIsIsolatedFromGlobal() = runBlocking {
        repository.ensureDefaultSpace()
        val characterSpace = MemoryRepository.characterSpaceId("shentinglan")
        repository.ensureCharacterSpace("shentinglan", "沈听澜")

        repository.createMemory(
            spaceId = MemoryRepository.GLOBAL_SPACE_ID,
            title = "现实工作",
            content = "周一交报告",
        )
        repository.createMemory(
            spaceId = characterSpace,
            title = "剧情",
            content = "白鸦第三章",
        )

        val globalTitles = repository.listTitles(MemoryRepository.GLOBAL_SPACE_ID).map { it.title }
        val characterTitles = repository.listTitles(characterSpace).map { it.title }

        assertEquals(listOf("现实工作"), globalTitles)
        assertEquals(listOf("剧情"), characterTitles)
        assertTrue(MemoryRepository.isCharacterSpace(characterSpace))
    }

    @Test
    fun moveMemoryChangesSpaceAndDeletesCharacterSpaceWithContent() = runBlocking {
        repository.ensureDefaultSpace()
        val characterSpace = MemoryRepository.characterSpaceId("youchu")
        repository.ensureCharacterSpace("youchu", "幼楚")
        val id = repository.createMemory(
            spaceId = characterSpace,
            title = "临时",
            content = "内容",
        )

        assertTrue(repository.moveMemory(id, MemoryRepository.GLOBAL_SPACE_ID))
        assertEquals(MemoryRepository.GLOBAL_SPACE_ID, repository.findById(id)!!.spaceId)

        repository.deleteSpace(characterSpace)
        assertNull(repository.findSpace(characterSpace))
        repository.deleteSpace(MemoryRepository.GLOBAL_SPACE_ID)
        assertNotNull(repository.findSpace(MemoryRepository.GLOBAL_SPACE_ID))
    }
}
