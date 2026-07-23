package com.haitang000.dakamiao

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

/**
 * 用 AlarmManager 精确闹钟在设定时间触发打卡。
 * 每个闹钟是一次性的，触发后由 ClockAlarmReceiver 负责重排下一天，从而实现「每日循环」。
 */
object AlarmScheduler {

    private const val TAG = "DaKaMiao"
    const val EXTRA_TYPE = "clock_type"
    private const val REQ_ON = 2001
    private const val REQ_OFF = 2002

    private fun alarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun requestCode(type: ClockType) =
        if (type == ClockType.ON) REQ_ON else REQ_OFF

    private fun pendingIntent(context: Context, type: ClockType): PendingIntent {
        val intent = Intent(context, ClockAlarmReceiver::class.java).apply {
            action = "com.haitang000.dakamiao.CLOCK_$type"
            putExtra(EXTRA_TYPE, type.name)
        }
        return PendingIntent.getBroadcast(
            context, requestCode(type), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 根据当前配置重排全部闹钟；未启用则全部取消。 */
    fun rescheduleAll(context: Context) {
        cancelAll(context)
        if (!Prefs.isEnabled(context)) return
        scheduleType(context, ClockType.ON)
        scheduleType(context, ClockType.OFF)
    }

    fun cancelAll(context: Context) {
        val am = alarmManager(context)
        am.cancel(pendingIntent(context, ClockType.ON))
        am.cancel(pendingIntent(context, ClockType.OFF))
    }

    /** 为某个类型排下一次触发（今天该时刻若已过，则排到明天）。 */
    fun scheduleType(context: Context, type: ClockType) {
        val am = alarmManager(context)
        val triggerAt = nextTriggerMillis(Prefs.getTime(context, type))
        val pi = pendingIntent(context, type)

        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            am.canScheduleExactAlarms() else true

        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                // 没有精确闹钟权限时退化为非精确闹钟（可能有几分钟延迟）
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "排精确闹钟被拒，降级为非精确", se)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
        Log.i(TAG, "已排 ${type.label} 闹钟：${java.util.Date(triggerAt)}")
    }

    private fun nextTriggerMillis(hhmm: String): Long {
        val parts = hhmm.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 9
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val now = Calendar.getInstance()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now.timeInMillis) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}
