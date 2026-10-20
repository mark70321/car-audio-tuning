package tw.marco.caraudio.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** 錄音音量檢查（dBFS：0 = 數位滿格，越負越小聲）。 */
data class Levels(val peakDbfs: Double, val noiseDbfs: Double, val signalDbfs: Double) {
    /** 訊噪比：掃頻音量比背景噪音大多少 dB。 */
    val snrDb: Double get() = signalDbfs - noiseDbfs
    val clipped: Boolean get() = peakDbfs > -1.0
    /** 2026-10 桌邊實測：峰值 -14～-22 dBFS 時高頻差異 3～6 dB；-5 dBFS 時僅 0.3 dB */
    val tooQuiet: Boolean get() = peakDbfs < -15
    val noisy: Boolean get() = snrDb < 25
}

data class MeasurementResult(
    val response: FrequencyResponse,
    /** 從按下播放到聲音進麥克風的時間（含藍牙延遲）。 */
    val latencyMs: Double,
    val levels: Levels,
)

object MeasurementAnalyzer {
    /**
     * @param recording 錄音（-1..1）
     * @param prerollSamples 開始播放前先錄的樣本數，這段用來量背景噪音
     */
    fun analyze(recording: DoubleArray, sweep: LogSweep, prerollSamples: Int): MeasurementResult {
        val ir = Deconvolver.impulseResponse(recording, sweep)
        val response = ResponseAnalyzer.frequencyResponse(ir)
        // 噪音段跳過錄音剛開始的 150ms（錄音器啟動時可能有雜音），到播放前留一點餘裕
        val noiseStart = minOf(sweep.sampleRate * 150 / 1000, prerollSamples / 2)
        val noiseEnd = (prerollSamples * 0.8).toInt()
        val signalEnd = minOf(recording.size, ir.peakIndex + sweep.samples.size)
        val levels = Levels(
            peakDbfs = toDb(recording.maxOfOrNull { abs(it) } ?: 0.0),
            noiseDbfs = toDb(rms(recording, noiseStart, noiseEnd)),
            signalDbfs = toDb(rms(recording, ir.peakIndex, signalEnd)),
        )
        val latencyMs = (ir.peakIndex - prerollSamples) * 1000.0 / sweep.sampleRate
        return MeasurementResult(response, latencyMs, levels)
    }

    private fun rms(x: DoubleArray, from: Int, to: Int): Double {
        if (to <= from) return 0.0
        var sum = 0.0
        for (i in from until to) sum += x[i] * x[i]
        return sqrt(sum / (to - from))
    }

    private fun toDb(v: Double) = 20 * log10(v + 1e-12)
}

/**
 * 重複性：同位置連測幾次，比較曲線「形狀」差多少。
 * - 整體音量偏移（例如中途調了音量）另外算在 [levelOffsetDb]，不算進形狀差異——調音只在乎形狀。
 * - 喇叭發不出聲音的頻段（低於曲線最高點 [validBelowPeakDb] dB，錄到的其實是背景噪音）不列入。
 */
class Repeatability(curves: List<FrequencyResponse>, validBelowPeakDb: Double = 35.0) {
    init {
        require(curves.size >= 2 && curves.all { it.freqs.contentEquals(curves.first().freqs) })
    }

    val freqs: DoubleArray = curves.first().freqs

    /** 每條曲線在 200Hz～2kHz 的平均音量，當作整體音量基準 */
    private val levels: List<Double> = curves.map { c ->
        val idx = freqs.indices.filter { freqs[it] in 200.0..2000.0 }
        if (idx.isEmpty()) 0.0 else idx.map { c.db[it] }.average()
    }

    /** 幾次之間整體音量最大差多少 dB */
    val levelOffsetDb: Double = levels.max() - levels.min()

    /** 每個頻率點的形狀差異（已扣掉整體音量偏移） */
    val spreadDb: DoubleArray = DoubleArray(freqs.size) { i ->
        val values = curves.mapIndexed { k, c -> c.db[i] - levels[k] }
        values.max() - values.min()
    }

    /** 喇叭有發出聲音的頻點：平均曲線不低於最高點 [validBelowPeakDb] dB */
    val valid: BooleanArray = run {
        val mean = DoubleArray(freqs.size) { i -> curves.map { it.db[i] }.average() }
        val top = mean.max()
        BooleanArray(freqs.size) { mean[it] >= top - validBelowPeakDb }
    }

    /** 有效頻段的最低～最高頻率（Hz） */
    val validRangeHz: Pair<Double, Double>? = freqs.indices.filter { valid[it] }
        .takeIf { it.isNotEmpty() }?.let { freqs[it.first()] to freqs[it.last()] }

    fun maxSpread(fromHz: Double, toHz: Double): Double =
        freqs.indices.filter { valid[it] && freqs[it] in fromHz..toHz }.maxOfOrNull { spreadDb[it] } ?: 0.0

    /** 主要聽感範圍 100Hz～10kHz 的最大差異。 */
    val midSpreadDb: Double get() = maxSpread(100.0, 10_000.0)
    /** 40Hz 以下用 200ms 視窗解析度不足，不列入，避免被不可靠的極低頻主導。 */
    val fullSpreadDb: Double get() = maxSpread(40.0, 16_000.0)

    /** 暫定標準（實測後可能調整）：≤1.5 dB 很穩、≤3 dB 可接受、>3 dB 不穩。 */
    val verdict: Verdict
        get() = when {
            midSpreadDb <= 1.5 -> Verdict.GOOD
            midSpreadDb <= 3.0 -> Verdict.OK
            else -> Verdict.POOR
        }

    enum class Verdict { GOOD, OK, POOR }
}
