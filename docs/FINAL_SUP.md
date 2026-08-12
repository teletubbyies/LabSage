## SSE相关

#### （1）后端执行顺序：

真实的执行顺序是这样的：

### `Begin()` — 流开始前（chat_turns.go:63-148）

在一个数据库事务里一次性创建两条记录：

```go
// 用户消息 — 写入完整内容
turn.UserMessage = Message{
    Content: input.Question,        // ← 完整的问题文本
    Status:  "completed",
}

// assistant 消息 — 占位，内容是空的！
turn.AssistantMessage = Message{
    Content: "",                    // ← 空字符串
    Status:  "streaming",
}
```

### 流式循环（chat_v1_chat_stream.go:175-210）

```go
for {
    chunk, err := sr.Recv()                     // 从 LLM 读一个 chunk
    fullResponse.WriteString(chunk.Content)     // 只在内存里攒
    client.SendToClient("message", chunk.Content) // 立刻推给 Android
    // 注意：这里没有任何数据库写入！
}
```

### `Complete()` — 流结束后（chat_turns.go:228-258）

只有在收到 LLM 的 `io.EOF` 之后，才执行一次 UPDATE：

```go
updates := map[string]any{
    "content": content,           // ← 这才把完整回复写入 DB
    "status": "completed",
}
```

---

**所以实际的数据流是：**

```
1. 请求到达
   → DB 写入: user(完整内容) + assistant(空占位)

2. LLM 流式返回 chunk
   → 同时做两件事: ① 立刻 SSE 推给 Android  ② 内存 StringBuilder 攒全量
   → DB 不动

3. LLM 结束 (EOF)
   → DB UPDATE: assistant.content = 完整回复, status = completed
   → SSE 推送 done 事件给 Android
```

**结论：Android 收到 chunk 的速度和 DB 完全无关。** chunk 是从 LLM 到后端内存再到 Android 的直接透传，中间不经过 SQLite。DB 只在流开始前写一次空占位、流结束后写一次最终内容。这也解释了为什么 Android 端的优化重点不在和服务端 DB 的交互——服务端在 streaming 期间本来就不写 DB。

#### （2）LabSage SSE 实现 vs 主流客户端 SSE 实现：逐维度对比

### 一、整体架构对比

| 层级     | LabSage                                                      | 主流做法                                                     |
| -------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| 网络层   | Square `okhttp-sse` `EventSource`，**POST** 请求             | OkHttp-sse（通常 GET）、Ktor `sse()`、或 Retrofit + 自定义 Adapter |
| 解析层   | 自定义 `sealed class` 6 种事件类型 + Gson + 严格 `require()` 校验 | 通常直接在 `onEvent` 里 `JSON.parse(data)`，仅区分 message/error |
| 桥接层   | `callbackFlow` + 有界缓冲区(512) + `trySend` 失败即断连      | 常见 `callbackFlow` + `Channel.UNLIMITED` 或 `conflate()` 静默丢事件 |
| 节流层   | **双速率**时间驱动: UI 50ms / Room 500ms                     | 通常单速率或按 chunk 计数写入，少数用 `debounce`             |
| 持久化层 | 流式期间**暂停 Room 观察**，UI 直写 StateFlow                | 绝大多数保持 Room Flow 活跃，每次写入触发全量重查            |
| 容错层   | 进程死亡恢复 + Continue/Regenerate + 合成 Error 事件         | 多数无进程死亡恢复，自动重连常见                             |

---

### 二、逐维度详细对比

#### 1. 连接方式

**LabSage（POST + 有界缓冲）**：
```kotlin
// OkHttpSseDataSource.kt:42-48
override fun streamChat(request: ChatStreamRequest): Flow<SseEvent> = callbackFlow {
    val jsonBody = gson.toJson(request)
    val httpRequest = Request.Builder()
        .url("$baseUrl/api/chat_stream")
        .post(jsonBody.toRequestBody(jsonMediaType))  // ← POST，非 GET
        .header("Accept", "text/event-stream")
        .build()
```

**主流做法**：绝大多数 SSE 客户端使用 **GET** 请求，参数放在 query string 中。因为 SSE 规范（HTML Standard）定义的 `EventSource` API 只支持 GET。OkHttp-sse 的 `EventSource` 虽然支持 POST，但这属于非标准用法。

**优劣**：
- ✅ **优**：POST body 可以承载复杂 JSON（session ID、clientMessageId、clientRequestId、clientCreatedAt），不受 URL 长度限制
- ✅ **优**：敏感参数不暴露在 URL/日志中
- ❌ **劣**：非标准，无法使用浏览器原生 `EventSource`；某些中间代理可能对 POST + `text/event-stream` 响应处理不当
- ❌ **劣**：POST 请求通常不被缓存，不过这在此场景下不是问题

---

#### 2. 事件模型与校验

**LabSage（强类型 + 严格校验）**：
```kotlin
// SseEvent.kt — 6 种 sealed class 子类型
sealed class SseEvent {
    data class Connected(...)
    data class Message(val text: String)      // 原始文本，非 JSON
    data class Trace(val line: String)        // 调试行
    data class TraceSummary(...)              // 工具统计
    data class Done(...)                      // 终端元数据
    data class Error(...)                     // 流错误
}

// OkHttpSseDataSource.kt:156-176 — Done 事件的严格校验
"done" -> {
    val payload = gson.fromJson(data, SseDonePayload::class.java)
    require(!payload.conversationId.isNullOrBlank())    // 6 个 require
    require(!payload.userMessageId.isNullOrBlank())
    require(!payload.assistantMessageId.isNullOrBlank())
    require(payload.status in setOf("completed", "partial", "failed"))
    require(payload.updatedAt > 0)
    require(payload.syncVersion > 0)
    // 任何校验失败 → SseEvent.Error，不崩溃
}
```

**主流做法**：通常在 `onEvent` 回调中做简单 JSON 解析，直接映射到数据类，不做严格字段校验。很多实现只区分 "有数据" 和 "报错" 两种状态，缺乏对终端事件的显式建模。

**优劣**：
- ✅ **优**：编译期类型安全，when 分支穷尽，不会漏处理事件类型
- ✅ **优**：`Done` 的 6 个 `require` 在数据进入业务层之前就拦截了不完整数据，避免后续 Room 写入脏数据
- ✅ **优**：校验失败不崩溃，而是降级为 `Error` 事件，保持了流的响应式语义
- ❌ **劣**：新增事件类型需要修改 sealed class（但这是有意的设计约束）
- ❌ **劣**：`null` 和未知 type 都 fallback 到 `Message`，可能掩盖协议变更问题

---

#### 3. 背压策略（最关键的架构差异）

**LabSage（有界缓冲 + 溢出即断）**：
```kotlin
// OkHttpSseDataSource.kt:39,84-92
const val EVENT_BUFFER_CAPACITY = 512

val delivery = trySend(event)
if (delivery.isFailure) {
    eventSource.cancel()
    close(IOException("SSE consumer could not keep up with the bounded event buffer"))
}
```

**主流做法**：
- **OkHttp + RxJava**：通常 `PublishSubject` 无界缓冲 → OOM 风险
- **Ktor SSE**：`Flow` 默认无背压，由协程挂起自然调节
- **常见简化**：`.conflate()` 静默丢弃中间事件 → 数据丢失风险
- **Retrofit + Flow**：通常无缓冲控制

**优劣**：
- ✅ **优**：512 事件上限防止内存无限增长。以当前 chunk 大小（~32 字符/条），最大缓冲 ~16KB，安全可控
- ✅ **优**：溢出即断连 + 通知上层 → fail-fast，而不是静默丢数据。对于**有序、非幂等**的事件流，这是正确性保证
- ✅ **优**：显式拒绝 `conflate()`：SSE 的 terminal 事件（Done/Error）如果被 conflate 丢弃，上层会永远等待
- ❌ **劣**：网络抖动 + 消费端短暂卡顿会导致不必要的断连。如果后端发送速度快于 UI 刷新（~16ms 一帧），即使 UI 正常也可能积压
- ❌ **劣**：512 是硬编码魔数，没有根据设备性能动态调整

---

#### 4. 双速率节流（最具创新性的设计）

**LabSage**：
```kotlin
// SseUpdateCadence.kt — 双速率独立节流
const val UI_INTERVAL_MS = 50L          // UI 每秒 ~20 帧
const val CHECKPOINT_INTERVAL_MS = 500L  // Room 每秒 ~2 次写入

fun onChunk(nowMs: Long): SseUpdateDecision {
    val publishUi = nowMs - lastUiPublishMs >= uiIntervalMs
    val checkpoint = nowMs - lastCheckpointMs >= checkpointIntervalMs
    // 两个决策独立，可能同时为 true
}
```

**主流做法**：
- 绝大多数应用：**不区分 UI 和持久化的更新频率**。要么每次 chunk 都同时更新 UI+DB（最多但最慢），要么每 N 个 chunk 批量写入一次（写放大取决于后端速率）
- 少数用 `flow.debounce(300)` 统一节流
- 很少见到 UI 速率和持久化速率分别建模

从测试数据看优化效果：

```kotlin
// SseUpdateCadenceTest.kt:64-73
val legacyTotalWrites = 202    // 旧方案：每 5 chunks 写 1 次 + 首尾
val optimizedTotalWrites = 21  // 新方案：500ms 定时 + 首尾
// 减少 89.6% 的 Room 写入，同时保持 UI 20fps 流畅
```

**优劣**：
- ✅ **优**：UI 和持久化解耦 — UI 流畅（20fps）不因 Room I/O 变慢
- ✅ **优**：**时间驱动而非计数驱动** — 不受后端 chunk 速率变化影响。后端快（10ms/chunk）或慢（200ms/chunk），用户体验一致
- ✅ **优**：减少 89.6% 的 Room 写入量，减少 WAL 增长和 checkpoint 压力
- ✅ **优**：有完整的网格基准测试（9 组参数 × 500 条历史消息 × 30 轮 × warmup），50/500 是实测最优平衡点
- ✅ **优**：首 chunk 始终触发 UI + checkpoint（`markCheckpoint`），保证有恢复点
- ✅ **优**：终端事件绕过节流（在 ViewModel 层处理），保证 Done/Error 一定被持久化
- ❌ **劣**：500ms 检查点间隔意味着**最坏情况下丢失 500ms 的生成内容**（进程崩溃场景）
- ❌ **劣**：`toString()` 在每次 UI 和 checkpoint 时都会复制整个 StringBuilder → 对于长回答（10K+ 字符），复制开销不可忽视。好在 UI 和 checkpoint 共享同一次 `toString()`（`contentSnapshot` 复用），这是一个巧妙的优化
- ❌ **劣**：50ms UI 间隔在低端设备上可能过于激进（Compose 重组可能超过 50ms）

---

#### 5. Room 观察生命周期管理

**LabSage**：
```kotlin
// ChatViewModel.kt:173-177, 233
private suspend fun pauseMessageObservation() {
    val activeObserver = messagesObservationJob
    messagesObservationJob = null
    activeObserver?.cancelAndJoin()  // 等待完全取消
}
// 然后才开始 SSE 流
pauseMessageObservation()
// ...SSE streaming...
// finally { startMessageObservation() }  // 重启，做一次权威查询
```

**主流做法**：
- 绝大多数应用：**保持 Room Flow 活跃**。每次 DB 写入 → Room InvalidationTracker 触发 → 重新执行 SQL 查询 → 重新 map entities → 重新 emit 到 UI
- 这个循环在每次 checkpoint 写入时都会发生，导致不必要的全量查询

**优劣**：
- ✅ **优**：消除 "写入→失效→重查→重映射→重发" 的级联效应。对照测试证实，带有 500 条历史消息的完整对话查询是 checkpoint UPDATE 延迟的 **~3×**（基准测试数据：roomMs 中位数 1-2ms vs reconcileMs 中位数 3-5ms，且 reconcile 需要读取所有历史消息的 content 列完成字符串复制）
- ✅ **优**：流式期间 UI 数据路径极短：`SSE chunk → StringBuilder.append → toString() → StateFlow.value =` ，不经过 Room/SQLite
- ✅ **优**：`cancelAndJoin()` 确保旧的 collector 完全退出后再开始 SSE，避免 "checkpoint 写入触发的旧查询结果覆盖了热更新内容" 的竞态
- ❌ **劣**：复杂度显著增加。需要小心处理所有退出路径（正常完成、错误、取消、崩溃恢复）中的 `startMessageObservation()` 重启
- ❌ **劣**：如果未来引入后台同步（别的进程也在写同一个对话），流式期间暂停观察意味着 UI 感知不到外部变更。不过当前架构下不存在这个场景

---

#### 6. 进程死亡恢复

**LabSage**：
```kotlin
// ChatViewModel.kt:631-661 — 从 Room 中恢复部分状态
private fun restorePartialStateIfAny(entities: List<MessageEntity>) {
    if (_streamState.value !is StreamState.Idle) return
    val recovered = ChatRecovery.from(entities) ?: return
    accumulatedAnswer.append(last.content)       // 恢复已生成内容
    streamingMessageId = last.id                 // 复用同一行
    // status == "streaming" → 进程在流中间被杀死 → 标记为 partial
}
```

