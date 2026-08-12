# LabSage 项目简历文案（代码核验版）

> 适用于 Android 客户端开发岗位。内容以当前 Android 代码、自动化测试和项目内基准结果为依据；项目时间、个人身份和链接请按实际情况补充。

## 主投版

### LabSage｜实验室 AI 知识助手 Android 客户端

**独立负责 Android 客户端开发** ｜ [项目时间] ｜ [项目链接/演示视频]

面向实验室知识检索与环境查询场景，对接现有 Go AI 后端，独立完成原生 Android 客户端；支持 AI 流式问答、异常续答、工具调用展示、多会话与知识库文件管理、账号认证及跨端历史同步。

- 使用 **Kotlin + Jetpack Compose** 搭建客户端，按 **MVVM + Repository + 手动依赖注入** 组织网络、Room 与 UI；通过 `StateFlow` 和密封类统一管理流式状态，完成 Markdown 回答、语音输入、工具调用时间线、主题切换等功能。
- 优化 **SSE 流式回答链路**：消息先在内存累积，最多每 50 ms 更新一次界面、每 500 ms 写入一次 Room，结束或异常时强制保存最终内容；同时合并内存与数据库状态，避免旧数据回流造成回答短暂倒退。真实 Room 基准中，数据库 UPDATE 由 201 次降至 20 次（-90.05%），本地持久化与会话快照中位耗时由 176.13 ms 降至 18.42 ms（-89.54%），最终 32,000 字回答完全一致。
- 将 SSE 事件队列由无限缓冲改为固定 **512** 容量，并检查事件写入结果；消费端跟不上时主动断开并进入可重试错误流程，避免事件持续堆积或静默丢失结束状态。
- 重构 **Markdown 上传链路**，取消“临时文件 + 全量读入内存”，改为从系统文件 URI 直接流式写入 OkHttp；文件大小未知时使用 10 MiB 受限临时文件兜底，并保证取消或异常后清理。Android A/B 基准中，10 MiB 文件峰值 Java 堆增量由 22.98 MiB 降至 208 KiB（-99.1%），临时磁盘占用降为 0，客户端文件准备与写出耗时降低 58.3%，内容经长度和 SHA-256 校验一致。
- 完成 **认证与历史同步**：使用 Android Keystore + AES-GCM 加密登录态，处理 Token 自动刷新与并发更新；以账号和后端地址隔离 Room 数据，通过持久化游标完成全量/增量同步、删除标记合并及旧版历史分批迁移，防止切换账号或服务端时数据串用。
- 完善异常恢复与系统体验：将流式回答分阶段写入 Room，进程重启后可从残缺回答继续生成或重新生成；回答完成通知覆盖冷、热启动并按消息 ID 精确定位，同时实现会话搜索/置顶/删除、用户滚动意图感知和离线/同步状态提示。

**技术栈：** Kotlin、Jetpack Compose、Material 3、Coroutines、Flow/StateFlow、OkHttp SSE、Retrofit、Room、DataStore、Android Keystore、Navigation Compose、JUnit、Android Instrumentation

## 一页简历压缩版

### LabSage｜实验室 AI 知识助手 Android 客户端

**独立负责 Android 客户端开发** ｜ [项目时间]

- 基于 **Kotlin、Compose、MVVM、StateFlow、Room** 完成 AI 知识助手客户端，对接 SSE/REST 接口，实现流式问答、异常续答、工具调用展示、多会话及知识库文件管理。
- 将 SSE 正文更新拆分为“50 ms 界面快照 + 500 ms Room 保存”，并以 512 容量队列控制积压；真实 Room 基准中 UPDATE 次数降低 **90.05%**，本地处理耗时中位数降低 **89.54%**，32,000 字最终正文一致。
- 将 Markdown 上传由“临时文件 + 全量入堆”改为 OkHttp 流式传输；10 MiB 文件峰值 Java 堆增量由 **22.98 MiB 降至 208 KiB（-99.1%）**，临时磁盘占用降为 0，客户端文件处理耗时降低 **58.3%**。
- 使用 **Android Keystore + AES-GCM** 保护登录态，完成 Token 自动刷新、账号/后端数据隔离及游标增量同步；结合 Room checkpoint 支持进程重启后的残缺回答恢复与通知精确跳转。

**技术栈：** Kotlin、Compose、Coroutines/Flow、OkHttp/Retrofit、Room、DataStore、Android Keystore、Navigation

## 30 秒项目介绍

我的核心项目是 LabSage，一个面向实验室知识检索的原生 Android AI 助手。我独立负责 Android 客户端，使用 Kotlin、Jetpack Compose、OkHttp SSE 和 Room 完成流式问答、异常恢复、账号认证及历史同步。项目中我重点优化了两条高频链路：一是将 SSE 的界面更新和数据库写入分开，使 Room 更新次数降低约 90%；二是将 Markdown 上传改为流式传输，使 10 MiB 文件的峰值 Java 堆增量降低 99.1%。这些结果都通过 Android 真实数据库或 ART 环境下的 A/B 基准验证，而不是估算值。

## 面试表述边界

- 可以写“独立负责 Android 客户端”；Go 后端、Milvus 和 Agent 能力是客户端对接对象，除非确实参与开发，否则不要写成个人实现。
- SSE 的 89.54% 是“Room 持久化 + 全会话快照”耗时下降，不代表服务端生成速度、首字耗时或页面帧率提升。
- 上传的 58.3% 是“客户端文件准备 + RequestBody 写出”耗时下降，不包含网络传输、后端解析、Embedding 或入库时间。
- “最多每 50 ms 更新一次界面”应表述为约 20 次 UI 快照/秒，不写成 20 FPS。
- 项目使用手动依赖注入，不写 Hilt/Dagger；生产 SSE 由 `okhttp-sse` 解析，不写“自研 SSE 协议解析器”。
