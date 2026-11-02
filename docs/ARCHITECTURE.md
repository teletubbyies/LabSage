# LabSage Android — 架构与技术文档

## 项目概述

为 Go 后端 LabSage（AI 实验室知识助手，GoFrame + Milvus + ReAct Agent + SSE 流式）开发的 Android 原生客户端。支持流式对话、断点续传、工具调用过程可视化、Markdown 渲染与引用角标、多会话管理、账号认证、历史增量同步、流式文件上传到知识库。

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
┌──────────────────────────────────────────────────────────┐
│  UI Layer (Compose)                                       │
│  auth / chat / conversation / files / settings            │
│  MessageBubble / ChatInputBar / InlineTraceSummary        │
├──────────────────────────────────────────────────────────┤
│  ViewModel Layer                                          │
│  ChatViewModel / ConversationListViewModel / …            │
│  State: StateFlow<StreamState> + 内存快照 / Room Flow      │
├──────────────────────────────────────────────────────────┤
│  Repository / Coordinator Layer                           │
│  ChatRepository · AuthRepository · SettingsRepository     │
│  HistorySyncCoordinator（游标增量同步，同事务提交）          │
├────────────────────────────┬─────────────────────────────┤
│  Remote Data Source        │  Local Data Source           │
│  OkHttpSseDataSource (SSE) │  Room v8：conversations /    │
│  LabSageApi (Retrofit)     │   messages / history_cursors │
│  AuthApi                   │  KeystoreAuthSessionStore    │
│  StreamingRequestBody      │  Preferences DataStore       │
└────────────────────────────┴─────────────────────────────┘
```

### 为何不用 Hilt/Dagger

聊天 App 的依赖图简单（单 Repository + 单 API 接口 + 单数据库），手动 DI（`LabSageApplication` 中约 10 个 `by lazy` 懒加载属性 + ViewModelFactory，约 210 行）比 Hilt 的注解处理器 + Dagger 组件生成的代码量更少，且完全透明可调试。

### 包结构

```
com.labsage.app/
├── data/
│   ├── remote/     # OkHttp SSE 数据源、Retrofit API、请求模型、StreamingRequestBody
│   ├── local/      # Room 数据库（v8）、实体、DAO、迁移
│   ├── sync/       # 历史同步协调器、游标存储（与页面数据同事务提交）
│   ├── auth/       # 认证仓库、Keystore 加密会话存储
│   └── repository/ # ChatRepository / SettingsRepository
├── domain/model/   # StreamState 流式状态机
├── ui/             # Compose 界面：auth / chat / conversation / files / settings / theme
├── notification/   # 回答完成通知与深链定位
└── crash/          # 崩溃捕获 → 落盘 → 下次启动上报
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
| `message` | 原始文本 | 追加到内存 `StringBuilder`；UI 快照按 50 ms、Room checkpoint 按 500 ms 分通道节流 |
| `trace` | 调试文本 | 仅保留 `[tool]` / `[react]` / `[route` 前缀的结构化行，其余在数据源层丢弃 |
| `trace_summary` | JSON `{tool_stats, total_tool_calls, trace_count}` | 记录工具调用统计 |
| `done` | JSON `{conversation_id, user_message_id, assistant_message_id, status, updated_at, sync_version, references}` | 立即落盘终态、写入服务端 id 与同步游标、保存引用角标，切换到 Completed |
| `error` | JSON `{message, partial, retryable}` | partial=true → 保留残缺正文可续传；否则丢弃 |

### 背压保护

工具查询会在短时间内突发 200+ 条 `trace` / `message` 事件。`EventSourceListener.onEvent()` 把事件 `trySend` 进**固定 512 容量的有界缓冲**（`EVENT_BUFFER_CAPACITY`）；消费端跟不上导致缓冲满时，**取消 EventSource 并以显式错误结束流**——以失败代替无界堆积与静默丢失。

### 关键实现细节

