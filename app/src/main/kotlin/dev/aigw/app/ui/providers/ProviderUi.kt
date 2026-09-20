package dev.aigw.app.ui.providers

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.gson.JsonObject
import dev.aigw.core.gateway.ProviderSettings
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.QuotaPack

/**
 * 供应商 UI 组件对外可用的动作。
 *
 * 组件不直接依赖 ViewModel：只通过这些回调发起操作，
 * 因此新增/替换一个供应商的实现不会牵动页面骨架与其它供应商。
 */
class ProviderUiActions(
    /** 拉起 WebView 登录（Trae 回调型 / Antigravity OAuth）。 */
    val onWebLogin: (providerId: String) -> Unit = {},
    /** 设备授权登录（WorkBuddy）：拉起 WebView 并开始轮询；region 由组件按滑块传入。 */
    val onDeviceLogin: (providerId: String, region: String) -> Unit = { _, _ -> },
    /** 读供应商私有设置项（同步，取引擎内存值）。 */
    val providerOptionOf: (providerId: String, key: String, fallback: String) -> String =
        { _, _, fallback -> fallback },
    /** 写供应商私有设置项（如 WorkBuddy 的 region 滑块）。 */
    val onUpdateProviderOption: (providerId: String, key: String, value: String) -> Unit = { _, _, _ -> },
    /** 短信登录：code 为空表示「先发验证码」；[onResult] 回传错误信息（空串表示成功）。 */
    val onSmsLogin: (providerId: String, phone: String, code: String, onResult: (String) -> Unit) -> Unit =
        { _, _, _, _ -> },
    /** 粘贴凭证 JSON 导入。 */
    val onImport: (providerId: String, raw: String) -> Unit = { _, _ -> },
    /** 刷新额度；uid 为空表示刷新该供应商全部账号。 */
    val onRefreshCredits: (providerId: String, uid: String?) -> Unit = { _, _ -> },
    /** 执行供应商专属动作（签到、兑换码等）。 */
    val onAction: (providerId: String, uid: String, action: String, payload: JsonObject) -> Unit = { _, _, _, _ -> },
    /** 重新检查设备授权状态（浏览器里已登录但轮询错过时手动重试）；region 与登录时一致。 */
    val onCheckDeviceAuth: (providerId: String, region: String) -> Unit = { _, _ -> },
    val onToggleAccount: (providerId: String, uid: String, enabled: Boolean) -> Unit = { _, _, _ -> },
    val onRemoveAccount: (providerId: String, uid: String) -> Unit = { _, _ -> },
    val onClearCooldown: (providerId: String, uid: String) -> Unit = { _, _ -> },
    /** 账号凭证级修改（如 Trae 的设备指纹）：直接替换 secret。 */
    val onUpdateSecret: (providerId: String, uid: String, secret: String, notice: String) -> Unit =
        { _, _, _, _ -> },
    /** 读账号的当前设备指纹（仅 Trae 用；其它供应商返回空串）。 */
    val deviceIdOf: (providerId: String, uid: String) -> String = { _, _ -> "" },
    /** 读账号所属区域（如 WorkBuddy 的 cn/global；无区域概念返回空串）。 */
    val regionOf: (providerId: String, uid: String) -> String = { _, _ -> "" },
    /** 读已拉取的额度包明细（键 `providerId/uid`；需要拉取时调 [onLoadCreditPacks]）。 */
    val creditPacksOf: (providerId: String, uid: String) -> List<QuotaPack> = { _, _ -> emptyList() },
    /** 异步拉取账号的额度包明细（打上游，结果回到 [creditPacksOf]）。 */
    val onLoadCreditPacks: (providerId: String, uid: String) -> Unit = { _, _ -> },
    /** 重新生成一套设备指纹（仅 Trae 用）。 */
    val onRegenerateDeviceId: (providerId: String, uid: String) -> Unit = { _, _ -> },
    /** 手动填入设备指纹（仅 Trae 用）。 */
    val onSetDeviceId: (providerId: String, uid: String, value: String) -> Unit = { _, _, _ -> },
    /** 添加自定义供应商账号（名称可选，默认同供应商名）。 */
    val onAddCustomAccount: (providerId: String, nickname: String, apiKey: String) -> Unit = { _, _, _ -> },
    /** 改账号显示名称（自定义供应商用）。 */
    val onRenameAccount: (providerId: String, uid: String, nickname: String) -> Unit = { _, _, _ -> },
    /** 读账号的 API Key（仅自定义供应商）。 */
    val accountApiKeyOf: (providerId: String, uid: String) -> String = { _, _ -> "" },
    /** 读自定义供应商已保存的模型列表。 */
    val customModelsOf: (providerId: String) -> List<String> = { _ -> emptyList() },
    /** 读自定义供应商的接口地址（拉模型用）。 */
    val customBaseUrlOf: (providerId: String) -> String = { _ -> "" },
    /** 保存自定义供应商的模型列表。 */
    val onUpdateCustomModels: (providerId: String, models: List<String>) -> Unit = { _, _ -> },
    /** 用任意 key 从云端拉模型列表（自定义供应商）。 */
    val onFetchCustomModels: (baseUrl: String, apiKey: String, onResult: (List<String>, String) -> Unit) -> Unit =
        { _, _, _ -> },
    val onNotice: (String) -> Unit = {},
)

/**
 * 一个供应商的界面组件。
 *
 * 每个供应商实现自己的图标、登录入口、账号附加信息与专属设置，
 * 由 [ProviderUiRegistry] 登记；页面骨架（列表/详情）不感知具体差异。
 */
interface ProviderUi {
    /** 与 `Provider.id` 一致（如 `trae`、`custom:<key>`）。 */
    val id: String

    /** 账号列表由 LoginEntry 自管时，页面骨架不再渲染通用「账号」卡。 */
    val managesOwnAccounts: Boolean get() = false

    /** 列表卡片上的说明文案。 */
    fun description(): String

    @Composable
    fun Icon(modifier: Modifier)

    /** 登录/添加账号入口。 */
    @Composable
    fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions)

    /** 账号详情里的专属信息区（额度明细、设备指纹、签到等）。默认不渲染。 */
    @Composable
    fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) = Unit

    /** 该供应商的专属设置区。默认不渲染。 */
    @Composable
    fun SettingsSection(settings: ProviderSettings, onUpdate: (ProviderSettings) -> Unit) = Unit
}
