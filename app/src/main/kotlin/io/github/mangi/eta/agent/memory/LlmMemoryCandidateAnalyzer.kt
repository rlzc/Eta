package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.repository.MemoryAnalysisInput
import io.github.mangi.eta.data.repository.MemoryCandidateAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用当前配置的模型把一轮对话提取成记忆分析 JSON。
 *
 * 分析时关闭所有工具开关，避免模型在后台执行设备操作；
 * 只要求它输出 `main` / `new` / `update` / `links` 结构。
 */
internal class LlmMemoryCandidateAnalyzer(
    private val configProvider: () -> AgentModelClient.ModelConfig = { AgentModelClient.loadConfig() },
) : MemoryCandidateAnalyzer {
    override suspend fun analyze(input: MemoryAnalysisInput): String = withContext(Dispatchers.IO) {
        val base = configProvider()
        val config =
            base.copy(
                terminalTools = false,
                browserTools = false,
                deviceDirectTools = false,
                deviceSensitiveReadTools = false,
                deviceSensitiveActionTools = false,
                hostedWebSearchEnabled = false,
                systemPrompt = SYSTEM_PROMPT,
            )
        val response =
            AgentModelClient.complete(
                config = config,
                prompt = buildPrompt(input),
                toolExecutor = NoOpToolExecutor,
            )
        response.content
    }

    private fun buildPrompt(input: MemoryAnalysisInput): String = buildString {
        appendLine("从下面这轮对话里提取值得长期记住的信息。")
        appendLine("只输出一个 JSON 对象，不要输出 Markdown 代码块或解释。")
        appendLine("没有值得记住的内容时输出 {}。")
        appendLine()
        appendLine("已有的记忆标题（避免重复创建，修改请用 update）：")
        if (input.existingTitles.isEmpty()) {
            appendLine("（无）")
        } else {
            input.existingTitles.take(200).forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("用户：")
        appendLine(input.userText.take(4_000))
        appendLine()
        appendLine("助手：")
        appendLine(input.assistantText.take(4_000))
    }

    private object NoOpToolExecutor : AgentModelClient.ToolExecutor {
        override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult =
            AgentModelClient.ToolResult(
                content = "{\"ok\":false,\"message\":\"记忆分析阶段不执行工具\"}",
            )
    }

    private companion object {
        const val SYSTEM_PROMPT =
            "你是记忆整理器。任务是从对话中提取稳定、可复用的事实、偏好、关系、项目进展或工作安排，" +
                "并输出严格 JSON。不要保存密钥、验证码、凭据或一次性的闲聊。\n" +
                "JSON 结构：\n" +
                "{\n" +
                "  \"main\": {\"title\": \"\", \"content\": \"\", \"tags\": [], \"folder\": \"\", \"importance\": 0.5},\n" +
                "  \"new\": [{\"title\": \"\", \"content\": \"\", \"tags\": [], \"folder\": \"\", \"importance\": 0.5}],\n" +
                "  \"update\": [{\"title_to_update\": \"\", \"new_content\": \"\", \"new_importance\": 0.5}],\n" +
                "  \"links\": [{\"source_title\": \"\", \"target_title\": \"\", \"type\": \"related\", \"description\": \"\"}]\n" +
                "}\n" +
                "main 和 new 的 title 必须简洁且能唯一识别；importance 取 0 到 1。"
    }
}
