package tw.marco.caraudio.measure

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.marco.caraudio.core.FrequencyResponse
import tw.marco.caraudio.core.LogSweep
import tw.marco.caraudio.core.MeasurementAnalyzer
import tw.marco.caraudio.core.MeasurementResult
import tw.marco.caraudio.core.Repeatability
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

// ---------- 量測流程（結果只放記憶體，關掉 App 就清空）----------

const val RUNS = 3
/** 自動校準時峰值超過這個就調小一格：留餘裕給比較大聲的收音方式（紅米實測語音辨識比原始大約 10 dB） */
private const val CAL_HOT_DBFS = -4.0
private const val CAL_MAX_TRIES = 10

class MeasureSession(private val context: Context) {
    private val files = ResultFiles(context.getExternalFilesDir(null))
    /** 最後一批結果存到哪個資料夾（存檔失敗則為 null） */
    var savedDir by mutableStateOf<String?>(null)

    /** 第一次量測時才決定（要先有麥克風權限才能實際試錄音器） */
    var sampleRate by mutableStateOf<Int?>(null)
    /** 分享文字用：最後一次使用的掃頻設定 */
    var sweep: LogSweep? = null

    private val audio = context.getSystemService(AudioManager::class.java)
    val maxVolume: Int = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    /**
     * 固定的量測音量（媒體音量第幾格）。第一次自動校準後固定下來，之後每種收音方式都用同一格，比較才公平。
     * 藍牙音響的音量鈕通常直接連動手機媒體音量，所以由 App 統一設定。
     */
    var lockedVolume by mutableStateOf<Int?>(null)

    var running by mutableStateOf(false)
    var step by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
    val results = mutableStateMapOf<MicSource, List<MeasurementResult>>()

    /** 在主執行緒同步設定 running，連點兩下也只會開跑一次。 */
    fun start(scope: CoroutineScope, source: MicSource) {
        if (running) return
        running = true
        scope.launch { runBatch(source) }
    }

