package com.phoneagent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Single-agent launcher. The multi-agent terminal picker (zcode / codex /
 * claude / opencode) is gone by design: this build only serves dsh, so the
 * screen keeps one primary entry plus the three tool pages.
 *
 * The shell itself is not shipped any more either — dsh's `bash` tool runs on
 * the embedded runtime through the bwrap/landlock shim, independent of any
 * terminal UI, and the user has their own terminal app for manual work.
 */
class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveSystemBars(fullBleed = false)
        setContent {
            AppTheme { Home() }
        }
    }

    @Composable
    private fun Home() {
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            TopAppBar(title = getString(R.string.app_name))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            startActivity(Intent(this@LauncherActivity, DshActivity::class.java))
                        },
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Row {
                                M3Text(
                                    "DeepSeek Harness",
                                    color = MiuixTheme.colorScheme.onSurface,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            M3Text(
                                "内置 Node 运行时 + dsh Web UI（127.0.0.1:${AgentRegistry.DSH_PORT}）",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
                item { GroupTitle("工具") }
                item {
                    HomeCard("用量统计", "会话、token 与回收站") {
                        startActivity(Intent(this@LauncherActivity, UsageActivity::class.java))
                    }
                }
                item {
                    HomeCard("设置", "API Key / 镜像 / 代理 / 工作区 / dsh 版本") {
                        startActivity(Intent(this@LauncherActivity, SettingsActivity::class.java))
                    }
                }
                item {
                    HomeCard("诊断", "运行时、槽位与补丁状态") {
                        startActivity(Intent(this@LauncherActivity, DiagActivity::class.java))
                    }
                }
                item {
                    Spacer(Modifier.height(20.dp))
                    M3Text(
                        "DshAndroid Next v${BuildConfig.VERSION_NAME}",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }

    @Composable
    private fun HomeCard(title: String, desc: String, onClick: () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
            Column(Modifier.padding(16.dp)) {
                M3Text(
                    title,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                M3Text(desc, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
            }
        }
    }
}
