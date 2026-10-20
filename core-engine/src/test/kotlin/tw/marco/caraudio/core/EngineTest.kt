package tw.marco.caraudio.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineTest {
    private val fs = 48_000
    private val sweep = LogSweep(fs, durationSec = 3.0)
    private val preroll = fs / 2

    /** 模擬錄音：先錄 preroll，聲音經過 [delaySamples] 延遲、[gain] 倍與 [filter] 後進麥克風。 */
    private fun simulate(
        delaySamples: Int,
        gain: Double,
        noiseRms: Double = 0.0,
        filter: (DoubleArray) -> DoubleArray = { it },
    ): DoubleArray {
        val total = preroll + sweep.samples.size + fs * 2
        val out = DoubleArray(total)
        val filtered = filter(sweep.samples)
        for (i in filtered.indices) {
            val j = preroll + delaySamples + i
            if (j < total) out[j] += gain * filtered[i]
        }
        if (noiseRms > 0) {
            val rnd = Random(42)
            for (i in out.indices) out[i] += noiseRms * rnd.nextGaussian()
        }
        return out
    }

    private fun Random.nextGaussian(): Double {
        // Box-Muller
        val u = nextDouble().coerceAtLeast(1e-12)
        val v = nextDouble()
        return sqrt(-2 * kotlin.math.ln(u)) * cos(2 * PI * v)
    }

    private fun onePoleLowPass(cutoffHz: Double): (DoubleArray) -> DoubleArray = { x ->
        val a = exp(-2 * PI * cutoffHz / fs)
        val y = DoubleArray(x.size)
        var prev = 0.0
        for (i in x.indices) {
            prev = a * prev + (1 - a) * x[i]
            y[i] = prev
        }
        y
    }

    private fun onePoleDb(cutoffHz: Double, f: Double): Double {
        val a = exp(-2 * PI * cutoffHz / fs)
        val w = 2 * PI * f / fs
        val re = 1 - a * cos(w)
        val im = a * sin(w)
        return 20 * log10((1 - a) / sqrt(re * re + im * im))
    }

    private fun FrequencyResponse.at(f: Double): Double = db[freqs.indices.minBy { abs(freqs[it] - f) }]

    @Test
    fun fftRoundTrip() {
        val n = 1024
        val orig = DoubleArray(n) { sin(it * 0.37) + 0.2 * cos(it * 1.3) }
        val re = orig.copyOf()
        val im = DoubleArray(n)
        Fft.transform(re, im)
        Fft.transform(re, im, inverse = true)
        for (i in 0 until n) assertEquals(orig[i], re[i], 1e-9)
    }

    @Test
    fun fftFindsSineBin() {
        val n = 256
        val re = DoubleArray(n) { sin(2 * PI * 10 * it / n) }
        val im = DoubleArray(n)
        Fft.transform(re, im)
        val mags = DoubleArray(n / 2) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        assertEquals(10, mags.indices.maxBy { mags[it] })
    }

    @Test
    fun sweepStaysWithinAmplitude() {
        assertEquals(3 * fs, sweep.samples.size)
        assertTrue(sweep.samples.all { abs(it) <= sweep.amplitude + 1e-12 })
        assertEquals(0.0, sweep.samples.first(), 1e-9)
    }

    @Test
    fun recoversDelayAndFlatGain() {
        val delay = (0.25 * fs).toInt() // 模擬藍牙延遲 250ms
        val result = MeasurementAnalyzer.analyze(simulate(delay, gain = 0.5), sweep, preroll)
        assertEquals(250.0, result.latencyMs, 0.1)
        val expected = 20 * log10(0.5)
        for (f in FrequencyResponse.thirdOctaveCenters) {
            // 25Hz 貼近掃頻下限且 200ms 視窗解析度有限，容許較大誤差
            val tolerance = if (f < 30) 1.0 else 0.3
            assertEquals(expected, result.response.at(f), tolerance, "at $f Hz")
        }
    }

    @Test
    fun recoversFilterShape() {
        val cutoff = 2000.0
        val rec = simulate(4800, gain = 0.3, filter = onePoleLowPass(cutoff))
        val r = MeasurementAnalyzer.analyze(rec, sweep, preroll).response
        val g = 20 * log10(0.3)
        for (f in listOf(100.0, 1000.0, 2000.0, 5000.0, 10000.0)) {
            assertEquals(g + onePoleDb(cutoff, f), r.at(f), 0.5, "at $f Hz")
        }
    }

    @Test
    fun toleratesBackgroundNoise() {
        // 掃頻約 -15 dBFS，噪音 -60 dBFS
        val rec = simulate(9600, gain = 0.5, noiseRms = 0.001)
        val result = MeasurementAnalyzer.analyze(rec, sweep, preroll)
        assertEquals(-60.0, result.levels.noiseDbfs, 1.0)
        assertTrue(result.levels.snrDb > 30, "snr ${result.levels.snrDb}")
        val expected = 20 * log10(0.5)
        for (f in FrequencyResponse.thirdOctaveCenters.filter { it in 40.0..16000.0 }) {
            assertEquals(expected, result.response.at(f), 1.0, "at $f Hz")
        }
    }

    @Test
    fun directSoundWinsOverStrongerReflection() {
        val ir = DoubleArray(4800)
        ir[1000] = 0.6 // 直達聲
        ir[1100] = 1.0 // 2ms 後更強的反射
        assertEquals(1000, Deconvolver.findDirectSound(ir, fs))
    }

    @Test
    fun levelFlags() {
        assertTrue(Levels(peakDbfs = -0.1, noiseDbfs = -70.0, signalDbfs = -10.0).clipped)
        assertTrue(Levels(peakDbfs = -40.0, noiseDbfs = -70.0, signalDbfs = -55.0).tooQuiet)
        assertTrue(Levels(peakDbfs = -10.0, noiseDbfs = -35.0, signalDbfs = -20.0).noisy)
    }

    @Test
    fun repeatabilitySpread() {
        val grid = FrequencyResponse.logGrid()
        val a = FrequencyResponse(grid, DoubleArray(grid.size) { 0.0 })
        val b = FrequencyResponse(grid, DoubleArray(grid.size) { if (grid[it] in 900.0..1100.0) 2.0 else 0.5 })
        val rep = Repeatability(listOf(a, a, b))
        assertEquals(2.0, rep.midSpreadDb, 1e-9)
        assertEquals(Repeatability.Verdict.OK, rep.verdict)
        assertEquals(Repeatability.Verdict.GOOD, Repeatability(listOf(a, a, a)).verdict)
    }
}
