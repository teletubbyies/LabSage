# SSE 打字机平滑渲染（FIFO 定速消费）可行性评估

> 状态：**评估结论，未实现**。本文回答两个问题：(1) 该方案在现有架构上能否实现平滑的流式打印；
> (2) 是否存在可验证手段来评估平滑效果。文末给出落地量级估算供后续决策。

## 1. 方案描述与问题

大模型吐 Token 的时间间隔不均匀：Reasoning 阶段长时间无输出，之后背靠背连续吐出多个 chunk。
当前 UI 对到达事件做被动节流，突发到达的内容在下一个时间窗整块倾泻，视觉上是
"停顿 → 一大块文字瞬间出现"的闪烁感。

参考 Web 前端的经典工程方案：

- 后端推送的 Token 统一先进入 FIFO 队列，不直接渲染；
- 渲染引擎以固定速率（如每 30ms 消费一个字符）从队列提取并打印（Web 上由
  `requestAnimationFrame` 驱动）；
- 队列堆积过长时按比例加速消费；队列为空时平滑等待；
- 目标是恒定视觉速率的打字机体验。

## 2. 现状与插入点

现有 SSE 数据通路（`update_sse.md` 双速率方案的落地形态）：

```
OkHttpSseDataSource (callbackFlow + buffer(512), flowOn(IO))
→ ChatRepository（纯透传）
→ ChatViewModel.handleSseEvent   [ChatViewModel.kt:408]
   Message 分支 [L414-454]：
     accumulatedAnswer.append(text)                  ← 每个 chunk 立即入内存
     SseUpdateCadence.onChunk(nowMs)                 ← 被动时间窗节流
       publishUi  (≤50ms 一次): publishStreamingSnapshot → _messages  [L689-713]
       checkpoint (≤500ms 一次): MessageDao.updateContent（持久化全文）
   Done 分支 [L482-557]：全量 publish + finalize + adoptServerIdentity
   Error/异常: persistTerminalFailure [L577]（进入后立即、在任何 Room 挂起点之前发布全文快照）
→ ChatScreen: messages.collectAsStateWithLifecycle() [L63]
   LazyColumn，流式气泡渲染纯 Text（完成后才切 MarkdownText）[MessageBubble.kt:66-84]
```

现状的平滑机制只有 `SseUpdateCadence`（`ui/chat/SseUpdateCadence.kt`，48 行）的
**被动时间窗节流**：它只回答"现在该不该发布"，不控制"每次发布多少"。卡顿后突发到达的
chunk 会在下一个 50ms 窗口内全部倒出——这正是要解决的缺口。

新方案的插入点非常干净：`handleSseEvent` 的 Message 分支与 `publishStreamingSnapshot`
之间。**Room checkpoint 半边（500ms、持久化全文）完全保留不动**，只替换 UI 发布半边。

## 3. 概念映射（Web 方案 → Android/Kotlin）

| Web 概念 | Android/Kotlin 对应 | 论证 |
|---|---|---|
| `requestAnimationFrame` | ViewModel 内协程 `while + delay(33ms)` tick 循环 | 可用虚拟时间/手动编排做确定性单测（项目已有 `SseUpdateCadenceTest` 注入时钟范式）；`withFrameNanos` 是字面意义的 rAF 对应物，但会把策略逻辑挤进 composable、难以单测，不推荐 |
| FIFO 队列 | **`accumulatedAnswer: StringBuilder` + `renderedLength` 游标**，backlog = `length - renderedLength` | `accumulatedAnswer` 本身就是积压载体：网络侧继续立即 append（入队），渲染循环推进游标（出队）。零拷贝、天然去重、Done 冲刷 = 游标推到末尾。`Channel<String>` 方案则需要同时维护 StringBuilder（checkpoint/finalize/continue 都要全文），等于双份真相源 + 取消排水 + 相同的速率逻辑，纯增复杂度 |
| `Sequence` | **不适用** | Kotlin `Sequence` 是惰性 pull 迭代器，没有跨协程的生产者-消费者协调与缓冲语义，不适合做这个队列 |
| 每次消费 1 字符 + 堆积按比例加速 | 每 tick 步长 = `ceil(backlog / K)` | 见 §5 |
| 渲染线程模型 | tick 循环与 SSE collector 同处 `viewModelScope`（Main.immediate），`publishStreamingSnapshot` 为非挂起函数 | 单线程局限，共享 `accumulatedAnswer` 与游标零锁零同步 |

