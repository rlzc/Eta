package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.db.MemorySearchRow
import io.github.mangi.eta.data.db.MemoryLinkEntity
import kotlin.math.sqrt

internal enum class MemoryScoreMode {
    BALANCED,
    KEYWORD_FIRST,
    SEMANTIC_FIRST,
}

internal data class MemorySearchConfig(
    val mode: MemoryScoreMode = MemoryScoreMode.BALANCED,
    val keywordWeight: Float = 1.0f,
    val tagWeight: Float = 0.8f,
    val edgeWeight: Float = 0.4f,
    /** 预留：向量权重。第一版不启用，保留字段便于后续接入 embedding。 */
    val vectorWeight: Float = 0.0f,
    val threshold: Float = 0f,
    val limit: Int = 20,
)

internal data class MemorySearchHit(
    val id: Long,
    val title: String,
    val content: String,
    val folderPath: String?,
    val importance: Float,
    val score: Float,
    val matchedTokens: List<String>,
    val tags: List<String>,
    val reason: String,
)

internal data class MemorySearchOutcome(
    val hits: List<MemorySearchHit>,
    val totalCandidates: Int,
    val excludedBySnapshot: Int,
)

/**
 * 三路检索：关键词、标签、链接扩展。
 *
 * 完全纯函数，便于单测；数据由调用方从 DAO 取好再传进来。
 * 向量一路只预留权重，不参与打分。
 */
internal object MemorySearchEngine {
    private const val TITLE_WEIGHT = 0.6f
    private const val CONTENT_WEIGHT = 0.4f
    private const val COVERAGE_BONUS = 0.35f
    private const val EDGE_DECAY = 0.5f
    private const val MAX_TOKENS = 32
    private const val SNIPPET_CHARS = 240

    fun search(
        query: String,
        rows: List<MemorySearchRow>,
        tagsByMemory: Map<Long, List<String>>,
        links: List<MemoryLinkEntity>,
        config: MemorySearchConfig,
        folderPath: String? = null,
        excludedIds: Set<Long> = emptySet(),
        queryEmbedding: FloatArray? = null,
        embeddings: Map<Long, FloatArray> = emptyMap(),
    ): MemorySearchOutcome {
        val scoped = rows.filter { folderPath.isNullOrBlank() || it.folderPath == folderPath }
        if (scoped.isEmpty()) return MemorySearchOutcome(emptyList(), 0, 0)

        val normalizedQuery = query.trim()
        val tokens = parseTokens(normalizedQuery)
        val listAll = normalizedQuery.isEmpty() || normalizedQuery == "*"

        val baseScores = LinkedHashMap<Long, ScoreParts>()
        scoped.forEach { row ->
            val tags = tagsByMemory[row.id].orEmpty()
            val parts =
                if (listAll) {
                    ScoreParts(keyword = 1f, tag = 0f, edge = 0f, matched = emptyList(), reason = "全部")
                } else {
                    scoreRow(row, tags, tokens)
                }
            val semanticCandidate = queryEmbedding != null && embeddings[row.id] != null
            if (parts.matched.isNotEmpty() || listAll || semanticCandidate) baseScores[row.id] = parts
        }

        if (baseScores.isEmpty()) {
            return MemorySearchOutcome(emptyList(), 0, 0)
        }

        if (!listAll && config.edgeWeight > 0f) {
            expandByEdges(baseScores, scoped, links)
        }

        val multipliers = when (config.mode) {
            MemoryScoreMode.BALANCED -> ScoreMultipliers(1f, 1f, 1f, 1f)
            MemoryScoreMode.KEYWORD_FIRST -> ScoreMultipliers(1.3f, 0.9f, 0.8f, 0.8f)
            MemoryScoreMode.SEMANTIC_FIRST -> ScoreMultipliers(0.85f, 1.1f, 1.15f, 1.3f)
        }

        val rowsById = scoped.associateBy { it.id }
        var excluded = 0
        val hits = ArrayList<MemorySearchHit>(baseScores.size)
        baseScores.forEach { (id, parts) ->
            if (id in excludedIds) {
                excluded++
                return@forEach
            }
            val row = rowsById[id] ?: return@forEach
            val importanceFactor = sqrt(row.importance.coerceIn(0f, 1f) + 0.1f)
            val semanticScore =
                if (queryEmbedding != null) {
                    embeddings[id]?.let { MemoryVectorCodec.cosine(queryEmbedding, it) }
                        ?.coerceAtLeast(0f)
                        ?: 0f
                } else {
                    0f
                }
            val score =
                (parts.keyword * config.keywordWeight * multipliers.keyword +
                    parts.tag * config.tagWeight * multipliers.tag +
                    parts.edge * config.edgeWeight * multipliers.edge +
                    semanticScore * config.vectorWeight * multipliers.semantic) * importanceFactor
            if (score < config.threshold) return@forEach
            hits +=
                MemorySearchHit(
                    id = row.id,
                    title = row.title,
                    content = row.content,
                    folderPath = row.folderPath,
                    importance = row.importance,
                    score = score,
                    matchedTokens = parts.matched,
                    tags = tagsByMemory[row.id].orEmpty(),
                    reason = parts.reason,
                )
        }

        val sorted = hits.sortedWith(
            compareByDescending<MemorySearchHit> { it.score }
                .thenByDescending { rowsById[it.id]?.updatedAt ?: 0L },
        )
        val limited = sorted.take(config.limit.coerceIn(1, 200))
        return MemorySearchOutcome(limited, baseScores.size, excluded)
    }

