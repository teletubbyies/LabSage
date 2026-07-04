# LabSage Android — 新增功能文档

本文件记录在初始版本之上迭代新增的功能模块（按提交顺序）。每个功能包含：实现概述、架构决策、关键代码点、面试考点。Bug 修复记录于 `BUGFIX_LOG.md`。

---

## 一、语音输入（Voice Input）

### 功能概述

在 `ChatInputBar` 中加入一个独立的语音输入按钮，使用 Android 内置 `SpeechRecognizer` 将用户语音识别为文本并追加到输入框。**不改动任何数据流**——识别结果通过回调 `onResult` 投递，由输入框决定如何处理（当前实现为追加到已输入文本末尾）。

| 属性 | 值 |
|------|-----|
| 组件 | `VoiceInputButton`（`ui/chat/components/VoiceInputButton.kt`） |
| API | `android.speech.SpeechRecognizer`（直接 API，非 Intent 弹窗） |
| 权限 | `RECORD_AUDIO`（dangerous，运行时申请） |
| 语言模型 | `LANGUAGE_MODEL_FREE_FORM`，语言 `zh-CN` |
| 集成点 | `ChatInputBar` → `ChatScreen.onVoiceResult` |

### 架构决策：直接 SpeechRecognizer API vs Intent 弹窗

| 方案 | 评估 |
|------|------|
| `ACTION_RECOGNIZE_SPEECH` Intent | 弹出系统/Google 语音搜索对话框，调用方一般不需要 `RECORD_AUDIO`（由接收方 App 录音）。简单，但交互脱离应用 UI |
| **直接 `SpeechRecognizer` API** ✅ | 在本应用内渲染"正在聆听"状态、可显示 partial 结果，交互连贯；需自行管理 `RECORD_AUDIO` 权限与生命周期 |

选择直接 API 以获得与应用一致的视觉反馈（麦克风按钮脉冲动画），与既有"Thinking..."点动画同一视觉语言，而非突兀的系统弹窗或 `CircularProgressIndicator`。

### 关键实现点

1. **权限运行时申请**：`RECORD_AUDIO` 自 API 23 起为 dangerous 权限。通过 `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission)` 申请，授予后才创建识别器。
2. **RecognitionListener 稳定化**：listener 用 `remember` 创建一次（避免每次重组重建），对最新回调 `onResult` 通过 `rememberUpdatedState` 引用，防止捕获过期闭包。
3. **生命周期管理**：
   - `SpeechRecognizer.createSpeechRecognizer()` 必须在主线程调用。
   - `DisposableEffect.onDispose` 中 `destroy()`，释放识别服务连接，否则连接会绑定到进程结束。
   - 每次开始前 `destroy()` 旧实例再新建（已结束的识别器绑定旧 listener/状态，无法可靠重启）。
4. **优雅降级**：模拟器或无 Google 服务的设备可能 `isRecognitionAvailable` 返回 true 但实际无法绑定服务。所有错误路径（`onError`）都向用户展示 Toast，避免 UI 永久停留在"聆听中"。`NO_MATCH`/`SPEECH_TIMEOUT`（用户没说话）不弹 Toast，避免噪音。
5. **状态语义**：`isListening` 单独驱动按钮视觉（脉冲 + 红色 + Stop 图标），与输入框 `enabled` 解耦——流式输出进行时按钮禁用，但仍可正确反映聆听态。

### 数据流

```
用户长按/点击麦克风
  → 检查 RECORD_AUDIO 权限
    → 未授予: permissionLauncher.launch(RECORD_AUDIO)
    → 已授予: SpeechRecognizer.createSpeechRecognizer() + startListening(intent)
  → RecognitionListener.onResults(matches)
    → currentOnResult(matches[0])     ← rememberUpdatedState 保持最新
      → ChatScreen.onVoiceResult { inputText += recognized }
        → OutlinedTextField 重组显示
```

### 面试考点

