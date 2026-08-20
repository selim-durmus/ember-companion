package dev.nicky.ember

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.nicky.ember.ble.EmberProtocol
import dev.nicky.ember.data.DisplayUnit
import dev.nicky.ember.data.EmberAppState
import dev.nicky.ember.data.EmberRepository
import kotlinx.coroutines.launch
import java.util.Locale

class EmberConnectionService : LifecycleService() {
    private lateinit var repository: EmberRepository
    private lateinit var notificationManager: NotificationManager
    private lateinit var readyNotificationPreferences: SharedPreferences
    private lateinit var readyNotificationGate: ReadyNotificationGate
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        repository = (application as EmberApplication).repository
        notificationManager = getSystemService(NotificationManager::class.java)
        readyNotificationPreferences = getSharedPreferences(READY_STATE_PREFERENCES, Context.MODE_PRIVATE)
        readyNotificationGate = restoreReadyNotificationGate(repository.state.value)
        createNotificationChannels()
        lifecycleScope.launch {
            repository.state.collect(::handleState)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_HEATER_OFF -> repository.setTargetTemperature(0f)
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (!foregroundStarted) {
                    startForeground(ONGOING_NOTIFICATION_ID, ongoingNotification(repository.state.value))
                    foregroundStarted = true
                }
                repository.start()
                if (repository.hasPermissions()) repository.connectPreferred()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        super.onDestroy()
    }

    private fun handleState(state: EmberAppState) {
        if (foregroundStarted) notificationManager.notify(ONGOING_NOTIFICATION_ID, ongoingNotification(state))
        val hasLiveSnapshot = state.connected && state.snapshotFresh
        val decision = readyNotificationGate.update(
            mugAddress = state.snapshotAddress ?: state.selectedAddress,
            targetCelsius = state.snapshot.targetTemperatureC,
            currentCelsius = state.snapshot.currentTemperatureC.takeIf { hasLiveSnapshot },
            liquidState = state.snapshot.liquidState.takeIf { hasLiveSnapshot },
        )
        if (decision.stateChanged) persistReadyNotificationGate()
        if (decision.shouldNotify) notifyReady(state)
        EmberWidgetProvider.updateAll(this, state)
        EmberTileService.requestUpdate(this)
    }

    private fun restoreReadyNotificationGate(state: EmberAppState): ReadyNotificationGate {
        if (readyNotificationPreferences.getBoolean(KEY_READY_STATE_INITIALIZED, false)) {
            return ReadyNotificationGate(
                mugAddress = readyNotificationPreferences.getString(KEY_READY_MUG_ADDRESS, null),
                targetCelsius = readyNotificationPreferences.takeIf { it.contains(KEY_READY_TARGET) }
                    ?.getFloat(KEY_READY_TARGET, 0f),
                armed = readyNotificationPreferences.getBoolean(KEY_READY_ARMED, true),
            )
        }

        return ReadyNotificationGate(
            mugAddress = state.snapshotAddress ?: state.selectedAddress,
            targetCelsius = state.snapshot.targetTemperatureC,
            armed = !(state.snapshotFresh && state.snapshot.liquidState == 6),
        ).also {
            readyNotificationGate = it
            persistReadyNotificationGate()
        }
    }

    private fun persistReadyNotificationGate() {
        readyNotificationPreferences.edit().apply {
            putBoolean(KEY_READY_STATE_INITIALIZED, true)
            readyNotificationGate.mugAddress?.let { putString(KEY_READY_MUG_ADDRESS, it) }
                ?: remove(KEY_READY_MUG_ADDRESS)
            readyNotificationGate.targetCelsius?.let { putFloat(KEY_READY_TARGET, it) }
                ?: remove(KEY_READY_TARGET)
            putBoolean(KEY_READY_ARMED, readyNotificationGate.armed)
        }.apply()
    }

    private fun ongoingNotification(state: EmberAppState) = NotificationCompat.Builder(this, CHANNEL_CONNECTION)
        .setSmallIcon(R.drawable.ic_mug_notification)
        .setContentTitle(state.selectedProfile?.displayName ?: state.snapshot.name ?: getString(R.string.app_name))
        .setContentText(connectionSummary(state))
        .setContentIntent(openAppPendingIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    private fun notifyReady(state: EmberAppState) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        val name = state.selectedProfile?.displayName ?: state.snapshot.name ?: "Your Ember"
        val temperature = state.snapshot.currentTemperatureC?.let { formatTemperature(it, state.displayUnit) }.orEmpty()
        val heaterOffIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, EmberConnectionService::class.java).setAction(ACTION_HEATER_OFF),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_READY)
            .setSmallIcon(R.drawable.ic_mug_notification)
            .setContentTitle("$name is ready")
            .setContentText(if (temperature.isBlank()) "Your drink reached its target" else "Your drink reached $temperature")
            .setContentIntent(openAppPendingIntent())
            .addAction(0, getString(R.string.turn_heater_off), heaterOffIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        notificationManager.notify(READY_NOTIFICATION_ID, notification)
    }

    private fun connectionSummary(state: EmberAppState): String {
        if (!state.connected) return state.connectionDetail
        val current = state.snapshot.currentTemperatureC?.let { formatTemperature(it, state.displayUnit) }
        val label = EmberProtocol.liquidStateLabel(state.snapshot.liquidState)
        return listOfNotNull(current, label).joinToString(" · ").ifBlank { "Connected" }
    }

    private fun formatTemperature(celsius: Float, unit: DisplayUnit): String {
        val value = EmberRepository.displayTemperature(celsius, unit)
        return if (unit == DisplayUnit.FAHRENHEIT) {
            String.format(Locale.US, "%.0f °F", value)
        } else {
            String.format(Locale.US, "%.1f °C", value)
        }
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannels() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CONNECTION,
                "Mug connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps an Ember mug connected for ready alerts" },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_READY,
                "Drink ready",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Alerts when a drink reaches its target temperature" },
        )
    }

    companion object {
        private const val CHANNEL_CONNECTION = "ember_connection"
        private const val CHANNEL_READY = "ember_ready"
        private const val ONGOING_NOTIFICATION_ID = 4101
        private const val READY_NOTIFICATION_ID = 4102
        private const val READY_STATE_PREFERENCES = "ember_ready_notification_state"
        private const val KEY_READY_STATE_INITIALIZED = "initialized"
        private const val KEY_READY_MUG_ADDRESS = "mug_address"
        private const val KEY_READY_TARGET = "target_celsius"
        private const val KEY_READY_ARMED = "armed"
        private const val ACTION_HEATER_OFF = "dev.nicky.ember.action.HEATER_OFF"
        private const val ACTION_STOP = "dev.nicky.ember.action.STOP_MONITORING"

        fun setEnabled(context: Context, enabled: Boolean) {
            val intent = Intent(context, EmberConnectionService::class.java)
            if (enabled) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
            } else {
                context.stopService(intent)
            }
        }
    }
}
