package com.haitang000.dakamiao

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 点击指示层：每次自动点击时在对应坐标画一圈扩散淡出的涟漪 + 中心点，
 * 让用户直观看到软件点了哪里。全屏、不可触摸，不影响任何操作。
 */
class ClickOverlayView(context: Context) : View(context) {

    private data class Ripple(val x: Float, val y: Float, val start: Long)

    private val ripples = ArrayList<Ripple>()
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val color = Color.parseColor("#22D3EE") // 青色，醒目
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    /** 在 (x,y) 触发一圈涟漪。 */
    fun ripple(x: Float, y: Float) {
        ripples.add(Ripple(x, y, System.currentTimeMillis()))
        if (!ticker.isStarted) ticker.start()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val dur = 650f
        var any = false
        val it = ripples.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val p = (now - r.start) / dur
            if (p >= 1f) {
                it.remove()
                continue
            }
            any = true
            val radius = dp(14f) + dp(34f) * p
            val ringAlpha = ((1f - p) * 255f).toInt().coerceIn(0, 255)
            ringPaint.color = (color and 0x00FFFFFF) or (ringAlpha shl 24)
            canvas.drawCircle(r.x, r.y, radius, ringPaint)

            val dotAlpha = ((1f - p) * 200f).toInt().coerceIn(0, 255)
            dotPaint.color = (color and 0x00FFFFFF) or (dotAlpha shl 24)
            canvas.drawCircle(r.x, r.y, dp(6f) * (1f - p * 0.5f), dotPaint)
        }
        if (!any) ticker.cancel()
    }
}
