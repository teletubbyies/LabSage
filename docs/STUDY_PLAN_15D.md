# LabSage 15 天面试冲刺计划（每天 7 小时 · 全职）

> **前提**：你看过《第一行代码》，是小白基础。目标不是"精通"（15 天到不了），而是 **中级偏上：能扛追问、能讲 Bug 根因、简历写"独立开发"**。
>
> **总预算**：15 天 × 7h = **105 小时**。这是接近全职的强度，**断社交、关娱乐、不开多线程**，否则计划作废。
>
> **核心方法论**：文档（`INTERVIEW_GUIDE.md`）是地图，但**看不会，必须动手踩坑**。每天必须有"亲手在 App 里操作/改代码"的环节，不然面试讲故事全是背的，一问就穿。
>
> **怎么用这份计划**：每天早上看"今日目标"，按时间块执行，晚上用"Done 标准"自检。没达标就动用"机动日"，连续两天没达标就要砍内容（见第 4 节红线）。

---

## 0. 三阶段总览

| 阶段 | 天数 | 目标 | 风险 |
|------|------|------|------|
| 🟢 **地基期** | Day 1~4 | 跑通 + 主线 + 协程基础 | 基础不牢后面全废 |
| 🟡 **攻坚期** | Day 5~11 | SSE / Room / Compose 三个硬骨头，亲手复现 Bug | 最累，最容易掉队 |
| 🔴 **输出期** | Day 12~15 | 内化 + 模拟面试 + 陈述 | 别留到最后一天才练嘴 |

---

## 1. 每日详细计划

> 每天固定节奏：**上午 3.5h（深学，脑子最清醒啃硬的）+ 下午 3.5h（动手实验 + 复盘）**。
> 周末（Day 6/7/13/14）不休息，DDL 在即。

### 🟢 Day 1 — 环境、跑通、全局认知(checked on 7.6, 7.18)

| 时段 | 任务 | 产出 |
|------|------|------|
| 上午 3.5h | 配好 Android Studio，编译跑通 App（连模拟器）。读 `INTERVIEW_GUIDE.md` 第 0~3 章 | App 能在模拟器打开，会话列表能进 |
| 下午 3.5h | 跑通后端（或 mock），**亲手发一条消息，看到流式打字效果**。对照第 3 章"一条消息的旅程"，在代码里跟一遍调用链 | 能指出：发送按钮 → `sendMessage` → `doSend` → SSE → `handleSseEvent` → Room → Compose |

- **Done 标准**：能用手指着 `ChatViewModel.kt` 说清"用户点发送后，代码按什么顺序跑"(第三章)
- ⚠️ 如果今天连 App 都跑不起来，**别硬扛**，明天先解决环境，地基期顺延

### 🟢 Day 2 — 协程/Flow 基础（在项目里学，别看干巴巴的教程）(checked on x, 7.18)

| 时段 | 任务 | 产出 |
|------|------|------|
| 上午 3.5h | 学协程三件套：`launch` / `suspend` / `viewModelScope`。在项目里搜 `viewModelScope.launch`，理解每个为什么这么用 | 能说清"为什么要在 `viewModelScope` 里开协程" |
| 下午 3.5h | 学 `Flow`：`collect` / `flowOn` / `catch`。回到第 4 章 `OkHttpSseDataSource`，看懂 `callbackFlow` 那段（看不懂正常，先建立印象） | 能画出 SSE → Flow → collect 的数据流图 |

- **Done 标准**：能回答"协程和线程的区别""`Flow.collect` 在哪个线程跑"
- 🔑 这一关不过，第 4 章的 SSE 你根本读不动。**宁可今天多花时间，也别跳过**

### 🟢 Day 3 — SSE 流式精读（第 4 章，项目的灵魂）(checked on x, 7.18)

| 时段 | 任务 | 产出 |
|------|------|------|
| 上午 3.5h | 精读第 4 章 4.1~4.4。搞懂：什么是 SSE、和 WebSocket 区别、`callbackFlow` 怎么把回调变 Flow | 能脱稿讲"SSE 是什么、为什么用它" |
| 下午 3.5h | 逐行注释 `OkHttpSseDataSource.kt`，搞懂 `trySend` / `awaitClose` / `readTimeout(0)` / 日志为什么只能 HEADERS | 能回答 `trySend` vs `send`、为什么不能 BODY 日志 |

- **Done 标准**：面试官问"讲讲你 SSE 怎么实现的"，你能讲 3 分钟不卡
### 面试版：「讲讲你 SSE 怎么实现的」（约 3 分钟，人话）

> 我做的 LabSage 是个 AI 实验室助手，核心场景是和大模型对话，回答需要**一个字一个字流式输出**，同时后端还会推工具调用的过程信息。所以选型上我用的是 **SSE**，而不是 WebSocket 或轮询——它是单向服务器推送，基于普通 HTTP，够用、简单、对后端友好。

> 实现上我分了三层。最底层是一个 `SseDataSource` **接口**，生产实现是 `OkHttpSseDataSource`。这里有个关键设计：OkHttp 的 SSE 是**回调风格**的，`onEvent`、`onFailure` 这些方法是它来调我；但我的上层是协程，想用 Flow 统一处理。所以我用 **`callbackFlow` 做了一座桥**——在回调里 `trySend` 把事件塞进一个 Channel，外部就能像普通 Flow 一样 `collect`。之所以用 `trySend` 而不是 `send`，是因为回调跑在 OkHttp 线程、不在协程里，调不了 suspend 函数。

> 然后加了两个操作符保证稳定性：`.flowOn(Dispatchers.IO)` 把网络生产逻辑切到 IO 线程，主线程只负责消费，不会卡 UI；`.buffer(Channel.UNLIMITED)` 在生产和消费之间放无界缓冲，因为工具类查询一次可能推两三百条 trace，没缓冲会丢数据。

> 这套设计踩过两个比较隐蔽的坑，我也写进了注释。一个是 **SSE 的 OkHttpClient 必须 `readTimeout=0`**，因为它是长连接，几十秒不来数据是正常的，设了超时会被当成死连接掐断。另一个是**日志拦截器绝对不能用 BODY 级**——BODY 会缓冲整个响应体，而 SSE 响应体是无限的，缓冲之后 `onEvent` 永远不触发。所以我专门给 SSE 单独建了个 client，只用 HEADERS 日志。

> 最后在上层做了点工程优化。消费端收到事件后，内容要落库再让 UI 显示，但我没每个字都写库——**每攒 5 个 chunk 写一次**，降低磁盘 IO 压力，最后 `done` 时统一 flush。整个流的生命周期我存成一个 `streamingJob`，用户重新生成或切走时能取消；而 `callbackFlow` 里的 `awaitClose` 保证了消费者一停，网络连接也会跟着断开，不会泄漏连接。

> 简单说就是：**用 callbackFlow 把回调世界翻译成协程世界，用 flowOn 和 buffer 保证不卡不丢，用接口分层换来可测试和可替换，再配合 readTimeout=0 这些细节踩平 SSE 的坑。**

---

几点面试加分提示（不一定念出来，被追问时备用）：

- **被问"为什么接口"** → 可测试性：能塞一个 FakeSseDataSource 离线测 `handleSseEvent` 的所有分支，不依赖真实后端。
- **被问"冷流热流"** → 这个 Flow 是冷的，不 collect 不执行；`callbackFlow` 在 collect 时才真正发起 SSE 连接。
- **被问"和 WebSocket 区别"** → SSE 单向（服务器→客户端）、基于 HTTP、自动重连、文本为主；WebSocket 双向、二进制、更重。我这场景只要服务器推，SSE 更合适。
- **被问"背压"** → 我用 `buffer(UNLIMITED)` + 消费端 `conflate()`（Room 那条链）缓解；如果事件量失控，更稳的做法是 `conflate` 或限流，但我这里事件有天然上限所以 UNLIMITED 可接受。


### 🟢 Day 4 — SSE 动手：重写 + 踩背压坑（第 4.5 章 + Bug 9.3）(checked on x, 7.18)

