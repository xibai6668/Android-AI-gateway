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

/**
 * 一个成长任务的快照，供 UI 展示「有哪些任务、哪些做了、哪些没做」。
 *
 * 字段命名对齐 WorkBuddy（腾讯 CodeBuddy）的 `/v2/activity/growth/tasks`：
 * `task_code` / `title` / `description` / `current` / `target` / `accept_status` /
 * `locked` / `claimed` / `reward_credit`。其它供应商可自行映射。
 */
data class ProviderTask(
    val code: String,
    val title: String,
    val description: String = "",
    /** 当前进度与目标；[target] 为 0 表示该任务无进度概念。 */
    val current: Long = 0,
    val target: Long = 0,
    /** 上游接取状态（如空串 / claimed / accepted / completed）。 */
    val status: String = "",
    /** 未解锁（前置条件未达成）。 */
    val locked: Boolean = false,
    /** 已领取奖励。 */
    val claimed: Boolean = false,
    val rewardCredit: Long = 0,
) {
    /** 进度达标（有目标且当前进度已达）。 */
    val progressDone: Boolean get() = target > 0 && current >= target

    /** 已完成：已领取，或进度已达标。 */
    val completed: Boolean get() = claimed || progressDone

    /** 可领取奖励：已达标且尚未领取。 */
    val claimable: Boolean get() = !claimed && progressDone

    /** 进度文案：有目标显示「当前/目标」，已领取显示「已领取」，否则回退上游状态或「未知」。 */
    fun progressText(): String = when {
        target > 0 -> "$current/$target"
        claimed -> "已领取"
        status.isNotEmpty() -> status
        else -> "未知"
    }
}

/**
 * 一个账号的成长任务列表快照。
 *
 * [inPeriod] 表示活动是否在期（对齐上游 `in_period`）：不在期时任务可能为空。
 */
data class ProviderTaskListView(
    val tasks: List<ProviderTask>,
    val inPeriod: Boolean = false,
    /** 非空表示拉取失败（凭证失效、上游报错等），界面据此提示。 */
    val error: String = "",
)
