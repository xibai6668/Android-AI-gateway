package dev.aigw.core.gateway

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64

/** 代理页里一个可勾选「是否走代理」的路由（普通供应商或其某个区域）。 */
data class ProxyTarget(
    /** 与 [ProxySettings.proxiedRoutes] 一致的键：`providerId` 或 `providerId/region`。 */
    val routeKey: String,
    val providerId: String,
    val providerName: String,
    /** 区域型供应商的区域 id（cn/global）；null 表示该供应商无区域概念。 */
    val region: String?,
    /** 该路由会出站访问的域名后缀，测试时用来展示「会拦截哪些域名」。 */
    val hosts: List<String>,
)

enum class ProxyProbeStatus {
    /** 代理握手成功，上游可连通。 */
    OK,

    /** 连接超时：地址大概率填错或代理没在运行。 */
    TIMEOUT,

    /** 无法连接：拒绝连接、域名解析失败等。 */
    UNREACHABLE,
}

data class ProxyProbeResult(
    val status: ProxyProbeStatus,
    /** 握手耗时（毫秒）；仅 [ProxyProbeStatus.OK] 时有效。 */
    val latencyMs: Long = 0,
    /** 失败原因，直接展示给用户。 */
    val message: String = "",
)

/**
 * 探测一套代理是否能真正把请求送出去。
 *
 * 只测「TCP 能连上代理端口」是不够的：端口通但代理没授权、上游被墙、SOCKS 配置错，
 * 都会让真正的请求失败。所以按协议各做一次到 [DEFAULT_TARGET_HOST] 的握手，
 * 以握手成功为「可连通」。
 */
fun probeProxy(
    profile: ProxyProfile,
    timeoutMs: Int = 8000,
    targetHost: String = DEFAULT_TARGET_HOST,
    targetPort: Int = DEFAULT_TARGET_PORT,
): ProxyProbeResult {
    if (!profile.usable) {
        return ProxyProbeResult(ProxyProbeStatus.UNREACHABLE, message = "代理地址或端口未填写完整")
    }
    val started = System.nanoTime()
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(profile.host, profile.port), timeoutMs)
            socket.soTimeout = timeoutMs
            val ok = when (profile.type) {
                ProxyType.HTTP -> httpTunnel(socket, profile, targetHost, targetPort)
                ProxyType.SOCKS -> socks5Tunnel(socket, profile, targetHost, targetPort)
            }
            val latency = (System.nanoTime() - started) / 1_000_000
            if (ok) {
                ProxyProbeResult(ProxyProbeStatus.OK, latencyMs = latency)
            } else {
                ProxyProbeResult(ProxyProbeStatus.UNREACHABLE, message = "代理拒绝了到 $targetHost 的连接")
            }
        }
    } catch (e: SocketTimeoutException) {
        ProxyProbeResult(ProxyProbeStatus.TIMEOUT, message = "连接超时：${profile.host}:${profile.port} 无响应")
    } catch (e: UnknownHostException) {
        ProxyProbeResult(ProxyProbeStatus.UNREACHABLE, message = "无法解析代理地址 ${profile.host}")
    } catch (e: ConnectException) {
        ProxyProbeResult(ProxyProbeStatus.UNREACHABLE, message = "无法连接 ${profile.host}:${profile.port}：${e.message.orEmpty()}")
    } catch (e: NoRouteToHostException) {
        ProxyProbeResult(ProxyProbeStatus.UNREACHABLE, message = "无法连接 ${profile.host}:${profile.port}")
    } catch (e: IOException) {
        ProxyProbeResult(ProxyProbeStatus.UNREACHABLE, message = e.message ?: "连接失败")
    }
}

private const val DEFAULT_TARGET_HOST = "www.gstatic.com"
private const val DEFAULT_TARGET_PORT = 443

/** HTTP 代理：发 CONNECT 隧道请求，代理回 200 即视为可连通。 */
private fun httpTunnel(socket: Socket, profile: ProxyProfile, host: String, port: Int): Boolean {
    val authority = "$host:$port"
    val request = buildString {
        append("CONNECT $authority HTTP/1.1\r\n")
        append("Host: $authority\r\n")
        if (profile.username.isNotEmpty()) {
            val token = Base64.getEncoder()
                .encodeToString("${profile.username}:${profile.password}".toByteArray())
            append("Proxy-Authorization: Basic $token\r\n")
        }
        append("Connection: close\r\n\r\n")
    }
    socket.getOutputStream().apply {
        write(request.toByteArray())
        flush()
    }
    val statusLine = readLine(socket) ?: return false
    val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: return false
    return code in 200..299
}

/** SOCKS5：握手（必要时带用户名密码认证）后发 CONNECT，回复码 0 即成功。 */
private fun socks5Tunnel(socket: Socket, profile: ProxyProfile, host: String, port: Int): Boolean {
    val out = socket.getOutputStream()
    val input = socket.getInputStream()
    val withAuth = profile.username.isNotEmpty()
    out.write(if (withAuth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
    out.flush()
    val version = input.read()
    val method = input.read()
    if (version != 5 || method < 0) return false
    if (method == 2) {
        val user = profile.username.toByteArray()
        val pass = profile.password.toByteArray()
        val auth = ByteArray(3 + user.size + pass.size)
        auth[0] = 1
        auth[1] = user.size.toByte()
        user.copyInto(auth, 2)
        auth[2 + user.size] = pass.size.toByte()
        pass.copyInto(auth, 3 + user.size)
        out.write(auth)
        out.flush()
        if (input.read() != 1 || input.read() != 0) return false
    } else if (method != 0) {
        return false
    }
    val hostBytes = host.toByteArray()
    val request = ByteArray(5 + hostBytes.size + 2)
    request[0] = 5
    request[1] = 1 // CONNECT
    request[2] = 0
    request[3] = 3 // 域名
    request[4] = hostBytes.size.toByte()
    hostBytes.copyInto(request, 5)
    request[5 + hostBytes.size] = (port shr 8).toByte()
    request[6 + hostBytes.size] = port.toByte()
    out.write(request)
    out.flush()
    val reply = ByteArray(4)
    var read = 0
    while (read < 4) {
        val n = input.read(reply, read, 4 - read)
        if (n <= 0) return false
        read += n
    }
    return reply[0].toInt() == 5 && reply[1].toInt() == 0
}

/** 逐字节读到 CRLF 为止（CONNECT 的响应只有状态行是必要的）。 */
private fun readLine(socket: Socket): String? {
    val input = socket.getInputStream()
    val builder = StringBuilder()
    while (builder.length < 512) {
        val b = input.read()
        if (b < 0) return builder.toString().ifEmpty { null }
        if (b == '\n'.code) break
        if (b != '\r'.code) builder.append(b.toChar())
    }
    return builder.toString().ifEmpty { null }
}