**SseParser** — 手写的纯函数行解析器（零依赖、便于补单测）。
> ⚠️ 学习/备选产物，**未接入运行链路**：生产用的是 OkHttp `okhttp-sse` 的 `EventSources` 内置协议解析（它已处理好 event/data 字段分离与空白行事件边界，直接把解析好的 `onEvent(type, data)` 喂给回调）。`SseParser` 是为理解 SSE 协议而写的备选实现，保留在仓库中作为面试展开素材。
```kotlin
class SseParser {
    fun feed(line: String): RawSseFrame?  // 逐行喂入，空白行触发事件派发
    fun flush(): RawSseFrame?             // 流结束后刷新残留数据
}
```

**流式期间的数据通路**：正文不走「写库 → Room Flow → UI」这条链。SSE `message` 进入内存 `StringBuilder` 顺序累积，`SseUpdateCadence` 决定何时发布 UI 快照（50 ms）与何时 checkpoint 到 Room（500 ms）；流式期间 Room 消息观察被暂停，收到 `done` / `error` 终态后立即落盘，再恢复冷 Flow 做一次权威对账查询。

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

### 表设计（Room schema v8，含迁移测试）

```sql
-- 会话表：按账号 + 后端命名空间隔离缓存
CREATE TABLE conversations (
    id               TEXT PRIMARY KEY,       -- 后端 session Id
    title            TEXT NOT NULL,          -- 首条用户消息前 30 字符
    created_at       INTEGER NOT NULL,
    updated_at       INTEGER NOT NULL,
    is_pinned        INTEGER NOT NULL DEFAULT 0,
    version          INTEGER NOT NULL,       -- 服务端乐观锁版本（0 = 仅本地）
    sync_version     INTEGER NOT NULL,       -- 服务端单调同步游标（0 = 未同步）
    is_deleted       INTEGER NOT NULL DEFAULT 0, -- 服务端墓碑镜像
    owner_user_id    TEXT,                   -- 拥有该缓存行的账号
    owner_server_key TEXT                    -- 产生该缓存行的后端命名空间
);
CREATE INDEX index_conversations_owner_user_id    ON conversations(owner_user_id);
CREATE INDEX index_conversations_owner_server_key ON conversations(owner_server_key);

-- 消息表：id 为字符串（本地 UUID / 服务端 id），便于端云幂等合并
CREATE TABLE messages (
    id                TEXT PRIMARY KEY,
    conversation_id   TEXT NOT NULL,         -- FK → conversations.id，级联删除
    role              TEXT NOT NULL,         -- "user" | "assistant"
    content           TEXT NOT NULL,
    timestamp         INTEGER NOT NULL,
    route             TEXT,                  -- "direct" | "react"
    is_partial        INTEGER NOT NULL DEFAULT 0, -- 流中断标记，重启后显示「继续」
    original_question TEXT,                  -- 续传所需的原始问题
    error_message     TEXT,
    status            TEXT NOT NULL,         -- pending / streaming / completed / partial / failed
    client_request_id TEXT,                  -- 一轮 user/assistant 共享的幂等键
    client_created_at INTEGER NOT NULL,
    sync_version      INTEGER NOT NULL,
    server_updated_at INTEGER,
    is_deleted        INTEGER NOT NULL DEFAULT 0,
    references_json   TEXT,                  -- 引用角标，内联 JSON（<10 条，随消息整体读取）
    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
);
CREATE INDEX index_messages_conversation_id_timestamp ON messages(conversation_id, timestamp);
CREATE INDEX index_messages_client_request_id         ON messages(client_request_id);

-- 同步游标表：与被描述的缓存行同库同事务，清缓存即清进度
CREATE TABLE history_cursors (
    scope           TEXT NOT NULL,           -- "conversations" | "messages"
    user_id         TEXT NOT NULL,
    conversation_id TEXT NOT NULL,           -- conversations 作用域下为空串
    server_key      TEXT NOT NULL,           -- 游标只在后端命名空间匹配时可复用
    cursor          INTEGER NOT NULL,        -- 服务端高水位
    PRIMARY KEY (scope, user_id, conversation_id)
);
CREATE INDEX index_history_cursors_user_id ON history_cursors(user_id);
```

