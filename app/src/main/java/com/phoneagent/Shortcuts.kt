package com.phoneagent

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Recreates the launcher shortcuts for the remaining entry points. Users can
 * delete pinned shortcuts from their launcher; this puts them back without
 * reinstalling.
 *
 * dsh-only build: the Zcode / Zcode Web / 终端 shortcuts are gone with their
 * activities.
 */
object Shortcuts {

    private data class Def(val id: String, val label: String, val cls: Class<*>, val icon: Int)

    private fun defs() = listOf(
        Def("shortcut_dsh", "DeepSeek Harness", DshActivity::class.java, R.mipmap.ic_dsh),
        Def("shortcut_tools", "工具箱", LauncherActivity::class.java, R.mipmap.ic_launcher),
    )

    /** Pins all entry shortcuts again (idempotent: existing ones are updated). */
    fun recreateAll(ctx: Context): List<String> {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
            return listOf("当前桌面不支持固定快捷方式（Pinned shortcuts）")
        }
        val mgr = ctx.getSystemService(Context.SHORTCUT_SERVICE) as ShortcutManager
        val log = mutableListOf<String>()
        for (d in defs()) {
            val intent = Intent(Intent.ACTION_VIEW).setClassName(ctx, d.cls.name)
            val info = ShortcutInfoCompat.Builder(ctx, d.id)
                .setShortLabel(d.label)
                .setIcon(IconCompat.createWithResource(ctx, d.icon))
                .setIntent(intent)
                .build()
            val ok = try {
                val existing = mgr.pinnedShortcuts.firstOrNull { it.id == d.id }
                if (existing != null) {
                    ShortcutManagerCompat.updateShortcuts(ctx, listOf(info))
                    true
                } else {
                    ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
                    true
                }
            } catch (e: Exception) {
                log.add("[fail] ${d.label}: ${e.message}")
                false
            }
            if (ok) log.add("[ok] ${d.label}")
        }
        log.add("桌面弹出确认后即可恢复；若桌面未弹出，请检查启动器是否允许固定快捷方式。")
        return log
    }
}
