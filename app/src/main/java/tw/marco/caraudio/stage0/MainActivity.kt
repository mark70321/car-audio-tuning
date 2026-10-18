package tw.marco.caraudio.stage0

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        EqController.initIfNeeded()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

// ---------- 裝置資訊 ----------

data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val brand: String,
    val release: String,
    val sdk: Int,
    val unprocessed: Boolean,
)

fun readDeviceInfo(context: Context): DeviceInfo {
    val am = context.getSystemService(AudioManager::class.java)
    val unprocessed = runCatching {
        am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }.getOrDefault(false)
    return DeviceInfo(Build.MODEL, Build.MANUFACTURER, Build.BRAND, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, unprocessed)
}

// ---------- 測試清單（存在 SharedPreferences，關掉 App 也不會不見）----------

enum class CheckResult(@StringRes val label: Int) {
    UNTESTED(R.string.result_untested), WORKS(R.string.result_works), NO_EFFECT(R.string.result_no_effect)
}

val checkItems = listOf(
    R.string.check_item_local,
    R.string.check_item_carplay,
    R.string.check_item_bt,
    R.string.check_item_phone,
)

class ChecklistStore(context: Context) {
    private val prefs = context.getSharedPreferences("checklist", Context.MODE_PRIVATE)
    val results = mutableStateListOf<CheckResult>().apply {
        for (i in checkItems.indices) for (e in Engine.entries) add(load(i, e))
    }

    private fun key(item: Int, engine: Engine) = "item${item}_${engine.name}"
    private fun index(item: Int, engine: Engine) = item * Engine.entries.size + engine.ordinal

    private fun load(item: Int, engine: Engine) = runCatching {
        CheckResult.valueOf(prefs.getString(key(item, engine), null) ?: CheckResult.UNTESTED.name)
    }.getOrDefault(CheckResult.UNTESTED)

    fun get(item: Int, engine: Engine) = results[index(item, engine)]

    fun set(item: Int, engine: Engine, value: CheckResult) {
        results[index(item, engine)] = value
        runCatching { prefs.edit().putString(key(item, engine), value.name).apply() }
    }

    fun clear() {
        for (i in checkItems.indices) for (e in Engine.entries) set(i, e, CheckResult.UNTESTED)
    }
}

// ---------- 畫面 ----------

private val TitleSize = 30.sp
private val BodySize = 22.sp

