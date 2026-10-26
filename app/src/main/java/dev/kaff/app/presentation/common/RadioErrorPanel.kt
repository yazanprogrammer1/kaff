package dev.kaff.app.presentation.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.kaff.app.R
import dev.kaff.app.core.util.KaffPermissions
import dev.kaff.app.domain.model.TransferError
import dev.kaff.app.ui.components.KaffIcons
import dev.kaff.app.ui.components.PillTone
import dev.kaff.app.ui.components.PrimaryButton
import dev.kaff.app.ui.components.SecondaryButton
import dev.kaff.app.ui.components.StatusPill

/**
 * Explains why a transfer failed and, when the user can fix it on the spot (permission, location,
 * Bluetooth), offers a one-tap fix. Coming back from the fix retries automatically.
 */
@Composable
fun RadioErrorPanel(error: TransferError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val retry by rememberUpdatedState(onRetry)

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (KaffPermissions.missingNearby(context).isEmpty()) {
            retry()
        } else {
            val activity = context.findActivity()
            val blocked = activity != null && KaffPermissions.missingNearby(context).none {
                ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            }
            if (blocked) context.openAppSettings()
        }
    }

    // Fixed in system settings? Pick up where we left off without another tap.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val fixed = when (error) {
            TransferError.NearbyPermission -> KaffPermissions.missingNearby(context).isEmpty()
            TransferError.LocationOff -> context.locationEnabled()
            else -> false
        }
        if (fixed) retry()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusPill(stringResource(error.message()), tone = PillTone.Danger, icon = KaffIcons.Alert)
        when (error) {
            TransferError.NearbyPermission -> PrimaryButton(
                stringResource(R.string.action_grant_permission),
                onClick = { permissions.launch((KaffPermissions.nearby + KaffPermissions.notifications).toTypedArray()) },
            )
            TransferError.LocationOff -> PrimaryButton(
                stringResource(R.string.action_open_location),
                onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            )
            TransferError.BluetoothOff -> PrimaryButton(
                stringResource(R.string.action_open_bluetooth),
                onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            )
            else -> Unit
        }
        val fixable = error == TransferError.NearbyPermission || error == TransferError.LocationOff || error == TransferError.BluetoothOff
        if (fixable) {
            SecondaryButton(stringResource(R.string.action_retry), onRetry, height = 52.dp)
        } else {
            PrimaryButton(stringResource(R.string.action_retry), onRetry, trailingIcon = KaffIcons.Refresh)
        }
    }
}

private fun Context.locationEnabled(): Boolean =
    getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