| 时段 | 任务 | 产出 |
|------|------|------|
| 上午 3.5h | **删掉 `OkHttpSseDataSource.streamChat` 的实现，自己重写一遍**（对照原文改回来） | 重写后能正常流式 |
| 下午 3.5h | **复现 Bug 9.3**：临时去掉 `.buffer(UNLIMITED)` 和事件过滤，点 GPU/Conda 查询，观察"答案输出一半就停"。再加回来，确认修复 | 亲眼见过丢数据 → 才讲得出故事 |

- **Done 标准**：能讲"`trySend` 为什么会丢数据，你三层怎么解决的"
- 🔑 这是你最大的面试亮点，**今天必须亲手踩坑**
  先给你讲透**为什么 trySend 会丢**（你自己得先懂），再给**面试口语稿**。

---

## 先理解：trySend 在什么情况下丢数据

`trySend` 不是阻塞的，它的规则是——**channel 放得下就放，放不下就立刻返回失败，绝不等待**。而代码里它的返回值是被忽略的：

```kotlin
override fun onEvent(...) {
    val event = buildEvent(type, data) ?: return
    trySend(event)   // ← 返回值没接！放不进就静默丢弃
}
```

关键点：**`callbackFlow` 内部自带一个有界 channel（系统默认容量 64）。** 所以当生产快、消费慢、64 个槽位填满后，再 `trySend` 就失败、事件就丢了。项目注释也写了这个起因（`OkHttpSseDataSource.kt:98`：`prevent data loss with 240+ events on tool queries`）——一次工具查询能推两三百条 trace，64 根本不够。

所以这不是 trySend 的 bug，是"有界缓冲 + 忽略返回值"的自然结果。

---

## 面试 3 分钟 版本

> 这要从 trySend 的特性说起。我把 OkHttp 的 SSE 回调桥接成协程 Flow，在回调里用 `trySend` 把事件塞进 channel。但 `trySend` 是"非阻塞试投"——放得下就放，放不下直接返回失败。而 `callbackFlow` 自带的有界 channel 默认只有 64 个槽位，生产快、消费慢时一旦填满，`trySend` 就失败，如果返回值没处理，事件就静默丢了。我的场景一次工具查询能推两三百条 trace，64 根本扛不住，注释里也记录过这个数据丢失的问题。

> 我是从**源头、缓冲、消费三个层面**一起解决的。

> **第一层，源头减量。** 我在 `onEvent` 里就做了过滤——后端的 trace 事件一次会推两百多条自由格式的日志，但真正用来构建工具面板的只有 `[tool]`、`[react]`、`[route]` 这几类前缀。不该进流的数据我在源头直接 `return` 掉，根本不 `trySend`。**减少生产量本身就是最好的背压**，这是最划算的一刀。

> **第二层，缓冲兜底。** 真正要保留的事件，我加了 `.buffer(Channel.UNLIMITED)`，把中间缓冲容量设成无限，保证放得下、不丢。这是直接针对"64 不够"的解法。

> **第三层，消费提速。** 缓冲无限了，但光兜底不够——如果消费者永远比生产者慢，UNLIMITED 会把数据全堆在内存里，最后 OOM。所以我用 `.flowOn(Dispatchers.IO)` 让生产在 IO 线程、消费在主线程**并行**，主线程不会被网络拖慢，消费速度跟得上；同时消费端做了**写库节流**——不是每个字都写磁盘，而是每攒 5 个 chunk 写一次，把磁盘 IO 这个最慢的环节降下来，避免它成为消费瓶颈导致缓冲堆积。

> 我也清楚这背后的权衡：**`buffer(UNLIMITED)` 本质是用内存换不丢**，它把"丢数据"的问题转嫁成了"内存膨胀"的风险。所以真正的背压不能只靠无限缓冲，还得靠源头过滤 + 消费端提速一起，让流入和流出尽量平衡。在我这个场景里，事件有天然上限、单条又很小，UNLIMITED 是可接受的；如果换成无上限的视频流，我就得换成 `conflate` 主动丢中间态，或者做真正的限流。

> 一句话：**trySend 在有界缓冲满时会静默丢，我用"源头过滤少生产、UNLIMITED 兜底不丢、flowOn 并行加节流写库让消费跟得上"三层，把丢数据的风险从源头到出口堵死。**

---

## 被追问的备弹

- **"为什么不用 send？"** → `send` 是 suspend，回调跑在 OkHttp 线程、不在协程里，调不了；只能用非 suspend 的 `trySend`。
- **"UNLIMITED 会不会 OOM？"** → 会，如果消费长期慢于生产。所以它不是银弹，必须配合源头过滤和消费提速。我这个场景事件有上限、体积小，可控。
- **"conflate 和 buffer(UNLIMITED) 有什么区别？"** → 方向相反。`buffer(UNLIMITED)` 是**全保**（一个都不丢，代价是内存）；`conflate` 是**主动丢中间态只留最新**（省内存，但丢历史）。我这儿用户内容必须全保所以用 buffer；而 Room 那条链路只关心最新消息列表，中间的过渡态可以丢，所以那里用了 conflate——**区分"什么能丢、什么不能丢"是关键判断**。
- **"为什么默认是 64？"** → `callbackFlow` 默认 `Channel.BUFFERED`，大小由 `kotlinx.coroutines.channels.defaultBuffer` 系统属性控制，默认 64。
- **"除了 buffer 还有什么背压策略？"** → `conflate()`（只留最新）、`buffer(n)`（有界+挂起而非丢弃，但需要生产端能挂起，回调场景用不了 send 所以不适用）、或自定义限流。

要不要我帮你把这个点压成 **30 秒电梯版**，或者扩成一个**带白板画图的 5 分钟深度版**（把"64 容量→填满→trySend 失败→丢"的时序画出来，面试官一秒就懂）？

### 🟡 Day 5 — Room 基础 + 表设计（第 5 章）(checked on x, 7.18)

| 时段 | 任务 | 产出 |
|------|------|------|
| 上午 3.5h | 补 Room 基础：Entity / DAO / Database 三件套。精读第 5 章 5.1~5.3，搞懂双表 + FK CASCADE + INSERT/UPDATE 流式存储 | 能画表结构图 |
| 下午 3.5h | **复现杀进程恢复**：发消息到一半，用 Android Studio 强杀进程，重开 App，看到 `is_partial` 消息显示"继续" | 亲眼验证"不丢消息" |

- **Done 标准**：能讲"流式消息怎么存的，为什么杀进程不丢"

### 🟡 Day 6 — Room 踩坑：复现 CASCADE 灾难（Bug 9.2，最有深度的故事）(checked on x, 7.18)

| 时段 | 任务 | 产出 |
|------|------|------|
| 上午 3.5h | **复现 Bug 9.2**：把 `upsertConversation` 改回用 `@Insert(REPLACE)`，发第二条消息，**亲眼看到历史消息全消失**。改回来 | 亲手踩过 CASCADE 的坑 |
| 下午 3.5h | 理透根因：SQLite REPLACE = DELETE+INSERT ≠ MySQL UPDATE。学数据库迁移（5.4）。把第 9.2 的"话术"背下来 | 能讲根因到 SQLite 算法级别 |

- **Done 标准**：能讲 5 分钟"`REPLACE` 为什么会删光消息"——这是你的杀手锏故事
  下面是**5 分钟面试版本**，你可以直接背这个结构。

**1. 先给结论**

（核心原因在于不能用replace来更新updated_at字段）
在我这个项目里，`REPLACE` 不能随便用在 `conversations` 表上，因为 `messages` 表通过外键依赖 `conversations.id`，而且设置了 `ON DELETE CASCADE`。SQLite 的 `REPLACE` 并不是单纯的 update，它的语义更接近：

```text
冲突了 -> 先删除旧行 -> 再插入新行
```

所以如果我对已有会话执行 `REPLACE`，SQLite 会先把旧的 conversation 删除。这个删除动作会触发外键级联，于是这条会话下面的所有 messages 都会被删掉。然后再插入新的 conversation，结果就是：会话还在，但消息全没了。

**2. 结合项目表结构讲**

我项目里有两张核心表：

```text
conversations
- id 主键
- title
- created_at
- updated_at
- is_pinned

messages
- id 自增主键
- conversation_id 外键，指向 conversations.id
- role
- content
- timestamp
```

