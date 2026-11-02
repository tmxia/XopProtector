package xop.xopprotector

import android.content.Context
import com.yqsh.protector.packer.PackerMain
import com.yqsh.protector.packer.ProtectOptions
import com.yqsh.protector.packer.ProtectPolicy
import com.yqsh.protector.packer.ProtectProgressListener
import com.yqsh.protector.packer.Protector
import java.io.File
import java.security.KeyStore

/**
 * 加固调度：解出壳文件 → 调用 Protector 完成加密/抽取/注入/签名。
 * 精简壳约束：关闭 True-VMP、SO .text 加密、assets 加密、资源混淆、NetGuard。
 */
object HardenEngine {

    interface Progress {
        fun onPhase(id: String, message: String, percent: Int)
        fun onLog(level: String, message: String)
    }

    /** 高级开关：默认值与安全边界见各项注释。 */
    data class Options(
        /** SO .text 加密。几乎无副作用，默认开。 */
        val protectSo: Boolean = true,
        /** 资源路径混淆（重写 resources.arsc）。多数应用安全。 */
        val resProtect: Boolean = false,
        /** 网络防护：代理/VPN 检测 + 证书固定后端。误报只降级不崩溃。 */
        val netGuard: Boolean = false,
        /** SO 加载诊断日志（排障用）。 */
        val soDiag: Boolean = false,
        /** assets 加密。目标应用必须改用 ProtectorAssets.open() 读取，否则崩溃。 */
        val encryptAssets: Boolean = false,
        /** RASP 动作：0=仅告警 1=降级 2=终止进程。 */
        val raspAction: Int = 2,
        /**
         * 是否支持含 x86/x86_64 库的 APK（模拟器）。
         * 开：改用精简壳（无 RASP/PVM2/SO 保护），四 ABI 齐全。
         * 关：完整壳（arm 双 ABI），防护最强。
         */
        val x86Support: Boolean = false,
    )

    /** 精简壳缺失的运行时能力：这些开关开了也不会生效，必须显式关掉。 */
    val LITE_UNSUPPORTED = listOf(
        "SO 代码加密", "网络防护", "SO 加载诊断", "assets 加密", "运行时自检（RASP）"
    )

    /**
     * 把用户勾选折算成实际生效值。
     * 精简壳没有 risk（RASP）/ so（SO 解密）/ netguard / PVM2 解释器，
     * 开着不仅无效，还会让目标应用加载失败（如 PVM2 方法无法解释）。
     *
     * @return 生效选项 + 被强制忽略的项名
     */
    fun Options.resolve(): Pair<Options, List<String>> {
        if (!x86Support) return this to emptyList()
        val ignored = mutableListOf<String>()
        if (protectSo) ignored += "SO 代码加密"
        if (netGuard) ignored += "网络防护"
        if (soDiag) ignored += "SO 加载诊断"
        if (encryptAssets) ignored += "assets 加密"
        if (raspAction != 0) ignored += "运行时自检（RASP）"
        return copy(
            protectSo = false,
            netGuard = false,
            soDiag = false,
            encryptAssets = false,
            raspAction = 0,
        ) to ignored
    }

    enum class Ink(val profile: ProtectPolicy.Profile, val desc: String) {
        BALANCED(ProtectPolicy.Profile.BALANCED, "整体加密 + 支付VMP · 推荐"),
        AGGRESSIVE(ProtectPolicy.Profile.AGGRESSIVE, "业务方法抽取 + SO保护"),
        MAX(ProtectPolicy.Profile.MAX, "近全量抽取 · 最强防护"),
    }

    const val KEY_ALIAS = "xopkey"
    const val KEY_STORE_PASS = "xopprotect123"