`buffer(512)`（OkHttp 层）不受影响：它限制的是网络事件积压，与视觉平滑是两层。

## 4. 平滑性评估（问题一：能否实现平滑）

### 4.1 能做到

- **发布节奏恒定**：每 tick 至多一次发布，每次发布的字符数近似恒定。稳态下
  （持续到达 R 字符/s）步长收敛到 R × tick / 1000 的近似常数，chars-per-publish 的
  变异系数（CV = 标准差/均值）趋近 0——可以用确定性测试断言（§7.1）。
- **卡顿后突发的追赶**：比例步长使消费速率随 backlog 渐变、指数式收敛（一阶系统，
  无振荡），视觉上是"加速追进度，然后回落匀速"，正是方案要的行为。
- **空队列平滑等待**：backlog ≤ 0 时 tick 不发布、不触发重组，等待成本是一次整数比较。

### 4.2 天然做不到（需要明示的边界）

1. **Done/Error 终态跳变**：终态必须瞬间冲刷剩余 backlog（几千字符按 30ms/字符滴完
   需要几十秒，不可接受）。跳变量 ≈ 冲刷时刻的 backlog ≈ 最后约 1 秒的到达量
   （有界，典型几十~几百字符）。紧随其后的 Text → MarkdownText 切换本身就是一次大的
   视觉变化，这次跳变被自然掩盖。要彻底消除需延迟终态持久化，复杂度不成比例，不做。
2. **绝对逐字匀速**：追赶期速率是渐变的。这是"堆积时按比例加速"需求的直接推论，
   属于特性而非缺陷。
3. **帧级完美对齐**：`delay` 计时有 ±ms 级漂移，30Hz 下不可感知，不追求。
4. **崩溃恢复的连续性**：进程死亡后恢复，直接显示 checkpoint 的全文（恢复语义优先，
   与今天行为一致，`ChatRecoveryTest` 覆盖的语义不变）。

### 4.3 代价量化

| 项 | 量级 | 说明 |
|---|---:|---|
| 稳态显示滞后 | ≈ K × tick = 30 × 33ms ≈ **1.0s** | 比例控制的自平衡点，**与到达速率无关**（见 §5）。用户没有"真值"参照，滞后不可感知 |
| 慢速流滞后 | 0 ~ 2 tick | 到达 < 33 字符/s 时 backlog 在 0~几个字符间摆动，逐字滴出 |
| 首 token 显示 | 无回归 | tick-then-delay 结构（先消费再睡）使首个 Message 当帧即发布首字符 |
| 发布频率 | 20/s → 30/s | 每次发布成本 = 1 个气泡重组 + O(N) substring（与今天 O(N) toString 同阶）。流式期间纯 `Text` 渲染、其他气泡 skip 已被 `ComposeRecompositionTest` 实证；`SSE_CADENCE_GRID_REPORT.md` 的 33ms 档实测过该发布频率的调度成本可接受 |
| 滚动跟随 | 20/s → 30/s snap | `ChatScreen.kt:175-179` 的非动画 snap 模式不变，仅频率微增 |

以文档化基准负载（1,000 chunk × 32 字符 / 10ms ≈ 3,200 字符/s）为例：显示速率稳定在
约 106 字符/tick，滞后 1s，总显示时长 ≈ 到达时长 + 1s。

## 5. 速率控制算法

核心是一条公式（每个 tick）：

```
backlog = accumulatedAnswer.length - renderedLength
若 backlog ≤ 0        → 不发布（平滑等待）
否则 step = ceil(backlog / K)
     renderedLength += step（若落在低位代理上则再 +1，见 §6.3）
     发布 accumulatedAnswer.substring(0, renderedLength)
```

