# LabSage 项目核心八股文 30 题

> 这些问题都能在 LabSage Android 客户端代码中找到对应实现。回答按正常面试交流整理，建议理解思路后用自己的语言表达，不要逐字背诵。

## 一、架构与 Compose

### 1. Repository 层在项目中起什么作用？

ViewModel 负责保存状态和处理逻辑。

我把 Repository 当成 ViewModel 和数据源之间的边界。ViewModel **只表达“查询会话”“发送消息”**“同步历史”**这类业务意图**；具体访问网络、写入数据库都由 Repository 负责。

这样做以后，**UI 不需要知道数据到底来自本地还是服务端**。比如我们的对话数据可以从本地加载也可以从服务器加载（优先）。Repository 解决的是多数据源协调和一致性问题。

- 项目的会话列表和消息列表由 Room DAO 返回 Flow。当相关表发生增删改时，Room 会通过失效追踪重新执行对应的 SELECT 查询，并由 Flow 发出最新列表。ViewModel 将结果转换或保存到 StateFlow，Compose 使用 `collectAsStateWithLifecycle` 收集；StateFlow 更新后，读取这份状态的 Composable 会重组，从而刷新界面。消息链路还使用了 `conflate`，在连续更新时跳过过时的中间结果，避免 UI 处理积压。

### 2. 为什么使用手动依赖注入，没有使用 Hilt？

这个客户端目前的依赖关系比较清楚，核心就是网络客户端、数据库、Repository 和几个 ViewModel，所以我先在 Application 中通过懒加载和 ViewModelFactory 管理依赖。优点是对象怎么创建、作用域在哪里都比较直观，调试也方便。

- Hilt 更适合模块多、作用域复杂或者多人协作的项目。手动注入的缺点是项目继续扩大后，Application 会越来越重。如果后续拆分多模块，我会考虑迁移到 Hilt。这个选择主要是根据当前规模控制复杂度，不是认为 Hilt 没有必要。

### 3. 为什么用密封类管理流式状态，而不是多个 Boolean？

项目使用密封类把流式过程限制为 `Idle`、`Streaming`、`Completed` 和 `Error`。这几个状态是互斥且完备的：同一时刻只能处于一种状态，同时又覆盖了完整的流式生命周期，避免多个 Boolean 组合出“正在生成但已经完成”这类非法状态。

其中 `Error` 还会保存是否可重试、残缺正文和原问题，使状态对象本身成为一份完整快照。Compose 可以通过 `when` 展示对应界面，编译器也能检查分支是否完整；执行继续生成或异常恢复时，不需要再从多个变量中拼接所需数据。

### 4. Compose 为什么会发生重组？项目怎样控制重组范围？

Composable 读取的 State 发生变化后，Compose 会重新执行依赖这份 State 的代码，这就是重组。它通常不是把整个 Activity 和所有界面全部重建，而是根据状态读取位置更新相关部分。

项目把消息列表、流式状态、工具调用和同步状态拆成不同的 StateFlow，避免一个总状态变化时牵连整个页面。同时遵循单向数据流：ViewModel 向下提供状态（VM的stateflow向下传递到组件），UI 通过回调向上传递事件（ui组件向上传递到ViewModel）。

- 这属于 Compose 中的单向数据流，也就是 State down、Events up。ViewModel 通过 StateFlow 向 UI 提供状态，Screen 再把状态传给子组件；用户点击或输入时，子组件通过 lambda 回调把事件传回上层，由 ViewModel 处理业务并更新 StateFlow。新状态再次向下传递，Compose 根据状态变化重组界面。这样可以避免 UI 和 ViewModel 各自维护一份数据，让状态来源更加明确。

### 5. `remember`、`rememberSaveable`、ViewModel 和 Room 应该怎么选？

我主要根据状态需要存活多久来选。`remember` 适合只在当前组合中使用的状态，比如面板是否展开；`rememberSaveable` 适合少量、可保存的界面状态，可以跨配置变更恢复。

- `remember` 的初始化代码会在第一次进入组合时执行，普通重组时复用之前保存的对象。如果 key 改变、Composable 离开组合后重新进入，或者 Activity 被重新创建，就可能重新计算。`rememberSaveable` 在此基础上通过 Saved State 保存可序列化的数据，因此能够应对屏幕旋转等重建，但不适合代替 ViewModel、Room 或 DataStore 做业务状态和长期持久化。

