package dev.aigw.core.provider

/**
 * 一条对外暴露的模型：`routePrefix/modelId` 是客户端看到的完整 id。
 *
 * 网关用前缀路由到对应 provider，客户端只配一次 base_url 就能用全部供应商。
 * 区域型供应商（如 WorkBuddy 的国内/国外）会拆成多个前缀条目（`codebuddy-cn`、
 * `codebuddy-global`），前缀本身携带区域约束，选号只在该区域的账号中进行。
 */
data class RoutedModel(
    val providerId: String,
    val providerName: String,
    val model: ProviderModel,
    /** 客户端调用用的前缀；普通供应商等于 [providerId]，区域型供应商带区域后缀。 */
    val routePrefix: String = providerId,
    /** 区域标识（如 cn/global）；null 表示该供应商无区域概念。 */
    val region: String? = null,
) {
    val fullId: String get() = "$routePrefix/${model.id}"
}