- **K = 30**（追赶视界 ≈ 1s），**tick = 33ms**（≈30fps；保守可退回 50ms，改一个常量）。
- backlog = 1 → step = 1（`ceil` 天然 ≥ 1）：队尾以 33 字符/s 匀速滴出，打字机质感。
- 突发 300 字符 → 首个 tick step = 10，随后按 (K-1)/K 的比例指数衰减。
- **自平衡**：持续到达 R 字符/s 时，消费速率随 backlog 单调增长，平衡点
  backlog* = K × R × tick / 1000，即**显示滞后恒 ≈ K × tick，与 R 无关**。
- **明确不设 charsPerTick 上限**：比例控制对任意到达速率自平衡；若加上限，
  超过上限速率的流（如 3,200 字符/s 的基准负载）滞后将无界增长，反而放大终态跳变。

## 6. 正确性约束与风险（可行性的一部分）

以下不变量延续 `update_sse.md` 的"正确性约束"传统，落地时必须遵守：

1. **终态取消先行（最关键）**：`Done` 分支在首次挂起点（Room 调用）让出主线程后，
   一个未取消的 tick 恢复执行可能发布**旧前缀覆盖终态全文**，违反"正文不得倒退"
   （`update_sse.md` 约束 2）。因此取消渲染循环必须是 `Done` 分支与
   `persistTerminalFailure` 的**第一条语句**（在任何挂起点之前）。
2. **continue 生成游标重置**：恢复 partial 后 `accumulatedAnswer` 已含全文
   （`restorePartialStateIfAny` 语义），`continueGeneration` 续传时游标必须重置到
   `accumulatedAnswer.length` 而非 0，否则气泡会"清空再重打"已显示内容
   （违反约束 4 的视觉形态）。
3. **代理对不切分**：按 UTF-16 char 推进可能切开 emoji 代理对。极简修法：步进后若
   新游标落在低位代理（`Character.isLowSurrogate`）上则 +1。已知残留：多 codepoint
   图形（国旗、ZWJ 家庭 emoji）可能跨 tick 分两半显示 33ms——grapheme 分段属过度
   工程，接受并声明。
4. **Trace/工具事件旁路队列**：`Trace`/`TraceSummary` 即时发布（现状不变），只有
   `Message` 文本走打字机。工具 chip 不应被文本积压延迟。
5. **Room checkpoint 与 UI 前缀解耦**：500ms checkpoint 持久化的仍是**全文**
   `accumulatedAnswer`（崩溃恢复窗口不变），UI 只显示已渲染前缀。两者数据不同是设计
   使然，终态时收敛一致（约束 3）。
6. **空转 tick**：流式期间即使 backlog = 0 也每 33ms 醒一次（比较后继续）。前台聊天页
   成本可忽略；不引入"排空即停、到达再启"的状态机（增复杂度，收益不成比例）。

## 7. 可验证手段（问题二：能否评估）

**能。** 分三层，全部沿用项目已有的测试范式，不引入新框架：

### 7.1 确定性单测（JVM，零真时钟）

仿 `SseUpdateCadenceTest`：策略类为纯逻辑（无时钟无协程，调用方供时间），测试手动编排
"到达序列 + tick 序列"。用例：

| 用例 | 断言 |
|---|---|
| 慢速滴流 | 每次发布恰 +1 字符；追平后 tick 无发布；前缀序列与源串逐字一致 |
| 卡顿后突发 300 字符 | 首 step = 10；空闲衰减期 step 单调不增；排空总 tick 数 ≤ 确定上界；每次发布的前缀单调不减 |
| 文档化负载稳态（1000×32c/10ms 交错 tick） | 跳过预热窗口后 chars-per-publish 的 **CV < 0.15**；同一 harness 模拟旧 50ms 窗口策略，断言 **新 CV < 旧 CV**（头条平滑性主张，纯确定性可验证） |
| 代理对安全 | 含 emoji（含贴着步长边界）文本的每个已发布前缀末字符不是高位代理；flush 后与源串 equals |
| 终态冲刷 | 部分渲染后 flush → equals 全文；后续 tick 返回 null |
| continue 防倒退 | 游标重置到既有长度后续传，首次发布长度 > 既有长度且前缀不变 |

