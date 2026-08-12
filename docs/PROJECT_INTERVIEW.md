# LabSage Android 项目精简文档

> 本文用于校招 Android 面试复习，只保留项目定位、核心链路、技术选型和问题复盘。性能优化部分不在本文展开。

## 一、项目介绍

LabSage 是一个面向实验室知识检索和环境查询场景的 AI 助手。后端已经具备知识库检索、ReAct Agent 和工具调用能力，Android 端负责把这些能力组织成可长期使用的移动端产品。

客户端使用 Kotlin 和 Jetpack Compose 开发，支持：

- SSE 流式问答，以及中断后的继续生成和重新生成；
- ReAct 路由与工具调用过程展示；
- 多会话管理、消息本地持久化和会话上下文维护；
- 知识库文件上传、列表查询和删除；
- 后端地址、主题、快捷操作等设置；
- 长回答完成通知，并能从通知精确回到对应消息。

项目采用单 Activity + Compose Navigation，整体架构是 MVVM + Repository + 手动依赖注入。界面层只消费 ViewModel 暴露的状态；ViewModel 负责业务编排；Repository 统一协调远端接口和本地数据库。

```text
Compose UI
    ↓ 用户操作 / ↑ UI State
ViewModel（StateFlow + sealed class）
    ↓
ChatRepository
    ├── Retrofit：普通 REST 请求
    ├── OkHttp SSE：流式长连接
    ├── Room：会话与消息
    └── DataStore：用户设置
```

技术栈：Kotlin、Jetpack Compose、Material 3、Coroutines、Flow/StateFlow、OkHttp、okhttp-sse、Retrofit、Room、DataStore、Navigation Compose。

## 二、架构设计与选型

### 1. MVVM + Repository

UI 不直接访问 API 或 DAO。`ChatScreen`、`ConversationListScreen` 等页面只向 ViewModel 发出操作，并收集 `StateFlow`；ViewModel 再通过 `ChatRepository` 访问网络或数据库。

这样划分的目的不是单纯增加层级，而是分开三类职责：

- Compose 只处理状态展示和用户交互；
- ViewModel 处理发送、续传、删除、刷新等业务流程，并跟随页面生命周期保存状态；
- Repository 隐藏数据来自 Retrofit、SSE 还是 Room，使 ViewModel 不依赖具体实现。

流式状态使用 `sealed class StreamState` 建模为 `Idle`、`Streaming`、`Completed`、`Error`。相比多个相互独立的 Boolean，密封类能表达互斥状态，`when` 也能在编译期检查分支是否完整。Compose 通过 `collectAsStateWithLifecycle` 收集状态，在状态变化后更新相关界面。


## 三、关键模块

### 1. 流式问答模块

这是项目的主链路。用户发送问题后，客户端先创建或更新会话，再保存用户消息，随后建立 SSE 连接。后端持续发送文本、路由、工具调用和结束事件，客户端将事件转换为统一模型并交给 ViewModel 处理。

```text
用户发送问题
  → ChatViewModel 校验状态并保存会话、用户消息
  → ChatRepository.sendStreamMessage()
  → OkHttp EventSource 建立 /api/chat_stream 长连接
  → EventSourceListener 接收回调
  → callbackFlow 转为 Flow<SseEvent>
  → ChatViewModel 按事件更新消息、工具状态和 StreamState
  → Room Flow 回推消息列表
  → Compose 重组对应界面
```

网络层将两类请求分开处理：

- 普通请求，如文件、会话上下文和非流式问答，使用 Retrofit。它适合固定的请求—响应模型，并能完成注解声明、JSON 转换和 Multipart 封装。
- 流式问答使用 OkHttp 的 `okhttp-sse`。SSE 是持续回调的长连接，Retrofit 在这里没有明显增值，直接使用 `EventSourceListener` 更贴合协议模型。

