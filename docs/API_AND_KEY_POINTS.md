# LabSage Android — API 对接与核心知识点

## 一、后端 API 合约

### 端点一览

| 端点 | Method | Content-Type | 说明 |
|------|--------|-------------|------|
| `/api/chat` | POST | `application/json` | 非流式对话 |
| `/api/chat_stream` | POST | `application/json` → SSE | 流式对话 |
| `/api/upload` | POST | `multipart/form-data` | 文件上传 |

### 请求格式

```json
// /api/chat
{ "Id": "session_abc_1234567890", "Question": "你好" }

// /api/chat_stream
{ "Id": "session_abc", "Question": "你好", "Continue": false, "PartialAnswer": "" }

// /api/upload (multipart, field name = "file")
```

> **注意**：后端 JSON 字段使用 PascalCase（`Id`/`Question`/`Continue`/`PartialAnswer`），Android 端通过 `@SerializedName` 映射。

### 响应格式

```json
// 统一信封（非 SSE 端点）
{ "message": "OK", "data": { ... } }

// /api/chat 的 data
{
  "answer": "...",
  "route": "direct",
  "trace": ["[route] route=direct...", "工具调用统计：0 次"],
  "toolStats": {}
}
```

### SSE 事件流

```
event: connected
data: {"status":"connected","client_id":"abc123"}

event: message
data: 你好！我是 LabSage

event: message
data: ，实验室知识助手。

event: trace
data: [route] route=direct intent=smalltalk

event: trace_summary
data: {"tool_stats":{},"total_tool_calls":0,"trace_count":3}

event: done
data: Stream completed
```

---

## 二、SSE 解析实现 — 面试重点

### SSE → Flow 的桥接实现细节

这是简历中的核心技术亮点。生产链路用 **OkHttp `okhttp-sse` 的 `EventSources` 做协议解析 + `callbackFlow` 桥接为 Flow**（**不使用 Retrofit CallAdapter**——Retrofit 为"请求-响应"设计，对 SSE 长连接无增值）。下面两个组件分别覆盖"理解协议"与"生产桥接"：

#### SseParser（手写纯函数行解析器 — 学习/备选，未接入运行链路）

> ⚠️ 该解析器是为理解 SSE 协议而写的备选实现，**生产并未调用**——`okhttp-sse` 的 `EventSources` 已内置协议解析，直接把解析好的 `onEvent(type, data)` 喂给回调。保留它是作为面试展开素材。

```kotlin
class SseParser {
    private var eventType = ""
    private val dataLines = mutableListOf<String>()

    fun feed(line: String): RawSseFrame? {
        if (line.isEmpty()) {
            // 空白行 = 事件边界，触发派发
            if (dataLines.isEmpty()) return null
            val frame = RawSseFrame(eventType.ifEmpty { "message" }, dataLines.joinToString("\n"))
            eventType = ""; dataLines.clear()
            return frame
        }
        if (line.startsWith(":")) return null                    // 注释行
        when {
            line.startsWith("event: ")  -> eventType = line.substring(7)
            line.startsWith("data: ")   -> dataLines.add(line.substring(6))
            line.startsWith("data:")    -> dataLines.add(line.substring(5))
        }
        return null
    }

    fun flush(): RawSseFrame? { /* EOF 时刷新残留数据 */ }
}
```

**面试考点**：
- 为什么不用 `BufferedReader.readLine()` 直接消费？→ 需要 `callbackFlow` 的非阻塞语义
- 为什么 `eventType.ifEmpty { "message" }`？→ SSE 规范允许省略 event 字段，默认按 message 处理
- 如何防止内存泄漏？→ `dataLines` 单次事件的 `joinToString` 后立即清空
- 为什么 `okhttp-sse` 的 EventSourceListener 已经做了行解析，还需要 SseParser？→ `okhttp-sse` 库已经处理了 SSE 行解析；这里 SseParser 是备选方案，展示理解深度

#### OkHttpSseDataSource（callbackFlow 桥接 — 生产实现）

```kotlin
class OkHttpSseDataSource : SseDataSource {
    override fun streamChat(request: ChatStreamRequest): Flow<SseEvent> = callbackFlow {
        val eventSource = EventSources.createFactory(sseClient)
            .newEventSource(httpRequest, object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    trySend(buildEvent(type, data))  // 非阻塞发送
                }
                override fun onClosed(eventSource: EventSource) { channel.close() }
                override fun onFailure(...) { close(error) }
            })
        awaitClose { eventSource.cancel() }  // Flow 取消时断开 SSE
    }.flowOn(Dispatchers.IO)
}
```

