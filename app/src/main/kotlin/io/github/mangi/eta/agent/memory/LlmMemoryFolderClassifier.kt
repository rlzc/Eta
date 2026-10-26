package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.repository.MemoryFolderCandidate
import io.github.mangi.eta.data.repository.MemoryFolderClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * 用当前对话模型给未分类记忆归类，复用同一套 baseUrl / apiKey，不额外配置。
 *
 * 返回 `{记忆 id: 目录}`；失败或无法解析时返回空表，调用方保持原样。
 */
internal class LlmMemoryFolderClassifier(
    private val configProvider: () -> AgentModelClient.ModelConfig = { AgentModelClient.loadConfig() },
) : MemoryFolderClassifier {
    override suspend fun classify(
        candidates: List<MemoryFolderCandidate>,
        existingFolders: List<String>,
    ): Map<Long, String> =
        withContext(Dispatchers.IO) {
            if (candidates.isEmpty()) return@withContext emptyMap()
            val config =
                runCatching { configProvider() }.getOrNull() ?: return@withContext emptyMap()
            val response =
                runCatching {
                    AgentModelClient.complete(
                        config =
                            config.copy(
                                terminalTools = false,
                                browserTools = false,
                                deviceDirectTools = false,
                                deviceSensitiveReadTools = false,
                                deviceSensitiveActionTools = false,
                                hostedWebSearchEnabled = false,
                                systemPrompt = SYSTEM_PROMPT,
                            ),
                        prompt = buildPrompt(candidates, existingFolders),
                        toolExecutor = NoOpToolExecutor,
                    )
                }.getOrNull() ?: return@withContext emptyMap()
            parse(response.content)
        }

    private fun buildPrompt(
        candidates: List<MemoryFolderCandidate>,
        existingFolders: List<String>,
    ): String =
        buildString {
            appendLine("给下面这些未分类的记忆分配目录。")
            appendLine("优先复用已有目录；确实不合适时才新建，目录用「一级/二级」形式，最多两级。")
            appendLine("只输出 JSON 数组，例如 [{\"id\": 1, \"folder\": \"工作/项目A\"}]，不要解释。")
            appendLine()
            appendLine("已有目录：")
            if (existingFolders.isEmpty()) {
                appendLine("（无）")
            } else {
                existingFolders.take(100).forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("未分类记忆：")
            candidates.forEach { candidate ->
                appendLine("#${candidate.id} 标题：${candidate.title}")
                appendLine("内容：${candidate.snippet.take(200)}")
            }
        }

    private fun parse(raw: String): Map<Long, String> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyMap()
        val array = runCatching { JSONArray(raw.substring(start, end + 1)) }.getOrNull() ?: return emptyMap()
        val result = linkedMapOf<Long, String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optLong("id", -1L)
            val folder = item.optString("folder").trim()
            if (id > 0 && folder.isNotEmpty()) result[id] = folder
        }
        return result
    }

    private object NoOpToolExecutor : AgentModelClient.ToolExecutor {
        override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult =
            AgentModelClient.ToolResult(
                content = "{\"ok\":false,\"message\":\"分类阶段不执行工具\"}",
            )
    }

    private companion object {
        const val SYSTEM_PROMPT =
            "你是记忆整理器，只负责给记忆分配目录。输出必须是 JSON 数组，不要输出 Markdown 或解释。"
    }
}
