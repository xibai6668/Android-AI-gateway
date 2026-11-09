package dev.aigw.app.ui

import dev.aigw.core.store.KeyValueStore

/**
 * 首页可摆放的组件。
 *
 * [ordinal] 决定顺序：数组下标即用户排定的位置，因此枚举声明顺序就是默认布局。
 * 新增组件只需在此追加一项并在 [HomeScreen] 的 `when` 里补一个分支，无需改动存储。
 */
enum class HomeWidget(val title: String) {
    /** 服务状态、接入地址与启停。 */
    STATUS("网关状态"),

    /** 可用账号、今日请求、今日 tokens。 */
    SUMMARY("今日用量"),

    /** 批量签到、任务中心、刷新额度。 */
    ACTIONS("快捷操作"),

    /** 最近一次请求的路由流水。 */
    LAST_CALL("最近路由"),

    /** 上下文容量与按模型的 token 分布。 */
    CONTEXT("上下文容量"),
}

/**
 * 首页布局持久化：只存「顺序」与「隐藏项」。
 *
 * 存隐藏项而不是存可见项：新增组件在后者的语义下会被判成「未启用」而永远不出现，
 * 在前者语义下则自动出现在末尾——老用户升级后不会少东西。
 */
class HomeLayoutStore(private val store: KeyValueStore) {

    /** 当前顺序（只含枚举里仍存在的项，脏数据自动丢弃）。 */
    fun order(): List<HomeWidget> {
        val saved = store.read(KEY_ORDER).orEmpty()
            .split(',')
            .mapNotNull { name -> HomeWidget.entries.firstOrNull { it.name == name } }
            .distinct()
        // 存储里没有的组件追加到末尾，保证新增组件对老用户可见
        return saved + HomeWidget.entries.filterNot { it in saved }
    }

    fun setOrder(order: List<HomeWidget>) {
        store.write(KEY_ORDER, order.joinToString(",") { it.name })
    }

    fun hidden(): Set<HomeWidget> = store.read(KEY_HIDDEN).orEmpty()
        .split(',')
        .mapNotNull { name -> HomeWidget.entries.firstOrNull { it.name == name } }
        .toSet()

    fun setHidden(hidden: Set<HomeWidget>) {
        if (hidden.isEmpty()) store.delete(KEY_HIDDEN) else store.write(KEY_HIDDEN, hidden.joinToString(",") { it.name })
    }

    private companion object {
        const val KEY_ORDER = "settings/home.order"
        const val KEY_HIDDEN = "settings/home.hidden"
    }
}
