package io.github.mangi.eta.data.repository

import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * 可选的向量化服务。
 *
 * 没有配置时记忆检索只走关键词/标签/链接；配置后 [MemoryRepository] 会在写入时生成向量，
 * 检索时计算余弦相似度并计入总分。实现方负责调用具体的 embedding HTTP 接口。
 */
internal fun interface MemoryEmbeddingProvider {
    suspend fun embed(text: String): FloatArray?
}

internal object MemoryVectorCodec {
    fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4)
        vector.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    fun decode(bytes: ByteArray?): FloatArray? {
        if (bytes == null || bytes.isEmpty() || bytes.size % 4 != 0) return null
        val buffer = ByteBuffer.wrap(bytes)
        return FloatArray(bytes.size / 4) { buffer.float }
    }

    fun cosine(left: FloatArray, right: FloatArray): Float {
        if (left.isEmpty() || left.size != right.size) return 0f
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            val l = left[index].toDouble()
            val r = right[index].toDouble()
            dot += l * r
            leftNorm += l * l
            rightNorm += r * r
        }
        if (leftNorm <= 0.0 || rightNorm <= 0.0) return 0f
        return (dot / (sqrt(leftNorm) * sqrt(rightNorm))).toFloat().coerceIn(-1f, 1f)
    }
}
