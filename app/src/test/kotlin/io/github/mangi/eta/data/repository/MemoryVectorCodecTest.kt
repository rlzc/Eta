package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryVectorCodecTest {
    @Test
    fun encodeDecodeRoundTrip() {
        val vector = floatArrayOf(0.1f, -0.2f, 0.3f, 0.0f)
        val decoded = MemoryVectorCodec.decode(MemoryVectorCodec.encode(vector))
        assertEquals(vector.size, decoded!!.size)
        vector.indices.forEach { assertEquals(vector[it], decoded[it], 0.0001f) }
    }

    @Test
    fun cosineOfIdenticalVectorsIsOne() {
        val vector = floatArrayOf(0.5f, 0.5f, 0.5f)
        assertEquals(1f, MemoryVectorCodec.cosine(vector, vector), 0.0001f)
    }

    @Test
    fun cosineOfOrthogonalVectorsIsZero() {
        assertEquals(
            0f,
            MemoryVectorCodec.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)),
            0.0001f,
        )
    }

    @Test
    fun cosineRejectsMismatchedDimensions() {
        assertEquals(0f, MemoryVectorCodec.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)), 0f)
    }

    @Test
    fun decodeRejectsInvalidBytes() {
        assertNull(MemoryVectorCodec.decode(null))
        assertNull(MemoryVectorCodec.decode(ByteArray(0)))
        assertNull(MemoryVectorCodec.decode(ByteArray(3)))
    }

    @Test
    fun semanticOnlyCandidateIsReturnedWhenVectorMatches() {
        val rows =
            listOf(
                io.github.mangi.eta.data.db.MemorySearchRow(
                    id = 1,
                    title = "完全无关的标题",
                    content = "完全无关的内容",
                    folderPath = null,
                    importance = 0.5f,
                    updatedAt = 1,
                ),
            )

        val outcome = MemorySearchEngine.search(
            query = "工作安排",
            rows = rows,
            tagsByMemory = emptyMap(),
            links = emptyList(),
            config = MemorySearchConfig(vectorWeight = 1f),
            queryEmbedding = floatArrayOf(1f, 0f),
            embeddings = mapOf(1L to floatArrayOf(1f, 0f)),
        )

        assertEquals(1, outcome.hits.size)
        assertTrue(outcome.hits.first().score > 0f)
    }
}
