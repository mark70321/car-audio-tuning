package tw.marco.caraudio.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2

/** 脈衝響應：[peakIndex] 是直達聲的位置（從錄音開頭算起的樣本數）。 */
class ImpulseResponse(val samples: DoubleArray, val sampleRate: Int, val peakIndex: Int)

/**
 * 把「錄到的聲音」除以「播出去的掃頻」，得到喇叭＋車廂＋麥克風整體的脈衝響應。
 * 藍牙延遲會自然反映在 peakIndex，不需要事先知道。
 * 對數掃頻的失真產物會落在負時間（陣列尾端），搜尋峰值時排除。
 */
object Deconvolver {
    fun impulseResponse(recording: DoubleArray, sweep: LogSweep): ImpulseResponse {
        val n = Fft.nextPowerOfTwo(maxOf(recording.size, sweep.samples.size))
        val rRe = recording.copyOf(n)
        val rIm = DoubleArray(n)
        val sRe = sweep.samples.copyOf(n)
        val sIm = DoubleArray(n)
        Fft.transform(rRe, rIm)
        Fft.transform(sRe, sIm)

        var maxPower = 0.0
        for (k in 0 until n) maxPower = maxOf(maxPower, sRe[k] * sRe[k] + sIm[k] * sIm[k])

        // 正規化除法（eps 避免除以接近 0 的數），再乘上平滑的頻帶窗：
        // 掃頻範圍內 = 1，範圍外用餘弦慢慢降到 0。硬切邊緣會讓脈衝響應拖出長長的振鈴，被視窗截斷後變成低頻漣波。
        val eps = maxPower * 1e-5
        val df = sweep.sampleRate.toDouble() / n
        val nyquist = sweep.sampleRate / 2.0
        for (k in 0 until n) {
            val f = (if (k <= n / 2) k else n - k) * df
            val sPow = sRe[k] * sRe[k] + sIm[k] * sIm[k]
            val w = bandWindow(f, sweep.startHz, sweep.endHz, nyquist) / (sPow + eps)
            // R * conj(S) / (|S|^2 + eps)
            val re = (rRe[k] * sRe[k] + rIm[k] * sIm[k]) * w
            val im = (rIm[k] * sRe[k] - rRe[k] * sIm[k]) * w
            rRe[k] = re
            rIm[k] = im
        }
        Fft.transform(rRe, rIm, inverse = true)

        return ImpulseResponse(rRe, sweep.sampleRate, findDirectSound(rRe, sweep.sampleRate))
    }

    /**
     * 直達聲位置：先找最大值，再往前 5ms 內找第一個達到最大值一半的樣本。
     * 車廂裡反射聲可能比直達聲還強，只取最大值會讓每次量測的起點跳來跳去。
     */
    internal fun findDirectSound(ir: DoubleArray, sampleRate: Int): Int {
        var peak = 0
        for (i in 0 until ir.size / 2) if (abs(ir[i]) > abs(ir[peak])) peak = i
        val threshold = abs(ir[peak]) / 2
        val from = maxOf(0, peak - sampleRate * 5 / 1000)
        for (i in from..peak) if (abs(ir[i]) >= threshold) return i
        return peak
    }

    /** 低頻端：startHz 往下一個八度內降到 0；高頻端：endHz 到 Nyquist 之間降到 0。 */
    private fun bandWindow(f: Double, startHz: Double, endHz: Double, nyquist: Double): Double = when {
        f >= startHz && f <= endHz -> 1.0
        f < startHz / 2 || f >= nyquist -> 0.0
        f < startHz -> 0.5 - 0.5 * cos(PI * log2(f / (startHz / 2)))
        else -> 0.5 + 0.5 * cos(PI * (f - endHz) / (nyquist - endHz))
    }
}
