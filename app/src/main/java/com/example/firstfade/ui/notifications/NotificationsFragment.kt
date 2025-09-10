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
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.provider.Settings
import android.app.AppOpsManager
import android.content.Context
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
        // 알림 간격 스피너 설정
        setupIntervalSpinner()
        updateCurrentIntervalText()
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
        if (!hasUsageAccess()) {
            // 사용 기록 접근 권한 요청 안내
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
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
        // 누적 계산: 서비스와 동일 로직
        val sessionStart = prefs.getLong("session_start", -1L)
        val accum = prefs.getLong("accum_ms", 0L)
        val now = System.currentTimeMillis()
        val totalMs = kotlin.math.max(0L, if (sessionStart >= 0) accum + (now - sessionStart) else accum)
        val hours = (totalMs / 3_600_000L).toInt()
        val minutes = ((totalMs % 3_600_000L) / 60_000L).toInt()
        val seconds = ((totalMs % 60_000L) / 1000L).toInt()
        binding.usageTimeText.text = String.format("현재 사용시간: %02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun setupIntervalSpinner() {
        val labels = resources.getStringArray(com.example.firstfade.R.array.interval_labels)
        val values = resources.getStringArray(com.example.firstfade.R.array.interval_values)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.intervalSpinner.adapter = adapter

        val prefs = requireContext().getSharedPreferences("usage_prefs", android.content.Context.MODE_PRIVATE)
        val savedSeconds = prefs.getInt("interval_seconds", 300)
        val index = values.indexOf(savedSeconds.toString()).takeIf { it >= 0 } ?: 4
        binding.intervalSpinner.setSelection(index)

        binding.intervalSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val seconds = values[position].toInt()
                prefs.edit().putInt("interval_seconds", seconds).apply()
                updateCurrentIntervalText()
                if (isServiceRunning()) {
                    // 서비스 재시작으로 새 간격 반영
                    requireContext().stopService(Intent(requireContext(), ReminderForegroundService::class.java))
                    ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), ReminderForegroundService::class.java))
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = requireContext().getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), requireContext().packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun updateCurrentIntervalText() {
        val prefs = requireContext().getSharedPreferences("usage_prefs", android.content.Context.MODE_PRIVATE)
        val seconds = prefs.getInt("interval_seconds", 300)
        val text = when (seconds) {
            10 -> "현재 간격: 10초"
            30 -> "현재 간격: 30초"
            60 -> "현재 간격: 1분"
            120 -> "현재 간격: 2분"
            300 -> "현재 간격: 5분"
            600 -> "현재 간격: 10분"
            900 -> "현재 간격: 15분"
            1800 -> "현재 간격: 30분"
            else -> "현재 간격: ${seconds}초"
        }
        binding.currentIntervalText.text = text
    }

    private fun isDeviceUnlockedAndInteractive(): Boolean {
        val keyguard = requireContext().getSystemService(android.content.Context.KEYGUARD_SERVICE) as KeyguardManager
        val power = requireContext().getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        val unlocked = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) !keyguard.isDeviceLocked else true
        val interactive = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT_WATCH) power.isInteractive else true
        return unlocked && interactive
    }
}