package dev.aigw.core.provider.chatgpt

/**
 * ChatGPT（Sign in with ChatGPT）协议常量。
 *
 * 全部是 openai/codex 官方 CLI 已在用的公开注册值（事实来源，非复制的第三方实现）：
 * client_id 是 Codex CLI 的 public client（无 client_secret），回调固定 localhost:1455。
 * 改这里的任何值都会让登录或对话直接失败，除非上游同步改了注册值。
 */
internal object ChatGPTConstants {
    const val ID = "chatgpt"
    const val DISPLAY_NAME = "ChatGPT"

    /** Codex CLI 的公开 OAuth 客户端（public client，PKCE，无 secret）。 */
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"

    /** 上游用它识别流量来源；Codex 系请求必须带这个值。 */
    const val ORIGINATOR = "codex_cli_rs"

    /** 模型目录的 client_version 参数；Codex 后端会据此裁剪模型清单。 */
    const val CLIENT_VERSION = "0.163.0"

    /**
     * 上游 User-Agent。与 [ORIGINATOR] 一样是协议侧识别值：不带它，部分端点会返回
     * 「unsupported client」。这不是伪造指纹，只是如实声明「这是一个 Codex 协议的客户端」。
     */
    const val USER_AGENT = "codex_cli_rs/$CLIENT_VERSION (Linux; Android) aigw-gateway"

    const val AUTH_ENDPOINT = "https://auth.openai.com/oauth/authorize"
    const val TOKEN_ENDPOINT = "https://auth.openai.com/oauth/token"
    const val CODEX_BASE = "https://chatgpt.com/backend-api/codex"
    const val USAGE_ENDPOINT = "https://chatgpt.com/backend-api/wham/usage"

    /** 回调端口/路径是上游注册值，**不可改**：OpenAI 会逐字符校验 redirect_uri。 */
    const val CALLBACK_PORT = 1455
    const val CALLBACK_PATH = "/auth/callback"
    const val REDIRECT_URI = "http://localhost:$CALLBACK_PORT$CALLBACK_PATH"

    const val SCOPE = "openid profile email offline_access"

    const val CONNECT_TIMEOUT_MS = 15_000

    /** 对话可能长时间不吐字（推理模型），读超时给足。 */
    const val READ_TIMEOUT_MS = 180_000

    /** 登录会话有效期：超时即作废内存里的 PKCE verifier，避免长期挂着。 */
    const val LOGIN_TTL_MS = 10 * 60_000L

    /** 凭证提前刷新窗口（与 rikkahub / codex CLI 的 5 分钟 leeway 一致）。 */
    const val REFRESH_LEEWAY_MS = 5 * 60_000L

    /** 刷新失败后的冷却，避免每个请求都去打 auth 端点。 */
    const val REFRESH_COOLDOWN_MS = 60_000L

    /** 同一账号刚刷新过的复用窗口：并发请求里只有第一个真去刷。 */
    const val REFRESH_REUSE_MS = 30_000L

    /** 模型目录缓存时长。 */
    const val MODEL_CACHE_TTL_MS = 30 * 60_000L

    /** 目录拉不到时的兜底模型名（不在目录里也能让首轮对话走通）。 */
    const val DEFAULT_MODEL = "gpt-5.1-codex"
}
