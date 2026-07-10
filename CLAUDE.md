# 根据我的问题以及对话程度将我的学习进度记录到CLAUDE.md，对比校招应届生该有的水平。非学习进度相关的内容就不要加入进来了

## 每完成一个代码变动任务，commit；如果是bugfix任务记录到docs/BUGFIX_LOG.md中，如果是功能增加记录到docs/FEATURES.md中


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
- 🟣 嵌套 launch 的"顺序保证"：内层用 `viewModelScope.launch`（显式 scope）→ 内/外层是**兄弟**非父子，外层结束带不走内层；内层写在外层代码体**末尾**，用执行顺序强制"建会话/外键约束/重置累加器"完成后才开流——写外面会竞态（外层挂起在首个 suspend 点时内层就启动，撞脏状态/未建会话）

### Flow
- 🟣 `collect`（终端操作符、冷流不 collect 不工作、suspend 必须在协程里、可挂一辈子不阻塞）
- 🟣 `flowOn` 管上游线程（会插入 channel 跨线程传）、不管下游
- 🟣 `catch` 只抓上游异常、可在里面 `emit` 恢复；下游异常抓不到
- 🟣 项目双链架构：链 A（SSE 直渲，纯内存瞬态）/ 链 B（写库→Room 监听→渲染，持久化）；同一个事件按"要不要留档"分流
- 🔵 Room 的 `InvalidationTracker` 监听表变化驱动 Flow；`callbackFlow` 底层是 Channel；`conflate()` + chunk 节流降磁盘回压
- 🟣 `callbackFlow` = 把"回调式 push"桥接成"Flow 式 pull"：回调里 `trySend` 塞 Channel、`collect` 从主线程取；`awaitClose` 在取消时清理网络连接（结构化并发兑现到资源层）
- 🟣 `trySend` vs `send`：send 是 suspend（满则挂起），trySend 非阻塞立即返回 `ChannelResult`；回调不在协程只能用 trySend
- 🟣 **背压与丢数据**：callbackFlow 默认 BUFFERED(64)，满则 trySend 失败、返回值被忽略会静默丢；项目**三层解决**——源头过滤(只 forward `[tool]/[react]/[route]`) + `buffer(UNLIMITED)` 兜底 + 消费提速(flowOn 并行/写库节流)；trade-off：UNLIMITED 用内存换不丢，真正背压还得靠流入流出平衡

### Android 运行时 / 主线程
- 🟣 **主线程 = Looper + MessageQueue**，从 Android 诞生未变；`ActivityThread.main()` → `prepareMainLooper()` + `loop()` 死循环
- 🟣 协程没替换它：`Dispatchers.Main` 骑在主 Looper 上；其它 Dispatcher（IO/Default）是 `CoroutineScheduler` 线程池，不走 Looper
- 🔵 主线程一切（生命周期、输入、vsync 刷新、Handler.post、动画）都是队列里的 Message

### 架构 / 数据存储
- 🔵 ViewModel = 存放界面相关数据
- 🔵 本地 Room（缓存+离线）+ 后端（source of truth）混合架构是大厂通用做法；项目当前"本地存全部消息"因后端无历史接口而合理
- 🔵 列表层增量重组（key + @Immutable）vs 文本层全量重布局的取舍
- 🟣 **DataSource 接口抽象（依赖倒置 DIP）**：上层依赖 `SseDataSource` 接口而非 OkHttp 具体类；价值=可测试(testability seam，塞 FakeSseDataSource 离线测 handleSseEvent)+可替换(换实现上层零改)+消除重复(send/continue 复用 streamChat)

### 网络层：OkHttp-SSE 实战
- 🟣 `readTimeout=0`：SSE 长连接不能设读超时（几十秒不来数据正常，设了会被当死连接掐断）
- 🟣 日志只能 `HEADERS` 级：`BODY` 级会缓冲整个响应体，而 SSE 响应体无限长 → `onEvent` 永不触发（隐蔽坑，已写进注释）
- 🔵 `EventSources.createFactory()` 工厂模式（封装"绑定 client + SSE 协议 + 发起连接"）；回调命中 `EventSourceListener`（监听器）非 `EventSource`（连接句柄）；回调=控制反转，何时调用由库决定
- 🔵 `okhttp-sse` = Square 的 `com.squareup.okhttp3:okhttp-sse` 子模块（在 square/okhttp 仓库内，非独立 repo，故直接搜不到）
- 🟣 主动发现**代码与注释不一致**：类注释称 apiClient 是 BODY 日志，实际 base 统一 HEADERS、两 client 经 newBuilder 继承、从未设 BODY

### Channel（Kotlin 协程的并发原语）
- 🟣 Channel 是通用原语，不只 callbackFlow：可直接 `Channel<T>()`；普通 `flow{}` 无 channel；callbackFlow/channelFlow/produce/.buffer 都用
- 🟣 **Kotlin Channel vs Go channel**：①阻塞 goroutine vs 挂起 coroutine ②Kotlin 有 `UNLIMITED`/`CONFLATED` 开箱即用，Go 要手搓 ③close 后 send：Go **panic** vs Kotlin 返回失败不崩 ④`onBufferOverflow`(SUSPEND/DROP_OLDEST/DROP_LATEST) Go 没有 ⑤定位：Go channel 是主角(CSP)，Kotlin channel 是底层砖、Flow 才是上层

