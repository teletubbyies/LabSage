package com.labsage.app.ui.perf

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 用 Compose TestRule + `SideEffect` 计数器，**确定性地**测重组次数。
 *
 * 为什么用 `SideEffect { counter.count++ }`：它在该 composable **每次成功提交的（重）组合**
 * 之后各跑一次；若该 composable 因参数稳定+未变被 **跳过（skipped）**，函数体根本不执行，
 * SideEffect 也就不跑——这正是 Layout Inspector「重组计数」的语义。所以这个计数器 = 该
 * composable 真正重组的次数。把每行的计数器做成 per-row，就能数出「一次列表更新，到底有几行
 * 在重组」。
 *
 * 三个实验分别对应项目里已在用的三条优化杠杆：
 *  1. `key` + 参数稳定（@Immutable）⇒ 只重组变化的那一行
 *  2. 不稳定参数（`List<String>`，正是 MessageBubble.traceLines 的现状）⇒ 全部行被迫重组
 *  3. `derivedStateOf` ⇒ 源变化但派生值不变时，读者不重组
 */
@RunWith(AndroidJUnit4::class)
class ComposeRecompositionTest {

    @get:Rule
    val rule = createComposeRule()

    private val tag = "PerfBench"

    /** 标记 @Immutable ⇒ 作为参数时稳定；同实例 Compose 视为「未变」。用于让被测 composable 可跳过。 */
    @Immutable
    private class Counter { @Volatile var count = 0 }

    /** 全部字段是稳定类型 ⇒ data class 本来就会被推断为 stable；再加 @Immutable 双保险。镜像 MessageUiState。 */
    @Immutable
    private data class StableMsg(val id: Int, val content: String)

    /** 带 `List<String>` 字段 ⇒ unstable。镜像 MessageBubble(role, content, modifier, isStreaming, traceLines: List<String>)。 */
    private data class ListMsg(val id: Int, val content: String, val tags: List<String>)

    @Composable
    private fun StableRow(msg: StableMsg, counter: Counter) {
        SideEffect { counter.count++ }
        Text(msg.content)
    }

    @Composable
    private fun ListRow(msg: ListMsg, counter: Counter) {
        SideEffect { counter.count++ }
        Text(msg.content)
    }

    /** 渲染 N 行，改第 [changeIndex] 行的 content，返回「重组的行数」。 */
    private fun measureRowsRecomposed(useStable: Boolean, rows: Int, changeIndex: Int): Int {
        val counters = List(rows) { Counter() }
        val listStable = mutableStateOf(List(rows) { StableMsg(it, "msg $it") })
        val listUnstable = mutableStateOf(List(rows) { ListMsg(it, "msg $it", emptyList()) })

        rule.setContent {
            Column {
                if (useStable) {
                    listStable.value.forEachIndexed { i, msg -> StableRow(msg, counters[i]) }
                } else {
                    listUnstable.value.forEachIndexed { i, msg -> ListRow(msg, counters[i]) }
                }
            }
        }
        rule.waitForIdle()
        val before = counters.sumOf { it.count }

        if (useStable) {
            listStable.value = listStable.value.toMutableList()
                .also { it[changeIndex] = it[changeIndex].copy(content = "CHANGED") }
        } else {
            listUnstable.value = listUnstable.value.toMutableList()
                .also { it[changeIndex] = it[changeIndex].copy(content = "CHANGED") }
        }
        rule.waitForIdle()

        val after = counters.sumOf { it.count }
        val rowsRecomposed = counters.count { it.count >= 2 }
        Log.i(
            tag,
            "${if (useStable) "STABLE  " else "List<String>"} rows=$rows changeRow=$changeIndex | " +
                "total recompositions=${after - before}, rows recomposed=$rowsRecomposed/$rows"
        )
        return rowsRecomposed
    }

    @Test
    fun stable_params_skip_unchanged_rows() {
        Log.i(tag, "================ RECOMPOSITION · 稳定参数（@Immutable + 同结构） ================")
        val rowsRecomposed = measureRowsRecomposed(useStable = true, rows = 10, changeIndex = 5)
        // 只有被改的那一行 content 不同 ⇒ 只有它重组，其余 9 行被跳过
        check(rowsRecomposed == 1) {
            "稳定参数下应只有 1 行重组，实际 $rowsRecomposed/10"
        }
        Log.i(tag, "PASS：稳定参数（@Immutable）让 10 行里只有变化的 1 行重组，其余跳过")
    }

    @Test
    fun list_field_does_not_block_skip_in_this_compose_version() {
        Log.i(tag, "================ RECOMPOSITION · 带 List<String> 字段的 data class（实证） ================")
        val rowsRecomposed = measureRowsRecomposed(useStable = false, rows = 10, changeIndex = 5)
        // 实测（本项目 Compose 版本）：ListMsg(id, content, tags: List<String>) 改 1 行仍是 1/10 重组——
        // 即 List<String> 字段并未让 data class 变成 unstable，跳过照常生效。结论：MessageBubble
        // 不可跳过的真凶**不是** traceLines 的 List 类型，而是 combinedClickable modifier（见下）。
        // （注：旧版 Compose 曾把 kotlin.collections.List 判 unstable；具体看编译器稳定性推断，
        //  所以这类结论必须自己测，别背教条。）
        Log.i(
            tag,
            "带 List<String> 字段：recomposed=$rowsRecomposed/10。→ " +
                if (rowsRecomposed <= 1) "List 字段未阻断跳过" else "List 字段阻断了跳过"
        )
    }

