package dev.aigw.core.gateway

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 有界请求执行器。
 *
 * 默认的 thread-per-request 无上限：慢客户端或并发洪峰能把线程数顶到内存上限，
 * 表现是整机卡死而不是报错。这里锁住「线程封顶 + 队列有界 + 满了直接拒绝」三条。
 *
 * `ClientHandler` 是 NanoHTTPD 的非静态内部类，所以测试用一个宿主子类 + 内部类来造可控 handler。
 */
class BoundedAsyncRunnerTest {

    private class Host : NanoHTTPD(8080) {
        /** 阻塞到 [gate] 放行的 handler。 */
        inner class Blocker(gate: CountDownLatch) : NanoHTTPD.ClientHandler(
            ByteArrayInputStream(ByteArray(0)),
            Socket(),
        ) {
            private val latch = gate

            override fun run() {
                latch.await(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `线程数封顶，超出的进队列`() {
        val gate = CountDownLatch(1)
        val host = Host()
        val runner = BoundedAsyncRunner(threads = 2, queueCapacity = 4)
        repeat(6) { runner.exec(host.Blocker(gate)) }
        // 线程启动是异步的，轮询等两个线程都真正跑起来
        val deadline = System.currentTimeMillis() + 2000
        while (runner.snapshot().first < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(2 to 4, runner.snapshot(), "应是 2 个在跑、4 个排队")
        gate.countDown()
        runner.closeAll()
    }

    @Test
    fun `队列也满时拒绝而不是无限排队`() {
        val gate = CountDownLatch(1)
        val rejected = ArrayList<String>()
        val host = Host()
        val runner = BoundedAsyncRunner(threads = 1, queueCapacity = 1, onReject = { rejected.add(it) })
        repeat(3) { runner.exec(host.Blocker(gate)) }
        assertEquals(1, rejected.size, rejected.toString())
        gate.countDown()
        runner.closeAll()
    }

    @Test
    fun `被拒绝的请求不会进入在途计数`() {
        val gate = CountDownLatch(1)
        val host = Host()
        val runner = BoundedAsyncRunner(threads = 1, queueCapacity = 0)
        runner.exec(host.Blocker(gate))
        val deadline = System.currentTimeMillis() + 2000
        while (runner.snapshot().first < 1 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        // 不排队：第二个请求立刻被拒
        runner.exec(host.Blocker(gate))
        assertEquals(1, runner.snapshot().first, "在途数不该把被拒的请求算进去")
        gate.countDown()
        runner.closeAll()
    }
}
