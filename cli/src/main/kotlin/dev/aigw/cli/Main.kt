package dev.aigw.cli

import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.gateway.GatewaySettings
import dev.aigw.core.gateway.LoginOutcome
import dev.aigw.core.store.FileKeyValueStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 命令行入口。用途是在本机（容器内）直接起网关做端到端联调，
 * 以及不打开 App 也能导入账号 / 签到 / 看用量。
 *
 * 用法：
 *   aigw serve [--port 8790] [--data DIR]
 *   aigw providers
 *   aigw accounts [--provider ID]
 *   aigw credits [--provider ID]
 *   aigw checkin --provider ID [--uid UID]
 *   aigw login --provider ID <回调链接或凭证 JSON>
 *   aigw sms --provider ID --phone P [--msgid M --code C]
 *   aigw models
 *   aigw usage
 *   aigw logs
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "help"
    val rest = args.drop(1)
    val dataDir = File(option(rest, "--data") ?: defaultDataDir())
    if (!dataDir.exists()) dataDir.mkdirs()

    val engine = GatewayEngine(FileKeyValueStore(dataDir), onLog = { println("[log] $it") })

    when (command) {
        "serve" -> serve(engine, rest)
        "providers" -> providers(engine)
        "accounts" -> accounts(engine, rest)
        "credits" -> credits(engine, rest)
        "checkin" -> checkin(engine, rest)
        "login" -> login(engine, rest)
        "browser-login" -> browserLogin(engine, rest)
        "sms" -> sms(engine, rest)
        "models" -> models(engine)
        "usage" -> usage(engine)
        "logs" -> logs(engine)
        "help", "--help", "-h" -> printHelp()
        else -> {
            System.err.println("未知命令：$command")
            printHelp()
            kotlin.system.exitProcess(2)
        }
    }
}

private fun serve(engine: GatewayEngine, rest: List<String>) {
    option(rest, "--port")?.toIntOrNull()?.let { port ->
        engine.updateSettings(engine.settings().copy(port = port))
    }
    engine.start()
    val settings: GatewaySettings = engine.settings()
    println("网关已启动：http://127.0.0.1:${settings.port}/v1")
    println("供应商数：${engine.providers().size}，账号数：${engine.pool.size()}，无 Key 调用：${settings.allowNoKey}")
    println("按 Ctrl+C 退出。")
    val latch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread {
        engine.stop()
        latch.countDown()
    })
    latch.await()
}

private fun providers(engine: GatewayEngine) {
    for (info in engine.providers()) {
        val caps = buildString {
            if (info.capabilities.isNotEmpty()) append(" [${info.capabilities.joinToString(",")}]")
        }
        println(
            "${info.id.padEnd(18)} ${info.displayName.padEnd(14)} 登录=${info.authKind} " +
                "账号=${info.accountCount} 可用=${info.usableCount}" +
                (if (info.enabled) "" else " [已停用]") + caps,
        )
    }
}

private fun accounts(engine: GatewayEngine, rest: List<String>) {
    val providerId = option(rest, "--provider")
    val statuses = engine.accounts(providerId)
    if (statuses.isEmpty()) {
        println("账号池为空。用 `login --provider <id> <凭证>` 导入账号。")
        return
    }
    for (status in statuses) {
        val flags = buildString {
            if (status.disabled) append(" [凭证失效]")
            else if (!status.enabled) append(" [已停用]")
            else if (status.cooling) append(" [冷却至 ${java.util.Date(status.untilMillis)}]")
        }
        val credits = if (status.creditsKnown) status.credits.toString() else "—"
        println(
            "${status.providerId}/${status.nickname.ifEmpty { status.uid }}  " +
                "额度=$credits  uid=${status.uid}$flags",
        )
    }
    val summary = engine.pool.summary(providerId)
    println(
        "合计 ${summary.total} 个账号，可用 ${summary.usable}，冷却 ${summary.cooling}，" +
            "停用 ${summary.disabled + summary.disabledByUser}，已知额度合计 ${summary.totalCredits}",
    )
}

private fun credits(engine: GatewayEngine, rest: List<String>) {
    val providerId = option(rest, "--provider")
    for (result in engine.refreshAllCredits(providerId)) {
        if (result.error.isEmpty()) {
            println("${result.providerId}/${result.uid}  额度=${result.balance}")
        } else {
            println("${result.providerId}/${result.uid}  刷新失败：${result.error}")
        }
    }
}

private fun checkin(engine: GatewayEngine, rest: List<String>) {
    val providerId = option(rest, "--provider") ?: "trae"
    val uid = option(rest, "--uid")
    val targets = if (uid != null) listOf(uid) else engine.accounts(providerId).map { it.uid }
    for (target in targets) {
        val result = engine.performAction(providerId, target, "checkin")
        println("$providerId/$target  ${if (result.ok) result.message else "失败：${result.message}"}")
    }
}

