# Android 客户端开发校招简历（主投版）

> 面向 Android 客户端开发校招岗位。方括号内容需要按个人真实信息替换；从“投递前检查”开始的内容不放进最终 PDF。

## [姓名]

**求职意向：Android 客户端开发工程师｜[2027 届/实际毕业年份]**

[手机号] ｜ [邮箱] ｜ [所在城市] ｜ [GitHub/Gitee] ｜ [技术博客，可选]

## 教育背景

**[学校名称]** ｜ [本科/硕士] ｜ [专业名称] ｜ [入学年月]—[毕业年月]

- GPA：[按实际填写]；排名：[按实际填写，可选]
- 核心课程：[数据结构与算法、操作系统、计算机网络、数据库、Java/Kotlin 等，仅保留真实课程]
- 荣誉奖项：[奖学金/竞赛/优秀学生等；没有则删除本行]

## 专业技能

- **Kotlin**：掌握协程、Flow/StateFlow、sealed class、高阶函数、属性委托等特性，能够处理异步任务、状态建模与生命周期协作。
- **Android**：能够使用 Jetpack Compose、Material 3、ViewModel、Navigation、Room、DataStore 完成原生应用开发；具备通知、运行时权限、语音识别、文件选择等系统能力接入经验。
- **网络与存储**：使用 OkHttp/okhttp-sse、Retrofit、Gson 对接 SSE 与 REST API，理解 `callbackFlow` 回调桥接、Channel 背压、请求取消、Room 外键与响应式查询。
- **架构与工程**：具备 MVVM、Repository、手动依赖注入实践；使用 Git、Gradle、JUnit、Android Lint，能够定位并修复并发竞态、依赖兼容和 SQLite 数据一致性问题。
- **Android 原理**：理解主线程 Looper/MessageQueue、Handler 与 `Dispatchers.Main` 的关系，能够区分挂起与阻塞并合理选择线程调度策略。

## 项目经历

### LabSage｜实验室 AI 知识助手 Android 客户端

**独立负责 Android 客户端** ｜ 2026.07—至今 ｜ [项目链接/演示视频]

面向实验室知识检索与环境查询场景，对接现有 Go AI 后端，独立完成从 SSE/REST 网络层、Room 本地持久化到 Compose UI 的端到端 Android 客户端；支持流式问答、中断续传、工具调用可视化、多会话管理与回答完成通知。

- 采用 **MVVM + Repository + 手动 DI** 组织代码，以 `StateFlow` 和 sealed class 建模 `Idle/Streaming/Completed/Error` 流式状态，使网络事件、持久化数据与 Compose UI 保持单向数据流。
- 基于 **OkHttp okhttp-sse + callbackFlow + awaitClose** 将 `EventSourceListener` 回调桥接为冷 Flow，协程取消时主动断开长连接；针对工具查询产生的 200+ trace/message 事件，先按结构化前缀过滤噪音，再以 `buffer(Channel.UNLIMITED)` 兜底，解决默认 Channel 容量下 `trySend` 丢失 message/done 的问题。
- 使用 **Room** 设计会话/消息双表及外键级联，采用“助手消息占位 INSERT + 流式 UPDATE + 完成时 finalize”持久化增量内容，并通过 `is_partial` 支持中断后的 Continue/Regenerate；定位 SQLite `REPLACE = DELETE + INSERT` 误触发 FK CASCADE 的数据丢失问题，改为 `UPDATE + INSERT(IGNORE)`。
- 使用 **Jetpack Compose + Material 3** 构建自适应聊天界面，实现 Markdown 渲染、浅/深色主题、ReAct 工具调用时间线、长回答自适应宽度，以及“底部锚点 + 用户滚动意图”策略，避免流式更新在用户回看历史时强制抢滚动。
- 完成 Android 系统能力闭环：通过 **NotificationChannel + NotificationCompat + PendingIntent** 支持回答完成通知，携带 `conversationId + messageId` 覆盖冷启动 `onCreate` 与热启动 `onNewIntent`，进入会话后等待 Room Flow 数据并精确定位目标消息；同时接入 `SpeechRecognizer`、运行时权限和 Content URI 文件上传。
- 将后端 string-encoded Facts JSON 解析为稳定的 **K–V UI 模型**，兼容包装对象、直接对象与嵌套 JSON，支持单项确认删除并通过现有 PUT 接口回写；为 JSON 解析/删除及通知摘要编写 **8 个 JUnit 测试**，完成 Gradle 单测、Debug APK 构建与 Android Lint 检查。

**技术栈：** Kotlin、Jetpack Compose、Material 3、Coroutines、Flow/StateFlow、OkHttp SSE、Retrofit、Room、DataStore、Navigation Compose、JUnit

## 个人总结

