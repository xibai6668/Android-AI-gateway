package dev.aigw.core.provider.trae

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 签到状态。 */
data class CheckinStatus(
    val checkedIn: Boolean,
    val credits: Long,
    val enabled: Boolean,
)

/** 签到结果。 */
enum class ClaimOutcome {
    /** 本次成功领取。 */
    CLAIMED,

    /** 账号今天已经签过（不算失败）。 */
    ALREADY_CHECKED_IN,
}

/**
 * 一个额度包（Trae 的「奖励」就是按包发放的）。
 *
 * 上游 `group_name` / `group_type` 用于区分来源：`每日签到`、`每月登录积分`、`用户福利` 等。
 */
data class CreditPack(
    val name: String,
    val group: String,
    val limit: Long,
    val used: Long,
    val remain: Long,
    /** 过期时间（Unix 秒），0 表示上游未给。 */
    val expireAt: Long,
)

/**
 * 额度明细。
 *
 * 注意：上游返回的是**权益包**（含 work 包）的额度聚合，不等于 SOLO 通道实际可用的 ide_credits；
 * SOLO 真正剩余多少要看对话流里的 `notify_usage`。这里只做诚实的展示。
 */
data class EntUsage(
    val remain: Long,
    val limit: Long,
    val used: Long,
    val packs: Int,
    /** 命中的上游接口路径；为空表示没有接口能解析出额度。 */
    val endpoint: String = "",
    /** 是否真的从响应里解析到了权益包列表。 */
    val parsed: Boolean = false,
    /** 响应原文（截断），用于接口改结构时排查。 */
    val rawPreview: String = "",
    /** 每个额度包的明细，供「奖励与活动」页展示。 */
    val details: List<CreditPack> = emptyList(),
)

/**
 * 签到与额度（`api.trae.cn`）。
 *
 * 上游这几个接口在不同入口/客户端版本上结构不一致（顶层字段 vs `data` 包裹、
 * `enable` vs `enabled`、到底哪个接口带额度），所以解析全部走 [TraeUgParse] 的容错分支，
 * 并把命中的接口与响应原文带回去，避免出现「HTTP 200 但静默显示 0」。
 */
class TraeCheckinClient(
    private val version: TraeVersion,
    private val host: String = TraeConstants.UG_HOST,
) {

    fun status(account: TraeAccount): CheckinStatus {
        val raw = post(account, TraeConstants.EP_CHECKIN_STATUS, "{}")
        return TraeUgParse.parseStatus(raw)
            ?: throw TraeHttpException(200, TraeErrorKind.SERVER, "签到状态响应无法解析：${raw.take(PREVIEW_LIMIT)}")
    }

    /**
     * 执行签到。
     *
     * 上游即使业务失败也返回 HTTP 200，所以必须看 body 里的 code：
     * 1001 或含「已签到」类文案视为今天已签；9074 原样抛出，由调用方决定是否重试；
     * 其余非成功码按失败抛出。
     */
    fun claim(account: TraeAccount): ClaimOutcome {
        val raw = post(account, TraeConstants.EP_CHECKIN_CLAIM, "{}")
        if (TraeUgParse.succeeded(raw)) return ClaimOutcome.CLAIMED
        if (TraeUgParse.alreadyCheckedIn(raw)) return ClaimOutcome.ALREADY_CHECKED_IN

        val code = TraeErrors.extractCode(raw)
        val message = TraeErrors.extractMessage(raw)
        throw TraeHttpException(200, TraeErrorKind.CLIENT, message.ifEmpty { "签到失败（code=$code）" }, code)
    }

    /**
     * 查询额度。按候选接口顺序尝试，任一接口解析出权益包列表就返回。
     * 全部失败时返回 `parsed = false` 并带上响应原文，由调用方记入日志。
     */
    fun entUsage(account: TraeAccount): EntUsage {
        var preview = ""
        var lastError: Exception? = null

        for (endpoint in ENT_ENDPOINTS) {
            val raw = try {
                post(account, endpoint.path, endpoint.body)
            } catch (e: Exception) {
                lastError = e
                continue
            }
            if (preview.isEmpty()) preview = raw.take(PREVIEW_LIMIT)

            val usage = TraeUgParse.aggregateEntitlement(raw) ?: continue
            return usage.copy(endpoint = endpoint.path, parsed = true, rawPreview = raw.take(PREVIEW_LIMIT))
        }

        if (preview.isEmpty() && lastError != null) throw lastError
        return EntUsage(remain = 0, limit = 0, used = 0, packs = 0, rawPreview = preview)
    }

    private fun post(account: TraeAccount, path: String, body: String): String {
        val conn = TraeHttp.post(
            url = host + path,
            body = body,
            connectTimeoutMs = TraeHttp.CONNECT_TIMEOUT_MS,
            readTimeoutMs = TraeHttp.READ_TIMEOUT_MS,
        ) { TraeHeaders.ug(it, account, version) }

        val status = conn.responseCode
        val raw = TraeHttp.readBody(conn)
        if (status >= 400) {
            throw TraeHttpException(status, TraeErrors.fromStatus(status, raw), raw, TraeErrors.extractCode(raw))
        }
        return raw
    }

    private companion object {
        const val PREVIEW_LIMIT = 2_000

        data class EntEndpoint(val path: String, val body: String)

        val ENT_ENDPOINTS = listOf(
            EntEndpoint(TraeConstants.EP_ENT_USAGE_WEB, """{"require_usage":true}"""),
            EntEndpoint(TraeConstants.EP_ENT_USAGE, "{}"),
            EntEndpoint(TraeConstants.EP_ENTITLEMENT_LIST, """{"require_usage":true}"""),
        )
    }
}

