package com.auraguard.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auraguard.app.alert.AlertBannerData
import com.auraguard.app.core.AlertLevel
import com.auraguard.app.ui.theme.OpsBackground
import com.auraguard.app.ui.theme.OpsCritical
import com.auraguard.app.ui.theme.OpsWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Large visual warning shown over the live feed on BREACH / significant-change alerts. */
@Composable
fun AlertBannerView(banner: AlertBannerData?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // Remember the last non-null banner so the exit (fade-out) animation still has
    // content to render after `banner` itself has already become null.
    var displayed by remember { mutableStateOf(banner) }
    if (banner != null) displayed = banner

    AnimatedVisibility(
        visible = banner != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 }),
        modifier = modifier
    ) {
        val data = displayed ?: return@AnimatedVisibility
        val color = if (data.level == AlertLevel.CRITICAL) OpsCritical else OpsWarning
        val timeFormat = remember(data.timestampMillis) {
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(data.timestampMillis))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(OpsBackground.copy(alpha = 0.92f))
                .clickable { onDismiss() }
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = color)
                Text(
                    text = "  ${data.title}",
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    letterSpacing = 1.sp
                )
            }
            Text(data.subtitle, color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Text("TIME: $timeFormat", color = color.copy(alpha = 0.85f), fontSize = 12.sp)
                if (data.zoneName != null) {
                    Text("   ZONE: ${data.zoneName}", color = color.copy(alpha = 0.85f), fontSize = 12.sp)
                }
            }
        }
    }
}
