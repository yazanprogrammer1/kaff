package dev.kaff.app.presentation.settings

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kaff.app.R
import dev.kaff.app.core.util.KaffPermissions
import dev.kaff.app.domain.model.AppSettings
import dev.kaff.app.domain.model.ThemeMode
import dev.kaff.app.service.ReceiveTileService
import dev.kaff.app.ui.components.IconBadge
import dev.kaff.app.ui.components.KaffBackground
import dev.kaff.app.ui.components.KaffCard
import dev.kaff.app.ui.components.KaffIcons
import dev.kaff.app.ui.components.KaffTopBar
import dev.kaff.app.ui.components.SecondaryButton
import dev.kaff.app.ui.theme.KaffShapes
import dev.kaff.app.ui.theme.KaffTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

data class SettingsActions(
    val onBack: () -> Unit = {},
    val onLab: () -> Unit = {},
    val onTheme: (ThemeMode) -> Unit = {},
    val onDeviceName: (String) -> Unit = {},
    val onTimeout: (Int) -> Unit = {},
    val onHaptics: (Boolean) -> Unit = {},
    val onSound: (Boolean) -> Unit = {},
    val onSensitivity: (Float) -> Unit = {},
    val onStrictPalm: (Boolean) -> Unit = {},
    val onShake: (Boolean) -> Unit = {},
    val onStandby: (Boolean) -> Unit = {},
    val onBubble: (Boolean) -> Unit = {},
    val onAddTile: (() -> Unit)? = null,
)

@Composable
fun SettingsRoute(onBack: () -> Unit, onLab: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingBubble by remember { mutableStateOf(false) }
    var pendingStandby by remember { mutableStateOf(false) }

    // Coming back from the overlay permission page: finish turning the bubble on if it was granted.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (pendingBubble && Settings.canDrawOverlays(context)) viewModel.setBubble(true)
        pendingBubble = false
    }

    val standbyPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingStandby && KaffPermissions.missingNearby(context).isEmpty()) viewModel.setStandby(true)
        pendingStandby = false
    }

    val tileLabel = stringResource(R.string.tile_receive)
    val current = settings ?: return
    SettingsScreen(
        settings = current,
        defaultDeviceName = viewModel.defaultDeviceName,
        actions = SettingsActions(
            onBack = onBack,
            onLab = onLab,
            onTheme = viewModel::setTheme,
            onDeviceName = viewModel::setDeviceName,
            onTimeout = viewModel::setCameraTimeout,
            onHaptics = viewModel::setHaptics,
            onSound = viewModel::setSound,
            onSensitivity = viewModel::setSensitivity,
            onStrictPalm = viewModel::setStrictPalm,
            onShake = viewModel::setShake,
            onStandby = { on ->
                val missing = KaffPermissions.missingNearby(context)
                if (on && missing.isNotEmpty()) {
                    pendingStandby = true
                    standbyPermissions.launch((missing + KaffPermissions.notifications).toTypedArray())
                } else {
                    viewModel.setStandby(on)
                }
            },
            onBubble = { on ->
                if (!viewModel.setBubble(on)) {
                    pendingBubble = true
                    Toast.makeText(context, R.string.settings_bubble_permission, Toast.LENGTH_LONG).show()
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            onAddTile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                {
                    context.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
                        ComponentName(context, ReceiveTileService::class.java),
                        tileLabel,
                        Icon.createWithResource(context, R.drawable.ic_stat_kaff),
                        context.mainExecutor,
                    ) { }
                }
            } else {
                null
            },
        ),
    )
}