ViewModel 用于页面级业务状态，能够跨 Activity 重建，但进程被杀后也会消失。真正需要可靠保留的聊天正文、同步信息和消息状态会写入 Room。不能因为 ViewModel 能抗旋转，就把它当成持久化方案。

### 6. `collectAsStateWithLifecycle`、`LaunchedEffect`  分别解决什么问题？

`collectAsStateWithLifecycle` 用来把 Flow 安全地交给 Compose 收集，页面不可见时会根据生命周期暂停，避免后台继续做无意义的 UI 更新。

- 把stateflow转换为Compose可观察的state，且只在有效生命周期内进行收集；

`LaunchedEffect` 适合跟某个 key 绑定的协程任务，例如通知目标变化后执行导航；key 变化时旧任务会取消并重新执行。

- 默认在 Compose 的主线程协程上下文中执行

### 7. LazyColumn 为什么需要稳定 key？流式回答如何处理自动滚动？

如果列表只按位置识别 Item，中间插入或删除消息后，Compose 可能把原位置的内部状态复用到另一条消息。因此项目使用消息 ID 和工具调用 ID 作为稳定 key。

自动滚动也不能简单地在每次正文更新后强制到底部。用户主动向上查看历史时，项目会暂停跟随并显示“返回底部”按钮；只有用户仍在底部或者刚发送问题时才继续跟随。流式过程中直接定位，不连续叠加滚动动画，否则长回答很容易出现滚动滞后。

## 二、协程与 Flow

### 8. `suspend` 是否代表函数自动运行在子线程？Dispatcher 怎么选？

不代表。`suspend` 只说明函数可以挂起，具体运行线程由协程上下文和函数内部实现决定。

项目中明确的文件查询和复制使用 `Dispatchers.IO`，界面状态以及 SpeechRecognizer 这类要求主线程的 API 留在 Main，偏计算的应用级任务可以使用 Default。选择 Dispatcher 要看任务是阻塞 I/O、CPU 计算还是 UI 操作，不能看到 `suspend` 就默认不会阻塞主线程。

### 9. 为什么使用 `viewModelScope`？Application 级任务为什么使用 `SupervisorJob`？

聊天发送、历史同步和页面加载都属于 ViewModel，所以放在 `viewModelScope` 中。ViewModel 被清除后，这些任务会自动取消，不会继续持有页面相关逻辑。`GlobalScope` 没有明确所有者，在这里不合适。

后端切换和全局设置属于应用级任务，因此 Application 自己持有 CoroutineScope，并使用 `SupervisorJob`。这样一个子任务失败不会把其他互不相关的任务一起取消。不过异常仍然要分别处理，SupervisorJob 不是用来吞错误的。

### 10. Flow、StateFlow 和 Channel 有什么区别？

Flow 更像一段异步数据流，默认是冷流，每个收集者都会**重新启动上游**（来获得flow）。StateFlow 是热流，始终保存一个最新值，很适合页面状态，因为 Compose 随时都能拿到当前值。

- 普通 Flow 通常是冷流，只有调用 collect 后，上游代码才开始执行，并且每个收集者通常会独立启动一次上游。它本身不负责保存最新值。
- StateFlow 是热流，必须有初始值，并且**始终保存当前最新状态**；即使暂时没有收集者，仍然可以通过 value 读取，新收集者开始收集时也会立即拿到最新值。StateFlow 只保留最新状态，不保存完整事件历史

Channel 更接近协程之间传递事件的队列，元素被接收后就消费掉了。项目用 StateFlow 表示页面状态，用 callbackFlow 把 OkHttp 的 SSE 回调转换成 Flow；callbackFlow 内部的 Channel 只承担事件传递，不作为界面的长期状态。

### 流程：

OkHttp SSE 原本是基于回调的。服务端每返回一个事件，就会调用一次 `EventSourceListener`，这种**回调形式不方便直接交给 ViewModel 使用**，所以项目通过 `callbackFlow` 把它转换成 Flow。

`callbackFlow` 内部可以理解为带有一个 Channel。OkHttp 回调到达后，通过 `trySend` 把 SSE 事件放进 Channel；Channel 负责在回调生产者和 Flow 收集者之间传递、缓冲事件。事件被收集后就消费掉了，因此它适合传递过程事件，不适合保存页面的长期状态。

- callbackFlow内部有一个channel来传递sse事件