/** 签到与额度接口的响应解析。抽出来是为了能单独单测这些容错分支。 */
internal object TraeUgParse {

    /** 上游把「今天已签」也用 1001 表示（与对话接口的 1001 含义不同）。 */
    private const val ALREADY_CODE = 1001L

    private val ALREADY_MARKERS = listOf(
        "已签到", "已经签到", "明日再来", "今日已完成", "已领取", "already", "checked",
    )

    fun parseStatus(raw: String): CheckinStatus? {
        val root = parseObject(raw) ?: return null
        val scope = unwrap(root)
        return CheckinStatus(
            checkedIn = scope.bool("checked_in") ?: scope.bool("checkedIn") ?: false,
            credits = scope.long("credits"),
            enabled = scope.bool("enable") ?: scope.bool("enabled") ?: false,
        )
    }

    /**
     * 聚合权益包：remain = Σ(credits_limit - credits_amount)，与上游文档给的余额口径一致。
     * 返回 null 表示响应里根本没有权益包列表（调用方应去试下一个接口）。
     */
    fun aggregateEntitlement(raw: String): EntUsage? {
        val packs = findPacks(raw) ?: return null
        var remain = 0L
        var limit = 0L
        var used = 0L
        val details = ArrayList<CreditPack>()

        for (element in packs) {
            val pack = element?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val base = pack.obj("entitlement_base_info")
            val creditsLimit = base?.obj("quota")?.long("credits_limit") ?: 0L
            if (creditsLimit <= 0L) continue
            // usage.credits_amount 是已用积分，可能是小数；部分包（如刚发的签到奖励）没有 usage
            val creditsUsed = pack.obj("usage")?.get("credits_amount")
                ?.let { runCatching { it.asDouble }.getOrDefault(0.0) }?.toLong() ?: 0L

            val group = pack.str("group_name")
            val name = pack.str("display_desc").ifEmpty { group }
                .ifEmpty { base?.obj("product_extra")?.obj("package_extra")?.str("package_name").orEmpty() }
                .ifEmpty { "额度包" }

            limit += creditsLimit
            used += creditsUsed
            remain += creditsLimit - creditsUsed
            details.add(
                CreditPack(
                    name = name,
                    group = group,
                    limit = creditsLimit,
                    used = creditsUsed,
                    remain = creditsLimit - creditsUsed,
                    expireAt = firstPositive(pack.long("expire_time"), base?.long("end_time") ?: 0L),
                ),
            )
        }
        return EntUsage(
            remain = remain,
            limit = limit,
            used = used,
            packs = details.size,
            details = details,
        )
    }

    /** 业务是否成功。没有 code / success / status 字段时按 HTTP 2xx 视为成功。 */
    fun succeeded(raw: String): Boolean {
        val root = parseObject(raw) ?: return true
        val code = root.long("code")
        if (code == 0L || code == 200L) return true
        if (root.bool("success") == true) return true
        if (root.str("status").equals("success", ignoreCase = true)) return true
        return !root.has("code") && !root.has("success") && !root.has("status")
    }

    fun alreadyCheckedIn(raw: String): Boolean {
        val code = TraeErrors.extractCode(raw)
        if (code == ALREADY_CODE) return true
        val message = TraeErrors.extractMessage(raw)
        return ALREADY_MARKERS.any { message.contains(it, ignoreCase = true) }
    }

    private fun findPacks(raw: String): JsonArray? {
        val root = parseObject(raw) ?: return null
        return root.array("user_entitlement_pack_list")
            ?: root.obj("data")?.array("user_entitlement_pack_list")
            ?: root.obj("result")?.array("user_entitlement_pack_list")
            ?: root.obj("Result")?.array("user_entitlement_pack_list")
    }

    /** 兼容顶层与 `data` / `result` / `Result` 包裹（OAuth 系列用的是大写 Result）。 */
    private fun unwrap(root: JsonObject): JsonObject =
        root.obj("data") ?: root.obj("result") ?: root.obj("Result") ?: root

    private fun parseObject(raw: String): JsonObject? =
        runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()

    private fun firstPositive(vararg values: Long): Long = values.firstOrNull { it > 0 } ?: 0L
}

private fun JsonObject.array(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.bool(key: String): Boolean? =
    get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() }