几个关键取舍：

- **消息主键用 TEXT 而非自增整数**：本地先以 UUID 落行，`done` 事件带回服务端 id 后原地采纳（`adoptServerIdentity`），同一轮的 user/assistant 共享 `client_request_id` 作为幂等键，不会因为自增 id 冲突而重复。
- **游标存在 Room 而非 DataStore**：同步一页数据与推进游标在同一个事务里提交，崩溃后不会出现「数据已入库但游标未前进」导致的重复拉取。
- **引用角标内联 JSON 不建子表**：列表很小、总是随消息一起读、从不按引用反查，子表只会多一次 JOIN。

### 流式内容写入策略

流式消息逐 chunk 到达时，**既不每 chunk 写库，也不按 chunk 计数写库**，而是按时间节流：

1. 第一个 `message` chunk → `INSERT` 占位行（`is_partial = 1`，`status = streaming`），并以此刻作为 checkpoint 起点
2. 后续 chunk 累积在内存 `StringBuilder`；每满 **500 ms** 执行一次 `UPDATE content` checkpoint（UI 快照另按 50 ms 发布，互不等待）
3. `done` → **立即**写入完整正文与服务端元数据（`is_partial = 0`、`status = completed`、`sync_version`、`references_json`），不等节流窗口
4. `error` → 立即落盘已收到的残缺正文并保留 `is_partial = 1`，UI 显示「继续」/「重新生成」

好处：进程异常退出最多损失约 500 ms 未 checkpoint 的尾部正文；重启后读到 `is_partial = 1` 的消息即可以 `Continue + PartialAnswer` 续传。写入次数从旧「每 5 chunk 一写」策略的 O(chunks) 变为 O(duration / 500 ms)，真实 trace 回放下降 82%–97%，见 [SSE_CADENCE_GRID_REPORT.md](SSE_CADENCE_GRID_REPORT.md) 与 [CADENCE_DECISION_SPACE.md](CADENCE_DECISION_SPACE.md)。

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
| mikepenz/multiplatform-markdown-renderer-m3 | ❌ v0.35 / 0.40 / 0.41 运行时 `NoSuchMethodError: getCurrentCompositeKeyHashCode`——库编译目标依赖 Compose Runtime 1.10+，项目 BOM 2025.06.01 提供的 Runtime 更早、缺该方法 |
| WebView + marked.js | 每条消息一个 WebView，+112MB 内存/实例，LazyColumn 不可行 |
| 自定义 AnnotatedString | 4-8 周工程工作量 |
| **jeziellago/compose-markdown 0.5.7** | ✅ 纯 Compose（内嵌 TextView），兼容当前 BOM，JitPack 分发 |

### 使用方式

```kotlin
import dev.jeziellago.compose.markdowntext.MarkdownText

// 流式进行中：纯文本（Markdown 不完整，直接 render 会断裂）
Text(text = content, style = MaterialTheme.typography.bodyMedium)

// 流式完成后：Markdown 渲染
MarkdownText(markdown = content)
```

`MarkdownText` 默认跟随 Material 3 主题，无需额外 colors / typography 配置。

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

## 七、文件上传（流式 RequestBody）

1. `ActivityResultContracts.GetContent` 启动系统文件选择器
2. 通过 `ContentResolver` 取得文件大小与可重复打开的 `Source` 工厂
3. 已知大小时以 `StreamingRequestBody`（固定缓冲区、`contentLength` 已知、每次 `writeTo` 重新打开源因而可在鉴权重试时重放，并校验实际字节数与声明一致）包装为 `MultipartBody.Part`，**不复制到 cacheDir、不 `readBytes()` 全量入堆**；大小未知时才退回带字节上限的临时文件复制
4. Retrofit `POST /api/upload` → 返回 `UploadData(fileName, filePath, fileSize)`
5. 聊天中添加「已上传」系统消息

