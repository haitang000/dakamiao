package com.haitang000.dakamiao

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Calendar

/**
 * 定时闹钟触发点。
 * 每次触发后立刻重排明天的同一闹钟，保证「每日循环」即使本次异常也不中断。
 */
class ClockAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val type = ClockType.fromName(intent?.getStringExtra(AlarmScheduler.EXTRA_TYPE))
        Log.i("DaKaMiao", "闹钟触发：${type.label}")

        // 先重排下一天，避免后续逻辑异常导致循环断掉
        AlarmScheduler.scheduleType(context, type)

        if (!Prefs.isEnabled(context)) return

        // 确保保活服务在运行（进程可能被杀过又被闹钟唤醒）
        KeepAliveService.start(context)

        // 仅工作日模式下，周末跳过
        if (Prefs.isWorkdayOnly(context) && isWeekend()) {
            Log.i("DaKaMiao", "周末，按设置跳过打卡")
            return
        }

        val service = AutoClockAccessibilityService.instance
        if (service == null) {
            notifyAccessibilityOff(context, type)
            return
        }
        // 定时到点：已解锁则倒计时打卡；锁屏则点亮+强提醒，等解锁后自动打卡
        service.handleScheduledTrigger(type)
    }

    private fun isWeekend(): Boolean {
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
    }

    private fun notifyAccessibilityOff(context: Context, type: ClockType) {
        val n = NotificationCompat.Builder(context, AutoClockAccessibilityService.CHANNEL_ID)
            .setContentTitle("⚠️ 无法自动${type.label}打卡")
            .setContentText("无障碍服务未开启，打卡喵无法操作钉钉。请打开 App 重新开启无障碍服务。")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(AutoClockAccessibilityService.NOTIF_RESULT, n)
    }
}