    /** 把 assets 里的壳文件与内置签名密钥解到工作目录。返回 shellDir。 */
    fun prepareWorkspace(ctx: Context, x86Support: Boolean = false): File {
        val root = File(ctx.cacheDir, "xop-work")
        val dexDir = File(root, "dex")
        val libsDir = File(root, "libs")
        // 密钥放 shellDir 同级，signConfig 路径才能对上
        val signDir = File(root, "signing")
        dexDir.deleteRecursively()
        libsDir.deleteRecursively()
        dexDir.mkdirs()
        libsDir.mkdirs()
        signDir.mkdirs()

        // 两套壳：完整壳（arm，强防护）/ 精简壳（四 ABI，含 x86）
        val shellPkg = if (x86Support) "xop-shell-lite" else "xop-shell-full"
        val abiList = if (x86Support) {
            listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        } else {
            listOf("arm64-v8a", "armeabi-v7a")
        }
        ctx.assets.open("$shellPkg/dex/classes.dex").use { input ->
            File(dexDir, "classes.dex").outputStream().use { input.copyTo(it) }
        }
        // 完整壳只提供 arm 双 ABI（x86 模拟器无 shadowhook 支持）。
        // 必须拷贝该 ABI 下的全部 .so：libprotector.so 依赖 libshadowhook.so
        // 和 libc++_shared.so，漏掉任何一个都会在目标应用启动时 UnsatisfiedLinkError。
        var copiedSos = 0
        for (abi in abiList) {
            val names = try {
                ctx.assets.list("$shellPkg/libs/$abi")?.toList().orEmpty()
            } catch (e: Exception) {
                emptyList()
            }
            val sos = names.filter { it.endsWith(".so") }
            if (sos.isEmpty()) continue
            val abiDir = File(libsDir, abi)
            abiDir.mkdirs()
            for (name in sos) {
                ctx.assets.open("$shellPkg/libs/$abi/$name").use { input ->
                    File(abiDir, name).outputStream().use { input.copyTo(it) }
                }
                copiedSos++
            }
        }
        if (copiedSos == 0) {
            throw IllegalStateException("assets 里没有壳 so（$shellPkg/libs）")
        }
        // 依赖完整性自检：完整壳的 libprotector.so 必须有 shadowhook / libc++ 陪跑
        if (!x86Support) {
            for (abi in abiList) {
                val abiDir = File(libsDir, abi)
                if (!File(abiDir, "libprotector.so").isFile) continue
                listOf("libshadowhook.so", "libc++_shared.so").forEach { dep ->
                    if (!File(abiDir, dep).isFile) {
                        throw IllegalStateException("壳依赖缺失: " + abi + "/" + dep)
                    }
                }
            }
        }
        val p12 = File(signDir, "xop-embed.p12")
        ctx.assets.open("signing/xop-embed.p12").use { input ->
            p12.outputStream().use { input.copyTo(it) }
        }
        if (!p12.isFile || p12.length() == 0L) {
            throw IllegalStateException("内置签名密钥解压失败: " + p12.absolutePath)
        }
        return root
    }

    /**
     * 签名密钥自检：调用引擎前先读一次 keystore，
     * 把「文件缺失 / 密码错 / 别名不存在 / Provider 不支持」区分清楚。
     */
    fun verifySigningKey(keystore: File) {
        if (!keystore.isFile) {
            throw IllegalStateException("签名密钥不存在: " + keystore.absolutePath)
        }
        val ks: KeyStore = try {
            KeyStore.getInstance("PKCS12").apply {
                keystore.inputStream().use { load(it, KEY_STORE_PASS.toCharArray()) }
            }
        } catch (e: java.io.IOException) {
            throw IllegalStateException("签名密钥读取失败（密码或格式）: " + e.message, e)
        } catch (e: java.security.GeneralSecurityException) {
            throw IllegalStateException("此设备不支持 PKCS12 密钥库: " + e.javaClass.simpleName + " " + e.message, e)
        }
        if (!ks.isKeyEntry(KEY_ALIAS)) {
            val names = ks.aliases().toList().joinToString(", ")
            throw IllegalStateException("密钥别名 [$KEY_ALIAS] 不存在，现有别名: $names")
        }
    }

