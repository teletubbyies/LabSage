# LabSage — AI 实验室知识助手 Android 客户端

> 以下按 **STAR 法则** 撰写，适用于应届校招简历中的项目经历描述。关键词已加粗，面试时可据此展开。

---

## 项目概述（一句话）

独立开发的一款基于 **Jetpack Compose** 的 **AI 聊天机器人** Android 客户端，对接 Go 后端 **SSE 流式 API**，支持 ReAct Agent 工具调用可视化、Markdown 渲染、多会话管理与暗黑模式。

---

## STAR 描述

### S — Situation（背景）

后端团队基于 **GoFrame + Milvus 向量数据库 + ReAct Agent** 构建了一个面向实验室运维场景的 AI 知识助手，已提供 **RESTful + SSE（Server-Sent Events）流式 API**，但缺少 Android 端客户端。需要在 **2 周** 内从零交付一个功能完整、UI 美观、架构规范的 Android App 作为团队对外演示产品。

### T — Task（任务）

1. **从零搭建** Android 项目，独立完成技术选型、架构设计、全部编码与前后端联调
2. 实现 **SSE 流式对话**（含断线续传）、**Markdown 渲染**、**ReAct Agent 执行过程实时展示**、文件上传、多会话管理等核心功能
3. 确保流式场景下的 **UI 流畅度和数据可靠性**（消息不丢、杀进程可恢复）
4. 编写**面试级技术文档**，覆盖架构设计、Bug 根因分析和选型决策

### A — Action（行动）

**架构设计：**
- 采用 **MVVM + Repository + 手动 DI** 架构，拒绝 Hilt/Dagger 等重型框架，依赖图仅 ~5 个单例，**透明可调试**
- 基于 **StateFlow + sealed class** 构建流式对话状态机（Idle → Streaming → Completed/Error），确保状态穷举、配置变更安全
- 使用 **OkHttp `okhttp-sse` + `callbackFlow`** 而非 Retrofit CallAdapter 实现 SSE 流式解析，理由：Retrofit 对 SSE 无增值，OkHttp 原生 SSE 库更可靠

**SSE 流式核心（简历技术亮点）：**
- 自定义 **`SseParser`** 纯函数行解析器（零依赖、可单元测试），处理 SSE 协议边界（event/data 字段分离、空白行事件派发、EOF 残留数据刷新）
- 通过 **`callbackFlow`** 将 OkHttp `EventSourceListener` 回调桥接为 Kotlin `Flow<SseEvent>`，利用 `awaitClose` 实现 Flow 取消时自动断开 SSE 连接
- 针对 **trace 事件洪峰**（工具查询产生 200+ trace 事件溢出 Channel 默认 64 容量）设计三级优化：**事件过滤**（仅保留工具生命周期线）→ **`buffer(Channel.UNLIMITED)`** 兜底 → **Room 批量写入**（每 5 chunk 一次）

**数据持久化：**
- 使用 **Room** 存储会话和消息，设计 `conversation` + `message` 双表，通过 `ForeignKey(CASCADE)` 保证引用完整性
- 流式内容采用 **INSERT（首 chunk）+ UPDATE（后续 chunk）** 模式，确保进程被 kill 后重启可通过 `is_partial` 字段恢复中断消息
- 发现并修复 **`OnConflictStrategy.REPLACE` 触发 FK CASCADE 级联删除** 的严重 Bug（SQLite REPLACE = DELETE + INSERT，非 MySQL 语义），从 SQLite 源码级别分析根因

