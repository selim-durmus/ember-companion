package dev.nicky.ember

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.nicky.ember.data.EmberAppState
import dev.nicky.ember.data.EmberRepository
import dev.nicky.ember.data.QuickAction
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the mug connected while "ready" notifications are enabled.
 * The repository decides when it runs; this class only renders notifications.
 */
class EmberConnectionService : LifecycleService() {
    private lateinit var repository: EmberRepository
    private lateinit var notificationManager: NotificationManager
    private lateinit var gatePreferences: SharedPreferences
    private lateinit var readyGate: ReadyNotificationGate

    override fun onCreate() {
        super.onCreate()
        repository = (application as EmberApplication).repository
        notificationManager = getSystemService(NotificationManager::class.java)
        gatePreferences = getSharedPreferences(READY_STATE_PREFERENCES, Context.MODE_PRIVATE)
        readyGate = restoreReadyGate(repository.state.value)
        lifecycleScope.launch {
            repository.state
                .map(::NotificationModel)
                .distinctUntilChanged()
                .collect { handleState(repository.state.value) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            repository.setBackgroundMonitoring(false)
            stopSelf()
            return START_NOT_STICKY
        }
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                ONGOING_NOTIFICATION_ID,
                ongoingNotification(repository.state.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.isSuccess
        if (!started) {
            // Typically a sticky restart while the app is in the background, or a revoked
            // Bluetooth permission. The repository restarts monitoring next time it can.
            stopSelf()
            return START_NOT_STICKY
        }
        repository.refreshEnvironment()
        if (!repository.state.value.connected) repository.connectPreferred()
        return START_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun handleState(state: EmberAppState) {
        val live = state.connected && state.snapshotFresh
        val decision = readyGate.update(
            mugAddress = state.snapshotAddress ?: state.selectedAddress,
            targetCelsius = state.snapshot.targetTemperatureC,
            currentCelsius = state.snapshot.currentTemperatureC.takeIf { live },
            liquidState = state.snapshot.liquidState.takeIf { live },
        )
        if (decision.stateChanged) persistReadyGate()
        if (decision.shouldNotify) notifyReady(this, state)
        if (canPostNotifications(this)) notificationManager.notify(ONGOING_NOTIFICATION_ID, ongoingNotification(state))
    }

    private fun restoreReadyGate(state: EmberAppState): ReadyNotificationGate {
        if (gatePreferences.getBoolean(KEY_READY_STATE_INITIALIZED, false)) {
            return ReadyNotificationGate(
                mugAddress = gatePreferences.getString(KEY_READY_MUG_ADDRESS, null),
                targetCelsius = gatePreferences.takeIf { it.contains(KEY_READY_TARGET) }?.getFloat(KEY_READY_TARGET, 0f),
                armed = gatePreferences.getBoolean(KEY_READY_ARMED, true),
            )
        }
        return ReadyNotificationGate(
            mugAddress = state.snapshotAddress ?: state.selectedAddress,
            targetCelsius = state.snapshot.targetTemperatureC,
            armed = !(state.snapshotFresh && state.snapshot.liquidState == dev.nicky.ember.ble.LiquidState.AT_TARGET),
        )
    }

    private fun persistReadyGate() {
        gatePreferences.edit {
            putBoolean(KEY_READY_STATE_INITIALIZED, true)
            readyGate.mugAddress?.let { putString(KEY_READY_MUG_ADDRESS, it) } ?: remove(KEY_READY_MUG_ADDRESS)
            readyGate.targetCelsius?.let { putFloat(KEY_READY_TARGET, it) } ?: remove(KEY_READY_TARGET)
            putBoolean(KEY_READY_ARMED, readyGate.armed)
        }
    }

    private fun ongoingNotification(state: EmberAppState) = NotificationCompat.Builder(this, CHANNEL_CONNECTION)
        .setSmallIcon(R.drawable.ic_mug_notification)
        .setContentTitle(state.mugDisplayName)
        .setContentText(OngoingNotificationText.summary(state))
        .setContentIntent(openAppIntent(this))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .apply {
            if (state.connected && state.heaterOn) {
                addAction(0, getString(R.string.turn_heater_off), QuickActionReceiver.pendingIntent(this@EmberConnectionService, QuickAction.HEATER_OFF))
            }
        }
        .addAction(0, getString(R.string.stop_monitoring), stopIntent())
        .build()

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_STOP,
        Intent(this, EmberConnectionService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The subset of state the notifications depend on; avoids re-posting on every log line. */
    private data class NotificationModel(
        val title: String,
        val summary: String,
        val connected: Boolean,
        val heaterOn: Boolean,
        val fresh: Boolean,
        val address: String?,
        val target: Float?,
        val current: Float?,
        val liquidState: Int?,
    ) {
        constructor(state: EmberAppState) : this(
            state.mugDisplayName,
            OngoingNotificationText.summary(state),
            state.connected,
            state.heaterOn,
            state.snapshotFresh,
            state.snapshotAddress,
            state.snapshot.targetTemperatureC,
            state.snapshot.currentTemperatureC,
            state.snapshot.liquidState,
        )
    }

    companion object {
        private const val CHANNEL_CONNECTION = "ember_connection"
        private const val CHANNEL_READY = "ember_ready"
        private const val ONGOING_NOTIFICATION_ID = 4101
        private const val READY_NOTIFICATION_ID = 4102
        private const val REQUEST_OPEN_APP = 1
        private const val REQUEST_STOP = 3
        private const val READY_STATE_PREFERENCES = "ember_ready_notification_state"
        private const val KEY_READY_STATE_INITIALIZED = "initialized"
        private const val KEY_READY_MUG_ADDRESS = "mug_address"
        private const val KEY_READY_TARGET = "target_celsius"
        private const val KEY_READY_ARMED = "armed"
        private const val ACTION_STOP = "dev.nicky.ember.action.STOP_MONITORING"

        /** Returns false when Android does not allow starting a foreground service right now. */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, EmberConnectionService::class.java))
        }.isSuccess

        fun stop(context: Context) {
            context.stopService(Intent(context, EmberConnectionService::class.java))
        }

        fun createChannels(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannels(
                listOf(
                    NotificationChannel(CHANNEL_CONNECTION, context.getString(R.string.channel_connection), NotificationManager.IMPORTANCE_LOW)
                        .apply { description = context.getString(R.string.channel_connection_detail) },
                    NotificationChannel(CHANNEL_READY, context.getString(R.string.channel_ready), NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = context.getString(R.string.channel_ready_detail) },
                ),
            )
        }

        fun cancelReadyNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(READY_NOTIFICATION_ID)
        }

        fun cancelNotifications(context: Context) {
            context.getSystemService(NotificationManager::class.java).apply {
                cancel(READY_NOTIFICATION_ID)
                cancel(ONGOING_NOTIFICATION_ID)
            }
        }

        /** POST_NOTIFICATIONS only exists from Android 13; before that the app-level toggle decides. */
        fun canPostNotifications(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        private fun notifyReady(context: Context, state: EmberAppState) {
            if (!canPostNotifications(context)) return
            val temperature = state.snapshot.currentTemperatureC?.let { TemperatureFormat.format(it, state.displayUnit) }
            val notification = NotificationCompat.Builder(context, CHANNEL_READY)
                .setSmallIcon(R.drawable.ic_mug_notification)
                .setContentTitle(context.getString(R.string.ready_title, state.mugDisplayName))
                .setContentText(
                    temperature?.let { context.getString(R.string.ready_text, it) } ?: context.getString(R.string.ready_text_generic),
                )
                .setContentIntent(openAppIntent(context))
                .addAction(0, context.getString(R.string.turn_heater_off), QuickActionReceiver.pendingIntent(context, QuickAction.HEATER_OFF))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(READY_NOTIFICATION_ID, notification)
        }

        private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