    fun harden(
        inputApk: File,
        outputApk: File,
        shellDir: File,
        ink: Ink,
        rawOpts: Options,
        progress: Progress,
    ): File {
        val (eff, ignored) = rawOpts.resolve()
        if (ignored.isNotEmpty()) {
            progress.onLog("warn", "精简壳不支持，已自动关闭: " + ignored.joinToString("、"))
        }
        // prepareWorkspace 返回的 shellDir = cacheDir/xop-work，signing 就在它下面
        val keyFile = File(shellDir, "signing/xop-embed.p12")
        progress.onLog("info", "签名密钥: " + keyFile.absolutePath)
        verifySigningKey(keyFile)
        progress.onLog("info", "签名密钥自检通过 [" + KEY_ALIAS + "]")

        val opts = ProtectOptions().apply {
            this.inputApk = inputApk
            this.outputApk = outputApk
            this.shellDir = shellDir
            profile = ink.profile
            // 完整壳能力（按用户勾选生效）
            protectSo = eff.protectSo
            encryptAssets = eff.encryptAssets
            enableResProtect = eff.resProtect
            enableNetGuard = eff.netGuard
            detectProxy = eff.netGuard
            soDiag = eff.soDiag
            dexScanCompat = true
            reportEnabled = true
            // 精简壳没有 PVM2 解释器：必须显式 OFF，否则支付类方法被编译成
            // VM 字节码后无人解释，目标应用启动即崩。完整壳才用档位默认值。
            paymentAutoVmp = if (eff.x86Support) {
                ProtectOptions.AutoVmpMode.OFF
            } else {
                ProtectOptions.AutoVmpMode.UNSET
            }
            industryAutoVmp = if (eff.x86Support) {
                ProtectOptions.AutoVmpMode.OFF
            } else {
                ProtectOptions.AutoVmpMode.UNSET
            }
            raspAction = eff.raspAction
            // 进程内签名（内置 PKCS12）
            signConfig = PackerMain.SignConfig(
                keyFile,
                KEY_ALIAS,
                KEY_STORE_PASS,
                KEY_STORE_PASS,
                // Android 默认 keystore 类型是 BKS，必须显式 PKCS12
                "PKCS12",
            )
        }
        val listener = object : ProtectProgressListener {
            override fun onPhase(id: String, message: String, percent: Int) {
                progress.onPhase(id, message, percent)
            }

            override fun onLog(level: String, message: String) {
                progress.onLog(level, message)
            }
        }
        val result = Protector().protect(opts, listener)
        verifyOutput(result.outputApk, progress)
        return result.outputApk
    }

    /**
     * 产物自检：注入的 libprotector.so 依赖 libshadowhook.so / libc++_shared.so，
     * 缺任何一个目标应用启动就会 UnsatisfiedLinkError。这里提前拦住坏包。
     */
    private fun verifyOutput(apk: File, progress: Progress) {
        val x86 = java.util.zip.ZipFile(apk).use { z ->
            z.entries().asSequence().any { it.name.startsWith("lib/x86") }
        }
        val required = if (x86) {
            listOf("libprotector.so")
        } else {
            listOf("libprotector.so", "libshadowhook.so", "libc++_shared.so")
        }
        val found = HashMap<String, MutableSet<String>>()   // abi -> 文件集合
        java.util.zip.ZipFile(apk).use { zip ->
            zip.entries().asSequence().forEach { e ->
                val name = e.name
                if (!name.startsWith("lib/") || !name.endsWith(".so")) return@forEach
                val parts = name.split('/')
                if (parts.size != 3) return@forEach
                found.getOrPut(parts[1]) { mutableSetOf() }.add(parts[2])
            }
        }
        val shabis = found.keys.filter { found[it]?.contains("libprotector.so") == true }
        if (shabis.isEmpty()) {
            throw IllegalStateException("产物里没有注入壳 so")
        }
        for (abi in shabis) {
            val files = found[abi] ?: emptySet()
            val missing = required.filter { !files.contains(it) }
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    "产物 " + abi + " 缺少壳依赖: " + missing.joinToString(",") +
                        "（安装后会 UnsatisfiedLinkError，已拦截）"
                )
            }
            progress.onLog("info", "产物自检通过 " + abi + ": " + files.size + " 个 so")
        }
    }
}
