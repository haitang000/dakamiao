package com.haitang000.dakamiao

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 开机 / 应用更新后重新注册闹钟（系统重启会清空所有 AlarmManager 闹钟）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i("DaKaMiao", "收到广播：${intent?.action}，重排闹钟")
        if (Prefs.isEnabled(context)) {
            AlarmScheduler.rescheduleAll(context)
            KeepAliveService.start(context)
        }
    }
}
