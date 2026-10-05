package dev.aigw.core.provider.minimax

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Agent 聊天接口（`/matrix/api/v1/chat/send_msg`）。
 *
 * 上游接口只收**单条文本**：把 OpenAI 多轮消息按 `role:content` 合并成一段、
 * 末尾补 `assistant:` 引导续写（对齐参考实现的 messagesPrepare，多轮语义靠文本拼接保留）。
 * `chat_type` 1=Lightning / 0=Pro；每次请求新建会话，流结束后由调用方负责删除。
 */
class MiniMaxChatClient(private val apiBase: String = MiniMaxConstants.API_BASE) {

    fun openStream(
        account: MiniMaxAccount,
        openAiBody: String,
        chatType: Long,
        nowMillis: Long,
    ): MiniMaxStreamCall {
        val payload = JsonObject().apply {
            addProperty("msg_type", 1)
            addProperty("text", mergeMessages(openAiBody))
            addProperty("chat_type", chatType)
            add("attachments", JsonArray())
            add("selected_mcp_tools", JsonArray())
            add("backend_config", JsonObject())
            add("sub_agent_ids", JsonArray())
        }.toString()
        return MiniMaxHttp.openStream(apiBase, account, MiniMaxConstants.PATH_CHAT_SEND, payload, nowMillis)
    }

    /**
     * OpenAI messages → 单条 Agent 文本。
     *
     * content 数组（多模态块）只取 text 部分——上游文本通道不收图片，非文本块静默丢弃。
     */
    internal fun mergeMessages(openAiBody: String): String {
        val messages = runCatching { JsonParser.parseString(openAiBody).asJsonObject.get("messages")?.asJsonArray }
            .getOrNull() ?: JsonArray()
        val text = StringBuilder()
        for (element in messages) {
            val message = element as? JsonObject ?: continue
            val role = message.get("role")?.asString?.takeIf { it.isNotEmpty() } ?: "user"
            text.append(role).append(':').append(contentOf(message)).append('\n')
        }
        text.append("assistant:").append('\n')
        // 图片/附件 markdown 会诱发幻觉，参考实现明确移除
        return imageMarkdown.replace(text.toString(), "")
    }

    private fun contentOf(message: JsonObject): String {
        val content = message.get("content") ?: return ""
        if (content.isJsonPrimitive) return content.asString
        if (content.isJsonArray) {
            return content.asJsonArray.joinToString("") { part ->
                (part as? JsonObject)?.get("text")?.asString.orEmpty()
            }
        }
        return ""
    }

    private companion object {
        val imageMarkdown = Regex("!\\[[^\\]]*]\\([^)]*\\)")
    }
}
