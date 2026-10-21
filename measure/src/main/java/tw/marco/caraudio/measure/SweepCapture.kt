package tw.marco.caraudio.measure

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tw.marco.caraudio.core.LogSweep
import kotlin.math.roundToInt

enum class MicSource(val id: Int, @StringRes val label: Int) {
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED, R.string.source_unprocessed),
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, R.string.source_voice_recognition),
    MIC(MediaRecorder.AudioSource.MIC, R.string.source_mic),
}

fun supportsUnprocessed(context: Context): Boolean = runCatching {
    context.getSystemService(AudioManager::class.java)
        ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
}.getOrDefault(false)

/**
 * 主要收音方式：支援原始就用原始（2026-10 紅米實測：低頻完整、高頻與語音辨識一致），否則語音辨識。
 * 音量只照它校準。
 */
fun primarySource(context: Context): MicSource =
    if (supportsUnprocessed(context)) MicSource.UNPROCESSED else MicSource.VOICE_RECOGNITION

/** 一次錄音的原始資料：前 [prerollSamples] 個樣本是播放前的背景噪音。 */
class Capture(val samples: DoubleArray, val sampleRate: Int, val prerollSamples: Int)

object SweepCapture {
    private const val PREROLL_SEC = 0.5
    /** 掃頻結束後多錄的時間：容納藍牙延遲（通常 0.1～0.5 秒）與車廂殘響 */
    private const val TAIL_SEC = 2.5
    /**
     * 開始錄音前先播的靜音：藍牙音響剛收到聲音時可能「啵」一聲（2026-10 哈曼實測，錄到 -19 dBFS 爆音），
     * 讓它在錄音開始前發生，並讓音訊串流一路不中斷到掃頻播完。
     */
    private const val WAKE_MS = 1500L
    /** 錄音卡住（例如麥克風被通話占用）時最多多等幾秒就放棄 */
    private const val STALL_GRACE_MS = 5_000L
    private val RATES = listOf(48_000, 44_100)

    @SuppressLint("MissingPermission")
    private fun openRecord(source: MicSource, fs: Int): AudioRecord? {
        val inMin = AudioRecord.getMinBufferSize(fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (inMin <= 0) return null
        val record = runCatching {
            AudioRecord(source.id, fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(inMin, fs / 5 * 2) * 2)
        }.getOrNull() ?: return null
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return record
    }

    /** 實際建立一次錄音器，回傳這個收音來源能用的取樣率（優先 48kHz）。需要已取得麥克風權限。 */
    fun probeSampleRate(source: MicSource): Int? = RATES.firstOrNull { fs ->
        openRecord(source, fs)?.let { it.release(); true } ?: false
    }

    private fun buildTrack(fs: Int): AudioTrack {
        val outMin = AudioTrack.getMinBufferSize(fs, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(fs)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(outMin, fs / 5 * 2) * 2)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("AudioTrack init failed ($fs Hz)")
        }
        return track
    }

    /**
     * 先播 1.5 秒靜音喚醒喇叭 → 開始錄音 → 先錄 0.5 秒背景噪音 → 用媒體音訊播放掃頻（有連藍牙就從車機喇叭出來）→ 錄完整段。
     * 呼叫前必須已取得 RECORD_AUDIO 權限。協程被取消時會停止錄音與播放。
     */
    suspend fun run(source: MicSource, sweep: LogSweep): Capture = withContext(Dispatchers.IO) {
        val fs = sweep.sampleRate
        val pcm = ShortArray(sweep.samples.size) { (sweep.samples[it] * 32767).roundToInt().toShort() }
        val preroll = (PREROLL_SEC * fs).toInt()
        // 多留 0.5 秒：送出掃頻時播放緩衝區（約 0.4 秒）裡可能還排著靜音，掃頻會晚一點開始
        val total = preroll + pcm.size + (TAIL_SEC * fs).toInt() + fs / 2

        val record = openRecord(source, fs) ?: error("AudioRecord init failed (source ${source.name}, $fs Hz)")
        val track = try {
            buildTrack(fs)
        } catch (e: Exception) {
            record.release()
            throw e
        }

        val buf = ShortArray(total)
        val playError = AtomicReference<Throwable?>(null)
        val recorded = AtomicInteger(0)
        val startSweep = AtomicBoolean(false)
        val done = AtomicBoolean(false)
        // 掃頻在錄音裡實際開始的位置：送出掃頻時播放緩衝區裡還排著的靜音也要算進去
        val sweepAt = AtomicInteger(preroll)
        // 串流模式 write 會阻塞，放在另一條執行緒，錄音迴圈不停
        val player = Thread {
            try {
                track.play()
                val zeros = ShortArray(fs / 50) // 20ms
                var written = 0L
                while (!startSweep.get() && !done.get()) {
                    val w = track.write(zeros, 0, zeros.size)
                    if (w < 0) error("AudioTrack.write = $w")
                    written += w
                }
                if (!done.get()) {
                    val queued = (written - (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL)).coerceAtLeast(0)
                    sweepAt.set(recorded.get() + queued.toInt())
                    val w = track.write(pcm, 0, pcm.size)
                    if (w < 0 && !done.get()) error("AudioTrack.write = $w")
                }
            } catch (e: Throwable) {
                playError.set(e) // 例如藍牙中途斷線；不能讓例外讓整個 App 閃退
            }
        }
        val deadline = SystemClock.elapsedRealtime() + WAKE_MS + total * 1000L / fs + STALL_GRACE_MS
        try {
            player.start()
            delay(WAKE_MS)
            record.startRecording()
            var n = 0
            while (n < total) {
                ensureActive()
                if (SystemClock.elapsedRealtime() > deadline) error("recording stalled at $n / $total samples")
                val read = record.read(buf, n, minOf(1024, total - n))
                if (read < 0) error("AudioRecord.read = $read")
                n += read
                recorded.set(n)
                if (n >= preroll) startSweep.set(true)
                playError.get()?.let { throw IllegalStateException("playback failed: ${it.message}", it) }
            }
        } finally {
            done.set(true)
            runCatching { record.stop() }
            record.release()
            runCatching { track.pause(); track.flush() } // 讓阻塞中的 write 提早返回
            player.join(2000)
            runCatching { track.stop() }
            // 播放執行緒還卡在 write 裡（藍牙卡住）時不釋放，避免底層崩潰；寧可漏一個 AudioTrack
            if (!player.isAlive) track.release()
        }
        playError.get()?.let { throw IllegalStateException("playback failed: ${it.message}", it) }
        Capture(DoubleArray(total) { buf[it] / 32768.0 }, fs, sweepAt.get())
    }
}
