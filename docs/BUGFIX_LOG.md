# LabSage Android — 联调问题修复记录

按优先级（严重程度）降序排列。

---

## P0 — 应用崩溃级

### 1. FOREIGN KEY 约束失败 — 插入消息时会话记录不存在

| 项目 | 内容 |
|------|------|
| **现象** | 点击发送消息后 app 立即崩溃，`SQLiteConstraintException: FOREIGN KEY constraint failed (code 787)` |
| **根因** | `ChatViewModel.sendMessage()` 中 `saveUserMessage()` 先于 `upsertConversation()` 执行。`messages` 表通过 `conversation_id` 外键引用 `conversations` 表，插入消息时目标会话记录尚未创建，SQLite 拒绝该 INSERT |
| **触发条件** | 新建对话后首次发送消息（已有历史记录的会话不受影响，因为 conversation 已存在） |
| **修复** | 交换两行调用顺序：先 `upsertConversation()` 创建/更新会话记录，再 `saveUserMessage()` 插入消息 |
| **影响文件** | `ChatViewModel.kt:73-75` |
| **教训** | 有 FK 约束的表，INSERT 顺序必须与依赖方向一致（被引用表 → 引用表）；Compose + Room 开发中应先验证 Room DAO 的级联和约束行为 |

---

### 2. Compose BOM 版本与 Markdown 库不兼容 — `NoSuchMethodError: shouldExecute`

| 项目 | 内容 |
|------|------|
| **现象** | 从会话列表点击进入聊天界面即崩溃，`NoSuchMethodError: No interface method shouldExecute(ZI)Z in class Composer` |
| **根因** | `multiplatform-markdown-renderer-m3:0.40.2` 编译时依赖的 Compose Runtime API 版本高于项目使用的 Compose BOM `2024.12.01` 提供的版本。库内部调用 `Composer.shouldExecute()` 方法，该方法在旧版 Runtime 中不存在 |
| **触发条件** | 进入 ChatScreen → LazyColumn 渲染历史消息列表 → MessageBubble 尝试调用 `Markdown()` composable |
| **修复** | 将 Compose BOM 从 `2024.12.01` 升级到 `2025.06.01` |
| **影响文件** | `gradle/libs.versions.toml` |

---

### 3. Markdown 库仍不兼容 — `NoSuchMethodError: getCurrentCompositeKeyHashCode`

| 项目 | 内容 |
|------|------|
| **现象** | 修复 BOM 后进入 ChatScreen 不再崩溃，但发送消息接收到后端响应后（Markdown 渲染完成消息时）仍然崩溃，`NoSuchMethodError: No static method getCurrentCompositeKeyHashCode(Landroidx/compose/runtime/Composer;I)J` |
| **根因** | `multiplatform-markdown-renderer-m3:0.40.2` 实际编译目标为 Compose 1.10.3 / Kotlin 2.3.20。即使 BOM `2025.06.01` 也尚未提供该版本的 Compose Runtime。库调用的 `ComposablesKt.getCurrentCompositeKeyHashCode()` 在项目运行时的 Compose 版本中不存在 |
| **修复** | **临时方案**：移除 `Markdown()` composable 调用，改用原生 `Text()` 渲染纯文本 |
| **选型分析** | |

| 方案 | 评估 |
|------|------|
| 继续升级 BOM | BOM `2025.06.01` 已是较新版本，再升级未必有此 API（`getCurrentCompositeKeyHashCode` 是 Compose 1.10+ 新增的内部 API）。且 BOM 版本与 AGP 9.2.1 / Kotlin 2.2.10 的兼容性需要逐一验证 |
| 降级 markdown 库 | 寻找兼容 Compose BOM 2025.06.01 的历史版本。v0.30.x 或更早版本可能使用更基础的 Compose API，但功能缺失（无流式支持、无代码高亮） |
| WebView + marked.js | 每消息一个 WebView 太重（+112MB 内存/实例），LazyColumn 中不可行 |
| 自定义 AnnotatedString | 4-8 周工程工作量，不可行 |
| **当前方案：纯 Text** | 流式完成后显示纯文本。Markdown 语法原样展示（`**粗体**`），视觉可接受，功能不影响 |
| **最终目标** | 跟踪 `multiplatform-markdown-renderer` 与 Compose BOM 的兼容性矩阵，在版本匹配时重新集成。代码中已预留 TODO 标记 |