@Composable
fun SettingsScreen(settings: AppSettings, defaultDeviceName: String, actions: SettingsActions) {
    val c = KaffTheme.colors
    KaffBackground(glowCenterY = 0.05f) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            KaffTopBar(KaffIcons.ArrowBack, stringResource(R.string.action_back), actions.onBack, title = stringResource(R.string.settings_title))

            Section(stringResource(R.string.settings_section_wake))
            KaffCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                ToggleRow(KaffIcons.Shake, R.string.settings_shake, R.string.settings_shake_desc, settings.shakeToWake, actions.onShake)
                ToggleRow(KaffIcons.Wifi, R.string.settings_standby, R.string.settings_standby_desc, settings.standbyMode, actions.onStandby)
                ToggleRow(KaffIcons.Bubble, R.string.settings_bubble, R.string.settings_bubble_desc, settings.floatingBubble, actions.onBubble)
                InfoRow(KaffIcons.Tile, R.string.settings_tile, R.string.settings_tile_desc) {
                    actions.onAddTile?.let { add ->
                        TextButton(onClick = add) { Text(stringResource(R.string.settings_tile_add), color = c.primarySoft) }
                    }
                }
            }

            Section(stringResource(R.string.settings_section_device))
            KaffCard {
                ThemeSelector(settings.themeMode, actions.onTheme)
                DeviceNameField(settings.deviceName, defaultDeviceName, actions.onDeviceName)
                LabeledSlider(
                    label = stringResource(R.string.settings_timeout),
                    valueText = stringResource(R.string.settings_timeout_value, settings.cameraTimeoutSec),
                    value = settings.cameraTimeoutSec.toFloat(),
                    range = AppSettings.CAMERA_TIMEOUT_RANGE.first.toFloat()..AppSettings.CAMERA_TIMEOUT_RANGE.last.toFloat(),
                    steps = (AppSettings.CAMERA_TIMEOUT_RANGE.last - AppSettings.CAMERA_TIMEOUT_RANGE.first) / 5 - 1,
                    onChange = { actions.onTimeout(((it / 5f).roundToInt() * 5)) },
                )
            }

            Section(stringResource(R.string.settings_section_feedback))
            KaffCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                ToggleRow(KaffIcons.Hand, R.string.settings_haptics, null, settings.hapticsEnabled, actions.onHaptics)
                ToggleRow(KaffIcons.Bell, R.string.settings_sound, null, settings.soundEnabled, actions.onSound)
            }

            Section(stringResource(R.string.settings_section_gesture))
            KaffCard {
                LabeledSlider(
                    label = stringResource(R.string.settings_sensitivity),
                    valueText = null,
                    value = settings.gestureSensitivity,
                    range = 0f..1f,
                    steps = 0,
                    onChange = actions.onSensitivity,
                    startLabel = stringResource(R.string.settings_sensitivity_strict),
                    endLabel = stringResource(R.string.settings_sensitivity_loose),
                )
                ToggleRow(KaffIcons.ShieldCheck, R.string.settings_strict_palm, null, settings.strictPalmCheck, actions.onStrictPalm, padded = false)
                SecondaryButton(stringResource(R.string.settings_open_lab), actions.onLab, height = 48.dp)
            }

            Section(stringResource(R.string.settings_section_privacy))
            KaffCard {
                Text(stringResource(R.string.settings_privacy_body), style = MaterialTheme.typography.bodyMedium, color = c.textSubtle)
                Text(stringResource(R.string.settings_limits_body), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = KaffTheme.colors.primarySoft,
        modifier = Modifier
            .padding(top = 8.dp)
            .semantics { heading() },
    )
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: Int,
    body: Int?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    padded: Boolean = true,
) {
    val c = KaffTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = if (padded) 16.dp else 0.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(icon, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = c.text)
            if (body != null) Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onPrimary,
                checkedTrackColor = c.primary,
                uncheckedTrackColor = c.surface2,
                uncheckedThumbColor = c.textMuted,
                uncheckedBorderColor = c.border,
            ),
        )
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: Int, body: Int, trailing: @Composable () -> Unit) {
    val c = KaffTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(icon, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = c.text)
            Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        trailing()
    }
}

@Composable
private fun ThemeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val c = KaffTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleSmall, color = c.text)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(KaffShapes.medium)
                .background(c.surface2)
                .padding(4.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                val active = mode == selected
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(KaffShapes.small)
                        .background(if (active) c.primary else c.surface2)
                        .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(mode) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(
                            when (mode) {
                                ThemeMode.Dark -> R.string.settings_theme_dark
                                ThemeMode.Light -> R.string.settings_theme_light
                                ThemeMode.System -> R.string.settings_theme_system
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) c.onPrimary else c.textSubtle,
                    )
                }
            }
        }
    }
}

/** Saves the name shortly after the user stops typing, not on every keystroke. */
@Composable
private fun DeviceNameField(saved: String, defaultName: String, onSave: (String) -> Unit) {
    val c = KaffTheme.colors
    var text by remember { mutableStateOf(saved) }
    LaunchedEffect(text) {
        if (text != saved) {
            delay(600)
            onSave(text)
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(32) },
        label = { Text(stringResource(R.string.settings_device_name)) },
        supportingText = { Text(stringResource(R.string.settings_device_name_hint, defaultName)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        shape = KaffShapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.primary,
            unfocusedBorderColor = c.border,
            focusedLabelColor = c.primarySoft,
            cursorColor = c.primary,
            focusedTextColor = c.text,
            unfocusedTextColor = c.text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String?,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    startLabel: String? = null,
    endLabel: String? = null,
) {
    val c = KaffTheme.colors
    // Local state keeps the thumb smooth; the setting is written when the drag ends.
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
            if (valueText != null) Text(valueText, style = MaterialTheme.typography.labelMedium, color = c.primarySoft)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = c.primary, activeTrackColor = c.primary, inactiveTrackColor = c.surface2),
        )
        if (startLabel != null && endLabel != null) {
            Row {
                Text(startLabel, style = MaterialTheme.typography.labelSmall, color = c.textMuted, modifier = Modifier.weight(1f))
                Text(endLabel, style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            }
        }
    }
}

@Preview(name = "Settings · RTL", locale = "ar", device = "spec:width=390dp,height=1400dp")
@Preview(name = "Settings · EN", locale = "en", device = "spec:width=390dp,height=1400dp")
@Composable
private fun SettingsPreview() {
    KaffTheme(darkTheme = true) { SettingsScreen(AppSettings(standbyMode = true), "Pixel 8", SettingsActions(onAddTile = {})) }
}
