package dev.nicky.ember

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.nicky.ember.ble.ConnectionState
import dev.nicky.ember.ble.EmberProtocol
import dev.nicky.ember.data.DisplayUnit
import dev.nicky.ember.data.EmberAppState
import dev.nicky.ember.data.EmberRepository
import dev.nicky.ember.data.ScanState
import dev.nicky.ember.data.WritePhase
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    private lateinit var mugName: TextView
    private lateinit var mugIdentity: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var currentTemperature: TextView
    private lateinit var targetTemperature: TextView
    private lateinit var liquidState: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var liquidLevel: TextView
    private lateinit var eventLog: TextView
    private lateinit var writeStatus: TextView
    private lateinit var connectButton: MaterialButton
    private lateinit var refreshButton: MaterialButton
    private lateinit var setTemperatureButton: MaterialButton
    private lateinit var heaterOffButton: MaterialButton
    private lateinit var localNicknameButton: MaterialButton
    private lateinit var hardwareNameButton: MaterialButton
    private lateinit var ledColorButton: MaterialButton
    private lateinit var temperatureSlider: Slider
    private lateinit var unitToggle: MaterialButtonToggleGroup
    private lateinit var backgroundMonitoringSwitch: SwitchMaterial
    private lateinit var presetButtons: List<MaterialButton>

    private var latestState = EmberAppState()
    private var userDraggingSlider = false
    private var renderingControls = false
    private var pendingBluetoothAction: (() -> Unit)? = null
    private var mugPickerDialog: BottomSheetDialog? = null
    private var mugPickerList: LinearLayout? = null
    private var mugPickerStatus: TextView? = null
    private var mugPickerProgress: ProgressBar? = null
    private var mugPickerEmpty: TextView? = null
    private var mugPickerScanButton: MaterialButton? = null
    private var transientSheet: BottomSheetDialog? = null

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            val firstStart = viewModel.start()
            pendingBluetoothAction?.invoke() ?: if (firstStart) viewModel.connectPreferred() else Unit
        } else {
            connectionStatus.text = getString(R.string.bluetooth_permission_required)
        }
        pendingBluetoothAction = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.setBackgroundMonitoring(true)
        } else {
            renderingControls = true
            backgroundMonitoringSwitch.isChecked = false
            renderingControls = false
            showMessage(getString(R.string.notification_permission_detail))
        }
    }

    private val companionChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val device = if (Build.VERSION.SDK_INT >= 33) {
            result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
        }
        device?.address?.let(viewModel::selectMug)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        configureSystemBars()
        bindViews()
        configureControls()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }

        ensureBluetoothPermissions {
            if (viewModel.start()) viewModel.connectPreferred()
        }
    }

    override fun onStop() {
        mugPickerDialog?.dismiss()
        mugPickerDialog = null
        transientSheet?.dismiss()
        transientSheet = null
        super.onStop()
    }

    private fun configureSystemBars() {
        val root = findViewById<android.view.View>(R.id.rootScroll)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        // The listener is installed after setContentView, so explicitly request the current
        // insets. Without this, an already-dispatched first frame can leave content under the
        // status bar until the next configuration change.
        ViewCompat.requestApplyInsets(root)
        val darkMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkMode
            isAppearanceLightNavigationBars = !darkMode
        }
    }

    private fun bindViews() {
        mugName = findViewById(R.id.mugName)
        mugIdentity = findViewById(R.id.mugIdentity)
        connectionStatus = findViewById(R.id.connectionStatus)
        currentTemperature = findViewById(R.id.currentTemperature)
        targetTemperature = findViewById(R.id.targetTemperature)
        liquidState = findViewById(R.id.liquidState)
        batteryStatus = findViewById(R.id.batteryStatus)
        liquidLevel = findViewById(R.id.liquidLevel)
        eventLog = findViewById(R.id.eventLog)
        writeStatus = findViewById(R.id.writeStatus)
        connectButton = findViewById(R.id.connectButton)
        refreshButton = findViewById(R.id.refreshButton)
        setTemperatureButton = findViewById(R.id.setTemperatureButton)
        heaterOffButton = findViewById(R.id.heaterOffButton)
        localNicknameButton = findViewById(R.id.localNicknameButton)
        hardwareNameButton = findViewById(R.id.hardwareNameButton)
        ledColorButton = findViewById(R.id.ledColorButton)
        temperatureSlider = findViewById(R.id.temperatureSlider)
        unitToggle = findViewById(R.id.unitToggle)
        backgroundMonitoringSwitch = findViewById(R.id.backgroundMonitoringSwitch)
        presetButtons = listOf(
            findViewById(R.id.presetOneButton),
            findViewById(R.id.presetTwoButton),
            findViewById(R.id.presetThreeButton),
        )
    }

    private fun configureControls() {
        temperatureSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                userDraggingSlider = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                userDraggingSlider = false
            }
        })
        temperatureSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) targetTemperature.text = formatDisplayTemperature(value, latestState.displayUnit)
        }
        connectButton.setOnClickListener {
            ensureBluetoothPermissions {
                when (latestState.connectionState) {
                    ConnectionState.DISCONNECTED -> viewModel.connectPreferred()
                    ConnectionState.CONNECTED -> viewModel.disconnect()
                    else -> viewModel.disconnect()
                }
            }
        }
        findViewById<MaterialButton>(R.id.chooseMugButton).setOnClickListener {
            ensureBluetoothPermissions(::showMugPicker)
        }
        refreshButton.setOnClickListener { viewModel.refresh() }
        setTemperatureButton.setOnClickListener {
            viewModel.setTargetTemperature(
                EmberRepository.inputToCelsius(temperatureSlider.value, latestState.displayUnit),
            )
        }
        heaterOffButton.setOnClickListener { viewModel.turnHeaterOff() }
        presetButtons.forEachIndexed { index, button ->
            button.setOnClickListener { latestState.presetsCelsius.getOrNull(index)?.let(viewModel::setTargetTemperature) }
        }
        findViewById<MaterialButton>(R.id.editPresetsButton).setOnClickListener { showPresetEditor() }
        localNicknameButton.setOnClickListener { showLocalNicknameEditor() }
        hardwareNameButton.setOnClickListener { showHardwareNameEditor() }
        ledColorButton.setOnClickListener { showLedColorPicker() }
        unitToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || renderingControls) return@addOnButtonCheckedListener
            viewModel.setDisplayUnit(if (checkedId == R.id.fahrenheitButton) DisplayUnit.FAHRENHEIT else DisplayUnit.CELSIUS)
        }
        backgroundMonitoringSwitch.setOnCheckedChangeListener { _, checked ->
            if (renderingControls) return@setOnCheckedChangeListener
            if (checked) requestNotificationPermissionOrEnable() else viewModel.setBackgroundMonitoring(false)
        }
    }

    private fun render(state: EmberAppState) {
        val unitChanged = latestState.displayUnit != state.displayUnit
        latestState = state
        renderingControls = true

        val profile = state.selectedProfile
        val hardwareName = state.snapshot.name?.takeIf(String::isNotBlank) ?: "Ember"
        mugName.text = profile?.displayName ?: hardwareName
        mugIdentity.text = buildString {
            if (profile?.nickname?.isNotBlank() == true && hardwareName != profile.nickname) append("$hardwareName · ")
            append(state.selectedAddress?.let { "…${it.takeLast(5)}" } ?: getString(R.string.no_mug_selected))
            if (state.snapshotUpdatedAt > 0 && !state.snapshotFresh) append(" · last known")
        }
        connectionStatus.text = state.connectionDetail
        renderConnectionButton(state.connectionState)

        currentTemperature.text = state.snapshot.currentTemperatureC?.let {
            formatCelsiusTemperature(it, state.displayUnit)
        } ?: getString(R.string.not_available)
        targetTemperature.text = state.snapshot.targetTemperatureC?.let {
            if (it == 0f) "Heater off" else formatCelsiusTemperature(it, state.displayUnit)
        } ?: getString(R.string.not_available)
        liquidState.text = EmberProtocol.liquidStateLabel(state.snapshot.liquidState)
        liquidLevel.text = state.snapshot.liquidLevel?.let { if (it == 0) "Empty" else "$it / 30" }
            ?: getString(R.string.not_available)
        batteryStatus.text = state.snapshot.batteryPercent?.let {
            if (state.snapshot.charging == true) "$it% · charging" else "$it%"
        } ?: getString(R.string.not_available)

        if (unitChanged || !userDraggingSlider) configureSlider(state)
        presetButtons.forEachIndexed { index, button ->
            button.text = state.presetsCelsius.getOrNull(index)?.let {
                formatCelsiusTemperature(it, state.displayUnit, compact = true)
            } ?: "—"
        }
        unitToggle.check(if (state.displayUnit == DisplayUnit.FAHRENHEIT) R.id.fahrenheitButton else R.id.celsiusButton)
        backgroundMonitoringSwitch.isChecked = state.backgroundMonitoring
        renderWriteStatus(state)
        eventLog.text = state.logLines.joinToString("\n").ifBlank { getString(R.string.ready) }

        val connected = state.connected
        refreshButton.isEnabled = connected
        temperatureSlider.isEnabled = connected
        setTemperatureButton.isEnabled = connected && state.writeStatus.phase != WritePhase.WRITING
        heaterOffButton.isEnabled = connected && state.writeStatus.phase != WritePhase.WRITING
        presetButtons.forEach { it.isEnabled = connected && state.writeStatus.phase != WritePhase.WRITING }
        hardwareNameButton.isEnabled = connected
        ledColorButton.isEnabled = connected
        state.snapshot.ledRgba?.takeIf { it.size >= 3 }?.let { colour ->
            ledColorButton.text = String.format(
                Locale.US,
                "Change LED colour · #%02X%02X%02X",
                colour[0],
                colour[1],
                colour[2],
            )
        }
        localNicknameButton.isEnabled = state.selectedAddress != null

        updateMugPicker(state)
        renderingControls = false
    }

    private fun renderConnectionButton(state: ConnectionState) {
        when (state) {
            ConnectionState.DISCONNECTED -> {
                connectButton.text = getString(R.string.connect)
                connectButton.isEnabled = true
            }
            ConnectionState.CONNECTED -> {
                connectButton.text = getString(R.string.disconnect)
                connectButton.isEnabled = true
            }
            ConnectionState.CONNECTING, ConnectionState.DISCOVERING, ConnectionState.PAIRING -> {
                connectButton.text = getString(R.string.connect_cancel)
                connectButton.isEnabled = true
            }
            ConnectionState.SCANNING -> {
                connectButton.text = getString(R.string.connecting)
                connectButton.isEnabled = false
            }
        }
    }

    private fun renderWriteStatus(state: EmberAppState) {
        writeStatus.text = state.writeStatus.message
        val color = when (state.writeStatus.phase) {
            WritePhase.FAILED -> R.color.ember_copper_dark
            WritePhase.CONFIRMED -> R.color.ember_sage
            else -> R.color.ember_sage
        }
        writeStatus.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun configureSlider(state: EmberAppState) {
        val targetCelsius = state.snapshot.targetTemperatureC?.takeIf { it in 49f..63f }
            ?: state.presetsCelsius.getOrElse(1) { 57.5f }
        val displayValue = EmberRepository.displayTemperature(targetCelsius, state.displayUnit)
        temperatureSlider.stepSize = 0f
        when (state.displayUnit) {
            DisplayUnit.CELSIUS -> {
                temperatureSlider.valueFrom = 49f
                temperatureSlider.value = EmberProtocol.snapTargetForSlider(displayValue).coerceIn(49f, 63f)
                temperatureSlider.valueTo = 63f
                temperatureSlider.stepSize = 0.5f
            }
            DisplayUnit.FAHRENHEIT -> {
                temperatureSlider.valueTo = 145f
                temperatureSlider.value = kotlin.math.round(displayValue).coerceIn(120f, 145f)
                temperatureSlider.valueFrom = 120f
                temperatureSlider.stepSize = 1f
            }
        }
    }

    private fun showMugPicker() {
        if (mugPickerDialog?.isShowing == true) return
        val content = layoutInflater.inflate(R.layout.bottom_sheet_mug_picker, null)
        mugPickerList = content.findViewById(R.id.mugPickerList)
        mugPickerStatus = content.findViewById(R.id.mugPickerStatus)
        mugPickerProgress = content.findViewById(R.id.mugPickerProgress)
        mugPickerEmpty = content.findViewById(R.id.mugPickerEmpty)
        mugPickerScanButton = content.findViewById(R.id.mugPickerScanButton)

        val dialog = BottomSheetDialog(this).apply {
            setContentView(content)
        }
        content.findViewById<MaterialButton>(R.id.mugPickerDoneButton).setOnClickListener { dialog.dismiss() }
        content.findViewById<MaterialButton>(R.id.mugPickerAddButton).setOnClickListener { beginCompanionPairing() }
        mugPickerScanButton?.setOnClickListener { viewModel.startMugScan() }
        dialog.setOnDismissListener {
            viewModel.stopMugScan()
            mugPickerDialog = null
            mugPickerList = null
            mugPickerStatus = null
            mugPickerProgress = null
            mugPickerEmpty = null
            mugPickerScanButton = null
        }
        mugPickerDialog = dialog
        dialog.show()
        updateMugPicker(latestState)
        viewModel.startMugScan()
    }

    private fun updateMugPicker(state: EmberAppState) {
        val list = mugPickerList ?: return
        list.removeAllViews()
        state.mugChoices.forEach { choice ->
            val row = layoutInflater.inflate(R.layout.item_mug_choice, list, false) as MaterialCardView
            row.findViewById<TextView>(R.id.mugChoiceName).text = choice.displayName
            row.findViewById<TextView>(R.id.mugChoiceStatus).apply {
                text = when {
                    choice.connected -> getString(R.string.mug_status_connected)
                    choice.selected -> getString(R.string.mug_status_selected)
                    choice.candidate.bonded -> getString(R.string.mug_status_paired)
                    else -> getString(R.string.mug_status_saved)
                }
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (choice.connected) R.color.ember_copper_dark else R.color.ember_sage,
                    ),
                )
            }
            row.findViewById<TextView>(R.id.mugChoiceMeta).text = buildString {
                append("…${choice.candidate.address.takeLast(5)}")
                choice.candidate.rssi?.let { rssi ->
                    val signal = when {
                        rssi >= -60 -> getString(R.string.signal_strong)
                        rssi >= -75 -> getString(R.string.signal_nearby)
                        else -> getString(R.string.signal_weak)
                    }
                    append("  ·  $signal  ·  $rssi dBm")
                } ?: append("  ·  ${getString(R.string.saved_mug)}")
            }
            row.findViewById<TextView>(R.id.mugChoiceCheck).visibility =
                if (choice.connected || choice.selected) android.view.View.VISIBLE else android.view.View.INVISIBLE
            row.strokeColor = ContextCompat.getColor(
                this,
                if (choice.connected || choice.selected) R.color.ember_copper else R.color.ember_outline,
            )
            row.strokeWidth = resources.getDimensionPixelSize(
                if (choice.connected || choice.selected) R.dimen.mug_card_selected_stroke else R.dimen.mug_card_stroke,
            )
            row.setOnClickListener {
                if (!choice.connected) viewModel.selectMug(choice.candidate.address)
                mugPickerDialog?.dismiss()
            }
            list.addView(row)
        }
        mugPickerEmpty?.visibility = if (state.mugChoices.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        val scanning = state.scanState == ScanState.SCANNING
        mugPickerProgress?.visibility = if (scanning) android.view.View.VISIBLE else android.view.View.GONE
        mugPickerStatus?.text = when {
            scanning -> getString(R.string.scanning_nearby)
            state.scanDetail.isNotBlank() -> state.scanDetail
            state.mugChoices.isEmpty() -> getString(R.string.no_saved_mugs)
            else -> getString(R.string.scan_complete)
        }
        mugPickerScanButton?.apply {
            isEnabled = !scanning
            text = getString(if (scanning) R.string.scanning else R.string.scan_again)
        }
    }

    private fun beginCompanionPairing() {
        mugPickerDialog?.dismiss()
        showInfoSheet(
            title = getString(R.string.pair_another_mug),
            message = getString(R.string.pairing_help),
            primaryLabel = getString(R.string.open_pairing),
            onConfirm = ::launchCompanionChooser,
        )
    }

    private fun launchCompanionChooser() {
        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("(?i).*Ember.*"))
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(false)
            .build()
        val manager = getSystemService(CompanionDeviceManager::class.java)
        val callback = object : CompanionDeviceManager.Callback() {
            @Deprecated("Required by the compatibility association API")
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                launchIntentSender(chooserLauncher)
            }

            override fun onFailure(errorMessage: CharSequence?) {
                runOnUiThread {
                    showMessage("System pairing unavailable: ${errorMessage ?: "try the nearby scan"}")
                    viewModel.startMugScan()
                }
            }
        }
        @Suppress("DEPRECATION")
        manager.associate(request, callback, null)
    }

    private fun launchIntentSender(intentSender: IntentSender) {
        runOnUiThread {
            companionChooserLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        }
    }

    private fun showLocalNicknameEditor() {
        val current = latestState.selectedProfile?.nickname.orEmpty()
        showTextEditor(
            title = getString(R.string.local_nickname_title),
            subtitle = getString(R.string.local_nickname_detail),
            fieldLabel = getString(R.string.nickname_field),
            current = current,
            maxLength = 32,
            allowEmpty = true,
        ) { viewModel.setLocalNickname(it) }
    }

    private fun showHardwareNameEditor() {
        val current = latestState.snapshot.name.orEmpty()
        showTextEditor(
            title = getString(R.string.mug_name_title),
            subtitle = getString(R.string.mug_name_detail),
            fieldLabel = getString(R.string.mug_name_field),
            current = current,
            maxLength = 16,
            allowEmpty = false,
            validator = { name ->
                getString(R.string.invalid_mug_name).takeUnless {
                    runCatching { EmberProtocol.encodeName(name) }.isSuccess
                }
            },
        ) { viewModel.setHardwareName(it) }
    }

    private fun showTextEditor(
        title: String,
        subtitle: String,
        fieldLabel: String,
        current: String,
        maxLength: Int,
        allowEmpty: Boolean,
        validator: (String) -> String? = { null },
        onSave: (String) -> Unit,
    ) {
        val content = layoutInflater.inflate(R.layout.bottom_sheet_text_editor, null)
        content.findViewById<TextView>(R.id.textEditorTitle).text = title
        content.findViewById<TextView>(R.id.textEditorSubtitle).text = subtitle
        val inputLayout = content.findViewById<TextInputLayout>(R.id.textEditorInputLayout).apply {
            hint = fieldLabel
            counterMaxLength = maxLength
        }
        val input = content.findViewById<TextInputEditText>(R.id.textEditorInput).apply {
            setText(current)
            setSelection(current.length)
            filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
            setSingleLine()
        }
        val dialog = BottomSheetDialog(this).apply { setContentView(content) }
        content.findViewById<MaterialButton>(R.id.textEditorCancelButton).setOnClickListener { dialog.dismiss() }
        content.findViewById<MaterialButton>(R.id.textEditorSaveButton).setOnClickListener {
            val value = input.text?.toString()?.trim().orEmpty()
            val error = when {
                value.isEmpty() && !allowEmpty -> getString(R.string.name_required)
                else -> validator(value)
            }
            if (error != null) {
                inputLayout.error = error
                return@setOnClickListener
            }
            onSave(value)
            dialog.dismiss()
        }
        showTransientSheet(dialog)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun showLedColorPicker() {
        val palette = listOf(
            LedChoice("Ember orange", 245, 130, 85),
            LedChoice("Blue", 70, 120, 255),
            LedChoice("Green", 80, 200, 130),
            LedChoice("Purple", 170, 90, 255),
            LedChoice("Pink", 255, 90, 160),
            LedChoice("Red", 255, 65, 65),
            LedChoice("White", 255, 255, 255),
        )
        val current = latestState.snapshot.ledRgba?.takeIf { it.size >= 3 }?.let {
            LedChoice(getString(R.string.current_colour), it[0], it[1], it[2])
        }
        val colours = buildList {
            if (current != null && palette.none { it.sameColour(current) }) add(current)
            addAll(palette)
        }
        val content = layoutInflater.inflate(R.layout.bottom_sheet_led_picker, null)
        val grid = content.findViewById<GridLayout>(R.id.ledChoiceGrid)
        val dialog = BottomSheetDialog(this).apply { setContentView(content) }
        colours.forEach { colour ->
            val row = layoutInflater.inflate(R.layout.item_led_choice, grid, false) as MaterialCardView
            row.findViewById<View>(R.id.ledChoiceSwatch).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(colour.red, colour.green, colour.blue))
                setStroke(resources.getDimensionPixelSize(R.dimen.mug_card_stroke), ContextCompat.getColor(this@MainActivity, R.color.ember_outline))
            }
            row.findViewById<TextView>(R.id.ledChoiceName).text = colour.name
            row.findViewById<TextView>(R.id.ledChoiceHex).text = colour.hex
            val selected = current?.sameColour(colour) == true
            row.findViewById<TextView>(R.id.ledChoiceCheck).visibility = if (selected) View.VISIBLE else View.INVISIBLE
            row.strokeColor = ContextCompat.getColor(this, if (selected) R.color.ember_copper else R.color.ember_outline)
            row.strokeWidth = resources.getDimensionPixelSize(if (selected) R.dimen.mug_card_selected_stroke else R.dimen.mug_card_stroke)
            row.setOnClickListener {
                viewModel.setLedColor(colour.red, colour.green, colour.blue)
                dialog.dismiss()
            }
            grid.addView(row)
        }
        content.findViewById<MaterialButton>(R.id.ledPickerDoneButton).setOnClickListener { dialog.dismiss() }
        showTransientSheet(dialog)
    }

    private fun showPresetEditor() {
        val unit = latestState.displayUnit
        val content = layoutInflater.inflate(R.layout.bottom_sheet_preset_editor, null)
        val layouts = listOf<TextInputLayout>(
            content.findViewById(R.id.presetOneLayout),
            content.findViewById(R.id.presetTwoLayout),
            content.findViewById(R.id.presetThreeLayout),
        )
        val inputs = listOf<TextInputEditText>(
            content.findViewById(R.id.presetOneInput),
            content.findViewById(R.id.presetTwoInput),
            content.findViewById(R.id.presetThreeInput),
        )
        inputs.forEachIndexed { index, input ->
            input.apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                val celsius = latestState.presetsCelsius.getOrElse(index) { 57.5f }
                val format = if (unit == DisplayUnit.CELSIUS) "%.1f" else "%.0f"
                setText(String.format(Locale.US, format, EmberRepository.displayTemperature(celsius, unit)))
                setSelection(text?.length ?: 0)
            }
            layouts[index].suffixText = if (unit == DisplayUnit.CELSIUS) "°C" else "°F"
        }
        content.findViewById<TextView>(R.id.presetEditorSubtitle).text = getString(
            if (unit == DisplayUnit.CELSIUS) R.string.preset_range_celsius else R.string.preset_range_fahrenheit,
        )
        val dialog = BottomSheetDialog(this).apply { setContentView(content) }
        content.findViewById<MaterialButton>(R.id.presetCancelButton).setOnClickListener { dialog.dismiss() }
        content.findViewById<MaterialButton>(R.id.presetSaveButton).setOnClickListener {
            val displayValues = inputs.map { input -> input.text.toString().toFloatOrNull() }
            val validRange = if (unit == DisplayUnit.CELSIUS) 49f..63f else 120f..145f
            var valid = true
            displayValues.forEachIndexed { index, value ->
                layouts[index].error = if (value == null || value !in validRange) {
                    valid = false
                    getString(if (unit == DisplayUnit.CELSIUS) R.string.valid_celsius_range else R.string.valid_fahrenheit_range)
                } else {
                    null
                }
            }
            if (!valid) return@setOnClickListener
            viewModel.setPresets(displayValues.map { EmberRepository.inputToCelsius(it!!, unit) })
            dialog.dismiss()
        }
        showTransientSheet(dialog)
    }

    private fun requestNotificationPermissionOrEnable() {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.setBackgroundMonitoring(true)
            return
        }
        showInfoSheet(
            title = getString(R.string.ready_notifications_title),
            message = getString(R.string.notification_permission_detail),
            primaryLabel = getString(R.string.continue_label),
            onConfirm = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            onCancel = {
                renderingControls = true
                backgroundMonitoringSwitch.isChecked = false
                renderingControls = false
            },
        )
    }

    private fun showInfoSheet(
        title: String,
        message: String,
        primaryLabel: String,
        onConfirm: () -> Unit,
        onCancel: () -> Unit = {},
    ) {
        val content = layoutInflater.inflate(R.layout.bottom_sheet_info, null)
        content.findViewById<TextView>(R.id.infoSheetTitle).text = title
        content.findViewById<TextView>(R.id.infoSheetMessage).text = message
        content.findViewById<MaterialButton>(R.id.infoSheetPrimaryButton).text = primaryLabel
        val dialog = BottomSheetDialog(this).apply { setContentView(content) }
        var confirmed = false
        content.findViewById<MaterialButton>(R.id.infoSheetCancelButton).setOnClickListener { dialog.dismiss() }
        content.findViewById<MaterialButton>(R.id.infoSheetPrimaryButton).setOnClickListener {
            confirmed = true
            dialog.dismiss()
            onConfirm()
        }
        showTransientSheet(dialog) {
            if (!confirmed) onCancel()
        }
    }

    private fun showTransientSheet(dialog: BottomSheetDialog, onDismiss: () -> Unit = {}) {
        transientSheet?.dismiss()
        transientSheet = dialog
        dialog.setOnDismissListener {
            if (transientSheet === dialog) transientSheet = null
            onDismiss()
        }
        dialog.show()
    }

    private fun showMessage(message: String) {
        Snackbar.make(findViewById(R.id.rootScroll), message, Snackbar.LENGTH_LONG).show()
    }

    private fun ensureBluetoothPermissions(action: () -> Unit) {
        val permissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingBluetoothAction = action
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun formatCelsiusTemperature(celsius: Float, unit: DisplayUnit, compact: Boolean = false): String {
        return formatDisplayTemperature(EmberRepository.displayTemperature(celsius, unit), unit, compact)
    }

    private fun formatDisplayTemperature(value: Float, unit: DisplayUnit, compact: Boolean = false): String {
        val suffix = if (unit == DisplayUnit.CELSIUS) "°C" else "°F"
        val number = if (unit == DisplayUnit.FAHRENHEIT) {
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.1f", value)
        }
        return if (compact) "$number°" else "$number $suffix"
    }

    private data class LedChoice(val name: String, val red: Int, val green: Int, val blue: Int) {
        val hex: String
            get() = String.format(Locale.US, "#%02X%02X%02X", red, green, blue)

        fun sameColour(other: LedChoice): Boolean = red == other.red && green == other.green && blue == other.blue
    }
}