**主流做法**：
- 大部分应用：进程被杀 → 已到达的 SSE 内容丢失（除非服务端有历史记录）
- 少数使用 `SavedStateHandle`（仅存活于配置变更，不存活于进程死亡）
- 极少数使用 `WorkManager` 在后台下载

**优劣**：
- ✅ **优**：进程死亡后可恢复已生成内容 + 显示"继续生成"按钮
- ✅ **优**：Continue 请求复用同一个 assistant message 行（`mergeTerminalOwned` / `insertOrReuseAssistantOwned`），不产生重复行
- ✅ **优**：区分 `streaming`（被杀）和 `partial`（后端主动标记中断）两种状态
- ❌ **劣**：恢复逻辑依赖 `clientRequestId` 匹配 user/assistant 消息对，若匹配失败静默返回 null，可能丢失恢复机会
- ❌ **劣**：只在 `StreamState.Idle` 时恢复（ViewModel 首次创建），如果 Room 先 emit 空列表再 emit 有数据列表，可能错过恢复窗口（但因为使用了 `conflate()` 在 Room Flow 上 + 从 Room 数据库直接读，实际上第一次 emit 就是完整数据）

---

#### 7. Trace 事件过滤（源码级优化）

**LabSage**：
```kotlin
// OkHttpSseDataSource.kt:77-81 — 在 trySend 之前过滤
if (event is SseEvent.Trace) {
    val l = event.line
    val keep = l.startsWith("[tool]") || l.startsWith("[react]") || l.startsWith("[route")
    if (!keep) return  // 不进入 channel
}
```

**优劣**：
- ✅ **优**：在源头过滤，节省 channel 容量。后端每个工具查询可能产生 200+ 条自由格式 trace 行
- ✅ **优**：过滤后的结构化 trace 构建了 `ToolCallUi` 时间线（开始/完成配对、耗时计算、FIFO 匹配）
- ❌ **劣**：过滤规则硬编码。如果后端新增 trace 前缀（如 `[rag]`），需要客户端更新

---

#### 8. 自动重连策略

**LabSage**：**刻意不使用自动重连**

**主流做法**：OkHttp-sse 的 `EventSource` 默认支持自动重连（通过 `EventSources.createFactory()` 的默认配置）。大多数应用开启自动重连以应对网络抖动。

**优劣**：
- ✅ **优**：对于**非幂等**的聊天生成，自动重连会导致重复请求/重复内容。LabSage 的选择是正确的
- ✅ **优**：断连后用户可以通过 Continue 显式恢复（带上 PartialAnswer），而不是自动重试整个请求
- ❌ **劣**：短暂网络波动（如 WiFi→5G 切换）会直接中断流，用户需要手动点"继续生成"

---

### 三、总结

LabSage SSE 实现与主流的最大差异不是用了什么库，而是**把 SSE 当作一个需要精确控制的有序事件管道，而非简单的"HTTP 长连接回调"**。具体体现为：

| 设计原则             | 体现                                                         |
| -------------------- | ------------------------------------------------------------ |
| **不信任后端数据**   | `Done` 6 个 `require`、JSON 解析 try/catch、clean EOF → Error |
| **不信任网络**       | 有界缓冲 + 溢出即断、不使用自动重连、所有终端路径都有 Error 事件 |
| **不信任消费端速度** | 512 缓冲上限、trySend 失败立即反应、trace 源过滤             |
| **分离关注点**       | UI 速率 ≠ 持久化速率、暂停 Room 观察让 UI 直写内存           |
| **移动端可靠性**     | 进程死亡恢复、checkpoint 定时写入、Continue/Regenerate       |
| **数据驱动优化**     | 9 组参数网格基准测试选定 50/500、旧方案对比 89.6% 写入减少   |

**核心优势**：正确性优先（fail-fast + 严格校验 + 非幂等感知）、移动端可靠性（进程死亡恢复）、性能量化（网格基准测试）。

**核心劣势**：复杂度高（~1500 行核心代码）、500ms checkpoint 间隔有数据丢失窗口、硬编码参数（512、50、500）缺乏自适应。

### 一、这个功能整体是怎么实现的？

这个项目的聊天回答通过 OkHttp SSE 接收。

网络层使用 `EventSourceListener` 接收服务端事件，再通过 `callbackFlow` 转换为
`Flow<SseEvent>`。事件主要分为：

- `message`：回答正文 chunk。
- `trace`、`trace_summary`：工具调用过程和统计信息。
- `done`：服务端确认本次回答结束，并携带会话、消息、状态和同步版本信息。
- `error`：服务端错误或客户端连接异常。

**SSE 事件进入一个容量为 512 的有界缓冲**。ViewModel 顺序消费事件，正文 chunk 先追加到
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

### 为什么不完全关闭Room观察？

保留room查询是为了保证数据一致性，也就是安卓数据库中的数据作为显示的真相源 。但这个"真相源"的实际作用要分两层来看：

### 架构设计层面：是合理的

模式是清晰的——**热路径走内存，冷路径回归 Room**：

```
Streaming 期间：内存是真相源（快，不走 DB 查询）
    ↓
Done/Error 之后：Room 重新接管（持久化，可审计）
```

这个设计本身没什么问题。类比就是写穿缓存：平时直接读缓存（内存），但每次写完之后用 DB 做一次校准，确保缓存没有漂移。

### 实际收益层面：比较薄

校准到底校了什么？

- **内容？** 我们刚在 494 行把最终内容写进内存，498 行写进 DB，两者一模一样。
- **状态？** `completed`/`partial`，两边同时设的。
- **真正不一致的只有消息 ID**——`adoptServerMessageIdentity` 改了 DB 里的主键，内存里还是旧 UUID。

所以这个"真相源校准"在当前的实现里，实际只修了一个字段：消息 ID。内容一致性更多是理论上的安全网，不是每次都有实际差异需要修正。

### 总结

说"Room 是 UI 的真相源"作为架构理念完全站得住脚。只是在这段代码里，它更像是一个**保险丝**——正常情况下用不上，但万一内存状态因为某种边界情况写坏了，Room 回流能兜底。花几毫秒换这个安全网，不算亏。

---

数据库中一条记录代表一条消息。一次正常问答是两条记录，通过相同的
`conversation_id` 和 `client_request_id` 关联：

| id   | conversation_id | role      | content                | client_request_id | status    |
| ---- | --------------- | --------- | ---------------------- | ----------------- | --------- |
| U1   | C1              | user      | 协程和线程有什么区别？ | R1                | completed |
| A1   | C1              | assistant | 协程是……               | R1                | completed |

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
  → Room Flow 重新查询当前会话的**全部所有**消息
  → Entity 映射成 UI 状态
  → Compose 显示新内容
```



这个方案主要有5个问题。

### 0.Room Flow重新查询当前会话的**全部所有**消息：

```sql
SELECT messages.* FROM messages
INNER JOIN conversations ON conversations.id = messages.conversation_id
WHERE messages.conversation_id = :conversationId
  AND conversations.owner_user_id = :userId
  AND messages.is_deleted = 0
ORDER BY messages.timestamp ASC, messages.id ASC
```

**没有 LIMIT，没有 role 过滤，没有"只查最新两条"。** 只要属于这个 `conversationId`，所有历史 Q-A 全部拉出来，映射成 `List<MessageUiState>`，整段替换 `_messages.value`，然后 Compose 对整个列表做 diff 重组。

这就是优化前的问题核心：

```
优化前：每 5 个 chunk 写一次 DB
→ Room 检测到 messages 表变化
→ 这条 SQL 重新执行，拉出整段会话（可能几十条消息）（但是这里原本就会有全量查询）
→ 全部重新映射为 UI 模型
→ Compose 对整个 LazyColumn 做 diff（这里原本就有优化，只会重组最新的）
→ 实际只变了一条消息的 content，但全链路跑了一遍
```

### diff细节：

代码里的注释已经回答了这个问题（306-313 行）：

```
历史气泡被跳过，只有正在流式输出的那个气泡会重组
（实测 8/8 → 1/8）
```

**Compose 确实做了优化。** 三层机制叠加：

##### 1. Key 匹配

```kotlin
items(messages, key = { it.id }) { msg -> ... }
```

Compose 用 `id` 跟踪每个列表项。旧列表的 `"msg-123"` 和新列表的 `"msg-123"` 通过 key 对上号。

##### 2. 数据相等性跳过

`MessageUiState` 是 `data class` + `@Immutable`。Compose 编译器对两个 key 相同的项调用 `equals()`，如果所有字段都没变，直接跳过 recomposition。

##### 3. Modifier 稳定性

注释里专门提了——他们把点击事件放在外层 Box，让 `MessageBubble` 接收一个常量 Modifier。否则每次重组会创建新的 lambda 对象，Modifier 被判定为 "changed"，导致所有可见气泡全部重组。

##### 所以 Diff 的实际效果

```
旧列表: [msg-1, msg-2, msg-3, msg-4, msg-5(streaming)]
新列表: [msg-1, msg-2, msg-3, msg-4, msg-5(streaming, 内容更新)]

Compose diff:
  msg-1: key 匹配, equals → true  → 跳过 ✓
  msg-2: key 匹配, equals → true  → 跳过 ✓
  msg-3: key 匹配, equals → true  → 跳过 ✓
  msg-4: key 匹配, equals → true  → 跳过 ✓
  msg-5: key 匹配, equals → false → 重组 ✓ (只重组这一个)
```

**是的，只有最近一次对话的 UI 会变。** 优化前的真正问题不是 Compose 重组太多，而是——为了让 Compose 发现 "msg-1 到 msg-4 都没变"，必须先执行一遍完整 SQL 查询 + `entities.map { it.toUiState() }` + 列表 diff。这 200 多次的全量查询和映射才是瓶颈，Compose 的 diff 优化救不了前面这几步。

优化后把这个全量查询从热路径上拿掉了——streaming 期间不跑，Done 之后跑一次，成本不在同一个量级上。

所以你说的完全正确：**Room Flow 返回的是整段会话的全量消息，不是当前 Q-A 对。** 这就是为什么"让 Room 退出 SSE 热路径"是这次优化的关键之一。

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

**Room 的失效粒度主要是表**，不会因为“这次只更新了某一行的 content”就判断查询结果一定
无需重算。因此即使 LazyColumn 只显示少量消息，数据层仍可能重新读取、排序和映射整段会话。

原实现还在 Room Flow 后使用了 `conflate()`。它只能在下游消费者跟不上时丢弃部分已经
产生的结果，不能撤销前面的 UPDATE，也不能从根本上阻止 Room invalidation 和上游 SQL
查询，所以不是对根因的优化。

用户消息也依赖这条 Room 回流链路显示：先 INSERT，再等待Room 查询和Flow发射，最后才能进入
`_messages`。

### 4. SSE 使用无限缓冲

原（SSE事件）网络层使用：

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

实现没有额外创建永久运行的 ticker 协程，而是在 **SSE 事件到达时进行判断**。没有新事件时
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

这个方案没有完全取消 checkpoint，因为那样虽然能进一步减少写入，却会**失去长回答生成期间**
**的进程死亡恢复能力。**

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
`Done/Error` 都**不能随意丢弃**。

所以优化的是 UI 快照发布、Room 持久化和 Room 观察频率，而不是丢弃原始 SSE 事件。

### 为什么不继续使用“每 N 个 chunk”？

**同样 5 个 chunk 可能在 50 ms 内到达，也可能需要 2 秒**。时间窗口让客户端负载不再跟随后端
分片策略变化，能稳定限制为约 20 次/秒 UI 发布和 2 次/秒 Room checkpoint。

### 为什么不仅做内存合并，还要暂停 Room Flow？

只做内存合并能够防止 UI 正文倒退，但不能阻止数据库 UPDATE 触发表失效和全会话查询。

暂停 collector 才真正从流式路径中移除了查询、Cursor 遍历、Entity 映射以及相应的
`_messages` 替换。终态后再恢复 Flow，可以用一次集中校准换掉流式期间的重复校准。

### 为什么不永久删除 Room Flow？

Room 不只接收当前 SSE 的写入，还可能接收历史同步、删除、服务端 ID 接管等变化。**永久移除**
**Flow 意味着以后每一种数据库变更都要手动维护 `_messages`，状态一致性成本更高**。

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

这两个值是工程约束，不是未经比较就宣称的全局最优值。当前实际比较的是旧的“每 5 chunk”
和新的“50/500 ms”方案。如果继续调参，会固定同一条回答，比较 UI 33/50/100 ms 与 Room
250/500/1000 ms 组合，并同时观察 P95 帧耗时、慢帧率、CPU 时间和恢复丢失窗口。

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

| 指标                               | 旧策略：每 5 chunk | 500 ms 时间策略 |     改善 |
| ---------------------------------- | -----------------: | --------------: | -------: |
| 中间 checkpoint + 终态 UPDATE      |             201 次 |           20 次 |   90.05% |
| 包含首条 INSERT 的总持久化次数     |             202 次 |           21 次 |   89.60% |
| Room 写入 + 强制全会话快照中位耗时 |          176.13 ms |        18.42 ms |   89.54% |
| Room 写入 + 强制全会话快照 P95     |          224.79 ms |        23.83 ms |   89.40% |
| 最终正文                           |        32,000 字符 |     32,000 字符 | 完全一致 |

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



先纠正一个概念：`SharedPreferences` 和 `DataStore` 是存储方案；“JWT 明文”是数据是否加密的状态，不是一种存储技术。

更准确的三种选型应该是：

1. SharedPreferences 明文存 JWT
2. DataStore 明文存 JWT
3. Android Keystore + 加密后的 SharedPreferences/DataStore

## 存储方式对比

| 方案                              | 实际保存位置                                | JWT 是否加密 | API 特征                      | 安全性 | 适用内容                 |
| --------------------------------- | ------------------------------------------- | ------------ | ----------------------------- | ------ | ------------------------ |
| SharedPreferences 明文            | App 私有目录中的 XML                        | 否           | 同步读取，`apply/commit` 写入 | 低     | 普通非敏感配置           |
| Preferences DataStore 明文        | App 私有目录中的 DataStore 文件             | 否           | 协程、Flow、事务化更新        | 低     | 响应式普通配置           |
| Proto DataStore 明文              | Proto 二进制文件                            | 否           | 类型安全、Flow、事务化        | 低     | 结构化普通配置           |
| Keystore + SharedPreferences 密文 | SP 保存 IV 和密文；Keystore 保存密钥        | 是           | 需要自己实现加解密            | 较高   | JWT、Refresh Token       |
| Keystore + DataStore 密文         | DataStore 保存 IV 和密文；Keystore 保存密钥 | 是           | 加密 + Flow，代码更复杂       | 较高   | 需要响应式读取的敏感状态 |

关键结论：

> 把 JWT 从 SharedPreferences 换到 DataStore，并不会自动变安全。只要没有加密，攻击者拿到应用数据文件后仍然能读出 Token。

## 三种典型选型对比

### 方案一：SharedPreferences 明文保存 JWT

```
preferences.edit()
    .putString("access_token", accessToken)
    .putString("refresh_token", refreshToken)
    .apply()
