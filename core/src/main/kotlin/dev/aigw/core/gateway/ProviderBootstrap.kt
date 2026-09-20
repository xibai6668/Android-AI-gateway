package dev.aigw.core.gateway

import dev.aigw.core.provider.ProviderRegistry
import dev.aigw.core.provider.antigravity.AntigravityProvider
import dev.aigw.core.provider.codebuddy.CodeBuddyProvider
import dev.aigw.core.provider.loomy.LoomyProvider
import dev.aigw.core.provider.trae.TraeConstants
import dev.aigw.core.provider.trae.TraeProvider
import dev.aigw.core.provider.trae.TraeVersion

/**
 * 内置供应商的登记处。
 *
 * 新增一个内置供应商时，只在这里加一行 `register(...)`，网关与其它 provider 都不需要改。
 */
internal fun registerBuiltinProviders(engine: GatewayEngine) {
    val registry: ProviderRegistry = engine.registry

    registry.register(
        TraeProvider(
            store = engine.store(),
            version = {
                val settings = engine.providerSettings(TraeProvider.ID)
                TraeVersion(
                    ideVersion = settings.option("ideVersion", "0.1.52"),
                    ideVersionCode = settings.option("ideVersionCode", "20260811"),
                )
            },
            defaultModel = {
                engine.providerSettings(TraeProvider.ID).defaultModel
                    .ifEmpty { TraeConstants.DEFAULT_CONFIG_NAME }
            },
            hooks = engine.hooks,
            nowMillis = { engine.now() },
        ),
    )

    registry.register(
        LoomyProvider(
            defaultModel = { engine.providerSettings(LoomyProvider.ID).defaultModel },
            nowMillis = { engine.now() },
        ),
    )

    registry.register(
        AntigravityProvider(nowMillis = { engine.now() }),
    )

    registry.register(
        CodeBuddyProvider(
            region = { engine.providerSettings(CodeBuddyProvider.ID).option("region", CodeBuddyProvider.REGION_CN) },
            nowMillis = { engine.now() },
        ),
    )
}
