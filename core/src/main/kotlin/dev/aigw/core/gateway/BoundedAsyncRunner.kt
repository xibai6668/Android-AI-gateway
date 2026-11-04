package dev.aigw.core.gateway

import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 有界的请求执行器。
 *
 * NanoHTTPD 默认的 `DefaultAsyncRunner` 是**每个请求新起一个线程**、无上限也无排队，
 * 慢客户端或并发洪峰会把线程数直接顶到内存上限（线上表现是整机卡死而不是报错）。
 * 这里换成固定大小的线程池：线程数封顶、超出的请求进有界队列，
 * 队列也满时直接拒绝并关闭连接——宁可让客户端收到一次快速失败，也不让整个网关被拖垮。
 *
 * 注意：**不能用 `CallerRunsPolicy`**。那会让拒绝的请求回到 NanoHTTPD 的 accept 线程上同步执行，
 * 等于把唯一的接受连接循环卡死，比直接拒绝更糟。
 */
internal class BoundedAsyncRunner(
    /** 同时处理请求的最大线程数。 */
    private val threads: Int = DEFAULT_THREADS,
    /** 排队上限：0 表示不排队（线程满即拒）。 */
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val onReject: (String) -> Unit = {},
) : NanoHTTPD.AsyncRunner {

    private val counter = AtomicInteger()

    private val pool = ThreadPoolExecutor(
        threads,
        threads,
        60L,
        TimeUnit.SECONDS,
        // LinkedBlockingQueue 不接受容量 0；0 容量用 SynchronousQueue 表达「不排队」
        if (queueCapacity <= 0) java.util.concurrent.SynchronousQueue() else LinkedBlockingQueue(queueCapacity),
        ThreadFactory { runnable ->
            Thread(runnable, "aigw-http-${counter.incrementAndGet()}").apply { isDaemon = true }
        },
    )

    /** 已被接手的请求（NanoHTTPD 的 `closeAll` 要能挨个关闭）。 */
    private val running = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<NanoHTTPD.ClientHandler, Boolean>(),
    )

    /** 正在执行的请求数（不含排队）。 */
    private val executing = AtomicInteger()

    override fun exec(handler: NanoHTTPD.ClientHandler) {
        running.add(handler)
        try {
            pool.execute {
                executing.incrementAndGet()
                try {
                    handler.run()
                } finally {
                    executing.decrementAndGet()
                    running.remove(handler)
                }
            }
        } catch (_: RejectedExecutionException) {
            running.remove(handler)
            // 队列与线程都满了：关掉这条连接，客户端会立刻收到连接被重置，
            // 而不是占着一个线程慢慢等超时。
            runCatching { handler.close() }
            onReject("请求被拒绝：HTTP 线程与队列已满（${threads} 线程 / ${queueCapacity} 排队）")
        }
    }

    override fun closed(handler: NanoHTTPD.ClientHandler) {
        running.remove(handler)
    }

    override fun closeAll() {
        running.forEach { handler -> runCatching { handler.close() } }
        running.clear()
        pool.shutdownNow()
    }

    /** 当前「执行中 / 排队」数量，供状态展示与测试断言。 */
    internal fun snapshot(): Pair<Int, Int> = executing.get() to pool.queue.size

    companion object {
        /**
         * 默认线程数。
         *
         * 网关的请求线程在流式转发期间是**一直占着**的（边读上游边写下游），空闲的 keep-alive
         * 连接也会占住线程直到读到超时；线程池饱和后新请求只能排队或直接被拒，
         * 客户端感受到的就是「并发一高就全部超时」。所以按「能同时挂多少条连接」定，
         * 而不是按 CPU 核数定。
         *
         * 不直接拉到 32：每条在途流自带两枚 512K 字符缓冲，手机堆（未开 largeHeap）
         * 撑不住那么多并发流。真正的上游并发上限交给并发闸门（maxGlobal）管，
         * 线程数只需比它宽松，留出余量给 /v1/models、健康检查这类短请求。
         */
        const val DEFAULT_THREADS = 16

        /** 排队上限：突发流量最多等这么多，再多的直接拒。 */
        const val DEFAULT_QUEUE_CAPACITY = 32
    }
}
