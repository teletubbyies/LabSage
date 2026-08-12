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

---

## 四、设置 / 偏好页面（Settings）

### 功能概述

把原先硬编码在 `Application` 中的后端地址提取为用户可配置项，并新增完整的设置页面。所有设置通过 **DataStore Preferences** 持久化，即时生效：

| 配置项 | 说明 | 生效方式 |
|--------|------|---------|
| **后端地址** | 替换硬编码 `10.0.2.2:6872` | 改后新请求即时生效（无需重启） |
| **主题** | 跟随系统 / 浅色 / 深色（系统跟随已具备，新增手动覆盖） | 整树重组 |
| **模型** | 首选模型下拉（`deepseek-reasoner` / `deepseek-chat`） | 实验性，需后端读取生效 |
| **工具审批模式** | 开关；开启后发送前确认（见功能二） | 立即 |
| **默认快捷操作** | 勾选 GPU / Conda 快捷 chip | 输入栏即时更新 |
| **连接测试** | 短超时 GET 探活，显示延迟或错误 | 手动触发 |

| 属性 | 值 |
|------|-----|
| 持久化 | `androidx.datastore:datastore-preferences` |
| 状态 | `LabSageApplication.appSettings: StateFlow<AppSettings>` |
| ViewModel | `SettingsViewModel` + `ChatViewModel`（审批模式同步） |
| UI | `SettingsScreen`（`ui/settings/`） |

### 为什么用 DataStore 而非 Room / SharedPreferences

- **vs Room**：设置是扁平键值，无关系/查询需求；Room 的 schema 迁移成本不划算。
- **vs SharedPreferences**：DataStore 协程/Flow 原生、线程安全、无 ANR 风险（SP 的 `commit()`/`apply()` 在 IO 阻塞与一致性问题上有坑）。
- Preferences 变体足够（结构化数据才用 Proto 变体）。

### 后端地址热切换 —— 按 URL 缓存网络层

旧实现 `retrofit`/`api`/`sseDataSource` 都是 `by lazy` 单例，URL 写死。改为**按 base URL 缓存**：

```kotlin
private val networkCache = ConcurrentHashMap<String, NetworkLayer>()
private data class NetworkLayer(val api: LabSageApi, val sse: SseDataSource)

val api: LabSageApi get() = networkLayerFor(appSettings.value.baseUrl).api
val sseDataSource: SseDataSource get() = networkLayerFor(appSettings.value.baseUrl).sse
```

- `ChatRepository` 不再持有 `api`/`sseDataSource`，而是持有 `app`，每次调用走 `app.api` / `app.sseDataSource` → 读当前 `appSettings.value.baseUrl`。
- URL 变更后，下次请求 `networkLayerFor(newUrl)` 命中新条目（新建 Retrofit + SSE），旧条目保留（连接池复用，无泄漏）。
- 无需重启 App，新请求立即指向新后端。

URL 归一化：补 `http://` 前缀、补结尾 `/`（Retrofit 要求），SSE 端去尾 `/`。

### 主题：从设置驱动整树

`MainActivity.LabSageRoot` 收集 `appSettings`，把 `themeMode` 映射为 `darkTheme: Boolean` 再交给 `LabSageTheme`：

```kotlin
val darkTheme = when (settings.themeMode) {
    SYSTEM -> isSystemInDarkTheme()
    LIGHT  -> false
    DARK   -> true
}
LabSageTheme(darkTheme = darkTheme) { LabSageNavGraph() }
```

设置页改主题 → `appSettings` 发射 → `LabSageRoot` 重组 → 整棵树换色。无需 Activity 重建。

### 审批模式：持久化的单一事实源

`ChatViewModel` 在 `init` 中 collect `settingsRepository.settings`，把 `approvalMode` 同步到本地 `_approvalMode`；`setApprovalMode()` 写 DataStore（不再直接改本地 state）。设置页开关与聊天页审批行为共用同一持久化值。

### 快捷操作：数据驱动的 chip

