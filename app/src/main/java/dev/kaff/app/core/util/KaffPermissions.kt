package dev.kaff.app.core.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permissions Kaff uses, per API level. Nothing else is ever requested. */
object KaffPermissions {

    val camera: List<String> = listOf(Manifest.permission.CAMERA)

    val nearby: List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** True when Bluetooth discovery still needs the location permission (Android 12L and older). */
    val needsLocation: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    val notifications: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    /** Needed before any transfer screen can work. */
    val required: List<String> = camera + nearby

    /** Requested together with [required], but the app works without it. */
    val all: List<String> = required + notifications

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missingRequired(context: Context): List<String> = required.filterNot { granted(context, it) }

    /** Nearby permissions only: what Standby mode needs to advertise without the camera. */
    fun missingNearby(context: Context): List<String> = nearby.filterNot { granted(context, it) }
}
