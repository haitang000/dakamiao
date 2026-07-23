package com.haitang000.dakamiao

import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.haitang000.dakamiao.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshStatuses()
        }

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

        // 定时已开启则确保保活服务在跑
        if (Prefs.isEnabled(this)) KeepAliveService.start(this)

        // 首次启动：拉起新手引导
        if (savedInstanceState == null && !Prefs.isOnboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    // ------------------------------------------------------------------
    // 初始化 UI
    // ------------------------------------------------------------------

    private fun loadIntoUi() = with(binding) {
        swEnabled.isChecked = Prefs.isEnabled(this@MainActivity)
        cbWorkday.isChecked = Prefs.isWorkdayOnly(this@MainActivity)
        cbOcr.isChecked = Prefs.isOcrEnabled(this@MainActivity)
        cbKill.isChecked = Prefs.isKillBefore(this@MainActivity)
        updateTimeLabels()
        etOnSteps.setText(Prefs.getStepsRaw(this@MainActivity, ClockType.ON))
        etOffSteps.setText(Prefs.getStepsRaw(this@MainActivity, ClockType.OFF))
        etSuccess.setText(Prefs.getSuccessRaw(this@MainActivity))
        etFace.setText(Prefs.getFaceRaw(this@MainActivity))
        etConfirm.setText(Prefs.getConfirmRaw(this@MainActivity))
        etFail.setText(Prefs.getFailRaw(this@MainActivity))
    }

    private fun showPage(itemId: Int) = with(binding) {
        pageHome.visibility = if (itemId == R.id.nav_home) View.VISIBLE else View.GONE
        pageSchedule.visibility = if (itemId == R.id.nav_schedule) View.VISIBLE else View.GONE
        pageSettings.visibility = if (itemId == R.id.nav_settings) View.VISIBLE else View.GONE
        pagePermissions.visibility = if (itemId == R.id.nav_perm) View.VISIBLE else View.GONE
        toolbar.title = when (itemId) {
            R.id.nav_schedule -> "定时"
            R.id.nav_settings -> "设置"
            R.id.nav_perm -> "权限"
            else -> "打卡喵 🐾"
        }
    }

    private fun updateTimeLabels() = with(binding) {
        btnOnTime.text = "上班时间：${Prefs.getTime(this@MainActivity, ClockType.ON)}"
        btnOffTime.text = "下班时间：${Prefs.getTime(this@MainActivity, ClockType.OFF)}"
    }

    private fun setupListeners() = with(binding) {
        // —— 权限 ——
        btnAccessibility.setOnClickListener {
            startActivitySafe(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("请在列表里找到「打卡喵」并开启")
        }
        btnOverlay.setOnClickListener {
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivitySafe(i)
        }
        btnNotification.setOnClickListener { requestNotificationPermission() }
        btnExactAlarm.setOnClickListener { openExactAlarmSettings() }
        btnBattery.setOnClickListener { requestIgnoreBattery() }
        btnAutostart.setOnClickListener { openAutostartSettings() }

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
        cbOcr.setOnCheckedChangeListener { _, checked ->
            Prefs.setOcrEnabled(this@MainActivity, checked)
        }
        cbKill.setOnCheckedChangeListener { _, checked ->
            Prefs.setKillBefore(this@MainActivity, checked)
        }
        btnOnTime.setOnClickListener { pickTime(ClockType.ON) }
        btnOffTime.setOnClickListener { pickTime(ClockType.OFF) }

        // —— 步骤/关键词 ——
        btnResetSteps.setOnClickListener {
            etOnSteps.setText(Prefs.DEFAULT_ON_STEPS)
            etOffSteps.setText(Prefs.DEFAULT_OFF_STEPS)
            etSuccess.setText(Prefs.DEFAULT_SUCCESS)
            etFace.setText(Prefs.DEFAULT_FACE)
            etConfirm.setText(Prefs.DEFAULT_CONFIRM)
            etFail.setText(Prefs.DEFAULT_FAIL)
            toast("已填入推荐值，检查后点「保存」")
        }
        btnSaveSteps.setOnClickListener {
            Prefs.setStepsRaw(this@MainActivity, ClockType.ON, etOnSteps.text.toString())
            Prefs.setStepsRaw(this@MainActivity, ClockType.OFF, etOffSteps.text.toString())
            Prefs.setSuccessRaw(this@MainActivity, etSuccess.text.toString())
            Prefs.setFaceRaw(this@MainActivity, etFace.text.toString())
            Prefs.setConfirmRaw(this@MainActivity, etConfirm.text.toString())
            Prefs.setFailRaw(this@MainActivity, etFail.text.toString())
            toast("已保存")
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
                startActivitySafe(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                toast("4 秒后抓取，请立刻切到钉钉打卡页面")
                svc.scheduleDump(4000)
            }
        }
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    private fun pickTime(type: ClockType) {
        val current = Prefs.getTime(this, type).split(":")
        val hour = current.getOrNull(0)?.toIntOrNull() ?: 9
        val minute = current.getOrNull(1)?.toIntOrNull() ?: 0
        TimePickerDialog(this, { _, h, m ->
            val value = "%02d:%02d".format(h, m)
            Prefs.setTime(this, type, value)
            updateTimeLabels()
            if (Prefs.isEnabled(this)) AlarmScheduler.scheduleType(this, type)
        }, hour, minute, true).show()
    }

    private fun triggerNow(type: ClockType) {
        val svc = AutoClockAccessibilityService.instance
        if (svc == null) {
            toast("请先开启无障碍服务")
            startActivitySafe(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        // 保存当前编辑框内容，确保测试用的是最新配置
        Prefs.setStepsRaw(this, ClockType.ON, binding.etOnSteps.text.toString())
        Prefs.setStepsRaw(this, ClockType.OFF, binding.etOffSteps.text.toString())
        Prefs.setSuccessRaw(this, binding.etSuccess.text.toString())
        Prefs.setFaceRaw(this, binding.etFace.text.toString())
        Prefs.setConfirmRaw(this, binding.etConfirm.text.toString())
        Prefs.setFailRaw(this, binding.etFail.text.toString())

        toast("开始${type.label}打卡，可随时用悬浮按钮/音量下键停止")
        svc.startClockIn(type)
    }

    // ------------------------------------------------------------------
    // 权限入口
    // ------------------------------------------------------------------

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // 低版本无运行时通知权限，跳到应用通知设置页
            val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            startActivitySafe(i)
        }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val i = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:$packageName"))
            startActivitySafe(i)
        } else {
            toast("当前系统无需额外授权精确闹钟")
        }
    }

    private fun requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            startActivitySafe(i)
        }
    }

    /** 尽力跳到各家 ROM 的自启动/后台管理页；都打不开则退回应用详情页。 */
    private fun openAutostartSettings() {
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
        )
        for ((pkg, cls) in candidates) {
            try {
                startActivity(
                    Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                toast("请在此页找到「打卡喵」并允许自启动/后台运行")
                return
            } catch (_: Throwable) {
                // 该机型没有这个页面，试下一个
            }
        }
        // 兜底：应用详情页
        startActivitySafe(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
        )
        toast("请在应用详情里找到「自启动/后台运行」等项并允许")
    }

    // ------------------------------------------------------------------
    // 状态刷新
    // ------------------------------------------------------------------

    private fun refreshStatuses() = with(binding) {
        setStatus(tvAccessibilityStatus, AutoClockAccessibilityService.isConnected())
        setStatus(tvOverlayStatus, Settings.canDrawOverlays(this@MainActivity))
        setStatus(
            tvNotificationStatus,
            NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled()
        )
        setStatus(tvAlarmStatus, canScheduleExact())
    }

    private fun canScheduleExact(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else true
    }

    private fun setStatus(view: android.widget.TextView, ok: Boolean) {
        if (ok) {
            view.text = "✅ 已开启"
            view.setTextColor(0xFF2E7D32.toInt())
        } else {
            view.text = "❌ 未开启"
            view.setTextColor(0xFFC62828.toInt())
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun startActivitySafe(intent: Intent) {
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            toast("无法打开该设置页，请手动前往系统设置操作")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