`ChatInputBar` 不再写死 GPU/Conda，改收 `quickChips: List<QuickChip>`；`ChatScreen` 从 `settings.quickActions` 构建（GPU→`queryGpuStatus`、Conda→`queryCondaEnv`）。设置页勾选即时反映到输入栏。

### 连接测试

`SettingsViewModel.testConnection(url)` 用一个 5s 超时的独立 `OkHttpClient` GET 目标 URL。**任何 HTTP 响应**都算可达（即便 404，说明服务在线）；连接拒绝/超时 → 失败。显示延迟毫秒或错误信息。

### 模型选择（实验性）

UI 下拉存到 DataStore；后端当前按固定函数构造模型（`OpenAIForDeepSeekV31Think` / `OpenAIForDeepSeekV3Quick`），不读该字段。要让选择真正生效，需后端在 `chat_stream` 请求中接收并据此选模型（已在 UI 与文档标注"实验性 · 需后端配合"）。这是设置页"可能需改后端"的那一项，留作后端扩展点。

### 面试考点

- DataStore vs SharedPreferences vs Room（一致性、线程模型、适用场景）
- `ConcurrentHashMap.getOrPut` 的原子性？这里为什么可接受（构建幂等，多建一次无副作用）
- 主题热切换如何不重建 Activity 就换色？（StateFlow 驱动 CompositionLocal/MaterialTheme 重组）
- 后端地址热切换为什么不直接 `by lazy` 重建？（lazy 单例不可重置；按 URL 缓存 + 每次读 `appSettings.value` 才能跟随设置变化）
- 协程/Flow 在 ViewModel 中的生命周期（`viewModelScope` + `stateIn(WhileSubscribed)`）

### 影响文件

- `gradle/libs.versions.toml`、`app/build.gradle.kts`（新增 datastore-preferences 依赖）
- `app/src/main/java/com/labsage/app/domain/model/AppSettings.kt`（新增：`AppSettings`/`ThemeMode`/`QuickAction`）
- `app/src/main/java/com/labsage/app/data/repository/SettingsRepository.kt`（新增：DataStore 读写）
- `app/src/main/java/com/labsage/app/LabSageApplication.kt`（`appSettings` StateFlow、按 URL 缓存网络层、`ChatRepository` 改注入 app、`settingsViewModelFactory`）
- `app/src/main/java/com/labsage/app/data/repository/ChatRepository.kt`（构造改为 `app`，远程层经 `app.api`/`app.sseDataSource`）
- `app/src/main/java/com/labsage/app/ui/chat/ChatViewModel.kt`（注入 `SettingsRepository`、审批模式持久化同步）
- `app/src/main/java/com/labsage/app/ui/chat/components/ChatInputBar.kt`（`quickChips` 数据驱动）
- `app/src/main/java/com/labsage/app/ui/chat/ChatScreen.kt`（从设置构建 quickChips）
- `app/src/main/java/com/labsage/app/ui/settings/SettingsViewModel.kt`（新增）
- `app/src/main/java/com/labsage/app/ui/settings/SettingsScreen.kt`（新增）
- `app/src/main/java/com/labsage/app/MainActivity.kt`（主题驱动 + 设置路由）
- `app/src/main/java/com/labsage/app/ui/conversation/ConversationListScreen.kt`（设置入口齿轮）

---

## 五、长回答完成通知（Android Notification）

### 功能概述

当 SSE 长回答正常生成完成后，应用发布一条带正文摘要的系统通知；点击通知会打开对应会话，并精确滚动到该条 assistant 消息。短回答不通知，避免频繁打扰。

| 属性 | 值 |
|------|-----|
| 通知渠道 | `long_response_completed`，`IMPORTANCE_DEFAULT` |
| 长回答阈值 | 300 个非空白字符 |
| 摘要长度 | 最多 180 字符，清理常见 Markdown 标记 |
| 定位参数 | `conversationId + messageId` |
| Android 13+ | 动态申请 `POST_NOTIFICATIONS` |

