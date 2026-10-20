package tw.marco.caraudio.core

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** 頻率響應曲線：每個頻率點（Hz）對應的音量（dB）。 */
class FrequencyResponse(val freqs: DoubleArray, val db: DoubleArray) {
    init {
        require(freqs.size == db.size)
    }

    companion object {
        /** 從 [fMin] 到 [fMax]，每八度 [pointsPerOctave] 個點，對數等距。 */
        fun logGrid(fMin: Double = 20.0, fMax: Double = 20_000.0, pointsPerOctave: Int = 24): DoubleArray {
            val octaves = kotlin.math.log2(fMax / fMin)
            val count = (octaves * pointsPerOctave).roundToInt() + 1
            return DoubleArray(count) { fMin * 2.0.pow(it.toDouble() / pointsPerOctave) }
        }

        /** 標準 1/3 倍頻程中心頻率，約 25Hz～16kHz（以 1kHz 為基準）。 */
        val thirdOctaveCenters: DoubleArray = DoubleArray(29) { 1000.0 * 2.0.pow((it - 16) / 3.0) }
    }
}

object ResponseAnalyzer {
    /**
     * 從脈衝響應算出頻率響應。
     * 只取直達聲前 [preMs] 到之後 [windowMs] 這一段（含車廂早期反射），再做 [smoothingOctave] 倍頻程平滑。
     */
    fun frequencyResponse(
        ir: ImpulseResponse,
        freqs: DoubleArray = FrequencyResponse.logGrid(),
        windowMs: Double = 200.0,
        preMs: Double = 2.0,
        smoothingOctave: Double = 1.0 / 3,
    ): FrequencyResponse {
        val fs = ir.sampleRate
        val pre = (preMs / 1000 * fs).toInt().coerceAtLeast(1)
        val length = (windowMs / 1000 * fs).toInt()
        val fadeOut = (length / 4).coerceAtLeast(1)
        val src = ir.samples
        val n = Fft.nextPowerOfTwo(maxOf(length, 32_768))
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in 0 until length) {
            // 直達聲前一點點的樣本可能繞到陣列尾端（循環卷積）
            val idx = Math.floorMod(ir.peakIndex - pre + i, src.size)
            val w = when {
                i < pre -> 0.5 - 0.5 * cos(PI * i / pre)
                i >= length - fadeOut -> 0.5 - 0.5 * cos(PI * (length - 1 - i) / fadeOut)
                else -> 1.0
            }
            re[i] = src[idx] * w
        }
        Fft.transform(re, im)

        val df = fs.toDouble() / n
        val power = DoubleArray(n / 2 + 1) { re[it] * re[it] + im[it] * im[it] }
        val halfBand = 2.0.pow(smoothingOctave / 2)
        val db = DoubleArray(freqs.size) { i ->
            val f = freqs[i]
            var lo = ceil(f / halfBand / df).toInt().coerceIn(1, power.size - 1)
            var hi = floor(f * halfBand / df).toInt().coerceIn(1, power.size - 1)
            if (hi < lo) {
                lo = (f / df).roundToInt().coerceIn(1, power.size - 1)
                hi = lo
            }
            var sum = 0.0
            for (k in lo..hi) sum += power[k]
            10 * log10(sum / (hi - lo + 1) + 1e-30)
        }
        return FrequencyResponse(freqs, db)
    }
}
