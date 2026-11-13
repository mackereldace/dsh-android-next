package com.phoneagent

import android.content.Context

/**
 * Starts battery/health watching for the web service an activity just
 * launched. Idempotent: the watchdog keeps a single tick thread and quietly
 * ignores targets already registered.
 *
 * dsh-only build: the registry resolves to one agent, so this is effectively
 * "watch 3080". The loopback tool channel (8899) is a separate foreground
 * service and is not health-probed here.
 */
object WatchdogTargets {

    /** Registers every web agent from the registry with the watchdog. */
    fun watchAll(ctx: Context) {
        val targets = AgentRegistry.withPort().mapNotNull { a ->
            val port = a.port ?: return@mapNotNull null
            val svc = a.service ?: return@mapNotNull null
            Watchdog.Target(a.id, port, a.label, svc) { DshState.process }
        }
        Watchdog.reset()
        Watchdog.start(ctx, targets)
    }

    fun watchDsh(ctx: Context) {
        Watchdog.reset(AgentRegistry.DSH_ID)
        Watchdog.start(ctx, target(AgentRegistry.DSH_ID) ?: return)
    }

    private fun target(id: String): List<Watchdog.Target> {
        val a = AgentRegistry.byId(id) ?: return emptyList()
        val port = a.port ?: return emptyList()
        val svc = a.service ?: return emptyList()
        return listOf(Watchdog.Target(a.id, port, a.label, svc) { DshState.process })
    }
}
