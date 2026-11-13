package com.phoneagent

import android.content.Context
import com.github.luben.zstd.ZstdInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-session view over the three agents' local stores.
 *

 *  dsh      ~/.dsh/sessions/<slug>/<dir>/     session.v3.jsonl[.zstd] event log (multi-frame zstd)

 */
object SessionData {

    data class ModelRow(val model: String, val requests: Int, val input: Long, val output: Long, val reasoning: Long)
    data class ToolRow(val name: String, val count: Int, val durationMs: Long, val errors: Int)

    data class AgentSession(
        val agent: String, // dsh
        val id: String,
        val title: String,
        val directory: String,
        val createdAt: Long,
        val updatedAt: Long,
        val requests: Int,
        val userMessages: Int,
        val assistantMessages: Int,
        val toolCalls: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val reasoningTokens: Long,
        val cacheTokens: Long,
        val durationMs: Long,
        val retries: Int,
        val subagent: Boolean,
        val model: String,
        /** true when the session was written by a terminal TUI (not the web UI) */
        val fromTui: Boolean = false,
    )

    data class SessionDetail(
        val session: AgentSession,
        val models: List<ModelRow>,
        val tools: List<ToolRow>,
        val inputs: List<String>,
    )

    // ───────────────────────── dsh ─────────────────────────

    fun dshAvailable(ctx: Context): Boolean = DshUsage.sessionsRoot(ctx).isDirectory

    private class DshParsed(
        val id: String, val dir: File, val cwd: String,
        val createdAt: Long, val subagent: Boolean,
        var title: String, var lastAt: Long, var model: String,
        var userMessages: Int, var assistantMessages: Int, var requests: Int,
        var toolCalls: Int, var retries: Int,
        var input: Long, var output: Long, var reasoning: Long, var cache: Long,
        val tools: HashMap<String, IntArray>, // name -> [count, errors]
        val models: HashMap<String, LongArray>, // model -> [reqs, in, out, reasoning]
        val inputs: MutableList<String>,
        val fromTui: Boolean,
    )

