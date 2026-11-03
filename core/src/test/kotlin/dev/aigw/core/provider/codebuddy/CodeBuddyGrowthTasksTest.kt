package dev.aigw.core.provider.codebuddy

import dev.aigw.core.provider.ProviderTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 成长任务列表解析。
 *
 * 报文取自上游 `GET /v2/activity/growth/tasks` 的真实形状：**任务挂在 `data.tasks` 下**
 * （与 `wb_tasks.py`、`workbuddy-auto-signin` 等反代实现一致），进度在 `progress.current/target`，
 * 接取状态字段是 `accept_status`（claimed / in_progress / not_accepted）。
 */
class CodeBuddyGrowthTasksTest {

    private val provider = CodeBuddyProvider()

    private val wrapped = """
        {
          "code": 0,
          "msg": "ok",
          "data": {
            "in_period": true,
            "tasks": [
              {
                "task_code": "chat_5",
                "title": "发起 5 次对话",
                "description": "和 AI 聊天 5 次",
                "accept_status": "in_progress",
                "progress": {"current": 2, "target": 5},
                "reward_credit": 100,
                "has_reward": true
              },
              {
                "task_code": "first_buddy",
                "title": "领取一只 Buddy",
                "accept_status": "claimed",
                "progress": {"current": 1, "target": 1},
                "reward_credit": 300
              },
              {
                "task_code": "create_canvas",
                "title": "创建设计任务",
                "accept_status": "not_accepted",
                "progress": {"current": 0, "target": 1},
                "reward_credit": 300
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `解包 data 外层并解析每条任务`() {
        val view = provider.parseGrowthTasks(wrapped)
        assertEquals(3, view.tasks.size, "应解析出全部任务：${view.error}")

        val chat = view.tasks.first { it.code == "chat_5" }
        assertEquals("发起 5 次对话", chat.title)
        assertEquals(2L, chat.current)
        assertEquals(5L, chat.target)
        assertEquals("in_progress", chat.status)
        assertEquals(100L, chat.rewardCredit)
        assertTrue(!chat.completed, "2/5 不应算完成")
        assertTrue(!chat.claimable)

        val buddy = view.tasks.first { it.code == "first_buddy" }
        assertTrue(buddy.claimed, "accept_status=claimed 应标记为已领取")
        assertTrue(buddy.completed)

        val canvas = view.tasks.first { it.code == "create_canvas" }
        assertTrue(!canvas.completed)
        assertEquals("0/1", canvas.progressText())
    }

    @Test
    fun `任务在顶层时同样能解析`() {
        val view = provider.parseGrowthTasks(
            """{"code":0,"tasks":[{"task_code":"skill_1","title":"体验技能","accept_status":"not_accepted"}]}""",
        )
        assertEquals(1, view.tasks.size)
        assertEquals("skill_1", view.tasks.first().code)
    }

    @Test
    fun `上游未给 in_period 时不谎称活动未开启`() {
        // 成长任务接口不返回 in_period；此时不能断言「活动未开启」（那是编造上游没说的事）
        val view = provider.parseGrowthTasks("""{"code":0,"data":{"tasks":[]}}""")
        assertTrue(view.tasks.isEmpty())
        assertNull(view.inPeriod, "字段缺失应记为未知：${view.inPeriod}")
    }

    @Test
    fun `上游明确 not in period 时如实记录`() {
        val view = provider.parseGrowthTasks("""{"code":0,"data":{"in_period":false,"tasks":[]}}""")
        assertEquals(false, view.inPeriod)
    }

    @Test
    fun `缺少 task_code 的条目被跳过而不是产出空壳任务`() {
        val view = provider.parseGrowthTasks(
            """{"code":0,"data":{"tasks":[{"title":"没有 code"},{"task_code":"ok_1","title":"正常"}]}}""",
        )
        assertEquals(listOf("ok_1"), view.tasks.map { it.code })
    }

    // ------------------------------------------------------- 「接单」判定

    @Test
    fun `未接取的任务需要接单`() {
        val task = task(code = "a", status = "not_accepted", current = 0, target = 1)
        assertTrue(provider.needsTaskAccept(task), "上游要求先接单才开始记进度")
    }

    @Test
    fun `已接单但未做完的任务不重复提交`() {
        val task = task(code = "b", status = "in_progress", current = 2, target = 5)
        assertTrue(!provider.needsTaskAccept(task))
    }

    @Test
    fun `已完成未领取的任务要去领奖`() {
        val task = task(code = "c", status = "in_progress", current = 5, target = 5)
        assertTrue(provider.needsTaskAccept(task))
        assertTrue(task.claimable)
    }

    @Test
    fun `已领取的任务不再处理`() {
        val task = task(code = "d", status = "claimed", current = 1, target = 1)
        assertTrue(!provider.needsTaskAccept(task))
    }

    @Test
    fun `状态缺失但未达标时也去接单`() {
        val task = task(code = "e", status = "", current = 0, target = 1)
        assertTrue(provider.needsTaskAccept(task))
    }

    // ------------------------------------------------------- 读取时自动领取

    @Test
    fun `自动领取只挑未解锁且已达标未领的任务`() {
        val done = task(code = "done", status = "in_progress", current = 5, target = 5)
        val claimed = task(code = "claimed", status = "claimed", current = 5, target = 5)
        val notDone = task(code = "not_done", status = "in_progress", current = 2, target = 5)
        val locked = ProviderTask(
            code = "locked",
            title = "locked",
            current = 5,
            target = 5,
            locked = true,
        )

        val targets = provider.autoClaimTargets(listOf(done, claimed, notDone, locked))
        assertEquals(listOf("done"), targets.map { it.code })
    }

    private fun task(code: String, status: String, current: Long, target: Long) = ProviderTask(
        code = code,
        title = code,
        current = current,
        target = target,
        status = status,
        claimed = status == "claimed",
    )
}