```

磁盘里的 XML 可能类似：

```
<string name="access_token">eyJhbGciOi...</string>
<string name="refresh_token">eyJhbGciOi...</string>
```

优点：

- 实现简单。
- 读取方便。
- 老项目兼容性好。

缺点：

- Token 是明文。
- Root、调试备份、设备取证或应用数据泄露后，可以直接复制 Token。
- SharedPreferences 的同步读取可能涉及主线程磁盘访问。
- 不适合生产项目中的 Refresh Token。

适用场景：

- 临时 Demo。
- Mock Token。
- 不包含真实账号数据的教学项目。

不推荐用于真实登录系统。

------

### 方案二：DataStore 明文保存 JWT

```
val ACCESS_TOKEN = stringPreferencesKey("access_token")

dataStore.edit { preferences ->
    preferences[ACCESS_TOKEN] = accessToken
}
```

优点：

- 使用协程，不阻塞主线程。
- 支持 Flow 响应式监听。
- 更新操作串行化、事务化。
- 比 SharedPreferences 更符合现代 Android 架构。

缺点：

- 默认仍是明文。
- Flow、异步和事务性解决的是工程问题，不是安全问题。
- 攻击者拿到 DataStore 文件后，仍可能提取 JWT。
- Proto DataStore 的二进制格式也不等于加密。

适用场景：

- 深色模式、语言、后端地址。
- 同步游标。
- 是否完成首次导入。
- 普通用户偏好。

不建议直接保存明文 Refresh Token。

------

### 方案三：Keystore + 加密存储 JWT

LabSage 当前使用的就是这个方案：

```
AuthSession
   ↓ JSON 序列化
AES-GCM 加密
   ↓
IV + 密文
   ↓
SharedPreferences
```

AES 密钥位于 Android Keystore：

```
SharedPreferences → 保存密文
Android Keystore  → 保存不可导出的 AES 密钥
```

优点：

- JWT 不以明文落盘。
- AES-GCM 同时提供加密和完整性校验。
- 密文被篡改后无法通过认证。
- Keystore 密钥通常不可导出。
- 即使复制 SharedPreferences 文件，没有原设备密钥也无法正常解密。

缺点：

- 实现复杂度更高。
- 需要处理密钥失效、密文损坏和设备迁移。
- 卸载重装后旧密文无法继续使用。
- 不能阻止运行中的恶意代码在 App 已解密 Token 后读取内存。
- 当前项目没有要求生物识别才能使用密钥，因此解锁设备上的 App 可以正常使用该密钥。

适用场景：

- Access Token
- Refresh Token
- 登录凭据
- 需要本地持久化的敏感会话信息

## DataStore 和 Keystore 的职责不同

不要把它们理解成替代关系：

```
DataStore          → 解决异步、Flow、事务、类型安全
Android Keystore   → 解决加密密钥保护
AES-GCM            → 解决数据加密和防篡改
SharedPreferences  → 可以作为密文的持久化容器
```

因此完全可以组合：

```
Keystore + SharedPreferences
Keystore + DataStore
```

LabSage 选择 `Keystore + SharedPreferences`，原因是登录状态读取需求简单：

- 启动时读取一次。
- 登录/刷新时保存。
- 退出登录时删除。
- 不需要持续通过 Flow 监听 Token。

如果登录状态需要复杂的响应式监听，也可以考虑 `Keystore + DataStore`，但代码复杂度会更高。

## 面试版三种选型对比

### 简洁版

> SharedPreferences 和 DataStore 都只是持久化容器，本身不会自动加密 JWT。SharedPreferences API 简单，但同步读取可能带来磁盘阻塞，也缺少事务化更新；DataStore 使用协程和 Flow，支持异步、响应式和事务化更新，更适合普通配置。但如果把 JWT 明文存入 DataStore，安全性并不会比 SharedPreferences 明显更高。对于 Access Token 和 Refresh Token，我会使用 Android Keystore 管理不可导出的 AES 密钥，通过 AES-GCM 加密登录状态，再把密文存入 SharedPreferences 或 DataStore。

### 结合项目版

> LabSage 的普通配置，例如后端地址、同步游标和 legacy import 标记，使用 DataStore，因为这些数据需要异步读取、事务更新或 Flow。聊天记录使用 Room，因为它们是结构化业务数据。JWT 属于敏感认证信息，所以没有明文写入 DataStore，而是使用 Android Keystore 生成 AES 密钥，通过 AES-GCM 加密完整 AuthSession，再把 IV 和密文保存到 SharedPreferences。这里 SharedPreferences 只是密文容器，真正的安全边界是 Keystore。

### 被追问“为什么不用 DataStore 存密文”

> 可以使用 Keystore 加密后再把密文放入 DataStore，安全性同样成立。但当前登录状态只在启动、登录、刷新和退出时读写，不需要持续的 Flow 订阅。使用 SharedPreferences 作为密文容器实现更直接，而并发一致性由 AuthSessionStore 的同步方法和条件更新控制。如果未来需要多处响应式订阅登录会话，可以迁移到加密后的 DataStore。

## 最终选型口诀

```
普通配置     → DataStore
结构化业务数据 → Room
敏感 Token   → Keystore + 加密存储
大文件       → 文件系统
```

最重要的面试点是：

> DataStore 比 SharedPreferences 现代，但 DataStore 不等于加密；Keystore 负责保护密钥，AES-GCM 负责加密数据，SharedPreferences 或 DataStore 只是保存最终密文。



下面可以直接作为后续学习和面试准备 TODO，重点结合 LabSage 当前实现，过于偏门的坑暂时不展开。

## TODO 1：认证逻辑

### 1. 理解 Access Token 和 Refresh Token

Access Token：

- 有效期短，例如项目中是 15 分钟。
- 每次普通 REST、SSE 请求通过 `Authorization: Bearer <token>` 携带。
- 后端验证签名和过期时间后获取用户 ID。
- 泄露后的风险窗口较短。

Refresh Token：

- 有效期长，例如项目中是 30 天。
- 不用于访问聊天、会话等业务接口。
- Access Token 过期后，用来向 `/api/auth/refresh` 换取新的 Token。
- 服务端保存 Refresh Token 的哈希，并支持轮换和撤销。

核心理解：

```
Access Token = 临时门票
Refresh Token = 获取新门票的长期凭证
```

### 2. 刷新 Token 的意义

如果只有 Access Token，有两个不理想的选择：

- 有效期设置得很长：泄露后攻击者可以长期使用。
- 有效期设置得很短：用户每 15 分钟就要重新登录。

Refresh Token 解决了这个矛盾：

```
短期 Access Token
        ↓ 过期
客户端拿 Refresh Token 请求刷新
        ↓
后端验证、轮换
        ↓
返回新的 Access Token + Refresh Token
```

因此它同时实现：

- Access Token 保持短生命周期。
- 用户可以长期保持登录。
- 后端可以撤销 Refresh Token。
- 修改密码、退出登录或发现异常时，可以强制终止长期会话。

### 3. LabSage 的认证流程

```
注册/登录
  ↓
后端验证用户名和密码
  ↓
返回 Access Token + Refresh Token
  ↓
Android 使用 Keystore 保护并保存 Token
  ↓
普通请求携带 Access Token
  ↓
收到 401
  ↓
使用 Refresh Token 刷新
  ├── 成功：保存新 Token，重试原请求
  └── 失败：清理登录态，返回登录页
```

### 4. 为什么刷新时要同时更换 Refresh Token

项目采用 Refresh Token rotation：

1. 客户端提交旧 Refresh Token。
2. 后端验证数据库中的哈希。
3. 后端撤销旧 Token。
4. 签发新的 Access Token 和 Refresh Token。
5. 数据库保存新 Refresh Token 的哈希。

旧 Refresh Token 随即失效。

这样即使旧 Token 被截获，攻击者也不能一直重复刷新。

### 5. 认证 TODO

- 

  能讲清 Access Token 和 Refresh Token 的职责区别。

- 

  能解释为什么不能把 Access Token 有效期直接设置成 30 天。

- 

  梳理注册、登录、刷新、退出登录、

  ```
  /auth/me
  ```

   完整流程。

- 

  理解密码为什么保存 bcrypt 哈希，而不是可解密密文。

- 

  理解 Refresh Token 为什么只保存哈希。

- 

  理解 Android 为什么使用 Keystore 保护 Token。

- 

  理解 REST 和 SSE 都需要携带 Access Token。

- 

  能解释 Refresh Token rotation 和注销机制。

- 

  能解释刷新失败后为什么要清理登录态。

### 6. 认证中容易踩的坑

#### 坑一：多个请求同时刷新 Token

假设 Access Token 过期时，客户端同时发出三个请求：

```
请求 A → 401 → refresh
请求 B → 401 → refresh
请求 C → 401 → refresh
```

由于 Refresh Token 会轮换：

- A 刷新成功，旧 Refresh Token 失效。
- B、C 继续使用旧 Token，会刷新失败。
- 客户端可能被错误退出登录。

解决方案：刷新操作加 Mutex，所有失败请求共享同一次刷新结果。

```
请求 A ─┐
请求 B ─┼→ 一次 refresh → 新 Token → 分别重试
请求 C ─┘
```

#### 坑二：刷新请求也被认证拦截器拦截

如果 `/auth/refresh` 自身经过普通 Access Token 刷新逻辑，可能形成：

```
刷新请求返回 401
  ↓
拦截器再次刷新
  ↓
再次返回 401
  ↓
无限循环
```

刷新接口需要绕开普通自动刷新逻辑，或明确限制只重试一次。

#### 坑三：只给 Retrofit 加 Token，忘记 SSE

LabSage 的普通请求走 Retrofit，而 SSE 使用独立的 OkHttp 长连接。

所以：

```
Retrofit 有 Bearer Token ≠ SSE 自动有 Bearer Token
```

两个网络入口都必须接入认证。

#### 坑四：任何 401 都立刻退出登录

401 可能只是 Access Token 过期。

正确顺序是：

```
业务请求 401
  ↓
尝试刷新一次
  ├── 成功：重试原请求
  └── 失败：清理登录态
```

不能第一次 401 就直接跳转登录页。

------

# TODO：历史同步与 Room 消息源

## 一、先明确最新的数据职责

根据最新 SSE 实现，不能再简单说“UI 永远只读取 Room”。

现在更准确的职责是：

```
Go 服务端数据库：业务权威数据源
Room：客户端持久化缓存、空闲期消息源、进程恢复点
内存 StateFlow：SSE 生成期间的实时 UI 消息源
```

对应两种状态：

```
非流式状态：
Room Flow → ViewModel → Compose

SSE 流式状态：
SSE → StringBuilder → 内存 _messages → Compose
                    ↓ 每500ms
                  Room checkpoint
```

流式结束后：

```
最终内容写入 Room
        ↓
恢复 Room Flow
        ↓
执行一次权威查询
        ↓