- `SpeechRecognizer` 直接 API 与 `ACTION_RECOGNIZE_SPEECH` Intent 的区别？何时选哪个？（控制力 vs 实现成本、权限归属）
- 为什么 `RECORD_AUDIO` 是 dangerous 权限？运行时申请的标准流程？
- `remember` 创建的 listener 中引用 Compose state setter 为什么安全？（MutableState 引用稳定；回调通过 `rememberUpdatedState` 保持最新）
- 为什么必须 `destroy()`？识别服务连接的生命周期与 Compose `DisposableEffect` 的关系。
- 模拟器上"识别器不可用"如何优雅降级？（`isRecognitionAvailable` + `onError` 全覆盖 + Toast 反馈）

### 影响文件

- `app/src/main/java/com/labsage/app/ui/chat/components/VoiceInputButton.kt`（新增）
- `app/src/main/java/com/labsage/app/ui/chat/components/ChatInputBar.kt`（加入按钮 + `onVoiceResult` 参数）
- `app/src/main/java/com/labsage/app/ui/chat/ChatScreen.kt`（传入 `onVoiceResult`，追加识别文本）
- `app/src/main/AndroidManifest.xml`（声明 `RECORD_AUDIO`）

---

## 二、工具执行面板升级（Tool Execution Panel）

### 功能概述

将原有的"最后 5 行 trace + 工具名"展示升级为**结构化的工具执行时间线**：

- **展开/折叠卡片**：每个工具调用一张卡，含状态徽标、调用序号、工具名、耗时；点击展开查看输入参数与输出摘要
- **时间线可视化**：纵向连线 + 状态节点（完成=✓绿、调用中=⏳脉冲、中断=✕红）
- **路由徽标**：在面板头部展示本次请求的路由决策（direct / react）
- **审批模式**：开启后，发送消息前弹窗确认（人机协同，防误触发工具）

任务定位为"只增强 traceLines 与 UI 渲染，不改变 ViewModel 结构"——实现上引入了 `toolCalls` / `activeRoute` 两个 StateFlow（仍属同一 ChatViewModel，未拆分），其余是数据解析与 UI。

| 属性 | 值 |
|------|-----|
| 数据模型 | `ToolCallUi`（`domain/model/ToolCall.kt`） |
| 解析 | `ChatViewModel.applyToolTrace()` 把 trace 文本解析为结构化对象 |
| UI | `ToolExecutionPanel`（`ui/chat/components/ToolExecutionPanel.kt`） |
| SSE 过滤 | 放宽为保留 `[tool]` / `[react]` / `[route]` 前缀的生命周期行 |

### 后端 trace 格式（数据来源）

后端 `utility/log_call_back/log_call_back.go` 发出的结构化 trace：

| trace 行 | 含义 |
|---------|------|
| `[react] 模型决定调用工具：name1 \| name2` | 路由到 ReAct，模型决定调用工具 |
| `[tool] 开始调用 #N <name> args=<json, ≤160>` | 工具开始执行，含序号与参数 |
| `[tool] 调用完成 <name> result=<json, ≤400>` | 工具完成，含结果摘要 |
| `[route] route=<direct\|react> intent=... confidence=...` | 路由决策 |

**关键修复**：原 `OkHttpSseDataSource` 的 trace 过滤只保留 `调用工具` / `调用完成` / `route=`，把带参数的 `开始调用` 行（不含这些关键字）过滤掉了，导致拿不到工具入参。现改为按前缀 `[tool]` / `[react]` / `[route` 过滤——这些都是有界的生命周期行（每个工具调用仅 2-3 条，参数/结果由后端截断到 160/400 字符），不会重蹈"200+ trace 撑爆 channel"的覆辙（见 BUGFIX_LOG #11），且 `.buffer(Channel.UNLIMITED)` 仍作兜底。

### 解析与配对策略

`调用完成` 行**不带调用序号**，只有工具名。同名工具可能被多次调用，因此按 **FIFO** 配对：完成事件匹配"最早的、仍在 CALLING 状态的同名条目"。

```
开始调用 #1 query_nvidia_gpu_usage args={...}   → 列表追加 CALLING#1
开始调用 #2 query_conda_env_usage args={...}    → 列表追加 CALLING#2
调用完成 query_conda_env_usage result={...}     → 命中 CALLING#2 → COMPLETED
调用完成 query_nvidia_gpu_usage result={...}    → 命中 CALLING#1 → COMPLETED
```

