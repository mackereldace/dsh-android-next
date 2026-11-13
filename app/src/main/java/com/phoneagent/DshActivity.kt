package com.phoneagent

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlin.concurrent.thread

/** DeepSeek Harness launcher icon target: foreground service + fullscreen WebView. */
class DshActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var loading: View
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveSystemBars(fullBleed = true)   // Web 界面真沉浸：内容铺到状态栏/手势条下面
        setContentView(R.layout.activity_dsh)
        webView = findViewById(R.id.webview)
        loading = findViewById(R.id.loading)
        errorBox = findViewById(R.id.error)
        errorText = findViewById(R.id.error_text)
        findViewById<Button>(R.id.retry).setOnClickListener { connect() }

        val webScale = Prefs.webUiScale(this)
        WebUi.apply(webView, webScale)
        AttachmentBridge.attach(this, webView)
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                if (req?.isForMainFrame == true) {
                    errorText.text = err?.description ?: getString(R.string.dsh_error)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 真沉浸（内容铺到状态栏下）必须由页面自己让出安全区，否则 dsh 顶栏
                // 会被系统时间/图标压住。给 html 加 padding-top（而不是给 WebView 加），
                // 这样那条区域显示的是页面自己的背景色，不会出现一条异色带。
                view?.evaluateJavascript(WebUi.safeAreaJs(statusBarHeightPx()), null)
                view?.evaluateJavascript(WebUi.patchJs(webScale) +
                    """
                    (function(){
                      var s=document.createElement('style');
                      s.textContent='html,body{margin:0;padding:0;width:100%;height:100%;max-width:none!important;min-width:0!important;}'+
                        '#root,.app,main,div[class*="layout"],div[class*="container"]{max-width:none!important;width:100%!important;}';
                      document.head.appendChild(s);
                    })();
                    """.trimIndent(),
                    null,
                )
            }
        }
        connect()
    }

    private fun connect() {
        loading.visibility = View.VISIBLE
        errorBox.visibility = View.GONE
        webView.visibility = View.GONE

        startForegroundService(Intent(this, DshService::class.java))
        EngineState.markRunning(this, "dsh")
        WatchdogTargets.watchDsh(this)

        thread(name = "dsh-connect") {
            val deadline = System.currentTimeMillis() + 160_000
            while (System.currentTimeMillis() < deadline) {
                if (DshState.serverReady) break
                if (DshState.lastError != null) {
                    runOnUiThread { showState() }
                    return@thread
                }
                Thread.sleep(500)
            }
            runOnUiThread { showState() }
        }
    }

    private fun showState() {
        loading.visibility = View.GONE
        if (DshState.serverReady) {
            errorBox.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.loadUrl(DshState.startUrl ?: "http://127.0.0.1:3080")
        } else {
            errorText.text = DshState.lastError ?: getString(R.string.dsh_error)
            errorBox.visibility = View.VISIBLE
            webView.visibility = View.GONE
            // A first run installs dsh for minutes; the service may go ready
            // after this screen was drawn. Poll instead of making the user tap
            // 重试 into a stale error.
            watchForLateReady()
        }
    }

    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private var watching = false

    private fun watchForLateReady() {
        if (watching) return
        watching = true
        thread(name = "dsh-wait-ready") {
            val deadline = System.currentTimeMillis() + 10 * 60 * 1000
            while (System.currentTimeMillis() < deadline) {
                if (DshState.serverReady) {
                    runOnUiThread { watching = false; showState() }
                    return@thread
                }
                Thread.sleep(2000)
            }
            watching = false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (AttachmentBridge.onResult(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }
}
