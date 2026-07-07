# LabSage — AI 实验室知识助手 Android 客户端

> 本文档按 **STAR 法则** 撰写，所有技术细节、行数、文件数、Bug 数均于 **2026-07-06** 与源码逐项核对一致。
>
> ⚠️ **三处需你按实情确认后再用**：
> 1. **时间跨度**：git 提交记录为 `2026-07-01 ~ 07-05`（24 次提交）。下文简历版本统一写 `2026.07`，若实际开发周期更长（更早动手 / 中途 rebase），请据实调整。
> 2. **无单元测试**：`app/src/test`、`app/src/androidTest` 当前为空。涉及测试只说「纯函数、便于补单测」，**不要写「已覆盖单测」**。
> 3. **`SseParser.kt` 是学习协议时手写的纯函数解析器，未接入运行链路**（生产走 okhttp-sse 内置解析）。若面试官翻到该文件追问，需如实说明，不要宣称「线上用了自研解析器」。

---

## 项目概述（一句话）

独立开发的、基于 **Jetpack Compose** 的 AI 知识助手 Android 客户端，对接 Go 后端（GoFrame + Milvus + ReAct Agent）的 **SSE 流式 API**，支持流式对话与中断续传、ReAct 工具调用过程可视化、Markdown 渲染、多会话管理与暗黑模式。

---

## STAR 描述

### S — Situation（背景）

后端团队基于 **GoFrame + Milvus 向量数据库 + ReAct Agent** 构建了面向实验室运维场景的 AI 知识助手，已提供 **RESTful + SSE（Server-Sent Events）流式 API**，但缺少 Android 端客户端。需要从零交付一个功能完整、架构规范的 Android App 作为团队对外演示产品。

### T — Task（任务）

1. **从零搭建** Android 项目，独立完成技术选型、架构设计、全部编码与前后端联调
2. 实现 **SSE 流式对话**（含中断续传）、**ReAct Agent 执行过程实时展示**、**Markdown 渲染**、文件上传、多会话管理等核心功能
3. 确保流式场景下的 **UI 流畅度与数据可靠性**（消息不丢、杀进程可恢复）
4. 编写覆盖架构设计、Bug 根因分析与选型决策的技术文档

### A — Action（行动）

**架构设计：**
- 采用 **MVVM + Repository + 手动 DI** 架构，不引入 Hilt/Dagger；依赖图集中在 `LabSageApplication` 中以约 **10 个 `by lazy` 单例**组装，透明可调试
- 基于 **StateFlow + sealed class** 构建流式对话状态机（`Idle → Streaming → Completed → Error`），状态穷举、配置变更安全
- 流式层使用 **OkHttp `okhttp-sse` + `callbackFlow`**（**而非 Retrofit CallAdapter**）实现 SSE 解析——Retrofit 对 SSE 长连接无增值，OkHttp 原生 SSE 库更可靠；Retrofit 仅用于 `/api/chat`、`/api/upload`、`/api/session/context` 等短请求

**SSE 流式核心（技术亮点）：**
- 通过 **`callbackFlow` + `awaitClose`** 将 OkHttp `EventSourceListener` 回调桥接为 Kotlin `Flow<SseEvent>`，Flow 取消时自动断开 SSE 连接
- 针对 **trace 事件洪峰**（工具查询产生 200+ 条 trace，溢出 `callbackFlow` 默认 64 容量，`trySend` 静默丢弃 `message`/`done`）设计三级治理：**事件过滤**（仅转发 `[tool]/[react]/[route]` 结构化生命周期行，~200 → ~3 条）→ **`buffer(Channel.UNLIMITED)`** 兜底 → **Room 批量写入**（每 5 chunk 一次，Done 时 finalize）

**数据持久化：**
- 使用 **Room** 存储会话与消息，设计 `conversation` + `message` 双表，通过 `ForeignKey(CASCADE)` 保证引用完整性
- 流式内容采用 **首 chunk INSERT（占位）+ 后续 UPDATE** 模式，进程被 kill 后可通过 `is_partial` 字段恢复中断消息
- 定位并修复 **`OnConflictStrategy.REPLACE` 触发 FK CASCADE 级联删除全部消息** 的严重 Bug：SQLite 的 REPLACE 算法等价于 DELETE + INSERT（非 MySQL 的 `ON DUPLICATE KEY UPDATE`），DELETE 触发 `ON DELETE CASCADE`。修复为拆分显式 `@Query("UPDATE")` + `@Insert(IGNORE)`

**UI 与交互：**
- 基于 **Jetpack Compose Material 3** 实现声明式 UI，`LazyColumn` 渲染消息列表
- **ReAct 工具调用过程可视化**：解析 SSE trace 事件（`[tool] 开始调用 #N name args=…` / `调用完成 name result=…`），实时构建工具调用时间线（FIFO 配对完成态、统计单次耗时），在执行面板呈现 Agent 推理链路；流式空消息期间渲染 **"Thinking" 动画**区分「工具执行中」与「卡死」
- 实现 **Markdown 渲染**（`jeziellago/compose-markdown`，支持代码块/表格/链接）、**暗黑模式**（`isSystemInDarkTheme()` + 自定义 ExtendedColors CompositionLocal）、**网络状态监控**（`ConnectivityManager.NetworkCallback` 断网横幅）、长按消息 **复制/分享/重新编辑**
- 集成 `ActivityResultContracts.GetContent` 文件选取，处理 Content URI → 临时文件拷贝 → multipart 上传至知识库

