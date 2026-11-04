package dev.aigw.app.ui.providers

import java.util.concurrent.ConcurrentHashMap
import dev.aigw.app.ui.providers.antigravity.AntigravityUi
import dev.aigw.app.ui.providers.chatgpt.ChatGPTUi
import dev.aigw.app.ui.providers.codebuddy.CodeBuddyUi
import dev.aigw.app.ui.providers.custom.CustomUi
import dev.aigw.app.ui.providers.loomy.LoomyUi
import dev.aigw.app.ui.providers.raccoon.RaccoonUi
import dev.aigw.app.ui.providers.trae.TraeUi

/**
 * 供应商 UI 组件的登记处。
 *
 * 新增一个供应商 = 新增一个 `ProviderUi` 实现 + 在这里加一行。
 * 页面只按 `providerId` 取组件，不认识任何具体供应商。
 */
object ProviderUiRegistry {

    private val builtin: Map<String, ProviderUi> = listOf(
        TraeUi,
        LoomyUi,
        CodeBuddyUi,
        AntigravityUi,
        ChatGPTUi,
        RaccoonUi,
    ).associateBy { it.id }

    private val customCache = ConcurrentHashMap<String, ProviderUi>()

    fun of(providerId: String): ProviderUi? = when {
        builtin.containsKey(providerId) -> builtin[providerId]
        // 每个自定义供应商一个实例：组件需要知道自己绑定的 providerId，缓存实例避免重复创建导致 Compose 无法跳过重组
        providerId.startsWith(CUSTOM_PREFIX) -> customCache.computeIfAbsent(providerId) { CustomUi(it) }
        else -> null
    }

    fun require(providerId: String): ProviderUi =
        of(providerId) ?: error("没有为供应商 $providerId 注册 UI 组件")

    const val CUSTOM_PREFIX = "custom:"
}
