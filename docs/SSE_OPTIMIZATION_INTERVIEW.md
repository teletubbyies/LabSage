# SSE 流式聊天性能优化：面试阐述稿

## 面试时的核心阐述

优化前后，assistant 正文都是先追加到 ViewModel 的 `StringBuilder`，落库时也都是把
`StringBuilder` 截至当前的完整正文覆盖到同一条 Room 消息记录。这次没有修改消息表结构，
也没有把完整快照改成增量存储。

原来的实现是每收到 5 个 chunk，就把当前完整正文写入 Room。回答越长，单次写入的字符串
越大；服务端 chunk 越密，写入频率也越高。

同时，ViewModel 在 `init` 中持续收集 Room DAO 返回的消息 Flow。返回 `Flow` 只是让这条
查询具备可观察能力，真正开始观察是在 ViewModel 调用 `collect` 之后。只要 collector
仍然活跃，`messages` 或关联的 `conversations` 表发生变化，Room 就会使查询失效并重新调度
查询；连续更新可能被 Room 合并，不应绝对表述为“一次 UPDATE 必然对应一次 SQL”，但原
方案确实把高频 Room 更新和全会话查询放在了同一条热路径上。

原来的 UI 也依赖 Room 回流：

```text
每 5 个 chunk
→ 从 StringBuilder 取得完整正文
→ UPDATE 同一条 assistant 记录
→ Room 消息查询失效并重新执行
→ 查询整段会话并映射 UI 模型
→ Compose 显示新正文
```

所以这次优化的重点不是“换一种字符串拼接方式”，而是：

1. 降低不断增长的完整正文快照的写入次数。
2. 让 Room 全会话查询退出 SSE Streaming 热路径。
3. 让 UI 刷新频率不再由服务端 chunk 分片速度决定。

优化后，数据模型和持久化语义没有改变：

- 一轮问答仍然是 user 和 assistant 两条消息。
- 两条消息通过同一个 `conversation_id` 和 `client_request_id` 关联。
- assistant 正文仍然在同一条记录上不断 UPDATE。
- 每次 checkpoint 仍然写入完整正文快照。
- `Done`、`Error` 和连接异常仍然强制执行最终落库。

改变的是更新频率和 UI 路径：

```text
用户发送：
user 消息成功写入 Room
→ 直接加入 _messages，立即显示
→ cancelAndJoin() 停止 Room 消息观察

SSE chunk：
立即追加到 StringBuilder

每 50 ms：
StringBuilder 不可变快照
→ 直接更新内存 _messages
→ Compose 显示

每 500 ms：
StringBuilder 完整快照
→ UPDATE 同一条 assistant 记录
→ 保留进程异常退出恢复能力
→ Streaming 期间没有活跃 Room collector，不触发本页面的全会话回查

Done / Error / 连接异常：
最终正文立即同步到 UI 和 Room
→ 恢复 Room Flow
→ 用数据库最终结果校准内存
```

50 ms 将 UI 发布限制在约 20 次/秒；500 ms 将中间 checkpoint 限制在约 2 次/秒。固定
1,000 个 chunk、每 10 ms 一个的测试中，总持久化次数从 202 次降到 21 次，降低
89.60%；真实 Room 基准中，“写入加每次强制全会话快照”的中位耗时从 176.13 ms 降到
18.42 ms，P95 从 224.79 ms 降到 23.83 ms。

这些耗时数据证明的是时间 checkpoint 减少了本地 Room 工作量。之后又基于最新“Streaming
期间暂停 Room Flow”的实现完成了 `33/50/100 ms × 250/500/1000 ms` 九组网格测试：
50/500 在10秒虚拟回答中产生200次UI发布、21次总持久化，并且终态只执行1次全会话校准
查询；本地处理总耗时P50为9.368 ms、P95为11.711 ms。该网格仍然不包含Compose绘制和
页面FPS，所以不会把本地处理耗时扩写成用户可感知帧率提升。

## 一、这个功能整体是怎么实现的？

这个项目的聊天回答通过 OkHttp SSE 接收。

网络层使用 `EventSourceListener` 接收服务端事件，再通过 `callbackFlow` 转换为
`Flow<SseEvent>`。事件主要分为：

- `message`：回答正文 chunk。
- `trace`、`trace_summary`：工具调用过程和统计信息。
- `done`：服务端确认本次回答结束，并携带会话、消息、状态和同步版本信息。
- `error`：服务端错误或客户端连接异常。