    /** 回傳實際生效的格數（有些裝置固定音量或不讓 App 改）。 */
    private fun setVolume(index: Int): Int {
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    /** 暖機兼音量檢查：播 2 秒短掃頻（結果不算，麥克風剛啟動時噪音偏高），回傳峰值 dBFS。 */
    private suspend fun warmUp(source: MicSource, fs: Int): Double {
        val warmSweep = LogSweep(fs, durationSec = 2.0)
        val warm = SweepCapture.run(source, warmSweep)
        return withContext(Dispatchers.Default) {
            MeasurementAnalyzer.analyze(warm.samples, warmSweep, warm.prerollSamples).levels.peakDbfs
        }
    }

    /**
     * 還沒固定音量：每種收音方式各暖機一次，以「最大聲的那種」為準調音量——
     * 同一個喇叭音量下各收音方式錄到的大小差很多（2026-10 紅米：語音辨識比原始大 12 dB 以上），
     * 只看一種會讓別種爆音。找到合適的一格後固定。
     * 已固定：設回那一格，只擋爆音；比較小聲的收音方式照樣量（看訊噪比），確保喇叭音量一致。
     */
    private suspend fun calibrate(source: MicSource, fs: Int) {
        lockedVolume?.let { locked ->
            if (setVolume(locked) != locked) throw IllegalStateException(context.getString(R.string.cal_volume_stuck))
            val peak = warmUp(source, fs)
            if (peak > -1.0) throw IllegalStateException(context.getString(R.string.cal_locked_too_loud, peak))
            return
        }
        // 這次要量的來源排最後，順便當它正式量測前的暖機
        val sources = withContext(Dispatchers.IO) {
            MicSource.entries.filter { s ->
                s != source && (s != MicSource.UNPROCESSED || supportsUnprocessed(context)) &&
                    runCatching { SweepCapture.probeSampleRate(s) }.getOrNull() == fs
            }
        } + source
        var vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        var wentUp = false
        repeat(CAL_MAX_TRIES) {
            if (setVolume(vol) != vol) throw IllegalStateException(context.getString(R.string.cal_volume_stuck))
            var peak = -200.0
            for (s in sources) {
                // 別種收音方式暫時錄不了就略過，只有這次要量的那種失敗才算失敗
                val p = if (s == source) warmUp(s, fs) else try {
                    warmUp(s, fs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (p != null) peak = maxOf(peak, p)
                if (peak > -1.0) break // 已經爆音，不用再試別種
            }
            when {
                peak < -15.0 -> {
                    if (vol >= maxVolume) throw IllegalStateException(context.getString(R.string.cal_too_quiet_max, peak))
                    vol = minOf(maxVolume, vol + if (peak < -25.0) 2 else 1) // 差很多時一次跳兩格，省時間
                    wentUp = true
                }
                peak > -1.0 || (peak > CAL_HOT_DBFS && !wentUp) -> {
                    if (vol <= 1) {
                        if (peak > -1.0) throw IllegalStateException(context.getString(R.string.cal_too_loud_min, peak))
                        lockedVolume = vol // 偏大聲但沒爆音，已經不能再小了就接受
                        return
                    }
                    vol -= 1
                }
                else -> {
                    lockedVolume = vol
                    return
                }
            }
            delay(500)
        }
        throw IllegalStateException(context.getString(R.string.cal_failed))
    }

    private suspend fun runBatch(source: MicSource) {
        error = null
        results.remove(source)
        val list = mutableListOf<MeasurementResult>()
        savedDir = null
        val dir = files.newBatchDir(source)
        try {
            val fs = withContext(Dispatchers.IO) { SweepCapture.probeSampleRate(source) }
                ?: throw IllegalStateException("this phone cannot record from ${source.name}")
            sampleRate = fs
            val sweep = LogSweep(fs, durationSec = 6.0).also { this.sweep = it }
            step = 0
            calibrate(source, fs)
            delay(1000)
            for (i in 1..RUNS) {
                step = i
                val capture = SweepCapture.run(source, sweep)
                list += withContext(Dispatchers.Default) {
                    if (dir != null) runCatching { files.writeWav(File(dir, "run$i.wav"), capture.samples, fs) }
                    MeasurementAnalyzer.analyze(capture.samples, sweep, capture.prerollSamples)
                }
                results[source] = list.toList()
                if (i < RUNS) delay(1500) // 讓車廂殘響散掉
            }
        } catch (e: CancellationException) {
            throw e // 離開畫面：正常取消，不當成錯誤
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            running = false
            step = 0
        }
        // 成功或中途失敗都存一份摘要（含錯誤訊息），方便事後追查
        if (dir != null) {
            val text = buildShareText(context, this, only = source) + (error?.let { "\n錯誤：$it" } ?: "")
            val ok = withContext(Dispatchers.IO) { runCatching { File(dir, "summary.txt").writeText(text) }.isSuccess }
            if (ok) savedDir = dir.absolutePath
        }
    }
}

fun repeatabilityOf(list: List<MeasurementResult>?): Repeatability? =
    list?.takeIf { it.size >= 2 }?.let { Repeatability(it.map { r -> r.response }) }

// ---------- 畫面 ----------

private val TitleSize = 30.sp
private val BodySize = 22.sp

/** 三次量測的線條顏色（深淺色主題都看得清楚） */
private val RunColors = listOf(Color(0xFF2E7DD7), Color(0xFFE8833A), Color(0xFF3BA55C))

@Composable
fun App() {
    val context = LocalContext.current
    val session = remember { MeasureSession(context.applicationContext) }
    var source by remember {
        mutableStateOf(if (supportsUnprocessed(context)) MicSource.UNPROCESSED else MicSource.VOICE_RECOGNITION)
    }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (maxWidth >= 840.dp) {
            // 橫式大螢幕：左右兩欄
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ScrollColumn(Modifier.weight(1f)) {
                    HowToCard()
                    MeasureCard(session, source) { source = it }
                }
                ScrollColumn(Modifier.weight(1f)) {
                    ResultCard(session, source)
                    CompareCard(session)
                }
            }
        } else {
            ScrollColumn(Modifier.fillMaxSize().padding(16.dp)) {
                HowToCard()
                MeasureCard(session, source) { source = it }
                ResultCard(session, source)
                CompareCard(session)
            }
        }
    }
}

@Composable
private fun ScrollColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = TitleSize, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Body(text: String, bold: Boolean = false, color: Color = Color.Unspecified) {
    Text(text, fontSize = BodySize, lineHeight = 30.sp, fontWeight = if (bold) FontWeight.Bold else null, color = color)
}

@Composable
private fun HowToCard() {
    Section(stringResource(R.string.section_howto)) {
        Body(stringResource(R.string.howto_steps))
        Body(stringResource(R.string.howto_volume_warning), bold = true)
    }
}

@Composable
private fun MeasureCard(session: MeasureSession, source: MicSource, onSource: (MicSource) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unprocessed = remember { supportsUnprocessed(context) }
    var denied by remember { mutableStateOf(false) }
    val start = { session.start(scope, source) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) start()
    }

