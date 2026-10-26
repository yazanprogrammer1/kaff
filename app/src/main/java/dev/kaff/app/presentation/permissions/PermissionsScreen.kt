package dev.kaff.app.presentation.permissions

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.kaff.app.R
import dev.kaff.app.core.util.KaffPermissions
import dev.kaff.app.presentation.common.findActivity
import dev.kaff.app.ui.components.HeroText
import dev.kaff.app.ui.components.IconBadge
import dev.kaff.app.ui.components.KaffBackground
import dev.kaff.app.ui.components.KaffCard
import dev.kaff.app.ui.components.KaffIcons
import dev.kaff.app.ui.components.KaffTopBar
import dev.kaff.app.ui.components.PillTone
import dev.kaff.app.ui.components.PrimaryButton
import dev.kaff.app.ui.components.SecondaryButton
import dev.kaff.app.ui.components.StatusPill
import dev.kaff.app.ui.theme.KaffTheme

data class PermissionItem(val icon: ImageVector, val title: Int, val body: Int, val granted: Boolean)

/**
 * Rationale first, then one system request. If a required permission is permanently denied the
 * screen offers the app settings instead of asking again.
 */
@Composable
fun PermissionsRoute(onGranted: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var blocked by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val missing = remember(refresh) { KaffPermissions.missingRequired(context) }
    LaunchedEffect(missing) { if (missing.isEmpty()) onGranted() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        refresh++
        val activity = context.findActivity()
        blocked = results.any { (permission, granted) ->
            !granted && permission in KaffPermissions.required &&
                activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
    }

    val items = remember(refresh) {
        buildList {
            add(PermissionItem(KaffIcons.Camera, R.string.perm_camera_title, R.string.perm_camera_body, KaffPermissions.camera.all { KaffPermissions.granted(context, it) }))
            if (KaffPermissions.radio.isNotEmpty()) {
                add(PermissionItem(KaffIcons.Wifi, R.string.perm_nearby_title, R.string.perm_nearby_body, KaffPermissions.radio.all { KaffPermissions.granted(context, it) }))
            }
            add(PermissionItem(KaffIcons.Lock, R.string.perm_location_title, R.string.perm_location_body, KaffPermissions.locationGranted(context)))
            if (KaffPermissions.notifications.isNotEmpty()) {
                add(PermissionItem(KaffIcons.Bell, R.string.perm_notifications_title, R.string.perm_notifications_body, KaffPermissions.notifications.all { KaffPermissions.granted(context, it) }))
            }
        }
    }

    PermissionsScreen(
        items = items,
        blocked = blocked,
        onGrant = { launcher.launch(KaffPermissions.all.filterNot { KaffPermissions.granted(context, it) }.toTypedArray()) },
        onOpenSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        onBack = onBack,
    )
}

@Composable
fun PermissionsScreen(
    items: List<PermissionItem>,
    blocked: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val c = KaffTheme.colors
    KaffBackground(glowCenterY = 0.15f) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            KaffTopBar(KaffIcons.ArrowBack, stringResource(R.string.action_back), onBack)
            IconBadge(KaffIcons.ShieldCheck, size = 64.dp)
            HeroText(stringResource(R.string.perm_title), stringResource(R.string.perm_body))
            items.forEach { item ->
                KaffCard(highlighted = item.granted, contentPadding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge(item.icon)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(item.title), style = MaterialTheme.typography.titleSmall, color = c.text)
                            Text(stringResource(item.body), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                        }
                        if (item.granted) Icon(KaffIcons.Check, contentDescription = null, tint = c.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (blocked) {
                StatusPill(stringResource(R.string.perm_denied), tone = PillTone.Danger, icon = KaffIcons.Alert)
                PrimaryButton(stringResource(R.string.perm_open_settings), onOpenSettings)
            } else {
                PrimaryButton(stringResource(R.string.perm_grant), onGrant)
            }
            SecondaryButton(stringResource(R.string.action_back), onBack, accent = false)
        }
    }
}

@Preview(name = "Permissions · RTL", locale = "ar", device = "spec:width=390dp,height=844dp")
@Composable
private fun PermissionsPreview() {
    KaffTheme(darkTheme = true) {
        PermissionsScreen(
            items = listOf(
                PermissionItem(KaffIcons.Camera, R.string.perm_camera_title, R.string.perm_camera_body, true),
                PermissionItem(KaffIcons.Wifi, R.string.perm_nearby_title, R.string.perm_nearby_body, false),
                PermissionItem(KaffIcons.Bell, R.string.perm_notifications_title, R.string.perm_notifications_body, false),
            ),
            blocked = false,
            onGrant = {},
            onOpenSettings = {},
            onBack = {},
        )
    }
}