SSE 事件进入一个容量为 512 的有界缓冲。ViewModel 顺序消费事件，正文 chunk 先追加到
`StringBuilder`，再通过两套互相独立的频率更新 UI 和 Room：

- UI 最多每 50 ms 发布一次内存快照，上限约 20 次/秒。
- Room 最多每 500 ms 保存一次恢复 checkpoint，上限约 2 次/秒。
- 首个正文 chunk 立即显示，同时在 Room 中创建 assistant 消息。
- `Done`、`Error` 或连接异常绕过时间限制，立即发布并落盘最终状态。

现在 Room Flow 不再贯穿整个流式过程。

页面空闲或刚打开会话时，ViewModel 收集 Room 的消息 Flow，用数据库中的完整会话恢复
`_messages`。用户发送问题时，先成功写入一条 `role=user` 的消息，然后立即把同一条消息
加入 `_messages`，不再等待 Room Flow 重新查询后才显示。接着通过 `cancelAndJoin()` 等待
Room 消息观察任务完全停止，再启动 SSE。

流式期间，页面上的用户消息和 assistant 正文都由内存 `_messages` 驱动；Room 仍然按
500 ms 保存 checkpoint，但由于没有活跃的消息 Flow collector，这些写入不会触发本页面
重新查询整段会话。

回答进入终态后，ViewModel 先完成最终落库，再重新收集 Room 冷 Flow。重新收集会执行一次
权威查询，用数据库最终状态校准内存。连接提前关闭、事件处理异常和继续生成等路径也有相同
的恢复兜底，避免 Room 观察任务永久停止。

数据库中一条记录代表一条消息。一次正常问答是两条记录，通过相同的
`conversation_id` 和 `client_request_id` 关联：

| id | conversation_id | role | content | client_request_id | status |
|---|---|---|---|---|---|
| U1 | C1 | user | 协程和线程有什么区别？ | R1 | completed |
| A1 | C1 | assistant | 协程是…… | R1 | completed |

流式更新不是“读取数据库旧正文，再拼上新 chunk 后写回”。完整正文始终由 ViewModel 中的
`StringBuilder` 累计，checkpoint 只是把当时的完整内存快照覆盖到同一条 assistant 记录。

## 二、原来的实现是什么？有什么问题？

原来的实现已经做过一次简单批量处理，并不是每个 chunk 都写数据库，而是每收到 5 个
chunk 更新一次 Room：

```text
SSE chunk
  → 追加到 StringBuilder
  → 每 5 个 chunk 把当前完整正文 UPDATE 到 Room
  → messages 表失效
  → Room Flow 重新查询当前会话的全部消息
  → Entity 映射成 UI 状态
  → Compose 显示新内容
```

这个方案主要有四个问题。

### 1. chunk 数量不是稳定的性能单位

chunk 的大小和发送速度由模型和服务端分片策略决定：

- 每秒 10 个 chunk、每 5 个写一次，相当于每秒 2 次 UPDATE。
- 每秒 100 个 chunk，就会变成每秒 20 次 UPDATE。

换模型或调整服务端分片后，客户端负载会直接变化。因此它只是在某一种后端行为下看起来
合适，并不能给客户端提供稳定的性能上限。

### 2. 每次写入的是不断增长的完整字符串

客户端不是向数据库追加一个小增量，而是把 `StringBuilder` 中截至当前的完整回答覆盖到
同一条记录。回答越长，单次写入的字符串越大。

### 3. Room 写入和全会话查询被绑定

原来 ViewModel 一直收集 DAO 返回的 `Flow<List<MessageEntity>>`。Room 能从 `@Query`
解析出查询依赖的表；当 `messages` 表发生 UPDATE 后，`InvalidationTracker` 会使活跃查询
失效，随后重新执行当前会话查询。

Room 的失效粒度主要是表，不会因为“这次只更新了某一行的 content”就判断查询结果一定
无需重算。因此即使 LazyColumn 只显示少量消息，数据层仍可能重新读取、排序和映射整段会话。

原实现还在 Room Flow 后使用了 `conflate()`。它只能在下游消费者跟不上时丢弃部分已经
产生的结果，不能撤销前面的 UPDATE，也不能从根本上阻止 Room invalidation 和上游 SQL
查询，所以不是对根因的优化。

用户消息也依赖这条 Room 回流链路显示：先 INSERT，再等待 Flow 查询和发射，最后才能进入
`_messages`。

