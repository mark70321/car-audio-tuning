package tw.marco.caraudio.probe

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.input.InputManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 車機資訊：打開就列出車機規格，按「存檔」寫到「下載/車機資訊_<時間>.txt」，
 * 再用車機檔案管理員複製到隨身碟帶回電腦。畫面也可以直接拍照。
 * 開著時按方向盤按鍵，畫面會顯示收到的按鍵代碼（看一般 App 收不收得到）。
 * 只用一般 Android 畫面元件，不靠其他套件，入門晶片也跑得動。
 */
class MainActivity : Activity() {
    private lateinit var keysView: TextView
    private val keys = mutableListOf<String>()
    private var report = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        report = buildReport()

        val status = TextView(this).apply { textSize = 20f }
        val save = Button(this).apply {
            text = "存檔到「下載」資料夾"
            textSize = 24f
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 23 &&
                    checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
                    status.text = "允許存取檔案後，請再按一次存檔"
                } else {
                    status.text = save()
                }
            }
        }
        keysView = TextView(this).apply {
            textSize = 20f
            text = "方向盤按鍵測試：開著這個畫面按方向盤上的按鍵（音量、上下首、接電話…），收到的會列在這裡"
        }
        val body = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
            text = report
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.WHITE)
            for (v in listOf(save, status, keysView, body)) {
                if (v is TextView && v !is Button) v.setTextColor(Color.BLACK)
                addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 24 })
            }
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    /** 方向盤按鍵通常會變成一般按鍵事件；收得到就代表自製介面可以用方向盤操作 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            keys += "${KeyEvent.keyCodeToString(event.keyCode)}（代碼 ${event.keyCode}，來自 ${event.device?.name ?: "?"}）"
            keysView.text = "收到的按鍵：\n" + keys.takeLast(15).joinToString("\n")
        }
        // 返回鍵照常離開，其他按鍵（音量等）也照常交給系統
        return super.dispatchKeyEvent(event)
    }

    /** 先存「下載」資料夾；有些機器即使給了權限還是寫不進去（Note 8 實測），就改存 App 自己的資料夾 */
    private fun save(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val keyPart = if (keys.isEmpty()) "（沒有按任何按鍵）" else keys.joinToString("\n")
        val text = report + "\n\n== 方向盤按鍵測試 ==\n" + keyPart + "\n\n== 系統屬性（getprop）==\n" + getprop()
        val dirs = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            getExternalFilesDir(null),
        )
        var lastError: Throwable? = null
        for (dir in dirs) {
            val file = File(dir.apply { mkdirs() }, "car_info_$stamp.txt")
            runCatching { file.writeText(text) }
                .onSuccess { return "已存到：${file.absolutePath}" }
                .onFailure { lastError = it }
        }
        return "存檔失敗：${lastError?.message}（請直接拍照）"
    }

    private fun buildReport(): String {
        val out = StringBuilder()
        fun section(title: String) = out.append("\n== $title ==\n")
        fun line(k: String, v: Any?) = out.append("$k：$v\n")
        fun safe(k: String, block: () -> Any?) = line(k, runCatching(block).getOrElse { "讀取失敗（${it.javaClass.simpleName}）" })

        section("系統")
        line("製造商／品牌", "${Build.MANUFACTURER}／${Build.BRAND}")
        line("型號", Build.MODEL)
        line("裝置／產品／主板", "${Build.DEVICE}／${Build.PRODUCT}／${Build.BOARD}")
        line("硬體", Build.HARDWARE)
        line("API level（以這個為準）", Build.VERSION.SDK_INT)
        line("畫面顯示的 Android 版本（可能虛報）", Build.VERSION.RELEASE)
        line("系統版本字串", Build.DISPLAY)
        line("指紋", Build.FINGERPRINT)
        line("CPU 架構", Build.SUPPORTED_ABIS.joinToString())
        line("CPU 核心數", Runtime.getRuntime().availableProcessors())
        safe("CPU 資訊") {
            File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Hardware") || it.startsWith("model name") }
        }

        section("螢幕")
        safe("實際解析度") {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(m)
            "${m.widthPixels} x ${m.heightPixels}，densityDpi ${m.densityDpi}（約 ${m.density}x），" +
                "換算 %.0f x %.0f dp".format(m.widthPixels / m.density, m.heightPixels / m.density)
        }
        @Suppress("DEPRECATION")
        safe("更新率") { "%.0f Hz".format(windowManager.defaultDisplay.refreshRate) }
        line("目前方向", if (resources.configuration.orientation == 2) "橫向" else "直向")

        section("記憶體與空間")
        val am = getSystemService(ActivityManager::class.java)
        safe("RAM") {
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            "總共 %.1f GB，目前可用 %.1f GB".format(mi.totalMem / 1e9, mi.availMem / 1e9)
        }
        safe("每個 App 可用記憶體") { "${am.memoryClass} MB（大型 ${am.largeMemoryClass} MB），低記憶體裝置：${am.isLowRamDevice}" }
        safe("內部儲存") {
            val s = StatFs(Environment.getDataDirectory().path)
            "總共 %.1f GB，剩 %.1f GB".format(s.totalBytes / 1e9, s.availableBytes / 1e9)
        }

        section("Google 與開發者")
        line("Play 商店", installed("com.android.vending"))
        line("Google 服務（GMS）", installed("com.google.android.gms"))
        safe("開發人員選項已開") { Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }
        safe("USB 偵錯已開") { Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }

        section("桌面（Launcher）")
        safe("目前預設桌面") {
            packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                ?.activityInfo?.let { "${it.packageName}/${it.name}" }
        }
        safe("可以當桌面的 App") {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .joinToString("\n  ", prefix = "\n  ") { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
        }

        section("硬體功能")
        for ((name, feature) in listOf(
            "車用（automotive）" to "android.hardware.type.automotive",
            "USB 主機（可接隨身碟等）" to PackageManager.FEATURE_USB_HOST,
            "USB 從屬（可被電腦連）" to PackageManager.FEATURE_USB_ACCESSORY,
            "藍牙" to PackageManager.FEATURE_BLUETOOTH,
            "Wi-Fi" to PackageManager.FEATURE_WIFI,
            "相機（倒車影像可能算）" to PackageManager.FEATURE_CAMERA_ANY,
            "GPS" to PackageManager.FEATURE_LOCATION_GPS,
            "麥克風" to PackageManager.FEATURE_MICROPHONE,
            "觸控" to PackageManager.FEATURE_TOUCHSCREEN,
        )) line(name, packageManager.hasSystemFeature(feature))

        section("音訊")
        safe("輸出裝置") {
            getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .joinToString { "${it.productName}(type ${it.type})" }
        }
        safe("媒體音量格數") {
            val a = getSystemService(AudioManager::class.java)
            "${a.getStreamVolume(AudioManager.STREAM_MUSIC)}／${a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
        }

        section("輸入裝置（方向盤按鍵、旋鈕可能在這裡）")
        safe("清單") {
            val im = getSystemService(InputManager::class.java)
            im.inputDeviceIds.toList().mapNotNull { id -> im.getInputDevice(id) }
                .joinToString("\n  ", prefix = "\n  ") { d -> "${d.name}（sources 0x${Integer.toHexString(d.sources)}）" }
        }

        section("已安裝的 App")
        safe("清單") {
            val apps = packageManager.getInstalledApplications(0).sortedBy { it.packageName }
            val (sys, user) = apps.partition { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            fun fmt(list: List<ApplicationInfo>) =
                list.joinToString("\n  ", prefix = "\n  ") { "${it.packageName}（${packageManager.getApplicationLabel(it)}）" }
            "共 ${apps.size} 個\n系統內建 ${sys.size} 個：${fmt(sys)}\n自己裝的 ${user.size} 個：${fmt(user)}"
        }
        return out.toString().trimStart()
    }

    private fun installed(pkg: String) =
        runCatching { packageManager.getPackageInfo(pkg, 0); "有" }.getOrDefault("沒有")

    /** 系統屬性常有公版機的廠商代號、MCU 版本、車型設定等 */
    private fun getprop(): String = runCatching {
        Runtime.getRuntime().exec("getprop").inputStream.bufferedReader().readText()
    }.getOrElse { "讀取失敗：${it.message}" }
}
