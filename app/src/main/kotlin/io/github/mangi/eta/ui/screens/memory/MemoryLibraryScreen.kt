package io.github.mangi.eta.ui.screens.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.repository.MemoryScoreMode
import io.github.mangi.eta.ui.app.MemoryEntryDraft
import io.github.mangi.eta.ui.app.MemoryLibraryUiState
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.screens.characters.CharacterPageMessage
import io.github.mangi.eta.ui.screens.characters.CharacterTextField
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal sealed interface MemoryLibraryAction {
    data object Back : MemoryLibraryAction
    data class SelectSpace(val id: String) : MemoryLibraryAction
    data class QueryChanged(val query: String) : MemoryLibraryAction
    data class SetScoreMode(val mode: MemoryScoreMode) : MemoryLibraryAction
    data object Search : MemoryLibraryAction
    data object BeginCreate : MemoryLibraryAction
    data class BeginEdit(val id: Long) : MemoryLibraryAction
    data class TitleChanged(val value: String) : MemoryLibraryAction
    data class ContentChanged(val value: String) : MemoryLibraryAction
    data class TagsChanged(val value: String) : MemoryLibraryAction
    data class FolderChanged(val value: String) : MemoryLibraryAction
    data class ImportanceChanged(val value: String) : MemoryLibraryAction
    data object Save : MemoryLibraryAction
    data object Delete : MemoryLibraryAction
    data object CancelEdit : MemoryLibraryAction
    data object DismissNotice : MemoryLibraryAction
    data object OpenLegacyMemory : MemoryLibraryAction
}

/**
 * 记忆库页面：空间切换、检索、条目查看与编辑。
 *
 * 不再展示整份 MEMORY.md，而是逐条结构化记忆。
 */
