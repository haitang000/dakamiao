package com.haitang000.dakamiao

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * OCR 兜底定位：当无障碍读不到按钮文字（比如按钮是纯图片）时，
 * 截屏 + 离线文字识别，找出目标文字在屏幕上的位置，供派发点击手势使用。
 *
 * - 截屏用 AccessibilityService.takeScreenshot()，仅安卓 11（API 30）及以上支持，静默无快门声；
 * - 识别用 ML Kit 中文识别器，模型随 APK 打包，全程离线不联网；
 * - 返回的矩形是屏幕物理像素坐标，正好与 dispatchGesture 所需坐标一致。
 */
object ScreenTextLocator {
    private const val TAG = "DaKaMiao"

    /** 仅安卓 11+ 可用（依赖 takeScreenshot）。 */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 截屏并识别，返回第一处包含 keyword 的文字行的屏幕矩形；找不到返回 null。
     * 阻塞调用（应在后台线程使用），最长约 2×timeoutMs。
     */
    fun findText(service: AccessibilityService, keyword: String, timeoutMs: Long = 2500): Rect? {
        if (!isSupported) return null
        val key = keyword.replace(" ", "")
        if (key.isEmpty()) return null

        val bitmap = captureScreen(service, timeoutMs) ?: return null
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val text = Tasks.await(recognizer.process(image), timeoutMs, TimeUnit.MILLISECONDS)
            var found: Rect? = null
            loop@ for (block in text.textBlocks) {
                for (line in block.lines) {
                    if (line.text.replace(" ", "").contains(key)) {
                        found = line.boundingBox
                        break@loop
                    }
                }
            }
            Log.i(TAG, "OCR 查找「$keyword」-> ${if (found != null) "命中 $found" else "未命中"}")
            found
        } catch (t: Throwable) {
            Log.e(TAG, "OCR 识别失败", t)
            null
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureScreen(service: AccessibilityService, timeoutMs: Long): Bitmap? {
        val latch = CountDownLatch(1)
        var out: Bitmap? = null
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hb = screenshot.hardwareBuffer
                            val hw = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                            // ML Kit 需要可读的软件位图，硬件位图要转成 ARGB_8888
                            out = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            hb.close()
                        } catch (t: Throwable) {
                            Log.e(TAG, "处理截屏结果失败", t)
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "截屏失败 code=$errorCode（可能触发了系统限频）")
                        latch.countDown()
                    }
                }
            )
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.e(TAG, "takeScreenshot 调用异常", t)
        }
        return out
    }
}
