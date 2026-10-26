package io.github.mangi.eta.ui.screens.memory

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.screens.characters.CharacterPageMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 迁移前的 `MEMORY.md` 原文，只读展示。
 *
 * 数据仍在原文件里（迁移不会删除），新记忆请回到记忆库页面管理。
 */
@Composable
internal fun LegacyMemoryScreen(onBack: () -> Unit) {
    var content by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        content =
            withContext(Dispatchers.IO) {
                runCatching { AgentMemoryRepository.snapshot().content }.getOrDefault("")
            }
    }
    MiuixScaffoldPage(title = "旧版记忆（只读）", onBack = onBack) {
        item(key = "hint") {
            CharacterPageMessage(
                "这是迁移前 MEMORY.md 的原文，仅作查看。新记忆已经存进结构化记忆库，请返回上一页管理。",
            )
        }
        item(key = "content") {
            val text = content
            if (text == null) {
                CharacterPageMessage("正在读取…")
            } else if (text.isBlank()) {
                CharacterPageMessage("旧版记忆文件为空。")
            } else {
                Text(
                    text = text,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