**性能优化：**
- 四层链路优化：Room Flow **`.conflate()`** 丢弃中间态 → 拆分滚动策略（新消息 `animateScrollToItem`、内容更新 `scrollToItem`）→ **`@Immutable`** + **`derivedStateOf`** 缩小重组范围 → `flowOn(Dispatchers.IO)` 保持主线程不阻塞

**工程质量：**
- 全程 Git 版本控制，**17 个联调问题**（含 P0 崩溃级、并发竞态、依赖兼容等）全部记录根因分析与选型决策（见 `BUGFIX_LOG.md`）
- 编写架构设计、核心知识点、联调记录等多份技术文档，可直接作为面试作品集
- 对接后端 `GET/PUT /api/session/context` 接口支持会话摘要的查看与编辑

### R — Result（成果）

- 独立交付 **33 个源文件 / 约 4142 行 Kotlin** 代码，构建通过、APK 在模拟器端到端跑通
- 修复 `trySend` 缓冲区溢出 Bug 后，流式 `message`/`done` 事件不再丢失
- 流式场景 UI 重组频率从 ~50/s 降至 ~10/s，`LazyColumn` 滚动无抖动
- 杀进程重启后历史消息与中断消息**完整恢复**

---

## 简历建议版本

> 以下版本**逐句与源码核对**，每条 bullet 的代码出处见本节末尾表格。

### 主推版（贴合代码，项目经历 5–6 条时推荐）

> **LabSage — 实验室 AI 知识助手 Android 客户端** ｜ 独立开发 ｜ 2026.07
>
> 为实验室自研 AI 知识助手后端（Go + Milvus + ReAct Agent + SSE 流式）独立开发的 Android 原生客户端，采用 **MVVM + Repository + 手动 DI** 架构，完成从网络层、本地持久化到 Compose UI 的完整链路。
>
> - 基于 **OkHttp + okhttp-sse** 实现 SSE 流式接收，通过 `callbackFlow` + `awaitClose` 将 `EventSourceListener` 回调桥接为 Kotlin `Flow<SseEvent>`，Flow 取消时自动断开连接；针对工具类查询产生的 200+ trace 事件洪峰，采用「事件过滤 + `buffer(Channel.UNLIMITED)` 兜底」策略避免 Channel 默认容量溢出导致丢事件
> - 采用 **MVVM + StateFlow** 架构，将流式状态建模为 sealed class（`Idle → Streaming → Completed → Error`），View 层仅观察单一状态源；借助后端 `Continue + PartialAnswer` 机制实现**中断续传**——部分内容落库后可一键 Continue 接续生成或 Regenerate 重发
> - 使用 **Jetpack Compose** 构建对话界面，针对流式场景设计双策略滚动（新消息 `animateScrollToItem`、流式 chunk 更新用 `scrollToItem`，避免逐 chunk 动画卡顿）；配合 Room Flow `.conflate()` + `@Immutable` + `derivedStateOf` 缩小重组范围，将高频更新下的重组频率从约 50/s 降至约 10/s
> - 解析 SSE **trace** 事件（`[tool] 开始调用 #N name args=…` / `调用完成 name result=…`），实时构建工具调用时间线，按 FIFO 匹配完成态并统计单次调用耗时，在执行面板内联呈现 **ReAct Agent** 推理链路
> - 基于 **Room** 设计 `conversation` + `message` 双表（`ForeignKey` + `CASCADE` 保证引用完整性），流式内容采用「首 chunk INSERT 占位 + 后续 UPDATE」模式，配合 `is_partial` 字段实现进程中断后的消息恢复；定位并修复 `OnConflictStrategy.REPLACE` 在 SQLite 下等价于 DELETE+INSERT、从而误触发 FK 级联删除的陷阱（改用 UPDATE）
> - 通过 **Retrofit** 封装非流式接口（`/api/chat`、`/api/upload` multipart、`/api/session/context`），集成 `ActivityResultContracts.GetContent` 文件选择器处理 Content URI → 临时文件拷贝与上传后清理；为 SSE 单独配置无读超时、仅 header 级别日志的 OkHttpClient，避免 BODY 日志缓冲整段响应体导致事件不回调
>
> **技术栈**：`Kotlin` `Jetpack Compose` `OkHttp / okhttp-sse` `Retrofit` `Room` `StateFlow` `Coroutines` `MVVM`

### 紧凑版（项目经历 7+ 条、空间紧张时）

