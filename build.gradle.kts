plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

/**
 * 壳文件已在 app/src/main/assets/xop-shell 中包含：
 *   dex/classes.dex            运行时壳 Java 层（D8 编译）
 *   libs/<abi>/libprotector.so 完整壳（RASP + PVM2 + SO 保护）
 *   libs/<abi>/libshadowhook.so
 *   libs/<abi>/libc++_shared.so
 * 本任务只校验完整性；:shell:dexShell 负责产出 classes.dex。
 */
tasks.register("exportShellFiles") {
    group = "protector"
    description = "Verify bundled shell files"
    dependsOn(":shell:dexShell")
    doLast {
        val assets = rootProject.file("app/src/main/assets")
        val fresh = rootProject.file("shell/build/shell-files/dex/classes.dex")
        // 两套壳共用同一个 dex（Java 层一致），各自校验
        listOf("xop-shell-full", "xop-shell-lite").forEach { pkg ->
            val outRoot = File(assets, pkg)
            val dex = File(outRoot, "dex/classes.dex")
            if (fresh.isFile) {
                dex.parentFile.mkdirs()
                fresh.copyTo(dex, overwrite = true)
            }
            if (!dex.isFile || dex.length() == 0L) {
                throw GradleException("shell.dex missing: " + dex)
            }
            val libs = File(outRoot, "libs")
            val abis = libs.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
            if (abis.isEmpty()) throw GradleException("no ABI libs under " + libs)
            abis.forEach { abi ->
                if (!File(libs, abi + "/libprotector.so").isFile) {
                    throw GradleException("missing libprotector.so for " + pkg + "/" + abi)
                }
            }
            println("Shell OK: " + pkg + " abis=" + abis.joinToString(","))
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
