### LabSage｜实验室 AI 知识助手 Android 客户端

面向实验室知识检索与环境查询场景，对接现有 Go AI 后端，独立完成原生 Android 客户端；支持 AI 流式问答、异常续答、工具调用展示、多会话与知识库文件管理、账号认证及跨端历史同步。

- 使用 **Kotlin + Jetpack Compose** 搭建客户端，按 **MVVM + Repository + 手动依赖注入** 组织网络、Room 与 UI；通过 `StateFlow` 和密封类统一管理流式状态，完成 Markdown 回答、语音输入、工具调用时间线、主题切换等功能。
- 优化 **SSE 流式回答链路**：将高频界面更新与 Room 持久化解耦，消息在内存累积后每 50 ms 更新界面、每 500 ms 保存一次，结束或异常时强制落盘；将无限事件队列改为固定 512 容量，积压时主动中断并进入重试流程。真实 Room 基准中，数据库 UPDATE 由 201 次降至 20 次（-90.05%），本地处理耗时中位数由 176.13 ms 降至 18.42 ms（-89.54%），最终 32,000 字回答保持一致。
- （就这个不大确定，其它的再小改）重构 **Markdown 上传链路**，取消“临时文件 + 全量读入内存”，改为从系统文件 URI 直接流式写入 OkHttp；文件大小未知时使用 10 MiB 受限临时文件兜底，并保证取消或异常后清理。Android A/B 基准中，10 MiB 文件峰值 Java 堆增量由 22.98 MiB 降至 208 KiB（-99.1%），临时磁盘占用降为 0，客户端文件准备与写出耗时降低 58.3%，内容经长度和 SHA-256 校验一致。
- 完成 **认证与历史同步**：使用 Android Keystore + AES-GCM 加密登录态，处理 Token 自动刷新与并发更新；以账号隔离 Room 数据，通过持久化游标完成全量/增量同步、删除标记合并及旧版历史分批迁移，防止切换账号或服务端时数据串用。
- 完善异常恢复与系统体验：进程重启后可从残缺回答继续生成或重新生成；回答完成通知覆盖冷、热启动并按消息 ID 精确定位，同时实现用户滚动意图感知和同步状态提示、会话搜索/置顶/删除。

**技术栈：** Kotlin、Jetpack Compose、Material 3、Coroutines、Flow/StateFlow、OkHttp SSE、Retrofit、Room、DataStore、Android Keystore、Navigation Compose、JUnit、Android Instrumentation