### 内存泄漏 / Context 生命周期
- 🟣 **Activity Context vs Application Context**：Application 随进程存活（持它不泄漏）；Activity 随界面销毁，被长生命周期对象（单例 / ViewModel scope / 静态字段）持有即泄漏
- 🟣 **ViewModel 跨配置变更存活 → 协程闭包捕获 Activity Context 的泄漏**：viewModelScope 跟 ViewModel 同生共死；旋转屏幕时 ViewModel 不销毁（`ViewModelStore` + `NonConfigurationInstance`），其内 `launch` 的协程若捕获了 Activity context，旧 Activity 泄漏到该协程结束；返回退出**不**触发（`onCleared` 取消 viewModelScope → 协程取消 → 释放引用）
- 🟣 **排查两段式**：① 静态读代码找"长生命周期对象持有短生命周期对象"的嫌疑；② 确证靠 LeakCanary——对观测对象建 `WeakReference`，触发 GC 后引用仍在 → dump hprof → 分析到 GC root 的强引用链
- 🔵 **LeakCanary 集成**：`debugImplementation`（release 不打包，体积零成本），2.x 经 ContentProvider 自动初始化、无需写代码
- 🟣 **修复范式**：Repository 已持有 Application 就别让上层把 Context 传进来（依赖倒置的方向上也不能塞短生命周期对象）；`LocalContext.current` 是 Activity context，传进 ViewModel = UI 层对象越界到业务层

## 水平评估（对照校招应届生）

**总体：明显高于多数应届生。** 不是停留在"会用"，而是多次主动追问到**原理层和系统咬合关系**——这正是区分强弱候选人的关键。

**强项（高于平均）：**
- 协程 / Flow 的**线程与生命周期语义**理解得很扎实，能讲清"为什么这么写"（这是很多工作 1-2 年的人都没彻底想通的）。
- Compose 重组机制 + `by` 委托的底层打通，不是黑盒使用者。
- "协程 resume = Handler.post"、"挂起 vs 阻塞"这类**把抽象 API 和 Android 运行时焊在一起**的认知——面试加分项。
- 🟣 **背压/缓冲的工程 trade-off**（buffer UNLIMITED 用内存换不丢、conflate 主动丢中间态、区分"能丢/不能丢"）+ **Kotlin Channel 与 Go channel 跨语言对比**——"理解取舍本质 + 迁移类比"，中级以上才有的判断力。
- 🟣 主动发现项目里**代码与注释不一致**（apiClient 日志级别），有 code review 敏锐度。

**建议补强（校招高频、尚未在对话中深入）：**
- 📌 **Handler 源码级细节**：Message 池（obtain/recycle）、`MessageQueue.next()` 的 native 唤醒（epoll）、`nativePollOnce`。你已懂机制，再补源码就到 SP/SSP 档。
- 📌 **Activity 生命周期 + 启动模式（launchMode / taskAffinity）**：四大组件里 Activity 是面试重中之重。
- 📌 **ViewModel 为何能在配置变更（旋转屏幕）后存活**：`ViewModelStore` + `NonConfigurationInstance`，你已会用 viewModelScope，补这块闭环。
- 📌 **Jetpack Navigation / 依赖注入（Hilt）**：项目在用，可结合代码深入。
- 📌 **性能优化 & 内存泄漏**：LeakCanary、Choreographer 掉帧分析、`StrictMode`——你已涉及 ANR/卡顿，可系统化。
- 📌 **View 体系（measure/layout/draw）+ Compose 互操作**：纯 Compose 项目容易漏 View 体系，但校招常考。

## 更新日志
- 2026-07-09：新增主题 15「协程与主线程 Looper/MessageQueue 的关系」（🟣）。补强项里加入 Handler 源码级细节。
- 2026-07-10：新增主题「网络层：OkHttp-SSE 实战」🟣、「Channel（并发原语）」🟣；Flow 主题补 callbackFlow 桥接 / trySend / 背压三层解决（🟣）；协程主题补「嵌套 launch 的顺序保证与竞态」（🟣）；架构主题补「DataSource 接口 / 依赖倒置」（🟣）。深度触达：背压 trade-off、Channel vs Go 跨语言对比、主动发现代码-注释不一致。
- 2026-07-10：新增主题「内存泄漏 / Context 生命周期」（🟣）——Activity vs Application Context、ViewModel 跨配置变更存活致协程闭包捕获 Activity context 泄漏、LeakCanary 原理（WeakReference + GC + hprof 引用链）与集成。修复 `uploadFile` 把 Activity Context 传进 viewModelScope 的泄漏（BUGFIX_LOG #20），并接入 LeakCanary 做 runtime 兜底。
