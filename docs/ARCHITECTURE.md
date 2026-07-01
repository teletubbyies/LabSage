# LabSage Android — 架构与技术文档

## 项目概述

为 Go 后端 LabSage（AI 实验室知识助手，GoFrame + Milvus + ReAct Agent + SSE 流式）开发的 Android 原生客户端。支持流式对话、Markdown 渲染、多会话管理、文件上传到知识库。

| 属性 | 值 |
|------|-----|
| 语言 | Kotlin |
| UI 框架 | Jetpack Compose (Material 3) |
| 架构模式 | MVVM + 手动 DI |
| 最低 SDK | API 24 (Android 7.0) |
| 目标 SDK | API 36 |
| 构建系统 | Gradle 9.4.1 + AGP 9.2.1 + Kotlin 2.2.10 |

---

## 一、整体架构

```
┌──────────────────────────────────────────────────┐
│  UI Layer (Compose)                               │
│  ChatScreen / ConversationListScreen              │
│  MessageBubble / ChatInputBar / TracePanel        │
├──────────────────────────────────────────────────┤
│  ViewModel Layer                                  │
│  ChatViewModel / ConversationListViewModel        │
│  State: StateFlow<StreamState> + List<MessageUi>  │
├──────────────────────────────────────────────────┤
│  Repository Layer                                 │
│  ChatRepository (single source of truth)          │
├──────────────────────┬───────────────────────────┤
│  Remote Data Source  │  Local Data Source         │
│  OkHttpSseDataSource │  Room (ConversationDao +   │
│  (SSE streaming)     │   MessageDao)              │
│  LabSageApi          │                            │
│  (Retrofit, REST)    │                            │
└──────────────────────┴───────────────────────────┘
```

### 为何不用 Hilt/Dagger

聊天 App 的依赖图简单（单 Repository + 单 API 接口 + 单数据库），手动 DI（`LabSageApplication` 懒加载属性 + ViewModelFactory）约 150 行代码，比 Hilt 的注解处理器 + Dagger 组件生成的代码量更少，且完全透明可调试。

### 包结构

```
com.labsage.app/
├── data/
│   ├── remote/     # API 模型、SSE 解析、Retrofit 接口
│   ├── local/      # Room 实体、DAO、数据库
│   └── repository/ # ChatRepository
├── domain/
│   └── model/      # StreamState 状态机
└── ui/
    ├── theme/      # Material 3 主题
    ├── conversation/ # 会话列表
    └── chat/       # 聊天界面 + 组件
```

---

## 二、SSE 流式解析 — 核心实现

### 技术选型

| 方案 | 结论 |
|------|------|
| 自定义 Retrofit CallAdapter | ❌ Retrofit 对 SSE 无增值（无需 JSON 反序列化），CallAdapter 增加测试复杂度 |
| EventSource API (Web 标准) | ❌ Android 无原生 EventSource，第三方库质量参差 |
| **OkHttp `okhttp-sse` + `callbackFlow` + `SseDataSource` 接口** | ✅ Square 官方库处理 SSE 协议边界，`callbackFlow` 天然适配 Kotlin Flow |

### 架构

```
OkHttpSseDataSource (implements SseDataSource)
  ├── OkHttpClient (readTimeout=0，BODY 级别日志禁用)
  ├── EventSources.createFactory() → EventSource
  ├── EventSourceListener.onEvent() → callbackFlow.trySend()
  └── Flow<SseEvent>.flowOn(Dispatchers.IO)
```

### SSE 事件协议

后端 Go 代码 (`internal/logic/sse/sse.go`) 发送 6 种事件：

| 事件类型 | data 格式 | 处理 |
|---------|----------|------|
| `connected` | JSON `{status, client_id}` | 连接确认，记录 client_id |
| `message` | 原始文本 | 追加到 `accumulatedAnswer`，UPDATE Room |
| `trace` | 调试文本 | 追加到 trace 列表 |
| `trace_summary` | JSON `{tool_stats, total_tool_calls}` | 记录工具调用统计 |
| `done` | `"Stream completed"` | 最终化消息，切换到 Complete 状态 |
| `error` | JSON `{message, partial, retryable}` | partial=true → 可续传；否则丢弃 |

### 关键实现细节

