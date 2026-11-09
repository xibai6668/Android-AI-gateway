package dev.aigw.core.provider.trae

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.ProviderAccount
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 区域推断：凭证里的 domain 既可能是区域 id（cn/global），也可能是主机名（trae.cn / www.trae.ai），
 * 缺字段时还会被 TraeAccount.parse 填成 trae.cn 默认值。因此 apiHost 必须参与判定——
 * 只认 domain 会把 apiHost 已是 api.trae.ai 的国际版凭证判成国内版，
 * 它的模型目录、对话与额度就都会打到国内 host。
 */
class TraeRegionTest {

    @Test
    fun `区域 id 优先，主机名参与兜底`() {
        assertEquals(TraeRegion.INTL, TraeRegion.infer("global", "https://api.trae.cn"))
        assertEquals(TraeRegion.CN, TraeRegion.infer("cn", "https://api.trae.ai"))
        assertEquals(TraeRegion.INTL, TraeRegion.infer("www.trae.ai", "https://api.trae.ai"))
        assertEquals(TraeRegion.CN, TraeRegion.infer("trae.cn", "https://api.trae.com.cn"))
        assertEquals(TraeRegion.INTL, TraeRegion.infer(null, "https://api.trae.ai"))
        assertEquals(TraeRegion.CN, TraeRegion.infer(null, null))
    }

    @Test
    fun `缺 domain 的凭证按 apiHost 判区域`() {
        val provider = TraeProvider(
            store = InMemoryKeyValueStore(),
            version = { TraeVersion() },
            defaultModel = { "glm-5.2" },
        )

        assertEquals(TraeRegion.INTL.id, provider.regionOf(accountWith(apiHost = "https://api.trae.ai")))
        assertEquals(TraeRegion.CN.id, provider.regionOf(accountWith(apiHost = "https://api.trae.com.cn")))
    }

    @Test
    fun `国际版 agent host 不是 OAuth host`() {
        // 探测实测：api.trae.ai 上 /api/agent/v3/llm_utils_chat 与 /api/ide/v1/get_detail_param
        // 都是 404（它是 OAuth/控制台 host），agent 集群在 -sg 域名上，错回 api.trae.ai
        // 会让国际版账号的对话与模型目录全部 404。
        assertEquals("https://trae-api-sg.mchost.guru", TraeRegion.INTL.agentHost)
        assertEquals("https://api.trae.ai", TraeRegion.INTL.oauthHost)
    }

    @Test
    fun `代理域名清单同时覆盖国内与国际版`() {
        val provider = TraeProvider(
            store = InMemoryKeyValueStore(),
            version = { TraeVersion() },
            defaultModel = { "glm-5.2" },
        )

        val hosts = provider.hosts()
        // 国际版账号请求的是另外一套 host，漏掉就会绕过代理开关
        assertEquals(true, hosts.contains(TraeRegion.INTL.agentHost.removePrefix("https://")))
        assertEquals(true, hosts.contains(TraeRegion.INTL.ugHost.removePrefix("https://")))
        assertEquals(true, hosts.contains(TraeRegion.CN.agentHost.removePrefix("https://")))
    }

    @Test
    fun `代理域名清单按区域分开返回`() {
        val provider = TraeProvider(
            store = InMemoryKeyValueStore(),
            version = { TraeVersion() },
            defaultModel = { "glm-5.2" },
        )

        val cn = provider.hosts(TraeRegion.CN.id)
        val intl = provider.hosts(TraeRegion.INTL.id)
        // 国内版不应包含国际版 host：否则「国内直连、国外走代理」无法表达
        assertEquals(false, cn.contains(TraeRegion.INTL.agentHost.removePrefix("https://")))
        assertEquals(false, intl.contains(TraeRegion.CN.agentHost.removePrefix("https://")))
    }

    @Test
    fun `国际版账号不会用国内鉴权 host`() {
        val client = TraeAuthClient(TraeVersion())
        val international = TraeAccount(
            uid = "u1",
            accessToken = "at",
            refreshToken = "rt",
            expiresAt = 0,
            nickname = "",
            enterpriseId = "",
            machineId = "m".repeat(64),
            deviceId = "1700000000000000",
            domain = TraeRegion.INTL.id,
            apiHost = TraeRegion.CN.oauthHost,
        )

        // 存量数据：旧版把国内 OAuth host 当缺省值写进了国际版凭证
        assertEquals(TraeRegion.INTL.oauthHost, client.authHost(international))
        // 凭证没带 apiHost 时按区域补
        assertEquals(TraeRegion.INTL.oauthHost, client.authHost(international.copy(apiHost = "")))
        // 用户自己配的 host 不被改写
        assertEquals(
            "https://trae-mirror.example.com",
            client.authHost(international.copy(apiHost = "https://trae-mirror.example.com")),
        )
        // 国内账号保持原样
        assertEquals(
            TraeRegion.CN.oauthHost,
            client.authHost(international.copy(domain = TraeRegion.CN.id)),
        )
    }

    private fun accountWith(apiHost: String): ProviderAccount {
        val secret = """
            {"auth":{"accessToken":"at","refreshToken":"rt","expiresAt":0,"apiHost":"$apiHost",
             "machineId":"${"m".repeat(64)}","deviceId":"1700000000000000"},
             "account":{"uid":"u1","enterpriseId":"","nickname":"昵称"}}
        """.trimIndent()
        return ProviderAccount(TraeProvider.ID, "u1", "昵称", secret)
    }
}
