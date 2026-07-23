package com.haitang000.dakamiao

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.haitang000.dakamiao.databinding.ActivityPermissionsBinding

/** 权限管理子页：各权限开关行，点行跳系统设置去开/关。 */
class PermissionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionsBinding

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        with(binding) {
            rowAccessibility.setOnClickListener {
                openSafe(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("请在列表里找到「打卡喵」并开启")
            }
            rowOverlay.setOnClickListener {
                openSafe(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            rowNotification.setOnClickListener { requestNotification() }
            rowAlarm.setOnClickListener { openExactAlarm() }
            rowBattery.setOnClickListener { requestIgnoreBattery() }
            rowAutostart.setOnClickListener { openAutostart() }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() = with(binding) {
        swAccessibility.isChecked = AutoClockAccessibilityService.isConnected()
        swOverlay.isChecked = Settings.canDrawOverlays(this@PermissionsActivity)
        swNotification.isChecked =
            NotificationManagerCompat.from(this@PermissionsActivity).areNotificationsEnabled()
        swAlarm.isChecked = canScheduleExact()
        swBattery.isChecked = isIgnoringBattery()
    }

    private fun requestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openSafe(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }

    private fun openExactAlarm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            openSafe(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:$packageName"))
            )
        } else {
            toast("当前系统无需额外授权精确闹钟")
        }
    }

    private fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } else true

    private fun requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            openSafe(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    private fun isIgnoringBattery(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** 尽力跳到各家 ROM 的自启动/后台管理页；都打不开则退回应用详情页。 */
    private fun openAutostart() {
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
                startActivity(Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                toast("请在此页找到「打卡喵」并允许自启动/后台运行")
                return
            } catch (_: Throwable) {
            }
        }
        openSafe(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
        )
        toast("请在应用详情里找到「自启动/后台运行」等项并允许")
    }

    private fun openSafe(intent: Intent) {
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            toast("无法打开该设置页，请手动前往系统设置操作")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
