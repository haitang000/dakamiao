package com.haitang000.dakamiao

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 打卡喵核心：无障碍服务。
 *
 * 职责：
 *  1. 被手动按钮或定时闹钟触发后，打开钉钉；
 *  2. 按用户配置的「导航步骤」逐个在界面里找按钮并点击；
 *  3. 点完最后一步后识别结果——成功 / 需要人脸拍照 / 未知；
 *  4. 全程提供多重中断：悬浮停止按钮、通知栏停止、音量下键、以及每步之间的停止标志检查。
 *
 * 不做的事：不伪造定位、不伪造人脸。遇到人脸/拍照界面立即停止并提醒用户本人接管。
 */
class AutoClockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DaKaMiao"
        const val DINGTALK_PKG = "com.alibaba.android.rimet"

        const val CHANNEL_ID = "clockin_channel"
        const val NOTIF_RUNNING = 1001
        const val NOTIF_RESULT = 1002
        const val NOTIF_ALERT = 1003

        // 锁屏时到点先挂起，等用户解锁后再打卡的有效期
        private const val PENDING_VALID_MS = 15 * 60 * 1000L

        /** 运行中的服务单例；为空表示无障碍服务未开启。 */
        @Volatile
        var instance: AutoClockAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    @Volatile
    private var shouldStop = false

    private var overlayView: View? = null
    private var borderView: BorderMarqueeView? = null
    private var errorDialog: AlertDialog? = null
    private var confirmDialog: AlertDialog? = null
    private var countdownDialog: AlertDialog? = null

    @Volatile
    private var borderBlocked = false

    private var windowManager: WindowManager? = null

    // 锁屏挂起：到点时若锁屏，记下类型与有效期，等用户解锁后自动打卡
    private var pendingType: ClockType? = null
    private var pendingDeadline = 0L
    private var userPresentRegistered = false
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) onUserUnlocked()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
        registerUserPresent()
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onDestroy() {
        super.onDestroy()
        shouldStop = true
        removeOverlay()
        removeBorder()
        dismissErrorPopup()
        confirmDialog?.let { runCatching { it.dismiss() } }
        countdownDialog?.let { runCatching { it.dismiss() } }
        if (userPresentRegistered) runCatching { unregisterReceiver(userPresentReceiver) }
        userPresentRegistered = false
        instance = null
        Log.i(TAG, "无障碍服务已断开")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不依赖事件流，改为在执行时主动轮询 rootInActiveWindow，逻辑更可控。
    }

    override fun onInterrupt() {
        // 系统要求中断无障碍反馈时回调；这里没有持续反馈，忽略。
    }

    /** 音量下键 = 紧急停止（仅在打卡执行中拦截，不影响平时调音量）。 */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (running &&
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.action == KeyEvent.ACTION_DOWN
        ) {
            requestStop("已通过音量键停止")
            return true // 消费该事件
        }
        return super.onKeyEvent(event)
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    val isRunning: Boolean get() = running

    /** 请求停止当前打卡流程。任何来源（悬浮按钮/通知/音量键/界面）都走这里。 */
    fun requestStop(reason: String = "已停止") {
        if (!running) return
        shouldStop = true
        toast(reason)
    }

    /**
     * 定时闹钟到点的总入口。
     * - 屏幕已亮且已解锁：直接进 5 秒倒计时打卡。
     * - 锁屏 / 息屏：点亮屏幕 + 全屏强提醒，并把这次打卡挂起；等用户解锁（ACTION_USER_PRESENT）后自动打卡。
     *   （安全锁无法被任何 App 自动解锁，这是安卓限制，所以只能等你解锁那一下。）
     */
    fun handleScheduledTrigger(type: ClockType) {
        if (running) return
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val locked = km?.isKeyguardLocked == true
        val interactive = pm?.isInteractive == true

        if (!locked && interactive) {
            startClockInWithCountdown(type)
            return
        }

        // 锁屏或息屏：点亮 + 强提醒；挂起，等解锁
        wakeScreenBriefly()
        if (locked) {
            pendingType = type
            pendingDeadline = System.currentTimeMillis() + PENDING_VALID_MS
            postLockedAlert(type)
        } else {
            // 息屏但无锁屏：亮屏后稍等直接打卡
            mainHandler.postDelayed({ startClockInWithCountdown(type) }, 1500)
        }
    }

    private fun onUserUnlocked() {
        val type = pendingType ?: return
        if (System.currentTimeMillis() > pendingDeadline) {
            pendingType = null
            return
        }
        pendingType = null
        NotificationManagerCompat.from(this).cancel(NOTIF_ALERT)
        // 解锁动画/桌面稳定后再开始
        mainHandler.postDelayed({ startClockInWithCountdown(type) }, 1200)
    }

    private fun registerUserPresent() {
        if (userPresentRegistered) return
        try {
            ContextCompat.registerReceiver(
                this,
                userPresentReceiver,
                IntentFilter(Intent.ACTION_USER_PRESENT),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            userPresentRegistered = true
        } catch (t: Throwable) {
            Log.e(TAG, "注册解锁广播失败", t)
        }
    }

    /** 点亮屏幕（安全锁下仍停在锁屏，但屏幕会亮，配合全屏通知促使用户解锁）。 */
    private fun wakeScreenBriefly() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "DaKaMiao:wake"
            )
            wl.acquire(8000)
        } catch (t: Throwable) {
            Log.e(TAG, "点亮屏幕失败", t)
        }
    }

    private fun postLockedAlert(type: ClockType) {
        val fullScreen = PendingIntent.getActivity(
            this, 5,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⏰ 到点${type.label}打卡")
            .setContentText("请解锁手机，解锁后会自动开始打卡")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreen, true)
            .setVibrate(longArrayOf(0, 500, 300, 500))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(NOTIF_ALERT, n)
    }

    /**
     * 定时到点用：先弹 5 秒倒计时确认窗。
     * 5 秒内无操作 → 自动开始；点「立即打卡」→ 马上开始；点「取消」→ 跳过本次。
     */
    fun startClockInWithCountdown(type: ClockType, seconds: Int = 5) {
        if (running) return
        mainHandler.post { showCountdownDialog(type, seconds) }
    }

    private fun showCountdownDialog(type: ClockType, seconds: Int) {
        // 无悬浮窗权限弹不出倒计时——定时是用户主动配置的，直接开始
        if (!Settings.canDrawOverlays(this)) {
            startClockIn(type)
            return
        }
        countdownDialog?.let { runCatching { it.dismiss() } }

        val ctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
        var remaining = seconds
        var decided = false
        fun msg() = "${remaining} 秒后自动开始${type.label}打卡。\n点「取消」可跳过本次。"

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("⏰ 即将自动${type.label}打卡")
            .setMessage(msg())
            .setCancelable(false)
            .setPositiveButton("立即打卡") { _, _ ->
                if (!decided) { decided = true; startClockIn(type) }
            }
            .setNegativeButton("取消") { _, _ ->
                if (!decided) { decided = true; notifySkipped(type) }
            }
            .create()

        val windowType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        dialog.window?.setType(windowType)

        val ticker = object : Runnable {
            override fun run() {
                if (decided) return
                remaining--
                if (remaining <= 0) {
                    decided = true
                    runCatching { dialog.dismiss() }
                    startClockIn(type)
                } else {
                    dialog.setMessage(msg())
                    mainHandler.postDelayed(this, 1000)
                }
            }
        }
        dialog.setOnDismissListener {
            mainHandler.removeCallbacks(ticker)
            if (countdownDialog === dialog) countdownDialog = null
        }

        try {
            dialog.show()
            countdownDialog = dialog
            mainHandler.postDelayed(ticker, 1000)
        } catch (t: Throwable) {
            Log.e(TAG, "显示倒计时失败，直接开始", t)
            startClockIn(type)
        }
    }

    private fun notifySkipped(type: ClockType) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⏭ 已跳过本次${type.label}打卡")
            .setContentText("你在倒计时里点了取消。")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(NOTIF_RESULT, n)
    }

    /** 开始一次打卡流程。若已有流程在跑则忽略，避免并发。 */
    fun startClockIn(type: ClockType) {
        if (running) {
            toast("正在打卡中，请稍候…")
            return
        }
        running = true
        shouldStop = false
        borderBlocked = false
        showBorder()
        showOverlay()
        showRunningNotification(type)

        Thread({
            val wl = acquireWakeLock()
            val result = try {
                runSequence(type)
            } catch (t: Throwable) {
                Log.e(TAG, "打卡流程异常", t)
                RunResult(RunStatus.ERROR, "内部异常：\n" + Log.getStackTraceString(t))
            } finally {
                wl?.let { if (it.isHeld) runCatching { it.release() } }
            }
            finish(type, result)
        }, "dakamiao-runner").start()
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    private enum class RunStatus { SUCCESS, NEED_FACE, STOPPED, APP_NOT_FOUND, STEP_FAILED, UNKNOWN, ERROR }
    private data class RunResult(val status: RunStatus, val detail: String)

    private fun runSequence(type: ClockType): RunResult {
        // 0) 可选：先退出钉钉后台，让下面冷启动到干净的消息页
        val killed = Prefs.isKillBefore(this)
        if (killed) {
            killDingTalk()
            if (!sleepChecked(1200)) return stopped()
        }

        // 1) 打开钉钉
        if (!launchDingTalk()) {
            return RunResult(RunStatus.APP_NOT_FOUND, "未找到钉钉，请确认已安装")
        }
        // 冷启动渲染更慢，多等一会儿
        val launchWait = Prefs.getLaunchWaitMs(this) + if (killed) 2500L else 0L
        if (!sleepChecked(launchWait)) return stopped()

        // 2) 逐步导航并点击
        val steps = Prefs.getSteps(this, type)
        val successKw = Prefs.getSuccessKeywords(this)
        val confirmKw = Prefs.getConfirmKeywords(this)
        val stepTimeout = Prefs.getStepTimeoutMs(this)

        if (steps.isEmpty()) {
            return RunResult(RunStatus.STEP_FAILED, "未配置任何打卡步骤")
        }

        // 外勤等「需确认」只在最后一个真正点击的步骤（即打卡界面上的打卡按钮）判断，导航途中不查
        val lastClickIndex = steps.indices.lastOrNull {
            !isScrollTopDirective(steps[it]) && !isBackHomeDirective(steps[it])
        } ?: -1

        for ((index, keyword) in steps.withIndex()) {
            if (shouldStop) return stopped()

            // 动作指令：滚动到顶部（用于消息页顶部导航栏的打卡入口）
            if (isScrollTopDirective(keyword)) {
                updateBorder(false)
                scrollToTop()
                if (!sleepChecked(600)) return stopped()
                continue
            }

            // 动作指令：按返回键退出聊天/子页面，回到有底部标签栏的主界面
            if (isBackHomeDirective(keyword)) {
                updateBorder(false)
                backToHome()
                if (!sleepChecked(600)) return stopped()
                continue
            }

            // = 开头表示精确匹配（只点文字完全相等的节点），用于「打卡」这种在消息里也常出现的短词
            val exact = keyword.startsWith("=")
            val kw = if (exact) keyword.removePrefix("=").trim() else keyword
            // 仅打卡界面那步（最后一个点击步）才做外勤确认，导航步传空
            val stepConfirm = if (index == lastClickIndex) confirmKw else emptyList()
            val clicked = findAndClick(kw, exact, stepConfirm, stepTimeout)
            if (!clicked) {
                if (shouldStop) return stopped()
                // 没找到这一步的按钮：也许已经打过卡了？先看屏幕上有没有成功字样。
                if (scanForAny(successKw)) {
                    return RunResult(RunStatus.SUCCESS, "检测到已打卡（未找到「$kw」但界面显示已完成）")
                }
                return RunResult(
                    RunStatus.STEP_FAILED,
                    "第 ${index + 1} 步未找到「$kw」。请在设置里核对你钉钉里的实际按钮文案。"
                )
            }
            // 每步之间留出界面切换时间
            if (!sleepChecked(1500)) return stopped()
        }

        // 3) 点完最后一步，判定结果
        return detectResult()
    }

    /**
     * 点完最后的打卡按钮后，在若干秒内轮询判断结果。
     * 优先级：拦截/失败（明确未成功）> 人脸（交还本人）> 成功。都没有则「未能确认」。
     */
    private fun detectResult(): RunResult {
        val failKw = Prefs.getFailKeywords(this)
        val faceKw = Prefs.getFaceKeywords(this)
        val successKw = Prefs.getSuccessKeywords(this)

        // 点击后先给结果弹窗一点出现时间
        if (!sleepChecked(1500)) return stopped()

        // 成功弹窗（如「下班打卡成功」）常在点击后数秒才出现，甚至先经过「定位中/打卡中」。
        // 常规等 15s；若还在加载则续期，最长等 25s。
        val start = System.currentTimeMillis()
        val hardDeadline = start + 25_000L
        var softDeadline = start + 15_000L
        // 成功文字需持续存在这么久才判成功——避免残留的历史「打卡成功」让我们抢在拦截框弹出前误判
        val successConfirmMs = 6000L
        var successSince = 0L

        while (true) {
            val now = System.currentTimeMillis()
            if (shouldStop) return stopped()
            if (now >= hardDeadline || now >= softDeadline) break

            // 最优先：拦截/失败（如「公司不允许使用虚拟定位软件」）随时出现随时判失败，绝不算成功
            val blocked = findTextContaining(failKw)
            if (blocked != null) {
                return RunResult(
                    RunStatus.STEP_FAILED,
                    "打卡未成功，检测到拦截/失败提示：\n“$blocked”\n请打开钉钉手动处理。"
                )
            }
            if (scanForAny(faceKw)) {
                return RunResult(RunStatus.NEED_FACE, "检测到人脸识别界面，请你本人完成验证")
            }

            // 成功需持续确认期，期间若冒出拦截框会被上面的失败判定压过
            if (scanForAny(successKw)) {
                if (successSince == 0L) successSince = now
                if (now - successSince >= successConfirmMs) {
                    return RunResult(RunStatus.SUCCESS, "打卡成功")
                }
            } else {
                successSince = 0L
            }

            // 还在定位/打卡处理中 → 续期，继续等结果
            val root = rootInActiveWindow
            if (root != null && isPageLoading(root)) {
                softDeadline = minOf(now + 15_000L, hardDeadline)
            }
            if (!sleepChecked(500)) return stopped()
        }
        // 到点：全程见到成功文字且始终没冒出拦截/失败 → 判成功；否则未能确认
        if (successSince != 0L) return RunResult(RunStatus.SUCCESS, "打卡成功")
        return RunResult(
            RunStatus.UNKNOWN,
            "已点击打卡按钮，但未能确认结果，请打开钉钉核对一下。"
        )
    }

    /** 返回第一处包含任一关键词的节点文字（截断 80 字），没有则 null。用于把拦截提示原文显示给用户。 */
    private fun findTextContaining(keywords: List<String>): String? {
        if (keywords.isEmpty()) return null
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hay = when {
                keywords.any { text.contains(it) } -> text
                keywords.any { desc.contains(it) } -> desc
                else -> null
            }
            if (hay != null) return hay.take(80)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun stopped() = RunResult(RunStatus.STOPPED, "已中断")

    // ------------------------------------------------------------------
    // 打开钉钉
    // ------------------------------------------------------------------

    /** 退出钉钉后台进程（非 root，best-effort）。若钉钉正在前台则无效，但通常触发时它在后台。 */
    private fun killDingTalk() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(DINGTALK_PKG)
            Log.i(TAG, "已请求退出钉钉后台")
        } catch (t: Throwable) {
            Log.e(TAG, "退出钉钉后台失败", t)
        }
    }

    private fun launchDingTalk(): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(DINGTALK_PKG) ?: return false
        // CLEAR_TOP 尽量让钉钉回到主活动而非停在上次的聊天页（不同版本效果不一，配合 @回到主页 兜底）
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return try {
            startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "启动钉钉失败", t)
            false
        }
    }

    // ------------------------------------------------------------------
    // 节点查找 / 点击
    // ------------------------------------------------------------------

    /**
     * 在超时时间内反复查找包含 keyword 的可点节点并点击。
     * 顺序：无障碍文字匹配（快） → 滚动一次露出折叠内容 → OCR 兜底（按钮是图片时）。
     * 若发现「需确认」按钮（如「外勤打卡」），会先暂停弹窗让用户确认，取消则中断整个流程。
     */
    private fun findAndClick(
        keyword: String,
        exact: Boolean,
        confirmKeywords: List<String>,
        timeoutMs: Long
    ): Boolean {
        val start = System.currentTimeMillis()
        // 硬上限：即使一直在加载也不无限等；软超时：页面已加载好却找不到按钮时的正常放弃点
        val hardDeadline = start + maxOf(timeoutMs, 45_000L)
        var softDeadline = start + timeoutMs
        updateBorder(false)
        val graceUntil = start + 2500
        val ocrEnabled = Prefs.isOcrEnabled(this) && ScreenTextLocator.isSupported
        var scrolledOnce = false
        var lastOcr = 0L
        var confirmed = false

        while (true) {
            val now = System.currentTimeMillis()
            if (shouldStop) return false
            if (now >= hardDeadline || now >= softDeadline) break

            var loading = false
            val root = rootInActiveWindow
            if (root != null) {
                // 优先检查「需确认」按钮（如外勤打卡）：命中说明这一下有后果，必须先问用户
                val confirmNode = findClickableForAny(root, confirmKeywords)
                if (confirmNode != null) {
                    val ca = clickableAncestor(confirmNode)
                    val enabled = ca?.isEnabled ?: confirmNode.isEnabled
                    if (enabled) {
                        if (!confirmed) {
                            updateBorder(true)
                            val label = (confirmNode.text ?: confirmNode.contentDescription)
                                ?.toString()?.trim().orEmpty()
                            val ok = requestUserConfirm(
                                if (label.isNotEmpty()) "检测到「$label」，这一下不在正常考勤范围内，确定要打卡吗？"
                                else "检测到需要确认的打卡（如外勤），确定要继续吗？"
                            )
                            if (!ok) {
                                requestStop("已取消：需确认的打卡")
                                return false
                            }
                            confirmed = true
                        }
                        updateBorder(false)
                        if (clickNode(confirmNode)) return true
                    }
                }

                val node = findNodeByText(root, keyword, exact)
                if (node != null) {
                    // 命中可点击控件但仍禁用（常见于「定位中」打卡按钮不可点）：先不点，等它可用。
                    // 无可点击祖先时（自绘按钮）用手势兜底。
                    val clickable = clickableAncestor(node)
                    val enabled = clickable?.isEnabled ?: node.isEnabled
                    if (enabled && clickNode(node)) {
                        updateBorder(false)
                        return true
                    }
                }

                // 页面还在加载（有加载/定位字样，或几乎没内容）→ 续期软超时，不判失败
                loading = isPageLoading(root)
                if (loading) softDeadline = minOf(now + timeoutMs, hardDeadline)

                // 不是加载态且没找到时，尝试滚动一次露出折叠内容（如工作台里靠下的「考勤打卡」）
                if (node == null && !loading && !scrolledOnce) {
                    scrolledOnce = scrollForward(root)
                }
            }

            // OCR 兜底：无障碍读不到（按钮是图片）时截屏识别定位。加载中或精确匹配步骤跳过。
            if (ocrEnabled && !exact && !loading && now - lastOcr > 1300) {
                lastOcr = now
                val rect = ScreenTextLocator.findText(this, keyword)
                if (shouldStop) return false
                if (rect != null && gestureClickRect(rect)) {
                    Log.i(TAG, "OCR 兜底命中并点击「$keyword」")
                    updateBorder(false)
                    return true
                }
            }

            // 边框：加载中=蓝（还在正常等待）；已加载却找不到超过 grace=红（受阻）
            if (loading) updateBorder(false)
            else if (System.currentTimeMillis() > graceUntil) updateBorder(true)

            if (!sleepChecked(400)) return false
        }
        return false
    }

    private val loadingKeywords = listOf(
        "加载中", "正在加载", "加载失败", "请稍候", "请稍侯", "loading",
        "定位中", "正在定位", "获取位置", "位置获取中", "刷新中"
    )

    /** 判断当前页面是否还在加载：出现加载/定位字样，或有文字的节点极少（页面还没渲染出来）。 */
    private fun isPageLoading(root: AccessibilityNodeInfo): Boolean {
        var textNodes = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() || d.isNotEmpty()) {
                textNodes++
                val hay = t + d
                if (loadingKeywords.any { hay.contains(it, ignoreCase = true) }) return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        // 有文字的节点太少，说明打卡页内容还没渲染（如整屏黑只剩底部标签）
        return textNodes < 8
    }

    /**
     * 遍历整棵树，给所有匹配候选打分，返回最优：完全相等 > 可点击 > 已启用 > 多余文字少 > 越靠上。
     * exact=true 时只接受「文字完全相等」的节点（用于「打卡」这种在消息里也常出现的短词，
     * 避免误点进"考勤打卡""工作通知"这类会话）。
     */
    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        keyword: String,
        exact: Boolean
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        val rect = Rect()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val equals = text == keyword || desc == keyword
            val matched = if (exact) equals else (text.contains(keyword) || desc.contains(keyword))
            if (matched) {
                val hay = if (text.contains(keyword)) text else desc
                var score = 0
                if (equals) score += 1000                                   // 完全相等最优
                if (clickableAncestor(node) != null) score += 300           // 可点击优先
                if (node.isEnabled) score += 50
                score -= (hay.length - keyword.length).coerceAtLeast(0) * 5  // 多余文字越多越差
                node.getBoundsInScreen(rect)
                score -= rect.top / 20                                       // 越靠屏幕上方越优先（导航栏在顶部）
                if (score > bestScore) {
                    bestScore = score
                    best = node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
    }

    /** 屏幕上是否出现任一关键词。 */
    private fun scanForAny(keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (keywords.any { text.contains(it) || desc.contains(it) }) return true
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    /** 找到第一个「文字/描述含任一关键词」且可点击（自身或祖先可点）的节点。 */
    private fun findClickableForAny(
        root: AccessibilityNodeInfo,
        keywords: List<String>
    ): AccessibilityNodeInfo? {
        if (keywords.isEmpty()) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (keywords.any { text.contains(it) || desc.contains(it) } &&
                clickableAncestor(node) != null
            ) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** 返回自身或最近的可点击祖先；没有则 null。 */
    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable) return n
            n = n.parent
        }
        return null
    }

    /** 点击节点：优先点自身或最近的可点祖先；都不行则在节点中心派发一次点击手势。 */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable) {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                break
            }
            n = n.parent
        }
        return gestureClick(node)
    }

    private fun gestureClick(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return gestureClickRect(rect)
    }

    /** 在给定屏幕矩形中心派发一次点击手势（供 OCR 命中的坐标使用）。 */
    private fun gestureClickRect(rect: Rect): Boolean {
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, mainHandler)
    }

    /** 找到第一个可滚动节点并向前滚动，用于露出屏幕外的按钮。 */
    private fun scrollForward(root: AccessibilityNodeInfo): Boolean {
        val scroller = findScrollable(root) ?: return false
        return scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /**
     * 用无障碍动作把列表滚到顶部。用 ACTION_SCROLL_BACKWARD，到顶会自然返回 false 停止——
     * 不会像手指下拉那样过度回弹触发「更多应用」面板。
     */
    private fun scrollToTop(maxTimes: Int = 10) {
        for (i in 0 until maxTimes) {
            if (shouldStop) return
            val root = rootInActiveWindow ?: return
            val scroller = findScrollable(root) ?: return
            if (!scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return
            if (!sleepChecked(350)) return
        }
    }

    /** step 是否为「滚动到顶部」动作指令（@开头且含"顶"，或明确写"滚动到顶部/滑到顶部"）。 */
    private fun isScrollTopDirective(step: String): Boolean {
        val s = step.trim()
        if (s.startsWith("@")) {
            val cmd = s.removePrefix("@").lowercase()
            return cmd.contains("顶") || cmd.contains("top")
        }
        return s == "滚动到顶部" || s == "滑到顶部" || s == "上滑到顶"
    }

    /** step 是否为「回到主页」动作指令（@开头且含 主页/首页/回到/back）。 */
    private fun isBackHomeDirective(step: String): Boolean {
        val s = step.trim()
        if (!s.startsWith("@")) return false
        val cmd = s.removePrefix("@").lowercase()
        return cmd.contains("主页") || cmd.contains("首页") || cmd.contains("回到") || cmd.contains("back")
    }

    /**
     * 按返回键退出聊天/子页面，直到回到有底部标签栏（「工作台」可见）的主界面。
     * 最多退 maxTries 次；若已在主页则一次都不退；若退到已不在钉钉则立即停止，避免退出钉钉。
     */
    private fun backToHome(maxTries: Int = 6) {
        for (i in 0 until maxTries) {
            if (shouldStop) return
            val root = rootInActiveWindow
            if (root != null) {
                val pkg = root.packageName?.toString()
                if (pkg != null && pkg != DINGTALK_PKG) return // 已不在钉钉，别再退
                if (isOnMainPage(root)) return                 // 已回到主页
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            if (!sleepChecked(700)) return
        }
    }

    /** 主界面判定：能看到底部标签「工作台/工作」即视为在主界面（聊天详情页没有它）。 */
    private fun isOnMainPage(root: AccessibilityNodeInfo): Boolean =
        hasExactText(root, listOf("工作台", "工作"))

    private fun hasExactText(root: AccessibilityNodeInfo, texts: List<String>): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val t = node.text?.toString()?.trim()
            val d = node.contentDescription?.toString()?.trim()
            if (t in texts || d in texts) return true
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    // ------------------------------------------------------------------
    // 收尾
    // ------------------------------------------------------------------

    private fun finish(type: ClockType, result: RunResult) {
        // 失败/异常/未确认：红色跑马灯 + 提示音明确警示，短暂显现后再收尾
        val failed = result.status == RunStatus.STEP_FAILED ||
            result.status == RunStatus.ERROR ||
            result.status == RunStatus.APP_NOT_FOUND ||
            result.status == RunStatus.UNKNOWN
        if (failed) {
            val wasBlocked = borderBlocked
            updateBorder(true)                 // 转红（原本蓝会顺带响一次提示音）
            if (wasBlocked) playBlockedTone()  // 原本已红没触发，这里补一次，保证失败必响
            sleepChecked(1600)
        }

        removeOverlay()
        removeBorder()
        running = false
        shouldStop = false
        showResultNotification(type, result)
        // 失败/异常/未确认时，用悬浮弹窗把具体报错内容显示出来，方便当场排查
        when (result.status) {
            RunStatus.APP_NOT_FOUND, RunStatus.STEP_FAILED, RunStatus.UNKNOWN, RunStatus.ERROR ->
                showOverlayDialog(errorTitle(type, result.status), result.detail)
            else -> {}
        }
        Log.i(TAG, "打卡结束[${type.label}] -> ${result.status} ${result.detail}")
    }

    private fun errorTitle(type: ClockType, status: RunStatus): String = when (status) {
        RunStatus.APP_NOT_FOUND -> "❌ 打不开钉钉"
        RunStatus.STEP_FAILED -> "❌ ${type.label}打卡失败"
        RunStatus.UNKNOWN -> "❓ 未能确认结果"
        RunStatus.ERROR -> "⚠️ 出错了"
        else -> "提示"
    }

    // ------------------------------------------------------------------
    // 悬浮「停止」按钮
    // ------------------------------------------------------------------

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return // 未授悬浮窗权限则跳过，靠通知/音量键兜底
        mainHandler.post {
            if (overlayView != null) return@post
            val btn = Button(this).apply {
                text = "■ 停止打卡"
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFFD32F2F.toInt())
                setPadding(36, 20, 36, 20)
                setOnClickListener { requestStop("已通过悬浮按钮停止") }
            }
            val type =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 24
                y = 160
            }
            try {
                windowManager?.addView(btn, params)
                overlayView = btn
            } catch (t: Throwable) {
                Log.e(TAG, "添加悬浮按钮失败", t)
            }
        }
    }

    private fun removeOverlay() {
        mainHandler.post {
            overlayView?.let {
                try {
                    windowManager?.removeView(it)
                } catch (_: Throwable) {
                }
            }
            overlayView = null
        }
    }

    // ------------------------------------------------------------------
    // 屏幕边框跑马灯（蓝=操作中，红=受阻）
    // ------------------------------------------------------------------

    private fun showBorder() {
        if (!Settings.canDrawOverlays(this)) return
        mainHandler.post {
            if (borderView != null) return@post
            val view = BorderMarqueeView(this)
            val type =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                // 不可聚焦 + 不可触摸：触摸全部穿透到钉钉，绝不挡住自动点击或用户操作
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            try {
                windowManager?.addView(view, params)
                borderView = view
                // 淡入
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(380).start()
            } catch (t: Throwable) {
                Log.e(TAG, "添加边框跑马灯失败", t)
            }
        }
    }

    private fun removeBorder() {
        mainHandler.post {
            val view = borderView ?: return@post
            borderView = null // 立即置空，允许下次重新创建
            // 淡出后再移除
            view.animate().alpha(0f).setDuration(320).withEndAction {
                try {
                    windowManager?.removeView(view)
                } catch (_: Throwable) {
                }
            }.start()
        }
    }

    /** 切换边框颜色状态；仅在状态变化时下发，避免频繁重启颜色动画。转入受阻时响一次提示音。 */
    private fun updateBorder(blocked: Boolean) {
        if (borderBlocked == blocked) return
        borderBlocked = blocked
        mainHandler.post { borderView?.setBlocked(blocked) }
        if (blocked) playBlockedTone()
    }

    /** 受阻/失败提示音：柔和的合成双音钟声。 */
    private fun playBlockedTone() {
        SoundFx.playAlert()
    }

    // ------------------------------------------------------------------
    // 悬浮弹窗（覆盖在钉钉之上，可复制内容）——报错与屏幕抓取共用
    // ------------------------------------------------------------------

    private fun showOverlayDialog(title: String, message: String) {
        mainHandler.post {
            // 没有悬浮窗权限就无法弹系统级对话框，退化为 Toast，至少让内容可见
            if (!Settings.canDrawOverlays(this)) {
                toast(message.take(200))
                return@post
            }
            dismissErrorPopupNow()
            val ctx = ContextThemeWrapper(
                this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
            )
            val dialog = AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(true)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制", null)
                .create()

            val windowType =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            dialog.window?.setType(windowType)
            dialog.setOnDismissListener { if (errorDialog === dialog) errorDialog = null }

            try {
                dialog.show()
                // 「复制」按钮点击后不关闭弹窗，方便用户复制完继续看
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                    copyToClipboard(message)
                    toast("已复制到剪贴板")
                }
                errorDialog = dialog
            } catch (t: Throwable) {
                Log.e(TAG, "显示悬浮弹窗失败", t)
                toast(message.take(200))
            }
        }
    }

    private fun dismissErrorPopup() {
        mainHandler.post { dismissErrorPopupNow() }
    }

    // ------------------------------------------------------------------
    // 需确认弹窗（如外勤打卡）——阻塞打卡线程，等用户点「继续/取消」
    // ------------------------------------------------------------------

    /** 在后台线程调用：弹确认框并阻塞等待用户选择。返回 true=继续，false=取消/超时/无权限。 */
    private fun requestUserConfirm(message: String): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            // 无悬浮窗权限弹不出确认框，涉及后果的操作保守当作取消
            toast("需要确认但未授悬浮窗权限，已取消本次打卡")
            return false
        }
        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)
        mainHandler.post {
            showConfirmDialog(message) { ok ->
                result.set(ok)
                latch.countDown()
            }
        }
        val start = System.currentTimeMillis()
        while (latch.count > 0) {
            if (shouldStop) return false
            if (System.currentTimeMillis() - start > 60_000) return false // 60s 无操作视为取消
            try {
                latch.await(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return result.get()
    }

    private fun showConfirmDialog(message: String, onResult: (Boolean) -> Unit) {
        confirmDialog?.let { runCatching { it.dismiss() } }
        val ctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
        var decided = false
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("⚠️ 任务受阻")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("继续打卡") { d, _ -> decided = true; d.dismiss(); onResult(true) }
            .setNegativeButton("取消") { d, _ -> decided = true; d.dismiss(); onResult(false) }
            .create()
        val windowType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        dialog.window?.setType(windowType)
        dialog.setOnDismissListener {
            if (confirmDialog === dialog) confirmDialog = null
            if (!decided) onResult(false)
        }
        try {
            dialog.show()
            confirmDialog = dialog
        } catch (t: Throwable) {
            Log.e(TAG, "显示确认弹窗失败", t)
            onResult(false)
        }
    }

    private fun dismissErrorPopupNow() {
        errorDialog?.let { runCatching { it.dismiss() } }
        errorDialog = null
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("打卡喵", text))
        } catch (t: Throwable) {
            Log.e(TAG, "复制失败", t)
        }
    }

    // ------------------------------------------------------------------
    // 屏幕抓取（调试）：导出当前前台窗口的节点树，供适配钉钉打卡界面
    // ------------------------------------------------------------------

    /** 延迟若干毫秒后抓取当前前台界面（留出时间切到钉钉打卡页）。 */
    fun scheduleDump(delayMs: Long) {
        toast("准备抓取…请立刻切到钉钉打卡页面")
        mainHandler.postDelayed({ dumpAndShow() }, delayMs)
    }

    private fun dumpAndShow() {
        val text = try {
            buildNodeDump()
        } catch (t: Throwable) {
            "抓取失败：\n" + Log.getStackTraceString(t)
        }
        showOverlayDialog("屏幕抓取 · 点「复制」发我排查", text)
    }

    /** 遍历当前活动窗口，导出每个有文字/描述/可点击的节点：类名、文字、描述、标志位、屏幕坐标。 */
    private fun buildNodeDump(): String {
        val root = rootInActiveWindow
            ?: return "读不到当前窗口（rootInActiveWindow=null）。请确认无障碍已开启，且抓取时钉钉在前台。"
        val sb = StringBuilder()
        sb.append("前台包名: ").append(root.packageName).append('\n')
        sb.append("标志说明: C=可点击 E=启用 d=禁用 S=可滚动 K=可勾选\n")
        sb.append("————————————————————\n")
        var count = 0
        val rect = Rect()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || count >= 500) return
            count++
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            // 只打印有信息或可点击的节点，减少噪音
            if (t.isNotEmpty() || d.isNotEmpty() || node.isClickable) {
                val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
                node.getBoundsInScreen(rect)
                val flags = buildString {
                    if (node.isClickable) append('C')
                    append(if (node.isEnabled) 'E' else 'd')
                    if (node.isScrollable) append('S')
                    if (node.isCheckable) append('K')
                }
                sb.append("  ".repeat(depth.coerceAtMost(10)))
                sb.append(cls)
                if (t.isNotEmpty()) sb.append(" text=\"").append(t).append('"')
                if (d.isNotEmpty()) sb.append(" desc=\"").append(d).append('"')
                sb.append(" [").append(flags).append("] ")
                sb.append(rect.toShortString())
                sb.append('\n')
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }

        walk(root, 0)
        sb.append("————————————————————\n共 ").append(count).append(" 个节点")
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "打卡状态",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "打卡进行中与结果提醒" }
            nm.createNotificationChannel(channel)
        }
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, StopReceiver::class.java)
        return PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showRunningNotification(type: ClockType) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在${type.label}打卡…")
            .setContentText("点这里的「停止」，或用悬浮按钮 / 音量下键随时中断")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "停止", stopPendingIntent())
            .build()
        NotificationManagerCompat.from(this).notify(NOTIF_RUNNING, n)
    }

    private fun showResultNotification(type: ClockType, result: RunResult) {
        val nm = NotificationManagerCompat.from(this)
        nm.cancel(NOTIF_RUNNING)

        val title = when (result.status) {
            RunStatus.SUCCESS -> "✅ ${type.label}打卡成功"
            RunStatus.NEED_FACE -> "⚠️ 需要你本人验证"
            RunStatus.STOPPED -> "⏹ 已停止"
            RunStatus.APP_NOT_FOUND -> "❌ 打卡失败"
            RunStatus.STEP_FAILED -> "❌ ${type.label}打卡失败"
            RunStatus.UNKNOWN -> "❓ 请手动核对"
            RunStatus.ERROR -> "⚠️ 出错了"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(result.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(result.detail))
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
        // 需要人脸时加震动强提醒，避免用户错过
        if (result.status == RunStatus.NEED_FACE) {
            builder.setVibrate(longArrayOf(0, 400, 200, 400))
        }
        nm.notify(NOTIF_RESULT, builder.build())
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 分片睡眠，期间反复检查停止标志；被停止时返回 false。 */
    private fun sleepChecked(totalMs: Long): Boolean {
        var remaining = totalMs
        val slice = 100L
        while (remaining > 0) {
            if (shouldStop) return false
            val t = minOf(slice, remaining)
            try {
                Thread.sleep(t)
            } catch (_: InterruptedException) {
                return false
            }
            remaining -= t
        }
        return !shouldStop
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "DaKaMiao:clockin"
            )
            wl.acquire(60_000L) // 最多持有 60 秒，兜底自动释放
            wl
        } catch (t: Throwable) {
            Log.e(TAG, "获取 WakeLock 失败", t)
            null
        }
    }

    private fun toast(msg: String) {
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