| **影响文件** | `MessageBubble.kt`，`gradle/libs.versions.toml` |

---

## P1 — 功能缺陷级

### 4. SSE 事件丢失 — Done/Error 后过早关闭 Channel

| 项目 | 内容 |
|------|------|
| **现象** | 流式响应可能收不到 Done 或 Error 事件，UI 一直停留在 Streaming 状态 |
| **根因** | `OkHttpSseDataSource` 的 `EventSourceListener.onEvent()` 中，`trySend(event)` 后立即调用 `channel.close()`。`trySend` 是非阻塞的，事件尚未被下游 Flow collector 消费时 channel 可能已关闭，导致事件丢失 |
| **触发条件** | SSE 流完成或出错时（每个流结束时会有一定概率发生） |
| **修复** | 移除 `onEvent()` 中的 `channel.close()` 调用，改为依赖 `onClosed()` 回调来关闭 channel：<br>1. 正常结束：服务器关闭连接 → `onClosed()` → `trySend(Done)` + `close()`<br>2. 异常结束：`onFailure()` → `trySend(Error)` + `close(error)` |
| **设计要点** | - `callbackFlow` 的 `close()` 在发送最后一个元素后调用是安全的，但必须确保元素先被投递<br>- OkHttp `EventSourceListener` 的生命周期回调保证 `onClosed` 在最后一个 `onEvent` 之后才触发<br>- 在 `onEvent` 内部调用 `close` 是竞态条件 |

| **影响文件** | `OkHttpSseDataSource.kt:51-64` |

---

### 5. 非 retryable 错误无用户提示

| 项目 | 内容 |
|------|------|
| **现象** | 发送消息后后端无法连接（如网络不通），用户看不到任何错误提示，输入框重新可用但消息"消失"了 |
| **根因** | `ChatScreen` 只在 `StreamState.Error.retryable == true` 时显示 `PartialMessageBanner`。连接失败（`ConnectException`）产生的 Error 的 `retryable` 为 `false`，且 `partialAnswer` 为空，`PartialMessageBanner` 不会渲染，也没有任何其他错误提示 |
| **修复** | 对 `retryable == false` 的错误增补 `Snackbar` 显示错误消息："Error: {message}" + "Dismiss" 按钮 |
| **设计考量** | - retryable 错误（流中断但有部分内容）→ 显示 `PartialMessageBanner` 含"继续"/"重新生成"按钮<br>- 非 retryable 错误（连接失败、无内容）→ 显示 `Snackbar` 含错误信息和关闭按钮<br>- 未来可增加 Toast 或 Dialog 选项 |

| **影响文件** | `ChatScreen.kt:125-147`，`ChatViewModel.kt`（新增 `clearError()`） |

---

### 6. 流式指示器在首个 chunk 后消失

| 项目 | 内容 |
|------|------|
| **现象** | 用户发送消息后，"打字中"动画仅一闪而过就消失了，看起来像卡住了 |
| **根因** | 流式指示器的显示条件是 `isStreaming && messages.lastOrNull()?.role == "user"`。第一个 SSE message chunk 到达后，Room 插入/更新 assistant 消息记录 → Room Flow 触发 → `_messages` 更新 → 最后一条消息的 role 变为 `"assistant"` → 指示器消失 |
| **修复** | 改为 `isStreaming` 单独控制指示器可见性，不再依赖最后一条消息的角色判断 |
| **设计要点** | 指示器应反映"正在等待/接收"的状态，而不是"还没收到第一个 chunk"的状态 |

| **影响文件** | `ChatScreen.kt:119-122` |

