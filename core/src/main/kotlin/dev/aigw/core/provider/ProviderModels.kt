package dev.aigw.core.provider

/** 统一模型条目。 */
data class ProviderModel(
    val id: String,
    val name: String,
    val contextWindow: Long = 0,
    /** provider 私有的附加信息（Trae 的倍率说明、Loomy 的能力位等），由各自 UI 组件解读。 */
    val extra: Map<String, String> = emptyMap(),
)

/** 模型目录视图。 */
data class ProviderModelCatalogView(
    val models: List<ProviderModel>,
    val fromFallback: Boolean,
    val error: String,
)

/** 账号额度/积分快照。 */
data class CreditInfo(
    val balance: Long,
    /** false 表示上游未返回可解析的额度字段，界面显示「—」而不是 0。 */
    val known: Boolean = true,
    val detail: String = "",
)

/** provider 专属动作的结果。 */
data class ProviderActionResult(
    val ok: Boolean,
    val message: String,
    val unsupported: Boolean = false,
) {
    companion object {
        fun success(message: String = "") = ProviderActionResult(true, message)
        fun failure(message: String) = ProviderActionResult(false, message)
        fun unsupported(action: String) =
            ProviderActionResult(false, "该供应商不支持「$action」", unsupported = true)
    }
}
