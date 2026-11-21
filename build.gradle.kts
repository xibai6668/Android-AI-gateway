plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    // 静态检查：只开能抓到真实缺陷的规则，配置见 config/detekt/detekt.yml
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}