**面试考点**：
- `callbackFlow` vs `flow { }` 的区别？→ `callbackFlow` 是冷流，`awaitClose` 块处理取消清理
- 为什么 `readTimeout(0)`？→ SSE 是长连接，可能在几分钟内无数据但不断开
- 为什么禁用 `BODY` 级别日志？→ OkHttp 的 `HttpLoggingInterceptor(BODY)` 会缓冲整个响应体才回调 `onResponse`，对无限流会永不回调（OkHttp issue #4298）
- `channel.close()` vs `close(error)` 的区别？→ close 不传参会正常结束 Flow，传异常会触发 Flow 的 catch

---

## 三、流式消息的 Compose 渲染策略

### 两阶段渲染

| 阶段 | 渲染方式 | 原因 |
|------|---------|------|
| 流式进行中 | `Text(text = content)` 纯文本 | Markdown 不完整时（如未闭合的代码块）会导致渲染异常 |
| 流式完成后 | `dev.jeziellago.compose.markdowntext.MarkdownText(markdown = content)` | 完整 Markdown 渲染，含代码块/表格/链接 |

### LazyColumn 自动滚底（双策略）

```kotlin
val listState = rememberLazyListState()
// 新消息到达：平滑动画滚底
LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
}
// 流式 chunk 更新：snap 即时滚底（避免逐 chunk 动画卡顿）
LaunchedEffect(messages.lastOrNull()?.content) {
    if (isStreaming && messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
}
```

**面试考点**：
- 为什么拆成两个 `LaunchedEffect`？→ 新消息用 `animateScrollToItem` 平滑过渡；流式逐 chunk 更新频率高达 ~50/s，每帧都 animate 会卡顿，改用 `scrollToItem` snap 即时定位
- 为什么第二个 `LaunchedEffect` 的 key 是 `lastOrNull()?.content`？→ 需要追踪最后一条消息的内容变化（流式逐 chunk 更新），而不仅是消息数量变化

---

## 四、Room 数据库 — 面试考点

### 为什么不用 DataStore？

- DataStore 适合键值对（偏好设置、配置），不适合结构化查询
- Room 提供 SQL 查询能力、Flow 响应式观察、外键级联删除
- DataStore + Room 双存储方案只在大项目中有意义（如分别存储用户偏好和业务数据）

### 为什么不用 SQLite FTS（全文搜索）？

- 对话历史的搜索场景是"浏览 + 按会话筛选"，不是"跨所有消息模糊匹配"
- LazyColumn 列表展示已经满足浏览需求
- FTS 在消息量 < 10000 条时没有性能优势

### OnConflictStrategy.REPLACE 与 FK CASCADE 的致命交互（实战 Bug）

**Bug 场景**：每次发送新消息后，历史消息全部消失。

**根因**：
```kotlin
// 错误写法
@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun upsert(conversation: ConversationEntity)
```

SQLite 的 REPLACE 算法 ≠ MySQL 的 `ON DUPLICATE KEY UPDATE`。SQLite 在主键冲突时执行 **DELETE + INSERT**，DELETE 触发 FK 的 `ON DELETE CASCADE`，导致该会话所有消息被删除。

**正确写法**：
```kotlin
// 方案：显式判断是否存在
val existing = conversationDao.getById(id)
if (existing != null) {
    conversationDao.updateMeta(id, title, timestamp) // @Query("UPDATE ...")
} else {
    conversationDao.insert(entity) // @Insert(IGNORE)
}
```

**面试考点**：
- REPLACE 的实现机制（DELETE + INSERT vs UPDATE）
- SQLite vs MySQL 行为差异
- Room `@RawQuery` vs `@Query("UPDATE ...")` 的选择
- 为什么不用 `@Transaction` 包裹 → 这里不需要原子性，分开判断足够

### ForeignKey + CASCADE 的设计意图

```kotlin
@Entity(
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversation_id"],
        onDelete = ForeignKey.CASCADE
    )]
)
```

删除会话时自动删除所有关联消息，无需手动清理。这是关系型数据库的基本正确性保证，但很多面试者会忽略。

---

## 五、ViewModel 与配置变更

### ViewModel 生命周期

```
Activity 创建 → ViewModelProvider.get() → ViewModel 创建
屏幕旋转 → Activity 销毁 → ViewModel 存活
新 Activity 创建 → 获取同一 ViewModel → 状态无缝恢复
Activity finish() → ViewModel.onCleared() → 资源释放
```

### 为什么 StateFlow 比 LiveData 更适合 Compose