`callbackFlow` 用来把 `EventSourceListener` 的回调桥接成冷 Flow；`awaitClose` 在收集取消时关闭 `EventSource`，使网络连接和协程生命周期保持一致。SSE 客户端设置无限读超时，因为连接在一段时间没有正文时也不能被普通请求超时策略误判为失败。生产链路的 SSE 协议解析由 `okhttp-sse` 完成，仓库中的 `SseParser` 只是协议学习和备选实现。

后端事件被整理为 `Connected`、`Message`、`Trace`、`TraceSummary`、`Done` 和 `Error`。ViewModel 不直接处理字符串事件名，而是处理密封事件模型，减少协议细节向业务层扩散。

### 2. 消息持久化与中断恢复

Room 中有 `conversations` 和 `messages` 两张表。消息通过 `conversation_id` 外键关联会话，删除会话时由 `ON DELETE CASCADE` 清理其消息。会话负责标题、创建时间、更新时间和置顶状态；消息负责角色、正文、路由、是否为残缺回答、原始问题等信息。

流式回答不会为每个片段新增一条消息，而是维护同一条 assistant 记录：

```text
首个 message 事件
  → 插入 assistant 消息，标记 is_partial = true
后续 message 事件
  → 累加完整正文并更新同一条记录
done
  → 写入最终正文和 route，标记 is_partial = false
error / 连接中断
  → 保留已接收正文、original_question 和 is_partial = true
```

消息列表以 Room Flow 作为界面数据来源。网络事件负责改变数据库，数据库变化再回推到 ViewModel 和 Compose，避免同时维护一份“网络消息列表”和一份“数据库消息列表”。

当流中断且已经收到部分正文时，界面提供两条恢复路径：

- 继续生成：携带原问题、`Continue=true` 和 `PartialAnswer` 请求后端续写；
- 重新生成：删除残缺 assistant 消息，再发送原问题。

进程被系统杀死后，ViewModel 内存状态会消失，但 Room 中的 `is_partial`、正文和 `original_question` 仍然存在。重新进入会话时，ViewModel 会从最后一条残缺 assistant 消息重建 `StreamState.Error`、当前消息 ID 和已累积正文，因此恢复能力不依赖旧进程还活着。

### 3. ReAct 工具调用展示

后端在回答过程中通过 `trace` 事件发送路由和工具生命周期信息。客户端只消费有明确结构的 `[route]`、`[react]`、`[tool]` 行，再在 ViewModel 中转换为 `ToolCallUi`，展示工具名、入参、结果、调用状态和路由类型。

```text
trace 事件
  → OkHttpSseDataSource 保留结构化生命周期行
  → ChatViewModel.applyToolTrace()
  → CALLING / COMPLETED / ERROR 工具状态
  → ToolExecutionPanel 展示时间线
```

开始事件带调用序号，完成事件只带工具名，因此完成事件使用“同名且仍处于 CALLING 的最早一项”进行 FIFO 配对。流结束时仍未完成的调用标记为 `ERROR`。这个设计来自真实协议约束，而不是假设所有事件都拥有统一 ID。

项目还提供工具审批模式。当前实现是在请求发送前进行客户端确认，因为流开始后是否暂停工具执行由后端控制；如果要做到真正的逐工具审批，需要后端新增待审批事件和确认通道。面试中应明确这一边界，不能把发送前确认描述成完整的人机协同协议。

### 4. 会话与上下文管理

会话列表由 Room Flow 驱动，支持搜索、置顶、重命名和删除。搜索词变化后使用 `flatMapLatest` 在完整列表和 DAO 模糊查询之间切换，旧查询会被取消，避免过期结果覆盖新结果。

置顶字段通过 Room v1 到 v2 的 Migration 增加，默认值为 `0`，旧数据可以原样保留。重命名只更新标题，不更新 `updated_at`，因为“改标题”不应被当成新会话活动而改变排序。

