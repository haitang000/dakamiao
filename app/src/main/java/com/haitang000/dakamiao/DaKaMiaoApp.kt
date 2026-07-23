package com.haitang000.dakamiao

import android.app.Application
import com.google.android.material.color.DynamicColors

/** 应用入口：开启 Material You 动态取色（安卓 12+ 跟随壁纸配色；不支持时回退到内置 M3 蓝色板）。 */
class DaKaMiaoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