| | LiveData | StateFlow |
|---|---|---|
| 初始值 | 无，需处理 null | 必须有初始值（`MutableStateFlow(initial)`） |
| Compose 收集 | `observeAsState()` 需要 LifecycleOwner | `collectAsStateWithLifecycle()` |
| 线程安全 | 主线程观察 | 任意线程 emit |
| 操作符 | 仅 `map`/`switchMap` | 完整 Flow 操作符链（`catch`/`debounce`/`flatMapLatest`） |

---

## 六、OkHttp 连接池管理

```kotlin
// 基础客户端 — 连接池、header 日志
val baseClient = OkHttpClient.Builder()
    .addInterceptor(HttpLoggingInterceptor(HEADERS))
    .build()

// Retrofit 客户端 — 30s 超时
val apiClient = baseClient.newBuilder()
    .readTimeout(30, SECONDS)
    .build()

// SSE 客户端 — 无限读超时
val sseClient = baseClient.newBuilder()
    .readTimeout(0, MILLISECONDS)
    .build()
```

`newBuilder()` 共享连接池、Dispatcher、线程池，不会浪费资源。

### 面试考点
- OkHttp 默认连接池大小？→ 5 个空闲连接，5 分钟 keep-alive
- 为什么复用连接池？→ TCP 握手耗时 1-3 RTT（50-150ms），连接复用省去握手
- SSLSocket 复用条件？→ 相同 host:port + HTTP/2 支持多路复用

---

## 七、技术栈总览与面试话术

| 技术 | 选型 | 面试话术 |
|------|------|---------|
| UI | Jetpack Compose | "纯声明式 UI，配合 LazyColumn + StateFlow 实现流式场景下的高性能重组" |
| 网络 | OkHttp (SSE) + Retrofit (REST) | "SSE 长连接与短请求分离：长连接用 OkHttp 直连 0 超时，短请求用 Retrofit 30s 超时" |
| 存储 | Room | "conversation + message 双表 FK 级联，流式内容 INSERT 后逐 chunk UPDATE" |
| 状态 | StateFlow + sealed class | "sealed class 建模状态机，StateFlow 承载，Compose collectAsStateWithLifecycle 消费" |
| DI | 手动 | "依赖图简单（约 10 个 `by lazy` 单例、~210 行），手动 DI 比 Hilt 更透明且无编译时开销" |
| Markdown | jeziellago/compose-markdown 0.5.7 | "纯 Compose 渲染，无 WebView 开销，流式完成后全量渲染（mikepenz 系因 Compose Runtime 版本不兼容而弃用）" |
| Trace 展示 | 内联 InlineTraceSummary | "SSE trace 事件在 assistant 头像旁实时显示'Calling xxx'/'Tool completed'，正式输出后隐藏" |
| 网络监控 | ConnectivityManager | "NetworkCallback + DisposableEffect，断网时顶部横幅提示，不影响已加载内容" |

---

## 八、内联 Trace 展示设计（面试重点）

### 为什么 trace 放在头像旁而非顶部固定面板？

| 方案 | 问题 |
|------|------|
| 顶部固定面板（ExecutionTracePanel） | 脱离对话流，用户视线需要在"执行状态"和"对话内容"间切换；工具调用完成后面板消失导致页面跳动 |
| **头像旁内联标签（InlineTraceSummary）** | ✅ 与对话流自然融合，trace 状态紧邻 assistant 回复，视线不需要跳转；消失时无明显页面抖动 |

### 实现细节

```kotlin
// MessageBubble 接收 traceLines，在 assistant 头像下方显示
fun InlineTraceSummary(traceLines: List<String>) {
    val toolLine = traceLines.lastOrNull { it.contains("调用工具") || it.contains("开始调用") }
    val summary = when {
        toolLine?.contains("调用工具") == true ->
            "Calling ${toolLine.substringAfter("调用工具：").substringBefore("(").trim()}..."
        toolLine?.contains("开始调用") == true -> "Running..."
        toolLine?.contains("调用完成") == true -> "Tool completed"
        else -> traceLines.lastOrNull()?.take(40) ?: "Processing..."
    }
    // 渲染为蓝色迷你标签（Surface + Icon + Text）
}
```

**面试考点**：
- 为什么不直接用 `CircularProgressIndicator`？→ 聊天 UI 中的 loading 应保持文本化，圆形进度条语义过重
- 为什么 trace 不需要持久化？→ trace 是实时执行日志，完成后由 Done 事件清除，避免界面冗余
- StateFlow 逐条追加 trace 的性能开销？→ `_traceLines.value = accumulatedTraces.toList()` 每次创建新 List，但 trace 通常 < 20 条，完全可接受

---

