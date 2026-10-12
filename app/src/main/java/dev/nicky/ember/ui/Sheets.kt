@file:OptIn(ExperimentalMaterial3Api::class)

package dev.nicky.ember.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.nicky.ember.MainViewModel
import dev.nicky.ember.R
import dev.nicky.ember.TemperatureFormat
import dev.nicky.ember.ble.EmberProtocol
import dev.nicky.ember.data.DisplayUnit
import dev.nicky.ember.data.EmberAppState
import dev.nicky.ember.data.MugChoice
import dev.nicky.ember.data.ScanState
import java.util.Locale

@Composable
private fun EmberSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            content = content,
        )
    }
}

@Composable
private fun SheetTitle(title: String, subtitle: String? = null) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    subtitle?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
    Spacer(Modifier.height(16.dp))
}

// region Mug picker

@Composable
fun MugPickerSheet(
    state: EmberAppState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelect: (String) -> Unit,
    onForget: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DisposableEffect(Unit) {
        onStartScan()
        onDispose { onStopScan() }
    }
    val scanning = state.scanState == ScanState.SCANNING
    EmberSheet(onDismiss) {
        SheetTitle("Your mugs", "Pick the mug this phone should control.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    scanning -> "Looking for nearby mugs…"
                    state.scanDetail.isNotBlank() -> state.scanDetail
                    else -> "Nearby scan finished"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onStartScan, enabled = !scanning) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Scan")
            }
        }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp)) else Spacer(Modifier.height(12.dp))

        if (state.mugChoices.isEmpty()) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (scanning) "Nothing yet — keep the mug close by." else "No mugs found yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
        state.mugChoices.forEach { choice ->
            MugRow(choice, onClick = { onSelect(choice.candidate.address) }, onForget = { onForget(choice.candidate.address) })
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(12.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp)) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Adding a new mug? Press and hold the button on its base until the light pulses blue, then tap it here. Android will ask to pair.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun MugRow(choice: MugChoice, onClick: () -> Unit, onForget: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val candidate = choice.candidate
    val status = when {
        choice.connected -> "Connected"
        choice.selected && candidate.bonded -> "Selected"
        candidate.bonded -> "Paired · tap to connect"
        candidate.rssi != null -> "Nearby · tap to pair"
        else -> "Saved · not in range"
    }
    val signal = candidate.rssi?.let { rssi ->
        when {
            rssi >= -60 -> "strong signal"
            rssi >= -75 -> "good signal"
            else -> "weak signal"
        }
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (choice.selected) colors.primaryContainer else colors.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Surface(shape = CircleShape, color = if (choice.connected) colors.primary else colors.surfaceContainerHighest) {
                Icon(
                    painterResource(R.drawable.ic_mug_notification),
                    contentDescription = null,
                    tint = if (choice.connected) colors.onPrimary else colors.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(choice.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(status, signal, "…${candidate.address.takeLast(5)}").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            when {
                choice.selected -> Icon(Icons.Default.Check, contentDescription = "Selected", tint = colors.primary)
                !candidate.bonded && candidate.rssi == null -> IconButton(onClick = onForget) {
                    Icon(Icons.Default.Delete, contentDescription = "Forget ${choice.displayName}", tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

// endregion

// region Settings

private enum class SettingsDialog { NONE, NICKNAME, MUG_NAME, PRESETS }

@Composable
fun SettingsSheet(
    state: EmberAppState,
    viewModel: MainViewModel,
    onOpen: (Sheet) -> Unit,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    var dialog by rememberSaveable { mutableStateOf(SettingsDialog.NONE) }
    val connected = state.connected
    EmberSheet(onDismiss) {
        SheetTitle("Settings", state.mugDisplayName)

        SectionLabel("Units")
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            DisplayUnit.entries.forEachIndexed { index, unit ->
                SegmentedButton(
                    selected = state.displayUnit == unit,
                    onClick = { viewModel.setDisplayUnit(unit) },
                    shape = SegmentedButtonDefaults.itemShape(index, DisplayUnit.entries.size),
                ) { Text(if (unit == DisplayUnit.CELSIUS) "Celsius" else "Fahrenheit") }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("This mug")
        SettingsRow(
            title = "Nickname",
            value = state.selectedProfile?.nickname?.takeIf(String::isNotBlank) ?: "Not set",
            icon = { Icon(Icons.Default.Edit, contentDescription = null) },
            enabled = state.selectedAddress != null,
            onClick = { dialog = SettingsDialog.NICKNAME },
        )
        SettingsRow(
            title = "Name on mug",
            value = if (connected) state.snapshot.name ?: "—" else "Connect to change",
            icon = { Icon(painterResource(R.drawable.ic_mug_notification), contentDescription = null) },
            enabled = connected,
            onClick = { dialog = SettingsDialog.MUG_NAME },
        )
        SettingsRow(
            title = "Light colour",
            value = if (connected) state.snapshot.ledColor?.let(::hexColor) ?: "—" else "Connect to change",
            icon = { Icon(painterResource(R.drawable.ic_palette), contentDescription = null) },
            trailing = state.snapshot.ledColor?.let { { ColorSwatch(Color(0xFF000000.toInt() or it), selected = false, size = 24.dp) } },
            enabled = connected,
            onClick = { onOpen(Sheet.LED) },
        )
        SettingsRow(
            title = "Presets",
            value = state.presetsCelsius.joinToString("  ·  ") { TemperatureFormat.format(it, state.displayUnit, compact = true) },
            icon = { Icon(Icons.Default.Star, contentDescription = null) },
            onClick = { dialog = SettingsDialog.PRESETS },
        )
        SettingsRow(
            title = "Mug details",
            value = "Battery, firmware and activity log",
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            enabled = state.selectedAddress != null,
            onClick = { onOpen(Sheet.DETAILS) },
        )
        SettingsRow(
            title = "Switch mug",
            value = "${state.mugChoices.size} saved",
            icon = { Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null) },
            enabled = state.hasPermissions,
            onClick = { onOpen(Sheet.PICKER) },
        )

        Spacer(Modifier.height(16.dp))
        if (state.connected || state.busy) {
            OutlinedButton(onClick = { viewModel.disconnect(); onDismiss() }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Disconnect") }
        } else if (state.selectedAddress != null) {
            Button(onClick = { viewModel.connect(); onDismiss() }, enabled = state.hasPermissions && state.bluetoothEnabled, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Connect")
            }
        }
    }

    when (dialog) {
        SettingsDialog.NICKNAME -> TextFieldDialog(
            title = "Nickname",
            description = "Only shown in this app. Leave blank to use the name stored on the mug.",
            initial = state.selectedProfile?.nickname.orEmpty(),
            maxLength = 32,
            allowEmpty = true,
            onSave = { viewModel.setLocalNickname(it); dialog = SettingsDialog.NONE },
            onDismiss = { dialog = SettingsDialog.NONE },
        )
        SettingsDialog.MUG_NAME -> TextFieldDialog(
            title = "Name on mug",
            description = "Stored on the mug, so other phones see it too. Up to 16 letters, numbers and basic punctuation.",
            initial = state.snapshot.name.orEmpty(),
            maxLength = EmberProtocol.MAX_NAME_LENGTH,
            validator = { if (EmberProtocol.isValidName(it)) null else "Use letters, numbers, spaces or basic punctuation" },
            onSave = { viewModel.setHardwareName(it); dialog = SettingsDialog.NONE },
            onDismiss = { dialog = SettingsDialog.NONE },
        )
        SettingsDialog.PRESETS -> PresetsDialog(
            presetsCelsius = state.presetsCelsius,
            unit = state.displayUnit,
            onSave = { viewModel.setPresets(it); dialog = SettingsDialog.NONE; onMessage("Presets saved") },
            onDismiss = { dialog = SettingsDialog.NONE },
        )
        SettingsDialog.NONE -> Unit
    }
}

@Composable
private fun SettingsRow(
    title: String,
    value: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        leadingContent = icon,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailing?.invoke()
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f),
    )
}

@Composable
private fun TextFieldDialog(
    title: String,
    description: String,
    initial: String,
    maxLength: Int,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    allowEmpty: Boolean = false,
    validator: (String) -> String? = { null },
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(description, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(maxLength); error = null },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: "${value.length} / $maxLength") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = value.trim()
                error = when {
                    trimmed.isEmpty() && !allowEmpty -> "Enter a name"
                    trimmed.isEmpty() -> null
                    else -> validator(trimmed)
                }
                if (error == null) onSave(trimmed)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PresetsDialog(
    presetsCelsius: List<Float>,
    unit: DisplayUnit,
    onSave: (List<Float>) -> Unit,
    onDismiss: () -> Unit,
) {
    val values = remember {
        mutableStateListOf(*presetsCelsius.map { TemperatureFormat.number(unit.fromCelsius(it), unit) }.toTypedArray())
    }
    val errors = remember { mutableStateListOf(false, false, false) }
    val range = unit.range
    val rangeText = "${TemperatureFormat.number(range.start, unit)}–${TemperatureFormat.number(range.endInclusive, unit)} ${unit.symbol}"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Presets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("One-tap targets between $rangeText.", style = MaterialTheme.typography.bodyMedium)
                values.forEachIndexed { index, text ->
                    OutlinedTextField(
                        value = text,
                        onValueChange = { values[index] = it.filter { c -> c.isDigit() || c == '.' }.take(5); errors[index] = false },
                        label = { Text("Preset ${index + 1}") },
                        suffix = { Text(unit.symbol) },
                        isError = errors[index],
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = values.map { it.toFloatOrNull() }
                parsed.forEachIndexed { index, value -> errors[index] = value == null || value !in range }
                if (errors.none { it }) onSave(parsed.map { unit.toCelsius(it!!) })
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// endregion

// region Details

@Composable
fun DetailsSheet(state: EmberAppState, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val snapshot = state.snapshot
    val unavailable = "—"
    EmberSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SheetTitle(
                    "Mug details",
                    if (state.connected && state.snapshotFresh) "Live from the mug" else "Last saved values",
                )
            }
            IconButton(onClick = onRefresh, enabled = state.connected) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
        }
        DetailRow("Name on mug", snapshot.name ?: unavailable)
        DetailRow("Bluetooth address", state.selectedAddress ?: unavailable)
        DetailRow("Connection", state.connectionDetail)
        DetailRow("Last sync", if (state.snapshotUpdatedAt > 0) relativeTime(state.snapshotUpdatedAt) else "Never")
        DetailRow("Battery", snapshot.batteryPercent?.let { if (snapshot.charging == true) "$it% · charging" else "$it%" } ?: unavailable)
        DetailRow("Drink", EmberProtocol.liquidStateLabel(snapshot.liquidState))
        DetailRow("Liquid level", snapshot.liquidLevel?.let { "$it / ${EmberProtocol.FULL_LIQUID_LEVEL}" } ?: unavailable)
        DetailRow("Light colour", snapshot.ledColor?.let(::hexColor) ?: unavailable)
        DetailRow("Firmware", snapshot.firmwareVersion?.toString() ?: unavailable)
        DetailRow("Hardware", snapshot.hardwareVersion?.toString() ?: unavailable)
        DetailRow("Bootloader", snapshot.bootloaderVersion?.toString() ?: unavailable)
        DetailRow(
            "Control access",
            when (snapshot.setupKeyPresent) {
                true -> "Ready"
                false -> "Setup required"
                null -> unavailable
            },
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        SectionLabel("Activity")
        Spacer(Modifier.height(8.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
            Text(
                state.logLines.joinToString("\n").ifBlank { "Nothing yet." },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}

// endregion

// region LED colour

private val SWATCHES = listOf(
    0xF58255, 0xFF3B30, 0xFF9F0A, 0xFFD60A, 0x34C759, 0x2EC4B6, 0x0A84FF, 0x5E5CE6, 0xBF5AF2, 0xFF5FA2, 0xFFF1DC,
)

@Composable
fun LedColorSheet(initialColor: Int?, onApply: (Int, Int, Int) -> Unit, onDismiss: () -> Unit) {
    val initialHsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(0xFF000000.toInt() or (initialColor ?: 0xF58255), it) }
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initialHsv[2].coerceAtLeast(0.15f)) }
    val color = Color.hsv(hue, saturation, brightness)
    val rgb = color.toArgb() and 0xFFFFFF

    EmberSheet(onDismiss) {
        SheetTitle("Light colour", "The status light on the mug's base.")
        HueSaturationPicker(hue, saturation, onChange = { h, s -> hue = h; saturation = s })
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SWATCHES.forEach { swatch ->
                val swatchColor = Color(0xFF000000.toInt() or swatch)
                ColorSwatch(
                    swatchColor,
                    selected = swatch == rgb,
                    modifier = Modifier.clickable {
                        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(swatchColor.toArgb(), it) }
                        hue = hsv[0]
                        saturation = hsv[1]
                        brightness = hsv[2]
                    },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionLabel("Brightness")
        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 0.15f..1f)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorSwatch(color, selected = false, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Text(hexColor(rgb), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = { onApply((rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff) }) { Text("Apply") }
        }
    }
}

// endregion

private fun hexColor(rgb: Int): String = String.format(Locale.US, "#%06X", rgb and 0xFFFFFF)
