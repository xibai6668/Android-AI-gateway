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
    /**
     * 余额的计量单位；空串等同 [CreditUnit.CREDITS]。
     *
     * 各家口径不一样：Trae / 小浣熊 / Loomy 是积分，Antigravity 没有积分条目时只能拿到
     * 配额剩余百分比（0~100）。不带单位就会把 87% 和 9 万积分加成「90087」，
     * 对客报余额时必须按单位分组，不能跨单位直接相加。
     */
    val unit: String = "",
)

/** 余额单位取值（[CreditInfo.unit]）。 */
object CreditUnit {
    /** 积分 / 权益包份数，各家可相加做总量。 */
    const val CREDITS = "credits"

    /** 配额剩余百分比（0~100），只能按账号展示，不能与积分类相加。 */
    const val PERCENT = "percent"
}

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
 */
data class ProviderTaskListView(
    val tasks: List<ProviderTask>,
    /**
     * 活动是否在期（对齐上游 `in_period`）：true 在期、false 不在期、**null 表示上游没给这个字段**。
     *
     * 用三态而不是默认 false：不是每个任务接口都返回 in_period，默认 false 会让界面把
     * 「上游没说」当成「活动已结束」展示。
     */
    val inPeriod: Boolean? = null,
    /** 非空表示拉取失败（凭证失效、上游报错等），界面据此提示。 */
    val error: String = "",
)

/**
 * 一笔积分/额度流水。
 *
 * 字段命名对齐小浣熊的 `/api/web/points/v1/bills`：`bill_id` / `event_name` /
 * `biz_type` / `points` / `created_at`。
 *
 * 与 [QuotaPack] 的分工：额度包回答「还剩多少」，流水回答「每一笔怎么来的」。
 */
data class PointsBill(
    val id: String,
    /** 展示名（上游 `event_name`，缺失时回退业务类型）。 */
    val title: String,
    /** 正数入账、负数支出，符号与上游一致。 */
    val points: Long,
    /** 上游业务类型（如 `daily_grant` 发放 / `daily_expire` 过期）。 */
    val bizType: String = "",
    /** 发生时间（毫秒；0 表示上游没给或无法解析）。 */
    val createdAtMillis: Long = 0,
)

/**
 * 一个账号的积分流水快照。
 *
 * [error] 非空表示拉取失败，[bills] 为空不代表没有流水。
 */
data class PointsBillListView(
    val bills: List<PointsBill>,
    val error: String = "",
)
