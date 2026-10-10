import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * release 签名：从 keystore.properties 读，仅在文件存在时装配。
 *
 * 本地开发只出 debug 包（见 buildTypes.debug），不需要 keystore.properties；
 * 云端发版时才由 CI 现生成该文件。文件缺失时不在配置阶段报错，
 * 但 release 构建会因无签名配置而失败——正是期望行为。
 */
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val hasReleaseKeystore = rootProject.file("keystore.properties").exists()

fun keystoreValue(key: String): String =
    keystoreProps.getProperty(key)
        ?: error("缺少签名配置 $key：请检查根目录 keystore.properties")

android {
    namespace = "dev.aigw.app"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.aigw.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 103
        versionName = "1.1.6"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        // 只有存在 keystore.properties 时才创建 release 签名；
        // 本地只出 debug 包时不依赖该文件。
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreValue("storeFile"))
                storePassword = keystoreValue("storePassword")
                keyAlias = keystoreValue("keyAlias")
                keyPassword = keystoreValue("keyPassword")
            }
        }
    }

    buildTypes {
        // 两个变体都开 R8。注意 AGP 对 debuggable 构建会禁用「优化与混淆」
        // （构建时会提示 “All code optimizations and obfuscation are disabled for
        // debuggable builds”），但**代码裁剪照常生效**——这才是体积的大头：
        // material-icons-extended 的 10660 个未使用图标类会被裁掉。
        // 裁剪后 debug 3.5MB / release 1.4MB（未开前 debug 是 16.8MB）。
        //
        // debug = 本地开发包：applicationId 加 .debug 后缀、应用名加 Debug，
        // 用 debug 签名，可与 release 包同机共存、互不覆盖。
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            applicationIdSuffix = ".debug"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // release = 仅由 GitHub Actions 云端构建并发版；本地不产出 release 包。
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    // :core 用 implementation 引 gson，不传递；app 里要直接构造 JsonObject（如动作 payload）
    implementation("com.google.code.gson:gson:2.11.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
