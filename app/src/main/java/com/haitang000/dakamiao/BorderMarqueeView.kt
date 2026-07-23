package com.haitang000.dakamiao

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin
class BorderMarqueeView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val cornerRadius = dp(46f)
    private val edgeOffset = dp(4f) // 描边中心略微压到屏幕外，只留向内的弥散

    // 冷色 / 暖色调色板（首尾同色，保证扫描渐变无缝衔接）
    private val coolColors = intArrayOf(
        0xFF3B82F6.toInt(), // 蓝
        0xFF6366F1.toInt(), // 靛
        0xFFA855F7.toInt(), // 紫
        0xFFEC4899.toInt(), // 粉
        0xFF22D3EE.toInt(), // 青
        0xFF3B82F6.toInt()
    )
    private val warmColors = intArrayOf(
        0xFFEF4444.toInt(), // 红
        0xFFF97316.toInt(), // 橙
        0xFFF59E0B.toInt(), // 琥珀
        0xFFEC4899.toInt(), // 粉
        0xFFF43F5E.toInt(), // 玫红
        0xFFEF4444.toInt()
    )
    private val stops = floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)

    private val argb = ArgbEvaluator()
    private var blendT = 0f // 0=冷色，1=暖色
    private var blendAnimator: ValueAnimator? = null

    private val path = Path()
    private var cx = 0f
    private var cy = 0f
    private var sweep: SweepGradient? = null
    private val rotMatrix = Matrix()

    // 辉光层：从外到内，半径递减、透明度递增；共用同一个旋转扫描渐变
    private class Layer(val paint: Paint, val baseAlpha: Float)

    private fun makeLayer(widthDp: Float, blurDp: Float, baseAlpha: Float): Layer {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = dp(widthDp)
            maskFilter = BlurMaskFilter(dp(blurDp), BlurMaskFilter.Blur.NORMAL)
        }
        return Layer(p, baseAlpha)
    }

    private val layers = listOf(
        makeLayer(46f, 24f, 0.28f), // 最外层弥散光晕
        makeLayer(30f, 15f, 0.42f), // 中层
        makeLayer(18f, 8f, 0.65f),  // 内层
        makeLayer(9f, 3f, 0.95f)    // 贴边亮芯
    )

    // 帧驱动：持续 invalidate，角度与呼吸由时间实时计算
    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    init {
        // BlurMaskFilter 在硬件加速下不生效，切软件层
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** 切换受阻（暖）/正常（冷）配色，带过渡。 */
    fun setBlocked(blocked: Boolean) {
        val target = if (blocked) 1f else 0f
        if (target == blendT && blendAnimator == null) return
        blendAnimator?.cancel()
        blendAnimator = ValueAnimator.ofFloat(blendT, target).apply {
            duration = 400
            addUpdateListener {
                blendT = it.animatedValue as Float
                buildSweep()
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    blendAnimator = null
                }
            })
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!ticker.isStarted) ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        blendAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        val rect = RectF(-edgeOffset, -edgeOffset, w + edgeOffset, h + edgeOffset)
        path.reset()
        path.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        buildSweep()
    }

    private fun buildSweep() {
        if (width <= 0 || height <= 0) return
        val colors = IntArray(coolColors.size) {
            argb.evaluate(blendT, coolColors[it], warmColors[it]) as Int
        }
        sweep = SweepGradient(cx, cy, colors, stops)
    }

    override fun onDraw(canvas: Canvas) {
        val shader = sweep ?: return

        val now = SystemClock.elapsedRealtime()
        // 颜色沿边框缓慢旋转（约 7 秒一圈）
        val angle = (now % ROT_PERIOD) / ROT_PERIOD.toFloat() * 360f
        rotMatrix.setRotate(angle, cx, cy)
        shader.setLocalMatrix(rotMatrix)
        // 轻微呼吸
        val breath = 0.80f + 0.20f * sin(now / BREATH_PERIOD * 2.0 * PI).toFloat()

        for (l in layers) {
            l.paint.shader = shader
            l.paint.alpha = (l.baseAlpha * breath * 255f).toInt().coerceIn(0, 255)
            canvas.drawPath(path, l.paint)
        }
    }

    companion object {
        private const val ROT_PERIOD = 7000L
        private const val BREATH_PERIOD = 2600.0
    }
}