@Composable
fun App() {
    val context = LocalContext.current
    val info = remember { readDeviceInfo(context) }
    val store = remember { ChecklistStore(context) }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (maxWidth >= 840.dp) {
            // 橫式大螢幕（車機）：左右兩欄
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ScrollColumn(Modifier.weight(1f)) {
                    DeviceCard(info)
                    EqCard()
                }
                ScrollColumn(Modifier.weight(1f)) { ChecklistCard(store, info) }
            }
        } else {
            ScrollColumn(Modifier.fillMaxSize().padding(16.dp)) {
                DeviceCard(info)
                EqCard()
                ChecklistCard(store, info)
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
private fun Section(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(title), fontSize = TitleSize, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Body(text: String, bold: Boolean = false) {
    Text(text, fontSize = BodySize, lineHeight = 30.sp, fontWeight = if (bold) FontWeight.Bold else null)
}

@Composable
private fun InfoRow(@StringRes label: Int, value: String) {
    Body("${stringResource(label)}：$value")
}

@Composable
private fun DeviceCard(info: DeviceInfo) {
    Section(R.string.section_device) {
        InfoRow(R.string.device_model, info.model)
        InfoRow(R.string.device_manufacturer, info.manufacturer)
        InfoRow(R.string.device_brand, info.brand)
        InfoRow(R.string.device_android_version, info.release)
        InfoRow(R.string.device_api_level, info.sdk.toString())
        InfoRow(R.string.device_unprocessed, stringResource(if (info.unprocessed) R.string.yes else R.string.no))
    }
}

@Composable
private fun engineName(engine: Engine) = stringResource(
    if (engine == Engine.DYNAMICS) R.string.engine_dynamics else R.string.engine_equalizer
)

@Composable
private fun statusText(status: EngineStatus): String = when (status) {
    EngineStatus.NotTried -> stringResource(R.string.engine_status_not_tried)
    EngineStatus.Unsupported -> stringResource(R.string.engine_status_unsupported)
    EngineStatus.Skipped -> stringResource(R.string.engine_status_skipped)
    is EngineStatus.Fail -> stringResource(R.string.engine_status_fail, status.error)
    is EngineStatus.DynamicsOk ->
        stringResource(R.string.engine_status_ok) + "\n" + stringResource(R.string.engine_detail_dp, status.bands)
    is EngineStatus.EqualizerOk ->
        stringResource(R.string.engine_status_ok) + "\n" +
            stringResource(R.string.engine_detail_eq, status.bandCount, status.minDb, status.maxDb, status.centers)
}

@Composable
private fun EqCard() {
    val dpStatus = EqController.dpStatus
    val eqStatus = EqController.eqStatus
    val activeCut = EqController.activeCut
    var selected by remember { mutableStateOf(Engine.DYNAMICS) }
    // 預設選第一個建立成功的 API
    LaunchedEffect(dpStatus, eqStatus) {
        if (!EqController.isReady(selected)) Engine.entries.firstOrNull { EqController.isReady(it) }?.let { selected = it }
    }

    Section(R.string.section_eq) {
        Body(stringResource(R.string.eq_hint))

        for (engine in Engine.entries) {
            val status = if (engine == Engine.DYNAMICS) dpStatus else eqStatus
            Body(engineName(engine), bold = true)
            Body(statusText(status))
            if (status is EngineStatus.Ok) {
                val control = EqController.hasControl(engine)
                Body(stringResource(R.string.engine_has_control, stringResource(if (control == true) R.string.yes else R.string.no)))
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (eqStatus == EngineStatus.Skipped) {
                OutlinedButton(onClick = { EqController.createEqualizerManually() }) {
                    Text(stringResource(R.string.engine_create_eq), fontSize = 18.sp)
                }
            }
            OutlinedButton(onClick = { EqController.createAll() }) {
                Text(stringResource(R.string.engine_retry), fontSize = 18.sp)
            }
        }

        Body(stringResource(R.string.choose_engine), bold = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (engine in Engine.entries) {
                FilterChip(
                    selected = selected == engine,
                    onClick = { selected = engine },
                    enabled = EqController.isReady(engine),
                    label = { Text(engineName(engine), fontSize = 20.sp, modifier = Modifier.padding(8.dp)) },
                )
            }
        }

        val ready = EqController.isReady(selected)
        Button(
            onClick = { EqController.cutBass(selected) },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().height(110.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.btn_cut_bass), fontSize = 36.sp, fontWeight = FontWeight.Bold) }
        Button(
            onClick = { EqController.restore() },
            modifier = Modifier.fillMaxWidth().height(110.dp),
        ) { Text(stringResource(R.string.btn_restore), fontSize = 36.sp, fontWeight = FontWeight.Bold) }

        if (!ready) Body(stringResource(R.string.engine_not_ready))
        Body(
            if (activeCut == null) stringResource(R.string.state_restored)
            else stringResource(R.string.state_cut, engineName(activeCut), EqController.cutDescription(activeCut)),
            bold = true,
        )
        EqController.lastError?.let { Body(stringResource(R.string.apply_error, it)) }
    }
}

@Composable
private fun ChecklistCard(store: ChecklistStore, info: DeviceInfo) {
    val context = LocalContext.current
    Section(R.string.section_checklist) {
        Body(stringResource(R.string.checklist_hint))
        checkItems.forEachIndexed { i, itemRes ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Body("${i + 1}. ${stringResource(itemRes)}", bold = true)
                    for (engine in Engine.entries) {
                        Text(engineName(engine), fontSize = 18.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (r in CheckResult.entries) {
                                FilterChip(
                                    selected = store.get(i, engine) == r,
                                    onClick = { store.set(i, engine, r) },
                                    label = { Text(stringResource(r.label), fontSize = 20.sp, modifier = Modifier.padding(6.dp)) },
                                    modifier = Modifier.heightIn(min = 56.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        val shareText = buildShareText(store, info)
        val shareTitle = stringResource(R.string.share_title)
        Button(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                runCatching { context.startActivity(Intent.createChooser(send, shareTitle)) }
            },
            modifier = Modifier.fillMaxWidth().height(80.dp),
        ) { Text(stringResource(R.string.btn_share), fontSize = 22.sp) }
        OutlinedButton(onClick = { store.clear() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.btn_clear), fontSize = 20.sp)
        }
    }
}

@Composable
private fun buildShareText(store: ChecklistStore, info: DeviceInfo): String {
    val lines = mutableListOf<String>()
    lines += "${stringResource(R.string.device_manufacturer)}：${info.manufacturer} / ${info.brand} / ${info.model}"
    lines += "${stringResource(R.string.device_android_version)}：${info.release}（API ${info.sdk}）"
    lines += stringResource(R.string.share_unprocessed_line, stringResource(if (info.unprocessed) R.string.yes else R.string.no))
    lines += "${engineName(Engine.DYNAMICS)}：${statusText(EqController.dpStatus)}"
    lines += "${engineName(Engine.EQUALIZER)}：${statusText(EqController.eqStatus)}"
    val names = Engine.entries.associateWith { engineName(it) }
    val labels = CheckResult.entries.associateWith { stringResource(it.label) }
    checkItems.forEachIndexed { i, itemRes ->
        val parts = Engine.entries.joinToString("，") { e -> "${names[e]}=${labels[store.get(i, e)]}" }
        lines += "${i + 1}. ${stringResource(itemRes)}：$parts"
    }
    return lines.joinToString("\n")
}
