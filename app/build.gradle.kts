// 水墨加固台：XopProtector Android 客户端
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "xop.xopprotector"
    compileSdk = 37

    defaultConfig {
        applicationId = "xop.xopprotector"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
                "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE*"
            )
        }
    }

    signingConfigs {
        create("release") {
            // 本机构建的发布签名（debug 性质，正式分发请替换自己的密钥）。
            // 文件不存在时（其他构建环境）字段留空，buildTypes 回退 debug 签名。
            val ks = file("/root/.android/xop-release.jks")
            if (ks.isFile) {
                storeFile = ks
                storePassword = "xopprotector"
                keyAlias = "xopprotector"
                keyPassword = "xopprotector"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (file("/root/.android/xop-release.jks").isFile) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
    }
}

dependencies {
    implementation(project(":packer-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
}

// 构建前确保壳文件已导出到 assets
tasks.named("preBuild") {
    dependsOn(":exportShellFiles")
}
