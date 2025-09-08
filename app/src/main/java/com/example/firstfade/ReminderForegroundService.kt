package com.example.firstfade

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.app.KeyguardManager
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.firstfade.MainActivity

class ReminderForegroundService : Service() {

    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }

    private var screenReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ONGOING_NOTIFICATION_ID, buildOngoingNotification())
        initializeTodayIfNeeded()
        registerScreenReceivers()
        // 현재 상태가 이미 잠금 해제라면 세션 시작 및 다음 임계 알림 예약
        if (isDeviceUnlockedAndInteractive()) {
            startSessionIfNotStarted()
            scheduleNextThreshold()
        } else {
            endSessionIfStarted()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        unregisterScreenReceivers()
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
            .setContentText("사용 시간 알림 서비스 실행 중")
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun showUsageNotification(totalMinutes: Int) {
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
            .setContentTitle("오늘 사용 시간")
            .setContentText("자정 이후 총 ${totalMinutes}분 사용")
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
                "Usage Reminder",
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

    // ====== Usage tracking logic ======
    private fun prefs() = getSharedPreferences("usage_prefs", Context.MODE_PRIVATE)

    private fun todayKey(): String {
        val cal = java.util.Calendar.getInstance()
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        return String.format("%04d-%02d-%02d", year, month, day)
    }

    private fun initializeTodayIfNeeded() {
        val p = prefs()
        val storedDay = p.getString("day", null)
        val today = todayKey()
        if (storedDay != today) {
            p.edit()
                .putString("day", today)
                .putLong("accum_ms", 0L)
                .putLong("session_start", -1L)
                .putInt("next_threshold_min", 1)
                .apply()
        } else {
            // 기존 설정이 5분 등으로 남아있다면 1분 단위로 교정
            val cur = p.getInt("next_threshold_min", 1)
            if (cur != 1) {
                p.edit().putInt("next_threshold_min", 1).apply()
            }
        }
    }

    private fun registerScreenReceivers() {
        if (screenReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                initializeTodayIfNeeded()
                when (intent?.action) {
                    Intent.ACTION_USER_PRESENT -> {
                        startSessionIfNotStarted()
                        scheduleNextThreshold()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        endSessionIfStarted()
                    }
                }
            }
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun unregisterScreenReceivers() {
        screenReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        screenReceiver = null
    }

    private fun isDeviceUnlockedAndInteractive(): Boolean {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val unlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) !keyguard.isDeviceLocked else true
        val interactive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) power.isInteractive else true
        return unlocked && interactive
    }

    private fun startSessionIfNotStarted() {
        val p = prefs()
        val sessionStart = p.getLong("session_start", -1L)
        if (sessionStart < 0) {
            p.edit().putLong("session_start", System.currentTimeMillis()).apply()
        }
    }

    private fun endSessionIfStarted() {
        val p = prefs()
        val sessionStart = p.getLong("session_start", -1L)
        if (sessionStart >= 0) {
            val add = System.currentTimeMillis() - sessionStart
            val accum = p.getLong("accum_ms", 0L) + add
            p.edit().putLong("accum_ms", accum).putLong("session_start", -1L).apply()
        }
        handler.removeCallbacks(thresholdRunnable)
    }

    private val thresholdRunnable = object : Runnable {
        override fun run() {
            initializeTodayIfNeeded()
            ensureThresholdAndNotifyIfNeeded()
            scheduleNextThreshold()
        }
    }

    private fun ensureThresholdAndNotifyIfNeeded() {
        val p = prefs()
        val sessionStart = p.getLong("session_start", -1L)
        val accum = p.getLong("accum_ms", 0L)
        val now = System.currentTimeMillis()
        val totalMs = if (sessionStart >= 0) accum + (now - sessionStart) else accum
        val totalMin = (totalMs / 60000L).toInt()
        var nextThreshold = p.getInt("next_threshold_min", 1)
        var notified = false
        while (totalMin >= nextThreshold) {
            showUsageNotification(nextThreshold)
            nextThreshold += 1
            notified = true
        }
        if (notified) {
            p.edit().putInt("next_threshold_min", nextThreshold).apply()
        }
    }

    private fun scheduleNextThreshold() {
        handler.removeCallbacks(thresholdRunnable)
        val p = prefs()
        val sessionStart = p.getLong("session_start", -1L)
        if (sessionStart < 0) return // not unlocked -> no scheduling
        val accum = p.getLong("accum_ms", 0L)
        val nextThresholdMin = p.getInt("next_threshold_min", 1)
        val now = System.currentTimeMillis()
        val elapsedMs = accum + (now - sessionStart)
        val targetMs = nextThresholdMin * 60_000L
        val delay = (targetMs - elapsedMs).coerceAtLeast(1L)
        handler.postDelayed(thresholdRunnable, delay)
    }
}