## 九、SSE 空消息与 "Thinking" 动画

### 问题场景

后端 ReAct Agent 在调用工具时（如 `query_conda_env_usage`），工具执行期间 LLM 不产出文本，SSE `message` 事件 data 为空。若不做处理，用户看到 assistant 气泡空白 + StreamingIndicator，误以为卡死。

### 解决方案

- **空 content + isStreaming** → 显示 "Thinking..." 动画（3 个递变点数）
- **trace 事件到达** → 显示 "Calling xxx..." / "Tool completed"
- **content 非空** → 渲染实际文本

### 面试考点
- SSE 流中消息可能为空的场景：工具调用等待、模型推理缓冲、网络延迟
- 如何区分"卡死"和"工具执行中"：trace 事件是最可靠的信号（`[tool] 开始调用`、`[tool] 调用完成`）
- 动画选择：`InfiniteTransition + animateFloat + repeat` vs `Animatable` → InfiniteTransition 更轻量，适合无交互纯展示动画

---

## 十、Compose 性能优化 — 流式聊天场景

### 瓶颈分析

流式 SSE 场景下，每个 message chunk（~50/s）触发 Room 写入 → Flow 发射 → StateFlow 更新 → 全量 Composable 重组。四层链路每层都可能成为瓶颈。

### 优化策略

| 层级 | 问题 | 优化 | 效果 |
|------|------|------|------|
| Room → Flow | 每 chunk 触发全表查询发射 | `.conflate()` 丢弃中间态 | 重组 ~50/s → ~10/s |
| LazyColumn 滚动 | `animateScrollToItem` 每帧都执行 | 新消息 animate / 内容更新 snap | 消除动画抖动 |
| StateFlow → Compose | 每次值变化触发所有读取该值的 composable 重组 | `@Immutable` 标注 data class + `derivedStateOf` | 跳过无关重组 |
| ViewModel 协程 | SSE Flow 收集在主线程 | 保持 `flowOn(Dispatchers.IO)`，Room 写入在 IO | 主线程不阻塞 |

### 面试考点
- `conflate()` vs `debounce()` vs `sample()`：conflate 立即消费最新值但丢弃中间值，适合 UI 更新；debounce 等待静默期，适合搜索输入
- `@Immutable` vs `@Stable`：Immutable 保证所有属性 final + 不可变；Stable 允许 mutable 但变化可被追踪
- `derivedStateOf` vs `remember(key)`：derivedStateOf 在读取时才计算，适合从其他 State 派生；remember(key) 在 key 变化时重建

## 十一、callbackFlow 背压与 trySend 陷阱（实战 Bug，见 BUGFIX_LOG #11/#13）

### 问题
`callbackFlow` 默认 buffer = 64。SSE 工具查询产生 200+ 条 trace 事件，`trySend()` 在 buffer 满后静默丢弃，包括后续的 `message` 和 `done` 事件，导致流式输出中断。

### 三级修复
```
原始事件流:   connected → trace×200(自由文本) → message×50 → trace_summary → done
Phase 1 过滤: connected → trace×3([tool]/[react]/[route] 结构化行) → message×50 → ... → done
Phase 2 缓冲: callbackFlow { ... }.buffer(Channel.UNLIMITED)   // 兜底，防 message chunk + Room 写延迟再次溢出
Phase 3 批写: Room updateContent 每 5 chunk 一次，Done 时 finalize
```

过滤逻辑（按**结构化前缀**，非中文关键字子串——BUG #13：关键字匹配会漏掉措辞不同但同属生命周期的行）：
```kotlin
if (event is SseEvent.Trace) {
    val l = event.line
    val keep = l.startsWith("[tool]") || l.startsWith("[react]") || l.startsWith("[route")
    if (!keep) return  // 丢弃非结构化的自由文本 trace
}
```

### 面试考点
- `trySend` vs `send`：`trySend` 非阻塞、立即返回 `ChannelResult`，适合回调；`send` 挂起等待、保证交付，适合协程
- Channel 容量策略：BUFFERED(64) / CONFLATED(1) / UNLIMITED 的选择场景
- 为什么过滤后还敢用 `Channel.UNLIMITED`：过滤已把 200+ trace 砍到个位数，剩余主要是 message chunk（有界），UNLIMITED 只为兜底 Room 写入延迟导致的瞬时溢出，不会无限膨胀
- 为什么不直接无脑 `UNLIMITED` 不过滤：993 条事件全部缓存 → 内存 GC 压力 + 消费者延迟
- 为什么不在 `onEvent` 中 `launch { send() }`：增加协程创建开销，且顺序可能乱
