package com.haitang000.dakamiao

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 前台保活服务：常驻一条低优先级通知，把 App 进程保持在前台级别，尽量避免被系统/ROM 杀掉，
 * 从而保证定时闹钟与无障碍服务在到点时可用。仅在「定时」开启时运行。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_STICKY // 被杀后系统尽量重启
    }

    private fun goForeground() {
        createChannel()
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CH)
            .setContentTitle("打卡喵守护中")
            .setContentText("定时打卡已就绪，保持后台运行")
            .setSmallIcon(android.R.drawable.presence_online)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(tap)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID, n)
            }
        } catch (t: Throwable) {
            Log.e("DaKaMiao", "前台保活启动失败", t)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null) {
                val ch = NotificationChannel(CH, "后台保活", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "保持打卡喵后台运行以按时打卡"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    companion object {
        private const val ID = 2001
        private const val CH = "keepalive_channel"

        /** 启动保活（幂等）。从后台上下文（开机/闹钟广播）调用也允许，因为它们处于可启动前台服务的窗口内。 */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, KeepAliveService::class.java)
                )
            } catch (t: Throwable) {
                Log.e("DaKaMiao", "启动保活服务失败", t)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}
