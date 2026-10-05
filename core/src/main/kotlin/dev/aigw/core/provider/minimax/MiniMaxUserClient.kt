package dev.aigw.core.provider.minimax

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.util.UUID

/**
 * 用户/设备/商务接口（`/v1/api/` 前缀与 `get_membership_info`）。
 *
 * 响应包裹两种格式：老接口 `{statusInfo:{code,message}, data:{…}}`，
 * matrix 接口 `{base_resp:{status_code,status_msg}, data:{…}}`（余额字段也可能直接在顶层）。
 */
class MiniMaxUserClient(private val apiBase: String = MiniMaxConstants.API_BASE) {

    /**
     * 注册设备，返回 `deviceIDStr`。
     * 设备信息 3 小时有效；过期后由调用方重新注册。
     */
    fun registerDevice(account: MiniMaxAccount, nowMillis: Long): String {
        val body = JsonObject().apply {
            addProperty("uuid", newUuid())
        }.toString()
        val (status, text) = MiniMaxHttp.request(
            apiBase, account, "POST", MiniMaxConstants.PATH_DEVICE_REGISTER, body, nowMillis,
        )
        if (status !in 200..299) throw MiniMaxApiException(status, MiniMaxErrors.extractMessage(text).ifEmpty { "设备注册失败（HTTP $status）" })
        MiniMaxErrors.extractCode(text)?.takeIf { it != 0L }?.let { code ->
            throw MiniMaxApiException(status, "设备注册失败（业务码 $code）：${MiniMaxErrors.extractMessage(text)}")
        }
        return findString(text, "deviceIDStr")
            ?: throw MiniMaxApiException(status, "设备注册响应缺少 deviceIDStr：${text.take(120)}")
    }

    /** Token 存活检查：`/v1/api/user/info` 能取到 userInfo 即存活。 */
    fun tokenAlive(account: MiniMaxAccount, nowMillis: Long): Boolean = try {
        val (status, text) = MiniMaxHttp.request(
            apiBase, account, "GET", MiniMaxConstants.PATH_USER_INFO, null, nowMillis,
            readTimeoutMs = 15_000,
        )
        status in 200..299 && findObject(text, "userInfo") != null
    } catch (_: Exception) {
        false
    }

    /** 会员/积分余额：`(planName, remainCredit)`；取不到的字段为 null。 */
    fun membership(account: MiniMaxAccount, nowMillis: Long): Membership? {
        val (status, text) = MiniMaxHttp.request(
            apiBase, account, "POST", MiniMaxConstants.PATH_MEMBERSHIP, "{}", nowMillis,
            readTimeoutMs = 15_000,
        )
        if (status !in 200..299) throw MiniMaxApiException(status, MiniMaxErrors.extractMessage(text).ifEmpty { "查询余额失败（HTTP $status）" })
        MiniMaxErrors.extractCode(text)?.takeIf { it != 0L }?.let { code ->
            throw MiniMaxApiException(status, "查询余额失败（业务码 $code）：${MiniMaxErrors.extractMessage(text)}")
        }
        val plan = findString(text, "plan_name")
        val remain = findLong(text, "total_remains_credit")
        if (plan == null && remain == null) return null
        return Membership(plan.orEmpty(), remain)
    }

    /** 删除会话（上游可能不支持，静默失败——只是避免账号的对话列表堆积）。 */
    fun deleteConversation(account: MiniMaxAccount, chatId: String, nowMillis: Long) {
        if (chatId.isEmpty()) return
        runCatching {
            MiniMaxHttp.request(
                apiBase, account, "DELETE", MiniMaxConstants.PATH_CHAT_DELETE + chatId, null, nowMillis,
                readTimeoutMs = 10_000,
            )
        }
    }

    data class Membership(val planName: String, val remainCredit: Long?)

    private fun newUuid(): String = UUID.randomUUID().toString()

    /** 在整包响应里按 key 找字符串字段：顶层 → data → 具名包裹，逐层兼容。 */
    private fun findString(body: String, key: String): String? {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        obj.stringOrNull(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        obj.objOrNull("data")?.stringOrNull(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    private fun findLong(body: String, key: String): Long? {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        obj.longOrNull(key)?.let { return it }
        obj.objOrNull("data")?.longOrNull(key)?.let { return it }
        return null
    }

    private fun findObject(body: String, key: String): JsonObject? {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        obj.objOrNull(key)?.let { return it }
        return obj.objOrNull("data")?.objOrNull(key)
    }
}
