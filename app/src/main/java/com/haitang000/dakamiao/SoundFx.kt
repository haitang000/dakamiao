package com.haitang000.dakamiao

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 用 AudioTrack 实时合成的柔和提示音，无需打包音频文件。
 * 受阻/失败时播放一个下行双音钟声（正弦波 + 淡入 + 指数衰减），温和但清晰。
 */
object SoundFx {
    private const val SR = 44100

    fun playAlert() {
        Thread {
            var track: AudioTrack? = null
            try {
                val pcm = buildChime(doubleArrayOf(880.0, 660.0), noteMs = 170, gapMs = 45)
                val totalMs = pcm.size * 1000L / SR
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(totalMs + 150)
            } catch (t: Throwable) {
                Log.e("DaKaMiao", "播放提示音失败", t)
            } finally {
                try {
                    track?.stop()
                    track?.release()
                } catch (_: Throwable) {
                }
            }
        }.start()
    }

    /** 合成若干音符首尾相接的钟声 PCM（16bit 单声道）。 */
    private fun buildChime(freqs: DoubleArray, noteMs: Int, gapMs: Int): ShortArray {
        val noteN = SR * noteMs / 1000
        val gapN = SR * gapMs / 1000
        val total = freqs.size * noteN + (freqs.size - 1) * gapN
        val out = ShortArray(total)
        var idx = 0
        val dur = noteMs / 1000.0
        for ((k, f) in freqs.withIndex()) {
            for (i in 0 until noteN) {
                val t = i.toDouble() / SR
                val attack = if (t < 0.008) t / 0.008 else 1.0          // 8ms 淡入，避免爆音
                val env = attack * exp(-3.5 * t / dur)                  // 指数衰减，钟声感
                val s = sin(2 * PI * f * t) * env * 0.38
                out[idx++] = (s * Short.MAX_VALUE).toInt().toShort()
            }
            if (k < freqs.size - 1) repeat(gapN) { out[idx++] = 0 }
        }
        return out
    }
}
