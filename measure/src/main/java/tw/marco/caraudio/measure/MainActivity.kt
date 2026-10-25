package tw.marco.caraudio.measure

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
    /** 電腦用 adb 送來、還沒處理的遙控指令 */
    private val remote = mutableStateOf<RemoteCommand?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 畫面重建時不要把同一個指令再跑一次
        if (savedInstanceState == null) remote.value = RemoteCommand.from(intent)
        setIntent(Intent()) // 處理過就清掉，之後從最近使用的 App 打開不會又自動播測試音
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(remote.value) { remote.value = null } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        RemoteCommand.from(intent)?.let { remote.value = it }
    }
}

/**
 * 電腦遙控量測（使用者在車上不用碰手機）：
 * adb shell am start -n tw.marco.caraudio.measure/.MainActivity -a tw.marco.caraudio.measure.REMOTE_MEASURE
 *   --es source UNPROCESSED --ei runs 1 --ez leaveCar true --ez recalibrate false
 * 每個參數都可省略（省略＝照畫面目前的設定）。結果照常存檔，電腦看 summary.txt 出現就代表這批結束；
 * 指令沒被執行時改寫 files/remote_rejected.txt（原因），電腦不用白等。
 */
data class RemoteCommand(val source: MicSource?, val runs: Int?, val leaveCar: Boolean?, val recalibrate: Boolean) {
    companion object {
        const val ACTION = "tw.marco.caraudio.measure.REMOTE_MEASURE"

        fun from(intent: Intent?): RemoteCommand? {
            if (intent?.action != ACTION) return null
            // 從「最近使用的 App」重新打開時，系統會把當初的指令再送一次，不能當成新指令
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            return RemoteCommand(
                source = intent.getStringExtra("source")?.let { n -> MicSource.entries.firstOrNull { it.name == n } },
                runs = intent.getIntExtra("runs", 0).takeIf { it in RUN_OPTIONS },
                leaveCar = if (intent.hasExtra("leaveCar")) intent.getBooleanExtra("leaveCar", true) else null,
                recalibrate = intent.getBooleanExtra("recalibrate", false),
            )
        }
    }
}

// ---------- 量測流程（結果只放記憶體，關掉 App 就清空）----------