删除采用延迟提交：先在 StateFlow 中隐藏会话，Snackbar 超时后才真正删除 Room 数据。如果用户撤销，只需取消待删除状态。这样做是因为真实删除会触发外键级联，先删库再恢复需要重建整个会话及其消息，数据风险更高。

会话上下文由后端保存，包含 summary 和 string-encoded facts JSON。客户端把 facts 解析成稳定的键值模型，兼容 `{ "facts": {...} }` 包装和直接对象；删除单项时保留其余字段，再通过现有 PUT 接口整体回写。当前方案适用于单客户端场景，多端并发下可能发生覆盖，更完整的服务端设计应提供按 key 的原子删除接口，并配合 version 或 ETag。

### 5. 文件与知识库管理

文件上传使用系统文件选择器返回的 Content URI。Repository 通过 Application Context 的 `ContentResolver` 读取内容，复制到缓存文件后构造 Multipart 请求。使用 Application Context 是因为 Repository 和 `viewModelScope` 的生命周期可能长于 Activity，不能让上传协程持有旋转前的 Activity。

文件列表不落 Room，而是以后端为唯一事实源：

```text
进入文件页 / 主动刷新
  → Retrofit GET /api/files
  → UiState.Loading / Success / Error
删除文件
  → 本地列表先移除
  → Retrofit DELETE /api/files/{id}
  → 失败时恢复该项并提示
```

文件本身、知识库索引和删除结果都由后端控制，本地建立文件表会额外引入同步和冲突问题，因此当前采用 remote-first。删除必须使用后端返回的稳定 ID，客户端把 ID 当作不透明字符串，不根据路径自行推导。

### 6. 设置、通知与导航

设置属于少量、扁平、需要持久化并可观察的键值数据，因此使用 Preferences DataStore，而不是 Room。后端地址变化后，Application 按规范化 URL 获取或创建对应的 Retrofit/SSE 网络层，新请求无需重启应用即可使用新地址。

长回答完成后，客户端先将最终正文写入 Room，再发布系统通知。通知携带 `conversationId + messageId`：

```text
SseEvent.Done
  → finalizeAssistantMessage()
  → 发布完成通知
  → PendingIntent 打开 MainActivity
  → onCreate（冷启动）或 onNewIntent（热启动）
  → Navigation 进入指定会话
  → Room Flow 返回消息后按 messageId 定位
```

先落库再通知可以保证用户立即点击时目标消息已经存在。冷启动和热启动分别处理，是因为已有 Activity 配合 `CLEAR_TOP | SINGLE_TOP` 时会进入 `onNewIntent`，不会重新执行 `onCreate`。Android 13 及以上发布通知前还要检查运行时权限；通知属于附加能力，失败不能影响回答完成和消息保存。

## 四、主要问题与解决方案

### 1. 插入消息时触发外键约束失败

**现象：** 新会话第一次发送消息时数据库报 `FOREIGN KEY constraint failed`。

**根因：** 代码先插入 `messages`，后创建 `conversations`。子表写入时父表记录还不存在。

**解决：** 调整业务顺序，先 `upsertConversation` 保证父记录存在，再保存用户消息和 assistant 消息。这个问题说明数据库约束不仅是表结构配置，也会反向约束业务执行顺序。

### 2. 更新会话后历史消息被清空

**现象：** 已有会话发送新消息后，之前的消息全部消失。

**根因：** `OnConflictStrategy.REPLACE` 在 SQLite 中不是 UPDATE，而是 DELETE 后 INSERT。父表记录被删除时触发 `ON DELETE CASCADE`，其子消息随之被删除。

**解决：** 新会话使用 `INSERT(IGNORE)`；已有会话使用明确的 `UPDATE` 更新标题和时间。保留级联删除，因为它在用户真正删除会话时仍然是正确设计，只修正错误的冲突处理方式。

### 3. 工具查询时 SSE 后半段事件丢失

**现象：** 普通问答正常，工具查询有时收不到最终正文或 `done`，界面一直停留在生成中。

