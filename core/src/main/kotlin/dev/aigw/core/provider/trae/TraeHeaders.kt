package dev.aigw.core.provider.trae

import java.net.HttpURLConnection

/** 三类上游请求所需的头部，规则来自逆向实测，缺一项即可能被拒。 */
object TraeHeaders {
    private fun ua(v: TraeVersion) = "Trae/" + v.ideVersion

    /** llm_utils_chat / get_detail_param 使用的 SOLO 专属头。 */
    fun solo(c: HttpURLConnection, a: TraeAccount, v: TraeVersion, stream: Boolean) {
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
        c.setRequestProperty("User-Agent", ua(v))
        c.setRequestProperty("Authorization", "Cloud-IDE-JWT " + a.accessToken)
        c.setRequestProperty("X-Cloudide-Token", a.accessToken)
        c.setRequestProperty("X-Ide-Token", a.accessToken)
        if (a.uid.isNotEmpty()) c.setRequestProperty("X-Uid", a.uid)
        c.setRequestProperty("X-App-Id", TraeConstants.APP_ID)
        c.setRequestProperty("X-App-Version", "default")
        c.setRequestProperty("X-Ide-Version", v.ideVersion)
        c.setRequestProperty("X-Ide-Version-Code", v.ideVersionCode)
        c.setRequestProperty("X-App-Version-Code", v.ideVersionCode)
        c.setRequestProperty("X-Ide-Version-Type", "stable")
        c.setRequestProperty("X-Device-Type", "windows")
        c.setRequestProperty("X-OS-Version", v.osVersion)
        c.setRequestProperty("X-Device-Brand", v.deviceBrand)
        c.setRequestProperty("Request-Traffic-Type", "prod")
        if (a.machineId.isNotEmpty()) c.setRequestProperty("X-Machine-Id", a.machineId)
        if (a.deviceId.isNotEmpty()) c.setRequestProperty("x-device-id", a.deviceId)
    }

    /**
     * 签到 / 积分查询（api.trae.cn）使用的头。
     *
     * 这里的设备指纹头是必须的：UG 接口会校验设备指纹，缺任一项上游会以 9074
     * 「当前参与用户太多」为由拒绝签到（实测结论，见 rockswang/wild-work 的逆向文档与 issue）。
     */
    fun ug(c: HttpURLConnection, a: TraeAccount, v: TraeVersion) {
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", ua(v))
        c.setRequestProperty("Authorization", "Cloud-IDE-JWT " + a.accessToken)
        c.setRequestProperty("X-User-Region", "CN")
        if (a.uid.isNotEmpty()) c.setRequestProperty("X-Uid", a.uid)
        c.setRequestProperty("x-device-brand", v.deviceBrand)
        c.setRequestProperty("x-device-type", "windows")
        c.setRequestProperty("x-os-version", v.osVersion)
        c.setRequestProperty("x-app-version", v.ideVersion)
        if (a.deviceId.isNotEmpty()) c.setRequestProperty("x-device-id", a.deviceId)
    }

    /** ExchangeToken / GetUserInfo 只校验 UA，无签名。 */
    fun oauth(c: HttpURLConnection, v: TraeVersion) {
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", ua(v))
    }
}