`messages.conversation_id` 是子表字段，外键指向 `conversations.id`，并且配置了：

```kotlin
onDelete = ForeignKey.CASCADE
```

这代表：只要父表某个 conversation 被删除，Room/SQLite 会自动删除它下面所有 message。这在用户主动删除会话时是好事，因为可以自动清理消息；但如果我们只是想“更新会话标题或更新时间”，却用了 `REPLACE`，那就会误触发这个删除逻辑。

**3. 解释 REPLACE 的坑**

很多人以为 `REPLACE` 等于：

```sql
UPDATE conversations SET ...
```

但 SQLite 里不是这样。`REPLACE` 是冲突解决策略，遇到主键或唯一键冲突时，它会删除冲突旧行，再插入新行。

所以对已有会话：

```text
REPLACE conversation(id = "session_123")
```

实际效果类似：

```sql
DELETE FROM conversations WHERE id = 'session_123';
INSERT INTO conversations (...) VALUES (...);
```

中间那一步 `DELETE` 会触发：

```text
messages 表里 conversation_id = session_123 的所有消息被级联删除
```

所以最后表现就是“会话元信息更新成功了，但聊天记录空了”。

**4. 我项目里的正确做法**

所以我现在的 `upsertConversation()` 没有用 `REPLACE`。

逻辑是：

```kotlin
val existing = conversationDao.getById(conversationId)
if (existing != null) {
    conversationDao.updateMeta(conversationId, title, System.currentTimeMillis())
} else {
    conversationDao.insert(...)
}
```

也就是：

第一次发送消息时：
```text
先 insert conversation 父表
再 insert user message 子表
```

后续继续发送消息时：
```text
只 update conversation 的 title / updated_at
再 insert 新 message
```

这里的重点是：**已有会话只做 UPDATE，不做 REPLACE**。UPDATE 不会删除父表行，所以不会触发 `ON DELETE CASCADE`，消息就安全。

**5. 面试升华一句**

这个问题本质上不是 Room 的 bug，而是数据库约束和冲突策略之间的语义问题。`ON DELETE CASCADE` 本身没错，删除会话时它能帮我们自动清理消息；错的是在“更新父表元信息”的场景下使用了带删除语义的 `REPLACE`。所以工程上应该区分：

```text
新增：INSERT IGNORE
更新：UPDATE 指定字段
删除：显式 DELETE，让 CASCADE 生效
```

这也是为什么我项目里 `ConversationDao.insert()` 用的是 `OnConflictStrategy.IGNORE`，已有会话则用 `updateMeta()`，避免 `REPLACE` 把聊天消息级联删光。

### 🟡 Day 7 — Compose 基础 + 状态机（第 6 章）(checked on x, 7.19)

| 时段 | 任务 | 产出 |
|------|------|------|
| 上午 3.5h | 补 Compose 心智模型：声明式 vs 命令式、状态、重组。如果《第一行代码》是 XML 版，这关要专门过 | 能说清"状态怎么驱动 UI" |
| 下午 3.5h | 精读第 6 章 6.2~6.3。搞懂 `StateFlow` + `sealed class` 状态机、`LazyColumn` 流式滚动 | 能讲 StateFlow vs LiveData |

- **Done 标准**：能回答"Compose 重组是什么""为什么用 sealed class 建状态机"
  可以，下面是稍微长一点、面试里能讲 3-5 分钟的版本。

**Compose 重组是什么**

Compose 是声明式 UI。传统 View 体系里，我们经常是拿到一个控件引用，然后手动改它：

```kotlin
textView.text = "加载失败"
progressBar.visibility = View.GONE
```

但 Compose 不是这种思路。Compose 更像是：

```text
当前状态是什么，界面就应该长什么样
```

也就是：

```text
UI = f(state)
```

我们写的 Composable 函数，本质上是在描述“当前状态下 UI 应该是什么样”。

比如在我的项目里，聊天页会读取 ViewModel 暴露出来的状态：

```kotlin
val streamState by viewModel.streamState.collectAsStateWithLifecycle()
```

这一步会把 ViewModel 里的 `StateFlow<StreamState>` 接入 Compose。之后当 ViewModel 改变状态：

```kotlin
_streamState.value = StreamState.Error(...)
```

`StateFlow` 会发射新值，`collectAsStateWithLifecycle()` 收到新值后，会把它变成 Compose 可观察的 State。Compose 发现某些 Composable 读取过这个 `streamState`，就会让这些相关 Composable 重新执行一遍，这个过程就是重组。

重组可以理解成：

```text
State 变化
-> Compose 记录到哪些地方读过这个 State
-> 让相关 Composable 函数重新执行
-> 根据新的 State 生成新的 UI 描述
-> 和之前的 UI 描述进行比较
-> 尽量只更新真正变化的部分
```

它不是 Activity 重启，也不是整个页面所有控件重新创建。Compose 有 Slot Table 和重组范围的概念，会尽量做到精准重组。比如一个页面里同时有消息列表、输入框、错误提示、工具调用面板。如果只是 `streamState` 从 `Streaming` 变成 `Error`，那主要影响的是读取 `streamState` 的错误提示区域，而不是每一条消息气泡都要重新生成。

结合我项目里的例子：

```kotlin
when (val s = streamState) {
    is StreamState.Error -> if (s.retryable) {
        PartialMessageBanner(...)
    } else {
        Snackbar(...)
    }

    else -> {}
}
```

这里的 `when` 本身不是监听器。真正监听状态变化的是：

```kotlin
collectAsStateWithLifecycle()
```

当 `streamState` 变化时，Compose 触发相关区域重组。重组时这段 `when` 重新执行，然后根据新的状态决定显示哪个组件。

比如：

```text
streamState = Idle
-> when 走 else
-> 不显示错误组件

streamState = Error(retryable = true)
-> when 走 Error 分支
-> 显示 PartialMessageBanner

streamState = Error(retryable = false)
-> when 走 Error 分支
-> 显示 Snackbar
```

所以我对 Compose 重组的理解是：它是声明式 UI 的核心机制。开发者只需要维护状态，Compose 负责在状态变化后重新执行依赖该状态的 UI 函数，并把界面更新到和新状态一致。

面试里可以总结成：

```text
Compose 重组就是当 State 变化时，Compose 重新执行读取了这个 State 的 Composable 函数，重新计算当前状态下的 UI 描述，然后尽量只把真实 UI 中发生变化的部分更新出来。它不是全页面刷新，而是基于状态读取关系的精准更新机制。
```

Compose 的重组不是重新刷新整个页面，而是基于 State 读取关系进行精准更新。Compose Runtime 会记录 Composable 对 State 的依赖关系，当 State 变化时，只会标记读取该 State 的 Composable 进行重组。重组过程中，Compose 会重新执行这些 Composable 生成新的 UI 描述，然后通过 Slot Table 保存的节点信息进行比较，只更新发生变化的节点，而不是重新创建整个 UI。

**为什么用 sealed class 建状态机**

我用 `sealed class` 建状态机，主要是因为很多业务状态是有限且互斥的。

比如聊天流式生成这件事，它同一时间只能处于某一种状态：

```text
空闲 Idle
生成中 Streaming
生成完成 Completed
生成失败 Error
```

它不应该同时既是生成中，又是失败，又是完成。

如果用多个 Boolean 表示，就容易出现非法组合：

```kotlin
val isStreaming: Boolean
val isCompleted: Boolean
val isError: Boolean
val errorMessage: String?
```

这时理论上可能出现：

```text
isStreaming = true
isCompleted = true
isError = true
```

这种状态就很混乱。UI 到底应该显示加载中、成功结果，还是错误提示？代码里就会充满各种互斥判断和兜底逻辑。

用 `sealed class` 会更自然：

```kotlin
sealed class StreamState {
    data object Idle : StreamState()
    data object Streaming : StreamState()
    data class Completed(
        val fullAnswer: String,
        val route: String?
    ) : StreamState()

    data class Error(
        val message: String,
        val partial: Boolean = false,
        val retryable: Boolean = false,
        val partialAnswer: String = "",
        val lastQuestion: String = ""
    ) : StreamState()
}
```