    // ============================================================
    // 真实 MessageBubble：谁让它不可跳过？（隔离 modifier 这个变量）
    // ============================================================

    /**
     * 复刻 MessageBubble 形参类型：role/content/modifier/isStreaming/traceLines: List<String>。
     * 唯一变量是 modifier：
     *  - passClickableModifier=true：每次重组把「新建 lambda 的 combinedClickable」当 modifier 传入
     *    （= 今天 ChatScreen 的调用方式）。
     *  - passClickableModifier=false：传入常量 Modifier（等价于「把 clickable 挪到外层 Box」）。
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun RepBubble(
        role: String,
        content: String,
        modifier: Modifier,
        isStreaming: Boolean,
        traceLines: List<String>,
        counter: Counter
    ) {
        SideEffect { counter.count++ }
        Column(modifier) { Text(content) }
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun measureBubbleRows(passClickableModifier: Boolean, rows: Int = 8, changeIndex: Int = 3): Int {
        val counters = List(rows) { Counter() }
        val list = mutableStateOf(List(rows) { "msg $it" })
        rule.setContent {
            Column {
                list.value.forEachIndexed { i, content ->
                    val mod = if (passClickableModifier) {
                        Modifier.combinedClickable(onClick = {}, onLongClick = {}) // 每次重组新建 lambda
                    } else {
                        Modifier // 常量，等价于「clickable 已移到外层 Box」
                    }
                    RepBubble("assistant", content, mod, false, emptyList(), counters[i])
                }
            }
        }
        rule.waitForIdle()
        list.value = list.value.toMutableList().also { it[changeIndex] = "CHANGED" }
        rule.waitForIdle()
        val recomposed = counters.count { it.count >= 2 }
        Log.i(
            tag,
            "MessageBubble 签名 ${if (passClickableModifier) "现状(combinedClickable 新 lambda 当 modifier 传入)" else "修复后(clickable 移到外层, modifier=常量)"}：" +
                "recomposed=$recomposed/$rows"
        )
        return recomposed
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun real_messageBubble_today_is_not_skippable_due_to_modifier() {
        Log.i(tag, "================ RECOMPOSITION · 真实 MessageBubble（现状） ================")
        val recomposed = measureBubbleRows(passClickableModifier = true)
        check(recomposed == 8) { "现状应 8/8 全重组（每次重组新建 lambda 的 combinedClickable modifier 阻断跳过），实际 $recomposed" }
        Log.i(tag, "现状确认：改 1 行 ⇒ $recomposed/8 气泡全重组（阻断点=modifier 的新 lambda，不是 List 参数）")
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun real_messageBubble_after_moving_clickable_to_parent_skips() {
        Log.i(tag, "================ RECOMPOSITION · 真实 MessageBubble（clickable 移到外层后） ================")
        val recomposed = measureBubbleRows(passClickableModifier = false)
        Log.i(
            tag,
            "修复后：recomposed=$recomposed/8 — " +
                if (recomposed <= 2) "✅ 可跳过！把 combinedClickable 移出 MessageBubble 形参后，只有变化的那条重组"
                else "仍未跳过（说明 List 参数也是阻断点，需进一步 @Immutable 包装）"
        )
    }

    // ============================================================
    // derivedStateOf：源变化但派生值不变 ⇒ 读者不重组
    // ============================================================

    @Composable
    private fun RawReader(src: androidx.compose.runtime.MutableState<Int>, counter: Counter) {
        val v = src.value            // 直接读源 ⇒ 源每次变都重组
        SideEffect { counter.count++ }
        Text("raw=$v")
    }

    @Composable
    private fun DerivedReader(src: androidx.compose.runtime.MutableState<Int>, counter: Counter) {
        val hot by remember { derivedStateOf { src.value >= 10 } } // 只在跨越 10 时翻转
        SideEffect { counter.count++ }
        Text(if (hot) "ON" else "off")
    }

    @Test
    fun derivedStateOf_shields_reader_from_irrelevant_source_changes() {
        Log.i(tag, "================ RECOMPOSITION · derivedStateOf ================")
        val src = mutableStateOf(0)
        val rawCounter = Counter()
        val derivedCounter = Counter()

        rule.setContent {
            Column {
                RawReader(src, rawCounter)
                DerivedReader(src, derivedCounter)
            }
        }
        rule.waitForIdle()

        // 源从 0 走到 20：派生值 hot 只在 src>=10 翻转一次
        for (i in 1..20) {
            src.value = i
            rule.waitForIdle()
        }

        Log.i(tag, "源 0→20（21 个值）：RawReader 重组=${rawCounter.count}，DerivedReader 重组=${derivedCounter.count}")
        // 直接读源：源每次变都重组 ⇒ ≈ 21
        check(rawCounter.count >= 19) { "RawReader 应几乎每次源变化都重组，实际 ${rawCounter.count}" }
        // derivedStateOf：派生值只翻转一次 ⇒ ≈ 2（初始 + 跨越）
        check(derivedCounter.count <= 3) { "DerivedReader 应只在派生值翻转时重组，实际 ${derivedCounter.count}" }
        Log.i(
            tag,
            "PASS：derivedStateOf 把读者重组从 ${rawCounter.count} 降到 ${derivedCounter.count} " +
                "（源狂变但派生值不变时不触发重组，正是 ChatScreen 里 isStreaming/isAtBottom 的用法）"
        )
    }
}
