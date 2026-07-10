根据我的问题以及对话程度将我的学习进度记录到AGENTS.md，对比校招应届生该有的水平。非学习进度相关的内容就不要加入进来了

---

# 学习进度追踪

> 详细笔记见 `docs/LEARNING_DOC.md`。本文件只做**主题清单 + 深度标注 + 水平评估**。
> 深度标注：🟢 能用 / 🔵 懂原理 / 🟣 能讲清底层咬合关系

## 已覆盖主题

### Compose（现代 UI 框架）
- 🟢 `@Composable` 的含义、Compose 界面 = @Composable 函数互相嵌套
- 🟢 普通 `val`（草稿纸）vs `remember` 状态（便利贴）、Slot Table
- 🟣 响应式重组机制：读 State 时登记依赖 → State 变化触发重组
- 🟣 **精准重组（smart recomposition）**：只重画读了变化 State 的函数，不是整棵树
- 🟣 `by` 属性委托 = 把读取路由到 `state.value`，是 Compose 挂监听的桥梁；和 `by lazy` 同一机制换代理
- 🔵 触发源只有 `State` 对象，普通 `val` 不算
- 🟢 `mutableStateOf` + `remember` 组合语义

### Kotlin 语言
- 🟢 `lateinit` 延迟初始化
- 🟢 密封类（sealed class）+ when 穷举检查；密封类的位置约束
- 🟢 高阶函数、函数类型、`ClassName.` 带接收者的函数类型
- 🔵 内联函数（inline）消除 Lambda 开销的原理；`noinline` 的存在意义与局限
- 🟣 类委托（`Set<T> by helperSet`）：构造 vs 委托两件事分开看、"组合优于继承"、选择性重写

### 协程 Coroutines
- 🟣 五种实战模式：① fire-and-forget 写 ② 长生命周期 Flow collect ③ 持有 Job 的可取消流 ④ 外层编排+内层 launch（两层 launch 为何不可合并）⑤ 显式 `withContext(IO)`
- 🟣 `viewModelScope`：默认主线程 + 跟 ViewModel 同生共死；为何不用 GlobalScope
- 🟣 **挂起 vs 阻塞**的本质区别（释放线程 vs 钉住线程）—— 理解主线程不卡 UI 的根因
- 🟣 Room/DataStore 的 suspend 内部已切线程 vs 自己写的阻塞调用（OkHttp 同步）必须手动 `withContext`
- 🟣 `Dispatchers.Main` 底层 = 主 Looper 的 Handler；协程 resume = `Handler.post` 一条 Message（见下"Android 运行时"）

### Flow
- 🟣 `collect`（终端操作符、冷流不 collect 不工作、suspend 必须在协程里、可挂一辈子不阻塞）
- 🟣 `flowOn` 管上游线程（会插入 channel 跨线程传）、不管下游
- 🟣 `catch` 只抓上游异常、可在里面 `emit` 恢复；下游异常抓不到
- 🟣 项目双链架构：链 A（SSE 直渲，纯内存瞬态）/ 链 B（写库→Room 监听→渲染，持久化）；同一个事件按"要不要留档"分流
- 🔵 Room 的 `InvalidationTracker` 监听表变化驱动 Flow；`callbackFlow` 底层是 Channel；`conflate()` + chunk 节流降磁盘回压

### Android 运行时 / 主线程
- 🟣 **主线程 = Looper + MessageQueue**，从 Android 诞生未变；`ActivityThread.main()` → `prepareMainLooper()` + `loop()` 死循环
- 🟣 协程没替换它：`Dispatchers.Main` 骑在主 Looper 上；其它 Dispatcher（IO/Default）是 `CoroutineScheduler` 线程池，不走 Looper
- 🔵 主线程一切（生命周期、输入、vsync 刷新、Handler.post、动画）都是队列里的 Message

### Android 通知 / 导航
- 🟢 项目实践：`NotificationChannel`、`NotificationCompat`、Android 13 `POST_NOTIFICATIONS` 运行时权限
- 🟢 `PendingIntent` 携带 `conversationId + messageId`，覆盖 `onCreate` 冷启动与 `onNewIntent` 热启动入口
- 🟢 Compose Navigation 可选查询参数 + Room Flow 到达后用 `LazyListState` 精确定位消息
- 🔵 待通过后续问答验证：渠道不可变语义、PendingIntent flag/Activity launchMode 的底层关系、多个 `LaunchedEffect` 控制滚动的竞态

