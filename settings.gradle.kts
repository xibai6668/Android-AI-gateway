pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ai-gateway"

include(":core")
include(":cli")
include(":app")

// 崩溃日志测试用独立 App：只在本机存在时才纳入构建。
// 目录已列入 .gitignore，别人克隆后没有这个模块，不能让构建直接失败。
if (file("crashlab").isDirectory) include(":crashlab")
