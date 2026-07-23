package com.haitang000.dakamiao

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.haitang000.dakamiao.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadIntoUi()
        setupListeners()

        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_guide) {
                startActivity(Intent(this, OnboardingActivity::class.java))
                true
            } else false
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            showPage(item.itemId)
            true
        }
        showPage(R.id.nav_home)

        if (Prefs.isEnabled(this)) KeepAliveService.start(this)

        if (savedInstanceState == null && !Prefs.isOnboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    private fun showPage(itemId: Int) = with(binding) {
        pageHome.visibility = if (itemId == R.id.nav_home) View.VISIBLE else View.GONE
        pageSchedule.visibility = if (itemId == R.id.nav_schedule) View.VISIBLE else View.GONE
        pageSettings.visibility = if (itemId == R.id.nav_settings) View.VISIBLE else View.GONE
        toolbar.title = when (itemId) {
            R.id.nav_schedule -> "定时"
            R.id.nav_settings -> "设置"
            else -> "打卡喵 🐾"
        }
    }

    private fun loadIntoUi() = with(binding) {
        swEnabled.isChecked = Prefs.isEnabled(this@MainActivity)
        cbWorkday.isChecked = Prefs.isWorkdayOnly(this@MainActivity)
        updateTimeLabels()
    }

    private fun updateTimeLabels() = with(binding) {
        btnOnTime.text = "上班时间：${Prefs.getTime(this@MainActivity, ClockType.ON)}"
        btnOffTime.text = "下班时间：${Prefs.getTime(this@MainActivity, ClockType.OFF)}"
    }

    private fun setupListeners() = with(binding) {
        // —— 定时 ——
        swEnabled.setOnCheckedChangeListener { _, checked ->
            Prefs.setEnabled(this@MainActivity, checked)
            if (checked) {
                AlarmScheduler.rescheduleAll(this@MainActivity)
                KeepAliveService.start(this@MainActivity)
                toast("定时已开启")
                if (!AutoClockAccessibilityService.isConnected()) {
                    toast("提醒：还没开启无障碍服务，到点无法自动打卡")
                }
            } else {
                AlarmScheduler.cancelAll(this@MainActivity)
                KeepAliveService.stop(this@MainActivity)
                toast("定时已关闭")
            }
        }
        cbWorkday.setOnCheckedChangeListener { _, checked ->
            Prefs.setWorkdayOnly(this@MainActivity, checked)
        }
        btnOnTime.setOnClickListener { pickTime(ClockType.ON) }
        btnOffTime.setOnClickListener { pickTime(ClockType.OFF) }

        // —— 设置菜单 ——
        rowPermissions.setOnClickListener {
            startActivity(Intent(this@MainActivity, PermissionsActivity::class.java))
        }
        rowSteps.setOnClickListener {
            startActivity(Intent(this@MainActivity, StepsActivity::class.java))
        }
        rowAbout.setOnClickListener {
            startActivity(Intent(this@MainActivity, AboutActivity::class.java))
        }

        // —— 手动 / 测试 ——
        btnTestOn.setOnClickListener { triggerNow(ClockType.ON) }
        btnTestOff.setOnClickListener { triggerNow(ClockType.OFF) }
        btnStop.setOnClickListener {
            val svc = AutoClockAccessibilityService.instance
            if (svc == null || !svc.isRunning) {
                toast("当前没有正在进行的打卡")
            } else {
                svc.requestStop("已手动停止")
            }
        }
        btnDump.setOnClickListener {
            val svc = AutoClockAccessibilityService.instance
            if (svc == null) {
                toast("请先开启无障碍服务")
                openSafe(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                toast("4 秒后抓取，请立刻切到钉钉打卡页面")
                svc.scheduleDump(4000)
            }
        }
    }

    private fun pickTime(type: ClockType) {
        val current = Prefs.getTime(this, type).split(":")
        val hour = current.getOrNull(0)?.toIntOrNull() ?: 9
        val minute = current.getOrNull(1)?.toIntOrNull() ?: 0
        TimePickerDialog(this, { _, h, m ->
            Prefs.setTime(this, type, "%02d:%02d".format(h, m))
            updateTimeLabels()
            if (Prefs.isEnabled(this)) AlarmScheduler.scheduleType(this, type)
        }, hour, minute, true).show()
    }

    private fun triggerNow(type: ClockType) {
        val svc = AutoClockAccessibilityService.instance
        if (svc == null) {
            toast("请先开启无障碍服务")
            openSafe(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        toast("开始${type.label}打卡，可随时用悬浮按钮/音量下键停止")
        svc.startClockIn(type)
    }

    private fun openSafe(intent: Intent) {
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            toast("无法打开该页面")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