`callbackFlow` 对外暴露的是 `Flow<SseEvent>`。当 ViewModel 开始 `collect` 时，callbackFlow 才建立 SSE 连接；Channel 中的事件会按数据流形式交给 ViewModel。收集被取消后，`awaitClose` 会关闭 SSE 连接。因为它是冷 Flow，如果被多个收集者分别收集，通常会分别启动一条 SSE 链路。

- callbackFlow返回的是flow类型数据，当ViewModel开始collect时，callbackFlow 才建立 SSE 连接；Channel 中的事件会按数据流形式交给 ViewModel。

ViewModel 收到 Flow 中的 Message、Done、Error 等事件后，不直接把 Channel 暴露给 Compose，而是把这些过程事件转换成稳定的页面状态，保存到 StateFlow 中。例如正文事件更新消息内容，Done 更新为 Completed，异常则更新为 Error。

- ViewModel根据flow的数据去更新相应的stateflow值，从而让compose重组

StateFlow 是热流，会一直保存当前最新状态。Compose 使用 `collectAsStateWithLifecycle` 收集 StateFlow，新值到达后转换成 Compose State，并触发读取这份状态的界面重组。

所以这几个组件的职责可以概括为：Channel 负责事件排队和传递，callbackFlow 负责把回调桥接成 Flow，Flow 负责把 SSE 事件交给 ViewModel，StateFlow 负责保存 ViewModel 处理后的最新页面状态。

### 11. 为什么使用 `callbackFlow` 和 `awaitClose` 接入 SSE？

OkHttp SSE 通过 `EventSourceListener` 回调消息，而 ViewModel 更适合收集 Flow。`callbackFlow` 可以在回调里通过 `trySend` 发送事件（事件->Flow），把连接关闭和异常统一成 Flow 的完成或失败，上层就能继续使用协程取消和 Flow 操作符。

`awaitClose` 负责收集结束后的清理。项目会在这里取消 EventSource，否则页面退出后连接可能仍然存在，并继续向已经没有消费者的 Channel 发送事件。

### 12. 什么是背压？`buffer` 和 `conflate` 应该怎么选？

背压就是上游产生数据的速度比下游处理速度快。无限缓冲并没有真正解决问题，只是把压力变成越来越高的内存占用。

### buffer 和 conflate 在项目中是怎么使用的？

项目中 `buffer` 和 `conflate` 用在两条不同的数据流上。

首先，`buffer` 用在原始 SSE 事件流上。OkHttp 每收到一次服务端回调，就通过 `callbackFlow` 的 `trySend` 发送一个 Message、Trace、Done 或 Error 事件，然后使用固定 512 容量的 buffer 承接这些事件。

这里的 Message 是增量文本，不是完整回答。例如服务端分别发送“你”“好”“世界”，ViewModel 需要依次收到三个 chunk，再通过 StringBuilder 拼成“你好世界”。因此原始 SSE Flow 不能使用 conflate，否则消费端处理较慢时，中间 chunk 可能被跳过，最终正文就会缺字。Done、Error 和工具调用事件也不能随便丢失。

当 512 容量的缓冲区满时，`trySend` 因为不能挂起，会返回失败。项目不会继续运行并静默丢掉新事件，而是取消 SSE 连接，并让 Flow 携带异常结束。这样客户端能够明确知道本次回答已经无法保证完整。

`conflate` 则用在 Room 返回的消息列表 Flow 上。Room 每次执行查询后返回的是当前会话的完整消息列表，而不是单独一个文本 chunk。假设 Room 连续返回正文为“你”“你好”“你好世界”的三份完整列表，如果 UI 来不及处理，conflate 可以跳过中间的“你好”，直接处理最新的“你好世界”，最终结果不会缺字。

所以选择依据不是 buffer 一定比 conflate 安全，而是看数据类型：原始 SSE chunk 是不能丢的增量事件，因此使用 buffer；Room 返回的是完整状态快照，只关心最新结果，因此可以使用 conflate。

### 如何防止 Room 的旧快照覆盖内存中的新正文？

当前项目的流式界面不是完全等待 Room 更新，而是把 UI 更新和数据库持久化拆成了两条链路。

每个 SSE chunk 都会先追加到内存中的 `accumulatedAnswer`。最多每 50 毫秒，ViewModel 直接使用完整的内存正文更新 `_messages` StateFlow，让 Compose及时显示；最多每 500 毫秒，才把当前完整正文保存为 Room checkpoint。

因此某个时刻可能出现：

