package io.github.mangi.eta.agent.tool

import android.content.Context
import androidx.room.Room
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.MemoryRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MemoryLibraryToolsTest {
    private lateinit var database: EtaDatabase
    private lateinit var repository: MemoryRepository
    private lateinit var tools: MemoryLibraryTools

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        database = Room.inMemoryDatabaseBuilder(context, EtaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MemoryRepository(database.memoryDao(), database.memorySpaceDao())
        tools = MemoryLibraryTools(repository, MemoryRepository.GLOBAL_SPACE_ID)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun run(name: String, args: JSONObject): JSONObject =
        JSONObject(requireNotNull(tools.execute(name, args)) { "工具 $name 没有返回结果" })

    @Test
    fun createQueryAndReadByTitle() = runBlocking {
        val created = run(
            "create_memory",
            JSONObject()
                .put("title", "工作项目")
                .put("content", "负责 PLC 网关维护")
                .put("tags", JSONArray().put("工作")),
        )
        assertTrue(created.getBoolean("ok"))
        val id = created.getLong("id")

        val searched = run("query_memory", JSONObject().put("query", "PLC"))
        assertEquals(1, searched.getInt("count"))
        assertEquals(id, searched.getJSONArray("hits").getJSONObject(0).getLong("id"))

        val byTitle = run("get_memory_by_title", JSONObject().put("title", "工作项目"))
        assertTrue(byTitle.getBoolean("ok"))
        assertTrue(byTitle.getString("content").contains("PLC"))
    }

    @Test
    fun characterSessionWritesItsOwnSpaceByDefaultAndGlobalOnRequest() = runBlocking {
        val characterTools =
            MemoryLibraryTools(repository, MemoryRepository.characterSpaceId("shentinglan"))

        val drama = JSONObject(
            requireNotNull(
                characterTools.execute(
                    "create_memory",
                    JSONObject().put("title", "剧情").put("content", "白鸦第三章卡住了"),
                ),
            ),
        )
        val work = JSONObject(
            requireNotNull(
                characterTools.execute(
                    "create_memory",
                    JSONObject()
                        .put("title", "工作")
                        .put("content", "周五交周报")
                        .put("space", "global"),
                ),
            ),
        )

        assertEquals("character:shentinglan", drama.getString("space"))
        assertEquals("global", work.getString("space"))
        assertEquals(1, repository.count("global"))
        assertEquals(1, repository.count("character:shentinglan"))
    }

    @Test
    fun snapshotIdExcludesAlreadyReturnedMemories() = runBlocking {
        run("create_memory", JSONObject().put("title", "A").put("content", "共同关键词"))
        run("create_memory", JSONObject().put("title", "B").put("content", "共同关键词"))

        val first = run(
            "query_memory",
            JSONObject().put("query", "共同").put("snapshot_id", "task-1").put("limit", 1),
        )
        val second = run(
            "query_memory",
            JSONObject().put("query", "共同").put("snapshot_id", "task-1").put("limit", 1),
        )

        assertEquals(1, first.getInt("count"))
        assertEquals(1, second.getInt("count"))
        assertTrue(
            first.getJSONArray("hits").getJSONObject(0).getLong("id") !=
                second.getJSONArray("hits").getJSONObject(0).getLong("id"),
        )
    }

    @Test
    fun updateDeleteAndMove() = runBlocking {
        val created = run("create_memory", JSONObject().put("title", "临时").put("content", "内容"))
        val id = created.getLong("id")

        val updated = run("update_memory", JSONObject().put("id", id).put("title", "改名").put("importance", 0.8))
        assertTrue(updated.getBoolean("ok"))

        val moved = run("move_memory", JSONObject().put("id", id).put("space", "character:youchu"))
        assertTrue(moved.getBoolean("ok"))
        assertEquals("character:youchu", repository.findById(id)!!.spaceId)

        val deleted = run("delete_memory", JSONObject().put("id", id))
        assertTrue(deleted.getBoolean("ok"))
        assertEquals(null, repository.findById(id))
    }

    @Test
    fun linkLifecycleCanQueryUpdateAndDelete() = runBlocking {
        val a = run("create_memory", JSONObject().put("title", "A").put("content", "a")).getLong("id")
        val b = run("create_memory", JSONObject().put("title", "B").put("content", "b")).getLong("id")
        val linkId =
            run(
                "link_memories",
                JSONObject().put("source_id", a).put("target_id", b).put("type", "related"),
            ).getLong("link_id")

        val queried = run("query_memory_links", JSONObject().put("id", a))
        assertEquals(1, queried.getJSONArray("outgoing").length())
        assertEquals(1, run("query_memory_links", JSONObject().put("id", b)).getJSONArray("incoming").length())

        val updated = run("update_memory_link", JSONObject().put("link_id", linkId).put("type", "part_of"))
        assertTrue(updated.getBoolean("ok"))

        val deleted = run("delete_memory_link", JSONObject().put("link_id", linkId))
        assertTrue(deleted.getBoolean("ok"))
        assertEquals(0, run("query_memory_links", JSONObject().put("id", a)).getJSONArray("outgoing").length())
    }

    @Test
    fun documentImportCreatesChunks() = runBlocking {
        val content = "段落内容".repeat(500)
        val created =
            run(
                "create_memory_document",
                JSONObject().put("title", "长文档").put("content", content).put("chunk_size", 500),
            )
        assertTrue(created.getBoolean("ok"))
        assertTrue(created.getInt("chunks") > 1)
    }

    @Test
    fun linkAcrossSpacesIsRejected() = runBlocking {
        val a = run("create_memory", JSONObject().put("title", "A").put("content", "a")).getLong("id")
        val b = run(
            "create_memory",
            JSONObject().put("title", "B").put("content", "b").put("space", "character:youchu"),
        ).getLong("id")

        try {
            tools.execute(
                "link_memories",
                JSONObject().put("source_id", a).put("target_id", b).put("type", "related"),
            )
            fail("跨空间建链应当被拒绝")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("跨空间"))
        }
    }
}