**SseParser** — 纯函数行解析器，零依赖可单元测试：
```kotlin
class SseParser {
    fun feed(line: String): RawSseFrame?  // 逐行喂入，空白行触发事件派发
    fun flush(): RawSseFrame?             // 流结束后刷新残留数据
}
```

**OkHttp 配置要点**：
- SSE 客户端禁用 `HttpLoggingInterceptor.Level.BODY`（会缓冲整个流导致 `onResponse` 永不触发）
- `readTimeout(0)` 允许无限长连接
- 与 Retrofit 客户端共享 `baseClient.newBuilder()`，复用连接池

**断线续传 (Continue/PartialAnswer)**：
- 用户问题保存为 `originalQuestion`
- 中断时 `StreamState.Error(partial=true, partialAnswer=<已接收文本>)`
- 用户点击"继续" → 发送 `ChatStreamRequest(Continue=true, PartialAnswer=<已接收文本>)`
- 后端追加到响应前但不重复持久化，流正常结束后才写 memory

---

## 三、数据持久化 — Room 设计

### 表设计

```sql
-- 会话表
CREATE TABLE conversations (
    id         TEXT PRIMARY KEY,   -- session ID
    title      TEXT NOT NULL,       -- 首条消息前 30 字符
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- 消息表
CREATE TABLE messages (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id  TEXT NOT NULL,          -- FK → conversations.id
    role             TEXT NOT NULL,          -- "user" | "assistant"
    content          TEXT NOT NULL,
    timestamp        INTEGER NOT NULL,
    route            TEXT,                   -- "direct" | "react"
    is_partial       INTEGER DEFAULT 0,     -- 流中断标记
    original_question TEXT,                  -- 用于续传
    error_message    TEXT,
    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
);

CREATE INDEX idx_messages_conversation ON messages(conversation_id);
```

### 流式内容写入策略

流式消息逐 chunk 到达时，**不在每个 chunk 都 INSERT**：

1. 第一个 `message` chunk → `INSERT` 占位行，`is_partial=1`，返回 `messageId`
2. 后续 chunk → `UPDATE messages SET content = :content WHERE id = :messageId`
3. `done` 事件 → `UPDATE messages SET is_partial=0, route=:route`
4. `error` 事件 → 保留 `is_partial=1`，UI 显示"继续"/"重新生成"

好处：app 被 kill 后重启，`is_partial=1` 的消息会被加载，UI 自动显示续传按钮。

---

## 四、状态管理 — StateFlow + Sealed Class

### 状态机

```kotlin
sealed class StreamState {
    object Idle : StreamState()           // 空闲
    object Streaming : StreamState()      // 正在接收
    data class Completed(                 // 完成
        val fullAnswer: String,
        val route: String?,
        val toolStats: Map<String, Int>
    ) : StreamState()
    data class Error(                     // 错误
        val message: String,
        val partial: Boolean,             // 是否有部分内容
        val retryable: Boolean,           // 是否可续传
        val partialAnswer: String,        // 已接收内容
        val lastQuestion: String          // 原始问题
    ) : StreamState()
}
```

### 数据流

```
User Input → ViewModel.sendMessage()
  → ChatRepository.sendStreamMessage()
    → OkHttpSseDataSource.streamChat()
      → callbackFlow { EventSourceListener.onEvent() → trySend() }
        → Flow<SseEvent>.flowOn(IO)
          → ViewModel.handleSseEvent()
            → StateFlow update → Compose recomposition
```

ViewModel 通过 `viewModelScope.launch` 收集 Flow。配置变更时 ViewModel 存活，Flow 继续收集。

---

## 五、Markdown 渲染

### 技术选型

| 方案 | 问题 |
|------|------|
| jeziellago/compose-markdown | 非纯 Compose（内嵌 TextView），不支持流式增量，JitPack 分发 |
| WebView + marked.js | 每条消息一个 WebView，+112MB 内存/实例，LazyColumn 不可行 |
| 自定义 AnnotatedString | 4-8 周工程工作量 |
| **mikepenz/multiplatform-markdown-renderer-m3** | ✅ 纯 Compose，976 stars，支持代码高亮 |

### 使用方式

```kotlin
import com.mikepenz.markdown.m3.Markdown

// 流式进行中：纯文本（Markdown 不完整，直接 render 会断裂）
Text(text = content, style = MaterialTheme.typography.bodyMedium)

// 流式完成后：Markdown 渲染
Markdown(content = content)
```