### 4. SSE 使用无限缓冲

原网络层使用：

```kotlin
buffer(Channel.UNLIMITED)
```

它能降低突发事件下 `trySend` 失败的概率，但没有内存上限。如果服务端持续发送，而下游被
数据库工作拖慢，事件会不断积压。

所以最终定位出的根因不是单一的“数据库写得太多”，而是：

1. UI 发布、Room checkpoint 和 Room 全会话查询耦合在一起。
2. 更新频率依赖不稳定的后端 chunk 数量。
3. 上游使用无限缓冲吸收下游变慢，没有明确的背压失败策略。

## 三、我是怎么优化的？

### 1. 从次数节流改为时间节流

每个 chunk 到达时，通过 `SystemClock.elapsedRealtime()` 判断：

- 距离上次 UI 发布是否达到 50 ms。
- 距离上次 Room checkpoint 是否达到 500 ms。

这里使用单调时钟，是因为用户修改系统时间、切换时区等行为不应该影响流式调度。

实现没有额外创建永久运行的 ticker 协程，而是在 SSE 事件到达时进行判断。没有新事件时
不会唤醒，也不需要管理额外的循环任务。

### 2. UI 改为内存直出

正文 chunk 先顺序追加到 `StringBuilder`。满足 50 ms 窗口时，ViewModel 调用
`toString()` 创建不可变正文快照，并直接更新 `_messages`。

用户消息在成功落库后也会立即构造对应的 `MessageUiState` 加入 `_messages`。因此发送问题
和显示流式回答都不再依赖：

```text
Room 写入 → InvalidationTracker → SQL 查询 → Entity 映射
```

首个 assistant chunk 仍会立即创建 Room 记录，这不是为了驱动 UI，而是为了保留进程被
杀死后的 partial 恢复能力。

### 3. 流式期间暂停 Room 消息观察

仅仅让 assistant 正文从内存直出还不够。如果 Room Flow 仍被收集，每次 500 ms
checkpoint 依然可能让全会话查询重新执行，只是查询结果不再决定最新正文。

因此现在把 Room collector 保存为独立 `Job`。开始 SSE 前执行 `cancelAndJoin()`：

- `cancel()` 发出取消信号。
- `join()` 等待 collector 真正结束。
- 等待完成后才启动 SSE，避免旧 collector 与第一个 checkpoint 发生竞态。

流式期间 Room 仍然执行低频 UPDATE，但本页面没有活跃的消息查询观察者，所以 checkpoint
不会继续触发这条全会话查询链。

这一版也不再需要“Room 发射旧 checkpoint 时，用内存正文覆盖数据库正文”的补偿逻辑，
因为流式期间根本不接收 Room 消息快照。

### 4. 终态后恢复 Room，并做权威校准

收到合法 `Done` 时，客户端会校验：

- `conversationId` 是否属于当前会话。
- `userMessageId` 是否属于当前请求。
- assistant 消息 ID、状态、更新时间和同步版本是否合法。

校验通过后，先把 `StringBuilder` 中的最终正文发布给 UI，并完成 assistant 终态落库和
服务端 ID 接管；随后把状态改为 Completed 或 Error，最后重新收集 Room 冷 Flow。

SSE `Error`、网络异常、没有终态事件的提前关闭以及继续生成也走同样的恢复路径。流收集任务
还有 `finally` 兜底，即使终态处理本身抛异常，也会重新启动 Room 观察。

Room 因此仍是空闲期、重新打开会话和最终一致性的真实源，但退出了高频流式热路径。

### 5. Room 只做低频 checkpoint

Room 最多每 500 ms 保存一次最新完整正文。

500 ms 表示进程被强制杀死时，理论上最多损失最近约半秒还没有 checkpoint 的正文。正常
结束时不会损失，因为 `Done`、错误和连接异常都会绕过窗口，强制执行终态写入。

这个方案没有完全取消 checkpoint，因为那样虽然能进一步减少写入，却会失去长回答生成期间
的进程死亡恢复能力。

### 6. 将无限缓冲改为有界缓冲

SSE 缓冲由 `Channel.UNLIMITED` 改为固定 512 个事件。

没有直接选 64 或 128，是因为项目的工具查询历史上出现过 200 多个连续 SSE 事件。512 为
正常突发保留余量，同时提供明确的内存上限。

现在每次 `trySend` 都检查返回值。缓冲已满或 Flow 已关闭时会：