旧链路「cache 临时文件 → `readBytes()` → `ByteArray RequestBody`」在 10 MiB 文件上峰值堆增量约 23 MiB、额外临时磁盘等量于文件大小；流式链路把两者分别降到约 208 KiB 与 0，180 样本 A/B 见 [UPLOAD_BENCHMARK.md](UPLOAD_BENCHMARK.md)。

---

## 八、会话上下文（Summary + Facts）

后端通过 `/api/session/context` (GET/PUT) 暴露会话的 LLM 维护的摘要和关键事实。Android 端通过 AlertDialog 查看和编辑：

- `GET /api/session/context?Id=xxx` → `{summary, facts}`
- `PUT /api/session/context` ← `{Id, summary?, facts?}` → `{ok}`

后端实现位于 `internal/controller/chat/chat_v1_session_context.go`。

---

## 九、网络状态监控

`ConnectivityManager.NetworkCallback` + `DisposableEffect` 监听网络变化，断网时顶部显示红色横幅。

---

## 十、消息交互

长按消息弹出 `DropdownMenu`：复制（ClipboardManager）、分享（Intent.ACTION_SEND）、重新编辑（仅用户消息，回填输入框）。

---

## 十一、性能优化

流式场景下 message chunk 可达 ~50/s、工具查询时 trace 事件会簇发 200+ 条，优化重点在四层链路：

- **SSE 入口**：数据源层按 `[tool]` / `[react]` / `[route` 前缀过滤 trace，再进 512 容量有界缓冲；溢出即取消连接并显式报错
- **内存累积 → UI**：正文在预分配容量的 `StringBuilder` 中顺序追加，每 50 ms 发布一次不可变快照（约 20 Hz），流式期间暂停 Room 观察，避免「写库 → 失效 → 全会话查询 → 重组」链路被每个 chunk 触发
- **内存累积 → Room**：每 500 ms 一次 checkpoint，终态立即落盘；真实 trace 回放下写入次数较「每 5 chunk 一写」降低 82%–97%
- **LazyColumn / Compose**：新消息 `animateScrollToItem`，内容更新 `scrollToItem`（snap 无动画）；`@Immutable` data class + `derivedStateOf` 收窄重组范围；SSE 消费 `flowOn(Dispatchers.IO)` 不阻塞主线程
- **构建产物**：R8 + resource shrink，Release 包体积 20.83 → 2.58 MB

---

## 十二、关键设计决策汇总

| 决策 | 选择 | 原因 |
|------|------|------|
| DI 框架 | 手动 DI | 依赖图简单，Hilt 增加编译时开销但无实际收益 |
| SSE 实现 | OkHttp `okhttp-sse` + `callbackFlow` | 绕过 Retrofit，OkHttp 原生 SSE 支持更可靠 |
| SSE 背压 | 有界缓冲 512，溢出取消连接 | 以显式失败代替无界堆积与静默丢事件 |
| 流式持久化 | INSERT + 500 ms checkpoint UPDATE + 终态立即落盘 | 写入次数 O(duration) 而非 O(chunks)；崩溃最多丢约 500 ms 尾部正文，可续传 |
| UI 刷新节奏 | 50 ms 内存快照，与 Room 解耦 | 约 20 Hz 保持流式连续感，不被落盘节奏拖慢 |
| 消息主键 | TEXT（本地 UUID → 采纳服务端 id） | 端云幂等合并，避免自增 id 冲突 |
| 同步游标 | 存 Room，与页面数据同事务提交 | 崩溃后不会出现「数据已入库、游标未前进」的重复拉取 |
| 登录态存储 | Android Keystore + AES-GCM | 密钥不出安全硬件，明文 token 不落盘 |
| 引用角标存储 | `references_json` 内联 | 列表小、随消息整体读取、从不反查，省一次 JOIN |
| 文件上传 | 可重放 `StreamingRequestBody` | 消除 `readBytes()` 全量入堆与等量临时文件 |
| Markdown 库 | jeziellago/compose-markdown 0.5.7 | 兼容当前 Compose BOM（mikepenz 系因 `getCurrentCompositeKeyHashCode` 缺失而不兼容） |
| Base URL | 构建期固定 `BuildConfig.API_BASE_URL`，Debug 默认 `10.0.2.2:6872` | 模拟器映射宿主机 localhost；Release 不可被运行时篡改 |
| 状态管理 | StateFlow + sealed class | 比 LiveData 更适合 Compose，sealed class 确保状态穷举 |
| 超时策略 | SSE 0 超时 / API 30s | SSE 长连接不能被超时打断；短请求需快速失败 |
| UI 视觉层 | "Sage" 设计系统 | 统一品牌色 / 字体 / 形状 / 组件语言；纯视觉重构，零契约改动 |

