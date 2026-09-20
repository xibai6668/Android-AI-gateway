package dev.aigw.core.provider.loomy

import com.google.gson.JsonObject
import dev.aigw.core.util.bool
import dev.aigw.core.util.long
import dev.aigw.core.util.objOrNull

/** 一个新手任务。 */
data class OnboardingTask(
    val key: String,
    val title: String,
    val points: Int,
    val completed: Boolean,
)

/** 新手任务列表。 */
data class OnboardingTasks(
    val tasks: List<OnboardingTask>,
    /** 已完成任务的积分合计（按官方客户端的做法本地现算，不信任服务端回传）。 */
    val earned: Int,
    val total: Int,
)

/** 完成单个任务的结果。 */
data class TaskCompletion(
    val key: String,
    val alreadyCompleted: Boolean,
    /** 上游回传的余额（可能缺失，缺失为 -1）。 */
    val balance: Long,
)

/**
 * 新手任务客户端（`/api/v1/onboarding/tasks`）。
 *
 * 这是 Loomy 里唯一可「一键领取」的积分任务：官方客户端把 8 个任务与各自积分写死在
 * `TASK_POINTS` 注册表里（见 electron/onboarding-service.js），服务端只记录完成状态，
 * 积分合计由客户端按注册表现算——这里保持一致。
 *
 * 注意：任务本身对应真实动作（首次对话、生成 PPT 等），服务端会校验；
 * 没做过就点完成，上游会拒绝，这里如实把上游原话透出来。
 */
class LoomyTaskClient(
    private val apiBase: String = LoomyConstants.API_BASE,
) {

    fun tasks(session: String): OnboardingTasks {
        val raw = get(LoomyConstants.PATH_ONBOARDING_TASKS, session)
        val data = raw.objOrNull("data") ?: JsonObject()
        val state = data.objOrNull("tasks") ?: JsonObject()
        val tasks = TASK_POINTS.map { (key, points) ->
            OnboardingTask(key = key, title = titleOf(key), points = points, completed = state.bool(key))
        }
        return OnboardingTasks(
            tasks = tasks,
            earned = tasks.filter { it.completed }.sumOf { it.points },
            total = TASK_POINTS.values.sum(),
        )
    }

    fun complete(session: String, key: String): TaskCompletion {
        val body = JsonObject().apply { addProperty("key", key) }.toString()
        val headers = apiHeaders(session) + mapOf("Content-Type" to "application/json")
        val (status, responseBody) = httpJson("$apiBase${LoomyConstants.PATH_ONBOARDING_COMPLETE}", "POST", headers, body)
        val data = decodeEnvelope(status, responseBody).objOrNull("data") ?: JsonObject()
        return TaskCompletion(
            key = key,
            alreadyCompleted = data.bool("alreadyCompleted"),
            balance = if (data.has("balance")) data.long("balance") else -1L,
        )
    }

    private fun get(path: String, session: String): JsonObject {
        val (status, body) = httpJson("$apiBase$path", "GET", apiHeaders(session))
        return decodeEnvelope(status, body)
    }

    private fun apiHeaders(session: String): Map<String, String> = mapOf(
        LoomyConstants.TOKEN_HEADER to session,
        LoomyConstants.TRACEPARENT_HEADER to LoomyTrace.newTraceparent(),
        LoomyConstants.VERSION_HEADER to LoomyConstants.CLIENT_VERSION,
    )

    companion object {
        /**
         * 任务 key → 积分。与官方客户端 `electron/onboarding-service.js` 的 `TASK_POINTS`
         * 逐项一致（合计 10000）。
         */
        val TASK_POINTS: LinkedHashMap<String, Int> = linkedMapOf(
            "first_message" to 500,
            "pick_skill" to 1000,
            "generate_ppt" to 1500,
            "set_schedule" to 1000,
            "install_skill" to 1500,
            "configure_remote" to 1000,
            "create_soul" to 1500,
            "share_soul" to 2000,
        )

        /**
         * 任务标题。
         *
         * 官方客户端的标题写在渲染进程的 `task-registry.js` 里（打包在 asar 内，未解出），
         * 这里是**按 key 语义推断**的展示文案；不影响接口行为。
         */
        fun titleOf(key: String): String = when (key) {
            "first_message" -> "完成首次对话"
            "pick_skill" -> "选择技能"
            "generate_ppt" -> "生成 PPT"
            "set_schedule" -> "设置定时任务"
            "install_skill" -> "安装技能"
            "configure_remote" -> "配置远程控制"
            "create_soul" -> "创建搭子"
            "share_soul" -> "分享搭子"
            else -> key
        }
    }
}