1. 取消当前 `EventSource`。
2. 用明确异常关闭 Flow。
3. 进入 ViewModel 已有的 partial 保存和可重试错误流程。

这里选择明确失败并保存已生成正文，而不是静默丢失某个正文 chunk 或 `Done` 事件。

## 四、为什么选这个方案？

### 为什么不直接给 SSE Flow 加 `conflate()`？

SSE 传递的是有顺序语义的事件，不是可以任意覆盖的最新状态。正文 chunk、工具状态和
`Done/Error` 都不能随意丢弃。

所以优化的是 UI 快照发布、Room 持久化和 Room 观察频率，而不是丢弃原始 SSE 事件。

### 为什么不继续使用“每 N 个 chunk”？

同样 5 个 chunk 可能在 50 ms 内到达，也可能需要 2 秒。时间窗口让客户端负载不再跟随后端
分片策略变化，能稳定限制为约 20 次/秒 UI 发布和 2 次/秒 Room checkpoint。

### 为什么不仅做内存合并，还要暂停 Room Flow？

只做内存合并能够防止 UI 正文倒退，但不能阻止数据库 UPDATE 触发表失效和全会话查询。

暂停 collector 才真正从流式路径中移除了查询、Cursor 遍历、Entity 映射以及相应的
`_messages` 替换。终态后再恢复 Flow，可以用一次集中校准换掉流式期间的重复校准。

### 为什么不永久删除 Room Flow？

Room 不只接收当前 SSE 的写入，还可能接收历史同步、删除、服务端 ID 接管等变化。永久移除
Flow 意味着以后每一种数据库变更都要手动维护 `_messages`，状态一致性成本更高。

暂停仅覆盖单条回答的 Streaming 窗口，终态后立即恢复，是性能和一致性之间改动较小的折中。

### 为什么不是单独建立 streaming checkpoint 表？

单独的草稿表可以让 checkpoint 天然不使正式消息查询失效，是更彻底的数据库设计。但它需要
新增表、数据库迁移、终态搬运事务和旧版本兼容，改动明显更大。

当前方案不改变 schema，也不改变后端协议，就能让活跃 Room 查询退出流式热路径，更符合
“修改不复杂但效果明确”的目标。

### 为什么 UI 是 50 ms，Room 是 500 ms？

50 ms 对应最多约 20 次 UI 更新/秒。对于文字逐步增长，能够保持连续感，同时避免跟随后端
高频小 chunk 无限刷新。

500 ms 对应最多约 2 次 Room 写入/秒，并把异常退出时可能未保存的窗口控制在约半秒。

我实际比较了 UI 33/50/100 ms 与 Room 250/500/1000 ms 共9组组合。在相同500 ms
checkpoint下，50 ms相比33 ms少20%的UI发布和22.99%的快照字符复制，同时仍保持20 Hz；
100 ms虽然再减少约一半快照工作，但只剩10 Hz。

在相同50 ms UI间隔下，500 ms相比250 ms少48.78%的持久化操作；1,000 ms虽然还能从21次
降到11次，却会把进程异常退出时可能丢失的尾部窗口从约半秒放大到约1秒。因此50/500不是
单一性能指标的最低点，而是20 Hz文字连续感、2 Hz容灾保存和500 ms恢复窗口之间的取舍。
完整结果见 `docs/SSE_CADENCE_GRID_REPORT.md`。

### 为什么不做 Last-Event-ID 断点续传？

它需要后端保存和重放 SSE 事件，并定义 event ID、去重、过期和恢复协议，已经超出单纯的
客户端性能优化。

本次目标是在不改变协议的前提下，解决客户端高频持久化、重复查询和无限积压，因此没有把
协议升级混入这次修改。

## 五、性能是怎么测试的？

测试分为确定性调度测试和真实 Room 文件数据库基准。

### 第一层：确定性调度测试

固定模拟：

- 1,000 个 chunk。
- 每 10 ms 到达一个。
- 总持续时间约 10 秒。

旧策略：

- 首个 chunk 插入 assistant 消息。
- 每 5 个 chunk 执行一次更新。
- 终态再执行一次最终更新。
- 包含首条 INSERT 共 202 次持久化。

新时间策略：

- 首个 chunk 插入 assistant 消息。
- 每 500 ms 执行一次 checkpoint。
- 终态强制写入。
- 包含首条 INSERT 共 21 次持久化。
- UI 快照最多发布 200 次，约 20 次/秒。

