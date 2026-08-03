package dev.nicky.ember

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.nicky.ember.data.DisplayUnit
import dev.nicky.ember.data.EmberAppState
import dev.nicky.ember.data.EmberRepository
import java.util.Locale

class EmberWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val state = (context.applicationContext as EmberApplication).repository.state.value
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, remoteViews(context, state)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val repository = (context.applicationContext as EmberApplication).repository
        when (intent.action) {
            ACTION_REFRESH -> if (repository.state.value.connected) repository.refresh() else repository.connectPreferred()
            ACTION_HEATER_OFF -> repository.setTargetTemperature(0f)
            ACTION_PRESET -> repository.state.value.presetsCelsius.getOrNull(1)?.let(repository::setTargetTemperature)
        }
        if (intent.action in setOf(ACTION_REFRESH, ACTION_HEATER_OFF, ACTION_PRESET)) {
            updateAll(context, repository.state.value)
        }
    }

    companion object {
        private const val ACTION_REFRESH = "dev.nicky.ember.widget.REFRESH"
        private const val ACTION_HEATER_OFF = "dev.nicky.ember.widget.HEATER_OFF"
        private const val ACTION_PRESET = "dev.nicky.ember.widget.PRESET"

        fun updateAll(context: Context, state: EmberAppState) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, EmberWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { manager.updateAppWidget(it, remoteViews(context, state)) }
        }

        private fun remoteViews(context: Context, state: EmberAppState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_ember)
            val name = state.selectedProfile?.displayName ?: state.snapshot.name ?: "Ember"
            val current = state.snapshot.currentTemperatureC?.let { formatTemperature(it, state.displayUnit) } ?: "—"
            val target = state.snapshot.targetTemperatureC?.let {
                if (it == 0f) "Off" else formatTemperature(it, state.displayUnit)
            } ?: "—"
            views.setTextViewText(R.id.widgetMugName, name)
            views.setTextViewText(R.id.widgetCurrentTemperature, current)
            views.setTextViewText(R.id.widgetTarget, "Target $target")
            views.setTextViewText(
                R.id.widgetStatus,
                if (state.connected) state.connectionDetail else if (state.snapshotUpdatedAt > 0) "Last known" else "Disconnected",
            )
            val openApp = activityIntent(context)
            views.setOnClickPendingIntent(R.id.widgetRoot, openApp)
            views.setOnClickPendingIntent(
                R.id.widgetRefresh,
                if (state.connected) broadcastIntent(context, ACTION_REFRESH, 21) else openApp,
            )
            views.setOnClickPendingIntent(
                R.id.widgetPreset,
                if (state.connected) broadcastIntent(context, ACTION_PRESET, 22) else openApp,
            )
            views.setOnClickPendingIntent(
                R.id.widgetHeaterOff,
                if (state.connected) broadcastIntent(context, ACTION_HEATER_OFF, 23) else openApp,
            )
            return views
        }

        private fun activityIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            20,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun broadcastIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, EmberWidgetProvider::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun formatTemperature(celsius: Float, unit: DisplayUnit): String {
            val value = EmberRepository.displayTemperature(celsius, unit)
            return if (unit == DisplayUnit.FAHRENHEIT) {
                String.format(Locale.US, "%.0f°", value)
            } else {
                String.format(Locale.US, "%.1f°", value)
            }
        }
    }
}
