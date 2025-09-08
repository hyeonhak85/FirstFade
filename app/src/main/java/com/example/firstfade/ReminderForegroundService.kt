package com.example.firstfade

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.example.firstfade.ui.home.HomeFragment

import com.example.firstfade.MainActivity

class ReminderForegroundService : Service() {

    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val tickRunnable = object : Runnable {
        override fun run() {
            showReminderNotification()
            handler.postDelayed(this, 20_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ONGOING_NOTIFICATION_ID, buildOngoingNotification())
        handler.removeCallbacks(tickRunnable)
        // 즉시 한 번 알림을 띄운 뒤, 20초마다 반복
        showReminderNotification()
        handler.postDelayed(tickRunnable, 20_000L)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildOngoingNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutability()
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("알림 서비스 실행 중")
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun showReminderNotification() {
        val dismissIntent = Intent(this, AlertActionReceiver::class.java).apply {
            action = ACTION_DISMISS
            putExtra(EXTRA_NOTIFICATION_ID, REMINDER_NOTIFICATION_ID)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutability()
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("알림")
            .setContentText("20초 알림 테스트")
            .addAction(R.drawable.ic_launcher_foreground, "OK", dismissPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(REMINDER_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reminder",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun pendingIntentMutability(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }

    companion object {
        private const val CHANNEL_ID = "reminder_channel"
        private const val ONGOING_NOTIFICATION_ID = 1
        private const val REMINDER_NOTIFICATION_ID = 2
        const val ACTION_DISMISS = "com.example.firstfade.action.DISMISS"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}