---

## P2 — 代码质量级

### 7. OkHttp 日志拦截器配置风险

| 项目 | 内容 |
|------|------|
| **现象** | 潜在问题 — SSE 连接永不回调（当前未触发，因为配置正确） |
| **风险** | 若将来有人将 SSE 客户端的日志级别设为 `Level.BODY`，OkHttp 的 `HttpLoggingInterceptor` 会缓冲整个响应体后才回调 `onResponse`。对于 SSE 无限流，这会导致 `EventSourceListener.onOpen()` 永不触发 |
| **预防** | 在 `LabSageApplication` 中已将 baseClient 设为 `Level.HEADERS`，SSE 客户端通过 `newBuilder()` 派生，自然继承 HEADERS 级别 |
| **建议** | 添加 lint 规则或代码注释，禁止 SSE 客户端使用 `Level.BODY` |

| **影响文件** | `LabSageApplication.kt:64-68` |

---

---

### 8. Room `@Insert(REPLACE)` 触发 FK CASCADE 删除全部消息

| 项目 | 内容 |
|------|------|
| **现象** | 每次发送新消息后，之前的历史聊天记录全部消失（同一会话内） |
| **根因** | `ConversationDao.upsert()` 使用 `@Insert(onConflict = OnConflictStrategy.REPLACE)`。SQLite 的 REPLACE 算法在遇到主键冲突时执行 **DELETE + INSERT**（非 UPDATE）。DELETE 操作触发 `messages` 表的外键 `ON DELETE CASCADE`，导致该会话的所有消息被级联删除 |
| **验证** | 发送第二条消息后，Room Flow 仅返回最新的用户+助手消息，旧消息从数据库被物理删除 |
| **触发条件** | 在同一会话中发送第二条及后续消息（首次发送时 session ID 不存在，INSERT 成功，无 REPLACE） |
| **修复** | 拆分为显式的 INSERT/UPDATE 逻辑：<br>1. 新增 `updateMeta()` 使用 `@Query("UPDATE conversations SET ...")` 仅更新 title 和 timestamp<br>2. 新增 `insert()` 使用 `@Insert(onConflict = OnConflictStrategy.IGNORE)`<br>3. `ChatRepository.upsertConversation()` 先 `getById()` 判断是否存在，存在则调用 `updateMeta()`，不存在则 `insert()` |
| **教训** | `OnConflictStrategy.REPLACE` 在有 FK CASCADE 的表上极其危险。SQLite REPLACE = DELETE + INSERT，不是 MySQL 的 ON DUPLICATE KEY UPDATE。Room DAO 设计时应优先使用 `@Query("UPDATE ...")` 做局部更新 |

| **影响文件** | `ConversationDao.kt`，`ChatRepository.kt` |

---

## 总结

| # | 问题 | 严重度 | 类型 | 修复状态 |
|---|------|--------|------|---------|
| 1 | FK 约束崩溃 | P0 | 逻辑错误 | ✅ 已修复 |
| 2 | Compose BOM 版本 | P0 | 依赖版本 | ✅ 已修复 |
| 3 | Markdown 库不兼容 | P0 | 依赖版本 | ⚠️ 临时回退 |
| 4 | SSE 事件丢失 | P1 | 并发竞态 | ✅ 已修复 |
| 5 | 错误无提示 | P1 | UI 缺失 | ✅ 已修复 |
| 6 | 流式指示器消失 | P1 | UI 逻辑 | ✅ 已修复 |
| 7 | 日志拦截器风险 | P2 | 预防性 | ✅ 已预防 |
| 8 | REPLACE 触发 CASCADE | P0 | 逻辑错误 | ✅ 已修复 |
| 9 | SSE 空消息不展示 | P1 | UI 缺失 | ✅ 已修复 |
| 10 | 摘要对话框不关闭 | P2 | UI 逻辑 | ✅ 已修复 |
| 11 | trySend 溢丢弃 message/done | P0 | 数据丢失 | ✅ 已修复 |
| 12 | Markdown 库选型 | P1 | 依赖兼容 | ✅ jeziellago |
| 13 | trace 过滤丢弃工具入参 | P2 | 功能缺陷 | ✅ 已修复 |