用 Room 最终状态校准内存 UI
```

因此，Room 仍然是客户端持久化层的单一消息源，但为了**避免高频数据库回流**，SSE 生成期间会暂时使用内存快照驱动 UI。

核心代码：

- [ChatViewModel.kt](/Users/zzy/Desktop/Android/LabSage/app/src/main/java/com/labsage/app/ui/chat/ChatViewModel.kt)
- [HistorySyncCoordinator.kt](/Users/zzy/Desktop/Android/LabSage/app/src/main/java/com/labsage/app/data/sync/HistorySyncCoordinator.kt)
- [RoomHistorySyncStore.kt](/Users/zzy/Desktop/Android/LabSage/app/src/main/java/com/labsage/app/data/sync/RoomHistorySyncStore.kt)
- [OkHttpSseDataSource.kt](/Users/zzy/Desktop/Android/LabSage/app/src/main/java/com/labsage/app/data/remote/OkHttpSseDataSource.kt)

------

## 二、历史同步完整流程

### 1. 打开会话列表

```
Compose 开始观察 Room conversations Flow
        ↓
Room 立即提供本地缓存
        ↓
Repository 执行 syncConversations()
        ↓
必要时先执行 legacy import
        ↓
读取当前用户和后端对应的 cursor
        ↓
请求服务端全量或增量会话
        ↓
事务写入 Room
        ↓
成功后推进 cursor
        ↓
Room Flow 通知 UI 更新
```

网络失败时不会清空 Room，用户仍然可以看到本地历史，只显示同步失败状态。

### 2. 打开某个聊天页面

```
ChatViewModel 开始观察该会话的 Room messages Flow
        ↓
立即展示本地消息
        ↓
调用 syncMessages(conversationId)
        ↓
检查父会话是否存在
        ├── 不存在：先全量同步 conversations
        └── 存在：继续
        ↓
读取该会话的 message cursor
        ↓
从服务端拉取全量或增量消息
        ↓
事务合并进 Room
        ↓
推进该会话的 cursor
```

必须先有会话，再插入消息，因为 `messages.conversation_id` 对 `conversations.id` 有外键约束。

### 3. 全量同步和增量同步

首次同步或本地缓存不可信时：

```
GET /api/conversations
GET /api/conversations/{id}/messages
```

后续同步：

```
GET /api/conversations?updatedAfter=100
GET /api/conversations/{id}/messages?updatedAfter=200
```

服务端返回：

```
{
  "items": [],
  "nextCursor": 250,
  "serverTime": 123456789
}
```

客户端会检查：

- `nextCursor` 不能小于旧 cursor。
- 每条数据的 `syncVersion` 必须大于请求 cursor。
- 每条数据的 `syncVersion` 不能超过 `nextCursor`。
- `serverTime` 必须有效。

只有 Room 合并成功后，才保存新的 cursor。

------

## 三、最新 SSE 与 Room 的配合

### 1. 用户发送消息

客户端先执行：

```
确保 conversation 已存在
        ↓
用户消息写入 Room
        ↓
立即把相同用户消息加入内存 _messages
        ↓
cancelAndJoin 暂停 Room 消息观察
        ↓
建立 SSE 连接
```

用户消息仍然要求先成功落库，内存发布只是为了不等待 Room 查询回流。

### 2. 收到正文 chunk

每个 `message` 事件到达后：

```
chunk
  ↓
追加到 StringBuilder
  ├── 每50ms：发布完整内存快照到 UI
  └── 每500ms：把完整正文 checkpoint 写入 Room
```

这里有两套独立频率：

| 通道            | 间隔   | 作用                     |
| --------------- | ------ | ------------------------ |
| 内存 UI         | 50 ms  | 保持约 20Hz 流式显示     |
| Room checkpoint | 500 ms | 支持进程死亡后的部分恢复 |

第一个 assistant chunk 会立即：

- 创建本地 assistant 消息。
- 写入第一份 Room checkpoint。
- 发布第一份 UI 快照。

### 3. 为什么流式期间暂停 Room Flow

旧方案是：

```
SSE chunk
  ↓
Room UPDATE
  ↓
InvalidationTracker
  ↓
重新查询当前会话全部消息
  ↓
映射 UI
  ↓
Compose 更新
```

即使使用 `conflate()`，前面的 Room UPDATE、表失效和 SQL 查询仍然已经发生。

最新方案在 SSE 开始前执行：

```
pauseMessageObservation()
```

内部使用：

```
cancelAndJoin()
```

它不仅发送取消信号，还等待 Room collector 真正退出，然后才开始 SSE。

因此流式期间：

```
Room checkpoint 仍然存在
但当前页面没有活跃的消息查询 collector
所以不会每500ms重新查询整段会话
```

### 4. 收到 Done

服务端的 `done` 事件包含：

```
conversationId
userMessageId
assistantMessageId
status
updatedAt
syncVersion
```

客户端先校验：

- `conversationId` 是否属于当前页面。
- `userMessageId` 是否属于当前请求。
- assistant ID、状态和同步版本是否合法。

然后执行：

```
发布最终内存正文
        ↓
将本地 assistant 消息写成终态
        ↓
本地临时 ID 接管服务端 assistantMessageId
        ↓
保存 syncVersion、serverUpdatedAt
        ↓
恢复 Room Flow
        ↓
Room 执行一次最终权威查询
        ↓
校准 _messages
```

### 5. Error、断网和异常关闭

以下路径都必须保存终态：

- 服务端发送 `error`
- 网络异常
- SSE 正常关闭但没有 `done`
- `done` 元数据与当前请求不匹配
- 终态处理过程中抛异常

规则是：

```
已有正文 → status=partial，可继续生成
没有正文 → status=failed，可重试
```

最后一定恢复 Room 消息观察，避免页面永久停留在纯内存状态。

### 6. App 在生成期间被杀死

内存中的：

- `StringBuilder`
- `_messages`
- `streamingMessageId`
- `StreamState`

都会丢失。

但 Room 最多每 500ms 保存一次 checkpoint。

App 重启后：

```
重新观察 Room
        ↓
发现最后一条 assistant 是 streaming/partial/failed
        ↓
恢复 accumulatedAnswer
        ↓
恢复 streamingMessageId、clientRequestId、userMessageId
        ↓
将意外中断的 streaming 转成 partial
        ↓
显示“继续生成/重新生成”
```

代价是进程突然死亡时，最多可能损失最后约 500ms 尚未 checkpoint 的文字。

------

# TODO 清单

## 1. 历史同步基础

- 

  能区分服务端权威数据源、Room 持久化消息源和 SSE 内存热状态。

- 

  能画出“服务端 → SyncCoordinator → Room → Flow → Compose”链路。

- 

  能说明首次全量同步和后续 cursor 增量同步的区别。

- 

  能解释为什么 cursor 必须由服务端单调递增生成。

- 

  能解释为什么 Room 写入成功后才能推进 cursor。

- 

  能解释为什么会话必须先于消息同步。

- 

  能解释网络失败时为什么不能清空 Room。

- 

  能解释 tombstone 如何让其他设备感知会话删除。

## 2. 用户和服务端隔离

同步 cursor 不能只按资源类型保存，至少要绑定：

```
userId + serverKey + conversationId
```

- 

  能解释切换账号后为什么不能复用旧 cursor。

- 

  能解释切换后端地址后为什么不能展示旧后端缓存。

- 

  能解释 Room 查询为什么同时限制 

  ```
  owner_user_id
  ```

   和 

  ```
  owner_server_key
  ```

  。

- 

  能解释同步完成前后为什么都要确认认证绑定没有变化。

## 3. SSE 与 Room

- 

  能解释为什么流式 UI 不再等待 Room Flow。

- 

  能解释为什么 Room 仍然保留 500ms checkpoint。

- 

  能解释 UI 50ms 和 Room 500ms 为什么必须解耦。

- 

  能解释为什么使用 

  ```
  SystemClock.elapsedRealtime()
  ```

   而不是系统墙上时间。

- 

  能解释为什么开始 SSE 前必须 

  ```
  cancelAndJoin()
  ```

   Room collector。

- 

  能解释终态后为什么要重新收集 Room Flow。

- 

  能解释进程死亡后如何从 partial checkpoint 恢复。

- 

  能解释 

  ```
  Done
  ```

   为什么必须携带服务端消息 ID 和 

  ```
  syncVersion
  ```

  。

## 4. 消息幂等与身份收敛

一次问答共享：

```
conversationId
clientRequestId
userMessageId
assistantMessageId
```

- 

  能解释重试时为什么要复用 

  ```
  clientRequestId
  ```

  。

- 

  能解释本地 assistant 临时 ID 为什么最终要替换成服务端 ID。

- 

  能解释历史同步和 SSE Done 同时到达时为什么可能发生主键冲突。

- 

  能解释 

  ```
  adoptServerIdentity()
  ```

   如何收敛本地行和服务端行。

- 

  能解释服务端 completed 消息为什么可以替换本地 resumable placeholder。

- 

  能解释服务端 partial/streaming 消息为什么不能随意覆盖本地更新的 chunk。

## 5. 错误与恢复

- 

  覆盖 Done、Error、异常关闭、网络异常和协程取消。

- 

  保证每个终态路径都恢复 Room observer。

- 

  保证最终内容绕过节流强制落盘。

- 

  保证空回答保存为 failed，已有内容保存为 partial。

- 

  验证继续生成会追加到原 partial，而不是覆盖成尾部增量。

- 

  验证重新生成不会留下重复 assistant placeholder。

------

# 重点踩过的坑

## 坑一：把“Room 是消息源”理解成每个 chunk 都必须经过 Room

Room 是持久化消息源，不代表高频流式动画必须由数据库驱动。

每个 chunk 都写 Room 会造成：

```
频繁 UPDATE
+ 不断增长的完整字符串复制
+ InvalidationTracker 表失效
+ 全会话重新查询
+ Entity 映射
+ Compose 状态更新
```

最终方案是：

```
流式正文：内存驱动
持久化恢复：Room checkpoint
终态校准：Room Flow
```

## 坑二：每 5 个 chunk 写一次 Room

chunk 不是稳定性能单位：

```
每秒10个chunk  → 每秒2次写入
每秒100个chunk → 每秒20次写入
```

换模型或服务端分片策略后，客户端负载会变化。

因此改成稳定的时间上限：

```
UI：最多每50ms一次
Room：最多每500ms一次
```

## 坑三：以为 `conflate()` 能解决 Room 高频查询

`conflate()` 只能让慢消费者跳过部分已经发出的结果。

它不能取消：

- 前面的 Room UPDATE。
- InvalidationTracker 通知。
- 上游 SQL 查询。
- Entity 构造。

最新方案直接在流式期间取消 Room collector，才真正切断这条查询链。

## 坑四：只取消 Room collector，不等待结束

仅调用 `cancel()` 后马上开始 SSE，旧 collector 可能尚未真正退出，第一个 checkpoint 仍可能触发一次查询。

所以必须：

```
cancelAndJoin()
```

等待 Room collector 完全结束后，再启动 SSE。

## 坑五：只在 Done 时写 Room

这样写入最少，但长回答生成期间如果进程被杀，所有正文都会丢失。

因此保留 500ms checkpoint，在性能和恢复能力之间折中。

## 坑六：Room checkpoint 覆盖内存中的更新正文

旧实现中，Room 可能发出较旧 checkpoint，短暂覆盖内存中更新的回答，出现文字“倒退”。

现在流式期间不收集 Room Flow，因此不存在旧数据库快照覆盖实时正文的问题。

## 坑七：SSE 关闭不代表回答正常结束

TCP/SSE 正常关闭，但没有收到 `done`，仍然属于协议不完整：

```
onClosed + 未见终态
  ↓
生成 retryable Error
  ↓
保存 partial/failed
```

不能把连接关闭直接当作生成成功。

## 坑八：SSE 无限缓冲掩盖下游变慢

原来的无限 Channel 会在消费者变慢时持续积压，最终带来不可控内存增长。

当前使用容量 512 的有界缓冲：

```
缓冲满
  ↓
取消 EventSource
  ↓
明确报错
  ↓
保存 partial
```

宁可明确失败，也不能静默丢失正文 chunk 或 `Done`。

## 坑九：历史同步和 SSE Done 可能同时写同一条消息

SSE 开始时，assistant 使用本地临时 UUID；服务端完成后又有正式 assistant ID。

如果历史同步先把服务端消息写入 Room，随后 Done 再把本地 ID 改成服务端 ID，就会发生主键冲突。

当前通过两层处理：

- 历史合并时根据 `clientRequestId` 查找本地 resumable placeholder。
- Done 时通过 `adoptServerIdentity()` 合并本地 ID 和服务端 ID。

最终 Room 中只能保留一条 assistant 消息。

## 坑十：会话使用 `REPLACE` 导致消息被级联删除

SQLite 的 `REPLACE` 可能是：

```
DELETE 旧 conversation
  ↓
ON DELETE CASCADE 删除 messages
  ↓
INSERT 新 conversation
```

因此：

- 新会话使用 `INSERT IGNORE`。
- 已有会话使用明确的 `UPDATE`。
- 服务端同步使用可控的 Upsert 和归属检查。

## 坑十一：先推进 cursor，再写 Room

如果 cursor 已更新，但 Room 写入失败，下次同步会跳过尚未落库的数据。

正确顺序：

```
校验服务端页面
  ↓
Room 事务合并成功
  ↓
