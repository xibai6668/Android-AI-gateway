import dev.detekt.gradle.extensions.FailOnSeverity
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("dev.detekt")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    // 只看生产代码：测试里的桩类、固定数据常会触发误报
    source.setFrom(files("src/main/kotlin"))
    // 2.0 用严重度取代了旧的权重式 maxIssues
    failOnSeverity.set(FailOnSeverity.Error)
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        // 2.0 把 txt/xml 换成了 markdown/checkstyle
        markdown.required.set(false)
        checkstyle.required.set(false)
        sarif.required.set(false)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 仅使用 Android 上同样可用的 API（HttpURLConnection + 纯 Java 库），使 :core 既能
    // 在本机 JVM 直接跑测试/联调，也能原样打进 Android 应用。
    implementation("com.google.code.gson:gson:2.15.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
