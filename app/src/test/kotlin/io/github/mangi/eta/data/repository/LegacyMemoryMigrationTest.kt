package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.room.Room
import io.github.mangi.eta.data.db.EtaDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LegacyMemoryMigrationTest {
    private lateinit var database: EtaDatabase
    private lateinit var repository: MemoryRepository
    private lateinit var memoryDir: File

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        database = Room.inMemoryDatabaseBuilder(context, EtaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MemoryRepository(database.memoryDao(), database.memorySpaceDao())
        memoryDir = File(context.cacheDir, "legacy-${UUID.randomUUID()}").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        database.close()
        memoryDir.deleteRecursively()
    }

    private fun legacyFile(content: String): File =
        File(memoryDir, LegacyMemoryMigration.FILE_NAME).also { it.writeText(content) }

    @Test
    fun migratesSectionsIntoGlobalSpaceAndKeepsLegacyFile() = runBlocking {
        legacyFile(
            """
            # 核心记忆
            用户叫小熙。

            ## 工作
            周五交周报。
            """.trimIndent(),
        )

        val created = LegacyMemoryMigration(memoryDir, repository).run()

        assertEquals(2, created)
        assertEquals(2, repository.count(MemoryRepository.GLOBAL_SPACE_ID))
        val work = repository.findByTitle(MemoryRepository.GLOBAL_SPACE_ID, "工作")
        assertNotNull(work)
        assertTrue(work!!.content.contains("周报"))
        assertTrue(File(memoryDir, LegacyMemoryMigration.FILE_NAME).exists())
        assertTrue(File(memoryDir, LegacyMemoryMigration.BACKUP_NAME).exists())
        assertTrue(File(memoryDir, LegacyMemoryMigration.MARKER_NAME).exists())
    }

    @Test
    fun secondRunIsIdempotent() = runBlocking {
        legacyFile("# 工作\n周五交周报。")
        val migration = LegacyMemoryMigration(memoryDir, repository)

        assertEquals(1, migration.run())
        assertEquals(0, migration.run())
        assertEquals(1, repository.count(MemoryRepository.GLOBAL_SPACE_ID))
    }

    @Test
    fun missingLegacyFileWritesMarkerAndCreatesNothing() = runBlocking {
        val created = LegacyMemoryMigration(memoryDir, repository).run()

        assertEquals(0, created)
        assertEquals(0, repository.count(MemoryRepository.GLOBAL_SPACE_ID))
        assertTrue(File(memoryDir, LegacyMemoryMigration.MARKER_NAME).exists())
    }

    @Test
    fun existingLibraryEntryWithSameTitleIsNotDuplicated() = runBlocking {
        repository.ensureDefaultSpace()
        repository.createMemory(
            spaceId = MemoryRepository.GLOBAL_SPACE_ID,
            title = "工作",
            content = "已经存在的条目",
        )
        legacyFile("# 工作\n旧文件里的内容。")

        LegacyMemoryMigration(memoryDir, repository).run()

        assertEquals(1, repository.count(MemoryRepository.GLOBAL_SPACE_ID))
        assertTrue(repository.findByTitle(MemoryRepository.GLOBAL_SPACE_ID, "工作")!!.content.contains("已经存在"))
    }
}