这样 `streamState` 同一时间只能是这些类型中的一个：

```kotlin
private val _streamState = MutableStateFlow<StreamState>(StreamState.Idle)
```

如果它是 `Streaming`，那就不可能同时又是 `Error`。状态本身就被类型系统约束住了。

第二个好处是，每个状态可以携带自己需要的数据。

比如：

```kotlin
Idle
```

不需要任何数据。

```kotlin
Streaming
```

可能也不需要额外数据，只代表正在生成。

但：

```kotlin
Completed(
    fullAnswer = finalAnswer,
    route = currentRoute
)
```

完成状态需要携带完整回答和路由信息。

```kotlin
Error(
    message = "连接中断",
    partial = true,
    retryable = true,
    partialAnswer = partial,
    lastQuestion = lastQuestion
)
```

错误状态需要携带错误信息、是否有部分回答、是否能重试、上一次问题是什么。

如果不用 sealed class，这些字段可能都要放在一个大 `data class` 里，然后大量字段变成 nullable：

```kotlin
data class UiState(
    val isError: Boolean,
    val errorMessage: String?,
    val partialAnswer: String?,
    val fullAnswer: String?,
    val route: String?
)
```

但这些字段并不是每个状态都有效。比如 `Idle` 状态下 `errorMessage` 没意义，`Error` 状态下 `fullAnswer` 可能也没意义。这样就很容易写出“状态和值不匹配”的代码。

`sealed class` 可以让数据跟状态绑定在一起：

```text
Error 状态才有 errorMessage
Completed 状态才有 fullAnswer
```

第三个好处是配合 `when` 使用时有穷举检查。

比如：

```kotlin
when (val s = streamState) {
    is StreamState.Idle -> {}
    is StreamState.Streaming -> {}
    is StreamState.Completed -> {}
    is StreamState.Error -> {}
}
```

因为 `StreamState` 是 sealed class，编译器知道它所有可能的子类。如果我以后新增一个状态：

```kotlin
data object Paused : StreamState()
```

编译器可以提醒我：有些 `when` 没处理 `Paused`。这在 UI 状态机里特别有用，因为新增状态时不容易漏掉某个 UI 分支。

结合 Compose，它还有一个非常顺的搭配方式：

```kotlin
when (val s = streamState) {
    is StreamState.Streaming -> LoadingIndicator()
    is StreamState.Error -> ErrorBanner(s.message)
    is StreamState.Completed -> ResultPanel(s.fullAnswer)
    is StreamState.Idle -> {}
}
```

这正好符合 Compose 的声明式思路：

```text
不同状态 -> 不同 UI
```

所以 sealed class 负责把状态建模清楚，Compose 负责根据状态渲染 UI。

面试里可以这样总结：

```text
我用 sealed class 建状态机，是因为 UI 状态通常是有限集合，并且同一时间互斥。sealed class 可以从类型层面保证当前状态只能是其中一种，避免多个 Boolean 组合出非法状态。同时每个状态可以携带自己专属的数据，比如 Error 携带错误信息和是否可重试，Completed 携带完整结果。再配合 when 使用时，编译器能做穷举检查，新增状态时能提醒我补齐 UI 分支。它和 Compose 的声明式 UI 很搭，因为 Compose 本来就是根据当前状态选择渲染不同界面。
```

最后给你一个更完整的面试串联版：

```text
在我的项目里，聊天页的流式生成状态用 StateFlow<StreamState> 暴露给 Compose。
ChatScreen 通过 collectAsStateWithLifecycle 收集这个状态。
当 ViewModel 把状态从 Streaming 改成 Error 时，StateFlow 发射新值，Compose 感知到读取了 streamState 的 UI 区域需要重组，于是重新执行对应的 Composable。
重组时 when(streamState) 会重新判断当前状态，如果是可重试错误就显示 PartialMessageBanner，如果是不可重试错误就显示 Snackbar。

这里的 StreamState 我用 sealed class 表示，因为流式生成状态是有限且互斥的：Idle、Streaming、Completed、Error 同一时间只能有一个。
sealed class 能避免多个 Boolean 带来的非法组合，也能让每个状态携带自己需要的数据，并且配合 when 有编译期穷举检查。整体上就是：ViewModel 管状态，sealed class 保证状态模型清晰，Compose 根据状态变化自动重组并渲染对应 UI。
```
对，就结合“AI 正在生成内容，聊天列表要自动滚到底”来理解 `LaunchedEffect`，会非常好懂。

先给结论：

```text
重组负责“根据状态重新描述 UI”；
LaunchedEffect 负责“当某个状态变化后，执行一次副作用”。
```

滚动到底不是单纯画 UI，它是一个动作：

```text
让 LazyColumn 滚到某个位置
```

这种动作就属于副作用，所以适合放进 `LaunchedEffect`。

在你的项目里，核心代码是这两段：

```kotlin
LaunchedEffect(messages.size, hasPendingMessageTarget, hasStreamingIndicator, hasToolPanel) {
    if (messages.isEmpty() || hasPendingMessageTarget) return@LaunchedEffect

    if (!initialScrollCompleted) {
        listState.scrollToBottom(bottomAnchorIndex, animated = false)
        initialScrollCompleted = true
    } else if (autoFollow) {
        listState.scrollToBottom(bottomAnchorIndex, animated = true)
    }
}
```

还有这一段：

```kotlin
LaunchedEffect(messages.lastOrNull()?.content) {
    if (isStreaming && initialScrollCompleted && autoFollow) {
        listState.scrollToBottom(bottomAnchorIndex, animated = false)
    }
}
```

我们从头串一下。

用户发送消息后，ViewModel 会把用户消息存进 Room。Room 的 `Flow` 发出新的消息列表，`ChatScreen` 收到新的 `messages`。

然后：

```text
messages 变化
-> ChatScreen 重组
-> LazyColumn 根据新的 messages 重新显示消息
```

这一步只是“新消息画出来”。

但画出来之后，还要做一件额外的事：

```text
把列表滚到底
```

这个“滚到底”不是 UI 描述，而是命令式动作，所以不能只靠普通的 `if` 写在 Composable 里。因为 Composable 可能因为很多原因反复重组，如果你在函数体里直接滚动，就可能每次重组都乱滚。

所以用：

```kotlin
LaunchedEffect(messages.size, ...)
```

意思是：

```text
当 messages.size 等 key 发生变化时，启动一个协程，执行里面的滚动逻辑。
```

这里的 key 很重要。

```kotlin
LaunchedEffect(messages.size)
```

可以理解成：

```text
消息数量变了，我才执行一次
```

比如：

```text
原来 3 条消息
用户发了一条，变成 4 条
-> messages.size 从 3 变 4
-> LaunchedEffect 触发
-> scrollToBottom()
```

如果只是普通重组，但 `messages.size` 没变，这个 effect 不会重新执行。

你的项目里之所以不只监听 `messages.size`，还监听：

```kotlin
hasStreamingIndicator
hasToolPanel
```

是因为底部不一定只有消息。

生成中可能有：

```text
消息列表
+ 正在生成指示器
+ 工具调用面板
+ 底部锚点
```

所以滚动目标不是简单的最后一条消息，而是：

```kotlin
val bottomAnchorIndex = messages.size +
    (if (hasStreamingIndicator) 1 else 0) +
    (if (hasToolPanel) 1 else 0)
```

也就是说，你项目里专门放了一个“底部锚点”。滚到底不是滚到最后一条消息的顶部，而是滚到真正的底部。这样如果最后一条 assistant 回答很长，也不会只把长消息的开头滚出来，而是能定位到它的末尾附近。

再看流式生成这个细节。

AI 回答不是一次性出来的，而是一段一段 chunk 出来。

第一次 assistant 内容来了，会插入一条 assistant 消息：

```text
messages.size 变化
-> 第一段 LaunchedEffect 触发
-> 滚到底
```

但后面继续生成时，通常不是新增一条消息，而是更新同一条 assistant 消息的 `content`：

```text
第 1 秒：你好
第 2 秒：你好，我是
第 3 秒：你好，我是 LabSage
```

这时候：

```text
messages.size 没变
```