### 完成事件与通知时序

```text
SseEvent.Done
  → finalizeAssistantMessage(messageId, finalAnswer)  // 先完成 Room 落库
  → ResponseNotificationManager.notifyResponseCompleted(...)
    → 长度策略过滤 + 生成纯文本摘要
    → PendingIntent(MainActivity, conversationId, messageId)
    → NotificationManagerCompat.notify(...)
```

必须先落库再通知。否则用户在通知发布后立即点击时，目标 `messageId` 可能尚未出现在 Room Flow 中，界面无法定位。

### 通知渠道与权限

- `LabSageApplication.onCreate()` 在 API 26+ 创建渠道。渠道 ID 一旦发布应保持稳定；渠道重要性后续由用户在系统设置中控制，应用不能强行覆盖。
- API 33+ 需要运行时权限 `POST_NOTIFICATIONS`。`ChatScreen` 在用户真正开始生成时请求，而不是 App 冷启动时突兀弹窗。
- 发布前再次检查权限，并捕获权限检查与 `notify()` 之间可能发生撤权造成的 `SecurityException`。通知失败不能影响消息落库和 `StreamState.Completed`。

### 点击通知后的精确定位

通知的 `PendingIntent` 带有自定义 action 和两个 extra：

```text
ACTION_OPEN_RESPONSE
  conversation_id = <会话 ID>
  message_id       = <Room 消息主键>
```

`MainActivity` 同时覆盖两条入口：

- **冷启动**：`onCreate()` 解析初始 Intent。
- **Activity 已存在**：`FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP` 让点击进入 `onNewIntent()`。

解析结果被映射到可选路由 `chat/{conversationId}?messageId={messageId}`。`ChatScreen` 等待 Room 首次发射完整消息列表，找到目标消息索引后调用 `LazyListState.scrollToItem(index)`。等待期间会暂停原有的“新列表自动滚到底部”逻辑，避免两个滚动 Effect 竞争。

### 架构决策

- **通知策略与 Android API 分离**：`ResponseNotificationContent` 是纯 Kotlin，负责阈值和摘要，可直接做 JVM 单元测试；`ResponseNotificationManager` 只负责渠道、权限、Intent 和发布。
- **使用 Room 主键定位**：不用“滚到会话底部”近似代替。即使目标回答后面已经出现新消息，仍能回到准确位置。
- **通知为 best-effort 副作用**：权限关闭或系统拒绝通知时静默跳过，不回滚已完成的回答。

### 验证

- `ResponseNotificationContentTest` 覆盖阈值、Markdown 清理、摘要截断。
- `./gradlew testDebugUnitTest assembleDebug` 通过。

### 影响文件

- `app/src/main/java/com/labsage/app/notification/ResponseNotificationContent.kt`（新增）
- `app/src/main/java/com/labsage/app/notification/ResponseNotificationManager.kt`（新增）
- `app/src/main/java/com/labsage/app/LabSageApplication.kt`（创建渠道、注入通知管理器）
- `app/src/main/java/com/labsage/app/ui/chat/ChatViewModel.kt`（回答落库后发布完成通知）
- `app/src/main/java/com/labsage/app/MainActivity.kt`（冷/热启动 Intent 与消息路由）
- `app/src/main/java/com/labsage/app/ui/chat/ChatScreen.kt`（权限请求、目标消息滚动）
- `app/src/main/AndroidManifest.xml`（声明通知权限）
- `app/src/main/res/drawable/ic_notification.xml`（通知小图标）
- `app/src/test/java/com/labsage/app/notification/ResponseNotificationContentTest.kt`（新增）

### 面试考点

