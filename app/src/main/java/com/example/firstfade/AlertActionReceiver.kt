package com.example.firstfade

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == ReminderForegroundService.ACTION_DISMISS) {
            val id = intent.getIntExtra(ReminderForegroundService.EXTRA_NOTIFICATION_ID, 0)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(id)
        }
    }
}