    Section(stringResource(R.string.section_measure)) {
        Body(stringResource(R.string.choose_source), bold = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (s in MicSource.entries) {
                FilterChip(
                    selected = source == s,
                    onClick = { onSource(s) },
                    enabled = !session.running,
                    label = { Text(stringResource(s.label), fontSize = 20.sp, modifier = Modifier.padding(8.dp)) },
                )
            }
        }
        if (source == MicSource.UNPROCESSED && !unprocessed) Body(stringResource(R.string.source_unsupported))

        Button(
            onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) start() else permission.launch(Manifest.permission.RECORD_AUDIO)
            },
            enabled = !session.running,
            modifier = Modifier.fillMaxWidth().height(110.dp),
        ) { Text(stringResource(R.string.btn_start, RUNS), fontSize = 34.sp, fontWeight = FontWeight.Bold) }

        if (session.running) {
            Body(
                if (session.step == 0) stringResource(R.string.calibrating)
                else stringResource(R.string.running, session.step, RUNS),
                bold = true,
            )
        }
        if (denied) Body(stringResource(R.string.permission_denied), color = MaterialTheme.colorScheme.error)
        session.error?.let { Body(stringResource(R.string.measure_error, it), color = MaterialTheme.colorScheme.error) }
        session.savedDir?.let { Text(stringResource(R.string.saved_to, it), fontSize = 16.sp) }
        session.lockedVolume?.let { v ->
            Body(stringResource(R.string.volume_locked, v, session.maxVolume))
            OutlinedButton(onClick = { session.lockedVolume = null }, enabled = !session.running) {
                Text(stringResource(R.string.btn_recalibrate), fontSize = 20.sp)
            }
        }
        session.sampleRate?.let { Text(stringResource(R.string.sample_rate, it), fontSize = 16.sp) }
    }
}

@Composable
private fun verdictText(v: Repeatability.Verdict) = stringResource(
    when (v) {
        Repeatability.Verdict.GOOD -> R.string.verdict_good
        Repeatability.Verdict.OK -> R.string.verdict_ok
        Repeatability.Verdict.POOR -> R.string.verdict_poor
    }
)

@Composable
private fun ResultCard(session: MeasureSession, source: MicSource) {
    val list = session.results[source]
    Section(stringResource(R.string.section_result, stringResource(source.label))) {
        if (list.isNullOrEmpty()) {
            Body(stringResource(R.string.no_result))
            return@Section
        }
        ResponseChart(list.map { it.response })
        Text(stringResource(R.string.chart_hint), fontSize = 16.sp)
        list.forEachIndexed { i, r ->
            val l = r.levels
            Body(stringResource(R.string.run_line, i + 1, r.latencyMs, l.peakDbfs, l.snrDb), color = RunColors[i % RunColors.size])
            if (l.clipped) Body(stringResource(R.string.warn_clipped))
            if (l.noisy) Body(stringResource(R.string.warn_noisy))
        }
        repeatabilityOf(list)?.let { rep ->
            Body(stringResource(R.string.spread_line, rep.midSpreadDb), bold = true)
            Body(stringResource(R.string.offset_line, rep.levelOffsetDb))
            rep.validRangeHz?.let { (lo, hi) -> Body(stringResource(R.string.valid_range_line, lo, hi)) }
            Body(verdictText(rep.verdict), bold = true)
        }
    }
}

@Composable
private fun CompareCard(session: MeasureSession) {
    val context = LocalContext.current
    Section(stringResource(R.string.section_compare)) {
        val rows = MicSource.entries.mapNotNull { s -> repeatabilityOf(session.results[s])?.let { s to it } }
        if (rows.isEmpty()) Body(stringResource(R.string.compare_empty))
        for ((s, rep) in rows) {
            val snr = session.results[s]!!.map { it.levels.snrDb }.average()
            Body(stringResource(R.string.compare_line, stringResource(s.label), rep.midSpreadDb, verdictText(rep.verdict), snr))
        }

        val shareTitle = stringResource(R.string.share_title)
        Button(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, buildShareText(context, session))
                runCatching { context.startActivity(Intent.createChooser(send, shareTitle)) }
            },
            enabled = session.results.isNotEmpty() && !session.running,
            modifier = Modifier.fillMaxWidth().height(80.dp),
        ) { Text(stringResource(R.string.btn_share), fontSize = 22.sp) }
        OutlinedButton(
            onClick = { session.results.clear() },
            enabled = !session.running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.btn_clear), fontSize = 20.sp) }
    }
}