耗时用 `System.nanoTime()` 记录每个调用的起始纳秒，完成时 `(now - start)/1e6` 得到毫秒。流结束（Done）时仍在 CALLING 的条目标记为 ERROR（中断）。

### 审批模式（人机协同）

真正的"流中拦截工具调用"需要后端在每个工具调用前暂停并等待客户端确认（协议级改动）。当前实现为**客户端发送前确认闸门**——覆盖最常见场景：用户在发送可能触发工具的查询前二次确认。

- `ChatViewModel.approvalMode: StateFlow<Boolean>`：开关（Feature 4 接入持久化设置）
- 开启时 `sendMessage()` 不直接发送，而是把问题暂存到 `pendingSend`，UI 弹 `AlertDialog`
- "允许发送" → `confirmPendingSend()` → 实际发送；"取消" → 丢弃
- 快捷操作（GPU/Conda）同样经过此闸门（它们内部调用 `sendMessage`）

> 端到端流中工具拦截列为后续工作：需后端新增 `tool_call_pending` 事件 + 客户端响应通道。

### 数据流

```
SSE [tool]/[react]/[route] trace
  → OkHttpSseDataSource（按前缀过滤后转发）
    → ChatViewModel.handleSseEvent(Trace)
      → accumulatedTraces（保留，供路由兜底解析）
      → _traceLines（末 5 条，驱动头像旁内联 chip，行为不变）
      → applyToolTrace(line)
          → _toolCalls（结构化时间线）
          → _activeRoute（路由）
            → ChatScreen LazyColumn item: ToolExecutionPanel
```

### 面板状态生命周期

- 发送新消息 → `_toolCalls` / `_activeRoute` 重置
- 流式期间 → 工具调用逐条进入（CALLING → COMPLETED）
- Done → 剩余 CALLING 标记 ERROR；`_activeRoute` 定稿；面板保留（折叠态）供用户事后查看
- 下次发送 → 重置清空

面板默认折叠（`rememberSaveable`），避免抢夺对话流焦点；头部用 `✓N ⏳N ✕N` 紧凑徽标给出概览。

### 面试考点

- 为什么按"工具名 + FIFO"配对而非序号？（完成行不带序号）
- 放宽 trace 过滤会不会重现 channel 溢出？（前缀过滤的是有界生命周期行，非自由文本；`UNLIMITED` buffer 兜底）
- `rememberSaveable(call.key)` 控制每张卡的展开态：为什么 key 用调用标识而非固定值？（同名多次调用需独立展开）
- 审批模式为什么做在客户端发送前而非流中？（后端流式驱动，客户端无法暂停；真拦截需协议级支持）
- 时间线节点的脉冲动画如何实现不卡顿？（`rememberInfiniteTransition` + `animateFloat`，纯绘制，不触发重组扩散）

### 影响文件

- `app/src/main/java/com/labsage/app/domain/model/ToolCall.kt`（新增，`ToolCallUi` + `ToolCallStatus`）
- `app/src/main/java/com/labsage/app/ui/chat/components/ToolExecutionPanel.kt`（新增，替换旧 `TracePanel`）
- `app/src/main/java/com/labsage/app/ui/chat/components/TracePanel.kt`（删除，旧的无结构 trace 面板，从未被调用）
- `app/src/main/java/com/labsage/app/data/remote/OkHttpSseDataSource.kt`（trace 过滤放宽为前缀匹配）
- `app/src/main/java/com/labsage/app/ui/chat/ChatViewModel.kt`（`toolCalls`/`activeRoute` 状态、`applyToolTrace` 解析、审批模式 `approvalMode`/`pendingSend`、`sendMessage` 拆分为 `doSend`）
- `app/src/main/java/com/labsage/app/ui/chat/ChatScreen.kt`（渲染 `ToolExecutionPanel`、审批 `AlertDialog`）

---

## 三、对话组织（Conversation Organization）