保存 nextCursor
```

如果 cursor 保存失败，下一次最多重复合并，依靠 UUID 和 Upsert 保持幂等，不会丢数据。

## 坑十二：切换用户或后端时复用旧缓存

相同的 conversation UUID 在不同用户或不同后端上可能表示完全不同的数据。

因此同步和查询都必须绑定：

```
userId + serverKey
```

发现归属不一致时不能静默覆盖，需要拒绝写入或重新进行全量同步。

------

# 仍建议补强的 TODO

- 

  使用 WorkManager 增加后台同步和网络恢复后重试。

- 

  为本地已写入、但 SSE 请求尚未到达服务端的用户消息增加明确 outbox/pending 状态。

- 

  增加“历史同步先到、SSE Done 后到”和相反顺序的并发集成测试。

- 

  增加进程死亡发生在两个 checkpoint 之间的恢复测试。

- 

  增加切换账号、切换后端过程中同步返回的竞态测试。

- 

  增加 Room 写入成功但 cursor 保存失败时的重复同步测试。

- 

  根据低端真机数据继续验证 50ms/500ms 参数，而不只依赖模拟器基准。

```
Compose 观察 Room conversations Flow
        ↓
立即展示本地会话缓存
        ↓
Repository.syncConversations()
        ↓
从 labsage_history_cursors DataStore 读取 conversations cursor
        ↓
检查 cursor 是否属于当前 userId，
并确认 Room 中确实存在相应服务端缓存
        ├── 不可信：cursor=null，执行全量请求
        └── 可信：携带 updatedAfter=cursor，执行增量请求
        ↓
服务端返回 items + nextCursor
        ↓
验证每条 item.syncVersion 和 nextCursor
        ↓
Room 事务写入
        ↓
DataStore 保存 nextCursor
        ↓
Room Flow 通知 Compose 更新
```

## 最终面试版本

> LabSage 的服务端数据库是业务权威数据源，Room 是客户端的持久化缓存和空闲期消息源。页面打开时先观察 Room，立即展示本地历史，再通过服务端 cursor 执行全量或增量同步，网络结果事务性写入 Room，成功后才推进 cursor。最新 SSE 实现没有让每个 chunk 都经过 Room：生成期间先暂停当前页面的 Room collector，正文按顺序追加到内存 `StringBuilder`，每 50 毫秒发布一次 UI 快照，每 500 毫秒把完整正文作为恢复 checkpoint 写入 Room。收到 Done、Error 或连接异常时会绕过节流强制落盘，再恢复 Room Flow，执行一次权威查询校准内存状态。这样既避免了 Room 高频 UPDATE 引起的表失效和全会话查询，也保留了进程死亡后的 partial 恢复能力。历史同步与 SSE 通过 `clientRequestId`、服务端消息 ID 和 `syncVersion` 收敛为同一条消息，同时使用用户、后端地址和增量游标隔离不同账号的数据。

------

### 历史同步简介版

最开始聊天记录主要保存在本地，只能在当前设备查看。后来接入账号体系后，需要支持跨设备历史同步。

第一版同步采用全量拉取，实现简单，但每次都下载全部会话和消息。数据量增加以后，会产生重复传输和大量无效数据库写入。

后面我改成了“首次全量、后续增量”的方式。客户端为会话列表和每个会话分别保存同步游标，服务端数据发生变化时会生成递增的 syncVersion，客户端下次只拉取游标之后的数据（Q：如何保证现在只拉去游标之后的数据A：只请求游标之后的数据）。

这里没有直接使用客户端时间戳作为游标，因为不同设备的时间可能不一致，用户也可以修改系统时间。使用服务端维护的单调版本号，顺序和边界会更稳定。

删除数据也不能只从服务端查询结果中消失，否则增量同步时客户端不知道某条数据已经被删除。因此服务端会返回删除标记，客户端收到后再删除或隐藏对应的本地记录。

同步写入会放在 Room 事务中，并在提交前后检查账号是否仍然有效。这样可以避免同步进行到一半时用户退出登录，结果却把旧账号的数据写进新账号的缓存。

### 历史同步详细版

## 2.2 历史同步功能

两轮技术复核已完成：第一轮发现 3 处逻辑/范围问题并修正，第二轮结论为 `PASS`。以下为最终版本。

## 一、历史同步功能介绍

LabSage 采用“服务端权威数据源 + Room 本地缓存”的历史同步架构：

```
服务端数据库：业务权威数据源
Room：客户端业务缓存和界面数据来源
Preferences DataStore：保存同步进度 cursor
```

页面打开后不会等待网络请求完成，而是先观察 Room Flow，立即展示本地会话或消息。Repository 随后从服务端拉取最新数据，网络响应不会直接修改界面，而是先写入 Room，再由 Room Flow 通知 ViewModel 和 Compose 更新。

完整链路是：

```
Compose 观察 Room Flow
        ↓
立即显示本地缓存
        ↓
Repository 发起历史同步
        ↓
服务端返回最新数据
        ↓
写入 Room
        ↓
Room Flow 通知 UI 更新
```

这样即使网络较慢或同步失败，用户仍然可以查看已经保存在 Room 中的历史记录。

## 二、全量同步与增量同步

### 1. 全量同步

以下情况不能安全复用旧 cursor：

- 客户端还没有 cursor。
- cursor 对应的 Room 缓存已经不存在。
- Room 中没有已经同步过的服务端数据。
- 缓存所属用户与当前用户不一致。
- 缓存所属后端与当前后端不一致。
- 业务明确要求强制全量刷新。

此时请求不传递 `updatedAfter`：

```
GET /api/conversations
```

或者：

```
GET /api/conversations/{id}/messages
```

服务端返回当前同步范围内的完整数据。客户端在 Room 事务中对当前用户、当前后端、当前会话范围进行全量对账，而不是无条件清空整个 Room。

### 2. 增量同步

客户端已经拥有可信 cursor 时，将其作为 `updatedAfter` 发送：

```
GET /api/conversations?updatedAfter=100
```

或者：

```
GET /api/conversations/{id}/messages?updatedAfter=200
```

含义是：

```
客户端已经完整处理到版本100或200
只需要返回之后发生的变化
```

服务端响应：

```
{
  "items": [
    {
      "id": "conversation-id",
      "syncVersion": 105
    }
  ],
  "nextCursor": 105,
  "serverTime": 123456789
}
```

其中：

- `items`：本次需要合并的变化。
- `syncVersion`：某一条会话或消息自己的服务端版本。
- `nextCursor`：本次数据成功处理后，整个同步流可以确认到达的新位置。
- `serverTime`：服务端处理请求时的时间。

客户端将 `items` 合并进 Room，成功后再把 cursor 从 100 更新为 105。

## 三、同步时机

### 1. 进入会话列表

进入会话列表后：

```
观察 Room conversations Flow
        ↓
立即展示本地会话
        ↓
自动调用会话同步
        ↓
全量或增量拉取服务端会话
```

### 2. 进入聊天页面

进入某个会话后：

```
观察该会话的 Room messages Flow
        ↓
立即展示本地消息
        ↓
自动同步该会话消息
```

会话列表和每个会话的消息属于不同同步流，因此它们分别维护自己的 cursor。

### 3. 用户主动刷新或失败后重试

用户主动下拉刷新，或者上一次同步失败后点击重试时，会再次执行相应同步。

如果旧 cursor 和 Room 缓存仍然可信，则继续增量同步；否则退回全量同步。

### 4. 深链打开会话

如果用户通过通知或深链直接打开某个会话，但 Room 中还没有对应父会话：

```
先全量同步 conversations
        ↓
确认父会话存在并属于当前用户、当前后端
        ↓
再同步 messages
```

因为消息表依赖会话表，直接写入消息会违反外键或数据归属约束。

### 5. 缓存或绑定失效

如果同步开始前发现：

- cursor 仍然存在，但 Room 缓存已经被清空；
- 当前用户发生变化；
- 当前后端地址发生变化；
- 缓存归属与当前环境不一致；

客户端会放弃旧 cursor，重新执行全量同步。

## 四、Cursor 是什么

Cursor 可以理解为同步检查点：

> 它表示客户端已经完整处理了服务端哪个版本之前的所有变化。

例如：

```
cursor = 100
```

表示客户端已经确认处理完：

```
syncVersion ≤ 100
```

的这一段服务端变化。

Cursor 不是 Room 中某一条记录的最大版本，也不能根据单条记录的 `syncVersion` 自行推进。只有历史同步接口返回的 `nextCursor`，才能证明这个同步流已经完整处理到对应位置。

否则可能出现：

```
Room 中某条消息已经是 syncVersion=120
但版本101～119的其他变化还没有被拉取
```

此时不能直接把整体 cursor 设置为 120。

## 五、Cursor 存在哪里

Cursor 保存在 Preferences DataStore：

```
labsage_history_cursors
```

Room 与 DataStore 的职责不同：

| 数据                     | 存储位置  | 含义                       |
| ------------------------ | --------- | -------------------------- |
| 会话、消息               | Room      | 客户端业务缓存             |
| 单条记录的 `syncVersion` | Room      | 这条数据自身的服务端版本   |
| 会话 cursor              | DataStore | 会话同步整体进度           |
| 消息 cursor              | DataStore | 某个会话的消息同步进度     |
| 缓存 binding             | DataStore | 当前缓存属于哪个用户和后端 |

### 会话 Cursor

绑定维度：

```
userId + serverKey
```

表示当前用户在当前后端上的会话列表同步进度。

### 消息 Cursor

绑定维度：

```
userId + serverKey + conversationId
```

每个会话分别保存自己的消息 cursor。

这样可以避免：

- 用户 A 误用用户 B 的同步进度。
- 测试后端误用生产后端的同步进度。
- 会话 A 误用会话 B 的消息进度。

## 六、一次同步的完整提交过程

```
读取并验证当前身份和缓存 binding
        ↓
读取可信 cursor
        ↓
携带 updatedAfter 请求服务端
        ↓
服务端返回 items + nextCursor
        ↓
校验 nextCursor 没有倒退
        ↓
校验每条 syncVersion 大于请求起点
且不超过 nextCursor
        ↓
在 Room 事务中执行全量对账或增量合并
        ↓
Room 成功后，通过 DataStore edit 保存 nextCursor
        ↓
Room Flow 通知 UI 更新
```

需要特别注意：

> Room 和 DataStore 是两个独立存储，不能组成一个真正的跨存储事务。

因此项目通过提交顺序保证安全：

```
先写 Room
再推进 cursor
```

设计原则是：

> 宁可重复同步，也不能提前推进进度造成数据遗漏。

## 七、同步过程中可能出现的问题与解决方案

| 问题                          | 可能后果                           | 解决方案                                                   |
| ----------------------------- | ---------------------------------- | ---------------------------------------------------------- |
| 网络同步失败                  | 无法获取最新数据                   | 不清空 Room，继续展示本地缓存，只显示同步失败并允许重试    |
| 没有可信 cursor               | 增量同步可能跳过早期数据           | 不传 `updatedAfter`，执行全量同步                          |
| 先更新 cursor，后写 Room      | Room 失败后，下次会跳过本批数据    | 先事务写 Room，成功后才推进 cursor                         |
| Room 成功，但 cursor 保存失败 | 下次会重复拉取同一批数据           | 通过稳定 ID 和 Upsert 幂等合并，允许重复但不丢数据         |
| cursor 存在，但 Room 已清空   | 增量同步无法恢复 cursor 之前的数据 | 检查本地是否有已同步缓存，没有则退回全量                   |
| 用户或后端复用了旧 cursor     | 跳过数据或发生缓存污染             | 会话 cursor 绑定 `userId + serverKey`                      |
| 不同会话共用消息 cursor       | 某些会话消息被错误跳过             | 消息 cursor 额外绑定 `conversationId`                      |
| 网络请求期间切换账号或后端    | 旧请求响应可能写入新身份缓存       | 请求前和最终提交时再次检查 binding，不一致则拒绝提交       |
| 消息父会话不存在              | 违反外键或数据归属约束             | 先同步 conversations，再同步 messages                      |
| 服务端 `nextCursor` 倒退      | 客户端同步进度损坏                 | 写库前验证 `nextCursor ≥ requestedCursor`                  |
| 服务端数据版本越界            | 可能错误推进 cursor                | 检查每条 `syncVersion` 大于旧 cursor 且不超过 `nextCursor` |
| 多个同步任务同时运行          | Room 与 cursor 更新顺序混乱        | 使用 Mutex 串行化历史同步                                  |

## 八、为什么“Room 成功、Cursor 失败”可以接受

假设当前 cursor 是 100，服务端返回：

```
items：版本101～105
nextCursor：105
```

Room 写入成功，但 DataStore 保存 105 失败。

下次客户端仍会使用：

```
cursor = 100
```

于是服务端再次返回版本 101～105。

由于会话和消息使用稳定 ID，并通过 Upsert 合并，所以重复数据不会创建多份记录。

结果只是：

```
重复请求和重复合并一次
```

不会丢数据。

相反，如果先保存 cursor 105，再写 Room，而 Room 写入失败：

```
DataStore cursor = 105
Room 中没有版本101～105
```

下次客户端会从 105 之后开始请求，版本 101～105 就可能永久遗漏。

因此正确的故障方向必须是：

```
最多重复
不能遗漏
```

## 九、最终面试版本

> LabSage 的历史同步采用服务端权威、Room 本地缓存的结构。页面打开后不会等待网络，而是先观察 Room Flow，让用户立即看到本地历史，然后再从服务端同步最新数据。网络响应不会直接更新列表，而是先写入 Room，最终仍由 Room Flow 更新界面。同步分为全量和增量：没有可信 cursor，或者 cursor 对应的本地缓存已经失效时，不传 `updatedAfter` 做全量同步；有可信 cursor 时，把它作为 `updatedAfter` 发送，只拉取后续变化，服务端通过 `nextCursor` 返回新的同步高水位。Room 保存业务数据和每条记录自己的 `syncVersion`，整体 cursor 则保存在 Preferences DataStore；会话 cursor 绑定用户和后端，消息 cursor 还会绑定会话 ID，避免跨账号、跨后端或跨会话误用同步进度。提交时客户端会先校验响应，再在 Room 事务中对当前同步范围做全量对账或增量合并，只有 Room 成功后才推进 DataStore 中的 cursor。因为 Room 和 DataStore 不能组成跨存储事务，所以这个顺序可以保证 Room 写入失败时仍然能够重新拉取；即使 Room 已经成功但 cursor 保存失败，也只是下一次重复同步，通过稳定 ID 和 Upsert 幂等合并，不会丢数据。这套设计的核心原则就是宁可重复同步，也不能提前推进进度造成数据遗漏。网络失败时继续展示 Room 缓存；如果同步开始前发现缓存缺失或归属不匹配，就放弃旧 cursor 并退回全量同步；如果网络请求期间用户或后端发生变化，则拒绝提交这次旧响应。消息同步缺少父会话时，也会先重建会话缓存，再同步消息。



> 服务端没有使用时间戳生成同步游标，因为并发请求和时钟精度可能导致重复或乱序。后端维护了一张只有一行的序列表，每次业务数据发生变化时，都在数据库事务中通过 `value = value + 1 returning value` 原子获取新的 `sync_version`，并把它写入对应的会话或消息。数据库对同一行更新进行并发控制，因此不同请求不会获得重复版本号；业务写入失败时，序列更新也会随事务回滚。客户端只需要请求 `sync_version > cursor` 的数据，再将本次返回数据中的最大版本保存为新 cursor。版本号允许存在空洞，但必须保证不重复、不倒退，这就足以支持可靠的增量同步。



## 文件上传附录

#### （3）文件上传功能——详细介绍版

### 一、功能目标

LabSage 是一个带知识库检索能力的 AI 聊天应用。用户可以在聊天页面选择 Markdown 文件并上传，后端将文件保存到知识库目录，随后完成文档解析、分块、向量化和 Milvus 入库。后续聊天时，Agent 就可以从这些文件中检索相关内容。

整个链路可以分为四层：

```
Android 系统文件选择器
        ↓
