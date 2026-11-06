package dev.aigw.core.gateway

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.boolOrNull
import dev.aigw.core.util.intOrNull
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/** 路由组成员：指向某个供应商上的某个模型。 */
data class GroupMember(
    val providerId: String,
    val model: String,
    val priority: Int = 100,
    val weight: Int = 1,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("providerId", providerId)
        addProperty("model", model)
        addProperty("priority", priority)
        addProperty("weight", weight)
    }

    companion object {
        fun fromJson(obj: JsonObject): GroupMember? {
            val providerId = obj.stringOrNull("providerId").orEmpty().trim()
            val model = obj.stringOrNull("model").orEmpty().trim()
            if (providerId.isEmpty() || model.isEmpty()) return null
            return GroupMember(
                providerId = providerId,
                model = model,
                priority = obj.intOrNull("priority") ?: 100,
                weight = obj.intOrNull("weight") ?: 1,
            )
        }
    }
}

/** 组内排序策略。 */
enum class GroupStrategy {
    /** priority 降序，同优先级内按 weight 加权随机。 */
    ORDER,

    /** 纯权重加权随机，忽略 priority。 */
    WEIGHT,
}

/**
 * 规则：命中时把某一轮请求优先交给组内指定成员，其余成员退化为故障转移候选。
 *
 * 所有条件为「与」，未设置的条件不参与判断。规则只在**一轮开始时**决策——
 * 工具回调过程中不能换模型，否则上游前缀缓存整段作废（这是 magpie 的实测结论）。
 */
data class GroupRule(
    val targetProviderId: String,
    val targetModel: String,
    /** 提示词 token 估算 ≥ 该值才命中；0 表示不限。 */
    val minPromptTokens: Long = 0L,
    /** 请求里带图片才命中。 */
    val requireImage: Boolean = false,
    /** 是上下文压缩请求（/compact 类）才命中。 */
    val compactOnly: Boolean = false,
    /** 工作目录（前缀匹配到路径段边界）命中其一；空表示不限。 */
    val folders: List<String> = emptyList(),
    /** 生效时段（本地时间的「当天第几分钟」，含首尾；-1 表示不限）。 */
    val startMinuteOfDay: Int = -1,
    val endMinuteOfDay: Int = -1,
    /** 生效星期，1=周一 … 7=周日；空表示每天。 */
    val daysOfWeek: Set<Int> = emptySet(),
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("targetProviderId", targetProviderId)
        addProperty("targetModel", targetModel)
        addProperty("minPromptTokens", minPromptTokens)
        addProperty("requireImage", requireImage)
        addProperty("compactOnly", compactOnly)
        add("folders", JsonArray().apply { folders.forEach { add(it) } })
        addProperty("startMinuteOfDay", startMinuteOfDay)
        addProperty("endMinuteOfDay", endMinuteOfDay)
        add("daysOfWeek", JsonArray().apply { daysOfWeek.sorted().forEach { add(it) } })
    }

    companion object {
        fun fromJson(obj: JsonObject): GroupRule? {
            val providerId = obj.stringOrNull("targetProviderId").orEmpty().trim()
            val model = obj.stringOrNull("targetModel").orEmpty().trim()
            if (providerId.isEmpty() || model.isEmpty()) return null
            val folders = obj.arrayOrNull("folders")
                ?.mapNotNull { if (it.isJsonPrimitive) it.asString.trim() else null }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            val days = obj.arrayOrNull("daysOfWeek")
                ?.mapNotNull { if (it.isJsonPrimitive) runCatching { it.asInt }.getOrNull() else null }
                ?.filter { it in 1..7 }
                ?.toSet()
                ?: emptySet()
            return GroupRule(
                targetProviderId = providerId,
                targetModel = model,
                minPromptTokens = (obj.longOrNull("minPromptTokens") ?: 0L).coerceAtLeast(0L),
                requireImage = obj.boolOrNull("requireImage") ?: false,
                compactOnly = obj.boolOrNull("compactOnly") ?: false,
                folders = folders,
                startMinuteOfDay = obj.intOrNull("startMinuteOfDay") ?: -1,
                endMinuteOfDay = obj.intOrNull("endMinuteOfDay") ?: -1,
                daysOfWeek = days,
            )
        }
    }
}

