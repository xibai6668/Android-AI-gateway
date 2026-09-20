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

    data object DataManagement : SubPage
}