---

### 11. trySend 缓冲区溢出 — GPU/Conda 查询输出中断

| 项目 | 内容 |
|------|------|
| **现象** | 点击 GPU/Conda 查询按钮后，回复输出一部分就停止，UI 停留在 Streaming 状态，Done 事件不触发 |
| **根因** | 工具查询时后端发送 200+ 条 SSE `trace` 事件（路由决策、工具调用状态、中间日志）。`callbackFlow` 默认 buffer = 64。`trySend()` 在 buffer 满后静默丢弃事件，导致后续的 `message` 和 `done` 事件被丢弃。`trySend` 返回 `ChannelResult.closed` 或 `ChannelResult.failure` 但未被检查 |
| **验证** | `time curl /api/chat_stream` 返回 993 行 SSE 数据（~5 秒），`callbackFlow` buffer 只能容纳 64 个未消费事件 |
| **修复** | **Phase 1 — 事件过滤**：`onEvent` 中仅转发工具生命周期 trace（"调用工具"/"调用完成"/"route="），~200 → ~3 条<br>**Phase 2 — 无限缓冲**：`callbackFlow { ... }.buffer(Channel.UNLIMITED)` 防止 238 条 message chunk + Room 写入延迟导致的 buffer 溢出<br>**Phase 3 — 批量写入**：Room `updateContent` 每 5 chunk 执行一次（减少磁盘 I/O），Done 时 `finalizeMessage` 保证最终内容写入 |
| **设计决策** | |
| 方案 | 评估 |
| `send()` 替代 `trySend` | ❌ `send()` 是 suspend 函数，`EventSourceListener.onEvent()` 不是协程上下文，需要额外 `launch` |
| 增大 buffer (`Channel.UNLIMITED`) | ❌ 993 条事件全部缓存 → 内存膨胀 + 消费延迟 |
| **过滤 trace 事件** | ✅ 保留 ~5 条工具生命周期事件，丢弃 200+ 条噪音，buffer 安全 |
| **影响文件** | `OkHttpSseDataSource.kt`，`ChatViewModel.kt`（更新 trace 处理） |

| **面试考点**：`callbackFlow` vs `channelFlow` 的区别？`trySend` 的返回值含义？Channel 背压策略（SUSPEND/CONFLATED/BUFFERED/UNLIMITED）的选择？

---

### 12. Markdown 渲染库选型 — mikepenz 全系不兼容 Compose BOM 2025.06.01

| 项目 | 内容 |
|------|------|
| **现象** | 多次尝试 `mikepenz/multiplatform-markdown-renderer-m3` v0.35.0 / v0.40.2 / v0.41.0，编译通过但运行时均崩溃：`NoSuchMethodError: getCurrentCompositeKeyHashCode(Landroidx/compose/runtime/Composer;I)J` |
| **根因** | mikepenz 库 v0.35+ 编译目标为 Compose Runtime 1.10+。项目 Compose BOM `2025.06.01` 提供的 Runtime 版本早于 1.10，不包含 `ComposablesKt.getCurrentCompositeKeyHashCode()` 方法 |
| **选型** | |
| 库 | 版本 | 结果 |
| mikepenz M3 | 0.41.0 | ❌ NoSuchMethodError |
| mikepenz M3 | 0.40.2 | ❌ NoSuchMethodError |
| mikepenz M3 | 0.35.0 | ❌ NoSuchMethodError |
| **jeziellago** | **0.5.7** | ✅ 兼容，MIT license，845 stars |
| **决策** | 使用 `dev.jeziellago:compose-markdown:0.5.7`（JitPack），API：`MarkdownText(markdown = ...)`。纯 Compose（内嵌 TextView），支持 HTML/tables/task lists/linkify。内存开销低，无 WebView 依赖 |
| **影响文件** | `gradle/libs.versions.toml`，`settings.gradle.kts`（JitPack repo），`MessageBubble.kt`，`ChatScreen.kt` |