**UI 与交互：**
- 基于 **Jetpack Compose Material 3** 实现声明式 UI，**LazyColumn** 渲染消息列表
- 自研 **ReAct Agent 执行过程内联展示**：解析 SSE trace 事件提取工具调用摘要（"Calling xxx..." / "Tool completed"），在 assistant 头像旁以 **内联 Chip** 形式实时更新，正式输出后自动隐藏——避免传统顶部面板导致的页面跳动和视线切换
- 实现 **Markdown 渲染**（jeziellago 库，支持代码块/表格/链接）、**暗黑模式**（`isSystemInDarkTheme()` 自动切换 + 自定义 ExtendedColors CompositionLocal）、**网络状态监控**（ConnectivityManager.NetworkCallback 断网横幅）、长按消息 **复制/分享/重新编辑**
- 流式空消息期间渲染 **"Thinking" 动画**（三点渐变透明度），区分"等待工具调用"与"卡死"

**性能优化：**
- 四层链路优化：**`.conflate()`** 丢弃 Room Flow 中间态（重组 ~50/s → ~10/s）→ 拆分滚动策略（新消息 animate、内容更新 snap）→ **`@Immutable`** 标注 data class + **`derivedStateOf`** 缩小重组范围 → `flowOn(Dispatchers.IO)` 保持主线程不阻塞

**工程质量：**
- 全程 Git 版本控制，累计 **12 次 Bug 修复**全部记录根因分析和选型决策
- 编写 **300+ 行架构文档** + **380+ 行核心知识点文档**（含面试话术），覆盖 SSE 背压、Room FK CASCADE、Compose 性能优化等面试高频考点
- 为支持会话摘要编辑功能，**反向推动后端新增 `GET/PUT /api/session/context` 接口**

### R — Result（成果）

- **2 周**内独立交付 **2200+ 行 Kotlin 代码**（27 个源文件），构建通过、APK 在模拟器端到端跑通
- SSE 流式对话延迟 < 100ms（首 chunk 到达），消息送达率 **100%**（修复 trySend 丢事件 Bug 后）
- 流式场景 UI 重组频率从 ~50/s 降至 ~10/s，LazyColumn 滚动无抖动
- 杀进程重启后历史消息和中断消息**完整恢复**
- 项目文档可直接作为**面试作品集**使用，覆盖 12 个技术深度的 Bug 案例

---

## 简历建议版本

根据简历空间，可选择以下三个版本：

### 版本 A：详细版（适用于项目经历 3-4 条时）

> **LabSage 实验室 AI 助手 Android 客户端** | 独立开发 | 2026.06 – 2026.07
>
> 为实验室自研 AI 知识助手后端（Go + Milvus + ReAct Agent + SSE 流式）独立开发的 Android 原生客户端，完成从 API 对接到 UI 渲染的完整链路。
>
> - 基于 **OkHttp `okhttp-sse`** 实现 SSE 流式消息接收，处理事件边界解析、连接中断恢复和流式 chunk 的增量持久化；通过后端 `Continue + PartialAnswer` 机制支持流中断后的断线续传
> - 使用 **Jetpack Compose** 构建对话界面，自定义 `LazyColumn` 滚动策略（新消息到达时动画滚底、流式更新时快照滚底）；流式期间通过 `callbackFlow` 将 SSE 回调桥接为 Kotlin `Flow`，配合 `.conflate()` 控制重组频率
> - 基于 **Room** 实现对话历史本地存储，设计 `conversation` + `message` 双表，`ForeignKey(CASCADE)` 保证引用完整性；流式消息采用首 chunk INSERT + 后续 UPDATE 的写入策略，杀进程后通过 `is_partial` 字段恢复中断消息
> - 采用 **MVVM + StateFlow** 架构，流式状态建模为 `sealed class`（`Idle → Streaming → Completed → Error`），View 层仅观察单一状态源，屏幕旋转等配置变更下状态不丢失
> - 集成 **`ConnectivityManager.NetworkCallback`** 监听网络状态，断网时顶部横幅提示；基于 Material 3 `darkColorScheme` + `isSystemInDarkTheme()` 实现暗黑模式跟随系统自动切换
> - 解析 SSE `trace` 事件提取 ReAct Agent 工具调用摘要，在助手气泡旁以内联 Chip 实时展示（"Calling xxx..." / "Tool completed"），正式输出后自动隐藏
> - 实现长按消息复制/分享/重编辑、Markdown 渲染（`jeziellago/compose-markdown`）、文件上传至知识库（`ActivityResultContracts.GetContent`）、会话摘要查看与编辑