客户端流式构造 multipart
        ↓
Go 后端保存文件并建立索引
        ↓
Milvus 提供知识库检索
```

上传接口为：

```
POST /api/upload
Content-Type: multipart/form-data
Authorization: Bearer <access-token>
```

multipart 的字段名固定为：

```
file
```

上传成功后，服务端返回：

```
{
  "fileId": "notes.md",
  "fileName": "notes.md",
  "filePath": "data/notes.md",
  "fileSize": 102400
}
```

其中 `fileId` 当前就是文件名，客户端把它当成不透明标识，用于后续列表展示和删除。

### 二、最初实现及其问题

旧版上传链路比较直接：

```
Content Uri
    ↓
复制到 cache 临时文件
    ↓
readBytes()
    ↓
完整 ByteArray
    ↓
RequestBody
    ↓
multipart 上传
```

这套方案功能上可以工作，但存在明显的资源浪费。

假设上传一个 10 MiB 文件：

1. cache 目录先保存一份约 10 MiB 的临时文件；
2. `readBytes()` 再申请一个至少 10 MiB 的 ByteArray；
3. 请求构造和网络写出过程中还可能产生额外缓冲或复制。

所以文件大小只有 10 MiB，Java 堆峰值却可能超过文件本身两倍。多次上传、低内存设备或者应用同时执行聊天、Room 查询时，都可能明显增加 GC 压力，甚至引发 OOM。

除此之外，Android 文件选择器还带来了几个容易忽略的问题：

- 返回的是 `content://` Uri，不一定对应真实文件路径；
- 不能直接使用 `File(uri.path)` 读取；
- 文档提供方声明的 MIME 不一定准确；
- 有些提供方不返回文件大小；
- 有些提供方返回的大小与实际内容不一致；
- Access Token 过期后，请求可能被 Authenticator 重放。

因此，文件上传不能只解决“把文件发出去”，还要处理内存、文件元数据、认证重试和异常清理。

### 三、优化后的整体思路

优化后的核心思路可以概括为三点：

```
不把整个文件读入内存
对不可信的文件元数据设置边界
让 RequestBody 支持安全重放
```

客户端会先读取文件名和大小，然后分成“大小已知”和“大小未知”两条链路：

```
选择 Content Uri
        ↓
读取 DISPLAY_NAME 和 SIZE
        ↓
校验扩展名、空文件和 10 MiB 上限
        ↓
SIZE 是否可信、可用？
       ↙               ↘
    已知大小            未知大小
       ↓                  ↓
流式读取 Content Uri    有界复制到临时文件
       ↓                  ↓
StreamingRequestBody    File RequestBody
       ↘                 ↙
          multipart 上传
```

### 四、文件选择与元数据读取

聊天页面使用 Android Activity Result API 打开系统文件选择器：

```
ActivityResultContracts.GetContent()
```

选择器使用：

```
*/*
```

而不是只传 `text/markdown`。

原因是不同文件提供方对 Markdown 的 MIME 标记并不统一。有些会返回：

```
text/markdown
text/plain
application/octet-stream
```

如果只允许 `text/markdown`，用户明明选择了合法的 `.md` 文件，也可能在部分文件管理器中看不到它。

因此，当前实现让系统选择器先展示所有文件，再通过 ContentResolver 查询：

```
OpenableColumns.DISPLAY_NAME
OpenableColumns.SIZE
```

客户端随后根据文件名判断是否以 `.md` 或 `.markdown` 结尾，并执行以下校验：

- 扩展名必须是 `.md` 或 `.markdown`；
- 已知大小不能为 0；
- 文件最大不能超过 10 MiB；
- 负数 size 按“未知大小”处理。

需要注意，这里的扩展名校验主要用于客户端体验，并不等于真正的文件安全检查。文件名可以伪造，可信的生产方案仍然需要服务端重复执行类型、大小和内容限制。

### 五、已知大小文件：直接流式上传

如果 ContentResolver 能提供有效 size，客户端不会创建临时文件，也不会调用 `readBytes()`，而是构造自定义的 `StreamingRequestBody`。

它会向 OkHttp 声明：

```
Content-Type: text/markdown; charset=utf-8
Content-Length: 文件大小
```

当 OkHttp 真正调用 `writeTo()` 时，再打开 ContentResolver 的 Source：

```
ContentResolver.openInputStream(uri)
        ↓
Okio Source
        ↓
固定大小 Buffer
        ↓
BufferedSink
```

每次只处理固定缓冲区大小的数据，因此 Java 堆占用不会随着文件大小线性增长。

这条链路的主要收益是：

- 不创建文件大小级别的 ByteArray；
- 已知大小文件不需要额外临时文件；
- 上传过程中只保留少量缓冲数据；
- 文件越大，内存优势越明显。

### 六、为什么 RequestBody 必须支持重放

普通请求会携带 Access Token。如果 Token 已经过期，流程可能是：

```
上传请求携带旧 Access Token
        ↓
服务端返回 401
        ↓
Authenticator 使用 Refresh Token 刷新
        ↓
使用新 Access Token 重试原上传请求
```

这意味着同一个 RequestBody 的 `writeTo()` 可能执行不止一次。

如果 RequestBody 在创建时只持有一个 InputStream：

```
第一次上传：InputStream 被读取完
第二次重试：InputStream 已耗尽或关闭
```

刷新 Token 虽然成功，但第二次请求可能发送空文件或者直接失败。

因此，`StreamingRequestBody` 保存的不是某一个已经打开的流，而是“如何重新打开 Source”的函数。每次执行 `writeTo()` 时都会重新调用 ContentResolver：

```
StreamingRequestBody(size, mediaType) {
    contentResolver.openInputStream(uri)!!.source()
}
```

这样无论请求首次发送还是认证后重试，都能从头读取完整文件。

### 七、如何处理文件大小不准确

文档提供方返回的 size 不能完全信任。

如果声明大小为 5 MiB，但实际内容只有 4 MiB，RequestBody 不能假装已经发送了 5 MiB；如果实际内容超过 5 MiB，也不能悄悄截断后当成成功。

因此流式写出时会累计实际字节数：

```
声明长度 = length
实际写出 = written
```

写出结束后执行两类检查：

1. 如果 `written != length`，说明文件比声明长度短，抛出 `ProtocolException`；
2. 写满声明长度后再额外探测一个字节，如果仍有数据，说明文件比声明长度长，同样终止请求。

这样可以避免错误的 Content-Length 让后端收到不完整文件，而客户端却认为上传成功。

### 八、未知大小文件：有界临时文件

如果文档提供方没有返回 size，客户端无法提前提供准确的 Content-Length。

此时不能直接无上限复制，更不能重新使用 `readBytes()`。当前实现会采用有界临时文件：

```
Content Uri
    ↓
固定大小 ByteArray 缓冲区
    ↓
逐段复制并累计 copied
    ↓
达到 10 MiB 后继续出现数据
    ↓
立即抛出 UploadTooLargeException
```

复制过程最多只使用固定大小缓冲区，不会一次性分配整个文件大小的内存。

复制成功后，再使用：

```
tempFile.asRequestBody(mediaType)
```

构造可重放的 RequestBody。文件 RequestBody 天然可以被重复打开，因此同样支持 401 后重试。

未知大小文件虽然仍需要一份临时磁盘，但这是为了获得可靠长度并支持请求重放。相比旧方案，它仍然消除了整文件 ByteArray。

### 九、临时文件清理

临时文件必须覆盖所有退出路径：

- 上传成功；
- 上传失败；
- 文件过大；
- 文件为空；
- ContentResolver 读取失败；
- 协程被取消；
- 认证刷新失败。

因此临时文件会在 `finally` 中清理，并使用：

```
NonCancellable + Dispatchers.IO
```

原因是如果上传协程已经取消，普通挂起清理操作也可能立即被取消，导致临时文件残留。使用 NonCancellable 不是为了继续上传，而只是确保短暂的本地清理能够执行完成。

### 十、客户端状态管理

ViewModel 使用一个明确的状态机表示上传过程：

```
Idle
  ↓
Uploading
 ↙       ↘
Success   Error
```

进入 `Uploading` 后，上传按钮会被禁用，避免用户连续点击造成同一文件并发上传。

上传完成后，客户端会尝试在当前会话中写入一条类似下面的本地提示：

```
Uploaded: notes.md (100 KB)
```

这里有一个容易混淆的状态问题：

```
远端文件上传成功
        ↓
本地提示消息写入 Room 失败
```

远端上传和本地提示不是同一个业务结果。文件已经保存在后端并完成索引，就应该显示 Success；不能因为一条辅助提示写入失败，把整个上传显示成失败，否则用户重试后可能重复上传。

因此当前实现先将状态设置为 Success，再尽力写入提示消息。提示写入失败不会覆盖远端成功状态。

当前 UI 只有“正在上传、成功、失败”状态，还没有逐字节进度百分比。如果以后需要精确进度，可以在 RequestBody 写入时累计已发送字节，并通过节流后的回调更新 StateFlow。

### 十一、客户端网络与认证链路

Retrofit 将文件包装成：

```
MultipartBody.Part.createFormData(
    "file",
    fileName,
    requestBody
)
```

普通 API 客户端会自动：

1. 通过拦截器附加 Access Token；
2. 收到 401 后通过 Authenticator 刷新 Token；
3. 使用新 Token 最多重试一次原请求；
4. 复用可重放的 RequestBody 再次发送文件。

普通 API 当前的连接、读取和写入超时都是 30 秒。

因为上传接口还要等待服务端执行 Embedding 和 Milvus 写入，如果索引耗时超过客户端读取超时，就可能出现：

```
客户端显示超时失败
但后端仍在处理，甚至已经完成
```

这是一种结果不确定性。更成熟的设计应该把上传和索引拆开：

```
上传文件
    ↓
服务端立即返回 fileId 和 indexing 状态
    ↓
后台异步建立索引
    ↓
客户端查询状态或接收通知
```

### 十二、后端处理流程

`POST /api/upload` 位于统一的 JWT 认证中间件后，因此请求必须携带有效 Access Token。

后端收到 multipart 后执行：

```
读取字段 file
        ↓
确认知识库目录存在
        ↓
保留原文件名写入磁盘
        ↓
读取文件大小
        ↓
加载 Markdown 文件
        ↓
文档分块
        ↓
Embedding
        ↓
删除同 source 的旧向量
        ↓
Upsert 新向量
        ↓
Flush Milvus
        ↓
返回上传结果
```

后端会使用：

