package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.repository.MemoryEmbeddingProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 用对话主模型的服务商配置（baseUrl + apiKey）做向量化，不额外要求用户配置。
 *
 * embedding 模型名不能直接复用对话模型名，处理顺序：
 * 1. 先查服务商的 `/models`，取第一个名字里带 `embed` 的模型；
 * 2. 查不到就用常见默认值 `text-embedding-3-small`；
 * 3. 调用失败（服务商不提供 embedding、模型名不对、网络不通）时返回 null，
 *    `MemoryRepository` 会自动退化为关键词/标签/链接检索。
 */
internal class OpenAiEmbeddingProvider(
    private val baseUrl: String,
    private val apiKey: String,
    preferredModel: String? = null,
) : MemoryEmbeddingProvider {
    private val client = AgentHttpClient.client
    private val resolvedModel = AtomicReference<String?>(preferredModel?.takeIf { it.isNotBlank() })
    private val cache = ConcurrentHashMap<String, FloatArray>()

    override suspend fun embed(text: String): FloatArray? =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext null
            cache[text]?.let { return@withContext it }
            val model = resolvedModel.get() ?: resolveModel() ?: return@withContext null
            val vector = runCatching { request(model, text) }.getOrNull() ?: return@withContext null
            if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
            cache[text] = vector
            vector
        }

    private fun resolveModel(): String? {
        val discovered =
            runCatching {
                listModelIds().firstOrNull { it.contains("embed", ignoreCase = true) }
            }.getOrNull()
        val model = discovered ?: DEFAULT_MODEL
        resolvedModel.set(model)
        return model
    }

    private fun listModelIds(): List<String> {
        val request =
            Request.Builder()
                .url(join("models"))
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()
            val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: return emptyList()
            return (0 until data.length()).mapNotNull { index ->
                data.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }
            }
        }
    }

    private fun request(model: String, text: String): FloatArray? {
        val payload =
            JSONObject()
                .put("model", model)
                .put("input", text)
                .toString()
        val request =
            Request.Builder()
                .url(join("embeddings"))
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody(JSON))
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val array =
                runCatching {
                    JSONObject(body)
                        .getJSONArray("data")
                        .getJSONObject(0)
                        .getJSONArray("embedding")
                }.getOrNull() ?: return null
            if (array.length() == 0) return null
            return FloatArray(array.length()) { index -> array.getDouble(index).toFloat() }
        }
    }

    private fun join(path: String): String = baseUrl.trimEnd('/') + "/" + path

    companion object {
        private const val DEFAULT_MODEL = "text-embedding-3-small"
        private const val MAX_CACHE_ENTRIES = 256
        private val JSON = "application/json".toMediaType()

        /** 从当前对话模型配置构造；没有可用凭据时返回 null，检索自动退化为无向量模式。 */
        fun fromConfig(config: AgentModelClient.ModelConfig?): OpenAiEmbeddingProvider? {
            if (config == null) return null
            if (config.baseUrl.isBlank() || config.apiKey.isBlank()) return null
            return OpenAiEmbeddingProvider(baseUrl = config.baseUrl, apiKey = config.apiKey)
        }
    }
}