**根因：** 工具调用会产生大量 trace。`callbackFlow` 默认 Channel 容量有限，而 `trySend` 不会挂起等待；容量满后发送失败，代码又没有处理返回结果，后续 message 和 done 被静默丢弃。

**解决：** 网络层只保留界面真正需要的结构化 trace 生命周期事件，并为事件通道提供明确缓冲；同时把 `done` 后的正常关闭交给 `onClosed`，避免发送终止事件后立即关闭 Channel。这里解决的是事件可靠性问题，核心是理解 `trySend` 的失败语义和流的关闭时序。

### 4. 进程重启后无法继续残缺回答

**现象：** 流式过程中强制结束应用，重新打开后能看到部分正文，却没有“继续生成”入口。

**根因：** 部分正文已经保存到 Room，但 `StreamState`、累积正文和当前消息 ID 只存在于 ViewModel 内存。进程死亡后，UI 只恢复了消息列表，没有恢复业务状态。

**解决：** ViewModel 首次收到 Room 消息时检查最后一条 assistant 消息。如果它标记为 `is_partial`，就使用持久化的正文、消息 ID 和 `original_question` 重建错误/可续传状态，使继续生成和重新生成都能工作。

### 5. Markdown 组件运行时崩溃

**现象：** 项目可以编译，但进入包含 Markdown 的消息页面时出现 `NoSuchMethodError`。

**根因：** 最初选用的 Markdown 库编译时依赖了更高版本的 Compose Runtime，项目 BOM 中没有对应方法。这是二进制兼容问题，不是业务代码调用错误。

**解决：** 对照依赖版本和运行时缺失的方法，确认版本边界后更换为与当前 Compose BOM 兼容的 `compose-markdown`。流式阶段使用普通文本，回答完成后再渲染完整 Markdown，避免半截语法影响展示。

### 6. 设置页接入后应用启动崩溃

**现象：** 引入 DataStore 设置后，应用在启动阶段出现空指针。

**根因：** `Application` 的属性初始化阶段就急切创建 `appSettings`，间接访问 `preferencesDataStore`；此时 Application 的 base context 尚未完成 attach。

**解决：** 将 `appSettings` 改为 `by lazy`，把第一次解析推迟到 Application 上下文已经可用之后。这个问题的关键不是 DataStore 本身，而是 Android 组件构造与 `attachBaseContext` 的时序。

### 7. 文件刷新失败时原列表消失

**现象：** 用户在已有文件列表上刷新，页面先切成全屏 Loading；如果请求失败，原列表也被错误页替换。

**根因：** 首次加载和后台刷新共用了同一个 `Loading/Success/Error` 状态，刷新动作覆盖了当前成功数据。

**解决：** 保留完整 `UiState` 表达首次加载结果，另外使用独立的 `refreshing` 状态表达后台刷新。刷新失败只发送一次性提示，不清空用户已经看到的数据。这里体现的是状态语义拆分，而不是简单增加一个加载动画。

## 五、面试表达建议

介绍项目时可以按下面的顺序展开：

1. 先说明它不是普通聊天页面，而是对接知识库和 ReAct Agent 的 Android 客户端；
2. 用一条消息的完整链路串起 Compose、ViewModel、Repository、SSE 和 Room；
3. 重点讲两个真实问题：`REPLACE` 触发级联删除，以及 Channel 满后事件丢失；
4. 再补充中断恢复、工具调用结构化、通知精确定位等完整性设计；
5. 最后说明选型边界，例如为什么 SSE 不走 Retrofit、为什么当前用手动 DI、为什么文件列表不落 Room。

面试时应坚持代码事实：生产 SSE 解析使用 `okhttp-sse`，不是手写解析器；工具审批目前是发送前确认，不是流中暂停；Facts 删除是整体 PUT，存在多端覆盖边界；Android 客户端是独立完成的部分，Go、Milvus 和 Agent 属于所对接的后端能力。
