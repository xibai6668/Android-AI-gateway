package dev.aigw.app.ui

import dev.aigw.core.store.KeyValueStore

/**
 * 首页可摆放的组件。
 *
 * **声明顺序就是默认布局**（与 2.31.0 正式版一致）：
 * 网关状态 → 调度拓扑 → 指标网格 → 消耗趋势 → 快捷操作 → 上游配额池。
 * 其余组件（今日用量/最近路由/上下文容量）默认隐藏，用户自行添加。
 *
 * 新增组件只需在此追加一项并在 [HomeScreen] 的 `when` 里补一个分支，无需改动存储。
 */
enum class HomeWidget(val title: String) {
    /** 服务状态、接入地址与启停。 */
    STATUS("网关状态"),

    /** 供应商分发拓扑。 */
    TOPOLOGY("调度拓扑"),

    /** 账号就绪、模型数等 2×3 网格。 */
    METRICS("指标网格"),

    /** 近 14 天消耗趋势。 */
    TREND("消耗趋势"),

    /** 批量签到、任务中心、刷新额度。 */
    ACTIONS("快捷操作"),

    /** 各账号的上游配额包。 */
    ANTIGRAVITY("上游配额池"),

    /** 可用账号、今日请求、今日 tokens（默认隐藏）。 */
    SUMMARY("今日用量"),

    /** 最近一次请求的路由流水（默认隐藏）。 */
    LAST_CALL("最近路由"),

    /** 上下文容量与按模型的 token 分布（默认隐藏）。 */
    CONTEXT("上下文容量"),
}

/**
 * 首页布局持久化：只存「顺序」与「隐藏项」。
 *
 * 存隐藏项而不是存可见项：新增组件在后者的语义下会被判成「未启用」而永远不出现，
 * 在前者语义下则自动出现在末尾——老用户升级后不会少东西。
 */
class HomeLayoutStore(private val store: KeyValueStore) {

    /**
     * 布局代次。改次意味着默认布局换过，需要丢弃旧的用户布局。
     * 2：默认布局改为 2.31.0 正式版的排列（网关状态/拓扑/指标/趋势/快捷操作/配额池）。
     */
    private fun migrateIfNeeded() {
        if (store.read(KEY_VERSION) == LAYOUT_VERSION) return
        // 旧布局是首测阶段留下的（含那几版错误的默认隐藏集合），直接丢掉重来；
        // 用户改过的自定义排版也一并重置——没有可靠手段区分两者。
        store.delete(KEY_ORDER)
        store.delete(KEY_HIDDEN)
        store.write(KEY_VERSION, LAYOUT_VERSION)
    }

    /** 当前顺序（只含枚举里仍存在的项，脏数据自动丢弃）。 */
    fun order(): List<HomeWidget> {
        migrateIfNeeded()
        val saved = store.read(KEY_ORDER).orEmpty()
            .split(',')
            .mapNotNull { name -> HomeWidget.entries.firstOrNull { it.name == name } }
            .distinct()
        // 存储里没有的组件追加到末尾，保证新增组件对老用户可见
        return saved + HomeWidget.entries.filterNot { it in saved }
    }

    fun setOrder(order: List<HomeWidget>) {
        store.write(KEY_VERSION, LAYOUT_VERSION)
        store.write(KEY_ORDER, order.joinToString(",") { it.name })
    }

    fun hidden(): Set<HomeWidget> {
        migrateIfNeeded()
        // 键从未写过时用默认集合：默认布局里只展示正式版那六块，
        // 其余（今日用量/最近路由/上下文容量）留给用户自己加。
        val raw = store.read(KEY_HIDDEN) ?: return DEFAULT_HIDDEN
        return raw.split(',')
            .mapNotNull { name -> HomeWidget.entries.firstOrNull { it.name == name } }
            .toSet()
    }

    fun setHidden(hidden: Set<HomeWidget>) {
        // 清空时也写入空串而不是删键：删键会让上面的「从未写过」重归于默认隐藏，
        // 用户明明把组件都加回来了，下次启动又消失。
        store.write(KEY_VERSION, LAYOUT_VERSION)
        store.write(KEY_HIDDEN, hidden.joinToString(",") { it.name })
    }

    private companion object {
        const val KEY_ORDER = "settings/home.order"
        const val KEY_HIDDEN = "settings/home.hidden"
        const val KEY_VERSION = "settings/home.layoutVersion"
        const val LAYOUT_VERSION = "2"

        val DEFAULT_HIDDEN = setOf(HomeWidget.SUMMARY, HomeWidget.LAST_CALL, HomeWidget.CONTEXT)
    }
}