    /** 摘要用：截断正文，避免工具结果过长。 */
    fun snippet(content: String, maxChars: Int = SNIPPET_CHARS): String {
        if (content.length <= maxChars) return content
        var end = maxChars
        if (end > 0 && content[end - 1].isHighSurrogate()) end--
        return content.substring(0, end) + "…"
    }

    private data class ScoreMultipliers(
        val keyword: Float,
        val tag: Float,
        val edge: Float,
        val semantic: Float,
    )

    private data class ScoreParts(
        var keyword: Float = 0f,
        var tag: Float = 0f,
        var edge: Float = 0f,
        var matched: List<String>,
        var reason: String,
    )

    private fun scoreRow(
        row: MemorySearchRow,
        tags: List<String>,
        tokens: List<QueryToken>,
    ): ScoreParts {
        if (tokens.isEmpty()) return ScoreParts(matched = emptyList(), reason = "")

        var titleHits = 0
        var contentHits = 0
        var tagHits = 0
        val matched = ArrayList<String>(tokens.size)

        tokens.forEach { token ->
            val inTitle = token.matches(row.title)
            val inContent = token.matches(row.content)
            val inTag = tags.any { token.matches(it) }
            if (inTitle) titleHits++
            if (inContent) contentHits++
            if (inTag) tagHits++
            if (inTitle || inContent || inTag) matched += token.raw
        }

        if (matched.isEmpty()) return ScoreParts(matched = emptyList(), reason = "")

        val total = tokens.size.toFloat()
        val coverage = matched.size / total
        val coverageMultiplier = 1f + COVERAGE_BONUS * coverage
        val keyword =
            (TITLE_WEIGHT * (titleHits / total) + CONTENT_WEIGHT * (contentHits / total)) *
                coverageMultiplier
        val tag = (tagHits / total) * coverageMultiplier

        val reason =
            buildString {
                if (titleHits > 0) append("标题命中 ")
                if (contentHits > 0) append("正文命中 ")
                if (tagHits > 0) append("标签命中 ")
            }.trim()

        return ScoreParts(keyword = keyword, tag = tag, matched = matched, reason = reason)
    }

    private fun expandByEdges(
        scores: MutableMap<Long, ScoreParts>,
        rows: List<MemorySearchRow>,
        links: List<MemoryLinkEntity>,
    ) {
        if (links.isEmpty()) return
        val rowIds = rows.mapTo(HashSet()) { it.id }
        val adjacent = HashMap<Long, MutableList<Pair<Long, Float>>>()
        links.forEach { link ->
            if (link.sourceId !in rowIds || link.targetId !in rowIds) return@forEach
            adjacent.getOrPut(link.sourceId) { mutableListOf() } += link.targetId to link.weight
            adjacent.getOrPut(link.targetId) { mutableListOf() } += link.sourceId to link.weight
        }
        val seeds = scores.filterValues { it.keyword > 0f || it.tag > 0f }.keys.toList()
        if (seeds.isEmpty()) return
        seeds.forEach { seedId ->
            adjacent[seedId]?.forEach { (neighborId, weight) ->
                val existing = scores[neighborId]
                val edgeScore =
                    EDGE_DECAY * weight.coerceIn(0f, 2f) * scores.getValue(seedId).let {
                        (it.keyword + it.tag).coerceAtLeast(0.1f)
                    }
                if (existing == null) {
                    scores[neighborId] =
                        ScoreParts(edge = edgeScore, matched = emptyList(), reason = "关联扩展")
                } else {
                    existing.edge = maxOf(existing.edge, edgeScore)
                }
            }
        }
    }

    private data class QueryToken(
        val raw: String,
        private val regex: Regex?,
        private val literal: String?,
    ) {
        fun matches(text: String): Boolean {
            if (text.isEmpty()) return false
            regex?.let { return it.containsMatchIn(text) }
            val value = literal ?: return false
            return text.contains(value, ignoreCase = true)
        }
    }

    private fun parseTokens(query: String): List<QueryToken> {
        if (query.isEmpty() || query == "*") return emptyList()
        val raw = if (query.contains('|')) query.split('|') else query.split(Regex("\\s+"))
        return raw.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_TOKENS)
            .map { token ->
                if (token.contains('*')) {
                    // 逐段转义后再用 .* 连接，避免 Regex.escape 把 * 一起转义掉。
                    val pattern = token.split('*').joinToString(".*") { Regex.escape(it) }
                    QueryToken(token, Regex(pattern, RegexOption.IGNORE_CASE), null)
                } else {
                    QueryToken(token, null, token)
                }
            }
            .toList()
    }
}