- 内存正文已经是“你好世界”；
- Room 中最近一次 checkpoint 仍然是“你好”。

Room 更新后会重新查询并返回完整消息列表，这份列表经过 conflate 到达 ViewModel。ViewModel 不会直接用它覆盖当前 `_messages`，而是先检查列表中是否包含正在生成的那条消息。

如果消息 ID 与 `streamingMessageId` 相同，并且当前仍处于 Streaming 状态，就使用最新的 `accumulatedAnswer` 替换 Room 快照中的旧正文，再把合并后的列表写入 `_messages` StateFlow。

也就是说，**数据库负责提供完整的消息列表结构和可恢复 checkpoint；正在生成的那一条消息，则以内存中的最新正文为准。**这样即使 Room 回流较慢，也不会让界面从“你好世界”倒退回“你好”。

### buffer 满了以后会自动重试吗？

当前代码没有使用 `retry()` 或 `retryWhen()`，因此不会自动重新建立 SSE 连接。

buffer 满后，数据源会取消 EventSource，并用 IOException 关闭 Flow。ViewModel 的 `catch` 会捕获异常，把当前已经累积的正文强制保存到 Room，并将流式状态更新为可重试的 Error。

界面随后会显示两个操作：

- “继续生成”：保留当前残缺正文，向后端发送原问题和 PartialAnswer，让后端从已有内容后继续生成；
- “重新生成”：删除原来的残缺回答，再重新发送最初的问题。

所以这里的“可重试”准确来说是用户可恢复，不是客户端自动重连。这样设计可以避免在消费端持续跟不上的情况下反复自动连接，造成重试风暴或者重复消息。如果以后要增加自动重试，还需要加入重试次数、指数退避、请求幂等和网络状态判断，不能简单无限调用 retry

### （SKIP）13. 为什么捕获异常时要单独处理 `CancellationException`？`NonCancellable` 又有什么作用？

协程取消也是通过异常传播。如果用 `catch (Exception)` 把 CancellationException 转成普通业务失败，任务看起来报错了，实际上是正常取消，协程的取消语义也会被破坏。

项目在文件上传中会先重新抛出 CancellationException，其他异常再转成失败结果。临时文件清理则放在 `NonCancellable + Dispatchers.IO` 中，因为进入 finally 时协程可能已经取消，普通挂起清理有可能执行不完整。NonCancellable 只包很短的收尾操作，不能用来包整个任务。

### 14. Mutex 和 `synchronized` 有什么区别？项目为什么使用 Mutex？

`synchronized` 是线程级阻塞锁，等待时会占住线程；Mutex 更适合协程，获取不到锁时会挂起协程。

项目用 Mutex 串行处理 Token 刷新、历史同步和旧数据导入。例如多个请求同时返回 401 时，只允许一个请求真正刷新 Token，其他请求等待后复用新结果。即使使用 Mutex，也要控制锁的范围，不能把无关的长耗时任务全放进去。

## 三、网络与 SSE

### 15. Retrofit 和 OkHttp 在项目中分别负责什么？为什么使用两个 OkHttpClient？

OkHttp 是底层 HTTP 客户端，负责连接、超时、拦截器、认证和请求体写出。Retrofit 在它上面把普通 REST 接口声明成 Kotlin 方法，并**使用 Gson 完成 JSON 转换**。

REST：

- 每个资源都有唯一的URL
- 使用HTTP动词表示操作
- 服务端不保存状态
- 统一接口

项目从同一个基础 Client 通过 `newBuilder()` 创建 REST 和 SSE 两个客户端，共享连接池等基础配置。REST 使用普通读取超时；SSE 是长连接，读取超时设为 0，并且只记录 Header。这样既复用了底层资源，又让两类请求使用各自合适的配置。

- 同一个连接池上为SSE与REST风格请求风别创建不同客户端；
- SSE客户端超时时长设为0
- 复用底层资源的同时，两类请求使用各自合适的配置

### 16. SSE、WebSocket 和轮询有什么区别？为什么 AI 回答选择 SSE？

轮询是客户端不断发请求确认有没有新数据，实现简单，但会产生重复请求和额外延迟。WebSocket 是全双工连接，客户端和服务端都能频繁主动发消息，适合聊天房间、游戏或协作编辑。

SSE 基于 HTTP，主要用于服务端持续向客户端单向推送。AI 问答的模式通常是客户端提交一次问题，服务端持续返回正文，所以 SSE 足够，而且比维护 WebSocket 协议和双向心跳更简单。

