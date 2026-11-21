import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * release 签名：从 keystore.properties 读，仅在文件存在时装配。
 *
 * 本地没有 release keystore，出不了发版包；发版包统一由 GitHub Actions 云端构建，
 * 那里用 secrets 现生成 keystore.properties。文件缺失时不在配置阶段报错，
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
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.aigw.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 230
        versionName = "2.38.0"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        // 只有存在 keystore.properties 时才创建 release 签名；
        // 本地不产 release 包时不依赖该文件。
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
        // 只有一个 release 变体：发版包统一由 GitHub Actions 云端构建。
        //
        // 开 R8 裁剪（体积大头是 material-icons-extended 的 10660 个未使用图标类）；
        // debuggable 构建会被 AGP 禁用优化与混淆但裁剪照常生效，所以不再保留 debug 变体。
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

    testOptions {
        // Robolectric 需要 android.jar 里的资源；不开 includeAndroidResources 拿不到 R 与 strings
        unitTests.isIncludeAndroidResources = true
    }

    // 本项目只有 release 变体（发版包由云端构建）。AGP 9 起只给「测试构建类型」建
    // 单元测试任务（android.onlyEnableUnitTestForTheTestedBuildType 默认 true），
    // 不指定的话 testReleaseUnitTest 根本不存在，CI 那步会 Task not found。
    testBuildType = "release"

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }
}

// 关掉 debug 变体：删掉 buildTypes.debug 配置块后 AGP 仍会自动创建它，
// 这里显式禁用，保证只产出 release。
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { it.enable = false }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    // :core 用 implementation 引 gson，不传递；app 里要直接构造 JsonObject（如动作 payload）
    implementation("com.google.code.gson:gson:2.15.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.core:core-ktx:1.15.0")
    // 登录走 Custom Tab：裸 ACTION_VIEW 会让进程被系统判 idle 冻结，本地回调端口随之失聪
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // 1.1.0 起不再依赖旧版 Tink 的 alpha 通道；之前停在 alpha06，是加密存储的核心依赖
    implementation("androidx.security:security-crypto:1.1.0")

    // 首页编辑态的拖拽排序：自己写的手势在 LazyColumn 里反复被滚动抢手势、
    // 重排动画与跟手位移双重叠加，换成这个库（基于官方 animateItem）。
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    // 液态玻璃底栏：真正的背景模糊（backdrop blur）。Compose 原生做不到。
    // 2.x 把模糊能力拆到独立 artifact，haze 只保留 HazeState 等基础设施。
    implementation("dev.chrisbanes.haze:haze:2.1.0")
    implementation("dev.chrisbanes.haze:haze-blur:2.1.0")

    // 原本给 debug 变体用的 Compose 预览工具（ui-tooling）随 debug 变体一起移除：
    // 打进 release 包会增体积，也会把调试入口带进线上包。
    // 本地 JVM 上的 Android 单元测试：本仓没有 emulator，instrumented 测试跑不了，
    // 用 Robolectric 在 JVM 里验证文件落盘与导出内容这类真实逻辑。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation("androidx.test:core:1.6.1")
}