| **面试考点**：JitPack vs Maven Central 的区别？Compose Runtime 版本兼容性如何排查？WebView vs TextView-based Markdown 渲染的选型依据？

---

### 9. SSE 空消息期间无 UI 反馈 — Thinking 动画缺失

| 项目 | 内容 |
|------|------|
| **现象** | 发送 conda/GPU 查询后，assistant 气泡一直为空，用户以为卡死。实际后端在调用工具（`query_conda_env_usage`），工具执行期间 SSE `message` 事件 data 为空 |
| **根因** | 后端 ReAct Agent 在工具调用阶段发送的 `message` 事件 data 为空字符串，前端 `accumulatedAnswer.append("")` 不产生任何内容，UI 无变化 |
| **触发条件** | 触发 ReAct Agent 工具调用的查询（如 "查询 GPU 状态"） |
| **修复** | 在 `MessageBubble` 中增加 `ThinkingText()` 组件：content 为空 + `isStreaming=true` 时显示动画 "Thinking..." 带递增圆点数。同时 `InlineTraceSummary` 在 trace 事件到达时显示 "Calling xxx..." / "Tool completed" 状态 |
| **设计考量** | - 不能用 `CircularProgressIndicator`（太突兀，与对话 UI 不协调）<br>- 不能用固定的 "Loading..."（无动态感）<br>- 最终选择 "Thinking" + 递变点数：简洁、有动态反馈、与 LLM 对话场景语义匹配 |

| **影响文件** | `MessageBubble.kt` |

---

### 10. 会话摘要对话框无法提交修改

| 项目 | 内容 |
|------|------|
| **现象** | 点击 Info 按钮打开摘要对话框，编辑后点 "Save" 无反应，对话框不关闭 |
| **根因** | `updateSessionSummary()` 中 `_sessionContext.value = _sessionContext.value?.copy(...)` 只更新了 StateFlow 值但未设为 null，对话框仍显示且无反馈 |
| **修复** | 成功时 `_sessionContext.value = null`（关闭对话框）；失败时追加错误信息到 summary 字段显示 |
| **附加修复** | `SessionContextUi` data class 被误放在 ChatViewModel class 内部（导致语法错误），移至顶层声明 |

| **影响文件** | `ChatViewModel.kt`，`ChatScreen.kt` |

---

### 13. SSE trace 过滤丢弃工具入参 —— 工具执行面板拿不到 args

| 项目 | 内容 |
|------|------|
| **现象** | 工具执行面板升级时，每个工具调用卡的"输入参数"始终为空，只能拿到"输出摘要" |
| **根因** | `OkHttpSseDataSource.onEvent()` 的 trace 过滤条件是 `l.contains("调用工具") || l.contains("调用完成") || l.contains("route=")`。后端工具入参行格式为 `[tool] 开始调用 #N <name> args=<json>`，它只含"开始调用"而不含上述任一关键字，被静默过滤掉；而含结果的 `[tool] 调用完成 ...` 因含"调用完成"得以保留 |
| **触发条件** | 任何触发 ReAct 工具调用的查询（GPU / Conda / 文件操作） |
| **修复** | 改为按结构化前缀过滤：保留 `l.startsWith("[tool]") || l.startsWith("[react]") || l.startsWith("[route")`。这些都是后端 `log_call_back.go` 发出的有界生命周期行（每工具调用 2-3 条，args 截断 160 / result 截断 400 字符），不会引入 BUG #11 的 200+ 自由文本 trace 溢出 |
| **教训** | trace 过滤应以"结构化前缀"而非"中文关键字子串"为准则——关键字匹配会漏掉格式相近但措辞不同的生命周期行；前缀匹配更精确且能随格式稳定 |
| **影响文件** | `OkHttpSseDataSource.kt` |