/** 頻率響應圖：橫軸對數 20Hz～20kHz，縱軸 dB（自動縮放到 10dB 一格）。 */
@Composable
private fun ResponseChart(curves: List<FrequencyResponse>) {
    val measurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val all = curves.flatMap { it.db.asList() }
    val top = ceil((all.maxOrNull() ?: 0.0) / 10) * 10
    val bottom = minOf(floor((all.minOrNull() ?: -10.0) / 10) * 10, top - 30)
    val fMin = 20.0
    val fMax = 20_000.0

    Canvas(Modifier.fillMaxWidth().height(300.dp)) {
        val left = 48.dp.toPx()
        val bottomPad = 20.dp.toPx()
        val w = size.width - left
        val h = size.height - bottomPad
        fun x(f: Double) = left + (w * log10(f / fMin) / log10(fMax / fMin)).toFloat()
        fun y(db: Double) = (h * (top - db) / (top - bottom)).toFloat()

        var level = top
        while (level >= bottom) {
            drawLine(gridColor, Offset(left, y(level)), Offset(size.width, y(level)))
            drawText(measurer, "${level.toInt()}", Offset(0f, y(level) - 8.dp.toPx()), labelStyle)
            level -= 10
        }
        for ((f, label) in listOf(20.0 to "20", 100.0 to "100", 1000.0 to "1k", 10_000.0 to "10k", 20_000.0 to "20k")) {
            drawLine(gridColor, Offset(x(f), 0f), Offset(x(f), h))
            drawText(measurer, label, Offset(x(f) - 8.dp.toPx(), h + 2.dp.toPx()), labelStyle)
        }
        for (f in listOf(50.0, 200.0, 500.0, 2000.0, 5000.0)) {
            drawLine(gridColor.copy(alpha = 0.4f), Offset(x(f), 0f), Offset(x(f), h))
        }

        curves.forEachIndexed { i, c ->
            val path = Path()
            c.freqs.indices.forEach { k ->
                val px = x(c.freqs[k])
                val py = y(c.db[k]).coerceIn(0f, h)
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, RunColors[i % RunColors.size], style = Stroke(width = 2.5.dp.toPx()))
        }
    }
}

// ---------- 分享文字（給 Claude 分析用，含 1/3 倍頻程數值）----------

/** [only] 不為 null 時只輸出該收音來源（存檔用）。 */
private fun buildShareText(context: Context, session: MeasureSession, only: MicSource? = null): String {
    fun s(@StringRes id: Int) = context.getString(id)
    val lines = mutableListOf<String>()
    lines += "裝置：${Build.MANUFACTURER} / ${Build.MODEL}（Android ${Build.VERSION.RELEASE}，API ${Build.VERSION.SDK_INT}）"
    session.lockedVolume?.let { lines += "量測音量：媒體音量第 $it 格／共 ${session.maxVolume} 格" }
    session.sweep?.let { lines += "取樣率 ${it.sampleRate} Hz，掃頻 ${it.startHz.toInt()}～${it.endHz.toInt()} Hz / ${it.durationSec} 秒" }
    val centers = FrequencyResponse.thirdOctaveCenters
    for ((source, list) in session.results) {
        if (only != null && source != only) continue
        lines += ""
        lines += "【${s(source.label)}】"
        list.forEachIndexed { i, r ->
            val l = r.levels
            lines += "第${i + 1}次：延遲 %.0f ms、峰值 %.1f dBFS、噪音 %.1f dBFS、訊噪比 %.0f dB".format(
                r.latencyMs, l.peakDbfs, l.noiseDbfs, l.snrDb,
            )
        }
        repeatabilityOf(list)?.let { rep ->
            val range = rep.validRangeHz?.let { "%.0f～%.0f Hz".format(it.first, it.second) } ?: "-"
            lines += "形狀差異（100Hz～10kHz，有效頻段內）最大 %.1f dB，整體音量偏移 %.1f dB，有效頻段 %s".format(
                rep.midSpreadDb, rep.levelOffsetDb, range,
            )
        }
        lines += "Hz\t" + list.indices.joinToString("\t") { "第${it + 1}次(dB)" }
        for (f in centers) {
            val values = list.joinToString("\t") { r ->
                val k = r.response.freqs.indices.minBy { kotlin.math.abs(r.response.freqs[it] - f) }
                "%.1f".format(r.response.db[k])
            }
            lines += "${f.toInt()}\t$values"
        }
    }
    return lines.joinToString("\n")
}