### 7.2 Instrumented 双臂基准（真机/模拟器）

复刻 `SseCadenceGridBenchmarkTest` 范式（真实 Room 文件库 + 500 条历史 + 交替轮次 +
CSV 日志输出），双臂对照：

- **旧策略臂**：`SseUpdateCadence(50, 500)` 现状行为（对照组）；
- **打字机臂**：相同到达负载（稳态 + 突发两种模式），Room 操作序列完全一致
  （首 INSERT + 500ms checkpoint 全文 + 终态 finalize + 校准查询）。

指标列（CSV）：

```
publishes, charsPerPublishP50/P95/CV, burstDrainTicks,
snapshotMedianMs, roomMedianMs, roomP95Ms, totalMedianMs
```

硬断言：两臂 Room 写入次数相同、终态正文逐字一致（**持久化路径零回归**）；
打字机臂 CV 显著低于旧策略臂。可选第三方法借 `ComposeRecompositionTest` 的
SideEffect 计数法验证"每次发布仅 1 行重组"。

### 7.3 指标定义与声明边界

- **平滑度** := chars-per-publish 的 CV（核心）+ 发布间隔分布 + 突发排空 tick 上界
  （延迟上界）；重组计数作帧成本代理。
- 延续项目文档的诚实边界（`SSE_CADENCE_GRID_REPORT.md` 已承认）：**绝对 FPS / 慢帧率 /
  主线程 CPU 不在本方案量化范围**。若未来需要，用 Macrobenchmark/Perfetto 补充，
  与本方案的确定性指标正交。

## 8. 落地量级估算（供决策，非本次实施）

| 项 | 量级 |
|---|---|
| 新纯逻辑类（`TypewriterCadence`，风格仿 `SseUpdateCadence`） | ~90 行（含 KDoc，纯代码 ~45 行） |
| `ChatViewModel` 接线 | 净改 ~35 行，7 处：字段区、`doSend` 重置块、Message 分支重写（删 UI 发布半边、保留 checkpoint 半边）、Done 首行取消、`persistTerminalFailure` 首行取消、`continueGeneration` 游标重置、渲染循环 helper（tick-then-delay，~15 行） |
| `SseUpdateCadence` 去留 | **完整保留**：checkpoint 半边继续使用；旧测试与基准作对照组。`publishUi` 字段在生产代码变为死值，是已知小瑕疵（换来零测试翻新），可后续单独清理 |
| 不改动 | `OkHttpSseDataSource`、`ChatRepository`、`MessageDao`、`ChatScreen`、`MessageBubble`、Room 观察暂停/恢复、崩溃恢复链路 |
| 新增测试 | 确定性单测一份 + 双臂基准一份 |

## 9. 结论

1. **可行性：成立。** 插入点干净（Message 分支与 `publishStreamingSnapshot` 之间），
   FIFO 用现有 `StringBuilder` + 渲染游标即可表达，不需要引入真正的队列结构；
   `Sequence` 不适用于此场景。
2. **平滑性：能实现**，且可量化断言（稳态 CV < 0.15、新 CV < 旧 CV）。
   唯一接受的视觉边界是终态瞬间冲刷的跳变（≈ 最后 1 秒到达量，被 Markdown 切换掩盖）。
3. **可验证性：成立。** 确定性单测覆盖平滑度/安全/防倒退不变量；双臂 instrumented
   基准同时证明持久化路径零回归。绝对帧率指标维持项目既有声明边界，不在本次范围。
4. **代价**：稳态显示滞后 ≈ 1s（与到达速率无关、不可感知）；发布频率 +50%
   （重组成本增量已被现有实测背书）。
