package tw.marco.caraudio.stage0

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Engine { DYNAMICS, EQUALIZER }

sealed interface EngineStatus {
    data object NotTried : EngineStatus
    data object Unsupported : EngineStatus
    data object Skipped : EngineStatus
    sealed interface Ok : EngineStatus
    data class DynamicsOk(val bands: String) : Ok
    data class EqualizerOk(val bandCount: Int, val minDb: Int, val maxDb: Int, val centers: String) : Ok
    data class Fail(val error: String) : EngineStatus
}

/**
 * 階段 0 驗證用：把 EQ 掛在全域音訊 session 0，看會不會影響其他 App 的聲音。
 * 放在 object 裡，Activity 轉向或退到背景時效果不會被釋放。
 */
object EqController {
    private const val GLOBAL_SESSION = 0
    private const val PRIORITY = 100
    private const val BASS_LIMIT_HZ = 250f
    private const val DP_CUT_DB = -40f

    // DynamicsProcessing 的 preEq 每個頻段以「上限頻率」定義
    private val dpCutoffs = floatArrayOf(60f, 120f, 250f, 500f, 1000f, 4000f, 20000f)

    private var dp: Any? = null // DynamicsProcessing，API 28 以下不能直接宣告型別
    private var eq: Equalizer? = null

    var dpStatus by mutableStateOf<EngineStatus>(EngineStatus.NotTried)
        private set
    var eqStatus by mutableStateOf<EngineStatus>(EngineStatus.NotTried)
        private set
    /** null = 原狀；否則為目前砍低音中的引擎 */
    var activeCut by mutableStateOf<Engine?>(null)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    fun initIfNeeded() {
        if (dpStatus == EngineStatus.NotTried) createAll()
    }

    fun createAll() {
        releaseAll()
        dpStatus = createDynamics()
        eqStatus = if (dpStatus is EngineStatus.Ok) EngineStatus.Skipped else createEqualizer()
    }

    fun createEqualizerManually() {
        eq?.release()
        eq = null
        eqStatus = createEqualizer()
    }

    fun isReady(engine: Engine) = when (engine) {
        Engine.DYNAMICS -> dpStatus is EngineStatus.Ok
        Engine.EQUALIZER -> eqStatus is EngineStatus.Ok
    }

    fun hasControl(engine: Engine): Boolean? = runCatching {
        when (engine) {
            Engine.DYNAMICS -> (dp as? android.media.audiofx.AudioEffect)?.hasControl()
            Engine.EQUALIZER -> eq?.hasControl()
        }
    }.getOrNull()

    fun cutBass(engine: Engine) {
        lastError = null
        restore()
        try {
            when (engine) {
                Engine.DYNAMICS -> if (Build.VERSION.SDK_INT >= 28) dpSetBassCut(true)
                Engine.EQUALIZER -> eqSetBassCut(true)
            }
            activeCut = engine
        } catch (t: Throwable) {
            lastError = t.describe()
        }
    }

    fun restore() {
        try {
            if (Build.VERSION.SDK_INT >= 28 && dp != null) dpSetBassCut(false)
            if (eq != null) eqSetBassCut(false)
        } catch (t: Throwable) {
            lastError = t.describe()
        }
        activeCut = null
    }

    /** 給畫面顯示「砍了多少 dB」 */
    fun cutDescription(engine: Engine): String = when (engine) {
        Engine.DYNAMICS -> "${DP_CUT_DB.toInt()} dB"
        Engine.EQUALIZER -> eq?.bandLevelRange?.let { "${it[0] / 100} dB" } ?: "?"
    }

    private fun createDynamics(): EngineStatus {
        if (Build.VERSION.SDK_INT < 28) return EngineStatus.Unsupported
        return try {
            dp = buildDynamics()
            EngineStatus.DynamicsOk(dpCutoffs.joinToString(" / ") { "≤${it.toInt()}Hz" })
        } catch (t: Throwable) {
            dp = null
            EngineStatus.Fail(t.describe())
        }
    }

    @RequiresApi(28)
    private fun buildDynamics(): DynamicsProcessing {
        val bandCount = dpCutoffs.size
        val builder = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,               // 聲道數
            true, bandCount, // preEq 開
            false, 0,        // MBC 關
            false, 0,        // postEq 關
            false,           // limiter 關
        )
        val preEq = DynamicsProcessing.Eq(true, true, bandCount)
        dpCutoffs.forEachIndexed { i, f -> preEq.setBand(i, DynamicsProcessing.EqBand(true, f, 0f)) }
        builder.setPreEqAllChannelsTo(preEq)
        return DynamicsProcessing(PRIORITY, GLOBAL_SESSION, builder.build()).apply { enabled = false }
    }

    @RequiresApi(28)
    private fun dpSetBassCut(cut: Boolean) {
        val effect = dp as? DynamicsProcessing ?: error("DynamicsProcessing not created")
        dpCutoffs.forEachIndexed { i, f ->
            val gain = if (cut && f <= BASS_LIMIT_HZ) DP_CUT_DB else 0f
            effect.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, f, gain))
        }
        effect.enabled = cut
    }

    private fun createEqualizer(): EngineStatus = try {
        @Suppress("DEPRECATION") // 全域 session 0 已被標為不建議，但這正是要驗證的
        val e = Equalizer(PRIORITY, GLOBAL_SESSION).apply { enabled = false }
        eq = e
        val range = e.bandLevelRange
        val centers = (0 until e.numberOfBands).joinToString(" / ") { b ->
            formatHz(e.getCenterFreq(b.toShort()) / 1000)
        }
        EngineStatus.EqualizerOk(e.numberOfBands.toInt(), range[0] / 100, range[1] / 100, centers)
    } catch (t: Throwable) {
        eq = null
        EngineStatus.Fail(t.describe())
    }

    private fun eqSetBassCut(cut: Boolean) {
        val e = eq ?: error("Equalizer not created")
        val min = e.bandLevelRange[0]
        for (b in 0 until e.numberOfBands) {
            val band = b.toShort()
            val centerHz = e.getCenterFreq(band) / 1000f
            val level: Short = if (cut && centerHz <= BASS_LIMIT_HZ) min else 0
            e.setBandLevel(band, level)
        }
        e.enabled = cut
    }

    private fun releaseAll() {
        runCatching { (dp as? android.media.audiofx.AudioEffect)?.release() }
        runCatching { eq?.release() }
        dp = null
        eq = null
        activeCut = null
    }

    private fun formatHz(hz: Int) = if (hz >= 1000) "${hz / 1000}k" else "$hz"

    private fun Throwable.describe() = "${javaClass.simpleName}: ${message ?: "-"}"
}
