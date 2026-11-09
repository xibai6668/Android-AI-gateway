package dev.aigw.app.ui

/** 「我的」页里可进入的二级页面。 */
sealed interface SubPage {
    /** 某个供应商的详情（账号、登录、专属设置）。 */
    data class ProviderDetail(val providerId: String) : SubPage

    /** 新建（key 为 null）或编辑某个自定义供应商。 */
    data class CustomProviderEdit(val key: String?) : SubPage

    data object Usage : SubPage

    /** 任务中心：批量签到与额度刷新。 */
    data object TaskCenter : SubPage

    /** 额度中心：按供应商查看额度包明细。 */
    data object CreditCenter : SubPage

    /** 某个供应商的额度包明细。 */
    data class ProviderCredits(val providerId: String) : SubPage

    data object KeepAlive : SubPage

    /** 代理设置：按供应商管理是否走代理。 */
    data object Proxy : SubPage

    /** 接入设置：端口与 API Key。 */
    data object ApiSettings : SubPage

    /** 安全防护：反审核脱敏与账号级限速。 */
    data object SecurityProtection : SubPage

    /** 并发与容错：并发闸门、重试退避与熔断器。 */
    data object Resilience : SubPage

    /** 外观主题：明暗模式、配色方案与金色文字。 */
    data object AppearanceTheme : SubPage

    /** 主题设置：明暗模式、配色、底栏样式集中入口。 */
    data object ThemeSettings : SubPage

    /** 账号导入导出：备份与迁移（含明文凭证）。 */
    data object AccountTransfer : SubPage

    /** 客户端密钥：谁能调用网关（含白名单与限额）。 */
    data object ClientKeys : SubPage

    /** 路由组：把多个模型合成一个对外模型名。 */
    data object RoutingGroups : SubPage

    /** 模型价格：按 token 计算调用费用。 */
    data object ModelPricing : SubPage

    /** 请求改写：模型别名、system 追加与思考标签清理。 */
    data object RequestRewrite : SubPage
}
