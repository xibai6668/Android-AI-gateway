package dev.aigw.app.ui.providers

import dev.aigw.app.ui.providers.antigravity.AntigravityUi
import dev.aigw.app.ui.providers.codebuddy.CodeBuddyUi
import dev.aigw.app.ui.providers.custom.CustomUi
import dev.aigw.app.ui.providers.loomy.LoomyUi
import dev.aigw.app.ui.providers.minimax.MiniMaxUi
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
        MiniMaxUi,
    ).associateBy { it.id }

    fun of(providerId: String): ProviderUi? = when {
        builtin.containsKey(providerId) -> builtin[providerId]
        // 每个自定义供应商一个实例：组件需要知道自己绑定的 providerId
        providerId.startsWith(CUSTOM_PREFIX) -> CustomUi(providerId)
        else -> null
    }

    fun require(providerId: String): ProviderUi =
        of(providerId) ?: error("没有为供应商 $providerId 注册 UI 组件")

    const val CUSTOM_PREFIX = "custom:"
}