@Composable
internal fun MemoryLibraryScreen(
    state: MemoryLibraryUiState,
    onAction: (MemoryLibraryAction) -> Unit,
) {
    MiuixScaffoldPage(
        title = "记忆库",
        onBack = { onAction(MemoryLibraryAction.Back) },
    ) {
        state.notice?.let { notice ->
            item(key = "notice") {
                CharacterPageMessage(notice)
            }
        }

        val draft = state.draft
        if (draft != null) {
            item(key = "editor") {
                MemoryEditor(
                    draft = draft,
                    busy = state.busy,
                    onAction = onAction,
                )
            }
        } else {
            item(key = "spaces") {
                MemorySpaceSelector(
                    state = state,
                    onAction = onAction,
                )
            }
            item(key = "legacy") {
                EtaTextButton(
                    text = "查看旧版 MEMORY.md（只读）",
                    onClick = { onAction(MemoryLibraryAction.OpenLegacyMemory) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            item(key = "search") {                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    CharacterTextField(
                        label = "搜索记忆（支持空格或 | 分隔，* 通配）",
                        value = state.query,
                        onChange = { onAction(MemoryLibraryAction.QueryChanged(it)) },
                        enabled = !state.loading,
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EtaTextButton(
                            text = "搜索",
                            onClick = { onAction(MemoryLibraryAction.Search) },
                            enabled = !state.loading,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                        EtaTextButton(
                            text = "新建",
                            onClick = { onAction(MemoryLibraryAction.BeginCreate) },
                            enabled = !state.loading,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "排序方式",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ScoreModeButton(
                            label = "均衡",
                            selected = state.scoreMode == MemoryScoreMode.BALANCED,
                            onClick = { onAction(MemoryLibraryAction.SetScoreMode(MemoryScoreMode.BALANCED)) },
                            modifier = Modifier.weight(1f),
                        )
                        ScoreModeButton(
                            label = "关键词优先",
                            selected = state.scoreMode == MemoryScoreMode.KEYWORD_FIRST,
                            onClick = { onAction(MemoryLibraryAction.SetScoreMode(MemoryScoreMode.KEYWORD_FIRST)) },
                            modifier = Modifier.weight(1f),
                        )
                        ScoreModeButton(
                            label = "关联优先",
                            selected = state.scoreMode == MemoryScoreMode.SEMANTIC_FIRST,
                            onClick = { onAction(MemoryLibraryAction.SetScoreMode(MemoryScoreMode.SEMANTIC_FIRST)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (state.entries.isEmpty()) {
                item(key = "empty") {
                    CharacterPageMessage(
                        if (state.loading) "正在加载…" else "这个空间里还没有记忆。可以让助手用 create_memory 记录，或点上面的新建。",
                    )
                }
            }
            items(state.entries, key = { it.id }) { hit ->
                MemoryRow(
                    title = hit.title,
                    snippet = hit.content,
                    meta = buildString {
                        if (hit.tags.isNotEmpty()) append(hit.tags.joinToString(" / "))
                        hit.folderPath?.let { folder ->
                            if (isNotEmpty()) append("  ·  ")
                            append(folder)
                        }
                        if (isNotEmpty()) append("  ·  ")
                        append("重要度 ${"%.2f".format(hit.importance)}")
                    },
                    onClick = { onAction(MemoryLibraryAction.BeginEdit(hit.id)) },
                )
            }
        }
    }
}

@Composable
private fun MemorySpaceSelector(
    state: MemoryLibraryUiState,
    onAction: (MemoryLibraryAction) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "记忆空间",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.spaces.forEach { space ->
                val selected = space.id == state.selectedSpaceId
                EtaTextButton(
                    text = "${space.name}（${space.id}）",
                    onClick = { onAction(MemoryLibraryAction.SelectSpace(space.id)) },
                    enabled = !state.loading && !selected,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        if (selected) {
                            ButtonDefaults.textButtonColorsPrimary()
                        } else {
                            ButtonDefaults.textButtonColors()
                        },
                )
            }
        }
    }
}

@Composable
private fun MemoryRow(
    title: String,
    snippet: String,
    meta: String,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = snippet.take(160).replace('\n', ' '),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = meta,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun ScoreModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EtaTextButton(
        text = label,
        onClick = onClick,
        enabled = !selected,
        modifier = modifier,
        colors =
            if (selected) {
                ButtonDefaults.textButtonColorsPrimary()
            } else {
                ButtonDefaults.textButtonColors()
            },
    )
}

@Composable
private fun MemoryEditor(
    draft: MemoryEntryDraft,
    busy: Boolean,
    onAction: (MemoryLibraryAction) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        CharacterPageMessage(
            if (draft.isNew) "新建记忆" else "编辑记忆 #${draft.id}",
        )
        CharacterTextField(
            label = "标题",
            value = draft.title,
            onChange = { onAction(MemoryLibraryAction.TitleChanged(it)) },
            enabled = !busy,
            singleLine = true,
        )
        CharacterTextField(
            label = "内容",
            value = draft.content,
            onChange = { onAction(MemoryLibraryAction.ContentChanged(it)) },
            enabled = !busy,
            minLines = 8,
        )
        CharacterTextField(
            label = "标签（用逗号或空格分隔）",
            value = draft.tags,
            onChange = { onAction(MemoryLibraryAction.TagsChanged(it)) },
            enabled = !busy,
            singleLine = true,
        )
        CharacterTextField(
            label = "目录（如 工作/项目A）",
            value = draft.folderPath,
            onChange = { onAction(MemoryLibraryAction.FolderChanged(it)) },
            enabled = !busy,
            singleLine = true,
        )
        CharacterTextField(
            label = "重要度（0 到 1）",
            value = draft.importance.toString(),
            onChange = { onAction(MemoryLibraryAction.ImportanceChanged(it)) },
            enabled = !busy,
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EtaTextButton(
                text = "取消",
                onClick = { onAction(MemoryLibraryAction.CancelEdit) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
            if (!draft.isNew) {
                EtaTextButton(
                    text = "删除",
                    onClick = { onAction(MemoryLibraryAction.Delete) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
            }
            EtaTextButton(
                text = "保存",
                onClick = { onAction(MemoryLibraryAction.Save) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