- NotificationChannel 为什么必须在 API 26+ 创建？渠道 ID、重要性由谁控制？
- `PendingIntent.FLAG_IMMUTABLE` 与 `FLAG_UPDATE_CURRENT` 分别解决什么问题？
- Android 13 的 `POST_NOTIFICATIONS` 与清单权限、运行时权限是什么关系？
- `onCreate()` 与 `onNewIntent()` 为什么都要处理通知入口？`singleTop` 的行为是什么？
- 为什么导航参数必须同时带 `conversationId` 和 `messageId`？
- Compose 中多个 `LaunchedEffect` 同时控制同一个 `LazyListState` 会有什么竞态？本功能如何规避？


## 六、文件 / 知识库管理页（Knowledge File Management）

### 功能概述

新增一个独立页面，从后端列出知识库文件、支持按文件名搜索、以及**真实的后端删除**（不可撤销，带确认）。入口在会话列表 TopAppBar 的 📁 图标。此前的「文件上传」只把文件 `POST /api/upload` 进后端全局知识库、本地零记录，用户上传后既看不到也删不掉；本页补上了「管理」这一半。

| 属性 | 值 |
|------|-----|
| 页面 | `FileManagementScreen`（`ui/files/FileManagementScreen.kt`） |
| ViewModel | `FileManagementViewModel`（状态机 `Loading/Success/Error` + `refreshing` + 乐观删除） |
| 数据来源 | **后端权威**（`GET /api/files`），remote-first，**v1 不引入本地 Room 文件表** |
| 删除 | `DELETE /api/files/{id}`，乐观删除 + 失败回滚 + 确认 Dialog |
| 入口 | `ConversationListScreen` TopAppBar `Folder` 图标 → `NavRoutes.FILES` |
| 后端契约 | `docs/files-api-contract.md`（GET/DELETE/upload 增 fileId + Go 参考实现） |

### 架构决策

| 决策点 | 选择 | 理由 |
|------|------|------|
| 列表数据源 | **remote-first（后端权威，内存 StateFlow）** | 文件是后端资源；引入本地 Room 表会带来「本地/远端同步 + 分歧」的复杂度，v1 不值得。与 SSE 不走 Room、`SettingsViewModel` 状态机一致 |
| 删除模型 | **乐观删除 + 失败回滚**（不提供 Undo） | 后端真删、文件内容只在服务器，删除不可逆 → Undo 无法实现（无法通过 API 恢复）。乐观删除保证即时反馈，失败回滚 + snackbar 保证一致性 |
| 刷新模型 | **后台刷新（保留列表 + 指示器）**，区分初次加载 | 初次/重试用全屏 `Loading`（无内容可留）；刷新保留当前列表、失败只弹 snackbar，不擦掉用户正在看的数据 |
| 文件列表来源类型 | Retrofit `@GET`（非 SSE） | 列表是普通请求-响应，不经 `SseDataSource` 接口抽象，与 `chat/upload/session` 一致 |

### 关键实现点

1. **状态机驱动 UI**：`UiState { Loading; Success(files); Error(msg) }` 三态分别渲染加载圈 / 列表 / 错误+重试，镜像 `SettingsViewModel` 连接测试、`UploadState` 的写法。
2. **乐观删除 + 回滚**：`delete(file)` 先同步从 `Success.files` 移除（`viewModelScope` 在主线程，state 变更无并发问题），再挂起调 `deleteFile`，失败时把该行按 `uploadedAt` 倒序插回 + 发 `DeleteFailed` 事件。`fileId` 为空时直接 return（兼容未升级的后端）。
3. **后台刷新**：`refreshing: StateFlow<Boolean>` 单独驱动指示器；`fetch(replaceOnError)` 一个私有方法同时服务初次加载（replaceOnError=true → 落 Error）和刷新（replaceOnError=false → 保留列表 + 发 `LoadFailed`）。`if (_refreshing.value) return` 防重复并发刷新。
4. **PullToRefreshBox + 刷新按钮双入口**：列表外包 `PullToRefreshBox(isRefreshing, onRefresh)`（手势下拉），TopAppBar 刷新按钮在 `refreshing` 时换成 `CircularProgressIndicator`、并 `enabled = !refreshing`。两入口都走 `viewModel.refresh()`。
5. **删除确认 Dialog**：`AlertDialog`，确认才 `viewModel.delete`。后端删除不可逆，必须有这一步。
6. **抽取 `formatFileSize`**：从 `ChatViewModel` 私有方法提到顶层 `util/Format.kt`，上传提示与文件行复用，避免重复。
7. **`UploadData.fileId` 可空兼容**：旧后端不返回 `fileId` 时客户端不崩；契约要求后端补 `fileId` 才能端到端删除。