/** 連測次數選項：正式量測 1 次就夠，驗證重複性時才用 2～3 次（測試音很刺耳，能少就少） */
val RUN_OPTIONS = listOf(1, 2, 3)
/** 開始前倒數幾秒，讓使用者下車關門 */
private const val COUNTDOWN_SEC = 15
/** 自動校準時峰值超過這個就調小一格 */
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
    /** 每種收音方式最後一批實際用的音量（比固定值大聲會爆音的收音方式會往下調） */
    val batchVolumes = mutableStateMapOf<MicSource, Int>()

    var running by mutableStateOf(false)
    var step by mutableIntStateOf(0)
    var runs by mutableIntStateOf(1)
    /** 開始前倒數，讓人先下車（人在車內聽測試音很難受），測完嗶嗶叫人回來 */
    var leaveCar by mutableStateOf(true)
    /** 倒數剩幾秒，0 = 沒在倒數 */
    var countdown by mutableIntStateOf(0)
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
     * 音量只照主要收音方式校準（2026-10-21 車上實測：以最大聲的語音辨識為準時，紅米原始收音峰值只有 -34 dBFS、
     * 訊噪比 15 dB；只照原始校準可多約 20 dB）。
     * 還沒固定音量：用主要收音方式找合適的一格並固定。
     * 之後每批：設回固定那一格；這次的收音方式若會爆音，只往下調（這批用比較小的音量，記在 [batchVolumes]），固定值不變。
     */
    private suspend fun calibrate(source: MicSource, fs: Int) {
        val ref = primarySource(context).takeIf { p ->
            p == source || withContext(Dispatchers.IO) { runCatching { SweepCapture.probeSampleRate(p) }.getOrNull() } == fs
        } ?: source
        val refPeak = if (lockedVolume == null) lockVolume(ref, fs) else null
        var vol = lockedVolume!!
        if (setVolume(vol) != vol) throw IllegalStateException(context.getString(R.string.cal_volume_stuck))
        // 剛用同一種收音方式校準完就不用再暖機一次
        var peak = if (ref == source && refPeak != null) refPeak else warmUp(source, fs)
        while (peak > -1.0) {
            if (vol <= 1) throw IllegalStateException(context.getString(R.string.cal_too_loud_min, peak))
            vol -= 1
            if (setVolume(vol) != vol) throw IllegalStateException(context.getString(R.string.cal_volume_stuck))
            delay(500)
            peak = warmUp(source, fs)
        }
        batchVolumes[source] = vol
    }

    /** 用 [ref] 收音方式找合適的媒體音量並固定到 [lockedVolume]，回傳最後一次暖機的峰值。 */
    private suspend fun lockVolume(ref: MicSource, fs: Int): Double {
        var vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        var wentUp = false
        repeat(CAL_MAX_TRIES) {
            if (setVolume(vol) != vol) throw IllegalStateException(context.getString(R.string.cal_volume_stuck))
            val peak = warmUp(ref, fs)
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
                        return peak
                    }
                    vol -= 1
                }
                else -> {
                    lockedVolume = vol
                    return peak
                }
            }
            delay(500)
        }
        throw IllegalStateException(context.getString(R.string.cal_failed))
    }

    /** 測完嗶三聲（走媒體音量，會從車機喇叭出來），叫下車的人回來；失敗時改成低沉的錯誤音 */
    private suspend fun doneBeep(ok: Boolean) {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull() ?: return
        try {
            repeat(3) {
                tone.startTone(if (ok) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK, 200)
                delay(400)
            }
        } finally {
            tone.release()
        }
    }

    private suspend fun runBatch(source: MicSource) {
        error = null
        results.remove(source)
        batchVolumes.remove(source)
        val list = mutableListOf<MeasurementResult>()
        savedDir = null
        val dir = files.newBatchDir(source)
        try {
            val fs = withContext(Dispatchers.IO) { SweepCapture.probeSampleRate(source) }
                ?: throw IllegalStateException("this phone cannot record from ${source.name}")
            sampleRate = fs
            val sweep = LogSweep(fs, durationSec = 6.0).also { this.sweep = it }
            step = 0
            if (leaveCar) {
                for (sec in COUNTDOWN_SEC downTo 1) {
                    countdown = sec
                    delay(1000)
                }
                countdown = 0
            }
            calibrate(source, fs)
            delay(1000)
            val runs = runs
            for (i in 1..runs) {
                step = i
                val capture = SweepCapture.run(source, sweep)
                list += withContext(Dispatchers.Default) {
                    if (dir != null) runCatching { files.writeWav(File(dir, "run$i.wav"), capture.samples, fs) }
                    MeasurementAnalyzer.analyze(capture.samples, sweep, capture.prerollSamples)
                }
                results[source] = list.toList()
                if (i < runs) delay(1500) // 讓車廂殘響散掉
            }
            if (leaveCar) doneBeep(ok = true)
        } catch (e: CancellationException) {
            throw e // 離開畫面：正常取消，不當成錯誤
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            if (leaveCar) doneBeep(ok = false)
        } finally {
            running = false
            step = 0
            countdown = 0
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
fun App(remote: RemoteCommand?, onRemoteHandled: () -> Unit) {
    val context = LocalContext.current
    val session = remember { MeasureSession(context.applicationContext) }
    var source by remember {
        mutableStateOf(primarySource(context))
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(remote) {
        val cmd = remote ?: return@LaunchedEffect
        onRemoteHandled()
        val rejected = File(context.getExternalFilesDir(null), "remote_rejected.txt")
        runCatching { rejected.delete() }
        fun reject(reason: String) {
            session.error = reason
            runCatching { rejected.writeText(reason) }
        }
        if (session.running) return@LaunchedEffect reject(context.getString(R.string.remote_busy))
        cmd.source?.let { source = it }
        cmd.runs?.let { session.runs = it }
        cmd.leaveCar?.let { session.leaveCar = it }
        if (cmd.recalibrate) { session.lockedVolume = null; session.batchVolumes.clear() }
        // 遙控沒辦法按權限視窗：先用 adb shell pm grant 給麥克風權限
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            session.start(scope, source)
        } else {
            reject(context.getString(R.string.remote_no_permission))
        }
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
    // 倒數＋量測期間人可能在車外，不能讓螢幕關掉
    val view = LocalView.current
    DisposableEffect(session.running) {
        view.keepScreenOn = session.running
        onDispose { view.keepScreenOn = false }
    }
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

        Body(stringResource(R.string.choose_runs), bold = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (n in RUN_OPTIONS) {
                FilterChip(
                    selected = session.runs == n,
                    onClick = { session.runs = n },
                    enabled = !session.running,
                    label = { Text(stringResource(R.string.runs_option, n), fontSize = 20.sp, modifier = Modifier.padding(8.dp)) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = session.leaveCar, onCheckedChange = { session.leaveCar = it }, enabled = !session.running)
            Body(stringResource(R.string.leave_car))
        }

        Button(
            onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) start() else permission.launch(Manifest.permission.RECORD_AUDIO)
            },
            enabled = !session.running,
            modifier = Modifier.fillMaxWidth().height(110.dp),
        ) { Text(stringResource(R.string.btn_start, session.runs), fontSize = 34.sp, fontWeight = FontWeight.Bold) }

        if (session.running) {
            Body(
                when {
                    session.countdown > 0 -> stringResource(R.string.countdown, session.countdown)
                    session.step == 0 -> stringResource(R.string.calibrating)
                    else -> stringResource(R.string.running, session.step, session.runs)
                },
                bold = true,
            )
        }
        if (denied) Body(stringResource(R.string.permission_denied), color = MaterialTheme.colorScheme.error)
        session.error?.let { Body(stringResource(R.string.measure_error, it), color = MaterialTheme.colorScheme.error) }
        session.savedDir?.let { Text(stringResource(R.string.saved_to, it), fontSize = 16.sp) }
        session.lockedVolume?.let { v ->
            Body(stringResource(R.string.volume_locked, v, session.maxVolume, stringResource(primarySource(context).label)))
            session.batchVolumes[source]?.takeIf { it != v }?.let { Body(stringResource(R.string.volume_lowered, it)) }
            OutlinedButton(onClick = { session.lockedVolume = null; session.batchVolumes.clear() }, enabled = !session.running) {
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
        val rows = MicSource.entries.mapNotNull { s -> session.results[s]?.takeIf { it.isNotEmpty() }?.let { s to it } }
        if (rows.isEmpty()) Body(stringResource(R.string.compare_empty))
        for ((s, list) in rows) {
            val snr = list.map { it.levels.snrDb }.average()
            val rep = repeatabilityOf(list)
            if (rep != null) {
                Body(stringResource(R.string.compare_line, stringResource(s.label), rep.midSpreadDb, verdictText(rep.verdict), snr))
            } else {
                Body(stringResource(R.string.compare_line_single, stringResource(s.label), snr))
            }
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
    session.lockedVolume?.let { lines += "固定音量（照${s(primarySource(context).label)}校準）：媒體音量第 $it 格／共 ${session.maxVolume} 格" }
    session.sweep?.let { lines += "取樣率 ${it.sampleRate} Hz，掃頻 ${it.startHz.toInt()}～${it.endHz.toInt()} Hz / ${it.durationSec} 秒" }
    val centers = FrequencyResponse.thirdOctaveCenters
    for ((source, list) in session.results) {
        if (only != null && source != only) continue
        lines += ""
        lines += "【${s(source.label)}】"
        session.batchVolumes[source]?.let { lines += "量測音量：媒體音量第 $it 格／共 ${session.maxVolume} 格" }
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