因为还是同一条 assistant 消息，只是内容变长了。

如果你只写：

```kotlin
LaunchedEffect(messages.size)
```

那么后续内容越来越长时，不会继续滚到底。用户可能看到回答的上半截，下面新生成的内容跑到屏幕外了。

所以你的项目又加了第二个 effect：

```kotlin
LaunchedEffect(messages.lastOrNull()?.content) {
    if (isStreaming && initialScrollCompleted && autoFollow) {
        listState.scrollToBottom(bottomAnchorIndex, animated = false)
    }
}
```

这段意思是：

```text
当最后一条消息的内容变化时，如果当前正在生成，并且允许自动跟随，就滚到底。
```

也就是专门处理：

```text
同一条 assistant 消息内容不断变长
```

这个场景。

为什么这里用：

```kotlin
animated = false
```

因为流式生成时 content 可能变化非常频繁。如果每个 chunk 都开一个动画：

```text
动画 1 还没结束
动画 2 又来了
动画 3 又来了
```

滚动会变得抖，甚至堆很多动画。所以这里用瞬间定位：

```kotlin
scrollToItem()
```

而不是连续动画：

```kotlin
animateScrollToItem()
```

项目里的封装是：

```kotlin
private suspend fun LazyListState.scrollToBottom(
    bottomAnchorIndex: Int,
    animated: Boolean
) {
    if (animated) animateScrollToItem(bottomAnchorIndex)
    else scrollToItem(bottomAnchorIndex)
}
```

注意它是 `suspend fun`，所以也解释了为什么要放进 `LaunchedEffect`：

```text
scrollToItem / animateScrollToItem 是挂起函数
需要在协程里调用
LaunchedEffect 正好会在 Compose 里启动协程
```

再说 `autoFollow`，这个是体验上的关键。

如果用户一直在底部看生成内容，那新内容出来时自动滚到底是合理的。

但如果用户手动往上滑，想看前面的内容，这时你不能每来一个 chunk 就把他强行拽回底部。否则体验很糟糕。

所以项目里有：

```kotlin
val isUserDragging by listState.interactionSource.collectIsDraggedAsState()

LaunchedEffect(isUserDragging) {
    if (!isUserDragging) autoFollow = isAtBottom
}
```

意思是：

```text
用户拖动结束时，判断他是不是还在底部。
如果还在底部，autoFollow = true，后续生成继续自动跟随。
如果不在底部，autoFollow = false，说明用户在看历史内容，后续不要强行滚到底。
```

所以完整链路是：

```text
AI 生成新 chunk
-> ViewModel 更新 assistant message content
-> Room Flow 发出新 messages
-> ChatScreen 收到 messages
-> Compose 重组，消息文本变长
-> messages.lastOrNull()?.content 这个 key 变化
-> LaunchedEffect 触发
-> 判断 isStreaming && initialScrollCompleted && autoFollow
-> 如果用户允许跟随，就 listState.scrollToBottom()
```

面试里可以这样讲：

```text  ize 变化，我用 LaunchedEffect(messages.size, ...) 在新增消息或底部面板变化时滚到底。但后续 chunk 只是更新最后一条 assistant 消息的 content，messages.size 不会变，所以我又用 LaunchedEffect(messages.lastOrNull()?.content) 监听最后一条消息内容变化，在 streaming 且 autoFollow 为 true 时继续滚到底。

这里用 LaunchedEffect 是因为滚动不是 UI 声明本身，而是一个副作用，而且 scrollToItem/animateScrollToItem 是 suspend 函数，需要在协程里执行。LaunchedEffect 会随着 key 变化启动协程，非常适合把 Compose 状态变化转换成滚动动作。同时我用 autoFollow 和 isUserDragging 判断用户是否主动上滑，避免用户看历史消息时被新 chunk 强行拉回底部。
```

最短背诵版：

```text
LaunchedEffect 的作用是：当 key 对应的 Compose 状态变化时，启动协程执行一次副作用。聊天页里滚动到底就是副作用。新增消息时监听 messages.size，流式生成时监听最后一条消息 content，因为后续 chunk 只改内容不增消息。然后结合 autoFollow 判断用户是否还想跟随底部，避免用户上滑阅读时被强行滚回去。
```

### 🟡 Day 8 — Compose 性能优化（第 6.4 章，亲眼见才有底气）

| 时段 | 任务 | 产出 |
|------|------|------|
| 上午 3.5h | 学四层优化：`conflate` / 滚动策略 / `@Immutable` / `flowOn` | 懂每层堵什么口 |
| 下午 3.5h | **开 Layout Inspector（或 Compose 重组计数器）**，临时去掉 `.conflate()`，看重组次数飙升；加回来，看降到 1/5 | 亲眼见过 50/s → 10/s |

---
先理清:conflate 在哪一层起作用

Room emit (~50/s) ──→ .conflate() ──→ collect{ _messages.value = … } ──→ Compose 重组                                                                                                                                                                                                                                     
↑ 它在这        ↑ 1 次 set = 1 次重组触发



- conflate 真正掐的是 collect 的触发频率(从 ~50/s 降到 ~10/s)。
- Compose 重组数只是 collect 把 _messages.value 一设的下游后果,1:1 跟着走。
- 所以最忠实地测 conflate 本身,是测 collect 触发率;测 Compose 重组是看下游视觉效果。两个都值得做。
---
- **Done 标准**：能讲"你做了哪些 Compose 性能优化，怎么验证的"
- 🔑 "我用工具测过重组从 50 降到 10"——这句话面试时含金量极高


---

### 🟡 Day 9 — 网络层 + DI（第 7 章）（checked on x, 7.19）

| 时段 | 任务 | 产出 |
|------|------|------|
| 上午 3.5h | 精读第 7 章：三客户端共享连接池（`newBuilder`）、`readTimeout=0`、后端地址热切换（按 URL 缓存） | 能讲为什么三客户端、为什么热切换这么设计 |
| 下午 3.5h | 在设置页**改后端地址，不重启 App 就生效**，对照 `networkLayerFor` 代码理解 | 能讲"lazy 单例为什么不能做热切换" |

- **Done 标准**：能讲 DI 为什么手动不用 Hilt、连接池为什么共享
  读了 `LabSageApplication.kt`,两个问题的事实都对上了。下面给面试版回答 —— 先一句话结论,再展开,最后补"反向诚实"和"深度加分"。

---
### Key：工厂设计模式在项目中的应用：
```kotlin
    fun chatViewModelFactory(conversationId: String): ViewModelProvider.Factory {
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                    return ChatViewModel(
                        repository,
                        settingsRepository,
                        responseNotificationManager,
                        conversationId
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
            }
        }
    }
```
因为不同会话的会话ID不同，以次来创建不同会话的ViewModel
- 但是repo是相同的
---

## 1. 为什么手动 DI,不用 Hilt?

> 代码:`LabSageApplication.kt:46-241` —— 整个 app 就一个手动 DI 容器;全仓 `grep @HiltViewModel/@Inject` 零命中。

**一句话**:当前 app 依赖图小、单人开发,手动 `by lazy` + `ViewModelProvider.Factory` 一屏代码搞定,Hilt 那套注解 + 编译期代码生成的收益盖不过成本;而且手动链路**显式、肉眼可追**,对学习/面试阶段更有价值。

**展开三个理由**:

1. **依赖图简单,框架不值当。** 整张图就 Application 里一屏 —— `Gson` / `OkHttpClient` / `Retrofit` / `Room` / 两个 Repository / 几个 ViewModel,构造链线性可见(`ChatViewModel(repository, settingsRepository, ...)` 谁传谁一目了然)。Hilt 要 `@HiltAndroidApp` + `@AndroidEntryPoint` + `@HiltViewModel` + `@Module/@InstallIn/@Provides` + KSP 编译期处理,**样板没省多少,构建复杂度和学习成本实打实加上去了**。

2. **显式 = 可调试。** 手动构造链是线性的,出问题直接点断点跟 `Factory.create()` → Application 的 `by lazy`。Hilt 的依赖图是**编译期生成的代码**,真出问题(尤其 `@ViewModelScoped` 边界、跨 Component 注入失败、缺失 binding)排查链路长很多。