### 数据流

```
会话列表 TopAppBar 📁  →  NavRoutes.FILES  →  FileManagementScreen
  init { load() }  →  GET /api/files  →  Success(files) → LazyColumn
  下拉 / 刷新按钮  →  refresh()  →  后台 GET（保留列表 + refreshing 指示器）
  搜索框          →  setSearchQuery  →  remember(files, query) 过滤
  行删除按钮       →  确认 Dialog  →  delete(file)
                      ├ 乐观移除（即时）
                      └ DELETE /api/files/{id}
                          ├ 成功：保持移除
                          └ 失败：按时间插回 + snackbar「删除失败，已恢复」
```

### 面试考点

- 为什么文件页用 remote-first 而不像会话/消息那样走 Room？什么资源适合「后端权威」vs「本地缓存」？
- 乐观删除为什么不做 Undo？什么场景下 Undo 才有意义（vs 会话列表的 stage/commit/undo）？
- 刷新为何不能复用初次加载的全屏 `Loading`？后台刷新如何避免「擦掉用户正在看的数据」？
- `PullToRefreshBox` 的实现原理（嵌套滚动 `NestedScrollConnection`）？为何要包在可滚动容器外？
- `viewModelScope` 默认在哪个调度器？为什么乐观删除里「先改 state 再挂起」是线程安全的？
- 删除失败回滚时，为什么用「按 `uploadedAt` 倒序插回」而不是直接还原原 list？

| **影响文件** | `ApiModels.kt`、`LabSageApi.kt`、`ChatRepository.kt`、`util/Format.kt`（新）、`ui/files/FileManagementViewModel.kt`（新）、`ui/files/FileManagementScreen.kt`（新）、`MainActivity.kt`、`ConversationListScreen.kt`、`LabSageApplication.kt`、`strings.xml`、`docs/files-api-contract.md`（新） |

---

## 七、Compose 性能优化 + 重组实测（Recomposition Benchmark）

### 功能概述

流式生成时，Room 每攒够数据就 emit 一次全量消息列表，峰值约 50 次/响应，每次 emit 都可能触发 Compose 重组。本优化做两件事：

1. **新增一项低入侵性能修复**：把 `MessageBubble` 变成**可跳过（skippable）**的 composable，避免流式时历史气泡被反复重组（含 MarkdownText 重新解析）。
2. **新增一套 instrumented 重组测量测试**（`app/src/androidTest/.../ui/perf/`），用真实数据验证 `conflate` / `@Immutable`+`key` / `derivedStateOf` / 上述 MessageBubble 修复各自的收益。

| 属性 | 值 |
|------|-----|
| 修复点 | `ChatScreen.kt`：`combinedClickable` 从 `MessageBubble.modifier` 移到外层 `Box` |
| 测试 | `ConflateBackpressureTest.kt`、`ComposeRecompositionTest.kt`（androidTest） |
| 测量仪器 | 纯协程 `collect` 触发计数 + `SideEffect { counter++ }` 重组计数（语义同 Layout Inspector） |

### 架构决策：为什么 combinedClickable 会破坏 skip

`MessageBubble(role, content, modifier, isStreaming, traceLines)` 原本接收 `modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { showMenu = true })`。`combinedClickable` 产生的 modifier 元素按其持有的 lambda **引用**判等；而 `onClick={}` / `onLongClick={...}` 每次重组都是**新 lambda 实例** ⇒ modifier 元素「变了」 ⇒ `MessageBubble` 的 `modifier` 参数每次都被判定为 changed ⇒ composable 不可跳过 ⇒ 列表里任一消息更新，**所有可见气泡全部重组**。