- 能够独立完成 Android 功能从需求拆解、数据契约、架构设计到实机回归的完整闭环，重视异常路径、生命周期与数据安全。
- 不止停留在 API 使用层，能够从协程调度、Flow 背压、Compose 重组、Looper/MessageQueue 和 SQLite 冲突策略解释代码设计与 Bug 根因。
- 持续记录技术选型和问题复盘，能够围绕真实项目清晰说明“遇到了什么问题、为什么发生、如何验证和取舍”。

---

# 项目经历压缩版（简历空间不足时替换）

### LabSage｜实验室 AI 知识助手 Android 客户端

**独立负责 Android 客户端** ｜ 2026.07—至今

- 基于 **MVVM + StateFlow + sealed class** 搭建 Compose 客户端；使用 **OkHttp SSE + callbackFlow/awaitClose** 接入流式问答，以事件过滤和 Channel 缓冲解决 200+ 事件下的消息丢失问题。
- 使用 **Room** 双表与增量 UPDATE 持久化流式内容，支持中断续传；定位并修复 SQLite `REPLACE` 触发 FK CASCADE、导致历史消息被级联删除的问题。
- 实现 Markdown 对话、ReAct 工具时间线、自适应长回答和用户意图感知滚动；通过通知 `PendingIntent` 覆盖冷/热启动并按 `messageId` 精确定位回答。
- 将后端 Facts JSON 转换为可管理的 K–V UI，完成单项删除回写；编写 **8 个 JUnit 测试**并通过 Debug 构建与 Android Lint。

**技术栈：** Kotlin、Compose、Coroutines/Flow、OkHttp/Retrofit、Room、DataStore、Navigation

---

# 30 秒自我介绍

> 面试官您好，我是 [姓名]，[学校][专业]的 [毕业年份] 届学生，应聘 Android 客户端开发。我的核心项目是 LabSage，一个使用 Kotlin 和 Jetpack Compose 独立开发的 AI 知识助手客户端。我完成了 OkHttp SSE 到 Flow 的流式链路、Room 消息持久化、Compose 对话 UI、通知精确跳转等功能，也实际解决过 Channel 背压丢事件和 SQLite REPLACE 触发级联删除的问题。相比只会调用 API，我更关注线程、生命周期和数据流为什么这样设计，希望能在 Android 客户端岗位继续深入工程实践。

---

# 投递前检查（最终 PDF 删除本节）

## 必须补齐

- 姓名、手机号、邮箱、城市、毕业年份。
- 学校、专业、学历、时间；GPA/排名不占优时可以不写。
- 项目仓库或 1—2 分钟演示视频链接。若仓库包含后端或隐私配置，先清理后公开。
- 若有实习、竞赛、奖项，应放在项目经历之前；没有则不要虚构。

## 排版建议

- 控制在 **一页 A4**；正文建议 10.5—11 pt，页边距 1.5—1.8 cm。
- 项目保留 5—6 条 bullet，每条最好不超过两行；使用黑色正文和单一强调色。
- 文件名使用 `姓名-学校-Android客户端开发-毕业年份.pdf`，不要使用“最终版2”“最新版”等名称。
- 技术名词加粗即可，不添加技能进度条、星级评分、头像或大面积图标。

## 真实性边界

- Android 客户端可以写“独立负责”；Go + Milvus + Agent 是对接的后端技术，不写成自己独立开发，除非确实参与且能展示对应代码。
- 项目使用的是**手动 DI**，不要写 Hilt/Dagger。
- 生产 SSE 协议解析由 `okhttp-sse` 完成；`SseParser.kt` 是学习/备选实现，不写“自研 SSE 解析器用于生产”。
- 不写未经 Macrobenchmark、Profiler 或埋点验证的 FPS、耗时、重组下降比例；用已复现的事件数量、测试数量和具体 Bug 代替。
- Facts 删除当前是客户端 read-modify-write 后整体 PUT；若被追问并发一致性，应说明多端场景更适合后端提供按 key 的原子 PATCH/DELETE 与 version/ETag。

## 技术面重点准备

1. `callbackFlow` 为什么适配回调 API，`awaitClose` 如何保证取消清理。
2. `trySend` 为什么会失败，BUFFERED/CONFLATED/UNLIMITED 的取舍。
3. Room Flow 如何通过表失效通知驱动 UI，为什么流式写入要节流。
4. SQLite `OnConflictStrategy.REPLACE` 为什么会触发外键级联。
5. Compose 如何登记 State 读取依赖，底部锚点和用户滚动意图如何避免抢滚动。
6. 通知点击如何覆盖冷启动/热启动，为什么要同时处理 `onCreate` 和 `onNewIntent`。
7. `Dispatchers.Main`、Handler、Looper/MessageQueue 之间的关系。
