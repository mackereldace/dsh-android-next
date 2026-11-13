package com.phoneagent

import android.content.Context
import java.io.File

/**
 * Generates the shell script the app exposes to the rest of the system.
 *
 * The interactive TUI launchers (zcode / dsh-tui / codex / claude / opencode)
 * went away with the terminal; dsh now runs only as the embedded web service,
 * so the single remaining script is the Magisk module wrapper that puts `dsh`
 * on PATH under /system/bin.
 */
object SetupScripts {

    private fun scriptsDir(ctx: Context): File =
        File(ctx.filesDir, "scripts").apply { mkdirs() }

    /** /system/bin/dsh wrapper installed by the Magisk module (root integration). */
    fun sysDshWrapper(): String {
        return """
#!/system/bin/sh
# DshAndroid Next system-wide dsh (Magisk module). Runs WITHOUT root.
PA="/system/etc/phone_agent/usr"
export LD_LIBRARY_PATH="${'$'}PA/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
export TMPDIR="${'$'}{TMPDIR:-/data/local/tmp}"
export HOME="${'$'}{DSH_HOME:-${'$'}HOME}"
case "${'$'}HOME" in ""|"/") HOME=/data/local/tmp ;; esac
mkdir -p "${'$'}HOME" 2>/dev/null
export NODE_PATH="${'$'}PA/lib/node_modules"
exec "${'$'}PA/bin/node" "${'$'}PA/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" "${'$'}@"
""".trim() + "\n"
    }
}
