package com.labsage.app.ui.perf

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 最忠实地测量 `.conflate()` 本身：当消费端（主线程 collect lambda → StateFlow set → 重组）
 * 成为瓶颈时，**collect 触发次数**能被压到多少。镜像 LabSage 的真实链路：
 *
 *   Room getMessages (~50 emits/s)
 *     → .conflate()            ← 这里
 *     → collect { _messages.value = entities.map{...} }   ← 每触发一次 = 1 次重组触发
 *     → Compose 重组
 *
 * 为什么是纯协程测试（不挂 Compose）：conflate 掐的是 **collect 的触发频率**；而 collect 里
 * 每执行一次 `_messages.value = ...` 就 1:1 触发一次 Compose 重组。所以测 collect 触发率
 * 就等于测「重组触发率」。这是对 conflate 最忠实的仪器化（见 CLAUDE.md「collect 的两段式运行时」）。
 *
 * 科学方法论：制造 unhappy path。快模拟器上消费端（一个裸 .map）跟得上，conflate 一个都不丢
 * ——测出「零效果」是**正确**的，不是「conflate 没用」。要观测 conflate 的价值，必须把消费端
 * 变成瓶颈，正如低端机主线程在流式生成中被贵重组占住：生产端按 Room 快照速率（~50/s）发，
 * 消费端用 delay 钉到 ~10/s。drop_rate = producer − consumer。
 *
 * 顺带一个对照实验（happy path）：消费端跟得上时 conflate 应当**一个都不丢**，证明它是
 * 「背压兜底 / 安全气囊」而非采样器。
 */
@RunWith(AndroidJUnit4::class)
class ConflateBackpressureTest {

    private val tag = "PerfBench"

    /**
     * @param producerPeriodMs 生产端两次 emit 间隔（20ms ⇒ ~50/s，对齐 Room 快照速率）
     * @param consumerDelayMs  消费端每处理一个 emit 的耗时（模拟主线程被重组占住）
     * @param burst            一次流式响应产生的快照数（≈ SSE chunk 数 / 5）
     */
    private suspend fun measureCollectFires(
        useConflate: Boolean,
        producerPeriodMs: Long,
        consumerDelayMs: Long,
        burst: Int
    ): Int {
        val fires = intArrayOf(0)
        // extraBufferCapacity 让生产端永不阻塞——镜像 Room 从 InvalidationTracker 线程独立发,
        // 与主线程消费者解耦（生产可跑在消费前面）。
        val source = MutableSharedFlow<Int>(extraBufferCapacity = burst + 16)
        coroutineScope {
            val collector = launch {
                val f = if (useConflate) source.conflate() else source
                f.collect {
                    fires[0]++
                    delay(consumerDelayMs) // 消费端瓶颈
                }
            }
            // 等消费者订阅完成，避免头几条 emit 丢给「无订阅者」
            while (source.subscriptionCount.value == 0) delay(2)
            delay(20)
            // 生产端：以 producerPeriodMs 间隔发 burst 条
            repeat(burst) { i ->
                source.tryEmit(i)
                delay(producerPeriodMs)
            }
            // settle：等到 fires 不再增长（消费者排空 / conflate 再无新值）或超时
            var prev = -1
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                delay(250)
                if (fires[0] == prev) break
                prev = fires[0]
            }
            collector.cancel()
        }
        return fires[0]
    }

    @Test
    fun conflate_trims_collect_fires_when_consumer_is_the_bottleneck() {
        runBlocking {
            val producerPeriodMs = 20L   // ~50 emits/s
            val consumerDelayMs = 100L   // ~10/s（被重组占住的主线程）
            val burst = 50

            val without = measureCollectFires(false, producerPeriodMs, consumerDelayMs, burst)
            val with = measureCollectFires(true, producerPeriodMs, consumerDelayMs, burst)
            val dropped = without - with
            val pct = if (without > 0) (1.0 - with.toDouble() / without) * 100 else 0.0

            Log.i(tag, "================ CONFLATE · BACKPRESSURE ================")
            Log.i(tag, "burst=$burst  producer≈${1000 / producerPeriodMs}/s  consumer≈${1000 / consumerDelayMs}/s")
            Log.i(tag, "collect fires  WITHOUT conflate = $without   (= 下游重组触发数)")
            Log.i(tag, "collect fires  WITH    conflate = $with")
            Log.i(tag, "dropped intermediate emissions = $dropped   (${"%.0f".format(pct)}% 减少)")

            // 不加 conflate：生产被 SharedFlow 缓冲，消费者最终把每一条都消费 ⇒ ≈ burst
            check(without >= burst - 1) {
                "without-conflate 应交付 ≈ 全部 $burst 条，实际 $without"
            }
            // 加 conflate：消费端是瓶颈 ⇒ 只交付 ≈ 消费速率（这里 ≈ 10/s 量级），中间态被丢
            check(with < without) {
                "conflate 在背压下必须丢中间态：with=$with without=$without"
            }
            Log.i(tag, "PASS：conflate 把 collect 触发（= 重组触发）从 $without 降到 $with")
        }
    }

    @Test
    fun conflate_is_a_noop_when_consumer_keeps_up() {
        runBlocking {
        // 对照：消费端远快于生产端 ⇒ 无背压 ⇒ conflate 一个都不丢（安全气囊正常不动）
        val producerPeriodMs = 40L   // ~25 emits/s
        val consumerDelayMs = 4L     // ~250/s，消费端轻松跟上
        val burst = 50

        val without = measureCollectFires(false, producerPeriodMs, consumerDelayMs, burst)
        val with = measureCollectFires(true, producerPeriodMs, consumerDelayMs, burst)

        Log.i(tag, "================ CONFLATE · HAPPY PATH (control) ================")
        Log.i(tag, "burst=$burst  producer≈${1000 / producerPeriodMs}/s  consumer≈${1000 / consumerDelayMs}/s")
        Log.i(tag, "collect fires  WITHOUT conflate = $without")
        Log.i(tag, "collect fires  WITH    conflate = $with   (应 ≈ 相等)")
        Log.i(tag, "结论：消费端跟得上 ⇒ conflate 零丢弃，这是它作为「背压兜底」的正确表现")

        check(abs(without - with) <= 2) {
            "happy path 下 conflate 不应丢弃：with=$with without=$without"
        }
        }
    }
}
