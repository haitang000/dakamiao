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
import com.haitang000.dakamiao.databinding.ActivityOnboardingBinding

/**
 * 新手引导：首次进入时逐步带用户完成权限与基本设置。
 * 单页步骤机（不引入额外依赖），每步有说明、去设置按钮和实时状态。
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private var index = 0
    private var ackChecked = false

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    private data class Step(
        val emoji: String,
        val title: String,
        val desc: String,
        val actionLabel: String? = null,
        val action: (() -> Unit)? = null,
        val status: (() -> Boolean)? = null,
        val requireAck: Boolean = false
    )

    private val steps: List<Step> by lazy { buildSteps() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAction.setOnClickListener { steps[index].action?.invoke() }
        binding.btnPrev.setOnClickListener { if (index > 0) { index--; render() } }
        binding.btnNext.setOnClickListener {
            if (index >= steps.lastIndex) finishOnboarding() else { index++; render() }
        }
        binding.cbAck.setOnCheckedChangeListener { _, checked ->
            ackChecked = checked
            if (steps[index].requireAck) binding.btnNext.isEnabled = checked
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        render() // 从系统设置页返回后刷新状态
    }

    private fun render() {
        val step = steps[index]
        binding.tvProgress.text = "${index + 1} / ${steps.size}"
        binding.tvEmoji.text = step.emoji
        binding.tvTitle.text = step.title
        binding.tvDesc.text = step.desc

        if (step.action != null) {
            binding.btnAction.visibility = android.view.View.VISIBLE
            binding.btnAction.text = step.actionLabel ?: "去设置"
        } else {
            binding.btnAction.visibility = android.view.View.GONE
        }

        val status = step.status
        if (status != null) {
            binding.tvStatus.visibility = android.view.View.VISIBLE
            val ok = status()
            binding.tvStatus.text = if (ok) "✅ 已开启" else "❌ 未开启（可点上方按钮）"
            binding.tvStatus.setTextColor(if (ok) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        } else {
            binding.tvStatus.visibility = android.view.View.GONE
        }

        // 免责声明步骤：必须勾选「我已知晓」才能继续
        if (step.requireAck) {
            binding.cbAck.visibility = android.view.View.VISIBLE
            binding.cbAck.isChecked = ackChecked
            binding.btnNext.isEnabled = ackChecked
        } else {
            binding.cbAck.visibility = android.view.View.GONE
            binding.btnNext.isEnabled = true
        }

        binding.btnPrev.visibility = if (index == 0) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnNext.text = if (index >= steps.lastIndex) "完成，进入主界面" else "下一步"
    }

    private fun finishOnboarding() {
        Prefs.setOnboarded(this, true)
        finish()
    }

    // ------------------------------------------------------------------

    private fun buildSteps(): List<Step> = listOf(
        Step(
            emoji = "🐾",
            title = "欢迎使用打卡喵",
            desc = "它能在你设定的时间（或手动一键）自动打开钉钉、找到打卡按钮并点击。\n\n" +
                "本工具不伪造定位、不伪造人脸；遇到外勤、人脸识别等有后果的情况会先停下征求你确认。是否合规请自行判断。\n\n" +
                "下面几步带你完成基本设置，大约 1 分钟。"
        ),
        Step(
            emoji = "⚠️",
            title = "免责声明（请务必阅读）",
            desc = "打卡喵是一个自动化辅助工具，随时可能因钉钉更新、界面改版、网络/定位/系统限制等原因失效或点错，不保证每次成功。\n\n" +
                "• 打卡是否成功，请你每次务必自行到钉钉里核对——不要以为开着就一定打上了。\n" +
                "• 因漏打卡、打卡失败或异常导致的任何后果（考勤扣款、迟到记录等）由你本人承担，与本软件及作者无关。\n" +
                "• 是否使用、是否符合公司规定，均由你自行判断并负责。\n\n" +
                "勾选下方选项代表你已理解并接受以上内容。",
            requireAck = true
        ),
        Step(
            emoji = "🖐️",
            title = "① 开启无障碍服务（核心）",
            desc = "这是打卡喵的核心：靠它读取钉钉界面、模拟点击打卡按钮。不开就无法自动打卡。\n在列表里找到「打卡喵」并打开即可。",
            actionLabel = "去开启无障碍",
            action = { openSafe(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            status = { AutoClockAccessibilityService.isConnected() }
        ),
        Step(
            emoji = "🪟",
            title = "② 开启悬浮窗",
            desc = "用于显示停止按钮、屏幕边缘状态光效，以及外勤/倒计时/报错的确认弹窗。",
            actionLabel = "去开启悬浮窗",
            action = {
                openSafe(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            },
            status = { Settings.canDrawOverlays(this) }
        ),
        Step(
            emoji = "🔔",
            title = "③ 开启通知",
            desc = "用来显示打卡进度、结果，以及锁屏到点时的强提醒。",
            actionLabel = "去开启通知",
            action = { requestNotification() },
            status = { NotificationManagerCompat.from(this).areNotificationsEnabled() }
        ),
        Step(
            emoji = "⏰",
            title = "④ 允许精确闹钟",
            desc = "让定时打卡准点触发，不被系统延迟。",
            actionLabel = "去允许精确闹钟",
            action = { openExactAlarm() },
            status = { canExactAlarm() }
        ),
        Step(
            emoji = "🔋",
            title = "⑤ 忽略电池优化（建议）",
            desc = "防止打卡喵被系统在后台杀掉，导致定时不触发。强烈建议开启。",
            actionLabel = "去设置电池优化",
            action = { requestIgnoreBattery() },
            status = { isIgnoringBattery() }
        ),
        Step(
            emoji = "🧭",
            title = "⑥ 填入推荐打卡步骤",
            desc = "按「消息页 → 滚动到顶 → 顶部『打卡』入口 → 打卡页大按钮」预置好步骤与关键词。\n" +
                "点下方按钮一键填入。之后可在主界面按你公司钉钉的实际按钮文字微调。",
            actionLabel = "一键填入并保存",
            action = {
                Prefs.applyRecommendedDefaults(this)
                toast("已填入推荐步骤，可在主界面再调整")
            }
        ),
        Step(
            emoji = "🎉",
            title = "全部就绪！",
            desc = "回到主界面后：\n• 设置上/下班时间并打开「启用定时自动打卡」\n• 用「立即执行」先手动测一次\n• 右上角菜单可随时重开本引导\n\n祝你天天准时打卡～"
        )
    )

    // ------------------------------------------------------------------

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

    private fun canExactAlarm(): Boolean =
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

    private fun openSafe(intent: Intent) {
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            toast("打不开该设置页，请到系统设置里手动操作")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
