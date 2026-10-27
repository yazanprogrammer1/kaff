package dev.kaff.app.core.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permissions Kaff uses, per API level. Nothing else is ever requested. */
object KaffPermissions {

    val camera: List<String> = listOf(Manifest.permission.CAMERA)

    /** Bluetooth and Wi-Fi permissions for Nearby Connections. */
    val radio: List<String> = when {
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
        )
        else -> emptyList()
    }

    /**
     * Google Play services Nearby rejects discovery without location (status 8034), on every
     * Android version. Approximate location is enough; precise is only offered.
     */
    val location: List<String> = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

    /** Everything Nearby needs to discover and advertise. */
    val nearby: List<String> = radio + location

    /** Read access for the in-app picker; on Android 14+ "selected photos only" is also accepted. */
    val media: List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val notifications: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    /** Needed before any transfer screen can work (precise location is optional). */
    val required: List<String> = camera + radio + Manifest.permission.ACCESS_COARSE_LOCATION

    /** Requested together, so the system shows one flow. */
    val all: List<String> = camera + nearby + notifications

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun locationGranted(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun missingRequired(context: Context): List<String> = required.filterNot { granted(context, it) }

    /** What Standby mode needs to advertise without the camera. */
    fun missingNearby(context: Context): List<String> =
        (radio + Manifest.permission.ACCESS_COARSE_LOCATION).filterNot { granted(context, it) }
}