/** 一个路由组：组名即对外模型 id（`group/<id>`）。 */
data class RoutingGroup(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val strategy: GroupStrategy = GroupStrategy.ORDER,
    val members: List<GroupMember> = emptyList(),
    val rules: List<GroupRule> = emptyList(),
) {
    val displayName: String get() = name.ifBlank { id }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("name", name)
        addProperty("enabled", enabled)
        addProperty("strategy", strategy.name)
        add("members", JsonArray().apply { members.forEach { add(it.toJson()) } })
        add("rules", JsonArray().apply { rules.forEach { add(it.toJson()) } })
    }

    companion object {
        fun fromJson(obj: JsonObject): RoutingGroup? {
            val id = obj.stringOrNull("id").orEmpty().trim()
            if (id.isEmpty()) return null
            val strategy = runCatching { GroupStrategy.valueOf(obj.stringOrNull("strategy").orEmpty()) }
                .getOrDefault(GroupStrategy.ORDER)
            val members = obj.arrayOrNull("members")
                ?.mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject?.let(GroupMember::fromJson) }
                ?: emptyList()
            val rules = obj.arrayOrNull("rules")
                ?.mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject?.let(GroupRule::fromJson) }
                ?: emptyList()
            return RoutingGroup(
                id = id,
                name = obj.stringOrNull("name").orEmpty(),
                enabled = obj.boolOrNull("enabled") ?: true,
                strategy = strategy,
                members = members,
                rules = rules,
            )
        }
    }
}

/** 规则判定上下文（由网关在每轮请求开始时构造）。 */
data class RuleContext(
    val promptTokens: Long = 0L,
    val hasImage: Boolean = false,
    val compactRequest: Boolean = false,
    val folder: String = "",
    val nowMillis: Long = System.currentTimeMillis(),
)

/** 全部路由组的持久化配置。 */
data class RoutingGroupSettings(val groups: List<RoutingGroup> = emptyList()) {

    fun find(id: String): RoutingGroup? = groups.firstOrNull { it.id == id && it.enabled }

    fun list(): List<RoutingGroup> = groups

    /** 新增或替换同名组。 */
    fun upsert(group: RoutingGroup): RoutingGroupSettings =
        copy(groups = groups.filterNot { it.id == group.id } + group)

    fun remove(id: String): RoutingGroupSettings = copy(groups = groups.filterNot { it.id == id })

    fun toJson(): JsonObject = JsonObject().apply {
        add("groups", JsonArray().apply { groups.forEach { add(it.toJson()) } })
    }

    companion object {
        const val STORE_KEY = "settings/routing_groups.json"
        const val PREFIX = "group/"

        fun fromJson(raw: String?): RoutingGroupSettings {
            if (raw.isNullOrBlank()) return RoutingGroupSettings()
            val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return RoutingGroupSettings()
            val groups = root.arrayOrNull("groups")
                ?.mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject?.let(RoutingGroup::fromJson) }
                ?: emptyList()
            return RoutingGroupSettings(groups)
        }

        /**
         * 从模型名识别组引用：`group/<id>`，或直接写的组 id（仅当确实存在同名组）。
         *
         * 允许裸 id 是为了客户端顺手：有些工具不接受带斜杠的模型名。
         * 但仍以「存在同名组」为前提，不能把任意模型名误判成组。
         */
        fun groupIdOf(requestedModel: String, settings: RoutingGroupSettings): String? {
            val requested = requestedModel.trim()
            if (requested.isEmpty()) return null
            if (requested.startsWith(PREFIX)) {
                val id = requested.removePrefix(PREFIX).trim()
                return id.takeIf { settings.find(it) != null }
            }
            return requested.takeIf { settings.find(it) != null }
        }
    }
}

/**
 * 把组的成员按「规则优先 + 组策略」排成有序候选列表。
 *
 * 规则命中的成员排最前（按规则声明顺序），其余成员按 [GroupStrategy] 排在其后。
 * 调用方负责不要在一轮中途重复调用（规则只在一轮开始时决策）。
 */
object RoutingGroupResolver {

    fun orderMembers(
        group: RoutingGroup,
        context: RuleContext,
        random: Random = Random.Default,
    ): List<GroupMember> {
        val deduped = dedupe(group.members)
        if (deduped.isEmpty()) return emptyList()
        val ordered = when (group.strategy) {
            GroupStrategy.ORDER -> orderByPriorityThenWeight(deduped, random)
            GroupStrategy.WEIGHT -> weightedShuffle(deduped, random)
        }
        // 停用的组仍可能被 resolveCandidateRoutes 拿去排候选（由调用方决定是否采用），
        // 但它的规则不该生效——停用就是「别按我的规则改变行为」。
        if (!group.enabled) return ordered
        val matched = LinkedHashSet<String>()
        val head = ArrayList<GroupMember>()
        for (rule in group.rules) {
            if (!matches(rule, context)) continue
            val member = deduped.firstOrNull {
                it.providerId == rule.targetProviderId && it.model == rule.targetModel
            } ?: continue
            if (matched.add(member.providerId + "\u0000" + member.model)) head.add(member)
        }
        if (head.isEmpty()) return ordered
        val headKeys = matched
        return head + ordered.filter { (it.providerId + "\u0000" + it.model) !in headKeys }
    }

