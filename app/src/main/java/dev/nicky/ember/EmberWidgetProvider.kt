package dev.nicky.ember

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import dev.nicky.ember.data.QuickAction

class EmberWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val state = (context.applicationContext as EmberApplication).repository.state.value
        val views = remoteViews(context, ExternalState.from(state))
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }

    companion object {
        internal fun updateAll(context: Context, state: ExternalState) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, EmberWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = remoteViews(context, state)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun remoteViews(context: Context, state: ExternalState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_ember)
            views.setTextViewText(R.id.widgetMugName, state.name)
            views.setTextViewText(R.id.widgetCurrentTemperature, state.current ?: "—")
            views.setTextViewText(
                R.id.widgetTarget,
                state.target?.let { context.getString(R.string.widget_target, it) } ?: context.getString(R.string.widget_target_unknown),
            )
            views.setTextViewText(R.id.widgetStatus, state.status)
            views.setOnClickPendingIntent(R.id.widgetRoot, openApp(context))

            val actionsVisible = state.hasMug
            views.setViewVisibility(R.id.widgetActions, if (actionsVisible) View.VISIBLE else View.GONE)
            views.setTextViewText(
                R.id.widgetPreset,
                state.presetLabel?.let { context.getString(R.string.widget_heat_to, it) } ?: context.getString(R.string.widget_preset),
            )
            views.setOnClickPendingIntent(R.id.widgetPreset, QuickActionReceiver.pendingIntent(context, QuickAction.PRESET))
            views.setOnClickPendingIntent(R.id.widgetHeaterOff, QuickActionReceiver.pendingIntent(context, QuickAction.HEATER_OFF))
            return views
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            20,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
