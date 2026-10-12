@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.nicky.ember.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nicky.ember.EmberConnectionService
import dev.nicky.ember.MainViewModel
import dev.nicky.ember.R
import dev.nicky.ember.TemperatureFormat
import dev.nicky.ember.ble.EmberBleClient
import dev.nicky.ember.ble.EmberProtocol
import dev.nicky.ember.ble.LiquidState
import dev.nicky.ember.data.EmberAppState
import dev.nicky.ember.data.WritePhase
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Sheet { NONE, PICKER, SETTINGS, DETAILS, LED }

@Composable
fun EmberApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draftTarget by viewModel.draftTarget.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var sheet by rememberSaveable { mutableStateOf(Sheet.NONE) }
    var askedForPermission by rememberSaveable { mutableStateOf(false) }

    fun showMessage(message: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            val result = snackbar.showSnackbar(message, actionLabel, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    fun connect() {
        when (viewModel.connect()) {
            EmberBleClient.ConnectResult.NEEDS_CHOICE -> sheet = Sheet.PICKER
            else -> Unit
        }
    }

    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshEnvironment()
        if (viewModel.state.value.bluetoothEnabled) connect()
    }
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        viewModel.refreshEnvironment()
        if (result.values.all { it }) {
            if (viewModel.state.value.bluetoothEnabled) connect()
        } else {
            showMessage("Bluetooth access is needed to talk to your mug", "Settings") {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.setBackgroundMonitoring(true)
        } else {
            showMessage("Allow notifications to get ready alerts", "Settings") {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        }
    }

    val requestBluetooth = { bluetoothPermission.launch(EmberBleClient.REQUIRED_PERMISSIONS) }
    val turnOnBluetooth = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }

    LaunchedEffect(state.hasPermissions) {
        if (!state.hasPermissions && !askedForPermission) {
            askedForPermission = true
            requestBluetooth()
        }
    }
    LaunchedEffect(state.writeStatus.sequence) {
        if (state.writeStatus.phase == WritePhase.FAILED && state.writeStatus.message.isNotBlank()) {
            snackbar.showSnackbar(state.writeStatus.message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(state, onPickMug = { if (state.hasPermissions) sheet = Sheet.PICKER else requestBluetooth() }, onSettings = { sheet = Sheet.SETTINGS })
                StatusBanner(
                    state = state,
                    onGrantPermission = requestBluetooth,
                    onEnableBluetooth = turnOnBluetooth,
                    onSetUp = { sheet = Sheet.PICKER },
                    onConnect = ::connect,
                    onCancel = viewModel::disconnect,
                )
                DrinkCard(state)
                TargetCard(
                    state = state,
                    draftTarget = draftTarget,
                    onStep = viewModel::stepTarget,
                    onPreview = viewModel::previewTarget,
                    onCommit = viewModel::commitDraftTarget,
                    onPreset = { viewModel.setTarget(it, delayMs = 0L) },
                    onHeater = viewModel::setHeater,
                    onSavePreset = { index, celsius ->
                        viewModel.setPresets(state.presetsCelsius.toMutableList().also { it[index] = celsius })
                        showMessage("Preset ${index + 1} set to ${TemperatureFormat.format(celsius, state.displayUnit)}")
                    },
                    onEditPresets = { sheet = Sheet.SETTINGS },
                )
                AlertsCard(state) { enabled ->
                    if (!enabled) {
                        viewModel.setBackgroundMonitoring(false)
                    } else if (EmberConnectionService.canPostNotifications(context)) {
                        viewModel.setBackgroundMonitoring(true)
                    } else if (Build.VERSION.SDK_INT >= 33) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.setBackgroundMonitoring(true)
                        showMessage("Notifications are turned off for this app", "Settings") {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    when (sheet) {
        Sheet.PICKER -> MugPickerSheet(
            state = state,
            onStartScan = viewModel::startMugScan,
            onStopScan = viewModel::stopMugScan,
            onSelect = { viewModel.selectMug(it); sheet = Sheet.NONE },
            onForget = viewModel::forgetMug,
            onDismiss = { sheet = Sheet.NONE },
        )
        Sheet.SETTINGS -> SettingsSheet(
            state = state,
            viewModel = viewModel,
            onOpen = { sheet = it },
            onDismiss = { sheet = Sheet.NONE },
            onMessage = { showMessage(it) },
        )
        Sheet.DETAILS -> DetailsSheet(state, onRefresh = viewModel::refresh, onDismiss = { sheet = Sheet.NONE })
        Sheet.LED -> LedColorSheet(
            initialColor = state.snapshot.ledColor,
            onApply = { r, g, b -> viewModel.setLedColor(r, g, b); sheet = Sheet.NONE },
            onDismiss = { sheet = Sheet.NONE },
        )
        Sheet.NONE -> Unit
    }
}

@Composable
private fun Header(state: EmberAppState, onPickMug: () -> Unit, onSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClickLabel = "Choose mug", onClick = onPickMug)
                .padding(vertical = 6.dp, horizontal = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.mugDisplayName,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = colors.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dotColor by animateColorAsState(
                    when {
                        state.connected -> Color(0xFF3FAE6A)
                        state.busy -> colors.tertiary
                        else -> colors.outline
                    },
                    label = "dot",
                )
                StatusDot(dotColor)
                Spacer(Modifier.width(8.dp))
                Text(
                    connectionLine(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        FilledTonalIconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Mug settings")
        }
    }
}

private fun connectionLine(state: EmberAppState): String = when {
    state.connected -> if (state.monitoringActive) "Connected · alerts on" else "Connected"
    state.busy -> state.connectionDetail
    state.snapshotUpdatedAt > 0 -> "Offline · last seen ${relativeTime(state.snapshotUpdatedAt)}"
    else -> "Offline"
}

@Composable
private fun StatusBanner(
    state: EmberAppState,
    onGrantPermission: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onSetUp: () -> Unit,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
) {
    data class Banner(val text: String, val action: String?, val onAction: () -> Unit, val busy: Boolean = false, val warning: Boolean = false)

    val banner = when {
        !state.hasPermissions -> Banner("Allow Bluetooth access so the app can find and control your mug.", "Allow", onGrantPermission, warning = true)
        !state.bluetoothEnabled -> Banner("Bluetooth is off.", "Turn on", onEnableBluetooth, warning = true)
        state.selectedAddress == null && !state.busy -> Banner("Let's find your mug. Hold its button until the light pulses blue.", "Set up", onSetUp)
        state.busy -> Banner(state.connectionDetail, "Cancel", onCancel, busy = true)
        !state.connected -> Banner(state.connectionDetail.takeUnless { it == "Disconnected" || it == "Not connected" } ?: "Your mug isn't connected.", "Connect", onConnect)
        else -> null
    }
    AnimatedVisibility(visible = banner != null, enter = fadeIn(), exit = fadeOut()) {
        val current = banner ?: return@AnimatedVisibility
        val colors = MaterialTheme.colorScheme
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (current.warning) colors.errorContainer else colors.secondaryContainer,
            contentColor = if (current.warning) colors.onErrorContainer else colors.onSecondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
                when {
                    current.busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = colors.onSecondaryContainer)
                    current.warning -> Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                    else -> Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(current.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                current.action?.let { TextButton(onClick = current.onAction) { Text(it) } }
            }
        }
    }
}

@Composable
private fun DrinkCard(state: EmberAppState) {
    val snapshot = state.snapshot
    val unit = state.displayUnit
    val live = state.connected && state.snapshotFresh
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 16.dp)) {
            TemperatureDial(
                currentCelsius = snapshot.currentTemperatureC,
                targetCelsius = snapshot.targetTemperatureC,
                valueText = snapshot.currentTemperatureC?.let { TemperatureFormat.number(unit.fromCelsius(it), unit) } ?: "--",
                unitText = unit.symbol,
                caption = drinkCaption(state),
                live = live,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                snapshot.batteryPercent?.let { percent ->
                    InfoPill(
                        painterResource(if (snapshot.charging == true) R.drawable.ic_bolt else R.drawable.ic_battery),
                        if (snapshot.charging == true) "$percent% · charging" else "$percent%",
                        emphasized = percent <= 15 && snapshot.charging != true,
                    )
                }
                snapshot.liquidLevel?.let { level ->
                    InfoPill(painterResource(R.drawable.ic_water_drop), liquidLevelLabel(level))
                }
            }
            if (!live && state.snapshotUpdatedAt > 0) {
                Text(
                    "Last known values · ${relativeTime(state.snapshotUpdatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

private fun drinkCaption(state: EmberAppState): String {
    val snapshot = state.snapshot
    val target = snapshot.targetTemperatureC?.takeIf { it > 0f }?.let { TemperatureFormat.format(it, state.displayUnit, compact = true) }
    return when (snapshot.liquidState) {
        null -> if (state.connected) "Reading mug…" else "No reading yet"
        LiquidState.EMPTY -> "Mug is empty"
        LiquidState.FILLING -> "Filling…"
        LiquidState.AT_TARGET -> "Ready to drink"
        LiquidState.HEATING -> target?.let { "Heating to $it" } ?: "Heating"
        LiquidState.COOLING -> target?.let { "Cooling to $it" } ?: "Cooling"
        LiquidState.COLD_NO_CONTROL, LiquidState.WARM_NO_CONTROL -> "Heater off"
        LiquidState.STANDBY -> "Standby"
        else -> EmberProtocol.liquidStateLabel(snapshot.liquidState)
    }
}

private fun liquidLevelLabel(level: Int): String {
    val percent = (level * 100f / EmberProtocol.FULL_LIQUID_LEVEL).coerceIn(0f, 100f)
    return when {
        level <= 0 -> "Empty"
        percent >= 90f -> "Full"
        else -> "${(percent / 10f).roundToInt() * 10}% full"
    }
}

@Composable
private fun TargetCard(
    state: EmberAppState,
    draftTarget: Float?,
    onStep: (Int) -> Unit,
    onPreview: (Float) -> Unit,
    onCommit: () -> Unit,
    onPreset: (Float) -> Unit,
    onHeater: (Boolean) -> Unit,
    onSavePreset: (Int, Float) -> Unit,
    onEditPresets: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val unit = state.displayUnit
    val enabled = state.connected
    val mugTarget = state.snapshot.targetTemperatureC
    val heaterOn = draftTarget != null || state.heaterOn
    val shownTarget = draftTarget ?: mugTarget?.takeIf { it > 0f }
    val sliderCelsius = shownTarget ?: state.presetsCelsius.getOrElse(1) { 57.5f }
    val haptics = LocalHapticFeedback.current

    Card(
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("Target")
                    WriteStatusLine(state)
                }
                Icon(painterResource(R.drawable.ic_power), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = heaterOn,
                    onCheckedChange = onHeater,
                    enabled = enabled,
                    modifier = Modifier.semantics { role = Role.Switch },
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) {
                FilledTonalIconButton(onClick = { onStep(-1) }, enabled = enabled, modifier = Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.ic_remove), contentDescription = "Lower target")
                }
                AnimatedContent(
                    targetState = shownTarget?.let { TemperatureFormat.format(it, unit) } ?: "Off",
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "target",
                ) { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.displayMedium,
                        color = if (shownTarget == null) colors.onSurfaceVariant else colors.onSurface,
                    )
                }
                FilledTonalIconButton(onClick = { onStep(1) }, enabled = enabled, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Raise target")
                }
            }

            val range = unit.range
            Slider(
                value = unit.fromCelsius(sliderCelsius).coerceIn(range.start, range.endInclusive),
                onValueChange = { onPreview(unit.toCelsius(it)) },
                onValueChangeFinished = onCommit,
                valueRange = range,
                steps = ((range.endInclusive - range.start) / unit.step).roundToInt() - 1,
                enabled = enabled,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(TemperatureFormat.format(unit.toCelsius(range.start), unit, compact = true), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text(TemperatureFormat.format(unit.toCelsius(range.endInclusive), unit, compact = true), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Presets", Modifier.weight(1f))
                IconButton(onClick = onEditPresets) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit presets", tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                state.presetsCelsius.forEachIndexed { index, preset ->
                    val selected = heaterOn && shownTarget != null && abs(shownTarget - preset) < 0.05f
                    PresetButton(
                        label = TemperatureFormat.format(preset, unit, compact = true),
                        selected = selected,
                        enabled = enabled,
                        onClick = { onPreset(preset) },
                        onLongClick = {
                            val target = shownTarget ?: return@PresetButton
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSavePreset(index, target)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                "Long-press a preset to save the current target to it.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun WriteStatusLine(state: EmberAppState) {
    val text = when (state.writeStatus.phase) {
        WritePhase.WRITING -> "Updating mug…"
        WritePhase.CONFIRMED -> "Saved to mug"
        else -> ""
    }
    AnimatedContent(targetState = text, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "writeStatus") {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun PresetButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (selected) colors.primary else colors.surfaceContainerHighest, label = "preset")
    val content by animateColorAsState(if (selected) colors.onPrimary else colors.onSurface, label = "presetText")
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = content,
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Save current target"),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.alpha(if (enabled) 1f else 0.5f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun AlertsCard(state: EmberAppState, onToggle: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val supporting = when {
        !state.backgroundMonitoring -> "Get notified when your drink reaches its target, even with the app closed."
        state.monitoringActive -> "On · keeping your mug connected in the background."
        state.snapshot.liquidState == LiquidState.EMPTY -> "On · paused while the mug is empty. Open the app after refilling."
        state.selectedAddress == null -> "On · choose a mug to start."
        else -> "On · resumes when the app can reach your mug."
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.padding(10.dp).size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Ready alerts", style = MaterialTheme.typography.titleMedium)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = state.backgroundMonitoring, onCheckedChange = onToggle)
        }
    }
}

@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(52.dp)) { Text(text) }
}

internal fun relativeTime(timestamp: Long): String {
    val elapsed = System.currentTimeMillis() - timestamp
    return when {
        elapsed < 60_000L -> "just now"
        elapsed < 3_600_000L -> "${elapsed / 60_000L} min ago"
        elapsed < 86_400_000L -> "${elapsed / 3_600_000L} h ago"
        else -> "${elapsed / 86_400_000L} d ago"
    }
}
