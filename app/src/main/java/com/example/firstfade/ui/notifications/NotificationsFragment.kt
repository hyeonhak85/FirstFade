package com.example.firstfade.ui.notifications

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.app.KeyguardManager
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.firstfade.ReminderForegroundService
import com.example.firstfade.databinding.FragmentNotificationsBinding

class NotificationsFragment : Fragment() {

    private var _binding: FragmentNotificationsBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    private val requestCodeNotifications = 2001
    private val uiHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateUsageText()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val notificationsViewModel =
            ViewModelProvider(this).get(NotificationsViewModel::class.java)

        _binding = FragmentNotificationsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val textView: TextView = binding.textNotifications
        notificationsViewModel.text.observe(viewLifecycleOwner) {
            textView.text = it
        }

        // 초기 스위치 상태 반영
        binding.toggleService.isChecked = isServiceRunning()
        binding.toggleService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) ensureNotificationPermissionAndStart() else requireContext().stopService(Intent(requireContext(), ReminderForegroundService::class.java))
        }

        // 사용시간 표시 시작
        updateUsageText()
        uiHandler.postDelayed(refreshRunnable, 1000L)
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        uiHandler.removeCallbacks(refreshRunnable)
        _binding = null
    }

    private fun ensureNotificationPermissionAndStart() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestCodeNotifications)
                return
            }
        }
        val intent = Intent(requireContext(), ReminderForegroundService::class.java)
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestCodeNotifications) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ensureNotificationPermissionAndStart()
            }
        }
    }

    private fun isServiceRunning(): Boolean {
        val prefs = requireContext().getSharedPreferences("usage_prefs", android.content.Context.MODE_PRIVATE)
        return prefs.getBoolean(ReminderForegroundService.KEY_SERVICE_RUNNING, false)
    }

    private fun updateUsageText() {
        val prefs = requireContext().getSharedPreferences("usage_prefs", android.content.Context.MODE_PRIVATE)
        // 표시용 보정: 서비스가 실행 중이고 기기가 잠금해제/인터랙티브인데 세션이 없으면 시작 시간 기록
        if (isServiceRunning() && isDeviceUnlockedAndInteractive()) {
            val sessionStart = prefs.getLong("session_start", -1L)
            if (sessionStart < 0) {
                prefs.edit().putLong("session_start", System.currentTimeMillis()).apply()
            }
        }
        // 누적 계산: 서비스와 동일 로직
        val sessionStart = prefs.getLong("session_start", -1L)
        val accum = prefs.getLong("accum_ms", 0L)
        val now = System.currentTimeMillis()
        val totalMs = if (sessionStart >= 0) accum + (now - sessionStart) else accum
        val hours = (totalMs / 3_600_000L).toInt()
        val minutes = ((totalMs % 3_600_000L) / 60_000L).toInt()
        val seconds = ((totalMs % 60_000L) / 1000L).toInt()
        binding.usageTimeText.text = String.format("현재 사용시간: %02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun isDeviceUnlockedAndInteractive(): Boolean {
        val keyguard = requireContext().getSystemService(android.content.Context.KEYGUARD_SERVICE) as KeyguardManager
        val power = requireContext().getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        val unlocked = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) !keyguard.isDeviceLocked else true
        val interactive = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT_WATCH) power.isInteractive else true
        return unlocked && interactive
    }
}