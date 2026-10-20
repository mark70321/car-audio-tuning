package tw.marco.caraudio.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * 對數掃頻（exponential sine sweep）：從 [startHz] 滑到 [endHz]，低頻停留較久。
 * 頭尾各加 [fadeSec] 秒淡入淡出，避免喇叭爆音。
 */
class LogSweep(
    val sampleRate: Int,
    val startHz: Double = 20.0,
    val endHz: Double = 20_000.0,
    val durationSec: Double = 6.0,
    val amplitude: Double = 0.5,
    val fadeSec: Double = 0.05,
) {
    init {
        require(startHz > 0 && endHz > startHz) { "invalid sweep range" }
        require(endHz < sampleRate / 2.0) { "endHz must be below Nyquist" }
    }

    val samples: DoubleArray = generate()

    private fun generate(): DoubleArray {
        val n = (durationSec * sampleRate).toInt()
        val rate = ln(endHz / startHz)
        val k = 2 * PI * startHz * durationSec / rate
        val fade = (fadeSec * sampleRate).toInt().coerceAtLeast(1)
        return DoubleArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val phase = k * (exp(t * rate / durationSec) - 1)
            val env = when {
                i < fade -> 0.5 - 0.5 * cos(PI * i / fade)
                i >= n - fade -> 0.5 - 0.5 * cos(PI * (n - 1 - i) / fade)
                else -> 1.0
            }
            amplitude * env * sin(phase)
        }
    }
}