修复：把 `combinedClickable` 挪到外层 `Box`（外层重组很轻），`MessageBubble` 退回默认 `Modifier`（常量、稳定），其余参数本就稳定（`MessageUiState` 全稳定类型 + `traceLines`），于是历史气泡可被跳过，只有正在流式的那条重组。

> 反直觉点（实测推翻教条）：原本怀疑是 `traceLines: List<String>` 这个 List 参数让 `MessageBubble` 不可跳过，但实测「带 List 字段的 data class」改 1 行仍只 1/10 重组——本版本 Compose 并未把 `kotlin.collections.List` 判 unstable。**真凶是 modifier 的新 lambda**。这条结论是用「隔离变量」法（同一 composable，只改 modifier 传不传）测出来的。

### 真实数据（emulator-5554, API 37）

| 实验 | 实测结果 |
|------|----------|
| `conflate` 背压（生产 ~50/s、消费 ~10/s） | collect 触发 **50 → 12**（76% 中间态被丢，无损） |
| `conflate` happy path 对照（消费跟得上） | **50 → 50**（零丢弃，证明是背压兜底非采样器） |
| `@Immutable` + `key` 列表，改 1 行 | 重组 **10 → 1** 行 |
| `derivedStateOf`，源 0→20 | 读者重组 **21 → 2** |
| MessageBubble 现状（combinedClickable 当 modifier 传入） | **8/8** 气泡全重组 |
| MessageBubble 修复后（clickable 移到外层 Box） | **1/8** |

复现：

```bash
./gradlew :app:connectedDebugAndroidTest
adb logcat -d -s PerfBench:I
```

### 关键实现点

1. **测量方法**：
   - `conflate` 用**纯协程测试**测 collect 触发率（`collect` 里每 `_messages.value = x` 1:1 触发一次重组 ⇒ collect 触发率 = 重组触发率），最忠实。
   - 重组用 `SideEffect { counter++ }`——它在 composable 每次成功提交的（重）组合后跑一次，被跳过则不跑，语义等同 Layout Inspector 重组计数。
2. **必须制造背压**：快模拟器上消费端跟得上，`conflate` 零丢弃（这是**正确**表现，非「无用」）。要观测收益得人为把消费端钉成瓶颈（`delay` 模拟主线程被占住）。
3. **espresso 3.7.0**：Compose 的 `createComposeRule()` 把 app-idle 同步走 `Espresso.onIdle()`，而 espresso 3.6.x 的输入注入器反射调用 `InputManager.getInstance()`，在 Android 16（API 36+）已被移除。`ui-test-junit4` 传递依赖钉了旧版 espresso，故显式强制 `espresso-core:3.7.0` + `ext:junit:1.3.0`。

### 面试考点

- 流式生成时你做了哪些 Compose 性能优化？怎么验证的？（四层 + instrumented test 实测数字）
- `conflate` 和 `buffer(UNLIMITED)` 区别？为什么 Room 那条链用 conflate、SSE 那条用 buffer？（state vs event 语义）
- 为什么快机上测不出 `conflate` 效果？怎么制造背压测？（happy path 测不出 unhappy path 价值）
- `MessageBubble` 不可跳过你怎么定位的？（隔离变量法：只改 modifier 传不传，8/8 vs 1/8）
- `SideEffect` 计数器为什么等于重组次数？被跳过时会怎样？（不跑，同 Layout Inspector 语义）

| **影响文件** | `ui/chat/ChatScreen.kt`（clickable 移到外层 Box）、`app/src/androidTest/java/com/labsage/app/ui/perf/ConflateBackpressureTest.kt`（新）、`…/ComposeRecompositionTest.kt`（新）、`app/build.gradle.kts`（espresso 3.7.0）、`gradle/libs.versions.toml`（junitExt/espressoCore 升级） |
