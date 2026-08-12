# LabSage 项目面试全程指南（小白向 · 由浅入深）

> **写给谁看**：你已经看过《第一行代码》，知道 Activity、ViewModel、Room、OkHttp、Retrofit、协程这些东西**各自怎么用**，但还没在真实项目里把它们**串起来**，也不知道面试官会怎么考。
>
> **怎么用这篇文档**：每章都按「**概念铺垫（小白向）→ 项目里的真实代码 → 面试考点**」三段式。面试前一晚从第 11 章开始刷问答；平时学习从第 0 章顺着读。
>
> **项目本身**：一个 AI 聊天机器人 Android 客户端，对话接的是后端的 LLM（大模型），重点是**流式输出（打字机效果）**和**工具调用可视化**。约 4142 行 Kotlin，33 个源文件，独立开发。

---

## 目录

- [第 0 章 这个项目到底在做什么](#第-0-章-这个项目到底在做什么)
- [第 1 章 30 秒鸟瞰：技术栈与架构全景](#第-1-章-30-秒鸟瞰技术栈与架构全景)
- [第 2 章 架构分层：为什么是 MVVM + Repository + 手动 DI](#第-2-章-架构分层为什么是-mvvm--repository--手动-di)
- [第 3 章 主线：一条消息的完整生命周期](#第-3-章-主线一条消息的完整生命周期)
- [第 4 章 SSE 流式——整个项目的灵魂（面试杀手锏）](#第-4-章-sse-流式整个项目的灵魂面试杀手锏)
- [第 5 章 Room 数据持久化——消息怎么存下来](#第-5-章-room-数据持久化消息怎么存下来)
- [第 6 章 Compose + 状态管理——UI 怎么动起来](#第-6-章-compose--状态管理ui-怎么动起来)
- [第 7 章 依赖注入与网络层（Application）](#第-7-章-依赖注入与网络层application)
- [第 8 章 其它功能速览](#第-8-章-其它功能速览)
- [第 9 章 Bug 实战复盘——面试讲故事的弹药库](#第-9-章-bug-实战复盘面试讲故事的弹药库)
- [第 10 章 技术选型决策表（面试"为什么"合集）](#第-10-章-技术选型决策表面试为什么合集)
- [第 11 章 面试问答大全（按主题）](#第-11-章-面试问答大全按主题)
- [第 12 章 自我陈述模板（30 秒 / 2 分钟 / 5 分钟）](#第-12-章-自我陈述模板30-秒--2-分钟--5-分钟)
- [附录 高频名词速查表](#附录-高频名词速查表)

---

## 第 0 章 这个项目到底在做什么

### 0.1 一句话定位

> **LabSage 是一个面向实验室运维场景的「AI 知识助手」Android 客户端**。用户在手机上像用 ChatGPT 一样提问，App 把问题发给后端的大模型，模型**一个字一个字地流式返回**答案，并在回答前**调用一些工具**（比如查 GPU 占用、查 conda 环境），App 把这个"思考+调工具"的过程**实时可视化**出来。

### 0.2 背景（为什么有这个项目）

后端团队用 **Go + Milvus（向量数据库）+ ReAct Agent** 搭了一个 AI 知识助手，已经提供了 HTTP 接口，但**只有后端没有客户端**。需要从零做一个 Android App，作为团队对外演示的产品。这就是你要讲的项目。

> **小白翻译**：
> - **ReAct Agent**：一种让大模型"边想边干活"的模式。模型不是直接回答，而是先决定"我需要调用某个工具"，调用完拿到结果，再组织语言回答。LabSage 里这个工具就是查实验室服务器状态（GPU、conda 环境）。
> - **Milvus / 向量数据库**：存知识的。用户上传文档后切成向量存进去，提问时按相似度检索。**这是后端的事，Android 端不直接碰**，你只要知道"后端会查知识库"即可。
> - **流式输出**：就是 ChatGPT 那种答案一个字一个字蹦出来的效果。技术上叫 **SSE（Server-Sent Events）**，是整个项目的技术核心，第 4 章详讲。

### 0.3 这个项目能讲多少东西（为什么值得作为面试项目）

一个聊天 App 看似简单，但因为**流式 + Agent 工具调用**这两个特性，它踩中了 Android 面试几乎所有高频考点：

| 面试考点 | 在本项目里的体现 |
|---------|----------------|
| 架构（MVVM/分层） | 整个项目就是 MVVM + Repository |
| 协程 & Flow | SSE 流用 `callbackFlow` 接，背压、取消全涉及 |
| 网络（OkHttp/Retrofit） | 长连接 SSE + 短请求 REST 分离 |
| 数据库（Room） | 双表外键级联、流式增量写入、数据库迁移 |
| Compose | 声明式 UI、状态管理、性能优化、暗黑模式 |
| 状态管理 | StateFlow + sealed class 状态机 |
| 依赖注入 | 手动 DI vs Hilt 的选型 |
| 性能优化 | 50 次/秒的 UI 更新怎么不卡 |
| 工程能力 | 12+ 个 Bug 的根因分析 |

**一句话**：这个项目小而全，能把八股文全部"落地"到真实代码里，面试官问什么你都能指着代码答。

---

## 第 1 章 30 秒鸟瞰：技术栈与架构全景

### 1.1 技术栈一张表

| 类别 | 技术 | 版本 | 一句话作用 |
|------|------|------|-----------|
| 语言 | **Kotlin** | 2.2.10 | Android 官方首选语言 |
| UI | **Jetpack Compose** + Material 3 | BOM 2025.06.01 | 声明式 UI 框架（替代 XML） |
| 架构 | **MVVM** + 手动 DI | — | 分层 + 手工管理依赖 |
| 网络 | **OkHttp**（含 `okhttp-sse`）+ **Retrofit** | 4.12.0 / 2.11.0 | SSE 长连接 + REST 短请求 |
| 数据库 | **Room** | 2.7.0 | SQLite 的封装，存对话历史 |
| 偏好设置 | **DataStore Preferences** | 1.1.7 | 存用户设置（后端地址、主题等） |
| 异步 | **Kotlin 协程 / Flow** | 1.10.1 | 处理流式、网络、数据库回调 |
| Markdown | **jeziellago/compose-markdown** | 0.5.7 | 渲染 AI 回答里的 Markdown |
| 构建 | Gradle + AGP + KSP | 9.4.1 / 9.2.1 | 构建系统 |
| 最低 SDK | API 24（Android 7.0） | — | 覆盖绝大多数设备 |

### 1.2 架构全景图

```
┌─────────────────────────────────────────────────────────┐
│  UI 层 (Jetpack Compose)                                │
│  ChatScreen / ConversationListScreen / SettingsScreen   │
│  └─ MessageBubble / ChatInputBar / ToolExecutionPanel   │
├─────────────────────────────────────────────────────────┤
│  ViewModel 层                                           │
│  ChatViewModel / ConversationListViewModel              │
│  └─ 持有 StateFlow<StreamState> + List<MessageUi>       │
├─────────────────────────────────────────────────────────┤
│  Repository 层 (单一数据出口)                            │
│  ChatRepository  ← ViewModel 只跟它说话                  │
├──────────────────────┬──────────────────────────────────┤
│  远程数据源 Remote    │  本地数据源 Local                 │
│  OkHttpSseDataSource │  Room 数据库                      │
│  (SSE 流式)          │  ├─ ConversationDao               │
│  LabSageApi          │  └─ MessageDao                    │
│  (Retrofit, REST)    │                                  │
└──────────────────────┴──────────────────────────────────┘
```

**三层记住一个口诀**：**UI 只看 ViewModel，ViewModel 只找 Repository，Repository 协调「网络」和「数据库」两个数据源**。这是 Android 官方推荐的架构，也是面试默认答案。

### 1.3 包结构（目录即架构）

```
com.labsage.app/
├── LabSageApplication.kt     # 入口：手动 DI 容器，所有单例在这创建
├── MainActivity.kt           # 唯一的 Activity，承载 Compose
├── data/                     # 数据层
│   ├── remote/               #   网络：SSE 解析、Retrofit 接口、API 模型
│   ├── local/                #   数据库：Room 实体、DAO、Database
│   └── repository/           #   ChatRepository / SettingsRepository
├── domain/                   # 领域层（纯模型，不依赖框架）
│   └── model/                #   StreamState(状态机) / AppSettings / ToolCall
└── ui/                       # UI 层
    ├── theme/                #   Material 3 主题、颜色、字体
    ├── conversation/         #   会话列表页
    ├── chat/                 #   聊天页 + components/ 子组件
    └── settings/             #   设置页
```

> **小白要点**：`data`/`domain`/`ui` 这种分包叫**按层分包**（layer-based），好处是"同一类东西放一起"。也有项目按功能分包（feature-based）。面试可以说："我用的是经典的按层分包，因为模块不多，按层更清晰。"

---

## 第 2 章 架构分层：为什么是 MVVM + Repository + 手动 DI

### 2.1 为什么是 MVVM（而不是 MVC / MVP）

> **小白概念**：MVVM = **Model - View - ViewModel**。
> - **View**：UI，只负责"显示"和"把用户操作告诉 ViewModel"。在 Compose 里就是各种 `@Composable` 函数。
> - **ViewModel**：持有 UI 需要的数据（状态），处理逻辑，**绝不动 UI 控件**。屏幕旋转它也不死。
> - **Model**：数据来源，这里就是 Repository。

**MVVM 的核心好处（面试必答）**：

1. **ViewModel 不持有 View 的引用** → 不会内存泄漏，不会因为 View 销毁了还去更新它而崩溃。
2. **ViewModel 能活过配置变更**（屏幕旋转）→ 数据不丢失。
3. **数据用"可观察的状态"暴露**（StateFlow）→ UI 订阅它，状态一变 UI 自动更新，不用手动 `setText()`。

**和《第一行代码》里的区别**：《第一行代码》早期章节用 MVC（Activity 又当 View 又当 Controller，容易臃肿）。MVVM 把逻辑挪到 ViewModel，Activity/Compose 只管显示。

```
对比：
  MVC  ：Activity 又是 View 又是 Controller → Activity 几千行，没法测
  MVP  ：Presenter 持有 View 接口 → 要写一堆接口，且 Presenter 和 View 生命周期绑定（容易泄漏）
  MVVM ：ViewModel 通过 StateFlow 推数据给 View → 解耦最彻底 ✅
```

### 2.2 为什么要有 Repository 这一层

> **小白疑问**：ViewModel 不直接调 DAO（数据库）和 API（网络）吗？为什么中间加个 Repository？

**答案**：Repository 是**"单一数据出口"**。它的价值有三点：

1. **封装数据来源的复杂性**。比如一条消息可能来自本地缓存，也可能要联网拉。ViewModel 不该关心"这次从哪取"，Repository 来决定。
2. **ViewModel 可测试**。测试 ViewModel 时，可以给一个假的 Repository，不用真连数据库。
3. **多个数据源的协调**。本项目里，发一条消息既要写 Room（存历史）、又要开 SSE 流（拿答案）——这俩动作被 `ChatRepository.sendStreamMessage()` 封装成一个对外接口。

看真实代码（`ChatRepository.kt`）：

```kotlin
class ChatRepository(
    private val app: LabSageApplication,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    // 网络流式对话 —— 返回一个 Flow（事件流），ViewModel 去消费
    fun sendStreamMessage(sessionId: String, question: String): Flow<SseEvent> {
        return app.sseDataSource.streamChat(
            ChatStreamRequest(id = sessionId, question = question)
        )
    }

    // 存一条用户消息到 Room
    suspend fun saveUserMessage(conversationId: String, content: String) {
        messageDao.insert(
            MessageEntity(conversationId = conversationId, role = "user", content = content)
        )
    }
    // ... 其它方法都是"一个数据操作"的封装
}
```

注意 `ViewModel` **永远不直接碰** `messageDao` 或 `app.api`，只通过 `repository`。这就是"单一数据出口"。

### 2.3 为什么手动 DI，不用 Hilt/Dagger（🔥 面试高频）

> **小白概念**：**DI（Dependency Injection，依赖注入）** = "一个对象需要的东西（依赖），不从自己内部 new，而是从外面传进来"。
>
> 例：`ChatViewModel` 需要 `ChatRepository`。如果 ViewModel 自己 `new ChatRepository(...)`，它就得知道 Repository 怎么构造（要传哪些 DAO）——耦合死了。DI 的做法是：**别人（容器）负责构造好 Repository，塞给 ViewModel**。

主流方案对比：

| 方案 | 是什么 | 本项目的选择 |
|------|--------|------------|
| **Hilt / Dagger** | 编译时生成注入代码，加注解 `@Inject` 就能注入 | ❌ 不用 |
| **Koin** | 运行时的轻量 DI（纯 Kotlin，无代码生成） | ❌ 不用 |
| **手动 DI** | 自己写一个"容器类"，把依赖 `by lazy` 持有，手动传给使用者 | ✅ **采用** |

**为什么手动 DI？**（面试标准回答）

> "这个 App 依赖图很简单，约 10 个 `by lazy` 单例（Gson、两个 OkHttpClient、Retrofit、Room 及其 Dao、Repository、DataStore 设置仓库等）。Hilt 要加注解处理器、生成 Dagger 组件代码，编译变慢，而且对这么简单的依赖图没有收益。手动 DI 我用一个 `Application` 子类做容器，每个依赖 `by lazy` 懒加载，总共约 210 行代码，**完全透明、可断点调试、没有黑盒**。等依赖图复杂到几十个再上 Hilt 也不迟。"

看本项目的手动 DI 容器（`LabSageApplication.kt`）：

```kotlin
class LabSageApplication : Application() {
    // 每个依赖都是 lazy 单例 —— 第一次访问时才创建
    val gson: Gson by lazy { Gson() }

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "labsage.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
    }

    val repository: ChatRepository by lazy {
        ChatRepository(
            app = this,
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao()
        )
    }

    // ViewModel 不能直接 new（它跟生命周期绑定），用工厂模式创建
    fun chatViewModelFactory(conversationId: String): ViewModelProvider.Factory {
        return object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(repository, settingsRepository, conversationId) as T
            }
        }
    }
}
```

> **`by lazy` 是什么**：Kotlin 的属性委托。第一次读这个属性时才执行后面的初始化代码，之后所有访问都返回同一个缓存值。好处：① 启动快（不用的依赖不创建）；② 线程安全（`lazy` 默认加锁）。

> **ViewModel 为什么用工厂**：因为 ViewModel 必须由 `ViewModelProvider` 创建（这样才能活过屏幕旋转）。但我们的 `ChatViewModel` 构造需要参数（`conversationId`），默认的无参构造工厂搞不定，所以自己写个 `ViewModelProvider.Factory`，在 `create()` 里手动 `new` 并传参。

**面试追问预案**：
- "那 Hilt 到底什么时候值得用？" → 依赖图复杂（多 Module、多 Activity、有不同作用域如登录态/全局），手动维护成本高时。
- "手动 DI 的缺点？" → 样板代码（每个 ViewModel 要写工厂）；作用域要自己管（Hilt 有 `@Singleton`/`@ActivityScoped` 等）。

---

## 第 3 章 主线：一条消息的完整生命周期

> 这一章是**全文的纲**。理解了这条主线，后面每章都是主线上某个环节的深入。建议对照代码反复读。

### 3.1 从"按下发送"到"看到答案"的完整链路

```
用户在输入框打字 → 点发送按钮
   │
   ▼  ① UI 层：ChatScreen 调用 viewModel.sendMessage(question)
   │
ChatViewModel.sendMessage()
   │
   ├─ ② 审批闸门（如果开了审批模式，先弹窗确认）
   │
   ▼  ③ doSend(question)
   │
   ├─ repository.upsertConversation(...)   # 先建/更新会话（FK 要求会话先存在）
   ├─ repository.saveUserMessage(...)      # 把用户问题存进 Room
   ├─ 重置一堆状态（accumulatedAnswer、trace 等）
   ├─ _streamState.value = Streaming       # 通知 UI："开始流式了"
   │
   ▼  ④ 开一个协程，收集 SSE 事件流
   │
   repository.sendStreamMessage(conversationId, question)
     └─ app.sseDataSource.streamChat(request)   # 这是个 Flow<SseEvent>
        └─ OkHttpSseDataSource：建立 SSE 连接，onEvent 回调里 trySend(event)
   │
   ▼  ⑤ 每收到一个事件，handleSseEvent(event) 分类处理
   │
   ├─ Message 事件：accumulatedAnswer.append(text) → 每 5 个 chunk 写一次 Room
   ├─ Trace 事件：解析工具调用 → 更新 _toolCalls（驱动工具面板）
   ├─ Done 事件：finalize 消息到 Room → _streamState = Completed
   └─ Error 事件：保留 partial → _streamState = Error(retryable)
   │
   ▼  ⑥ Room 一变 / StateFlow 一变
   │
   └─ Compose 自动重组（recomposition）→ UI 刷新 → 用户看到答案逐字蹦出
```

### 3.2 关键代码走读（`ChatViewModel.kt`）

**① 发送入口**：

```kotlin
fun sendMessage(question: String) {
    if (_streamState.value is StreamState.Streaming) return  // 防止重复发送
    if (_approvalMode.value) {           // 审批模式：先暂存，弹窗确认
        _pendingSend.value = question
        return
    }
    doSend(question)
}

private fun doSend(question: String) {
    viewModelScope.launch {              // 在 ViewModel 作用域里开协程
        // ⚠️ 顺序至关重要：必须先建会话，再插消息（见 9.1 的崩溃 Bug）
        repository.upsertConversation(conversationId, deriveTitle(question))
        repository.saveUserMessage(conversationId, question)

        _streamState.value = StreamState.Streaming
        // ... 重置累加器 ...

        // 开 SSE 流，逐事件处理
        streamingJob = viewModelScope.launch {
            repository.sendStreamMessage(conversationId, question)
                .catch { e -> handleStreamException(e) }   // 异常走续传逻辑
                .collect { event -> handleSseEvent(event) } // 正常事件
        }
    }
}
```

**② 事件处理（核心）**：

```kotlin
private suspend fun handleSseEvent(event: SseEvent) {
    when (event) {
        is SseEvent.Message -> {
            accumulatedAnswer.append(event.text)
            chunkCount++
            if (streamingMessageId == null) {
                // 第一个 chunk：INSERT 一条占位消息，拿到 id
                streamingMessageId = repository.saveAssistantMessage(
                    conversationId = conversationId,
                    content = accumulatedAnswer.toString(),
                    isPartial = true,                    // 标记"未完成"
                    originalQuestion = lastQuestion
                )
            } else if (chunkCount % 5 == 0) {
                // 每 5 个 chunk 才 UPDATE 一次（省磁盘 I/O，性能优化）
                repository.updateAssistantMessage(
                    streamingMessageId!!, accumulatedAnswer.toString(), isPartial = true
                )
            }
        }
        is SseEvent.Done -> {
            // 流结束：把最终内容写死，标记完成
            repository.finalizeAssistantMessage(streamingMessageId!!, finalAnswer, currentRoute)
            _streamState.value = StreamState.Completed(...)
        }
        // ... Trace / Error / ...
    }
}
```

### 3.3 这条主线为什么值得反复读

因为它**一次性串起了**：协程（`viewModelScope.launch`）、Flow（`collect`）、Room（`saveAssistantMessage`）、状态管理（`StateFlow`）、网络（SSE）。面试官问任何一个点，你都能回到这条主线，说清"它在整体里的位置"——这比孤立背八股强 10 倍。

---

## 第 4 章 SSE 流式——整个项目的灵魂（面试杀手锏）

> 这是本项目技术含量最高、最值得讲的部分。面试时如果聊到这个，能直接拉开差距。请反复读这一章。

### 4.1 先搞懂：什么是"流式输出"，为什么要 SSE

**问题**：大模型生成答案很慢（一个字一个字吐），如果等它全部生成完再一次性返回，用户要干等好几秒才看到完整答案，体验很差。**流式输出**就是让答案"边生成边发"，用户看到打字机效果。

**实现"服务器主动推数据给客户端"有三种主流方案**：

| 方案 | 原理 | 适合场景 |
|------|------|---------|
| **轮询 (Polling)** | 客户端每隔几秒主动问服务器"好了吗" | 简单但浪费，延迟高 |
| **WebSocket** | 全双工（双向）长连接，客户端和服务器随时互发 | 聊天室、实时游戏（需要双向） |
| **SSE (Server-Sent Events)** | 基于 HTTP 的**单向**长连接，服务器持续推，客户端只接收 | 服务器→客户端的单向推送，**最适合 LLM 流式** |

**为什么 LLM 流式用 SSE 而不是 WebSocket？**（面试必答）

> "LLM 流式是**纯单向**的——只有服务器往客户端推 token，客户端不需要在流的过程中往回发东西。SSE 基于 HTTP，自动重连、浏览器/服务器原生支持、比 WebSocket 轻量得多。WebSocket 的全双工能力在这里是多余的复杂度。"

**SSE 协议长什么样**（看一眼就懂了）：

```
event: connected                              ← 事件类型
data: {"status":"connected","client_id":"x"}  ← 数据

event: message
data: 你好！我是 LabSage                       ← 一块答案文本

event: message
data: ，实验室知识助手。                       ← 又一块

event: done
data: Stream completed                        ← 结束
```

> **关键**：SSE 是**纯文本协议**。一个事件 = `event:` 行 + `data:` 行，**用空行分隔**。服务器就这么一行行往连接里写文本，客户端按规则解析。

### 4.2 技术选型：怎么在 Android 收 SSE

**方案对比**（这是面试"为什么"的素材）：

| 方案 | 结论 | 原因 |
|------|------|------|
| 自己写 Retrofit 的 CallAdapter | ❌ | Retrofit 是为"请求-响应"设计的，对 SSE 这种长流没有增值，反而复杂 |
| 浏览器的 `EventSource` API | ❌ | 那是 Web 标准，Android 没有原生实现 |
| **OkHttp 的 `okhttp-sse` 库 + `callbackFlow`** | ✅ | Square 官方库，专门处理 SSE 协议；配合 Kotlin Flow 天然契合 |

**最终架构**：

```
OkHttpSseDataSource（实现 SseDataSource 接口）
  ├─ OkHttpClient (readTimeout=0 无限等待，日志只用 HEADERS 级别)
  ├─ EventSources.createFactory() → EventSource（okhttp3的SSE 连接）
  ├─ EventSourceListener.onEvent() 回调 → callbackFlow.trySend()
  └─ 输出 Flow<SseEvent>.buffer(UNLIMITED).flowOn(IO)
```

### 4.3 核心实现：把"回调"翻译成"Flow"（callbackFlow）

> **小白概念铺垫**：OkHttp 的 SSE 库是**回调式**的——连接收到数据时，它调你的 `onEvent(...)` 方法。但 Kotlin 现代写法更喜欢用 **Flow**（数据流，可以 `collect` 消费）。怎么把"回调"变成"Flow"？答案就是 **`callbackFlow`**。

**`callbackFlow` 是什么**：一个构建器，给你一个 `channel`，你往里面 `trySend()` 数据，外面就能 `collect` 到。它专门用来"把回调风格的 API 包装成 Flow"。

看真实代码（`OkHttpSseDataSource.kt`），逐行注释：

```kotlin
override fun streamChat(request: ChatStreamRequest): Flow<SseEvent> = callbackFlow {
    // 1. 构造 HTTP 请求（SSE 本质是个 POST，带 Accept: text/event-stream）
    val jsonBody = gson.toJson(request)
    val httpRequest = Request.Builder()
        .url("$baseUrl/api/chat_stream")
        .post(jsonBody.toRequestBody(jsonMediaType))
        .header("Accept", "text/event-stream")
        .build()

    // 2. 建立 SSE 连接，注册监听器
    val factory = EventSources.createFactory(sseClient)
    val eventSource = factory.newEventSource(httpRequest, object : EventSourceListener() {

        // 每收到一个 SSE 事件，OkHttp 解析好 event/data 后回调这里
        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            val event = buildEvent(type, data) ?: return

            // 🔥 关键：过滤 trace 事件（只留工具生命周期行，见 4.5 背压）
            if (event is SseEvent.Trace) {
                val l = event.line
                val keep = l.startsWith("[tool]") || l.startsWith("[react]") || l.startsWith("[route")
                if (!keep) return
            }
            trySend(event)   // 非阻塞地塞进 channel，外面 collect 到
        }

        override fun onClosed(eventSource: EventSource) {
            channel.close()  // 连接正常关闭 → 结束 Flow
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            eventSource.cancel()
            trySend(SseEvent.Error(message = ..., retryable = true))
            close(IOException(message))  // 异常关闭 → Flow 抛异常
        }
    })

    // 3. awaitClose：当 Flow 被取消时（比如用户离开页面），执行这里的清理
    awaitClose {
        eventSource.cancel()   // 断开 SSE 连接，防泄漏
    }
}
    .buffer(Channel.UNLIMITED)   // 🔥 见 4.5：防背压丢数据
    .flowOn(Dispatchers.IO)      // 在 IO 线程跑，不阻塞主线程
```

**这段代码的四个面试考点**：

1. **`trySend` vs `send`**：`onEvent` 是普通回调（不在协程里），不能用挂起函数 `send()`，只能用非阻塞的 `trySend()`。`trySend` 立即返回一个结果（成功/失败/已关闭），不等。
2. **`awaitClose { eventSource.cancel() }`**：这是 `callbackFlow` 的精髓。如果用户在流式途中退出页面，Flow 被取消，`awaitClose` 块执行，主动断开 SSE 连接。**没有它就会连接泄漏**。
3. **`readTimeout(0)`**：SSE 是长连接，可能好几十秒没数据但连接还活着。如果设了超时，OkHttp 会误以为卡死而断开。所以 SSE 客户端**读超时设 0（永不超时）**。
4. **日志级别只能用 HEADERS，绝不能用 BODY**：OkHttp 的 `HttpLoggingInterceptor(BODY)` 会把整个响应体缓冲完才回调，对无限流**永远缓冲不完**，导致 `onOpen` 永不触发（OkHttp issue #4298）。这是真实踩过的坑。

### 4.4 事件解析：把 SSE 文本变成类型安全的事件

OkHttp 的 `onEvent(type, data)` 已经帮我们把 `event:` 和 `data:` 拆好了，我们只需把 `type` 字符串映射成 Kotlin 的类型。用 **sealed class（密封类）**：

```kotlin
sealed class SseEvent {
    data class Connected(val status: String, val clientId: String) : SseEvent()
    data class Message(val text: String) : SseEvent()
    data class Trace(val line: String) : SseEvent()
    data class TraceSummary(val toolStats: Map<String, Int>, ...) : SseEvent()
    object Done : SseEvent()
    data class Error(val message: String, val partial: Boolean, val retryable: Boolean) : SseEvent()
}
```

> **sealed class 为什么好**（面试点）：它让"所有可能的事件类型"是**穷举的、有限的**。在 `when (event)` 里编译器会强制你处理每一种情况（或加 `else`），不会漏。比一堆 `if/else` 判字符串类型安全得多。

解析映射：

```kotlin
private fun buildEvent(type: String?, data: String): SseEvent? = when (type) {
    "connected"     -> { /* 解析 JSON */ SseEvent.Connected(...) }
    "message"       -> SseEvent.Message(data)        // 纯文本，直接包
    "trace"         -> SseEvent.Trace(data)
    "trace_summary" -> { /* 解析 JSON */ }
    "done"          -> SseEvent.Done
    "error"         -> { /* 解析 JSON */ }
    null            -> SseEvent.Message(data)        // 没带 event 字段的，按 message 处理（SSE 规范）
    else            -> SseEvent.Message(data)
}
```

### 4.5 背压问题：trySend 会丢数据！（🔥 项目最大亮点之一）

**这是整个项目最值得讲的 Bug，也是协程 Flow 的高频考点。**

**现象**：用户点"查询 GPU 状态"，答案输出到一半就停了，UI 卡在"流式中"，永远等不到 Done。

**根因调查**：
- `callbackFlow` 默认的缓冲区（Channel）容量是 **64**。
- 一次工具查询，后端会发 **200+ 条 trace 事件**（路由决策、工具日志、中间过程）。
- `trySend()` 在缓冲区满时，**静默丢弃**后续事件——包括关键的 `message` 和 `done`！
- 实测一次查询返回 **993 行** SSE 数据，64 的缓冲区根本扛不住。

**为什么 `trySend` 会丢**（面试深问）：

> "`callbackFlow` 内部是一个 Channel。`trySend` 是非阻塞的，它尝试往 channel 塞数据：如果 channel 没满就成功，**满了就直接返回失败（ChannelResult.failure），但不抛异常、不打日志，静默丢弃**。这是为了'不阻塞回调线程'而做的设计取舍。要保证不丢，得用挂起的 `send()`（满了会挂起等待），但 `onEvent` 不是协程上下文，调不了挂起函数。"

**本项目三级解决方案**（层层递进，体现工程思维）：

| 层级 | 措施 | 效果 |
|------|------|------|
| **① 事件过滤**（主攻） | `onEvent` 里只转发 `[tool]/[react]/[route]` 前缀的结构化行，丢掉 200+ 条自由文本噪音 | 200+ → ~5 条 |
| **② 无限缓冲**（兜底） | `.buffer(Channel.UNLIMITED)` | 万一过滤后还多，也不丢 |
| **③ 批量写库**（减负） | Room 每 5 个 chunk 才写一次，Done 时 finalize | 减少磁盘 I/O 背压 |

**为什么不全靠 `UNLIMITED`**（面试追问）：

> "全部缓存会内存膨胀、GC 压力大、消费者延迟堆积。过滤是治本（从源头减少无用事件），`UNLIMITED` 只是兜底。"

**面试关联八股**——Channel 背压策略（必背）：

| 策略 | 行为 | 场景 |
|------|------|------|
| `BUFFERED`(默认 64) | 满了之后 `send` 挂起 / `trySend` 丢弃 | 通用 |
| `CONFLATED`(容量1) | 满了丢旧的，只留最新 | 只关心最新值（如进度） |
| `UNLIMITED` | 无限缓冲，永不丢但费内存 | 不能丢、且消费跟得上 |
| `RENDEZVOUS`(容量0) | 发送者和接收者必须"碰头" | 严格同步 |

### 4.6 断线续传：流中断了怎么接着说

**场景**：答案输出到一半，网络断了。用户已经有前半段，想"继续"而不是"重发"。

**机制**（后端 + 前端配合）：
1. 流中断 → `StreamState.Error(partial=true, partialAnswer=<已收到文本>)`
2. UI 显示"继续 / 重新生成"按钮
3. 用户点"继续" → 发送 `ChatStreamRequest(Continue=true, PartialAnswer=<已收文本>)`
4. 后端把这段 `PartialAnswer` 接着往下生成，**不重复持久化**，流正常结束后才写入

```kotlin
fun continueGeneration() {
    val state = _streamState.value
    if (state !is StreamState.Error || !state.retryable) return
    viewModelScope.launch {
        _streamState.value = StreamState.Streaming
        streamingJob = viewModelScope.launch {
            repository.continueStreamMessage(
                conversationId, state.lastQuestion, state.partialAnswer
            ).collect { event -> handleSseEvent(event) }
        }
    }
}
```

> **面试点**：续传是"协议级"特性，需要后端支持。前端要做的就是把已收到的部分内容回传，让后端从断点续上。

---

## 第 5 章 Room 数据持久化——消息怎么存下来

### 5.1 Room 三件套（小白复习）

> **Room = SQLite 的"高级封装"**。它有三个核心角色：
> - **Entity（实体）**：一张表对应一个 data class，字段就是列。
> - **DAO（Data Access Object）**：定义对表的操作（增删改查），用注解或 SQL 写。
> - **Database**：数据库持有者，单例，提供 DAO。

### 5.2 表设计：会话 + 消息，外键级联

**会话表 `conversations`**（一个会话 = 一次聊天话题）：

```kotlin
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,            // session ID
    val title: String,                     // 取首条消息前 30 字
    val createdAt: Long,
    val updatedAt: Long,
    val isPinned: Boolean = false          // 置顶（v2 迁移加的）
)
```

**消息表 `messages`**（每条 user/assistant 消息一行）：

```kotlin
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE          // 🔥 级联删除
    )],
    indices = [Index("conversationId")]        // 给外键加索引
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,                // 外键 → conversations.id
    val role: String,                          // "user" | "assistant"
    val content: String,
    val timestamp: Long,
    val route: String? = null,                 // "direct" | "react"
    val isPartial: Boolean = false,            // 🔥 流中断标记
    val originalQuestion: String? = null,      // 用于续传
    val errorMessage: String? = null
)
```

**外键 + CASCADE 是什么**（面试必答）：

> "`messages.conversationId` 外键引用 `conversations.id`。`onDelete = CASCADE` 的意思是：**删一个会话，它的所有消息自动跟着删**，不用我手动遍历。这叫'引用完整性'。"
>
> 反例：如果没有 CASCADE，删会话后消息变"孤儿数据"（指向不存在的会话）。

**为什么给外键加索引**：

> "`Index("conversationId")`。查询某会话的所有消息时 `WHERE conversationId = ?`，没索引就是全表扫描；有索引是 O(log n)。外键列几乎一定要加索引。"

### 5.3 流式内容怎么存：INSERT + UPDATE 模式（🔥 高频）

**问题**：流式输出每秒来 50 个 chunk（文字片段）。难道每来一个 chunk 就往数据库 INSERT 一行？那一个回答就 50 行了，乱套。

**解决方案**：**第一块 INSERT 占位，后续 UPDATE 同一行**。

```
第 1 个 message chunk → INSERT 一条 assistant 消息 (isPartial=true)，拿到 messageId
第 2~N 个 chunk       → UPDATE messages SET content=? WHERE id=messageId
done 事件             → UPDATE ... isPartial=false, route=?  （标记完成）
```

代码（在 `ChatViewModel.handleSseEvent` 里，第 3 章贴过）：

```kotlin
is SseEvent.Message -> {
    accumulatedAnswer.append(event.text)
    chunkCount++
    if (streamingMessageId == null) {
        // 首 chunk：INSERT
        streamingMessageId = repository.saveAssistantMessage(..., isPartial = true, ...)
    } else if (chunkCount % 5 == 0) {
        // 后续：每 5 chunk UPDATE 一次（省 I/O）
        repository.updateAssistantMessage(streamingMessageId!!, ..., isPartial = true)
    }
}
```

**这个设计的杀手级好处——杀进程可恢复**：

> "App 流式输出到一半被系统杀了（内存不足）。重启后，从 Room 读消息，发现有条 `isPartial=true` 的 assistant 消息——就知道它没说完，UI 自动显示'继续'按钮。**消息不丢**，靠的就是这个 `isPartial` 标记 + 增量写库。"

### 5.4 数据库迁移：加字段不丢数据（v1 → v2）

**场景**：v1 没有"置顶"功能，后来要加 `isPinned` 字段。直接改 Entity 重新装 App，老用户的数据库 schema 对不上会崩。怎么办？**数据库迁移（Migration）**。

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0")
    }
}

// 注册到 DatabaseBuilder
Room.databaseBuilder(...)
    .addMigrations(AppDatabase.MIGRATION_1_2)   // 升级时跑这个 SQL
    .build()
```

**为什么不用 `fallbackToDestructiveMigration`**（面试点）：

> "那个选项是'版本对不上就清空数据库重建'。开发时方便，但**会清空用户的所有聊天历史**。正式产品必须写 Migration，用 `ALTER TABLE ADD COLUMN ... DEFAULT 0` 回填老数据（老会话全部 `isPinned=0`，数据无损）。"

---

## 第 6 章 Compose + 状态管理——UI 怎么动起来

### 6.1 Compose 的核心心智模型（小白向）

> 《第一行代码》可能还停留在 XML 布局。Compose 是**声明式 UI**：不用写"把这个 TextView 的文字设成 X"，而是写"**UI 长什么样，取决于状态是什么**"。状态一变，Compose 自动重新画出受影响的部分（叫**重组 recomposition**）。

```kotlin
// 命令式（XML 时代）：告诉系统"怎么做"
textView.text = "你好"

// 声明式（Compose）：描述"是什么"，状态变了自动重画
@Composable
fun Greeting(name: String) {   // name 是状态
    Text(text = "你好, $name")  // name 变了，这个 Text 自动重画
}
```

**两个核心概念**：
- **状态（State）**：会变的数据。Compose 里用 `mutableStateOf` / `MutableStateFlow` 持有。
- **重组（Recomposition）**：状态变了，读这个状态的 Composable 函数重新执行，更新 UI。

### 6.2 StateFlow + sealed class：状态机

**UI 需要表示"现在流式处于哪个阶段"**。用 sealed class 把所有可能状态列死：

```kotlin
sealed class StreamState {
    object Idle : StreamState()           // 空闲，没在对话
    object Streaming : StreamState()      // 正在接收答案
    data class Completed(                 // 完成
        val fullAnswer: String,
        val route: String?,
        val toolStats: Map<String, Int>
    ) : StreamState()
    data class Error(                     // 出错
        val message: String,
        val partial: Boolean,             // 有没有部分内容
        val retryable: Boolean,           // 能不能续传
        val partialAnswer: String,
        val lastQuestion: String
    ) : StreamState()
}
```

**为什么用 StateFlow 而不是 LiveData**（面试高频）：

| | LiveData | StateFlow |
|---|---|---|
| 初始值 | 无，要处理 null | **必须有初始值** |
| Compose 里收集 | 要 `observeAsState()` | `collectAsStateWithLifecycle()` |
| 线程 | 只能在主线程设值 | **任意线程都能设值** |
| 操作符 | 只有 map/switchMap | **完整 Flow 操作符链**（filter/flatMapLatest/debounce...） |

> "本项目大量用 Flow 操作符（`conflate`、`flatMapLatest`、`catch`），LiveData 做不到，所以选 StateFlow。而且 StateFlow 必须有初始值，配合 Compose 更安全（不会读到 null 状态）。"

**UI 怎么消费这个状态**：

```kotlin
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val streamState by viewModel.streamState.collectAsStateWithLifecycle()  // 订阅

    when (streamState) {
        StreamState.Streaming -> StreamingIndicator()     // 显示"打字中"
        is StreamState.Error -> PartialMessageBanner(...) // 显示错误+续传按钮
        // ...
    }
}
```

> **`by` 是属性委托**：`collectAsStateWithLifecycle()` 返回一个 `State<T>`，用 `by` 直接当 `T` 用，少写一层 `.value`。

```text
ViewModel 修改 StateFlow
        ↓
collectAsStateWithLifecycle 收到新值 (StageFlow -> State<T>)
        ↓
Compose State.value 更新 (通过by/.value取出State<T>中的T)
        ↓
读取了 streamState 的 Composable 重新执行
        ↓
when 重新判断
        ↓
显示 StreamingIndicator()


MutableStateFlow 值变化
-> collectAsStateWithLifecycle 收到变化
-> Compose State 变化
-> Compose 发现 ChatScreen 读过这个 State
-> 触发相关 Composable 重组


```
对，基本就是这个意思。我们把你的话拆开看。

```kotlin
val streamState by viewModel.streamState.collectAsStateWithLifecycle()
```

这一步之后，`ChatScreen` 就“接上了” ViewModel 里的 `streamState`。

更准确说：

```text
viewModel.streamState 是 StateFlow
collectAsStateWithLifecycle() 开始收集这个 StateFlow
收集到的最新值变成 Compose State
ChatScreen 读取这个 Compose State
```

所以当 ViewModel 里执行：

```kotlin
_streamState.value = StreamState.Error(...)
```

就会发生：

```text
ViewModel 的 StateFlow 值变化
-> collectAsStateWithLifecycle 收到新值
-> Compose State 变化
-> Compose 发现 ChatScreen 读了 streamState
-> 触发重组
```

你说的“重新走一遍 ChatScreen”，可以这么理解，但要稍微精确一点：

```text
逻辑上：读了 streamState 的 Composable 会重新执行
效果上：Compose 会尽量只更新真正受影响的 UI
```

也就是说，不是整个 Activity 重启，也不是整个页面所有控件都重新创建一遍。

比如 `ChatScreen` 里面有很多状态：

```kotlin
val messages by viewModel.messages.collectAsStateWithLifecycle()
val streamState by viewModel.streamState.collectAsStateWithLifecycle()
val uploadState by viewModel.uploadState.collectAsStateWithLifecycle()
```

如果这次只变了 `streamState`，那主要受影响的是读了 `streamState` 的地方，比如：

```kotlin
when (val s = streamState) {
    is StreamState.Error -> ...
    else -> {}
}
```

这段会重新判断。

但消息列表如果只依赖 `messages`，而 `messages` 没变，Compose 就不会因为 `streamState` 变了而真的去改变每一条消息气泡。

可以这样记：

```text
StateFlow 变化：负责通知 Compose “数据变了”
collectAsStateWithLifecycle：负责把 ViewModel 状态接进 Compose
重组：让相关 Composable 函数重新执行
when：重新根据当前 streamState 选择显示什么组件
精准重组：Compose 尽量只更新读了这个状态的 UI 区域
```

所以你的这句话可以改成更标准的面试版：

```text
ChatScreen 通过 collectAsStateWithLifecycle 订阅 ViewModel 暴露的 streamState。
当 ViewModel 修改 _streamState.value 时，StateFlow 发射新值，Compose 收到后会触发读取了 streamState 的 Composable 重组。
重组时 when(streamState) 会重新执行，根据新的状态选择显示 PartialMessageBanner、Snackbar 或不显示。
Compose 不会粗暴刷新整个页面，而是尽量只重组和更新依赖 streamState 的 UI 部分。
```

一句话压缩版：

```text
是的，collectAsStateWithLifecycle 让 ChatScreen 能感知 streamState；streamState 变了会触发相关 UI 重组，when 重新判断，但最终主要影响的是读取 streamState 的那块 UI。
```

### 6.3 LazyColumn：流式消息列表怎么渲染

> **小白概念**：`LazyColumn` 是 Compose 版的 `RecyclerView`。它**只渲染屏幕可见的那几条**，滚出屏幕的回收，所以列表 1000 条也不卡。

**流式场景的特殊难点**：最后一条消息的内容在不停变（逐字蹦出），列表要跟着滚动到底。怎么滚？

```kotlin
val listState = rememberLazyListState()
LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
    // key 同时包含"消息数量"和"最后一条的内容"
    // 这样：新消息进来（size 变）→ 滚；流式更新（content 变）→ 也滚
    if (messages.isNotEmpty()) {
        listState.animateScrollToItem(messages.size - 1)
    }
}
```

> **面试点**：`LaunchedEffect` 的 `key` 为什么要包含 `lastOrNull()?.content`？
> "因为流式时消息**数量没变**（还是那一条 assistant 消息），只是**内容在变**。如果 key 只用 `messages.size`，流式更新时不会触发滚动，用户看不到最新蹦出来的字。加上 content 才能在每次内容变化时滚到底。"

### 6.4 性能优化：50 次/秒的更新怎么不卡（🔥 高频）

**问题**：流式时每秒来 ~50 个 chunk，每个都触发：Room 写入 → Room Flow 发射 → StateFlow 更新 → Compose 重组。如果不过滤，**每秒重组 50 次**，主线程扛不住，列表滚动卡顿。

**四层链路优化**（每层堵一个口）：

| 层级 | 瓶颈 | 优化手段 | 效果 |
|------|------|---------|------|
| Room → Flow | 每 chunk 触发 Flow 发射 | **`.conflate()`** | 重组 50/s → 10/s |
| LazyColumn 滚动 | 每帧执行滚动动画 | 新消息 animate / 内容更新 snap | 消除抖动 |
| StateFlow → Compose | 值一变所有读它的都重组 | **`@Immutable`** + `derivedStateOf` | 跳过无关重组 |
| 协程 | SSE 在主线程跑 | **`flowOn(Dispatchers.IO)`** | 主线程不阻塞 |

**重点讲 `.conflate()`**（面试必问）：

```kotlin
viewModelScope.launch {
    repository.getMessages(conversationId)
        .conflate()   // 🔥 关键
        .collect { entities -> _messages.value = entities.map { it.toUiState() } }
}
```

> "`conflate()` 的作用：**当消费者（collect）来不及处理时，丢弃中间的旧值，只保留最新的一个**。比如 Room 一秒发射 50 次，但 collect 处理一次要 100ms（一秒只能处理 10 次），`conflate` 会把中间的 40 次丢掉，collect 始终拿到最新值。结果：重组从 50/s 降到 10/s，肉眼无感知差异（因为本来就是连续文字），但主线程轻松了。"
- 第一次跑完后,协程不会退出,而是常驻、挂在 collect 内部等。
- 这时触发机制才接管:Room 的 InvalidationTracker 发现 messages 表变了 → Room 在它的 IO 线程重跑查询 → 产出新 list → 流过 .conflate() → 送到挂在 Main 上的 collector。
- "在 Dispatchers.Main 上 resume 一个协程" = Handler.post 一条 Message。所以你说的"DB 变更触发主线程调度,主线程干完手头的活就来处理"——对,但它是 resume 一个正在等的协程,不是启动一个新协程。
- **（重点）**正因为每次 resume 是一条 Handler.post、主线程可能一时半会儿轮不到(正在做布局/重组),如果 Room 在主线程忙的时候连续 emit 了 5 次,conflate() 会把中间态全丢掉,只留最新值给主线程处理。这就是注释里 "drop intermediate emissions" 的运行时根因——它丢的不是查询,是排队的 resume。

**`conflate` vs `debounce` vs `sample`**（追问）：

| 操作符 | 行为 | 用途 |
|--------|------|------|
| `conflate` | 立即给最新值，丢中间 | **UI 更新**（要新不要全） |
| `debounce(n)` | 静默 n 毫秒才发 | **搜索输入**（用户停手才搜） |
| `sample(n)` | 每 n 毫秒取一个 | 周期采样 |

**`@Immutable` 是什么**（追问）：

```kotlin
@Immutable                          // 告诉 Compose：这个类完全不可变
data class MessageUiState(
    val id: Long, val role: String, val content: String, ...
)
```

> "Compose 判断'要不要重组一个 Composable'时，会比较输入参数。普通 data class Compose 不敢确定你有没有改它内部，会保守地重组。标了 `@Immutable`，Compose 知道它'两实例相等就没变化'，能放心跳过重组。流式列表里这是实打实的性能提升。"

### 6.5 暗黑模式：跟随系统 + 手动覆盖

**方案**：用 `isSystemInDarkTheme()` 读系统主题，配合 Material 3 的 `darkColorScheme()`。设置页可以手动覆盖。

```kotlin
@Composable
fun LabSageRoot(settings: AppSettings) {
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()   // 跟随系统
        ThemeMode.LIGHT  -> false
        ThemeMode.DARK   -> true
    }
    LabSageTheme(darkTheme = darkTheme) {
        // 整棵 UI 树，颜色全走 MaterialTheme.colorScheme，自动适配明暗
        LabSageNavGraph()
    }
}
```

> **面试点**：主题热切换为什么不用重建 Activity？
> "因为 `themeMode` 是个 StateFlow，UI 树订阅它。设置页改主题 → StateFlow 发射新值 → `LabSageRoot` 重组 → 整棵树换色。全程不重建 Activity，丝滑。"

### 6.6 两阶段 Markdown 渲染

**问题**：AI 回答是 Markdown（`**粗体**`、代码块等）。流式进行中 Markdown 是**不完整的**（比如代码块 ```` ``` ```` 还没闭合），这时候强行渲染会报错或显示断裂。

**方案**：流式中显示纯文本，完成后才渲染 Markdown。

```kotlin
// 流式进行中：纯 Text（安全）
Text(text = content)

// 流式完成：Markdown 渲染
MarkdownText(markdown = content)
```

> **选型踩坑**（讲故事素材，见 9.5）：原本想用 `mikepenz/multiplatform-markdown-renderer`（纯 Compose），但它依赖的 Compose Runtime 版本和项目 BOM 不兼容，运行时 `NoSuchMethodError` 崩溃。最终换成 `jeziellago/compose-markdown`（内嵌 TextView，兼容性好）。

---

## 第 7 章 依赖注入与网络层（Application）

> 第 2 章讲了"为什么手动 DI"，这一章讲"手动 DI 容器具体怎么写"，重点是网络层的两个细节：**三客户端共享连接池** 和 **后端地址热切换**。

### 7.1 Application：App 的全局入口

> **小白概念**：`Application` 类是**整个 App 进程的入口**，比第一个 Activity 还早创建，且全局只有一个实例。它适合放"全局单例"（数据库、网络客户端、Repository）。在 `AndroidManifest.xml` 里通过 `android:name=".LabSageApplication"` 指定。

本项目的 `LabSageApplication` 就是手动 DI 容器，所有单例都在这里 `by lazy` 持有（见第 2 章代码）。

### 7.2 OkHttp 三客户端，共享一个连接池（🔥 高频）

**为什么要三个 OkHttpClient？** 因为不同接口对超时的需求完全相反：

| 客户端 | 用途 | 读超时 | 日志级别 |
|--------|------|--------|---------|
| `baseOkHttpClient` | 基础（共享配置） | — | HEADERS |
| `apiOkHttpClient` | Retrofit 短请求（REST） | 30s | 继承 HEADERS |
| `sseOkHttpClient` | SSE 长连接（流式） | **0（永不超时）** | 继承 HEADERS |

```kotlin
// 基础客户端：只配日志和连接池
private val baseOkHttpClient by lazy {
    OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = Level.HEADERS })
        .build()
}

// Retrofit 客户端：派生自基础，30s 超时
val apiOkHttpClient by lazy {
    baseOkHttpClient.newBuilder()           // 🔥 newBuilder 派生
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}

// SSE 客户端：派生自基础，0 超时
val sseOkHttpClient by lazy {
    baseOkHttpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // SSE 不能被超时打断
        .build()
}
```

**`newBuilder()` 的妙处**（面试点）：

> "`baseOkHttpClient.newBuilder()` 派生出的新客户端，**和基础客户端共享同一个连接池、Dispatcher、线程池**。不会因为建了三个 Client 就有三套连接池（浪费资源）。复用连接池的好处：TCP 握手要 1-3 个 RTT（50-150ms），连接复用能省掉这些握手开销。"

**为什么 SSE 不能有读超时**：SSE 是长连接，可能几十秒不发数据（模型在思考），但连接还活着。设了超时，OkHttp 会以为"卡死"而断开。所以必须 `readTimeout(0)`。

**为什么日志只能 HEADERS**：见 4.3——BODY 级别会缓冲整个流，SSE 永远缓冲不完。

### 7.3 后端地址热切换：按 URL 缓存网络层（🔥 亮点）

**需求**：设置页里用户能改后端地址（比如从模拟器的 `10.0.2.2` 改成真机局域网 IP）。改完**不能重启 App 就立即生效**。

**难点**：原本 `api` / `sseDataSource` 都是 `by lazy` 单例，URL 写死。`lazy` 单例一旦创建不可重置。怎么办？

**方案：按 URL 缓存**。用一个 `ConcurrentHashMap<URL, NetworkLayer>` 缓存"每个 URL 对应的网络层"，每次取网络层时读当前设置的 URL。

```kotlin
private data class NetworkLayer(val api: LabSageApi, val sse: SseDataSource)

private val networkCache = ConcurrentHashMap<String, NetworkLayer>()

private fun networkLayerFor(rawUrl: String): NetworkLayer {
    val base = normalizeUrl(rawUrl)
    return networkCache.getOrPut(base) {           // 幂等：同 URL 只建一次
        val retrofit = Retrofit.Builder()
            .baseUrl(base).client(apiOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson)).build()
        NetworkLayer(
            api = retrofit.create(LabSageApi::class.java),
            sse = OkHttpSseDataSource(sseOkHttpClient, base.trimEnd('/'), gson)
        )
    }
}

// 每次取，都读当前设置的 URL
val api: LabSageApi get() = networkLayerFor(appSettings.value.baseUrl).api
val sseDataSource: SseDataSource get() = networkLayerFor(appSettings.value.baseUrl).sse
```

**为什么 `Repository` 改成持有 `app` 而不是直接持有 `api`**：

```kotlin
class ChatRepository(
    private val app: LabSageApplication,     // 持有 app，不持有 api
    ...
) {
    fun sendStreamMessage(...): Flow<SseEvent> {
        return app.sseDataSource.streamChat(...)   // 每次都走 app → 读最新 URL
    }
}
```

> "如果 Repository 直接持有 `api` 单例，那 URL 改了它还用旧的。改成持有 `app`，每次 `app.sseDataSource` 都重新读 `appSettings.value.baseUrl`，新 URL 立即生效。旧的 NetworkLayer 留在 map 里（连接池复用，无泄漏）。"

**URL 归一化**（细节但加分）：用户可能输入 `10.0.2.2:6872`，缺 `http://`、缺结尾 `/`。要补全成 Retrofit 能接受的格式：

```kotlin
private fun normalizeBaseUrl(raw: String): String {
    var u = raw.trim()
    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
    if (!u.endsWith("/")) u += "/"
    return u
}
```

**面试追问**：`ConcurrentHashMap.getOrPut` 的原子性？→ 它不是原子的（get 和 put 两步），但这里**可接受**：即使两个线程同时建了同一个 URL 的 NetworkLayer，结果幂等（多建一次无副作用，旧的被 GC），不会出错。

### 7.4 OkHttp 连接池八股（面试常问）

- **默认大小**：5 个空闲连接，5 分钟 keep-alive。
- **复用条件**：相同 host:port；HTTP/2 还支持多路复用（一个连接跑多个请求）。
- **为什么要复用**：TCP 握手（+ TLS 握手）耗时 1-3 RTT，复用省掉。

---

## 第 8 章 其它功能速览

这一章快速过一遍其余功能，每个都标注面试考点。详细版见 `FEATURES.md`。

### 8.1 语音输入（`VoiceInputButton`）

- 用 Android 内置 `SpeechRecognizer`（直接 API，非 Intent 弹窗）把语音转文字，追加到输入框。
- 权限 `RECORD_AUDIO` 是 **dangerous 权限**，运行时申请（`rememberLauncherForActivityResult(RequestPermission)`）。
- `DisposableEffect.onDispose` 里 `destroy()` 识别器，防服务连接泄漏。
- 模拟器可能"识别器不可用"，所有错误路径都 Toast 提示，优雅降级。

**面试考点**：dangerous 权限申请流程？为什么 `destroy()`？直接 API vs Intent 弹窗的区别？

### 8.2 工具执行面板（`ToolExecutionPanel`）🔥 亮点 （细节？）

把 Agent 调工具的过程做成**结构化时间线**：每个工具一张卡片（状态徽标 ✓/⏳/✕、调用序号、耗时），可展开看入参和输出。

**难点——配对算法**：后端"开始调用"行带序号 `#1`，"调用完成"行**不带序号**只有工具名。同名工具可能调多次。怎么把"完成"匹配到正确的"开始"？

> **答案：按工具名 + FIFO 配对**。"完成"事件匹配"最早的、还在 CALLING 状态的同名条目"。耗时用 `System.nanoTime()` 记起始，完成时 `(now-start)/1e6` 得毫秒。

**还有一个坑**（见 9.4）："开始调用 #1 query_gpu args=..." 里 `#1` 夹在中间，解析时如果取"第一个 token"会把 `#1` 当工具名，导致匹配失败、永远显示"中断"。

**审批模式（人机协同）**：开启后，发消息前弹窗确认（防止误触发工具）。**真正的"流中拦截工具"需要后端在每个工具调用前暂停等待确认（协议级改动）**，当前实现是"客户端发送前闸门"，覆盖最常见场景。

### 8.3 会话组织（搜索/置顶/重命名/分组）

- **置顶**：新增 `isPinned` 字段（v1→v2 迁移，见 5.4），置顶组始终在最前。
- **搜索**：DAO `LIKE '%query%'`，用 `flatMapLatest` 在"全量/搜索"间切换。
- **日期分组**：今天/昨天/更早，按 `updatedAt` 桶分。
- **重命名**：单独 `UPDATE title`，**不更新 `updatedAt`**（语义：重命名 ≠ 活跃，不该让会话"顶到今天"）。

**面试考点**：`flatMapLatest` vs `switchMap`/`map`？（查询词变了取消旧 Flow，防竞态）；为什么排序放 SQL 不放 UI？（索引 + 单次排序）。

### 8.4 设置页（`SettingsScreen`）

用 **DataStore Preferences** 持久化：后端地址、主题、模型、审批模式、快捷操作、连接测试。

**为什么 DataStore 而非 SharedPreferences**（🔥 高频）：

| | SharedPreferences | DataStore |
|---|---|---|
| 线程 | `commit()` 同步阻塞主线程（ANR 风险） | 协程异步，**线程安全** |
| API | 回调式，丑 | Flow 响应式 |
| 一致性 | `apply()` 有丢失风险 | 事务性写入 |

> "SP 的 `commit()` 在主线程写磁盘会卡（ANR），`apply()` 异步但有数据丢失风险。DataStore 完全协程化、线程安全、Flow 原生支持，是新项目首选。"

**连接测试**：一个 5s 超时的独立 OkHttpClient GET 目标地址。**任何 HTTP 响应都算可达**（连 404 也说明服务在线）；连接拒绝/超时 = 失败。见 9.6 的崩溃 Bug。

### 8.5 文件上传

```
ActivityResultContracts.GetContent（系统文件选择器）
  → ContentResolver 读 URI → 复制到 cacheDir 临时文件
  → MultipartBody.Part.createFormData("file", ...)
  → Retrofit POST /api/upload
  → 清理临时文件
```

### 8.6 网络状态监控

`ConnectivityManager.NetworkCallback` + `DisposableEffect` 监听网络变化，断网时顶部红色横幅提示，但不影响已加载内容。

---

## 第 9 章 Bug 实战复盘——面试讲故事的弹药库

> **面试中"遇到过什么困难/难题"几乎必问。** 不要说"没遇到"或"就是查文档"。要讲**具体现象 → 根因分析 → 解决方案 → 学到什么**。下面挑本项目最经典、最能体现深度的几个 Bug，每个都是现成的故事。完整 19 个 Bug 见 `BUGFIX_LOG.md`。

### 9.1 🔥 FK 约束崩溃——先存消息还是先存会话（P0）

**现象**：点发送后 App 立即崩，`SQLiteConstraintException: FOREIGN KEY constraint failed`。

**根因**：代码里先 `saveUserMessage()`（插消息）再 `upsertConversation()`（建会话）。但 `messages` 表外键引用 `conversations`，**插消息时会话还不存在**，SQLite 拒绝。

**修复**：交换两行顺序，先建会话再插消息。

```kotlin
// ❌ 错误：先存消息
repository.saveUserMessage(...)
repository.upsertConversation(...)

// ✅ 正确：先建会话
repository.upsertConversation(conversationId, title)
repository.saveUserMessage(conversationId, question)
```

**讲故事的话术**：
> "上线第一天就崩，是外键约束。我学到：**有 FK 的表，插入顺序必须顺着依赖方向**——先插被引用的（会话），再插引用方（消息）。这种问题只在'新建会话后首条消息'触发，所以前期测试很难发现。"

### 9.2 🔥🔥 REPLACE 触发 CASCADE——历史消息全没了（P0，最有深度）

**现象**：每次发新消息，**之前的聊天记录全部消失**。

**根因**（这个要讲清楚，体现深度）：会话更新用了 `@Insert(onConflict = REPLACE)`。**SQLite 的 REPLACE ≠ MySQL 的 `ON DUPLICATE KEY UPDATE`**！

> "SQLite 在主键冲突时，REPLACE 的算法是 **DELETE 旧行 + INSERT 新行**。而我的 `messages` 表对外键设了 `ON DELETE CASCADE`。所以更新会话 = 删会话行 = **级联删掉该会话所有消息**！第二条消息一发，历史全没了。"

**修复**：拆成显式的 INSERT/UPDATE。

```kotlin
// ❌ 危险：REPLACE = DELETE + INSERT → 触发 CASCADE
@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun upsert(conversation: ConversationEntity)

// ✅ 安全：先查存在性，存在就 UPDATE（局部更新，不删行），不存在才 INSERT(IGNORE)
suspend fun upsertConversation(id: String, title: String) {
    val existing = conversationDao.getById(id)
    if (existing != null) {
        conversationDao.updateMeta(id, title, System.currentTimeMillis())  // @Query("UPDATE ...")
    } else {
        conversationDao.insert(ConversationEntity(...))                    // @Insert(IGNORE)
    }
}
```

**讲故事的话术**（这是杀手锏）：
> "这是我印象最深的 Bug。现象是发第二条消息历史就没了。我一开始以为是查询的问题，排查很久。最后定位到是 `OnConflictStrategy.REPLACE`。我查了 SQLite 源码：**它的 REPLACE 算法是 DELETE+INSERT，不是 UPDATE**，DELETE 触发了外键的 CASCADE 级联。MySQL 里 `ON DUPLICATE KEY UPDATE` 是原地更新，语义完全不同。**教训：跨数据库的行为不能想当然，REPLACE 在有 FK CASCADE 的表上是地雷。**"

### 9.3 🔥🔥 trySend 溢出——答案输出到一半停了（P0，协程深度）

**现象**：点 GPU 查询，答案输出一部分就停，UI 卡在"流式中"。

**根因**：见 4.5。`callbackFlow` 默认缓冲区 64，工具查询发 200+ 条 trace，`trySend` 满了静默丢弃，连带丢了 `message` 和 `done`。

**修复**：事件过滤（200→5）+ `buffer(UNLIMITED)` 兜底 + 批量写库。

**讲故事的话术**：
> "这是协程背压问题。`trySend` 在缓冲区满时静默丢弃，没有异常没有日志，超难查。我先用 `time curl` 测出一次查询有 993 行 SSE，远超 64 缓冲。解决是三层：源头过滤噪音事件、`UNLIMITED` 兜底、批量写库减少消费延迟。这让我真正理解了 `trySend` vs `send`、Channel 背压策略。"

### 9.4 工具卡片永远显示"中断"——文本协议解析的坑（P1）

**现象**：工具明明成功了，卡片却显示"中断（ERROR）"。

**根因**：后端 start 行是 `[tool] 开始调用 #1 query_gpu args=...`，序号 `#1` 夹在"开始调用"和工具名**之间**。代码取"第一个 token"当名字，存成了 `#1`。而 complete 行是 `[tool] 调用完成 query_gpu ...`（名字直接跟在后面）。两边名字对不上 → 匹配失败 → 卡在 CALLING → Done 时翻成 ERROR。

**修复**：start 行解析时跳过前导 `#N ` token。

**讲故事的话术**：
> "这是文本协议解析的教训。**别假设'第一个 token 就是 name'，要对照真实 wire 格式**。而且 start/complete 两行格式不对称（一个带序号一个不带），配对键的提取必须两侧产出同一个值。最快的调试方法不是反复跑 App，而是直接 grep 后端落盘的 trace 日志核对格式。"

### 9.5 Markdown 库不兼容——NoSuchMethodError 崩溃（P0）

**现象**：进入聊天页崩溃，`NoSuchMethodError: getCurrentCompositeKeyHashCode`。

**根因**：`mikepenz/multiplatform-markdown-renderer` 编译目标依赖 Compose Runtime 1.10+，但项目 BOM 2025.06.01 提供的 Runtime 更早，缺这个方法。**这是"库 A 依赖的 X 版本 > 项目提供的 X 版本"的经典版本冲突。**

**修复过程**：试了 mikepenz 0.35/0.40/0.41 都崩 → 换 `jeziellago/compose-markdown:0.5.7`（兼容）。

**讲故事的话术**：
> "这是依赖版本冲突。第三方库编译时用的新版 Compose API，项目运行时的旧版没有。排查靠看堆栈里的方法名 + 对照库的依赖声明。教训：**选库要看它的依赖矩阵是否和项目主版本兼容，不能只看功能**。"

### 9.6 Application 急切初始化 NPE——DataStore 的坑（P0）

**现象**：加了设置页后，App 装上就崩，`NullPointerException: getApplicationContext()`。

**根因**（极有深度，体现 Android 生命周期理解）：`appSettings` 原本是**急切属性**（`val appSettings = ...`，非 lazy）。Application 实例化时，属性初始化在 `<init>` 阶段就触发 → 构造 `SettingsRepository(this)` → 访问 DataStore 的 `preferencesDataStore` 委托 → 它内部调 `this.getApplicationContext()`。**但此时 Application 的 base context 还没 attach**（`attachBaseContext()` 在构造函数之后才被系统调用）！于是 `getApplicationContext()` 返回 null → NPE。

**修复**：改成 `by lazy`，推迟到真正访问时（远晚于 attach）。

```kotlin
// ❌ 急切：<init> 阶段访问 context，此时还没 attach → NPE
val appSettings = settingsRepository.settings.stateIn(...)

// ✅ lazy：推迟到 Composable/网络请求时才访问，context 已 attach
val appSettings by lazy { settingsRepository.settings.stateIn(...) }
```

**讲故事的话术**：
> "这是 Android 生命周期的细节坑。Application 的 `attachBaseContext` 在构造函数之后才调用，所以 `<init>` 阶段拿不到 context。DataStore 的 `preferencesDataStore` 委托偏偏要 context。**依赖 Application Context 的单例一律 `by lazy`**，推迟到运行期访问。"

### 9.7 测试连接崩溃——URL 解析异常没兜住（P1）

**现象**：设置页输入非法端口（如 `68712`）点测试连接，App 直接崩。

**根因**：`ping()` 里只把网络请求包在 try/catch，但 `Request.Builder().url()` 在 try **之外**。OkHttp 对非法 URL（端口越界）抛**非受检** `IllegalArgumentException`，逃出 catch，协程未捕获异常把进程拉崩。

**修复**：把整个函数体（含 URL 解析）都纳入 try/catch。

**讲故事的话术**：
> "用户可输入的 URL 字段，**try/catch 边界要覆盖所有可能抛非受检异常的调用**，不能只裹'看起来会失败'的那行。OkHttp 的 `url()` 解析先于网络请求。协程里非受检异常不被 catch 会冒泡崩进程。"

### 9.8 🔥 杀进程后"继续生成"按钮消失——内存状态 vs 持久状态（P1）

**现象**：流式回答中途直接关掉 App，重开后消息还在（partial 已落盘），但底部的"继续生成/重新生成"横幅不再出现。

**根因**（体现"内存状态 vs 持久状态"的深度）：`_streamState`、`accumulatedAnswer`、`streamingMessageId` 都是 **ViewModel 内存状态**，进程被杀即清零。但 partial 内容每 5 个 chunk 已写进 Room（`is_partial=true`）。`ChatViewModel.init` 只把 Room 消息灌进 `_messages`，**没从残留的 partial 消息重建 `_streamState`**，所以停在 `Idle`，而横幅只在 `Error + retryable` 时渲染 → 看不见。

更关键的是：`continueGeneration()` 依赖 `accumulatedAnswer`/`streamingMessageId` **跨调用存活**（后端续写只发 delta，客户端在内存拼 `partial + 续写`）。所以重建时不能只设状态，还得把这两个累加器一起从 Room 灌回来——否则点继续会丢前半段、点重生成会删错行。

**修复**：新增 `restorePartialStateIfAny()`，重开后若最后一条是 `is_partial` 的非空 assistant 消息，就把 `accumulatedAnswer`/`streamingMessageId`/`_streamState` 三个一起从这条消息重建（`Idle` 守卫，只跑一次）。

**讲故事的话术**：
> "这是'数据没丢但功能丢了'的典型——ViewModel 的内存状态被进程死亡清零了，Room 里的 partial 内容还在。很多人只想到'恢复显示'，但续写功能依赖两个内存累加器跨调用存活，不一起灌回来，点继续就会丢前半段、点重生成会删错行。这让我理解了**内存状态和持久状态必须能相互重建**，`is_partial` 就是连接两段的线索。"

### 9.9 🔥 出错后按钮点了没反应——恢复字段漏填（P1）

**现象**：流式出错（400/连接断）且**没收到正文**时，弹出"Generation interrupted"横幅，按钮一直能点，但点下去错误原样复现，像按钮坏了。

**根因**：`handleSseEvent()` 的**非 partial 分支**构造 `StreamState.Error` 时**漏传 `lastQuestion`**，取了默认空串。而 `regenerate()`/`continueGeneration()` 重发读的是 `Error` 数据类里的 `lastQuestion` 快照字段（不是 ViewModel 的类字段）→ `sendMessage("")` → 后端请求体 `messages.content` 为空 → 回 `400 missing messages.content` → 同一错误横幅再弹 → 死循环，按钮表现为"失效"。

**修复**：两处构造非 partial `Error` 的地方（`handleSseEvent` + `handleStreamException` 的非 partial 分支）都补上 `lastQuestion = lastQuestion`（类字段此刻仍持有正确问题）。

**讲故事的话术**：
> "`StreamState.Error` 里的 `lastQuestion` 是'恢复用快照'，**每一条进入 Error 的路径都必须填**，漏一条就漏一种场景的恢复能力。还有个易错点：类字段 `lastQuestion`（实时值）和 Error 数据类里的 `lastQuestion`（进 Error 时的快照）是两份东西，恢复逻辑读哪个决定 bug 有没有。调试'按钮没反应'：先确认回调有没有被调，再看它依赖的状态字段是不是空——`state.lastQuestion == ""` 就是破案线索。"

---

## 第 10 章 技术选型决策表（面试"为什么"合集）

> 面试官最爱问"为什么选 X 不选 Y"。把这张表背下来。

| 决策点 | 选择 | 没选什么 | 理由（一句话） |
|--------|------|---------|---------------|
| UI 框架 | Jetpack Compose | XML | 声明式，流式场景高性能重组，新项目首选 |
| 架构 | MVVM + Repository | MVC/MVP | ViewModel 不持 View 引用，配置变更安全 |
| DI | 手动 DI | Hilt/Dagger | 依赖图简单（~5 单例），手动更透明无编译开销 |
| SSE 实现 | OkHttp `okhttp-sse` + `callbackFlow` | Retrofit CallAdapter | Retrofit 对 SSE 无增值，OkHttp 原生支持可靠 |
| 网络 | OkHttp(SSE) + Retrofit(REST) 分离 | 全用 Retrofit | 长连接 0 超时 vs 短请求 30s 超时，需求相反 |
| 数据库 | Room | SQLite/Realm | SQL 查询 + Flow 响应式 + FK 级联 |
| 流式持久化 | INSERT + UPDATE | 每 chunk INSERT | 杀进程可恢复，is_partial 标记中断 |
| 设置存储 | DataStore Preferences | SharedPreferences | 协程/Flow 原生、线程安全、无 ANR |
| 状态管理 | StateFlow + sealed class | LiveData | 任意线程设值 + 完整操作符链 + 状态穷举 |
| Markdown | jeziellago | mikepenz / WebView | mikepenz 版本不兼容；WebView 太重（+112MB） |
| 主题 | Material 3 + isSystemInDarkTheme | 手写主题切换 | 跟随系统 + 手动覆盖，StateFlow 驱动热切换 |
| 后端地址切换 | 按 URL 缓存 NetworkLayer | 重启 App / lazy 单例 | lazy 不可重置；缓存幂等、热生效 |

---

## 第 11 章 面试问答大全（按主题）

> 按主题分组的高频问答。建议面试前完整过一遍，每题先自己答再看参考。

### 一、架构相关

**Q1：讲讲你的项目架构。**
> MVVM + Repository + 手动 DI。分 UI 层（Compose）、ViewModel 层（StateFlow）、Repository 层（单一数据出口）、数据源层（Room 本地 + OkHttp/Retrofit 远程）。UI 只订阅 ViewModel 的 StateFlow，ViewModel 只调 Repository，Repository 协调网络和数据库。依赖注入我手写在 Application 里，每个依赖 `by lazy` 懒加载。

**Q2：为什么不用 Hilt？**
> 见 2.3。依赖图简单（约 10 个单例），Hilt 的注解处理器和代码生成对这个规模没有收益，反而编译慢、有黑盒。手动 DI 约 210 行代码完全透明可调试。

**Q3：MVVM 相比 MVC/MVP 的优势？**
> ViewModel 不持有 View 引用（不泄漏）、能活过配置变更（数据不丢）、通过可观察状态推送数据（解耦彻底）。

### 二、协程与 Flow（🔥 高频区）

**Q4：`callbackFlow` 和 `flow { }` 的区别？**
> `callbackFlow` 专门把**回调式 API** 包装成 Flow，它给你一个 channel，提供 `trySend`/`awaitClose`。`awaitClose` 块在 Flow 被取消时执行清理（如断开 SSE 连接）。普通 `flow { }` 没有 channel，适合自己控制 emit 的场景。

**Q5：`trySend` 和 `send` 的区别？为什么这里用 `trySend`？**
> `send` 是挂起函数，channel 满时会挂起等待，保证不丢；`trySend` 非阻塞，立即返回结果，满了丢弃。这里 `onEvent` 是普通回调不在协程上下文，调不了挂起的 `send`，只能用 `trySend`。代价是要自己处理背压（见 Q6）。

**Q6：你的 SSE 流怎么处理背压的？**（杀手题）
> 三级：① `onEvent` 里过滤噪音 trace 事件（200+→5）；② `.buffer(Channel.UNLIMITED)` 兜底；③ Room 每 5 chunk 批量写。因为 `trySend` 满了会静默丢，必须从源头减少事件 + 无限缓冲兜底 + 减少消费延迟。

**Q7：Channel 的容量策略有哪些？**
> BUFFERED(默认64)、CONFLATED(1，丢旧留新)、UNLIMITED、RENDEZVOUS(0，发收碰头)。各自适用场景见 4.5 表。

**Q8：`conflate()`、`debounce()`、`sample()` 区别？**
> conflate 立即给最新丢中间（UI 更新）；debounce 静默 N 毫秒才发（搜索输入）；sample 每 N 毫秒取一个（周期采样）。

**Q9：协程里异常没 catch 会怎样？**
> 会冒泡到 `CoroutineExceptionHandler`，Android 默认会**崩溃进程**。所以网络/用户输入相关逻辑必须兜底。见 9.7。

### 三、网络（OkHttp/Retrofit/SSE）

**Q10：SSE 和 WebSocket 区别？为什么用 SSE？**
> SSE 基于 HTTP、单向（服务器→客户端）、自动重连；WebSocket 全双工。LLM 流式是纯单向推送，SSE 更轻量合适。

**Q11：为什么 SSE 客户端 `readTimeout` 设 0？**
> SSE 是长连接，可能长时间无数据但连接活着，设超时会被误判为卡死而断开。

**Q12：为什么三个 OkHttpClient 共享连接池？**
> 用 `newBuilder()` 派生，共享连接池/Dispatcher/线程池，不浪费资源。连接复用省 TCP 握手开销（1-3 RTT）。

**Q13：为什么 OkHttp 日志不能用 BODY 级别？**
> BODY 级别会缓冲整个响应体才回调，SSE 无限流永远缓冲不完，`onOpen` 永不触发（issue #4298）。

### 四、Room / 数据库

**Q14：为什么用 Room 不用 DataStore 存对话？**
> DataStore 适合键值，对话是结构化数据需要查询、外键级联、Flow 观察。Room 才合适。

**Q15：`OnConflictStrategy.REPLACE` 有什么坑？**
> 见 9.2。SQLite 的 REPLACE = DELETE + INSERT，在有 FK CASCADE 的表上会级联删数据。应改用显式 UPDATE。

**Q16：流式消息怎么存的？杀进程会丢吗？**
> INSERT + UPDATE 模式。首 chunk INSERT 占位（isPartial=true），后续 UPDATE 同一行，done 时 finalize。杀进程后 isPartial=true 的消息会显示"继续"按钮，不丢。

**Q17：Room 迁移为什么不用 fallbackToDestructiveMigration？**
> 那会清空用户数据。要写 Migration + `ALTER TABLE ADD COLUMN DEFAULT 0` 回填。

### 五、Compose / UI

**Q18：声明式 UI 和命令式区别？**
> 命令式告诉系统"怎么做"（setText）；声明式描述"UI 是状态的函数"（状态变 UI 自动重画）。

**Q19：LazyColumn 怎么处理流式滚动？**
> `LaunchedEffect` 的 key 同时包含 `messages.size` 和 `lastOrNull()?.content`，这样新消息和内容更新都能触发滚到底。

**Q20：你做了哪些 Compose 性能优化？**
> 四层：`.conflate()` 限流（50→10/s）、拆分滚动策略、`@Immutable` + `derivedStateOf` 缩小重组、`flowOn(IO)` 不阻塞主线程。

**Q21：`@Immutable` 和 `@Stable` 区别？**
> Immutable 保证完全不可变（所有属性 final）；Stable 允许 mutable 但变化可追踪。

**Q22：StateFlow 和 LiveData 区别？**
> 见 6.2 表。StateFlow 有初始值、任意线程设值、完整操作符链。

### 六、Android 通用

**Q23：ViewModel 为什么能活过屏幕旋转？**
> ViewModel 由 `ViewModelStore`（挂在 Activity/Fragment 上）持有，配置变更时 Activity 重建但 ViewModelStore 保留，新 Activity 拿到同一个 ViewModel。

**Q24：dangerous 权限怎么申请？**（语音功能）
> `RECORD_AUDIO` 等自 API 23 是 dangerous，用 `registerForActivityResult(RequestPermission)` 运行时弹窗申请。

**Q25：Application 里能直接用 context 吗？**
> 不能在 `<init>` 阶段用，`attachBaseContext` 还没调用。依赖 context 的单例要 `by lazy`。见 9.6。

---

## 第 12 章 自我陈述模板（30 秒 / 2 分钟 / 5 分钟）

### 12.1 30 秒电梯版

> "我独立开发了一个 AI 聊天机器人 Android 客户端，技术栈是 Kotlin + Jetpack Compose + Room + OkHttp。核心是**用 OkHttp SSE + `callbackFlow` 实现大模型的流式输出**，解决了协程背压（trySend 丢事件）和 Room 流式持久化（杀进程可恢复）等难题。架构用 MVVM + 手动 DI，约 4142 行代码。"

### 12.2 2 分钟标准版（面试开场自我介绍后用）

> "这个项目是给实验室自研的 AI 知识助手后端做的 Android 客户端。后端是 Go + 向量数据库 + ReAct Agent，提供了 SSE 流式接口，我做的是 Android 端，从 API 对接到 UI 渲染完整链路。
>
> 技术上最核心的是 **SSE 流式**：我用 OkHttp 的 `okhttp-sse` 库建立长连接，用 `callbackFlow` 把回调式 API 包装成 Kotlin Flow。这里踩了个深坑——`callbackFlow` 默认缓冲区只有 64，工具查询时后端会发 200 多条 trace 事件，`trySend` 满了静默丢数据，连带丢了最终的 message 和 done 事件。我用三层解决：源头过滤噪音、`buffer(UNLIMITED)` 兜底、批量写库。
>
> 数据层用 **Room** 双表设计，外键级联。流式消息用 **INSERT 占位 + UPDATE 增量**的方式存，这样即使 App 被杀进程，重启后靠 `is_partial` 标记能恢复中断的消息。这里也踩了个经典坑——`OnConflictStrategy.REPLACE` 在 SQLite 里是 DELETE+INSERT，会触发外键 CASCADE 把历史消息删光，我改成了显式的 INSERT/UPDATE。
>
> UI 用 **Jetpack Compose**，状态用 StateFlow + sealed class 建模成状态机。性能上做了四层优化，比如 `.conflate()` 把每秒 50 次重组降到 10 次。架构是 MVVM + 手动 DI，没用 Hilt，因为依赖图简单，手动 DI 更透明。"

### 12.3 5 分钟深度版（项目深挖环节用）

在 2 分钟版基础上，每个点准备一段"追问深挖"的话术：

- **SSE 追问**（讲 callbackFlow 桥接、trySend 背压、awaitClose 清理、readTimeout=0、日志级别）→ 对应第 4 章
- **Room 追问**（讲表设计、FK CASCADE、REPLACE 坑、流式持久化、迁移）→ 对应第 5、9 章
- **Compose 追问**（讲状态机、LazyColumn 滚动、四层性能优化、conflate）→ 对应第 6 章
- **网络追问**（讲三客户端共享连接池、后端地址热切换）→ 对应第 7 章
- **DI 追问**（讲为什么手动 DI、ViewModel 工厂）→ 对应第 2 章

**结构建议**：每个深挖点都用 **"现象 → 根因 → 解决 → 收获"** 四段式，配上第 9 章的 Bug 故事。

---

## 附录 高频名词速查表

| 名词 | 一句话解释 |
|------|-----------|
| **MVVM** | Model-View-ViewModel，UI/逻辑/数据分离的架构 |
| **Repository** | 单一数据出口，封装网络和数据库 |
| **DI（依赖注入）** | 对象的依赖从外部传入，而非内部 new |
| **协程（Coroutine）** | 轻量级线程，用挂起函数写异步代码像同步一样 |
| **Flow** | 协程版的"数据流"，可 `collect` 消费的冷流 |
| **StateFlow** | 始终有值的 Flow，适合表示 UI 状态 |
| **callbackFlow** | 把回调式 API 包装成 Flow 的构建器 |
| **Channel** | 协程间的"管道"，`trySend`/`send` 发送，有容量和背压策略 |
| **SSE** | Server-Sent Events，基于 HTTP 的服务器单向推送 |
| **背压（Backpressure）** | 生产者比消费者快时，如何处理积压（缓冲/丢弃/挂起） |
| **sealed class** | 密封类，子类有限且穷举，`when` 分支编译器检查完整性 |
| **Room** | SQLite 的 Jetpack 封装（Entity/DAO/Database） |
| **DAO** | Data Access Object，定义数据库操作 |
| **FK CASCADE** | 外键级联，删父行自动删子行 |
| **Compose 重组** | 状态变化时，受影响的 Composable 重新执行更新 UI |
| **LazyColumn** | Compose 版 RecyclerView，只渲染可见项 |
| **DataStore** | 协程/Flow 原生的键值存储，替代 SharedPreferences |
| **ReAct Agent** | 让大模型"边推理边调工具"的模式 |
| **向量数据库（Milvus）** | 存向量做相似度检索的数据库（后端用） |
| **`by lazy`** | Kotlin 属性委托，首次访问才初始化，线程安全 |

---

> **最后的话**：这篇文档的目的不是让你背下来，而是让你**理解每个技术决策背后的 why**。面试官真正考的不是"你知不知道 callbackFlow"，而是"你为什么用 callbackFlow、不用会怎样、出了问题怎么查"。把这些 why 内化，配合第 9 章的实战故事，你就能把一个"小项目"讲出"大深度"。
>
> 完整的 Bug 记录见 `BUGFIX_LOG.md`，功能细节见 `FEATURES.md`，架构细节见 `ARCHITECTURE.md`，简历版本见 `RESUME.md`。祝你面试顺利。🎯