### 架构 / 数据存储
- 🔵 ViewModel = 存放界面相关数据
- 🔵 本地 Room（缓存+离线）+ 后端（source of truth）混合架构是大厂通用做法；项目当前"本地存全部消息"因后端无历史接口而合理
- 🔵 列表层增量重组（key + @Immutable）vs 文本层全量重布局的取舍
- 🟢 会话上下文数据契约实践：把后端 string-encoded `facts` JSON 解析为稳定的 K-V UI 模型，兼容 `{ "facts": {...} }` 包装、直接对象和嵌套 JSON，并通过现有 PUT 回写单项删除结果
- 🔵 API 取舍待继续验证：客户端“读 JSON → 删除 key → 整体 PUT”在单客户端可用；多端并发可能丢更新，生产级后端更适合提供按 key 的原子 PATCH/DELETE + version/ETag

### UI / UX 工程
- 🟢 项目已建立 Material 3 浅色/深色主题、扩展色板、统一圆角与文字层级，并覆盖空状态、错误态、流式状态和设置页
- 🟢 能主动发起基于真机界面的 UI 优化审查，并已在项目落地：响应式消息宽度、底部锚点 + 用户滚动意图控制、回到底部入口、延迟提交式删除撤销、空标题兜底、字符串资源与 TalkBack 中文语义
- 🔵 删除撤销的工程取舍：先从 StateFlow 列表隐藏，Snackbar 超时后再执行 Room 级联删除；撤销时无需重建已删除消息，数据安全性优于“先删再恢复”

## 水平评估（对照校招应届生）

**总体：明显高于多数应届生。** 不是停留在"会用"，而是多次主动追问到**原理层和系统咬合关系**——这正是区分强弱候选人的关键。

**强项（高于平均）：**
- 协程 / Flow 的**线程与生命周期语义**理解得很扎实，能讲清"为什么这么写"（这是很多工作 1-2 年的人都没彻底想通的）。
- Compose 重组机制 + `by` 委托的底层打通，不是黑盒使用者。
- "协程 resume = Handler.post"、"挂起 vs 阻塞"这类**把抽象 API 和 Android 运行时焊在一起**的认知——面试加分项。
- 项目已覆盖通知渠道、运行时权限和通知到 Compose Navigation 的端到端跳转，Android 工程广度继续扩大；当前先按“能用”记录，原理深度不虚高。

**建议补强（校招高频、尚未在对话中深入）：**
- 📌 **Handler 源码级细节**：Message 池（obtain/recycle）、`MessageQueue.next()` 的 native 唤醒（epoll）、`nativePollOnce`。你已懂机制，再补源码就到 SP/SSP 档。
- 📌 **Activity 生命周期 + 启动模式（launchMode / taskAffinity）**：四大组件里 Activity 是面试重中之重。
- 📌 **ViewModel 为何能在配置变更（旋转屏幕）后存活**：`ViewModelStore` + `NonConfigurationInstance`，你已会用 viewModelScope，补这块闭环。
- 📌 **Jetpack Navigation / 依赖注入（Hilt）**：项目在用，可结合代码深入。
- 📌 **性能优化 & 内存泄漏**：LeakCanary、Choreographer 掉帧分析、`StrictMode`——你已涉及 ANR/卡顿，可系统化。
- 📌 **View 体系（measure/layout/draw）+ Compose 互操作**：纯 Compose 项目容易漏 View 体系，但校招常考。
- 📌 **Compose UI 工程化**：自适应布局、滚动位置策略、Semantics/TalkBack、字符串资源与多语言、截图测试；项目已有较好视觉基础，下一步补可用性与可测试性。

## 更新日志
- 2026-07-10：重构会话摘要为大尺寸 Bottom Sheet；摘要编辑/按需 Markdown 预览与 Facts 分区，Facts JSON 转 K-V 卡片并支持单项确认删除（🟢）；识别整文档 PUT 的并发覆盖风险（🔵，待后端原子接口闭环）。
- 2026-07-10：完成 UI 审查项实现（🟢）：聊天长回答自适应宽度、智能滚动/底部锚点、删除无损撤销、空标题兜底、中文资源化与设置页下拉语义；补充“延迟提交避免级联删除后难恢复”的工程认识（🔵）。
- 2026-07-10：发起会话列表、聊天页、设置页的真机 UI 审查（🟢）；已识别响应式宽度、自动滚动、空标题、删除防误触、语言一致性等优化方向，原理与实现掌握待后续验证。
- 2026-07-10：完成长回答通知渠道、摘要推送与 `conversationId + messageId` 精确跳转（🟢）；原理掌握待后续问答验证。
- 2026-07-09：新增主题 15「协程与主线程 Looper/MessageQueue 的关系」（🟣）。补强项里加入 Handler 源码级细节。