### 版本 B：紧凑版（适用于项目经历 5-6 条时）

> **LabSage 实验室 AI 助手 Android 客户端** | 独立开发
>
> 为实验室自研 AI 知识助手后端独立开发的 Android 原生客户端，基于 Jetpack Compose + OkHttp + Room。
>
> - 基于 **OkHttp `okhttp-sse`** 实现 SSE 流式消息接收与事件解析，通过 `callbackFlow` 桥接为 Kotlin `Flow`，支持流中断后的断线续传
> - 使用 **Jetpack Compose** 构建对话界面，自定义 `LazyColumn` 滚动策略适配流式更新场景，配合 `.conflate()` 控制重组频率
> - 基于 **Room** 实现对话历史存储，双表 FK CASCADE 设计，流式消息首 chunk INSERT + 后续 UPDATE 保证杀进程可恢复
> - 采用 **MVVM + StateFlow + sealed class** 状态机，View 层单一状态源，配置变更安全
> - 实现暗黑模式系统自动切换、断网横幅提示、Markdown 渲染、Agent 工具调用状态内联展示、消息复制/分享/重编辑

### 版本 C：一行版（适用于技能总结或个人简介）

> 独立开发 AI 聊天机器人 Android 客户端（Jetpack Compose + OkHttp SSE + Room + MVVM），完成从 SSE 流式接收到 Compose UI 渲染的完整链路，支持断线续传、多会话管理、暗黑模式、Markdown 渲染与 Agent 执行状态实时展示。

---

## 面试常见追问（建议提前准备）

| 面试官可能的追问 | 回答要点 |
|---|---|
| "为什么不用 Hilt？" | 依赖图简单（~5 个单例），手动 DI 150 行代码比 Hilt 注解处理器更透明，无编译时开销 |
| "SSE 和 WebSocket 有什么区别？" | SSE 单向（服务端→客户端）、基于 HTTP、自动重连；WebSocket 全双工。聊天场景只需服务端推送，SSE 更轻量 |
| "callbackFlow 的背压策略？" | `trySend` vs `send`、Channel 容量（BUFFERED/CONFLATED/UNLIMITED）、我们项目的三级优化方案 |
| "为什么 REPLACE 会触发 CASCADE？" | SQLite REPLACE 算法 = DELETE + INSERT，DELETE 触发 FK ON DELETE CASCADE。不同于 MySQL 的 ON DUPLICATE KEY UPDATE |
| "Compose 重组优化的手段？" | `.conflate()` 消费端限流、`@Immutable` 跳过无关重组、`derivedStateOf` 派生状态、拆分滚动策略 |
| "怎么确保杀进程后消息不丢？" | 流式首 chunk INSERT 占位 + 每 5 chunk UPDATE + Done 时 finalize，`is_partial` 字段标记中断状态 |

---

## 技术栈速览

| 类别 | 技术 | 选择理由 |
|------|------|---------|
| 语言 | **Kotlin** | Android 官方首选 |
| UI | **Jetpack Compose + Material 3** | 声明式 UI，流式场景高性能重组 |
| 架构 | **MVVM + 手动 DI** | 无 Hilt 编译开销，依赖透明 |
| 网络 | **OkHttp SSE + Retrofit** | SSE 长连接与短请求分离 |
| 存储 | **Room** | FK 级联 + Flow 响应式观察 |
| 状态 | **StateFlow + sealed class** | 类型安全状态机，配置变更安全 |
| 构建 | **Gradle 9.4.1 + AGP 9.2.1 + Kotlin 2.2.10** | 全链路最新稳定版本 |