private fun browserLogin(engine: GatewayEngine, rest: List<String>) {
    val providerId = option(rest, "--provider") ?: "trae"
    val ticket = engine.beginBrowserLogin(providerId)
    println("请在浏览器打开：${ticket.loginUrl}")
    if (ticket.pollState.isNotEmpty()) {
        println("这是轮询型登录（state=${ticket.pollState}），完成登录后由调用方轮询拿 token。")
        return
    }
    println("已监听本地回调，等待浏览器跳回（最长 5 分钟）…")
    val latch = CountDownLatch(1)
    engine.onAccountsChanged = { latch.countDown() }
    val done = latch.await(5, TimeUnit.MINUTES)
    engine.stopCallbackServer()
    if (done) println("登录成功，账号已加入账号池") else System.err.println("等待回调超时")
}

private fun login(engine: GatewayEngine, rest: List<String>) {
    val providerId = option(rest, "--provider") ?: "trae"
    val payload = positional(rest).firstOrNull() ?: error("请给回调链接或凭证")
    // 回调链接（网页登录）以 http 开头；其余按凭证导入（Trae 的 JSON、自定义供应商的裸 Key）
    val outcome = if (payload.startsWith("http", ignoreCase = true)) {
        engine.completeWebLogin(providerId, payload)
    } else {
        engine.importAccountJson(providerId, payload)
    }
    report(outcome)
}

private fun sms(engine: GatewayEngine, rest: List<String>) {
    val providerId = option(rest, "--provider") ?: "loomy"
    val phone = option(rest, "--phone") ?: error("请给 --phone")
    val code = option(rest, "--code")
    if (code == null) {
        val msgid = engine.sendSmsCode(providerId, phone)
        println("验证码已发送，msgid=$msgid（再用 --code 与 --msgid 完成登录）")
        return
    }
    val msgid = option(rest, "--msgid") ?: error("请给 --msgid")
    report(engine.completeSmsLogin(providerId, phone, code, msgid))
}

private fun report(outcome: LoginOutcome) {
    if (outcome.ok) {
        println("登录成功：${outcome.nickname.ifEmpty { outcome.uid }}（${outcome.uid}）")
    } else {
        System.err.println("登录失败：${outcome.error}")
        kotlin.system.exitProcess(1)
    }
}

private fun models(engine: GatewayEngine) {
    for (model in engine.models()) {
        println("${model.fullId}\t${model.model.name}")
    }
}

private fun usage(engine: GatewayEngine) {
    val stats = engine.callLogStore.stats(0)
    println("累计请求 ${stats.requests}，成功 ${stats.success}，失败 ${stats.failed}，tokens ${stats.totalTokens}")
    for (record in engine.callLogStore.list().take(20)) {
        println(
            "${java.util.Date(record.startedAtMillis)}  ${record.providerId}/${record.model}  " +
                "${record.status}  ${record.durationMillis}ms  ${record.accountNickname}",
        )
    }
}

private fun logs(engine: GatewayEngine) {
    for (line in engine.requestLog.lines().take(60)) println(line.render())
}

private fun option(args: List<String>, name: String): String? {
    val index = args.indexOf(name)
    if (index < 0) return null
    return args.getOrNull(index + 1)
}

/**
 * 取出位置参数，跳过 `--选项 值`。
 * 否则 `tasks --data DIR` 会把 `--data` 当成账号名。
 */
private fun positional(args: List<String>): List<String> {
    val result = ArrayList<String>()
    var i = 0
    while (i < args.size) {
        val current = args[i]
        if (current.startsWith("--")) {
            i += if (i + 1 < args.size && !args[i + 1].startsWith("--")) 2 else 1
        } else {
            result.add(current)
            i += 1
        }
    }
    return result
}

private fun defaultDataDir(): String =
    System.getenv("AIGW_DATA") ?: (System.getProperty("user.home") + "/.aigw")

private fun printHelp() {
    println(
        """
        用法：aigw <命令> [选项]

          serve                   启动网关（--port 8790，--data DIR）
          providers               列出全部供应商
          accounts                列出账号与状态（--provider ID）
          credits                 刷新账号额度（--provider ID）
          checkin                 签到（--provider ID，--uid UID）
          login                   导入账号：回调链接或凭证 JSON（--provider ID）
          browser-login           用系统浏览器登录（--provider ID，打印登录链接并等待回调）
          sms                     短信登录：--provider ID --phone P [--msgid M --code C]
          models                  列出全部供应商的模型（id 带 provider 前缀）
          usage                   调用统计
          logs                    请求日志

        账号与设置存放于 --data 指定目录（默认 ~/.aigw）。
        """.trimIndent(),
    )
}