3. **零魔法 = 讲得清。** 我能讲清"ViewModel 怎么拿到 Repository"的完整链路:`ViewModelProvider` 拿 `Factory` → `create()` 里手动 new → Repository 在 Application `by lazy` 构造 → 内部 `database.messageDao()`。Hilt 把这层藏进 `@Inject constructor`,**学习阶段我宁可先手搓一遍把链路焊死**。

**反向诚实(关键加分项 —— 证明不是"不懂才不用")**:
- 手动 DI 的痛点恰恰是 Hilt 要解决的:**样板代码**(我那 4 个 `ViewModelProvider.Factory` 几乎长得一模一样,已经是坏味道了)、**依赖图靠人记忆**、**跨模块注入要手写传递**。
- 当规模到 **多模块 / 团队 / 十几个 ViewModel**,trade-off 就翻转 —— 那时 Hilt 的价值(编译期校验依赖图完整性、跨模块注入、消除样板)才真正兑现。
- 所以结论不是"Hilt 不好",是 **"当前规模不需要"**;换规模我会换选择。

> 面试官追问"那 ViewModelProvider.Factory 重复怎么解决?" → 可以提取一个泛型 factory(`viewModelFactory { initializer { ... } }`,Jetpack 2.5+ 的 CreationExtras 方案),手动 DI 也能去重。

---

## 2. 连接池为什么共享?

> 代码:`LabSageApplication.kt:95-119` —— `baseOkHttpClient` 是基础 client,`apiOkHttpClient` / `sseOkHttpClient` 都从它 `newBuilder()` 派生。

**事实先摆清(这是"为什么能共享")**:
`newBuilder()` 的语义是**浅拷贝**:引用类型字段(`connectionPool`、`dispatcher`、`interceptors` 列表)**共享同一个实例**,基本类型(`timeout`、`retryOnConnectionFailure`)才是值拷贝。所以两个 client 配置不同、但**底层共用一个 `ConnectionPool`**。

**为什么要共享(三个理由,按重要性)**:

1. **HTTP keep-alive 连接复用 —— 核心。** 两个 client 打同一个后端 host。Retrofit 短请求结束 → 连接归还池子(标记空闲,默认保活 5 分钟 / 5 条),SSE 或下一次请求命中**同 host** 时**直接复用已建立的 TCP/TLS 连接,省掉握手**(TLS 握手尤其贵,2~3 个 RTT)。**不共享 = 两个池子各自开连接,keep-alive 复用率打骨折。**

2. **统一资源上限。** 连接池默认 5 条空闲连接 / 5 分钟 keep-alive。共享 = 整个 app 对后端的空闲连接受一个池子约束;**不共享 = 两个池子各 5 条,连接和内存翻倍,后端看到的连接数也翻倍。**

3. **统一生命周期 / 监控。** 一个池子好清理(OkHttp 的 `EvictingConnectionPool` 后台清空闲连接的周期是全局的)。

**为什么先得有两个 client(共享池的前提是配置不同)**:
- **超时不同**:api 30s readTimeout;sse `readTimeout=0`(SSE 长连接几十秒不来数据正常,设超时会被当死连接掐 —— CLAUDE.md 里有记)。
- **日志不同**:api 可以 BODY;sse **必须 HEADERS** —— BODY 级日志会缓冲整个无限长响应体,`onEvent` 永不触发。
- 这俩都是 **client 级配置,没法用一个 client**。

所以"**两个 client + 一个共享池**" = 同时要"配置不同"和"连接复用",`newBuilder()` 正好给了"复制配置 + 共享池"的语义。

**深度加分(把原理咬合关系讲出来)**:
- `newBuilder()` 共享的不止 `ConnectionPool`,还有 **`Dispatcher`**(所以全局并发上限 `maxRequests=64` / `maxRequestsPerHost=5` 也是统一的)、interceptors 列表引用。
- 连接池池如果不变，换不同的host对是共享这个池，只不过里面的连接对象要同时看ip+端口。**共享的前提是同源**:连接是按 `Address`(host+port+TLS 设置)缓存的,host 不同池子虽共享但连接不互通。本项目 api 和 sse 用同一个 base URL,**所以共享才真正生效** —— 这是容易被忽略的点,面试官爱挖。
- 对比反例:`SettingsViewModel.kt:82` 里**单独 new 了一个 `OkHttpClient`** 去做"测试连接"探活 —— 它没进共享池,但那是一次性探活请求,不需要复用,反而避免污染主池,**这是有意的取舍**。

---
### 🟡 Day 10 — 其它功能 + 工具面板（第 8 章）(checked)

| 时段 | 任务 | 产出 |
|------|------|------|
| 上午 3.5h | 过第 8 章：语音输入（权限）、会话组织（搜索/置顶/迁移）、设置页（DataStore）、文件上传、网络监控 | 每个功能能讲一句话 |
| 下午 3.5h | 精读**工具执行面板**：`applyToolTrace` 的 FIFO 配对算法。**复现 Bug 9.4**（改回取"第一个 token"当名字，看卡片显示中断） | 能讲工具调用怎么配对、为什么 FIFO |

- **Done 标准**：能讲 DataStore vs SharedPreferences、工具面板配对逻辑
  两个话题都准备好了。下面是"能对着面试官讲"的版本——先结论、再原理、再落到项目代码。

---

# 一、DataStore vs SharedPreferences

## 一句话定位
> **SP 是同步阻塞 + 无事务 + 无类型安全的旧方案；DataStore 是异步、事务化、可观测、官方钦定的替代品。新项目用 DataStore，SP 只在历史包袱里。**

## 五个维度对比（面试主干）

| 维度         | SharedPreferences                                                             | DataStore (Preferences)                                                          |
| ------------ | ----------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| **读**       | **同步阻塞主线程**——首次 `getSharedPreferences` 把整个 XML 同步读进内存做 Map | `data: Flow<T>`，suspend/异步，内部走 `Dispatchers.IO`                           |
| **写**       | `apply()` 异步落盘 / `commit()` 同步                                          | `suspend edit {}`，异步、IO 线程                                                 |
| **事务**     | ❌ 无——"最后写入获胜"，崩在两次写之间会留半写 XML                             | ✅ 每次 `edit{}` 一个原子事务，写临时文件 + rename，崩不损坏                     |
| **类型安全** | ❌ `getString(key, default)` 靠强转+默认值，key 拼错运行时才炸                | 🔶 `stringPreferencesKey()` 带类型 key；**Proto DataStore** 才是真编译期类型安全 |
| **错误恢复** | 读损坏 XML 直接异常，无钩子                                                   | `Flow.catch{}` 可拦 `IOException` 回退默认值，自愈                               |
| **可观测**   | `OnSharedPreferenceChangeListener` 回调，要手动注销防泄漏                     | 天然 Flow，collect/StateFlow 驱动 UI                                             |

## 原理层深度（加分项，区分背诵和理解）

1. **SP 导致 ANR 的两条路径**——这是面试杀手锏：
  - 读：主线程同步 IO，启动时读大 SP 直接卡。
  - 写：`apply()` 看似异步（丢到 `QueuedWork` 的后台线程），但**进程退出时 `ActivityThread` 会调 `QueuedWork.waitToFinish()` 阻塞等磁盘写完**。写还没落盘就按返回键退出 → ANR。这条很多人不知道。

2. **DataStore 怎么保证线程安全**：actor 模型——所有 `edit` 通过 Channel 投递到**单线程串行执行**，天然互斥不用锁。所以单实例随便并发写都安全。

3. **多进程**：SP 的 `MODE_MULTI_PROCESS` 已废弃且不可靠；普通 Preferences DataStore **也不支持多进程**，要 `MultiProcessDataStore`（加 datastore-core handler）。别以为换 DataStore 就多进程安全了。

## 取舍（何时用什么）
- 扁平 KV 配置、要响应式、要异步 → **DataStore Preferences**（项目就是这个）
- 强类型/复杂对象/大 → **Proto DataStore**
- 关系型、要查询 → **Room**（DataStore 不是数据库）
- SP：官方建议迁移，DataStore 提供 `SharedPreferencesMigration` 平滑迁移 API

