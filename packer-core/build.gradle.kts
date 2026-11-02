// XopProtector 加固引擎（源自 github.com/xopJack/XopProtector :packer）
// 纯 Java，可在 JVM 与 Android 进程内运行。
plugins {
    id("java")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.zip4j)
    implementation(libs.dexlib2)
    // DexMaker 提供 com.android.dx.DexMaker；排除其自带 dalvik-dx，改用 libs/dx.jar，避免重复类。
    implementation("com.linkedin.dexmaker:dexmaker:2.28.5") {
        exclude(group = "com.jakewharton.android.repackaged", module = "dalvik-dx")
    }
}
