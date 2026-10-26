package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 结构化记忆库工具（Operit 记忆库模型的窄接口）。
 *
 * 记忆正文不进入系统提示，模型按需调用这些工具检索与写入。
 */
internal object MemoryLibraryToolCatalog {
    val names: Set<String> =
        setOf(
            "query_memory",
            "get_memory_by_title",
            "create_memory",
            "create_memory_document",
            "update_memory",
            "delete_memory",
            "move_memory",
            "link_memories",
            "query_memory_links",
            "update_memory_link",
            "delete_memory_link",
            "list_memory_spaces",
        )

    fun appendTo(tools: JSONArray, writable: Boolean = true) {
        tools.put(
            AgentToolSchema.function(
                name = "query_memory",
                description =
                    "Search the structured memory library. Returns matching memories with id, " +
                        "title, snippet, score and tags. Use natural-language words, " +
                        "space-separated terms, or `|` between keywords; `*` inside a keyword " +
                        "is a wildcard, and a lone `*` lists everything. Omit `space` to search " +
                        "the current conversation's default space.",
                parameters =
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("query", string("Search words or keywords, `*` lists all."))
                                .put("space", string("Optional memory space id, e.g. `global` or `character:<id>`."))
                                .put("folder_path", string("Optional folder path filter."))
                                .put(
                                    "snapshot_id",
                                    string("Reuse a snapshot id to exclude memories already returned in this task."),
                                )
                                .put("threshold", number("Minimum score, default 0."))
                                .put("limit", integer("Max results, default 20, max 200.")),
                        )
                        .put("required", JSONArray().put("query")),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "get_memory_by_title",
                description = "Read one memory's full content by exact title.",
                parameters =
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("title", string("Exact memory title."))
                                .put("space", string("Optional memory space id."))
                                .put("max_chars", integer("Max characters to return, default 12000, max 32000.")),
                        )
                        .put("required", JSONArray().put("title")),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "query_memory_links",
                description = "List outgoing and incoming links of one memory.",
                parameters =
                    JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject().put("id", integer("Memory id.")))
                        .put("required", JSONArray().put("id")),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "list_memory_spaces",
                description = "List memory spaces and how many memories each contains.",
                parameters = JSONObject().put("type", "object").put("properties", JSONObject()),
            ),
        )
        if (!writable) return
        tools.put(
            AgentToolSchema.function(
                name = "create_memory",
                description =
                    "Create a durable memory entry. Store stable facts, preferences, relationships, " +
                        "ongoing projects or work context. Never store secrets, credentials or verification codes. " +
                        "Omit `space` to write the current conversation's space; use `global` to write " +
                        "cross-character real-world memory, `character:<id>` for one character's story memory.",
                parameters =
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("title", string("Short unique title."))
                                .put("content", string("Memory body, Markdown allowed."))
                                .put("tags", stringArray("Optional tags."))
                                .put("folder_path", string("Optional folder, e.g. `工作/项目A`."))
                                .put("importance", number("0..1, default 0.5."))
                                .put("space", string("Optional target memory space id.")),
                        )
                        .put("required", JSONArray().put("title").put("content")),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "create_memory_document",
                description =
                    "Import a long document as one memory plus chunked blocks. Use this for notes, " +
                        "transcripts or reference material instead of create_memory.",
                parameters =
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("title", string("Document title."))
                                .put("content", string("Full document text."))
                                .put("tags", stringArray("Optional tags."))
                                .put("folder_path", string("Optional folder path."))
                                .put("chunk_size", integer("Chunk size in characters, default 2000."))
                                .put("space", string("Optional target memory space id.")),
                        )
                        .put("required", JSONArray().put("title").put("content")),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "update_memory",
                description = "Update an existing memory by id. Only provided fields change.",
                parameters =
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("id", integer("Memory id."))
                                .put("title", string("New title."))
                                .put("content", string("New body."))
                                .put("tags", stringArray("Replacement tag list."))
                                .put("folder_path", string("New folder path."))
                                .put("importance", number("0..1.")),
                        )
                        .put("required", JSONArray().put("id")),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "delete_memory",
                description = "Delete a memory by id, including its tags and links.",
                parameters =
                    JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject().put("id", integer("Memory id.")))
                        .put("required", JSONArray().put("id")),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "move_memory",
                description = "Move a memory to another memory space.",
                parameters =
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("id", integer("Memory id."))
                                .put("space", string("Target memory space id.")),
                        )
                        .put("required", JSONArray().put("id").put("space")),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "link_memories",
                description = "Create a directed relation between two memories.",
                parameters =
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("source_id", integer("Source memory id."))
                                .put("target_id", integer("Target memory id."))
                                .put("type", string("Relation label, e.g. `related`, `part_of`."))
                                .put("description", string("Optional note."))
                                .put("weight", number("0..2, default 1.")),
                        )
                        .put("required", JSONArray().put("source_id").put("target_id").put("type")),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "update_memory_link",
                description = "Update one existing memory link by id.",
                parameters =
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("link_id", integer("Link id."))
                                .put("type", string("New relation label."))
                                .put("weight", number("0..2."))
                                .put("description", string("New note.")),
                        )
                        .put("required", JSONArray().put("link_id")),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "delete_memory_link",
                description = "Delete one memory link by id.",
                parameters =
                    JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject().put("link_id", integer("Link id.")))
                        .put("required", JSONArray().put("link_id")),
            ),
        )
    }

    private fun string(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun integer(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)

    private fun number(description: String): JSONObject =
        JSONObject().put("type", "number").put("description", description)

    private fun stringArray(description: String): JSONObject =
        JSONObject()
            .put("type", "array")
            .put("description", description)
            .put("items", JSONObject().put("type", "string"))
}
