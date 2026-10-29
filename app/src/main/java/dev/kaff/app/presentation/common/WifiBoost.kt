package dev.kaff.app.presentation.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.kaff.app.domain.model.LinkQuality
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kaff.app.R
import dev.kaff.app.ui.components.KaffIcons
import dev.kaff.app.ui.theme.KaffShapes
import dev.kaff.app.ui.theme.KaffTheme

/** Why the transfer cannot reach full speed, if it cannot. */
enum class SpeedBlocker { WifiOffHere, WifiOffThere, StillBluetooth }

/**
 * The one thing that decides speed between two phones: both Wi-Fi radios on (no internet needed).
 * Shown above the send and receive screens whenever the fast path is not available.
 */
@Composable
fun WifiBoostCard(blocker: SpeedBlocker?, peerName: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AnimatedVisibility(
        blocker != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val c = KaffTheme.colors
        val shown = blocker ?: return@AnimatedVisibility
        Row(
            Modifier
                .fillMaxWidth()
                .clip(KaffShapes.card)
                .background(c.surface.copy(alpha = 0.92f))
                .border(1.dp, c.primary.copy(alpha = 0.45f), KaffShapes.card)
                .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(KaffShapes.medium)
                    .background(c.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(KaffIcons.Wifi, contentDescription = null, tint = c.primary, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when (shown) {
                        SpeedBlocker.WifiOffHere -> stringResource(R.string.boost_wifi_here)
                        SpeedBlocker.WifiOffThere -> stringResource(R.string.boost_wifi_there, peerName ?: stringResource(R.string.boost_other_phone))
                        SpeedBlocker.StillBluetooth -> stringResource(R.string.boost_bluetooth)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = c.text,
                )
                Text(
                    stringResource(if (shown == SpeedBlocker.StillBluetooth) R.string.boost_bluetooth_body else R.string.boost_wifi_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
            if (shown == SpeedBlocker.WifiOffHere) {
                TextButton(onClick = { openWifiPanel(context) }) {
                    Text(stringResource(R.string.boost_turn_on), color = c.primarySoft, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * What, if anything, keeps this session off full speed. "Still Bluetooth" only counts a few seconds
 * into a transfer: the upgrade to Wi-Fi normally lands right after pairing.
 */
@Composable
fun speedBlocker(wifiHere: Boolean, wifiThere: Boolean?, link: LinkQuality, transferStartedAtMs: Long?, active: Boolean): SpeedBlocker? {
    if (!active) return null
    if (!wifiHere) return SpeedBlocker.WifiOffHere
    if (wifiThere == false) return SpeedBlocker.WifiOffThere
    if (transferStartedAtMs == null || link >= LinkQuality.High) return null
    var late by remember(transferStartedAtMs) { mutableStateOf(false) }
    LaunchedEffect(transferStartedAtMs) {
        delay(BLUETOOTH_GRACE_MS)
        late = true
    }
    return if (late) SpeedBlocker.StillBluetooth else null
}

private const val BLUETOOTH_GRACE_MS = 6_000L

/** The quick Wi-Fi panel on Android 10+ (apps may not switch Wi-Fi themselves), settings before. */
fun openWifiPanel(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