确定性结果为：

- 总持久化次数从 202 次降到 21 次，降低 89.60%。
- 中间和终态 UPDATE 从 201 次降到 20 次，降低 90.05%。
- 最终 32,000 字符正文完全一致。

### 第二层：真实 Room 文件数据库基准

基准运行在 API 37 的 `Google sdk_gphone16k_arm64` 模拟器上，使用真实 Room 和 SQLite
文件数据库。

数据集为：

- 500 条历史消息。
- 1 条正在生成的 assistant 消息。
- 1,000 个 SSE chunk。
- 每个 chunk 32 个字符。
- 最终正文 32,000 字符。
- 5 轮预热。
- 30 轮正式测量，旧策略和新策略交替执行。

每个 checkpoint 都执行真实 DAO UPDATE。该基准还在每次 UPDATE 后主动执行并遍历一次与
生产查询等价的全会话 SQL，用来比较“Room 写入和全会话快照工作量”：

| 指标 | 旧策略：每 5 chunk | 500 ms 时间策略 | 改善 |
|---|---:|---:|---:|
| 中间 checkpoint + 终态 UPDATE | 201 次 | 20 次 | 90.05% |
| 包含首条 INSERT 的总持久化次数 | 202 次 | 21 次 | 89.60% |
| Room 写入 + 强制全会话快照中位耗时 | 176.13 ms | 18.42 ms | 89.54% |
| Room 写入 + 强制全会话快照 P95 | 224.79 ms | 23.83 ms | 89.40% |
| 最终正文 | 32,000 字符 | 32,000 字符 | 完全一致 |

### 最新“暂停 Room Flow”版本如何理解这些数据？

上表是已经完成并可复现的实测数据，但测试为了覆盖旧生产链路，在每次 UPDATE 后主动强制
执行了全会话查询。

最新生产实现已经在 Streaming 期间取消 Room collector，所以500 ms checkpoint 仍会
UPDATE，却不再触发本页面的这条全会话查询。终态后只重新启动一次观察任务进行权威校准。
因此上表能够证明时间 checkpoint 已减少约 90% 的持久化次数及对应工作量，但它不是最新
“暂停观察器”版本的重新实测端到端耗时。

在完成新的查询计数或 Perfetto/Benchmark 测量前，不把“暂停 Room Flow”额外带来的收益
虚构成新的毫秒数或 FPS。面试中可以准确表述为：

> 第一阶段把持久化次数从 202 次降到 21 次，真实 Room 基准中的中位耗时从
> 176.13 ms 降到 18.42 ms；第二阶段进一步让 Room 消息观察器退出 Streaming 热路径，
> 使低频 checkpoint 不再驱动本页面反复查询整段会话，终态后再统一校准。第二阶段的查询
> 消除属于代码路径优化，额外端到端耗时仍需单独复测。

### 可复现命令

运行全部 JVM 调度与回归测试：

```bash
./gradlew testDebugUnitTest --no-daemon
```

运行真实 Room 对比基准：

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.labsage.app.ui.perf.SsePersistenceBenchmarkTest
```

查看结果：

```bash
rg -n "SsePerf" app/build/outputs/androidTest-results/connected/debug -g '*.txt'
```

### 测试结论的边界

已经测得的数据能够证明：

- 固定负载下，Room 持久化次数减少约 90%。
- 同一模拟器、同一数据集下，“Room 写入 + 强制全会话快照”的中位数和 P95 工作量减少
  约 89%。
- 新旧策略最终正文完全一致。

它不能直接证明：

- 网络首 token 时间提升了 89%。
- 服务端生成速度提升了 89%。
- 页面 FPS 提升了 89%。
- 所有真实设备都会得到相同的绝对毫秒值。

如果要量化最新暂停观察器的额外收益，应在 DAO 或 SQLite 层增加查询计数，并对比：

- 旧方案：Streaming 全程保持 collector。
- 新方案：Streaming 前 `cancelAndJoin()`，终态后重新收集。
- 相同的 500 条历史消息、1,000 个 chunk 和 20 次 checkpoint。
- 记录 Streaming 期间全会话查询次数、终态校准次数、总耗时以及最终正文一致性。

之后再结合 Macrobenchmark 或 Perfetto 测量 P95 帧耗时、慢帧率和主线程 CPU 时间，才能把
用户可感知的流畅度提升写成新的量化结论。