## 项目落地（`SettingsRepository.kt`）
```kotlin
// 进程内单例委托
private val Context.settingsDataStore by preferencesDataStore(name = "labsage_settings")

// 读：Flow + catch 自愈
val settings: Flow<AppSettings> = context.settingsDataStore.data
    .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }  // 损坏回退默认
    .map { prefs -> AppSettings(baseUrl = prefs[Keys.BASE_URL] ?: DEFAULT, ...) }

// 写：suspend 事务
suspend fun setApprovalMode(enabled: Boolean) {
    context.settingsDataStore.edit { it[Keys.APPROVAL_MODE] = enabled }
}
```
注释里一句话点透了选型理由：*"flat settings record，Room overkill"*——配置是扁平 KV 没有查询需求，用 Room 杀鸡用牛刀。

**可能追问「为什么不用 SP」**：你这个项目要响应式驱动 Compose（`settings` Flow 直接 collect 进 ViewModel 的 StateFlow），SP 的回调式监听接 Compose 很别扭；而且 `apply()` 的 ANR 隐患在长连接 SSE 应用里更敏感。

---

# 二、工具面板配对逻辑

就说：后端发回来完整的工具调用链条，每个调用包含，起始与结束对，以及完整的调用信息（函数签名、工具函数传入的参数以及完整的返回值，这些都不需要），我们只要[tool], [route]这类关键的链路信息。

## 三态状态机 + 兜底
`CALLING → COMPLETED` 是正常路径，`ERROR` **不是后端发的**，是客户端兜底推断的：
```kotlin
// 流结束时还有 CALLING 的 → 调了没回完 = 中断
if (_toolCalls.value.any { it.status == CALLING }) {
    _toolCalls.value = _toolCalls.value.map {
        if (it.status == CALLING) it.copy(status = ERROR) else it
    }
}
```
把"网络中断/后端异常没发完成事件"转成用户可读的「中断」状态——**网络层的不确定性被状态机吸收成确定 UI 态**。
好，这段我拆开慢慢讲。先建直觉，再讲代码,最后给你一个我读代码时发现的细节。

## 1. 先搞懂"状态机"是啥

别被名字唬住。状态机就是:**一个东西在不同时刻处于不同"状态",状态之间按规则切换**。举个例子,你点外卖:

- 刚下单 → **配送中**(骑手取货了,但你不知道结局)
- 送达 → **已完成**
- 超时关店也没送到 → **异常**

这就是一个三态状态机。"配送中"是中间态(悬而未决),"已完成"和"异常"是**终态**(不会再变了)。

工具面板里每张卡片就是一个这样的小状态机,三个状态:

| 状态        | 现实含义                  | 谁触发                              |
| ----------- | ------------------------- | ----------------------------------- |
| `CALLING`   | 工具正在执行,还没返回结果 | 后端发 `开始调用` 事件 → 进这个态   |
| `COMPLETED` | 工具正常跑完了            | 后端发 `调用完成` 事件 → 进这个态   |
| `ERROR`     | 工具没正常跑完(中断了)    | **后端从不发这个** → 客户端自己推断 |

## 2. 状态怎么流转

```
          (后端: 开始调用)
                ↓
            ┌─────────┐  (后端: 调用完成)   ┌────────────┐
            │ CALLING ├───────────────────→│ COMPLETED  │  ← 终态
            └────┬────┘                     └────────────┘
                 │
                 │  流都结束了,你还卡在 CALLING?
                 │  → 客户端推断:你失败了
                 ↓
            ┌─────────┐
            │  ERROR  │  ← 终态
            └─────────┘
```

注意两条转移线都从 `CALLING` 出发。`COMPLETED` 和 `ERROR` 都是**死胡同**(到了就不变了)。

## 3. 重点:为什么需要"兜底"

`COMPLETED` 那条线没问题——后端发了完成事件,翻状态就好。

问题在 `ERROR`:**后端永远不会主动告诉你"这个工具失败了"**。就像骑手摔了不会主动打电话说"我送不到了"。那客户端怎么知道一个工具失败了呢?

**靠"流结束"这个隐式截止线来推断。**

SSE 流是有生命周期的:后端把该发的发完,会发一个 `Done` 事件表示"我说完了"。客户端的逻辑是:

> "既然整个流都结束了,那还没收到完成事件的工具,肯定就是没跑完——它们就是失败的。"

这就是"兜底"——**用流的生命周期,给所有悬而未决的工具调用,强行画一个句号**。把"不知道成没成功"的灰态(CALLING),变成一个确定的"失败"态(ERROR),好让 UI 显示「中断」而不是永远转圈。

用我上一条的话说:**网络层的不确定性被状态机吸收成确定 UI 态**。用户看到的永远是"完成/中断",不会看到"不知道"。

## 4. 逐行讲那段代码

```kotlin
// Any tool still CALLING when the stream ends did not finish.
if (_toolCalls.value.any { it.status == ToolCallStatus.CALLING }) {
    _toolCalls.value = _toolCalls.value.map {
        if (it.status == ToolCallStatus.CALLING) it.copy(status = ToolCallStatus.ERROR)
        else it
    }
}
```

**（sse done时）第一行 `if (...any {...})`** —— 先问一句:"列表里还有没有 CALLING 的?"
- `any{}` 是 List 的判断函数:只要有任意一个元素满足条件就返回 true。
- 这是个**短路**:如果所有工具都 COMPLETED 了,就没必要往下做。省一次遍历+重建列表。

**第二行 `_toolCalls.value = _toolCalls.value.map { ... }`** —— 把整个列表**映射**成一个新列表。
- 关键认知:`map` 不是"修改原列表",而是**造一个新 List**。每个元素过一遍大括号里的逻辑,结果拼成新列表。
- 为什么必须造新列表?因为 `_toolCalls` 是 `StateFlow<List<ToolCallUi>>`。StateFlow 要**发现"引用变了"才会通知 UI 重组**。如果你只改元素内部字段、List 还是同一个 List 对象,StateFlow 可能不通知。`map` 造新 List → 引用变了 → 一定触发重组。这点和你之前学的 Compose 重组/State 触发是一回事。

**第三行 `if (CALLING) it.copy(status=ERROR) else it`** —— 对每个元素决定怎么办:
- `it.copy(status = ERROR)`:data class 的 `copy`——**复制一个新对象,只改 status 字段,其余字段(key/name/args/duration)原样保留**。原对象不动。
- `else it`:已经 COMPLETED 的,**原样返回不动**。这句不能漏——否则把已完成的也改成 ERROR 就乱套了。

所以整段话翻译成人话:
> "流结束的时候,扫一遍所有工具卡片。凡是还卡在'调用中'的,统统改成'中断';已经完成的别动。然后把这份新列表塞回去触发 UI 更新。"

## 5. 一个容易忽略的点:兜底在 `Done` 分支

这段代码在 `SseEvent.Done` 分支里(324 行)。`Done` = **流正常关闭**,不是出错。也就是说:

> 即使后端"正常说完话"关了流,也可能有工具没回完成事件——比如后端 bug 漏发了完成事件、工具卡死后端强杀、网络丢了几条 trace。所以"正常关流"≠"所有工具都干净完成"。

兜底兜的正是这种**静默的未完成**——没有报错,但工具卡在 CALLING,用户会一直看到转圈。兜底把它变成明确的「中断」。

## 6. 我读代码时发现的一个细节(给你核实)

我顺手看了下 `Error` 分支(359 行)和 `handleStreamException`(391 行)——**它们只设置了 `_streamState = Error`,没有像 Done 那样把 CALLING 翻成 ERROR**。

也就是说,如果流是**报错**结束的(而不是正常 Done),那些还在 CALLING 的工具卡片会**继续显示"调用中"转圈**,不会变成「中断」,直到用户下次发消息触发 `resetAccumulators()` 把列表清空(`_toolCalls.value = emptyList()`)。

这算个**小不一致**:Done 路径兜底了,Error/异常路径没兜。后果不算严重(下次发消息会清),但在错误显示期间卡片是假死转圈的。你如果要完善,可以在 `handleStreamException` 末尾也加同样的翻转。这点你去核实下我说得对不对——如果属实,面试里讲"我读源码发现 Done 和 Error 两条退出路径对工具状态的处理不一致"也是个加分点,和你之前发现"apiClient 日志级别代码-注释不符"是同一类 review 敏锐度。

