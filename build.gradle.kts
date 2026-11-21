plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    // 静态检查：只开能抓到真实缺陷的规则，配置见 config/detekt/detekt.yml
    // 2.0 起 plugin id 从 io.gitlab.arturbosch.detekt 改为 dev.detekt；
    // 1.23.x 读不了 Kotlin 2.3+ 的元数据（会大量误报甚至 NPE），必须换到 2.0。
    id("dev.detekt") version "2.0.0-alpha.6" apply false
}
