package dev.aigw.core.provider.trae

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.ProviderAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 设备指纹相关：上游要求 `x-device-id` 与账号注册设备一致，否则 UG 接口以 9074 拒绝签到，
 * 所以登录时用过的指纹必须能被找回，也要允许用户手动修正。
 */
class TraeProviderTest {

    private fun provider(store: InMemoryKeyValueStore = InMemoryKeyValueStore()) = TraeProvider(
        store = store,
        version = { TraeVersion() },
        defaultModel = { "glm-5.2" },
    )

    private fun traeAccount(
        uid: String = "u1",
        machineId: String = "m".repeat(64),
        deviceId: String = "1700000000000000",
    ) = TraeAccount(
        uid = uid,
        accessToken = "at",
        refreshToken = "rt",
        expiresAt = 0,
        nickname = "昵称",
        enterpriseId = "",
        machineId = machineId,
        deviceId = deviceId,
    )

    private fun account(
        machineId: String = "m".repeat(64),
        deviceId: String = "1700000000000000",
    ) = ProviderAccount("trae", "u1", "昵称", traeAccount(machineId = machineId, deviceId = deviceId).toJson().toString())

    @Test
    fun `设备 ID 是纯数字的十五位，机器码是六十四位 hex`() {
        repeat(50) {
            val deviceId = TraeLogin.randomDeviceId()
            assertTrue(deviceId.all { it.isDigit() }, "device id 必须纯数字：$deviceId")
            assertEquals(15, deviceId.length, "device id 长度为 15：$deviceId")
            assertTrue(TraeLogin.looksLikeDeviceId(deviceId))

            val machineId = TraeLogin.newMachineId()
            assertEquals(64, machineId.length)
            assertTrue(machineId.all { it in "0123456789abcdef" })
        }
    }

    @Test
    fun `不符合官方格式的指纹会被就地修正`() {
        // 模拟 v0.1.4 留下的错误值：32 位 hex
        val fixed = ensureDeviceIds(traeAccount(machineId = "a".repeat(32), deviceId = "b".repeat(32)))
        assertTrue(TraeLogin.looksLikeDeviceId(fixed.deviceId), "应为数字格式：${fixed.deviceId}")
        assertEquals(64, fixed.machineId.length)
    }

    @Test
    fun `已是官方格式的指纹不会被改动`() {
        val numeric = "1739906528339770"
        val kept = ensureDeviceIds(traeAccount(machineId = "c".repeat(64), deviceId = numeric))
        assertEquals(numeric, kept.deviceId)
        assertEquals("c".repeat(64), kept.machineId)
    }

    @Test
    fun `手动填入纯数字设备 ID`() {
        val provider = provider()
        val original = account()
        assertEquals("", provider.validateDeviceId("  1700000000000000  "))

        val secret = provider.withDeviceId(original, "  1700000000000000  ")
        val updated = TraeAccount.parse(secret)
        assertEquals("1700000000000000", updated.deviceId)
        assertEquals("m".repeat(64), updated.machineId, "修正设备 ID 时应保留机器码")
    }

    @Test
    fun `拒绝非纯数字的设备 ID`() {
        val provider = provider()
        val error = provider.validateDeviceId("b".repeat(64))
        assertTrue(error.contains("纯数字"), error)
        assertTrue(provider.validateDeviceId("").isNotEmpty(), "空值应被拒绝")
        assertTrue(provider.validateDeviceId("   ").isNotEmpty())
    }

    @Test
    fun `重新生成会同时换掉机器码与设备 ID`() {
        val provider = provider()
        val updated = TraeAccount.parse(provider.regenerateDeviceIds(account()))
        assertTrue(TraeLogin.looksLikeDeviceId(updated.deviceId))
        assertEquals(64, updated.machineId.length)
    }

    @Test
    fun `登录时生成的设备指纹会落盘，供回调反查`() {
        val store = InMemoryKeyValueStore()
        val provider = provider(store)
        val ticket = provider.beginWebLogin("http://127.0.0.1:8790/authorize")

        val traceId = ticket.loginUrl.substringAfter("login_trace_id=").substringBefore('&')
        assertTrue(traceId.isNotEmpty(), "登录链接里应带上 login_trace_id")
        assertTrue(
            store.keys("login/pending/").contains("login/pending/trae/$traceId"),
            "应按 trace id 落盘设备指纹，进程被杀后回调仍能找到",
        )

        val saved = store.read("login/pending/trae/$traceId").orEmpty()
        assertTrue(saved.contains("\"machineId\""))
        assertTrue(saved.contains("\"deviceId\""))
    }

    /**
     * 回归：登录设备指纹暂存曾经是只增不减的 map，长期使用会一直累积。
     * 现已改成限容 LinkedHashMap。
     */
    @Test
    fun `反复登录不会让设备指纹暂存无限增长`() {
        val provider = provider()
        repeat(200) { provider.beginWebLogin("http://127.0.0.1:8790/authorize") }

        val field = TraeProvider::class.java.getDeclaredField("pending")
        field.isAccessible = true
        val size = (field.get(provider) as Map<*, *>).size
        assertTrue(size <= 40, "设备指纹暂存应被限容，实际 $size 条")
    }
}