M3 变体的 `Markdown` composable 自带 Material 3 默认 colors 和 typography，无需额外配置。

---

## 六、执行过程实时展示（内联 Trace）

流式对话期间，后端通过 SSE `trace` 事件发送 ReAct Agent 的执行日志（路由决策、工具调用）。Android 端在 **assistant 头像旁** 以内联标签展示执行摘要：

- **数据流**：`SseEvent.Trace` → `accumulatedTraces` → `_traceLines` StateFlow → `MessageBubble` → `InlineTraceSummary`
- **显示策略**：解析 trace 文本提取可读摘要（"Calling query_nvidia_gpu_usage..." / "Tool completed"），在 L 头像旁的蓝色迷你标签中展示
- **设计决策**：选择头像旁内联而非顶部固定面板，避免视线跳转和页面抖动
- **生命周期**：`Streaming` 时展示，`Done` 后清空
- **空内容处理**：content 为空 + isStreaming 时显示 "Thinking..." 动画

### 流式内容渲染策略

| 状态 | content | traceLines | 渲染 |
|------|---------|-----------|------|
| 等待首个消息 | 空 | 空 | `StreamingIndicator` 三点动画 |
| 工具调用中 | 空 | 非空 | `InlineTraceSummary`("Calling xxx...") + `ThinkingText`("Thinking...") |
| 模型生成中 | 非空 | 可能非空 | `Text(content)` + `InlineTraceSummary` |
| 完成 | 非空 | 清空 | `Text(content)` |

### 内联 vs 顶部面板 选型

| 方案 | 优点 | 缺点 |
|------|------|------|
| 顶部固定面板 | 位置固定、易发现 | 脱离对话流、隐藏时页面跳动、占用额外屏幕空间 |
| **头像旁内联标签** ✅ | 与对话自然融合、无跳动、视线不切换 | 需解析 trace 文本提取摘要 |

**面试考点**：StateFlow 逐条追加的性能开销（trace <20 条可忽略）、LazyColumn 中内联组件如何避免参与滚动（Column 包裹在 item 内）

---

## 七、文件上传

1. `ActivityResultContracts.GetContent` 启动系统文件选择器
2. `ContentResolver` 读取 URI → 复制到 `cacheDir` 临时文件
3. 构建 `MultipartBody.Part.createFormData("file", fileName, requestBody)`
4. Retrofit `POST /api/upload` → 返回 `UploadData(fileName, filePath, fileSize)`
5. 清理临时文件，聊天中添加"已上传"系统消息

---

## 七、会话上下文（Summary + Facts）

后端通过 `/api/session/context` (GET/PUT) 暴露会话的 LLM 维护的摘要和关键事实。Android 端通过 AlertDialog 查看和编辑：

- `GET /api/session/context?Id=xxx` → `{summary, facts}`
- `PUT /api/session/context` ← `{Id, summary?, facts?}` → `{ok}`

后端实现位于 `internal/controller/chat/chat_v1_session_context.go`。

---

## 八、网络状态监控

`ConnectivityManager.NetworkCallback` + `DisposableEffect` 监听网络变化，断网时顶部显示红色横幅。

---

## 九、消息交互

长按消息弹出 `DropdownMenu`：复制（ClipboardManager）、分享（Intent.ACTION_SEND）、重新编辑（仅用户消息，回填输入框）。

---

## 十、关键设计决策汇总

| 决策 | 选择 | 原因 |
|------|------|------|
| DI 框架 | 手动 DI | 依赖图简单，Hilt 增加编译时开销但无实际收益 |
| SSE 实现 | OkHttp `okhttp-sse` + `callbackFlow` | 绕过 Retrofit，OkHttp 原生 SSE 支持更可靠 |
| Markdown 库 | mikepenz M3 | 纯 Compose，活跃维护，M3 主题兼容 |
| 流式持久化 | INSERT + UPDATE 模式 | 进程 kill 后可恢复 |
| Base URL | `10.0.2.2:6872` | 模拟器映射宿主机 localhost |
| 状态管理 | StateFlow + sealed class | 比 LiveData 更适合 Compose，sealed class 确保状态穷举 |
| 超时策略 | SSE 0 超时 / API 30s | SSE 长连接不能被超时打断；短请求需快速失败 |