```
filepath.Join(common.FileDir, savedName)
```

生成保存路径，并将这个路径写入文档元数据的 `_source`。

`_source` 是后续索引去重和删除的关键。重新上传同名文件时，Indexer 会先删除相同 source 的旧向量，再写入新的分块。成功完成后，知识库中不会同时保留同一文件的新旧版本。

后端只有在索引流水线执行成功后才返回上传成功。因此客户端收到 Success 时，可以认为文件已经落盘并完成当前索引流程，而不是只上传到了临时目录。

### 十三、为什么文件名可以作为 fileId

当前后端没有单独的文件元数据表，也没有 UUID 到文件路径的映射关系。

文件的主要标识来自：

```
文件名
文件保存路径
metadata._source
```

因此当前直接把文件名作为稳定 `fileId`：

```
fileId = fileName
```

这样做的好处是：

- 不需要新增文件注册表；
- 同名上传自然表示替换；
- 文件列表可以直接扫描上传目录；
- 删除接口可以根据 fileId 定位文件；
- 向量可以根据相同 source 清理。

Android 端把 fileId 当成不透明字符串，不自行解析它。为了兼容未升级的旧后端，上传响应中的 `fileId` 在客户端模型中暂时允许为空；没有稳定 ID 的文件不能执行删除。

### 十四、文件列表为什么没有使用 Room

聊天记录需要离线查看，因此适合放入 Room；知识库文件列表当前以服务端磁盘为权威，客户端没有建立本地文件表。

打开文件管理页时，客户端直接请求：

```
GET /api/files
```

后端扫描知识库目录中的顶层普通文件，跳过子目录和点文件，再按修改时间倒序返回。

这样可以避免维护“Room 文件记录存在，但服务端文件已经不存在”的第二套状态。代价是文件管理页离线时不能展示旧列表。

### 十五、上传过程中踩过的坑与解决方案

| 问题                        | 可能后果                     | 当前解决方案                           |
| --------------------------- | ---------------------------- | -------------------------------------- |
| 把 Content Uri 当成本地路径 | 部分文档提供方无法读取       | 始终通过 ContentResolver 打开          |
| MIME 声明不可靠             | 合法 Markdown 无法选择       | 选择器使用 `*/*`，选择后校验扩展名     |
| `readBytes()` 读取整个文件  | 堆内存随文件大小增长         | 已知大小使用 StreamingRequestBody      |
| 已知大小也先复制临时文件    | 额外占用同等大小磁盘         | 直接从 ContentResolver 流向 OkHttp     |
| 提供方不返回 size           | 无法声明 Content-Length      | 有界复制到临时文件                     |
| 未知大小文件无限复制        | 超大文件撑满缓存目录         | 复制过程中强制执行 10 MiB 上限         |
| 提供方 size 与内容不一致    | 截断、空文件或协议错误       | 对实际写出长度进行前后校验             |
| RequestBody 只持有一次性流  | 401 刷新后无法重试           | 每次 `writeTo()` 重新打开 Source       |
| 协程取消后临时文件残留      | cache 持续积累垃圾           | NonCancellable IO 清理                 |
| 用户连续点击上传            | 同一文件并发提交             | Uploading 状态期间禁用按钮             |
| 远端成功、本地提示失败      | UI 错误显示上传失败          | 以远端成功为权威，提示消息尽力写入     |
| 同名文件重复建立索引        | 检索结果出现新旧重复         | 按 `_source` 删除旧向量后再 Upsert     |
| 文件落盘但索引失败          | 磁盘和向量库暂时不一致       | 接口返回失败，允许同名重传恢复         |
| 索引时间超过客户端超时      | 客户端和服务端结果认知不一致 | 当前暴露失败；长期方案是异步索引状态机 |

### 十六、量化结果

项目对旧链路和流式链路做了 Android ART A/B 基准测试。

旧链路：

```
源文件
  → cache 临时文件
  → readBytes()
  → ByteArray RequestBody
  → sink
```

新链路：

```
源文件
  → StreamingRequestBody
  → 固定缓冲区
  → sink
```

在 1、5、10 MiB 文件上，各执行预热和 30 次正式测量，共得到 180 条正式样本。

10 MiB 文件结果如下：

| 指标              | 旧实现    | 新实现   | 变化                 |
| ----------------- | --------- | -------- | -------------------- |
| Java 堆增量中位数 | 22.98 MiB | 208 KiB  | 下降约 99.1%         |
| 额外临时磁盘      | 10 MiB    | 0        | 已知大小链路完全消除 |
| 管线耗时中位数    | 48.48 ms  | 20.21 ms | 下降约 58.3%         |

所有样本还校验了：

- 最终写出的字节数；
- 输出 SHA-256；
- 是否与原文件完全一致。

需要强调，这里的耗时只包含客户端准备文件和 RequestBody 写出，不包含网络 RTT、Embedding 和 Milvus，因此不能把它解释为线上上传接口总耗时下降 58.3%。

### 十七、当前实现的工程边界

#### 1. 客户端校验不是安全边界

目前 `.md/.markdown` 和 10 MiB 上限主要由 Android 客户端检查，Go 上传 Handler 没有显式执行完全相同的限制。

正常 App 请求会经过客户端校验，但攻击者可以绕过 App 直接调用接口。因此生产环境还应该在后端补充：

- HTTP Body 总大小限制；
- 单文件大小限制；
- 扩展名白名单；
- 必要的内容类型或内容检查；
- 上传目录容量和配额限制。

#### 2. 磁盘与 Milvus 不是同一个事务

可能出现：

```
文件落盘成功
    ↓
Embedding 或 Milvus 失败
    ↓
接口返回失败
    ↓
磁盘文件仍然存在
```

同名重传通常可以重新建立索引，但这不是严格的事务恢复。

更完整的方案应该增加文件元数据表：

```
uploaded → indexing → ready
                    ↘ failed
```

后台任务根据状态重试，客户端可以查询真实索引进度。

#### 3. 当前文件命名空间没有按用户隔离

虽然 `/api/upload` 需要 JWT，但 Handler 当前没有使用 JWT 中的 userId 划分文件目录。所有用户仍然共享同一个知识库目录和文件名空间。

这意味着两个用户上传同名文件时可能互相覆盖，文件列表也不是用户私有列表。

如果要支持真正的多租户知识库，需要改成：

```
userId + fileId
```

作为文件归属，或者按照：

```
data/{userId}/{fileName}
```

组织目录，同时让列表、删除和 Milvus `_source` 都校验用户身份。

#### 4. 同步索引带来超时和结果不确定性

上传接口需要等待完整索引完成，而客户端普通请求的读取超时是 30 秒。文档较大、Embedding 服务较慢或 Milvus 繁忙时，可能出现客户端超时但后端最终完成的情况。

生产方案更适合使用异步任务：

```
上传成功 → 返回 fileId
索引后台执行 → 状态变成 ready/failed
```

### 十八、总结

LabSage 的文件上传功能表面上是一个 multipart 请求，实际需要同时解决 Android Content Uri、文件大小不可信、整文件入堆、401 请求重放、临时文件清理以及后端索引一致性等问题。

客户端最终使用两条上传路径：

- 已知大小文件直接通过可重放的 `StreamingRequestBody` 流向 OkHttp；
- 未知大小文件通过带 10 MiB 上限的临时文件完成长度确认和安全重放。

后端则负责文件落盘、文档分块、Embedding、Milvus 去重和 Upsert，并在索引完成后返回结果。

这次优化在不改变 multipart 接口的前提下，将 10 MiB 文件的客户端峰值堆增量从约 22.98 MiB 降到 208 KiB，同时消除了已知大小文件的临时磁盘占用。

工程上仍然需要继续完善服务端文件校验、异步索引状态和多用户文件隔离，但当前实现已经把客户端上传热路径中的内存风险、认证重试和异常清理问题处理完整。

### 请求 ID 与服务端消息身份收敛

这部分主要解决两个问题：

1. 网络重试不能让服务端重复生成同一轮回答；
2. 客户端的临时 assistant 消息和服务端正式消息最终只能保留一条。

如果只依赖消息内容或者创建时间判断“是不是同一条消息”，在 SSE 重连、请求重试和历史同步并发执行时，很容易出现两条相同回答。

#### 一、一轮对话里有哪些 ID？

用户发送一次问题时，客户端会生成两个 UUID：

```
clientMessageId：用户消息的稳定 ID
clientRequestId：这一轮请求的幂等 ID
```

另外，assistant 还会经历两个身份：

```
localAssistantId：客户端流式占位消息的临时 ID
assistantMessageId：服务端创建的正式消息 ID
```

它们的职责不同：

| 标识                 | 作用                                         |
| -------------------- | -------------------------------------------- |
| `clientMessageId`    | 固定用户消息身份，重试时仍指向同一条用户消息 |
| `clientRequestId`    | 标识一次完整问答请求，关联 user 和 assistant |
| `localAssistantId`   | 客户端收到首个 chunk 后创建的本地占位消息 ID |
| `assistantMessageId` | 服务端为 assistant 消息生成的最终权威 ID     |
| `syncVersion`        | 判断服务端数据的新旧，不负责标识消息身份     |

其中，user 和 assistant 两条消息都会保存相同的 `clientRequestId`：

```
clientRequestId = R1

user message：
    id = U1
    clientRequestId = R1

assistant message：
    id = A1
    clientRequestId = R1
```

所以 `clientRequestId` 表示的是“一轮问答”，不是某一条具体消息。

#### 二、客户端发送时如何建立身份

用户点击发送后，客户端首先创建：

```
currentUserMessageId = U1
currentClientRequestId = R1
```

接着按以下顺序执行：

```
创建或确认父会话
        ↓
把用户消息 U1 写入 Room
        ↓
直接把 U1 发布到内存消息列表
        ↓
暂停 Room 消息流观察
        ↓
携带 U1 + R1 建立 SSE 请求
```

请求内容相当于：

```
{
  "conversationId": "C1",
  "clientMessageId": "U1",
  "clientRequestId": "R1",
  "question": "用户的问题"
}
```

这里先把 user 消息写入 Room，是为了保证发送动作本身有本地记录；直接更新内存列表，则是为了让用户不用等待 Room Flow 回流就能看到自己的消息。

assistant 的本地占位消息不会在点击发送时立即创建，而是在收到第一个正文 chunk 时创建。这样如果请求在返回任何内容前就失败，不会额外留下一个空 assistant 气泡。

#### 三、服务端如何用 clientRequestId 保证幂等

服务端收到请求后，不会立即调用模型，而是先在数据库事务中执行 `ChatTurns.Begin()`。

这个事务会：

```
校验会话属于当前 JWT 用户
        ↓
查询 userId + clientRequestId 是否已经存在
        ↓
不存在：一次性预留 user 和 assistant 两条消息
存在：判断这是合法重试还是 ID 冲突
```

服务端数据库对消息设置了类似下面的唯一约束：

```
userId + clientRequestId + role
```

因此，同一个用户、同一个请求 ID 最多只能存在：

```
一条 user 消息
一条 assistant 消息
```

服务端会直接使用客户端传来的 `clientMessageId` 作为 user 消息 ID，同时自己生成正式的 `assistantMessageId`。

例如：

```
客户端发送：
clientMessageId = U1
clientRequestId = R1

服务端保存：
user.id = U1
assistant.id = S1
两条消息的 clientRequestId 都是 R1
```

assistant 消息会在模型真正执行前就被预留。这意味着即使两个相同请求并发到达，数据库唯一约束仍然可以阻止创建第二条 assistant。

#### 四、相同请求重试时服务端如何处理

假设请求已经到达服务端，但客户端因为网络断开没有收到结果，于是重新发送：

```
第一次：U1 + R1
第二次：U1 + R1
```

服务端发现 `R1` 已经存在后，会校验：

- 是否仍属于同一个用户；
- conversationId 是否相同；
- clientMessageId 是否仍为 U1；
- question 是否相同。

如果这些内容一致，说明这是同一请求的合法重试，而不是一条新问题。

服务端根据 assistant 当前状态处理：

```
pending / streaming
    → 返回 409，表示这一请求仍在执行
    → 不再启动第二次模型生成

completed
    → 直接重放已经保存的回答和 Done
    → 不重新调用模型

partial / failed + Continue
    → 在原 requestId 上继续生成
    → 不创建新的问答记录
```

如果相同 `clientRequestId` 被拿来发送另一段问题，服务端会返回冲突，而不是覆盖原来的记录。

因此，`clientRequestId` 解决的是服务端业务操作幂等：

> 同一次逻辑请求，无论客户端重试多少次，都不能重复创建消息或者重复调用模型。

#### 五、为什么客户端还需要本地 assistant 占位 ID

服务端在请求开始时已经创建了 assistant 正式记录，但客户端建立 SSE 后，最先收到的是正文 chunk，而不是服务端的 assistant ID。

因此客户端收到第一个 chunk 时，会先创建一个本地占位：

```
localAssistantId = L1
clientRequestId = R1
status = streaming
syncVersion = 0
```

后续 chunk 都覆盖更新 L1：

```
L1："这"
L1："这是"
L1："这是一个"
L1："这是一个回答"
```

它始终是同一条 Room 记录，不会每个 chunk 创建一条消息。