### 17. SSE 为什么不设置普通读取超时，也不能使用 BODY 级别日志？

AI 思考或调用工具时可能暂时没有正文，但连接仍然正常。若沿用 REST 的读取超时，空闲时间稍长就可能被错误断开，所以项目把 SSE 读取超时设为 0，业务结束由 Done、Error、连接关闭或用户取消判断。

BODY 日志通常需要读取响应体后再打印，而 SSE 响应可能长时间不结束，容易造成缓冲、延迟回调和额外内存占用。因此 SSE 只记录 Header，并对 Authorization 脱敏。**如果连接在 Done/Error 之前关闭，项目会把它视为可重试错误，而不是把半截回答当成完成。**

### 18. 为什么请求需要 `clientMessageId` 和 `clientRequestId`？

网络超时只表示客户端没有收到结果，不代表服务端一定没有处理。如果重试时重新生成身份，服务端可能插入两份相同消息。

项目由客户端提前生成稳定 ID，让首次发送、网络重试、继续生成和历史同步能够识别同一个逻辑请求。服务端返回正式消息后，也可以通过这些 ID 与本地占位消息对齐。这是实现幂等和避免重复数据的重要基础。

- 客户端在创建消息时生成唯一的 clientMsgId，并立即保存到本地数据库，而不是每次发送请求时重新生成。后续网络重试、断线恢复、历史同步都会复用这个 ID。服务端收到请求后根据 clientMsgId 做幂等判断，如果已经处理过则返回已有结果。同时服务端生成自己的 serverId，客户端通过 clientMsgId 将服务端正式消息和本地占位消息进行关联，从而避免重复数据。

## 四、Room、同步与恢复

### （SKIP）19. 为什么让 Room DAO 返回 Flow？同步写入为什么要使用事务？

DAO 返回 Flow 后，相关表发生变化时，Room 会重新执行查询并发出新列表。ViewModel 不需要在每次插入、更新后手动通知页面，聊天记录也能保持单一数据源。

一次历史同步通常包含多条新增或更新、清理旧记录以及推进游标。如果数据库只提交了一半，本地数据和游标就可能不一致。项目用事务完成一组 Room 操作，并且只在数据成功落库后推进游标；即使游标写入前进程退出，下次最多重复拉取，Upsert 仍能保证结果正确。

### 21. Room Migration 和索引应该怎样设计？

项目逐步增加了账号归属、后端归属、同步版本和消息状态等字段。如果直接使用**破坏性迁移，升级后会删库重建，**用户的聊天历史就没了，所以每次升级都提供明确的 Migration，并使用旧版本数据库验证数据、索引和外键。

索引主要加在常用查询条件、排序字段和业务唯一约束上。索引能加快查询，但会占空间，每次写入也需要维护，并不是越多越好。是否添加要结合实际的 WHERE、ORDER BY 和数据规模判断。

### 23. 全量同步和增量同步怎么配合？为什么使用 `syncVersion` 而不是时间戳？

首次登录、本地缓存缺失或者缓存绑定失效时，项目执行全量同步建立基线；**后续保存游标，只拉取游标之后变化的数据**。不能只看 DataStore 中有没有游标，如果**游标存在但 Room 缓存已经为空，也必须回退到全量同步。**

增量顺序使用服务端生成的单调 `syncVersion`（版本号），而不是客户端时间。不同设备的时间可能不一致，用户也可以修改系统时间，服务端版本号更稳定。客户端还会检查新游标是否倒退、返回记录是否处于正确范围，验证通过后才提交。

删除操作则通过 tombstone 传播。如果服务端直接把记录物理删除，其他设备只做增量同步时并不知道本地那条数据应该消失；带同步版本的删除标记可以解决这个问题。（逻辑删除，为了同步）

### 24. 旧版历史怎样迁移？本地占位消息和服务端消息冲突怎么办？

账号体系上线前，旧聊天记录只存在本地。项目会把旧会话和消息分批上传，避免单次请求过大；只有所有批次得到服务端确认后，才记录迁移完成，中途失败下次仍可重试。

流式发送时，本地会先创建占位消息，而历史同步可能提前拉到同一请求的服务端记录。项目通过 `clientRequestId` 判断两条记录是否属于同一逻辑请求：服务端记录已经完成时，用正式记录替换占位；如果服务端仍是中间状态，则优**先保留正文更完整的本地消息**，避免同步把已经接收的内容覆盖掉。

