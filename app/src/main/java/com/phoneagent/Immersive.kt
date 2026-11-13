package com.phoneagent

import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Edge-to-edge 系统栏：窗口铺到状态栏与手势导航条下面，两条栏都设为透明，
 * 并关掉系统在手势条后面加的对比度蒙层，让“小白条”直接浮在内容上。
 *
 * 本 App 只有深色主题，所以图标固定为浅色。
 */
object Immersive {
    /**
     * true  = 内容一直铺到栏下方（真沉浸，页面顶部/底部需要有 safe-area 意识）
     * false = 栏透明、背景统一，但内容留在安全区内（默认，风险最低）
     */
    var fullBleed = false
}

/** 在每个 Activity 的 onCreate 里、super.onCreate 之后调用。 */
fun ComponentActivity.enableImmersiveSystemBars() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // 去掉手势条/三键导航后面那条半透明蒙层，这是“小白条悬浮”的关键
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }
    if (!Immersive.fullBleed) padContentBySystemBars()
}

/**
 * 给窗口内容（含 Compose 容器、WebView、终端视图）统一加上系统栏 + 刘海的安全区内缩。
 * 背景色仍由主题/布局铺满整屏，所以栏区域看到的是 App 自己的背景，而不是系统给的一条色带。
 */
private fun ComponentActivity.padContentBySystemBars() {
    val content = findViewById<View>(android.R.id.content) ?: return
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(content)
}
