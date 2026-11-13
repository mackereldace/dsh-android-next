package com.phoneagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Small section header used across Miuix pages. */
@Composable
fun GroupTitle(text: String) {
    M3Text(
        text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

@Composable
fun FieldLabel(text: String) {
    M3Text(text, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
}

/**
 * A full-width action button with breathing room, used inside Miuix cards.
 * The vertical padding here is what keeps stacked buttons from touching.
 */
@Composable
fun UpdateRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        top.yukonga.miuix.kmp.basic.Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            M3Text(
                label,
                color = if (enabled) MiuixTheme.colorScheme.onPrimary
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Shared app theme: Miuix with system dark/light detection. */
@androidx.compose.runtime.Composable
fun AppTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = if (dark) top.yukonga.miuix.kmp.theme.darkColorScheme()
    else top.yukonga.miuix.kmp.theme.lightColorScheme()
    top.yukonga.miuix.kmp.theme.MiuixTheme(colors = colors, content = content)
}