    /** 单条规则是否命中：所有已设置的条件必须同时满足。 */
    fun matches(rule: GroupRule, context: RuleContext): Boolean {
        if (rule.minPromptTokens > 0 && context.promptTokens < rule.minPromptTokens) return false
        if (rule.requireImage && !context.hasImage) return false
        if (rule.compactOnly && !context.compactRequest) return false
        if (rule.folders.isNotEmpty() && !matchesFolder(rule.folders, context.folder)) return false
        if (rule.daysOfWeek.isNotEmpty()) {
            val day = Instant.ofEpochMilli(context.nowMillis).atZone(ZoneId.systemDefault()).dayOfWeek.value
            if (day !in rule.daysOfWeek) return false
        }
        if (rule.startMinuteOfDay >= 0 && rule.endMinuteOfDay >= 0) {
            val minute = minuteOfDay(context.nowMillis)
            val start = rule.startMinuteOfDay.coerceIn(0, 1439)
            val end = rule.endMinuteOfDay.coerceIn(0, 1439)
            // 跨午夜（如 22:00-02:00）按环形区间判断，否则这类规则永远不会命中
            val inWindow = if (start <= end) minute in start..end else minute >= start || minute <= end
            if (!inWindow) return false
        }
        return true
    }

    /** 从 OpenAI 请求体估算「提示词 token 数」；按字符数 / 4 向上取整，够用于阈值判断。 */
    fun estimatePromptTokens(openAiBody: String): Long {
        val body = parse(openAiBody) ?: return 1L
        val messages = body.arrayOrNull("messages") ?: return 1L
        var chars = 0L
        for (element in messages) {
            val message = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            chars += contentChars(message.get("content"))
        }
        // 向上取整而不是向下：阈值是「够长才交给它」，低估会让本该转走的长对话留在原地。
        return ((chars + 3L) / 4L).coerceAtLeast(1L)
    }

    /** 请求体是否包含图片内容。 */
    fun bodyHasImage(openAiBody: String): Boolean {
        val body = parse(openAiBody) ?: return false
        val messages = body.arrayOrNull("messages") ?: return false
        for (element in messages) {
            val parts = element.takeIf { it.isJsonObject }?.asJsonObject?.get("content") ?: continue
            if (!parts.isJsonArray) continue
            for (part in parts.asJsonArray) {
                val type = part.takeIf { it.isJsonObject }?.asJsonObject?.stringOrNull("type").orEmpty()
                if (type == "image_url" || type == "image" || type == "input_image") return true
            }
        }
        return false
    }

    /** 是否是上下文压缩请求（Claude Code 的 /compact 与自动压缩会带固定特征文本）。 */
    fun isCompactRequest(openAiBody: String): Boolean {
        val body = parse(openAiBody) ?: return false
        val messages = body.arrayOrNull("messages") ?: return false
        for (element in messages) {
            val message = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val role = message.stringOrNull("role").orEmpty()
            if (role != "system" && role != "developer" && role != "user") continue
            val text = contentText(message.get("content")).lowercase()
            if (COMPACT_MARKERS.any { text.contains(it) }) return true
        }
        return false
    }

    /** 从请求体里取工作目录提示；取不到返回空串。 */
    fun detectFolder(openAiBody: String): String {
        val body = parse(openAiBody) ?: return ""
        // 有些客户端把工作目录放在顶层自定义字段，不只看消息体
        for (key in listOf("cwd", "working_directory", "workingDirectory")) {
            val value = body.stringOrNull(key).orEmpty().trim()
            if (value.isNotEmpty()) return value
        }
        val messages = body.arrayOrNull("messages") ?: return ""
        for (element in messages) {
            val text = contentText(element.takeIf { it.isJsonObject }?.asJsonObject?.get("content"))
            val found = FOLDER_PATTERNS.firstNotNullOfOrNull { regex ->
                regex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            }
            if (found != null) return found
        }
        return ""
    }

    /** 路径前缀匹配到「路径段边界」：`~/work/acme` 命中 `~/work/acme/web`，不命中 `~/work/acme-old`。 */
    private fun matchesFolder(patterns: List<String>, folder: String): Boolean {
        if (folder.isEmpty()) return false
        val normalized = normalizePath(folder)
        // Agent 报的是绝对路径（`/home/xibai/work/acme`），用户配的是 `~/work/acme`：
        // 两边都剥掉 home 前缀后归一化成相对路径再比，否则这种最常见的情形永远不命中。
        val relativeFolder = stripHomePrefix(normalized)
        return patterns.any { pattern ->
            val p = normalizePath(pattern)
            if (p.isEmpty()) return@any false
            val relativePattern = stripHomePrefix(p)
            isPathPrefix(normalized, p) ||
                isPathPrefix(relativeFolder, p) ||
                isPathPrefix(normalized, relativePattern) ||
                isPathPrefix(relativeFolder, relativePattern)
        }
    }