创建占位消息时，DAO 不只是按照 ID 插入，还会先查询：

```
conversationId + role=assistant + clientRequestId
```

如果 Room 中已经存在相同请求的 assistant，就复用现有记录，而不是再创建 L1。

这一层主要防止：

- SSE 重试时重新创建占位消息；
- 进程恢复后 Continue 又插入一条 assistant；
- 历史同步已经提前写入服务端消息，首个 chunk 又创建本地消息。

#### 六、SSE Done 如何完成身份收敛

服务端完成回答后，会发送结构化 Done：

```
{
  "conversationId": "C1",
  "userMessageId": "U1",
  "assistantMessageId": "S1",
  "status": "completed",
  "syncVersion": 105,
  "updatedAt": 123456789
}
```

客户端先校验：

```
conversationId == 当前会话 C1
userMessageId == 当前发送的 U1
```

如果不一致，说明终态事件不属于当前请求，不能直接提交。

校验通过后，客户端先把最终正文写入当前占位消息 L1，然后执行身份收敛：

```
本地临时身份 L1
        ↓
服务端正式身份 S1
```

理想情况下，Room 中只有 L1，那么可以直接把主键从 L1 更新为 S1，同时写入：

```
status = completed
syncVersion = 105
serverUpdatedAt = ...
```

最终 Room 中只剩：

```
assistant.id = S1
clientRequestId = R1
content = 最终回答
```

这一步完成以后，通知、历史同步以及其他设备看到的都是同一个服务端消息 ID。

#### 七、为什么不能直接 Insert 服务端消息再删除占位

因为 Done 到达时，历史同步也可能正在执行。

可能出现两种顺序。

##### 情况一：Done 先到

```
Room 中存在本地占位 L1
        ↓
Done 返回服务端 ID S1
        ↓
L1 改名为 S1
        ↓
历史同步再次返回 S1
        ↓
按照 S1 Upsert
```

这种情况比较简单，最终只有 S1。

##### 情况二：历史同步先到

```
Room 中已经存在本地占位 L1
        ↓
历史同步提前拉到服务端消息 S1
        ↓
Done 随后要求 L1 收敛为 S1
```

此时如果直接执行：

```
UPDATE messages SET id = 'S1' WHERE id = 'L1'
```

就会因为 S1 已经存在而触发主键冲突。

如果简单 Insert S1，又不处理 L1，界面则会出现两条 assistant：

```
L1：客户端流式占位
S1：历史同步返回的服务端消息
```

所以身份收敛必须是一个 Room 事务，并且先检查 L1 和 S1 当前是否存在。

#### 八、Room 的身份收敛事务如何处理不同情况

`adoptServerIdentity()` 会在事务中处理三类情况。

##### 1. 本地占位存在，服务端记录不存在

```
L1 存在
S1 不存在
```

直接把 L1 的主键改为 S1：

```
L1 → S1
```

正文仍保留在同一条记录中，不需要先删除再插入。

##### 2. 本地占位和服务端记录都存在

```
L1 存在
S1 也存在
```

这时根据服务端版本和终态判断谁更可信。

如果 S1 的 `syncVersion` 更新，或者 S1 已经是服务端确认的 completed：

```
删除 L1
保留 S1
只补充 S1 的终态元数据
```

如果现有 S1 比 Done 返回的版本旧，而 L1 保存着更新、更完整的客户端正文：

```
删除旧 S1
把 L1 的主键改成 S1
```

无论走哪条分支，事务结束后都只能保留一条 S1。

##### 3. 本地占位已经不存在

这通常说明历史同步已经完成了占位清理：

```
L1 不存在
S1 存在
```

此时不再尝试改主键，只更新 S1 的状态、`syncVersion` 和服务端更新时间。

更新条件还会防止：

- 用旧 `syncVersion` 覆盖新版本；
- 用 partial 状态把服务端 completed 降级；
- 用空终态覆盖已经同步到的完整正文。

#### 九、历史同步如何识别本地占位冲突

服务端同步回来的 Message DTO 也包含 `clientRequestId`。

客户端合并历史消息前，会同时比较：

```
conversationId
role
clientRequestId
serverId
```

如果服务端消息 S1 和本地占位 L1 具有相同的：

```
conversationId = C1
role = assistant
clientRequestId = R1
```

就说明它们不是两次回答，而是同一逻辑消息的两个身份。

根据服务端消息状态进行处理。

##### 服务端已经 completed

服务端终态是权威结果：

```
删除本地占位 L1
Upsert 服务端消息 S1
```

之后即使 Done 再到，身份收敛逻辑也会发现 L1 已不存在，只更新 S1，不会重新生成占位。

##### 服务端仍是 streaming、partial 或 failed

此时客户端可能正在持有比服务端历史接口更新的流式正文。如果立即用服务端记录替换 L1，可能导致：

- 客户端刚收到的 chunk 被旧服务端快照覆盖；
- 页面正文回退；
- L1 和 S1 同时出现。

所以当前策略是暂时跳过这条非终态服务端消息，继续保留本地 L1，等 Continue 或 Done 再把 L1 正式收敛到 S1。

#### 十、三个身份层分别解决什么问题

完整设计可以理解为三层保证。

##### 第一层：clientRequestId

解决逻辑请求重复：

```
同一个问题重试
→ 服务端不会重复调用模型
→ 不会重复创建 user/assistant
```

##### 第二层：稳定消息 ID

解决具体记录身份：

```
clientMessageId
→ 固定 user 消息身份

assistantMessageId
→ 固定 assistant 的服务端权威身份
```

##### 第三层：syncVersion

解决并发更新的新旧顺序：

```
相同 serverId 出现多个状态
→ 使用 syncVersion 判断谁更新
→ completed 服务端记录不能被旧 partial 降级
```

因此：

```
clientRequestId 判断“是不是同一轮”
messageId 判断“是不是同一条”
syncVersion 判断“哪个状态更新”
```

三者不能互相替代。

#### 十一、完整请求流程

```
客户端生成：
userMessageId = U1
clientRequestId = R1
        ↓
用户消息 U1 写入 Room
        ↓
请求携带 U1 + R1 到服务端
        ↓
服务端事务预留：
user = U1
assistant = S1
两者 requestId 都是 R1
        ↓
客户端收到首个 chunk
        ↓
创建或复用本地 assistant 占位 L1
requestId = R1
        ↓
后续 chunk 持续覆盖 L1
        ↓
历史同步如果提前返回 S1
根据 requestId 和状态合并或暂缓
        ↓
Done 返回 U1 + S1 + syncVersion
        ↓
校验 conversationId 和 U1
        ↓
Room 事务执行 L1 → S1
        ↓
恢复 Room Flow
        ↓
历史同步继续按 S1 Upsert
```

最终本地和服务端收敛为：

```
user：
id = U1
clientRequestId = R1

assistant：
id = S1
clientRequestId = R1
syncVersion = 服务端版本
```

#### 十二、面试版总结

这套设计的核心是把“请求幂等”和“消息身份”分开处理。客户端为每轮问答生成 `clientRequestId`，同时为 user 消息生成稳定的 `clientMessageId`；服务端使用 `userId + clientRequestId + role` 的唯一约束，在模型执行前原子预留 user 和 assistant 两条记录，因此请求重试不会重复生成。

客户端流式期间会先使用本地 assistant 占位 ID，但占位消息同样携带 `clientRequestId`。SSE Done 返回正式的 `assistantMessageId` 和 `syncVersion` 后，Room 会在事务中把本地身份收敛到服务端身份。

如果历史同步先写入了服务端消息，收敛逻辑会根据消息是否存在、服务端状态和 `syncVersion` 决定删除占位、保留服务端记录，或者把本地正文迁移到正式 ID。历史同步本身也会通过 `clientRequestId` 识别“本地占位和服务端消息其实是同一轮回答”。

最终，`clientRequestId` 判断是不是同一轮请求，messageId 标识具体消息，`syncVersion` 判断哪个状态更新。三层配合后，即使请求重试、SSE Done 和历史同步交错到达，Room 中最终也只会保留一条服务端正式 assistant 消息。



## 通知设计

这个功能主要解决的是长回答等待问题。用户发出问题以后，可能切到其他页面甚至暂时离开应用。如果只能一直盯着聊天界面，长回答的等待体验会比较差。

**最直接的做法，是回答结束后发送通知，点击时只携带 conversationId 打开对应会话。**

但这样只能做到“打开会话”，不能做到“找到回答”。

一段对话可能已经很长，用户进入会话后仍然要自己向下寻找。另外，应用冷启动和 Activity 已经存在时，通知 Intent 的处理入口也不一样：冷启动进入 `onCreate()`，已有 Activity 被复用时进入 `onNewIntent()`。如果只处理其中一个，通知跳转就会时好时坏。

**当前实现把通知目标从会话级提升到了消息级，并且会等目标消息真正进入页面的可观察状态后再定位。**

应用启动时会创建“长回答完成”的 NotificationChannel。Android 13 以上需要 `POST_NOTIFICATIONS` 权限，但项目没有在应用冷启动时直接索取，而是在用户开始生成回答、这个功能真正变得相关时再申请。

回答完成后也不是每次都通知。当前会统计去掉空白字符后的正文长度，只有达到 300 个字符的完整回答才发送通知：

```
短回答、partial、failed → 不通知
完整长回答 → 可以通知
```

通知摘要会去掉标题符号、代码围栏、链接等常见 Markdown 标记，并限制在约 180 个字符，避免通知栏中出现大量格式字符或者过长文本。

**通知发送前，必须先保证回答已经持久化。**

当客户端收到合法的 SSE Done 事件后，ViewModel 会先：

```
完成 assistant 最终落库
        ↓
把本地临时消息 ID 替换为服务端 messageId
        ↓
确认状态为 completed
        ↓
发送完成通知
        ↓
恢复 Room Flow 做最终对账
```

因此 PendingIntent 中保存的是最终服务端消息 ID，而不是只存在于当前进程内存中的临时 ID。即使点击通知时应用已经被系统回收，也可以重新从 Room 找到对应记录。

通知发送属于辅助能力。如果用户没有授予权限，或者权限在检查和发送之间被撤销，通知管理器会直接跳过或捕获 `SecurityException`，不会影响回答的最终落库和状态更新。

**PendingIntent 会同时携带两个标识：**

```
conversationId：进入哪一个会话
messageId：定位哪一条回答
```

点击通知后，如果应用没有启动，MainActivity 会在 `onCreate()` 中解析目标；如果 Activity 已经存在，则通过 `onNewIntent()` 更新目标。

解析时还会检查：

- Intent action 是否是约定的通知跳转 action；
- conversationId 是否为非空字符串；
- messageId 是否为非空字符串。

校验通过后，导航层会进入：

```
chat/{conversationId}?messageId={messageId}
```

这样通知指向的是某一次具体回答，而不只是一个聊天窗口。

**这里需要特别说明 Room 和内存消息状态之间的关系。**

ChatScreen 实际观察的不是某次 Room 查询，而是 ChatViewModel 对外暴露的统一 `messages StateFlow`。

它在不同阶段有不同的数据来源：

```
空闲或冷启动：
Room Flow → messages StateFlow → Compose

SSE 生成期间：
内存快照 → messages StateFlow → Compose

回答结束后：
最终消息写入 Room
        ↓
恢复 Room Flow
        ↓
校准 messages StateFlow
```

所以通知定位逻辑并不直接依赖某一种存储，而是监听统一的消息列表。

点击通知进入聊天页后，`LaunchedEffect(targetMessageId, messages)` 会在消息列表变化时查找目标 ID。如果页面刚创建，`messages` 还为空，就暂时不滚动；当 Room Flow 返回持久化消息，或者**历史同步补齐消息并更新 StateFlow 后，再重新查找。**

热启动时也可能存在一个很短的过渡窗口：内存列表里还是本地临时 ID，而通知携带的已经是服务端正式 messageId。终态 Room 记录回流以后，**目标 ID 才会出现在统一消息列表中。**（查找的是服务端正式id）

找到目标后，页面会：

```
根据 messageId 找到消息下标
        ↓
scrollToItem(targetIndex)
        ↓
标记本次目标已经消费
        ↓
关闭 autoFollow
```

等待目标期间，普通的“初次进入自动滚到底部”逻辑也会暂停，避免两个滚动副作用同时执行。

定位成功后关闭 `autoFollow` 同样很重要。如果仍然保持自动跟随，页面刚跳到目标消息，后续 Room 更新或者布局变化又可能把用户拉回列表底部。

当前实现没有为“目标 messageId 永远不存在”设置超时提示。如果通知属于已经切换掉的账号，或者本地与服务端都无法恢复目标记录，页面会继续等待后续消息变化，而不会明确提示“目标回答不存在”。这是后续可以补充的异常边界。

总结来说，回答完成通知并不是简单地“发通知并打开页面”，而是一条完整的消息级跳转链路：先确保最终回答落库，再携带 conversationId 和正式 messageId，兼容冷启动与 Activity 复用，最后通过统一的 messages StateFlow 等待数据就绪并精确定位。

这样用户点击通知后看到的是那条刚刚完成的回答，而不是只进入一个可能很长的会话。



## 文件上传sup