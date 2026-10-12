package dev.nicky.ember

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.nicky.ember.data.QuickAction

/**
 * Handles notification and widget buttons. Uses [goAsync] so the process stays alive while it
 * connects to the mug and waits for the change to be confirmed.
 */
class QuickActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION)?.let { name -> QuickAction.entries.firstOrNull { it.name == name } } ?: return
        if (action == QuickAction.HEATER_OFF) EmberConnectionService.cancelReadyNotification(context)
        val pending = goAsync()
        (context.applicationContext as EmberApplication).repository.runQuickAction(action) { pending.finish() }
    }

    companion object {
        private const val EXTRA_ACTION = "action"

        fun pendingIntent(context: Context, action: QuickAction): PendingIntent = PendingIntent.getBroadcast(
            context,
            100 + action.ordinal,
            Intent(context, QuickActionReceiver::class.java).putExtra(EXTRA_ACTION, action.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