    /** 按路径段边界判断前缀：`a/b` 命中 `a/b/c`，不命中 `a/bc`。 */
    private fun isPathPrefix(path: String, prefix: String): Boolean {
        if (prefix.isEmpty() || path.isEmpty()) return false
        if (path == prefix) return true
        val trimmed = prefix.trimEnd('/')
        if (trimmed.isEmpty()) return false
        return path.startsWith("$trimmed/")
    }

    /** 剥掉 `~/`、`/home/<user>/`、`/users/<user>/`、`/root/` 这些与具体机器相关的段。 */
    private fun stripHomePrefix(path: String): String {
        if (path.startsWith("~/")) return path.removePrefix("~/")
        HOME_PREFIX.find(path)?.let { return path.removePrefix(it.value) }
        return path
    }

    private fun normalizePath(path: String): String =
        path.trim().replace('\\', '/').trimEnd('/').lowercase()

    private fun minuteOfDay(nowMillis: Long): Int {
        val time = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalTime()
        return time.hour * 60 + time.minute
    }

    private fun orderByPriorityThenWeight(members: List<GroupMember>, random: Random): List<GroupMember> {
        val out = ArrayList<GroupMember>(members.size)
        for ((_, group) in members.groupBy { it.priority }.toSortedMap(compareByDescending { it })) {
            out.addAll(weightedShuffle(group, random))
        }
        return out
    }

    /** 加权洗牌（不放回抽样）：每轮按剩余项的权重抽一个，与 `failover.weightedOrder` 同口径。 */
    private fun weightedShuffle(items: List<GroupMember>, random: Random): List<GroupMember> {
        val remaining = items.toMutableList()
        val out = ArrayList<GroupMember>(items.size)
        while (remaining.isNotEmpty()) {
            val total = remaining.sumOf { it.weight.coerceAtLeast(0).toLong() }
            if (total <= 0L) {
                // 权重全为 0：保持原相对顺序，避免死循环（不能把成员静默丢掉）
                out.addAll(remaining)
                break
            }
            var target = random.nextLong(total)
            var index = 0
            for ((i, item) in remaining.withIndex()) {
                target -= item.weight.coerceAtLeast(0)
                if (target < 0) {
                    index = i
                    break
                }
            }
            out.add(remaining.removeAt(index))
        }
        return out
    }

    private fun dedupe(members: List<GroupMember>): List<GroupMember> {
        val seen = HashSet<String>()
        return members.filter { seen.add(it.providerId + "\u0000" + it.model) }
    }

    private fun parse(body: String): JsonObject? =
        runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()

    private fun contentChars(content: com.google.gson.JsonElement?): Long {
        if (content == null) return 0L
        if (content.isJsonPrimitive) return content.asString.length.toLong()
        if (!content.isJsonArray) return 0L
        var total = 0L
        for (part in content.asJsonArray) {
            val obj = part.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            obj.stringOrNull("text")?.let { total += it.length }
        }
        return total
    }

    private fun contentText(content: com.google.gson.JsonElement?): String {
        if (content == null) return ""
        if (content.isJsonPrimitive) return runCatching { content.asString }.getOrDefault("")
        if (!content.isJsonArray) return ""
        return content.asJsonArray.mapNotNull { part ->
            part.takeIf { it.isJsonObject }?.asJsonObject?.stringOrNull("text")
        }.joinToString("")
    }

    private val COMPACT_MARKERS = listOf(
        "/compact",
        "summarize the conversation",
        "condense the conversation",
        "提供一份详细的对话摘要",
        "压缩上下文",
        "总结以上对话",
    )

    /** 与具体机器相关的 home 前缀：规则里写 `~/x`、Agent 报 `/home/<user>/x`，两者要对得上。 */
    private val HOME_PREFIX = Regex("""^/(?:home|users)/[^/]+/|^/root/""", RegexOption.IGNORE_CASE)

    private val FOLDER_PATTERNS = listOf(
        // `<cwd>/root/proj</cwd>`：Codex 会把工作目录写成 XML 样式的标签
        Regex("""<(?:cwd|working_directory)>(.*?)</(?:cwd|working_directory)>""", RegexOption.IGNORE_CASE),
        Regex("""(?:cwd|working directory|工作目录|当前目录|Primarily working in)\s*[:：]?\s*[`"']?([~/][^\s`"',)<]*)""", RegexOption.IGNORE_CASE),
        Regex("""[`"'](/(?:home|Users|root)/[^\s`"']+)[`"']"""),
    )
}
