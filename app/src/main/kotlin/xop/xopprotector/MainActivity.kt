package xop.xopprotector

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {

    private var pickedUri: Uri? = null
    private var pickedFile: java.io.File? = null
    private var pickedName: String = ""
    private var ink: HardenEngine.Ink = HardenEngine.Ink.BALANCED
    private var advOpen = false
    private var optSo = true
    private var optRes = false
    private var optNet = false
    private var optSoDiag = false
    private var optAssets = false
    private var optRaspAction = 2
    private var optX86 = false
    private var lastOutput: File? = null
    private val worker = Executors.newSingleThreadExecutor()

    private val apkPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pickedUri = uri
            pickedFile = null
            pickedName = queryName(uri) ?: "picked.apk"
            txtApkPath.text = pickedName
            txtApkPath.setTextColor(getColor(R.color.ink_black))
        }
    }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when {
            result.resultCode == RESULT_OK -> {
                val path = result.data?.getStringExtra("apk_path") ?: return@registerForActivityResult
                pickedFile = java.io.File(path)
                pickedUri = null
                pickedName = java.io.File(path).name
                val mb = (pickedFile?.length() ?: 0L) / 1048576
                txtApkPath.text = pickedName + "（" + mb + "MB）"
                txtApkPath.setTextColor(getColor(R.color.ink_black))
            }
            result.resultCode == FilePickerActivity.RESULT_USE_SAF -> apkPicker.launch(
                arrayOf(
                    "application/vnd.android.package-archive",
                    "application/octet-stream",
                    "application/zip"
                )
            )
        }
    }

    private val running = AtomicReference(false)
    private var cachedInput: File? = null

    private lateinit var txtApkPath: TextView
    private lateinit var btnPick: TextView
    private lateinit var chipBalanced: TextView
    private lateinit var chipAggressive: TextView
    private lateinit var chipMax: TextView
    private lateinit var txtProfileDesc: TextView
    private lateinit var btnHarden: TextView
    private lateinit var cardProgress: View
    private lateinit var txtPhase: TextView
    private lateinit var barProgress: View
    private lateinit var txtLog: TextView
    private lateinit var cardDone: View
    private lateinit var txtOutPath: TextView
    private lateinit var btnInstall: TextView
    private lateinit var btnShare: TextView
    private lateinit var panelAdvanced: View
    private lateinit var btnToggleAdvanced: TextView
    private lateinit var chipSo: TextView
    private lateinit var chipResProtect: TextView
    private lateinit var chipNetGuard: TextView
    private lateinit var chipSoDiag: TextView
    private lateinit var chipAssets: TextView
    private lateinit var chipRasp: TextView
    private lateinit var chipX86: TextView

    private val logBuf = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setInk(HardenEngine.Ink.BALANCED)
    }

    private fun bindViews() {
        txtApkPath = findViewById(R.id.txtApkPath)
        btnPick = findViewById(R.id.btnPick)
        chipBalanced = findViewById(R.id.chipBalanced)
        chipAggressive = findViewById(R.id.chipAggressive)
        chipMax = findViewById(R.id.chipMax)
        txtProfileDesc = findViewById(R.id.txtProfileDesc)
        btnHarden = findViewById(R.id.btnHarden)
        cardProgress = findViewById(R.id.cardProgress)
        txtPhase = findViewById(R.id.txtPhase)
        barProgress = findViewById(R.id.barProgress)
        txtLog = findViewById(R.id.txtLog)
        cardDone = findViewById(R.id.cardDone)
        txtOutPath = findViewById(R.id.txtOutPath)
        btnInstall = findViewById(R.id.btnInstall)
        btnShare = findViewById(R.id.btnShare)
        panelAdvanced = findViewById(R.id.panelAdvanced)
        btnToggleAdvanced = findViewById(R.id.btnToggleAdvanced)
        chipSo = findViewById(R.id.chipSo)
        chipResProtect = findViewById(R.id.chipResProtect)
        chipNetGuard = findViewById(R.id.chipNetGuard)
        chipSoDiag = findViewById(R.id.chipSoDiag)
        chipAssets = findViewById(R.id.chipAssets)
        chipRasp = findViewById(R.id.chipRasp)
        chipX86 = findViewById(R.id.chipX86)

        btnPick.setOnClickListener { pickApk() }
        chipBalanced.setOnClickListener { setInk(HardenEngine.Ink.BALANCED) }
        chipAggressive.setOnClickListener { setInk(HardenEngine.Ink.AGGRESSIVE) }
        chipMax.setOnClickListener { setInk(HardenEngine.Ink.MAX) }
        btnHarden.setOnClickListener { startHarden() }
        btnToggleAdvanced.setOnClickListener {
            advOpen = !advOpen
            panelAdvanced.visibility = if (advOpen) View.VISIBLE else View.GONE
            btnToggleAdvanced.text = if (advOpen) "收起" else "展开"
        }
        chipSo.setOnClickListener {
            optSo = !optSo
            renderChip(chipSo, "SO 代码加密", optSo)
        }
        chipResProtect.setOnClickListener {
            optRes = !optRes
            renderChip(chipResProtect, "资源路径混淆", optRes)
        }
        chipNetGuard.setOnClickListener {
            optNet = !optNet
            renderChip(chipNetGuard, "网络防护（代理检测）", optNet)
        }
        chipSoDiag.setOnClickListener {
            optSoDiag = !optSoDiag
            renderChip(chipSoDiag, "SO 加载诊断", optSoDiag)
        }
        chipAssets.setOnClickListener {
            optAssets = !optAssets
            renderChip(chipAssets, "assets 加密（需应用适配）", optAssets)
        }
        chipX86.setOnClickListener {
            optX86 = !optX86
            renderChip(chipX86, "含 x86 库的 APK（精简壳）", optX86)
            applyShellCompat()
            if (optX86) {
                Toast.makeText(this,
                    "已切换到精简壳：SO 加密 / 网络防护 / SO 诊断 / assets 加密 / RASP 不可用",
                    Toast.LENGTH_LONG).show()
            }
        }
        chipRasp.setOnClickListener {
            optRaspAction = when (optRaspAction) { 2 -> 0; 0 -> 1; else -> 2 }
            val label = when (optRaspAction) { 0 -> "仅告警"; 1 -> "降级"; else -> "Block" }
            chipRasp.text = "运行时自检（RASP）　" + label
            chipRasp.setBackgroundResource(
                if (optRaspAction == 2) R.drawable.bg_chip_ink else R.drawable.bg_chip_outline
            )
            chipRasp.setTextColor(getColor(
                if (optRaspAction == 2) R.color.paper else R.color.ink_black
            ))
        }
        btnInstall.setOnClickListener { installOut() }
        btnShare.setOnClickListener { shareOut() }

        // 日志区可滑动：手势期间禁止外层 ScrollView 抢事件
        txtLog.movementMethod = android.text.method.ScrollingMovementMethod()
        txtLog.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE -> v.parent.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    /** 追加日志后让最后一行贴底。 */
    private fun scrollLogToBottom() {
        txtLog.post {
            val layout = txtLog.layout ?: return@post
            val lineCount = layout.lineCount
            if (lineCount > 0) {
                val bottom = layout.getLineBottom(lineCount - 1)
                val visible = txtLog.height - txtLog.paddingTop - txtLog.paddingBottom
                txtLog.scrollTo(0, (bottom - visible).coerceAtLeast(0))
            }
        }
    }

    private fun pickApk() {
        filePicker.launch(Intent(this, FilePickerActivity::class.java))
    }

    private fun queryName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }

    /**
     * 加固运行期间锁定所有可变更项；展开/收起仍可用，方便查看已选配置。
     */
    private fun setControlsLocked(locked: Boolean) {
        val modifiable = listOf(
            btnPick, chipBalanced, chipAggressive, chipMax,
            chipSo, chipResProtect, chipNetGuard, chipSoDiag, chipAssets, chipRasp, chipX86
        )
        for (v in modifiable) {
            v.isEnabled = !locked
            v.alpha = if (locked) 0.45f else 1f
        }
        // 解锁后再套一层壳兼容禁用
        if (!locked) applyShellCompat()
        btnToggleAdvanced.isEnabled = true   // 只读查看不受限
        btnToggleAdvanced.alpha = 1f
    }

    /**
     * 精简壳能力联动：切到精简壳后，它不支持的开关直接禁用并置灰，
     * 避免用户勾了却静默不生效（或更糟：让目标应用起不来）。
     */
    private fun applyShellCompat() {
        val lite = optX86
        val unsupported = listOf(
            chipSo to "SO 代码加密",
            chipNetGuard to "网络防护（代理检测）",
            chipSoDiag to "SO 加载诊断",
            chipAssets to "assets 加密（需应用适配）",
            chipRasp to "运行时自检（RASP）",
        )
        for ((chip, label) in unsupported) {
            chip.isEnabled = !lite
            chip.alpha = if (lite) 0.38f else 1f
            if (lite) {
                chip.text = label + "　精简壳不支持"
                chip.setBackgroundResource(R.drawable.bg_chip_outline)
                chip.setTextColor(getColor(R.color.ink_faint))
            }
        }
        if (!lite) {
            renderChip(chipSo, "SO 代码加密", optSo)
            renderChip(chipNetGuard, "网络防护（代理检测）", optNet)
            renderChip(chipSoDiag, "SO 加载诊断", optSoDiag)
            renderChip(chipAssets, "assets 加密（需应用适配）", optAssets)
            val label = when (optRaspAction) { 0 -> "仅告警"; 1 -> "降级"; else -> "Block" }
            chipRasp.text = "运行时自检（RASP）　" + label
            chipRasp.setBackgroundResource(
                if (optRaspAction == 2) R.drawable.bg_chip_ink else R.drawable.bg_chip_outline
            )
            chipRasp.setTextColor(getColor(
                if (optRaspAction == 2) R.color.paper else R.color.ink_black
            ))
        }
    }

    private fun renderChip(chip: TextView, label: String, on: Boolean) {
        chip.text = label + (if (on) "　✓" else "　✗")
        chip.setBackgroundResource(if (on) R.drawable.bg_chip_ink else R.drawable.bg_chip_outline)
        chip.setTextColor(getColor(if (on) R.color.paper else R.color.ink_black))
    }

    private fun setInk(next: HardenEngine.Ink) {
        ink = next
        txtProfileDesc.text = next.desc
        val chips = listOf(chipBalanced, chipAggressive, chipMax)
        val names = HardenEngine.Ink.entries
        chips.forEachIndexed { i, chip ->
            val active = names[i] == next
            chip.setBackgroundResource(if (active) R.drawable.bg_chip_ink else R.drawable.bg_chip_outline)
            chip.setTextColor(getColor(if (active) R.color.paper else R.color.ink_black))
        }
    }

    private fun startHarden() {
        val uri = pickedUri
        if (uri == null && pickedFile == null) {
            Toast.makeText(this, "先选一卷 APK", Toast.LENGTH_SHORT).show()
            return
        }
        if (!running.compareAndSet(false, true)) {
            Toast.makeText(this, "正在落墨，稍候", Toast.LENGTH_SHORT).show()
            return
        }
        cardProgress.visibility = View.VISIBLE
        cardDone.visibility = View.GONE
        btnHarden.isEnabled = false
        btnHarden.alpha = 0.6f
        setControlsLocked(true)
        logBuf.setLength(0)
        txtLog.text = ""

        worker.execute {
            try {
                val shellDir = HardenEngine.prepareWorkspace(this, optX86)
                appendLog("info", "壳文件就绪: " + shellDir)
                val inFile = pickedFile?.let { it } ?: copyPickedToCache(uri)
                appendLog("info", "读入 " + inFile.name + " " + inFile.length() / 1024 + "KB")
                // 禁止加固自己：产物会覆盖本 APP 并导致无法启动
                val selfPkg = packageName
                val targetPkg = try {
                    packageManager.getPackageArchiveInfo(inFile.absolutePath, 0)?.packageName
                } catch (e: Throwable) {
                    null
                }
                if (targetPkg != null && targetPkg == selfPkg) {
                    throw IllegalStateException("不能加固墨枢自身（会覆盖并自毁），请选择其它 APK")
                }
                val outDir = File(getExternalFilesDir(null), "XopOut")
                outDir.mkdirs()
                val base = pickedName.removeSuffix(".apk").removeSuffix(".APK")
                val outFile = File(outDir, base + "-protected.apk")
                if (outFile.exists()) outFile.delete()

                val adv = HardenEngine.Options(
                    protectSo = optSo,
                    resProtect = optRes,
                    netGuard = optNet,
                    soDiag = optSoDiag,
                    encryptAssets = optAssets,
                    raspAction = optRaspAction,
                    x86Support = optX86,
                )
                appendLog("info", "壳: " + (if (optX86) "精简壳(4 ABI)" else "完整壳(arm)"))
                appendLog("info", "选项: SO=" + optSo + " 资源=" + optRes + " 网络=" + optNet +
                    " assets=" + optAssets + " RASP=" + optRaspAction)
                val out = HardenEngine.harden(inFile, outFile, shellDir, ink, adv, object : HardenEngine.Progress {
                    override fun onPhase(id: String, message: String, percent: Int) {
                        runOnUiThread {
                            txtPhase.text = message
                            val track = barProgress.parent as View
                            (barProgress.layoutParams as FrameLayout.LayoutParams).width =
                                track.width * percent / 100
                            barProgress.requestLayout()
                        }
                    }

                    override fun onLog(level: String, message: String) {
                        appendLog(level, message)
                    }
                })
                lastOutput = out
                runOnUiThread {
                    cardDone.visibility = View.VISIBLE
                    txtOutPath.text = out.absolutePath
                    Toast.makeText(this, "加固完成", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                // 完整异常链：根因类名 + 消息
                val sb = StringBuilder("失败: ")
                var cur: Throwable? = t
                var depth = 0
                while (cur != null && depth < 4) {
                    sb.append(cur.javaClass.simpleName).append(": ")
                        .append(cur.message ?: "(无消息)").append(" <- ")
                    cur = cur.cause
                    depth++
                }
                appendLog("error", sb.toString())
                runOnUiThread {
                    Toast.makeText(this, sb.toString().take(180), Toast.LENGTH_LONG).show()
                }
            } finally {
                inFileCleanup()
                runOnUiThread {
                    btnHarden.isEnabled = true
                    btnHarden.alpha = 1f
                    setControlsLocked(false)
                    running.set(false)
                }
            }
        }
    }

    private fun copyPickedToCache(uri: Uri?): File {
        if (uri == null) throw IllegalStateException("未选择文件")
        val f = File(cacheDir, "input-" + System.currentTimeMillis() + ".apk")
        contentResolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("无法读取所选文件")
        cachedInput = f
        return f
    }

    private fun inFileCleanup() {
        cachedInput?.delete()
        cachedInput = null
    }

    private fun appendLog(level: String, message: String) {
        runOnUiThread {
            logBuf.append('[').append(level).append("] ").append(message).append('\n')
            if (logBuf.length > 8000) {
                logBuf.delete(0, logBuf.length - 8000)
            }
            txtLog.text = logBuf
            scrollLogToBottom()
        }
    }

    private fun installOut() {
        val f = lastOutput
        if (f == null || !f.isFile) {
            Toast.makeText(this, "产物不存在", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "xop.xopprotector.fileprovider", f)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "安装启动失败：" + t.javaClass.simpleName + " " + t.message,
                Toast.LENGTH_LONG).show()
        }
    }

    private fun shareOut() {
        val f = lastOutput ?: return
        val uri = FileProvider.getUriForFile(this, "xop.xopprotector.fileprovider", f)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享加固包"))
    }

}