### 功能概述

将会话列表从"纯时间倒序平铺"升级为可组织、可检索的结构：

- **搜索**：按标题模糊搜索（实时过滤）
- **置顶**：重要会话置顶，单独成组且始终在最前
- **重命名**：就地重命名会话标题
- **日期分组**：未置顶会话按 今天 / 昨天 / 更早 分组

| 属性 | 值 |
|------|-----|
| 新增列 | `conversations.is_pinned`（DB v1 → v2 迁移） |
| 搜索 | DAO `LIKE '%query%'`，`flatMapLatest` 在"全量/搜索"间切换 |
| 分组 | `groupConversations()` 在 UI 层按 pinned + 日期分桶 |
| 持久化 | Room（置顶/重命名即时落库，列表 Flow 自动刷新） |

### 数据库迁移（v1 → v2）

新增 `is_pinned INTEGER NOT NULL DEFAULT 0` 列：

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0")
    }
}
```

- `ALTER TABLE ADD COLUMN` 用 `DEFAULT 0` 回填现有行 → 老会话全部"未置顶"，数据无损
- 在 `Room.databaseBuilder(...).addMigrations(MIGRATION_1_2)` 注册，避免 `fallbackToDestructiveMigration` 清空用户历史

### 排序与分组

排序在 SQL 完成（`ORDER BY is_pinned DESC, updated_at DESC`），UI 只做分组，避免在 Kotlin 里反复全排序：

```
置顶组（is_pinned=1，按 updated_at 倒序）
今天组（updated_at >= 今日 0 点）
昨天组（昨日 0 点 <= updated_at < 今日 0 点）
更早组（其余）
```

日期边界用 `Calendar` 取本地时区今日 0 点，避免跨时区错位。

### 搜索的数据流

```kotlin
val conversations = searchQuery
    .flatMapLatest { q ->
        if (q.isBlank()) repository.getAllConversations()
        else repository.searchConversations(q.trim())
    }
    .stateIn(...)
```

- `flatMapLatest`：查询词变化时取消上一个 Flow、切到新 Flow（避免旧搜索结果错位到达）
- 搜索态下不分日期组，返回单一平铺结果，减少视觉噪音

### 重命名的细节

重命名用独立的 `UPDATE conversations SET title=:title`（仅改 title），**不**走 `updateMeta`（它同时改 `updated_at`）。这样重命名不会把会话"顶到今天组"——只有真正有新消息时才应上浮。

### 面试考点

- Room 迁移为什么写 `Migration` 而非 `fallbackToDestructiveMigration`？（保留用户数据；destructive 会清库）
- `flatMapLatest` vs `switchMap`/`map`？（查询切换时取消旧 Flow，防止竞态）
- 为什么排序放 SQL 而非 UI？（索引 + 单次排序；UI 只分桶，列表大时更省）
- `is_pinned` 为什么用 INTEGER DEFAULT 0 而非 nullable？（Boolean 语义明确，老数据 0 = 未置顶，无需处理 null）
- 重命名为什么不更新 `updated_at`？（语义：重命名 ≠ 活跃；避免列表意外重排）

### 影响文件

- `app/src/main/java/com/labsage/app/data/local/entity/ConversationEntity.kt`（`isPinned` 字段）
- `app/src/main/java/com/labsage/app/data/local/dao/ConversationDao.kt`（排序、搜索、`updateTitle`、`setPinned`）
- `app/src/main/java/com/labsage/app/data/local/AppDatabase.kt`（v2 + `MIGRATION_1_2`）
- `app/src/main/java/com/labsage/app/LabSageApplication.kt`（注册迁移）
- `app/src/main/java/com/labsage/app/data/repository/ChatRepository.kt`（`searchConversations`、`setConversationPinned`、`renameConversation`）
- `app/src/main/java/com/labsage/app/ui/conversation/ConversationListViewModel.kt`（搜索 Flow、pin/rename 操作）
- `app/src/main/java/com/labsage/app/ui/conversation/ConversationListScreen.kt`（搜索栏、分组、溢出菜单、重命名对话框）
