package com.phoneagent

/**
 * Pinned component versions. MUST stay in sync with scripts/versions.env,
 * which drives the CI scripts that stage the matching APK assets.
 *
 * dsh-only build: zcode / codex / claude / opencode are no longer staged, so
 * their version constants are gone. DSH is the only node CLI that ships.
 */
object Versions {
    const val RUNTIME = "node-26.4.0-3"
    const val DSH = "0.2.2-rc.1"
    /** Static musl ripgrep that backs dsh's glob/grep on Android. */
    const val RIPGREP_VERSION = "1.18.0"

    /**
     * pnpm 10 for dsh's plugin manager. pnpm 12 ships a native executable whose
     * store operation lock (flock-based) is unsupported on the target
     * filesystems; 10.x is pure JS and verified working on device.
     */
    const val PNPM_VERSION = "10.34.6"

    const val DSH_TGZ = "deepseek-ai-dsh-$DSH.tgz"

    /** Marker content stored after a successful CLI install. */
    fun packagesMarker(): String = "@deepseek-ai/dsh@$DSH"
}
