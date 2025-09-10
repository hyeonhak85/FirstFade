package com.example.firstfade

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UsageTrackerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("usage_prefs", Context.MODE_PRIVATE)
        val action = intent.action
        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // 날짜 초기화
                val cal = java.util.Calendar.getInstance()
                val key = String.format(
                    "%04d-%02d-%02d",
                    cal.get(java.util.Calendar.YEAR),
                    cal.get(java.util.Calendar.MONTH) + 1,
                    cal.get(java.util.Calendar.DAY_OF_MONTH)
                )
                val nowKey = prefs.getString("day", null)
                if (nowKey != key) {
                    val intervalSec = prefs.getInt("interval_seconds", 60)
                    prefs.edit()
                        .putString("day", key)
                        .putLong("accum_ms", 0L)
                        .putLong("session_start", -1L)
                        .putInt("next_threshold_seconds", intervalSec)
                        .apply()
                }
            }
            Intent.ACTION_USER_PRESENT -> {
                // 세션 시작
                val start = prefs.getLong("session_start", -1L)
                if (start < 0) {
                    prefs.edit().putLong("session_start", System.currentTimeMillis()).apply()
                }
            }
            Intent.ACTION_SCREEN_OFF -> {
                // 세션 종료 -> 누적 반영
                val start = prefs.getLong("session_start", -1L)
                if (start >= 0) {
                    val accum = prefs.getLong("accum_ms", 0L) + (System.currentTimeMillis() - start)
                    prefs.edit().putLong("accum_ms", accum).putLong("session_start", -1L).apply()
                }
            }
        }
    }
}