### 25. 应用进程被杀后，为什么还能恢复残缺回答？为什么不用 SavedStateHandle？（）

ViewModel 只能跨配置变更，进程被系统杀死后，内存中的正文、消息 ID 和错误状态都会消失。项目把正文、原问题、请求 ID、消息状态和 `isPartial` 分阶段写入 Room。

重新进入会话时，如果最后一条助手消息仍是残缺状态，ViewModel 会一起恢复累积正文、消息身份和原问题，再提供继续生成或重新生成。只恢复界面上的错误提示是不够的，因为继续生成还依赖这些内部信息。

SavedStateHandle 更适合少量页面状态，**底层保存空间也有限，不适合不断增长的长回答**。Room 更适合这里的长期保存、离线查看和**进程重启恢复**。

## 五、认证与安全

### 26. OkHttp Interceptor 和 Authenticator 有什么区别？

Interceptor 在**普通请求发出前统一添加** Access Token，也适合处理公共请求头和日志。Authenticator 是服务端返回 401 后调用，用来刷新凭证并构造一条带新 Token 的重试请求。

刷新接口使用不带 Authenticator 的基础 Client，否则刷新请求自己返回 401 时，可能再次触发刷新，形成递归。Authenticator 还会检查新 Token 是否真的与失败 Token 不同，避免拿同一个无效凭证无限重试。

### 27. 多个请求同时返回 401，怎样保证只刷新一次 Token？

项目用 Mutex 串行化登录态修改。第一个请求拿到锁后执行刷新并保存新 Token，其他请求进入锁时会比较“这次失败使用的旧 Token”和“当前已经保存的 Token”。

如果发现 Token 已经被前一个请求更新，就直接复用，不再调用刷新接口。保存时还会做类似 CAS 的检查，确认用户、后端和 Refresh Token 仍然是发起刷新时的会话，防止刷新期间用户已经退出或切换账号，旧结果又把新会话覆盖回来。

### 28. 为什么使用 Android Keystore 和 AES-GCM 保存登录态？为什么还要限制同源？

SharedPreferences 的 MODE_PRIVATE 只能阻止其他普通应用直接读取，并不等于 Token 是加密的。项目通过 Android Keystore 生成不可导出的 AES 密钥，再用 AES-GCM 加密完整会话后写入本地。GCM 同时提供加密和完整性校验，密文被修改后解密会失败。

日志中的 Authorization 也会脱敏。

## 六、文件上传与测试

### 29. 为什么文件选择使用 Content URI？流式 RequestBody 要解决哪些问题？

系统文件选择器返回的是 content URI，它可能来自本地文件、云盘或者第三方 Provider，不一定存在可以直接使用的真实路径，所以项目通过 ContentResolver 查询文件名、大小并打开输入流。

已知大小时，RequestBody 直接从 URI 流式写入 OkHttp，不再先复制临时文件、再把整个文件读成 ByteArray。为了支持认证重试，每次 `writeTo` 都重新打开 Source，不能复用已经读完的 InputStream。写出时还会检查实际长度是否与 Provider 声明一致。

部分 Provider 不返回文件大小，这时才回退到固定缓冲区的临时文件，并设置 10 MiB 上限。无论成功、失败还是协程取消，最终都要清理临时文件。这样既兼顾 Multipart 的 Content-Length 和请求重放，也避免大文件整体进入 Java 堆。

### 30. 项目中的测试和性能基准是怎样设计的？

不依赖 Android Framework 的状态策略、JSON、认证并发和 RequestBody 放在 JVM 单元测试中；Room Migration、Keystore、Compose 和真实数据库性能依赖设备环境，因此使用 Instrumentation Test。认证网络行为用 MockWebServer 模拟 401 和刷新响应，能够检查请求头、重试次数和“并发 401 只刷新一次”。

性能基准不会只跑一轮取最好结果。项目先预热，再让新旧方案交替执行，使用中位数表示典型情况、P95 观察较慢情况。SSE 基准使用真实文件 Room，预置历史消息并遍历完整会话查询；上传基准除耗时和堆增量外，还校验字节长度和 SHA-256，确保优化后内容没有变化。

面试时我会明确数据边界：SSE 结果代表本地持久化和会话快照开销，不代表模型生成速度；上传结果代表客户端文件准备和 RequestBody 写出，不包含网络与服务端处理。把测试对象说清楚，比单独报一个很大的提升比例更可信。
