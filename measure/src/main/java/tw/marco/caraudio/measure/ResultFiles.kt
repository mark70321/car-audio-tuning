package tw.marco.caraudio.measure

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 把量測結果存成檔案，手機接電腦後 Claude 可用 adb 直接讀取：
 * /sdcard/Android/data/tw.marco.caraudio.measure/files/results/<時間>_<收音來源>/
 *   run1.wav、run2.wav、run3.wav（原始錄音，可在電腦重新分析）
 *   summary.txt（跟「分享量測結果」同樣的文字）
 */
class ResultFiles(private val root: File?) {
    fun newBatchDir(source: MicSource): File? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return root?.let { File(it, "results/${stamp}_${source.name}") }?.takeIf { it.mkdirs() || it.isDirectory }
    }

    /** 16-bit 單聲道 WAV */
    fun writeWav(file: File, samples: DoubleArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            fun le32(v: Int) = out.writeInt(Integer.reverseBytes(v))
            fun le16(v: Int) = out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
            out.writeBytes("RIFF"); le32(36 + dataBytes); out.writeBytes("WAVE")
            out.writeBytes("fmt "); le32(16); le16(1); le16(1); le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
            out.writeBytes("data"); le32(dataBytes)
            for (s in samples) le16((s * 32767).roundToInt().coerceIn(-32768, 32767))
        }
    }
}
