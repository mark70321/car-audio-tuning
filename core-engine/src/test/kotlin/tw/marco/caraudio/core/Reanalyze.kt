package tw.marco.caraudio.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test

/**
 * 用電腦重新分析手機存下來的錄音（不是自動測試，沒設定環境變數就跳過）：
 *   set MEASURE_DIR=...\measurements\results
 *   gradlew :core-engine:test --tests *Reanalyze*
 * 結果寫到 core-engine/build/reanalysis.txt
 */
class Reanalyze {
    private fun readWav(file: File): Pair<DoubleArray, Int> {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val sampleRate = bytes.getInt(24)
        val count = bytes.getInt(40) / 2
        return DoubleArray(count) { bytes.getShort(44 + it * 2) / 32768.0 } to sampleRate
    }

    @Test
    fun reanalyze() {
        val dir = System.getenv("MEASURE_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val out = StringBuilder()
        for (batch in dir.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }) {
            val results = batch.listFiles()!!.filter { it.name.matches(Regex("run\\d\\.wav")) }.sortedBy { it.name }.map {
                val (samples, fs) = readWav(it)
                MeasurementAnalyzer.analyze(samples, LogSweep(fs, durationSec = 6.0), fs / 2)
            }
            if (results.size < 2) continue
            val curves = results.map { it.response }
            // 舊算法：不扣音量偏移、不排除噪音頻段
            val raw = curves.first().freqs.indices.filter { curves.first().freqs[it] in 100.0..10_000.0 }
                .maxOf { i -> curves.maxOf { c -> c.db[i] } - curves.minOf { c -> c.db[i] } }
            val rep = Repeatability(curves)
            val range = rep.validRangeHz?.let { "%.0f～%.0f Hz".format(it.first, it.second) } ?: "-"
            out.appendLine(
                "%-36s 峰值 %s | 舊 %.1f dB → 新 形狀 %.1f dB、音量偏移 %.1f dB、有效 %s → %s".format(
                    batch.name, results.joinToString("/") { "%.0f".format(it.levels.peakDbfs) },
                    raw, rep.midSpreadDb, rep.levelOffsetDb, range, rep.verdict,
                )
            )
        }
        File("build/reanalysis.txt").writeText(out.toString())
        println(out)
    }
}
