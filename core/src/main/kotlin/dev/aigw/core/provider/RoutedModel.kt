package dev.aigw.core.provider

/**
 * 一条对外暴露的模型：`providerId/modelId` 是客户端看到的完整 id。
 *
 * 网关用前缀路由到对应 provider，客户端只配一次 base_url 就能用全部供应商。
 */
data class RoutedModel(
    val providerId: String,
    val providerName: String,
    val model: ProviderModel,
) {
    val fullId: String get() = "$providerId/${model.id}"
}