---

要不要我把"事件流配对 + 状态机兜底"这个设计模式(连同上面这个不一致发现)记进 `CLAUDE.md` 的学习进度?

## 配对 key 的设计
```kotlin
val key = "tc_${callNo}_${name}_${_toolCalls.value.size}"
```
三段：序号 + 名字 + 当前列表大小。`size` 这一段专门防**同名重调撞 key**——两次调 `gpu_query`，callNo 可能都 fallback 到同一个值，加 size 保证唯一，给 LazyColumn 当稳定 key。

## 配套设计（可迁移的思路）
1. **客户端解析、而非后端结构化**：后端把工具调用和自由日志混在 trace 流里，DataSource 层只 forward `[tool]/[react]/[route]` 三种前缀（`OkHttpSseDataSource` 的 keep 逻辑），避免 200+ 自由 trace 灌爆 UI。这是"在源头做协议裁剪"。
2. **纯内存态不落库**：`_toolCalls` 是 `StateFlow<List<ToolCallUi>>`，工具调用是**瞬态执行轨迹**不需要持久化——和消息正文不同（消息走双链架构落 Room）。状态语义决定存储策略。
3. **`@Immutable` + key 配精准重组**：`ToolCallUi` 标 `@Immutable`、LazyColumn 用 `call.key`，一条工具状态从 CALLING→COMPLETED 只重组那一张卡片，不重画整条时间线。

---

这两个话题对标校招：**DataStore vs SP** 是高频八股，但能讲出"SP 的 ANR 两条路径（读同步 + `QueuedWork.waitToFinish`）"和"DataStore actor 串行化"的原理层，就到 SP/SSP 档；**工具面板配对**是项目自带的"事件流配对 + 状态机兜底"设计模式，能讲清 FIFO 的 trade-off 和 #N 那个静默失败 bug，体现的是"在不对称协议上做健壮配对"的工程判断力。

### 🟡 Day 11 — 机动日 + 查漏（关键缓冲）

| 时段 | 任务 | 产出 |
|------|------|------|
| 全天 7h | **回头补没达标的 Day**。把 Day 1~10 里"Done 标准"做不到的，今天补齐 | 所有 Day 1~10 的 Done 标准全绿 |

- ⚠️ **如果今天还在补 Day 1~4 的基础**，说明进度严重落后，立刻砍第 10 章（其它功能只看不听），把时间压给 SSE/Room/Compose 三座大山
- 🔑 这一天是救命缓冲，**别用来赶新进度，用来夯实**

### 🔴 Day 12 — Bug 故事内化（第 9 章，9 个故事）

| 时段 | 任务 | 产出 |
|------|------|------|
| 上午 3.5h | 第 9 章 9 个 Bug，每个按"现象→根因→修复→收获"四段写自己的话（别抄文档） | 9 份自己的话术 |
| 下午 3.5h | 对着墙/录音，**脱稿讲每个 Bug**。卡壳的回去补 | 9 个故事能流利讲 |

- **Done 标准**：随便点一个 Bug，能讲 2 分钟不带停
- 🔑 "遇到过什么难题"几乎必问，这 9 个故事是你的弹药

### 🔴 Day 13 — 问答自测（第 11 章，25 题）

| 时段 | 任务 | 产出 |
|------|------|------|
| 上午 3.5h | 第 11 章 25 题，**遮住答案，口头答，录音** | 录音文件 |
| 下午 3.5h | 回放录音，标出卡壳/答错的，回去补对应章节 | 一份"薄弱清单" |

- **Done 标准**：25 题里至少 20 题能流畅答
- 🔑 录音回放最残忍也最有效——你会发现自己以为会的地方其实讲不清

### 🔴 Day 14 — 模拟面试 + 陈述

| 时段 | 任务 | 产出 |
|------|------|------|
| 上午 3.5h | 背第 12 章 2 分钟陈述 + 5 分钟深挖话术。练到自然，不背书感 | 2 分钟陈述能脱口而出 |
| 下午 3.5h | **找个人（或让 AI 当面试官）模拟面试 1 小时**，被追问到答不出就记录，回去补 | 一份"被问倒清单" |

- **Done 标准**：模拟面试能撑 40 分钟不崩

### 🔴 Day 15 — DDL：收口 + 查漏

| 时段 | 任务 | 产出 |
|------|------|------|
| 上午 3.5h | 补 Day 14 的"被问倒清单"。重看薄弱点 | 漏洞补上 |
| 下午 3.5h | **轻复习，不学新东西**。过一遍第 10 章选型表、第 12 章陈述、Bug 话术。早睡 | 心态稳 |

- ⚠️ 今天**别再啃新内容**，临阵磨刀只会增加焦虑。保持手感、睡好觉

---

## 2. 五个"必做实验"清单（面试底气的来源）

这五个实验**全部要亲手做过**，否则面试讲故事就是背的，一问细节就崩。分散在 Day 4/5/6/8/10：

- [ ] **删 `OkHttpSseDataSource` 重写一遍**（Day 4 上午）
- [ ] **复现 `trySend` 丢数据**：去 buffer+过滤，点 GPU 查询看输出中断（Day 4 下午）
- [ ] **复现 REPLACE CASCADE**：改回 `REPLACE`，发第二条消息看历史消失（Day 6 上午）
- [ ] **Layout Inspector 看 conflate 前后重组数**（Day 8 下午）
- [ ] **杀进程恢复**：流式中强杀进程，重开看 is_partial 续传（Day 5 下午）

---

## 3. 每周自测（判断是否跟上进度）

**Day 4 晚**（地基期结束）：能不能对着代码讲清"一条消息从发送到显示的完整链路"？讲不清 → Day 5 先别开 Room，回去补主线。

**Day 11 晚**（攻坚期结束）：SSE/Room/Compose 三座大山，能不能各讲 5 分钟？哪座讲不清，Day 11 机动日补哪座。

**Day 13 晚**：25 题自测，能不能答上 20 题？答不上 → Day 14 砍模拟面试时间补基础。

---

## 4. 红线：什么砍不得，什么可以砍

如果进度落后（连续两天没达 Done 标准），按优先级砍：

**🚫 砍不得（面试核心，必保）**
- SSE 流式（Day 3~4）——项目灵魂
- Room + CASCADE Bug（Day 5~6）——最有深度的故事
- Compose 状态机 + conflate（Day 7~8）——高频考点
- Bug 故事 + 陈述（Day 12~14）——临门一脚

**✂️ 可以砍（锦上添花）**
- 第 8 章"其它功能"（Day 10）——能讲一句话就行，不必深究
- 工具面板 FIFO 配对细节（Day 10 下午）——讲思路即可
- 数据库迁移、文件上传——知道有这回事就行

**💀 实在来不及（Day 13 还没学完三座大山）**
- 砍掉 Compose 性能优化的 `@Immutable`/`derivedStateOf` 细节，只留 `conflate`（这个最好讲）
- 砍掉工具面板，把时间全压给 SSE + Room + 主线陈述

---

## 5. 面试前一天（Day 14 晚）Checklist

- [ ] 2 分钟陈述背熟，能脱口而出
- [ ] 3 个杀手锏故事（SSE 背压、Room CASCADE、Markdown 版本冲突）能脱稿讲
- [ ] 第 10 章选型表过一遍（"为什么 X 不用 Y"）
- [ ] 简历项目描述用的是"独立开发"，**不是"精通"**
- [ ] 把 `INTERVIEW_GUIDE.md` 第 9、11、12 章存手机里，路上能瞄
- [ ] 11 点前睡

---

> **最后**：15 天 105 小时，足够你把这个项目讲到"面试官觉得你比简历写的懂得多"。别贪多求全，**三座大山（SSE/Room/Compose）+ Bug 故事 + 陈述**，这五样扎实了，剩下的都是加分项。每天结束前问自己一句："今天我亲手在 App 里做了什么？"——如果答案是"只看了文档"，那今天的 7 小时白费了一半。动手，动手，动手。🎯
