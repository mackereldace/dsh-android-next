package com.phoneagent

import android.content.Context

/**
 * Remembers that the dsh engine was running, so a reboot can bring it back.
 * Written when the dsh activity opens, cleared when the user leaves for good.
 *
 * dsh-only build: the state is a boolean, not a set of agent ids. The stored
 * key keeps the "running" string-set shape so an upgrade from a multi-agent
 * build does not need a migration.
 */
object EngineState {

    private const val FILE = "engine-state"
    private const val KEY = "running"

    fun markRunning(ctx: Context, agent: String = AgentRegistry.DSH_ID) {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(agent)
        prefs.edit().putStringSet(KEY, set).apply()
    }

    fun markStopped(ctx: Context, agent: String = AgentRegistry.DSH_ID) {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.remove(agent)
        prefs.edit().putStringSet(KEY, set).apply()
    }

    fun isRunning(ctx: Context, agent: String = AgentRegistry.DSH_ID): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())?.contains(agent) ?: false

    fun allRunning(ctx: Context): Set<String> =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()) ?: emptySet()

    /** Restores the remembered engine. Returns 1 when it was relaunched. */
    fun restoreAll(ctx: Context): Int {
        if (!Prefs.restoreOnBoot(ctx)) return 0
        if (!allRunning(ctx).contains(AgentRegistry.DSH_ID)) return 0
        return try {
            ctx.startForegroundService(android.content.Intent(ctx, DshService::class.java))
            WatchdogTargets.watchDsh(ctx)
            1
        } catch (_: Exception) {
            0
        }
    }
}
