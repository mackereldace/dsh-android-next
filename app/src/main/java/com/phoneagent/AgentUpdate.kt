package com.phoneagent

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * dsh self-update: queries the registry for @deepseek-ai/dsh and installs a
 * freshly downloaded tarball into the standby A/B slot.
 *
 * This is the only updater in the build — the multi-agent version also had to
 * chase zcode / codex / claude / opencode, which no longer ship. dsh has no
 * self-update command (it delegates to plugins), so updating means a fresh
 * `npm install -g` over a slot tree plus re-applying the Android patches
 * before the new tree is ever activated.
 *
 * Version pinning stays in Versions.kt for a cold install; this path lets the
 * user move to a newer dsh without shipping a new APK.
 */
object AgentUpdate {

    data class Info(val name: String, val installed: String?, val latest: String) {
        val updateAvailable: Boolean get() = installed != null && installed != latest
    }

    const val DSH_PKG = "@deepseek-ai/dsh"

    private fun registry(ctx: Context): String = Prefs.npmRegistry(ctx).trimEnd('/')

    /**
     * Opens a registry connection, honouring the configured HTTP proxy.
     * HttpURLConnection ignores the proxy Prefs on its own, so on proxy-only
     * networks every request timed out ("无法获取元数据：timeout").
     */
    fun open(ctx: Context, url: String): HttpURLConnection {
        val proxy = Prefs.httpProxy(ctx)
        if (proxy.isNotEmpty()) {
            runCatching {
                val host = proxy.substringAfter("://").substringBefore(':')
                val port = proxy.substringAfterLast(':').trimEnd('/').toIntOrNull() ?: 80
                val p = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port))
                URL(url).openConnection(p) as HttpURLConnection
            }.onSuccess { return it }
        }
        return URL(url).openConnection() as HttpURLConnection
    }

    /**
     * Registry JSON via the embedded Node.
     *
     * The Java stack still fails on https-over-CONNECT through some proxies
     * (verified on device: HttpURLConnection timed out on 4 of 5 package URLs
     * while Node's fetch returned all five), so this is the primary path for
     * metadata. Node needs HTTPS_PROXY/NODE_USE_ENV_PROXY to be set, which
     * NodeRuntime.environment() already does.
     */
    private fun nodeFetchJson(ctx: Context, url: String): JSONObject? {
        val node = NodeRuntime.nodeBin(ctx)
        if (!node.canExecute()) return null
        val script = "fetch(process.argv[1],{signal:AbortSignal.timeout(20000)})" +
            ".then(r=>{if(!r.ok)throw new Error('HTTP '+r.status);return r.text()})" +
            ".then(t=>{process.stdout.write(t);process.exit(0)})" +
            ".catch(e=>{process.stderr.write(String(e.message));process.exit(1)})"
        return try {
            val pb = ProcessBuilder(node.absolutePath, "-e", script, url)
            pb.environment().putAll(NodeRuntime.environment(ctx))
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0 || out.isBlank()) null else runCatching { JSONObject(out) }.getOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** Registry JSON for [url]: Node first (proxy-aware), Java as fallback. */
    fun registryJsonFor(ctx: Context, url: String): JSONObject? =
        nodeFetchJson(ctx, url) ?: runCatching {
            val conn = open(ctx, url)
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        }.getOrNull()

    /**
     * Downloads [url] to [out], again Node-first: tarballs are hundreds of MB
     * and the Java path times out through the proxy where Node's fetch works.
     */
    private fun download(ctx: Context, url: String, out: File, onFail: (String) -> Unit): Boolean {
        nodeDownload(ctx, url, out, onFail)?.let { return it }
        return try {
            val conn = open(ctx, url)
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
            true
        } catch (e: Exception) {
            onFail(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /** Fetch [url] to [out] with the embedded Node. Null when Node is unusable. */
    private fun nodeDownload(ctx: Context, url: String, out: File, onFail: (String) -> Unit): Boolean? {
        val node = NodeRuntime.nodeBin(ctx)
        if (!node.canExecute()) return null
        val tmp = File(out.absolutePath + ".part")
        // fetch() returns a WEB ReadableStream — it has no .pipe(); adapt it
        // with Readable.fromWeb before piping into the createWriteStream
        val script = "const{createWriteStream}=require('fs');" +
            "const{Readable}=require('stream');" +
            "fetch(process.argv[1]).then(r=>{if(!r.ok)throw new Error('HTTP '+r.status);" +
            "const w=createWriteStream(process.argv[2]);" +
            "return new Promise((res,rej)=>{Readable.fromWeb(r.body).pipe(w);w.on('finish',res);w.on('error',rej)})})" +
            ".then(()=>process.exit(0)).catch(e=>{process.stderr.write(String(e.message));process.exit(1)})"
        return try {
            val pb = ProcessBuilder(node.absolutePath, "-e", script, url, tmp.absolutePath)
            pb.environment().putAll(NodeRuntime.environment(ctx))
            pb.redirectErrorStream(true)
            val p = pb.start()
            val text = p.inputStream.bufferedReader().readText()
            val rc = p.waitFor()
            if (rc == 0 && tmp.exists() && tmp.length() > 0) {
                tmp.renameTo(out)
                true
            } else {
                tmp.delete()
                onFail(text.trim().ifEmpty { "node 退出码 $rc" }) // Node ran and failed: report it rather than retrying Java
                false
            }
        } catch (e: Exception) {
            onFail(e.message ?: e.javaClass.simpleName)
            null
        }
    }

    /** Latest version of [pkg] from the configured registry (falls back to npmjs). */
    fun latestVersion(ctx: Context, pkg: String): String? = try {
        val encoded = pkg.replace("/", "%2f")
        registryJsonFor(ctx, "${registry(ctx)}/$encoded/latest")?.getString("version")
    } catch (_: Exception) {
        null
    }

    /** Installed dsh version on disk (active tree), or null when absent. */
    fun installedVersion(ctx: Context): String? = try {
        JSONObject(File(NodeRuntime.usrDir(ctx), "lib/node_modules/@deepseek-ai/dsh/package.json").readText())
            .getString("version")
    } catch (_: Exception) {
        null
    }

    /** Current install + registry newest, for the settings row. Blocking. */
    fun status(ctx: Context): List<Info> =
        listOf(Info(DSH_PKG, installedVersion(ctx), latestVersion(ctx, DSH_PKG) ?: "未知"))

    /** Downloads [pkg]@[version] tarball into the pkg dir and returns the file. */
    fun fetchTarball(ctx: Context, pkg: String, version: String, onLine: (String) -> Unit): File? {
        val enc = pkg.replace("/", "%2f")
        val meta = try {
            registryJsonFor(ctx, "${registry(ctx)}/$enc") ?: throw Exception("registry 不可达")
        } catch (e: Exception) {
            onLine("[update] 无法获取 $pkg 元数据：${e.message}")
            return null
        }
        val tarball = try {
            meta.getJSONObject("versions").getJSONObject(version).getJSONObject("dist").getString("tarball")
        } catch (_: Exception) {
            onLine("[update] registry 上没有 $pkg@$version")
            return null
        }
        val safe = pkg.replace("@", "").replace("/", "-")
        val out = File(NodeRuntime.pkgDir(ctx), "$safe-$version.tgz")
        onLine("[update] 下载 $pkg@$version ...")
        if (!download(ctx, tarball, out) { onLine("[update] 下载失败：$it") }) {
            out.delete()
            return null
        }
        onLine("[update] 下载完成：${"%.1f".format(out.length() / 1048576.0)}MB")
        return out
    }

    /**
     * Installs [tgzFile] into the standby slot (A/B). The new version becomes
     * active on the next DshService boot; if that boot fails, the previous tree
     * is restored automatically. The Android patches are applied to the standby
     * tree BEFORE activation, so the verification boot runs the adapted build.
     */
    fun applyUpdate(
        ctx: Context,
        pkg: String,
        tgzFile: File,
        onLine: (String) -> Unit,
    ): Boolean {
        val node = NodeRuntime.nodeBin(ctx)
        val npm = NodeRuntime.npmCliJs(ctx)
        val short = AgentSlots.DSH
        clearMarkers(ctx)
        val version = AgentSlots.installUpdate(ctx, short, tgzFile, node, npm, ignoreScripts = true, onLine)
            ?: run {
                onLine("[update] $pkg 更新失败，当前版本仍可用")
                return false
            }
        val dshRoot = AgentSlots.read(ctx, short)?.let { s ->
            File(AgentSlots.slotPrefix(ctx, short, s.standby ?: return@let null), "lib/node_modules/@deepseek-ai/dsh")
        }
        injectPty(ctx, onLine, dshRoot)
        sharpWasm(ctx, node, npm, NodeRuntime.usrDir(ctx), onLine, dshRoot)
        runAndroidPatches(ctx, onLine, dshRoot)
        onLine("[update] dsh $version 已写入备用槽，下次启动生效 ✅")
        return true
    }

    /** Markers carry the packaged version, so match by prefix. */
    private fun clearMarkers(ctx: Context) {
        NodeRuntime.usrDir(ctx).listFiles()?.forEach { f ->
            if (f.name.startsWith(".cli-installed-dsh")) f.delete()
        }
    }

    /**
     * Re-applies every Android patch to the freshly installed tree: an update
     * replaces the package files, so without this the flock stub, the link()
     * fallback, the sandbox platform chain and the ripgrep shim are all lost.
     */
    private fun runAndroidPatches(ctx: Context, onLine: (String) -> Unit, dshRoot: File? = null) =
        Bootstrap.applyAndroidPatches(ctx, onLine, dshRoot)

    private fun injectPty(ctx: Context, onLine: (String) -> Unit, dshRoot: File? = null) {
        val src = File(NodeRuntime.pkgDir(ctx), "node-pty-prebuild/pty.node")
        val dir = File(
            dshRoot ?: File(NodeRuntime.usrDir(ctx), "lib/node_modules/@deepseek-ai/dsh"),
            "node_modules/node-pty/prebuilds/android-arm64",
        )
        if (src.exists()) {
            dir.mkdirs()
            src.copyTo(File(dir, "pty.node"), overwrite = true)
            onLine("[update] 已重新注入 node-pty 安卓预编译。")
        }
    }

    private fun sharpWasm(ctx: Context, node: File, npm: File, usr: File, onLine: (String) -> Unit, dshRoot: File? = null) {
        try {
            val sharpDir = File(dshRoot ?: File(usr, "lib/node_modules/@deepseek-ai/dsh"), "node_modules/sharp")
            val sharpPkg = File(sharpDir, "package.json")
            if (!sharpPkg.exists()) return
            val ver = org.json.JSONObject(sharpPkg.readText()).getString("version")
            if (File(sharpDir, "node_modules/@img/sharp-wasm32/package.json").exists()) return
            onLine("[update] 安装 @img/sharp-wasm32@$ver ...")
            val pb = ProcessBuilder(
                node.absolutePath, npm.absolutePath,
                "install", "--prefix", sharpDir.absolutePath,
                "--no-save", "--no-package-lock", "--ignore-scripts",
                "@img/sharp-wasm32@$ver",
            )
            pb.environment().putAll(NodeRuntime.environment(ctx))
            pb.directory(sharpDir)
            pb.redirectErrorStream(true)
            pb.start().waitFor()
        } catch (e: Exception) {
            onLine("[update] sharp-wasm32 安装失败（图片功能受限）：${e.message}")
        }
    }

    /** Manual rollback from Settings: swap back to the standby slot tree. */
    fun rollback(ctx: Context, pkg: String, onLine: (String) -> Unit): Boolean =
        AgentSlots.rollback(ctx, AgentSlots.DSH, onLine)
}
