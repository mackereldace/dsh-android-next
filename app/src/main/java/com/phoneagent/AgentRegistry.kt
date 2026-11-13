package com.phoneagent

/**
 * Single source of truth for the agent this build can run.
 *
 * The upstream project shipped five agents (zcode / dsh / opencode / codex /
 * claude) hard-coded as branches across the picker, the services, the update
 * screen and the watchdog. This build is dsh-only: the registry keeps the same
 * shape (so `byId` / `label` keep working) but resolves to exactly one entry.
 */
object AgentRegistry {

    enum class Kind { NODE_CLI, BINARY, BUN }

    data class Agent(
        val id: String,
        val label: String,
        val kind: Kind,
        /** loopback web UI port, or null for TUI-only agents */
        val port: Int? = null,
        /** true when an official web UI exists (drives the launcher icon) */
        val webUi: Boolean = false,
        /** activity hosting the web UI; null when TUI only */
        val webActivity: Class<*>? = null,
        val service: Class<*>? = null,
        val blurb: String = "",
        val blurbUnavailable: String = "运行时未就绪：重新打开 APP 解压运行时后再试",
        val iconRes: Int = 0,
        /** binary-backed agents need an asset extraction before they can run */
        val needsAsset: Boolean = false,
    )

    const val DSH_ID = "dsh"
    const val DSH_PORT = 3080

    val all: List<Agent> = listOf(
        Agent(
            DSH_ID, "DeepSeek Harness", Kind.NODE_CLI,
            port = DSH_PORT, webUi = true,
            webActivity = DshActivity::class.java, service = DshService::class.java,
            blurb = "DeepSeek 官方 Harness（内嵌 Node，自动安装）", iconRes = R.drawable.ic_dsh_fg,
        ),
    )

    fun byId(id: String): Agent? = all.firstOrNull { it.id == id }

    fun label(id: String): String = byId(id)?.label ?: id

    /** Agents whose web UI can be watched for liveness. */
    fun withPort(): List<Agent> = all.filter { it.port != null && it.service != null }

    fun hasWebUi(id: String): Boolean = byId(id)?.webUi == true

    fun isInstalled(ctx: android.content.Context, id: String): Boolean =
        id == DSH_ID && AgentSlots.read(ctx, AgentSlots.DSH) != null
}
