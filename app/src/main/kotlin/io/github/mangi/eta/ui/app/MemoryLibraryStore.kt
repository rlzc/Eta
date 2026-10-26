package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.data.db.MemorySpaceEntity
import io.github.mangi.eta.data.repository.MemoryRepository
import io.github.mangi.eta.data.repository.MemoryScoreMode
import io.github.mangi.eta.data.repository.MemorySearchConfig
import io.github.mangi.eta.data.repository.MemorySearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class MemoryEntryDraft(
    val id: Long? = null,
    val title: String = "",
    val content: String = "",
    val tags: String = "",
    val folderPath: String = "",
    val importance: Float = 0.5f,
) {
    val isNew: Boolean get() = id == null
}

internal data class MemoryLibraryUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val spaces: List<MemorySpaceEntity> = emptyList(),
    val selectedSpaceId: String = MemoryRepository.GLOBAL_SPACE_ID,
    val entries: List<MemorySearchHit> = emptyList(),
    val query: String = "",
    val scoreMode: MemoryScoreMode = MemoryScoreMode.BALANCED,
    val draft: MemoryEntryDraft? = null,
    val notice: String? = null,
)

internal class MemoryLibraryStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val repository: MemoryRepository by lazy {
        MemoryRepository(appContext).also { }
    }

    var state by mutableStateOf(MemoryLibraryUiState())
        private set

    fun refresh(spaceId: String? = null) {
        if (spaceId != null) state = state.copy(selectedSpaceId = spaceId)
        state = state.copy(loading = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                repository.ensureDefaultSpace()
                val spaces = repository.listSpaces()
                val spaceId =
                    state.selectedSpaceId.takeIf { id -> spaces.any { it.id == id } }
                        ?: MemoryRepository.GLOBAL_SPACE_ID
                val entries = loadEntries(spaceId, state.query)
                spaces to (spaceId to entries)
            }.fold(
                onSuccess = { (spaces, spaceAndEntries) ->
                    withContext(Dispatchers.Main) {
                        state = state.copy(
                            loading = false,
                            spaces = spaces,
                            selectedSpaceId = spaceAndEntries.first,
                            entries = spaceAndEntries.second,
                        )
                    }
                },
                onFailure = { error ->
                    withContext(Dispatchers.Main) {
                        state = state.copy(loading = false, notice = "记忆读取失败：${error.message.orEmpty()}")
                    }
                },
            )
        }
    }

    fun selectSpace(id: String) {
        if (id == state.selectedSpaceId) return
        state = state.copy(selectedSpaceId = id, draft = null)
        reloadEntries()
    }

    fun setQuery(query: String) {
        state = state.copy(query = query)
    }

    fun setScoreMode(mode: MemoryScoreMode) {
        if (mode == state.scoreMode) return
        state = state.copy(scoreMode = mode)
        reloadEntries()
    }

    fun search() = reloadEntries()

    fun beginCreate() {
        state = state.copy(draft = MemoryEntryDraft())
    }

    fun beginEdit(id: Long) {
        val hit = state.entries.firstOrNull { it.id == id } ?: return
        state = state.copy(
            draft = MemoryEntryDraft(
                id = hit.id,
                title = hit.title,
                content = hit.content,
                tags = hit.tags.joinToString("，"),
                folderPath = hit.folderPath.orEmpty(),
                importance = hit.importance,
            ),
        )
    }

    fun updateDraft(transform: (MemoryEntryDraft) -> MemoryEntryDraft) {
        val draft = state.draft ?: return
        state = state.copy(draft = transform(draft))
    }

    fun cancelEdit() {
        state = state.copy(draft = null)
    }

    fun save() {
        val draft = state.draft ?: return
        if (draft.title.isBlank() || draft.content.isBlank()) {
            state = state.copy(notice = "标题和内容不能为空")
            return
        }
        val spaceId = state.selectedSpaceId
        val tags = splitTags(draft.tags)
        state = state.copy(busy = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                repository.ensureSpace(spaceId, spaceId)
                if (draft.isNew) {
                    repository.createMemory(
                        spaceId = spaceId,
                        title = draft.title,
                        content = draft.content,
                        tags = tags,
                        folderPath = draft.folderPath.takeIf { it.isNotBlank() },
                        importance = draft.importance,
                        source = MemoryRepository.SOURCE_USER,
                    )
                } else {
                    repository.updateMemory(
                        id = requireNotNull(draft.id),
                        title = draft.title,
                        content = draft.content,
                        tags = tags,
                        folderPath = draft.folderPath.takeIf { it.isNotBlank() },
                        importance = draft.importance,
                    )
                }
                loadEntries(spaceId, state.query)
            }.fold(
                onSuccess = { entries ->
                    withContext(Dispatchers.Main) {
                        state = state.copy(
                            busy = false,
                            draft = null,
                            entries = entries,
                            notice = if (draft.isNew) "已创建记忆" else "已保存记忆",
                        )
                    }
                },
                onFailure = { error ->
                    withContext(Dispatchers.Main) {
                        state = state.copy(busy = false, notice = "保存失败：${error.message.orEmpty()}")
                    }
                },
            )
        }
    }

    fun delete() {
        val draft = state.draft ?: return
        val id = draft.id ?: return
        val spaceId = state.selectedSpaceId
        state = state.copy(busy = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                repository.deleteMemory(id)
                loadEntries(spaceId, state.query)
            }.fold(
                onSuccess = { entries ->
                    withContext(Dispatchers.Main) {
                        state = state.copy(busy = false, draft = null, entries = entries, notice = "已删除记忆")
                    }
                },
                onFailure = { error ->
                    withContext(Dispatchers.Main) {
                        state = state.copy(busy = false, notice = "删除失败：${error.message.orEmpty()}")
                    }
                },
            )
        }
    }

    fun dismissNotice() {
        state = state.copy(notice = null)
    }

    private fun reloadEntries() {
        val spaceId = state.selectedSpaceId
        val query = state.query
        state = state.copy(loading = true)
        scope.launch(Dispatchers.IO) {
            runCatching { loadEntries(spaceId, query) }.fold(
                onSuccess = { entries ->
                    withContext(Dispatchers.Main) {
                        state = state.copy(loading = false, entries = entries)
                    }
                },
                onFailure = { error ->
                    withContext(Dispatchers.Main) {
                        state = state.copy(loading = false, notice = "检索失败：${error.message.orEmpty()}")
                    }
                },
            )
        }
    }

    private suspend fun loadEntries(spaceId: String, query: String): List<MemorySearchHit> =
        repository.search(
            spaceId = spaceId,
            query = query.ifBlank { "*" },
            config = MemorySearchConfig(mode = state.scoreMode, limit = 100),
        ).hits

    private fun splitTags(raw: String): List<String> =
        raw.split(',', '，', '、', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