---

## 十三、UI 视觉设计系统（Sage 设计语言）

将原本「标准 Material 3 模板」的视觉层整体重构为统一、有品牌识别度的 **Sage 设计语言**（emerald/teal 翡翠青绿色系）。**纯视觉重构**：所有 ViewModel、数据层、Repository 契约与回调零改动，仅替换 `ui/theme` 与各 Screen / Component 的表现层。

### 设计令牌（ui/theme/）

| 模块 | 文件 | 说明 |
|------|------|------|
| 色板 | `Color.kt` | 9 级 Brand 翡翠色阶（Brand50–900）+ 完整 light/dark 双色方案（primary / secondary / tertiary / error 全槽位）；暗色为带绿色调的深石墨，对比度与层次更佳 |
| 字体 | `Type.kt` | display / headline / title / body / label 完整字号刻度，逐档调校字重、行高、字距（系统字体，保证离线可构建） |
| 形状 | `Shape.kt` | 8 / 12 / 16 / 22 / 28 dp 圆角刻度，注入 `MaterialTheme.shapes` |
| 主题 | `Theme.kt` | `ExtendedColors`（气泡 / 工具调用 / 品牌渐变）+ `brandBrush()` 翡翠→青绿线性渐变 |

### 品牌识别

- `BrandMark`（`chat/components/Avatars.kt`）：品牌渐变圆形 + 实验烧瓶图标（`Icons.Filled.Science`），作为 LabSage 助手身份贯穿全 App——顶栏、消息头像、空状态、设置页头部。
- `UserAvatar`：中性 `secondaryContainer` 圆形 + `Person` 图标。

### 各界面要点

| 界面 | 重构要点 |
|------|---------|
| 会话列表 | 品牌字标头部（Logo + 副标题）、填充式圆角搜索框、浮动「新会话」ExtendedFAB、卡片渐变头像与置顶态、更温暖的空状态 |
| 聊天 | 助手 / 用户头像、非对称气泡尾角、主题化的离线 / 完成横幅、精炼顶栏 |
| 输入栏 | 胶囊形输入框、精炼快捷 Chip、**品牌渐变圆形发送键**（禁用态扁平置灰） |
| 工具执行面板 | 图标式状态徽标取代裸字符、缩放呼吸的输入指示气泡、主题化错误 / 重试横幅 |
| 设置 | 品牌头部卡、各分区带前置图标的卡片、分段式主题选择器（带勾选徽标） |

### 设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 字体 | 系统字体（Roboto） | 不内嵌字体文件，保证离线 / 无网可构建；靠刻度与字重打磨质感 |
| 品牌色 | 翡翠 / 青绿（teal） | 保留原有品牌识别（LabSage ≈ 鼠尾草绿），同时提升暗色档位深度 |
| 头像 | 组合式（渐变 + 矢量图标） | 无需新增 PNG 资源即可获得品牌标识，矢量随主题无损缩放 |

> 该次重构已通过 `:app:compileDebugKotlin` 与 `:app:assembleDebug` 双重验证。