    private fun dshParseAll(ctx: Context): List<DshParsed> {
        val root = DshUsage.sessionsRoot(ctx)
        val tuiRoot = File(root.parentFile, "sessions-tui")
        val byDir = LinkedHashMap<File, MutableList<File>>()
        val tuiDirs = HashSet<File>()
        for (r in arrayOf(root, tuiRoot)) {
            if (!r.isDirectory) continue
            r.walkTopDown().maxDepth(3)
                .filter { it.isFile && it.name.contains("jsonl") }
                .forEach {
                    byDir.getOrPut(it.parentFile) { mutableListOf() }.add(it)
                    if (r == tuiRoot) tuiDirs.add(it.parentFile)
                }
        }
        val out = mutableListOf<DshParsed>()
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        for ((dir, files) in byDir) {
            val fromTui = dir in tuiDirs
            var parsed: DshParsed? = null
            for (f in files) {
                try {
                    f.inputStream().use { raw ->
                        val input: InputStream =
                            if (f.name.endsWith(".zstd")) ZstdInputStream(raw).apply { setContinuous(true) } else raw
                        BufferedInputStream(input).use { stream ->
                            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                                for (line in lines) {
                                    if (!line.trim().isNotEmpty()) continue
                                    val o = try { JSONObject(line) } catch (_: Exception) { continue }
                                    val t = o.optString("type")
                                    val time = o.optLong("time", 0L)
                                    when (t) {
                                        "session" -> {
                                            parsed = DshParsed(
                                                id = o.optString("id", dir.name), dir = dir,
                                                cwd = o.optString("cwd"), createdAt = o.optLong("createdAt", time),
                                                subagent = o.optString("origin") == "subagent",
                                                title = "", lastAt = time, model = "",
                                                userMessages = 0, assistantMessages = 0, requests = 0,
                                                toolCalls = 0, retries = 0, input = 0, output = 0, reasoning = 0,
                                                cache = 0, tools = HashMap(), models = HashMap(),
                                                inputs = mutableListOf(), fromTui = fromTui,
                                            )
                                        }
                                        "session/title" -> parsed?.let {
                                            if (time >= it.lastAt - 1) it.title = o.optJSONObject("data")?.optString("title").orEmpty()
                                        }
                                        "user/message" -> parsed?.let {
                                            it.userMessages++
                                            it.lastAt = maxOf(it.lastAt, time)
                                            if (it.title.isEmpty()) {
                                                val c = o.optJSONObject("data")?.optJSONArray("content")
                                                if (c != null && c.length() > 0) it.title = c.optJSONObject(0)?.optString("text").orEmpty().take(60)
                                            }
                                        }
                                        "assistant/message" -> parsed?.let { s ->
                                            val data = o.optJSONObject("data") ?: return@let
                                            val usage = data.optJSONObject("usage") ?: return@let
                                            s.assistantMessages++
                                            s.requests++
                                            s.lastAt = maxOf(s.lastAt, time)
                                            s.input += usage.optLong("inputTokens", 0)
                                            s.output += usage.optLong("outputTokens", 0)
                                            s.cache += usage.optLong("cacheReadTokens", 0)
                                            s.reasoning += usage.optLong("reasoningTokens", 0)
                                            val src = data.optJSONObject("message")?.optJSONObject("source")
                                            val model = (src?.optString("provider").orEmpty() + "/" + src?.optString("model").orEmpty().ifEmpty { "?" })
                                            s.model = model
                                            val m = s.models.getOrPut(model) { LongArray(4) }
                                            m[0]++; m[1] += usage.optLong("inputTokens", 0); m[2] += usage.optLong("outputTokens", 0); m[3] += usage.optLong("reasoningTokens", 0)
                                        }
                                        "tool/call" -> parsed?.let { s ->
                                            val data = o.optJSONObject("data") ?: return@let
                                            val name = data.optString("name", "?")
                                            s.toolCalls++
                                            s.lastAt = maxOf(s.lastAt, time)
                                            val tt = s.tools.getOrPut(name) { IntArray(2) }
                                            tt[0]++
                                        }
                                        "tool/result" -> parsed?.let { s ->
                                            val data = o.optJSONObject("data") ?: return@let
                                            s.lastAt = maxOf(s.lastAt, time)
                                            val callId = data.optJSONObject("message")?.optString("id").orEmpty()
                                            val name = data.optString("name", "")
                                            // count errors: result content carries isError
                                            val msg = data.optJSONObject("message")
                                            val contents = msg?.optJSONArray("content")
                                            var isError = false
                                            if (contents != null) {
                                                for (i in 0 until contents.length()) {
                                                    val ct = contents.optJSONObject(i)?.optJSONArray("content") ?: continue
                                                    for (j in 0 until ct.length()) {
                                                        if (ct.optJSONObject(j)?.optBoolean("isError") == true) isError = true
                                                    }
                                                }
                                            }
                                            if (isError) {
                                                val key = if (name.isNotEmpty()) name else "未知工具"
                                                val tt = s.tools.getOrPut(key) { IntArray(2) }
                                                tt[1]++
                                            }
                                        }
                                        "llm/retry" -> parsed?.let { s ->
                                            s.retries++
                                            s.lastAt = maxOf(s.lastAt, time)
                                        }
                                        "turn/start" -> parsed?.let { s -> s.lastAt = maxOf(s.lastAt, time) }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            parsed?.let { p ->
                if (p.title.isEmpty()) p.title = p.id.take(12)
                out += p
            }
        }
        return out
    }

    private fun toSession(p: DshParsed): AgentSession = AgentSession(
        agent = "dsh", id = p.id, title = p.title.ifEmpty { p.id.take(12) },
        directory = p.cwd, createdAt = p.createdAt, updatedAt = p.lastAt,
        requests = p.requests, userMessages = p.userMessages, assistantMessages = p.assistantMessages,
        toolCalls = p.toolCalls, inputTokens = p.input, outputTokens = p.output,
        reasoningTokens = p.reasoning, cacheTokens = p.cache,
        durationMs = (p.lastAt - p.createdAt).coerceAtLeast(0), retries = p.retries,
        subagent = p.subagent, model = p.model, fromTui = p.fromTui,
    )

    fun dshList(ctx: Context): List<AgentSession> =
        dshParseAll(ctx).map(::toSession).sortedByDescending { it.updatedAt }

    fun dshDetail(ctx: Context, id: String): SessionDetail {
        val p = dshParseAll(ctx).firstOrNull { it.id == id }
            ?: return SessionDetail(AgentSession("dsh", id, id, "", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, ""), emptyList(), emptyList(), emptyList())
        val models = p.models.entries.map { (m, v) -> ModelRow(m, v[0].toInt(), v[1], v[2], v[3]) }.sortedByDescending { it.input }
        val tools = p.tools.entries.map { (n, v) -> ToolRow(n, v[0], 0, v[1]) }.sortedByDescending { it.count }
        return SessionDetail(toSession(p), models, tools, p.inputs.toList())
    }

    /** dsh sessions live in their own directory — removal is a recursive delete. */
    fun dshDelete(ctx: Context, id: String): Boolean {
        val p = dshParseAll(ctx).firstOrNull { it.id == id } ?: return false
        return p.dir.deleteRecursively()
    }

}