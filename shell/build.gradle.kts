// 运行时壳（Java 层）：ProxyApplication / DexMerger / JniBridge
// 构建产物：shell.dex（D8 编译 classes.jar），供加固器注入目标 APK。
// 零外部依赖：壳代码只用 android 平台 API。
plugins {
    alias(libs.plugins.android.library)
}

val shellDexDir = layout.buildDirectory.dir("shell-files/dex")

android {
    namespace = "com.yqsh.protector.shell"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// D8 CLI（不做收缩，完整保留壳类）
val d8 by configurations.creating
dependencies {
    d8(libs.r8)
}

val dexShell = tasks.register<JavaExec>("dexShell") {
    group = "protector"
    description = "将壳模块编译为 shell.dex"
    dependsOn("assembleRelease")
    val jar = layout.buildDirectory.file("intermediates/aar_main_jar/release/syncReleaseLibJars/classes.jar")
    val androidJar = file(System.getenv("ANDROID_HOME") ?: "/opt/android-sdk")
        .resolve("platforms/android-36/android.jar")
    classpath = configurations.getByName("d8")
    mainClass.set("com.android.tools.r8.D8")
    inputs.file(jar)
    outputs.dir(shellDexDir)
    doFirst {
        delete(shellDexDir)
        shellDexDir.get().asFile.mkdirs()
    }
    args(
        "--release",
        "--min-api", "26",
        "--lib", androidJar.absolutePath,
        "--output", shellDexDir.get().asFile.absolutePath,
        jar.get().asFile.absolutePath
    )
}