> **LabSage — 实验室 AI 知识助手 Android 客户端** ｜ 独立开发 ｜ 2026.07
>
> 为实验室自研 AI 知识助手后端独立开发的 Android 原生客户端（Jetpack Compose + OkHttp + Room）。
>
> - 基于 **OkHttp okhttp-sse + callbackFlow** 实现 SSE 流式接收并桥接为 Kotlin `Flow`；针对工具查询的 200+ trace 事件洪峰，用「事件过滤 + `buffer(UNLIMITED)`」避免 Channel 溢出丢事件
> - **MVVM + StateFlow + sealed class**（`Idle→Streaming→Completed→Error`）状态机；借后端 `Continue + PartialAnswer` 实现流中断续传
> - **Room** 双表（FK CASCADE）+ 流式「首 chunk INSERT + 后续 UPDATE」+ `is_partial` 字段实现杀进程可恢复；修复 `OnConflictStrategy.REPLACE` 误触发 FK 级联删除
> - **Compose** 双策略滚动 + `.conflate()`/`@Immutable`/`derivedStateOf` 把重组频率从 ~50/s 降到 ~10/s；解析 SSE trace 实时可视化 ReAct 工具调用链路

### 一行版（技能总结 / 个人简介）

> 独立开发 AI 知识助手 Android 客户端（Kotlin + Jetpack Compose + OkHttp SSE + Room + MVVM），完成从 SSE 流式接收到 Compose UI 的完整链路，支持中断续传、ReAct 工具调用可视化、多会话管理与暗黑模式。

### bullet 代码出处（面试自证）

| Bullet | 关键证据 |
|---|---|
| SSE + callbackFlow + buffer | `data/remote/OkHttpSseDataSource.kt:38,62-98` |
| sealed class 状态机 + Continue 续传 | `domain/model/StreamState.kt:12`、`data/repository/ChatRepository.kt:74`、`ui/chat/ChatViewModel.kt:189` |
| 双策略滚动 + conflate / Immutable | `ui/chat/ChatScreen.kt:95-101`、`ui/chat/ChatViewModel.kt:125` |
| trace 解析 + 工具调用时间线 | `ui/chat/ChatViewModel.kt:398-467` |
| Room 双表 + REPLACE→UPDATE 修复 | `data/local/AppDatabase.kt:19`、`data/repository/ChatRepository.kt:185-198` |
| Retrofit 非流式接口 + 双 OkHttp Client | `data/remote/LabSageApi.kt:13-31`、`LabSageApplication.kt:88-141` |

---

## 面试常见追问（建议提前准备）

| 面试官可能的追问 | 回答要点 |
|---|---|
| "为什么不用 Hilt？" | 依赖图简单（`LabSageApplication` 约 10 个 `by lazy` 单例、~210 行），手动 DI 比注解处理器更透明，无 KSP/KAPT 编译开销 |
| "SSE 和 WebSocket 有什么区别？" | SSE 单向（服务端→客户端）、基于 HTTP、自动重连；WebSocket 全双工。聊天场景只需服务端推送，SSE 更轻量 |
| "callbackFlow 的背压策略？" | `trySend` vs `send`、Channel 容量（BUFFERED/CONFLATED/UNLIMITED）；本项目遇 `trySend` 在默认 64 容量下静默丢事件，最终用「过滤 + `buffer(UNLIMITED)` + Room 批量写」三级治理 |
| "为什么 REPLACE 会触发 CASCADE？" | SQLite REPLACE 算法 = DELETE + INSERT，DELETE 触发 FK `ON DELETE CASCADE`，不同于 MySQL 的 `ON DUPLICATE KEY UPDATE`；修复改用显式 `@Query("UPDATE")` |
| "Compose 重组优化的手段？" | `.conflate()` 消费端限流、`@Immutable` 跳过无关重组、`derivedStateOf` 派生状态、拆分滚动策略（新消息 animate / 内容更新 snap） |
| "怎么确保杀进程后消息不丢？" | 流式首 chunk INSERT 占位 + 每 5 chunk UPDATE + Done 时 `finalizeMessage`，`is_partial` 字段标记中断状态 |
| "Retrofit 为什么不处理 SSE？" | Retrofit 对 SSE 长连接无增值（无声明式收益），OkHttp `okhttp-sse` 原生 `EventSource` 更可靠；Retrofit 只负责短请求（chat / upload / session） |

---

## 技术栈速览

| 类别 | 技术 | 选择理由 |
|------|------|---------|
| 语言 | **Kotlin 2.2.10** | Android 官方首选 |
| UI | **Jetpack Compose + Material 3**（BOM 2025.06.01） | 声明式 UI，流式场景高性能重组 |
| 架构 | **MVVM + 手动 DI**（无 Hilt） | 无注解处理器编译开销，依赖透明 |
| 网络 | **OkHttp okhttp-sse + Retrofit** | SSE 长连接与短请求分离 |
| 存储 | **Room** + **DataStore 1.1.7** | Room 负责会话/消息（FK 级联 + Flow），DataStore 负责设置 |
| 状态 | **StateFlow + sealed class** | 类型安全状态机，配置变更安全 |
| Markdown | **jeziellago/compose-markdown 0.5.7** | 纯 Compose、兼容当前 BOM（mikepenz 系均因 `getCurrentCompositeKeyHashCode` 缺失而不兼容） |
| 构建 | **Gradle 9.4.1 + AGP 9.2.1** | — |
