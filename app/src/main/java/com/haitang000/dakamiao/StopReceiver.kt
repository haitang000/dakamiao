package com.haitang000.dakamiao

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知栏「停止」按钮 → 转达给无障碍服务中断当前打卡流程。 */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AutoClockAccessibilityService.instance?.requestStop("已通过通知停止")
    }
}
