# LagSage 安卓文档

## 0. 简历版本

### LabSage｜实验室 AI 知识助手 Android 客户端

**独立负责 Android 客户端开发** ｜ [项目时间] ｜ [项目链接/演示视频]

面向实验室知识检索与环境查询场景，对接现有 Go AI 后端，独立完成原生 Android 客户端；支持 AI 流式问答、异常续答、工具调用展示、多会话与知识库文件管理、账号认证及跨端历史同步。

- 使用 **Kotlin + Jetpack Compose** 搭建客户端，按 **MVVM + Repository + 手动依赖注入** 组织网络、Room 与 UI；通过 `StateFlow` 和密封类统一管理流式状态，完成 Markdown 回答、语音输入、工具调用时间线、主题切换等功能。
- 优化 **SSE 流式回答链路**：将高频界面更新与 Room 持久化解耦，消息在内存累积后每 50 ms 更新界面、每 500 ms 保存一次，结束或异常时强制落盘；将无限事件队列改为固定 512 容量，积压时主动中断并进入重试流程。真实 Room 基准中，数据库 UPDATE 由 201 次降至 20 次（-90.05%），本地处理耗时中位数由 176.13 ms 降至 18.42 ms（-89.54%），最终 32,000 字回答保持一致。
- 重构 **Markdown 上传链路**，取消“临时文件 + 全量读入内存”，改为从系统文件 URI 直接流式写入 OkHttp；文件大小未知时使用 10 MiB 受限临时文件兜底，并保证取消或异常后清理。Android A/B 基准中，10 MiB 文件峰值 Java 堆增量由 22.98 MiB 降至 208 KiB（-99.1%），临时磁盘占用降为 0，客户端文件准备与写出耗时降低 58.3%，内容经长度和 SHA-256 校验一致。
- 完成 **认证与历史同步**：使用 Android Keystore + AES-GCM 加密登录态，处理 Token 自动刷新与并发更新；以账号隔离 Room 数据，通过持久化游标完成全量/增量同步、删除标记合并及旧版历史分批迁移，防止切换账号或服务端时数据串用。
- 完善异常恢复与系统体验：进程重启后可从残缺回答继续生成或重新生成；回答完成通知覆盖冷、热启动并按消息 ID 精确定位，同时实现用户滚动意图感知和同步状态提示、会话搜索/置顶/删除。

**技术栈：** Kotlin、Jetpack Compose、Material 3、Coroutines、Flow/StateFlow、OkHttp SSE、Retrofit、Room、DataStore、Android Keystore、Navigation Compose、JUnit、Android Instrumentation

## 1.项目介绍

- 不要说多个后端服务器。（就当没有换地址这一功能）

面试官您好，我介绍一下我的核心项目 LabSage。它是一款面向实验室场景的 Android AI 助手，主要用于知识库检索和实验问题查询。后端是已有的 Go AI 服务，我独立负责 Android 客户端，使用 Kotlin、Jetpack Compose 和 MVVM，完成了流式问答、多会话、知识库文件管理、账号认证和历史同步。

这个项目里，我投入最多的是流式回答的性能和稳定性。

早期每收到 5 个 SSE 数据块，就把当前的完整回答更新到 Room，界面再等 Room Flow 刷新。服务端分片越密，数据库写入就越频繁；而且回答越长，每次写入的正文越大，整段会话重复查询和映射的开销也越明显。

后来我把界面更新和持久化拆开了。正文先在内存中累积，界面最多每 50 毫秒更新一次；流式期间暂停当前会话的 Room 观察，Room 最多每 500 毫秒保存一个恢复点。正常结束时，把完整正文落库并标记完成；连接异常时，则立即保存当时最新的部分回答，并标记为可重试。这样界面不用等待数据库回流，Room 也退出了流式生成的高频路径。

我用 9 组参数组合验证了这两个间隔，最后在文字的流式观感、本地开销和异常恢复窗口之间做了取舍。在固定负载下，总持久化次数从 202 次降到了 21 次；在同一模拟器的真实 Room 对比中，Room 写入加全会话快照的耗时中位数也下降了接近 90%。

稳定性方面，我先在事件入队前过滤非核心的工具日志，只保留工具开始、结束和路由信息，再把无限缓冲改成容量 512 的有界缓冲。512 是参考历史上工具查询出现过的 200 多条原始 SSE 事件预留的工程余量，但它仍然可能写满。一旦写入失败，我会主动断开连接，保存当前部分回答，并提示用户继续生成或重试，避免内存持续增长。

另一个优化是 Markdown 上传。对于能够获取长度的系统文件 URI，我改成直接流式写入 OkHttp，不再复制临时文件后再把整个文件读入内存。测试 10 MiB 文件时，峰值 Java 堆增量中位数从大约 23 MiB 降到了 208 KiB。

除此之外，我还使用 Keystore 管理密钥，通过 AES-GCM 加密登录态，并实现了 401 后的 Token 刷新、并发刷新去重和账号数据隔离。App 进程被杀后，也可以从 Room 恢复已经保存的部分回答，再由用户点击继续，重新发起续写请求。

这个项目让我真正处理过一条 Android 长任务从网络接收、内存状态、持久化到异常恢复的完整链路。



- `50/500 ms` 是 9 组参数测试后的工程折中，不是行业标准；分别对应约 20 次/秒的界面更新和约半秒的未保存窗口。
- `512` 不是保证永远不满。亮点是有界、满后快速失败、保存 partial，并允许继续或重试。
- “被杀后继续生成”不是恢复原来的 SSE 连接，而是恢复 Room 中的部分回答，再发起一次续写请求。

### 1.1 遇到的问题以及解决方案TODO（主要的3个）

#### （1）SSE 流式消息优化

---

我们做的是一个 AI 聊天应用，服务端通过 SSE 流式返回 assistant 的回复。不管优化前还是优化后，核心的数据模型没变：一轮对话就是 user 和 assistant 两条消息，assistant 的正文会在同一条数据库记录上不断被覆盖更新。

**先说一下优化前是怎么做的，以及问题在哪。**

原来的逻辑很简单：每收到 5 个 SSE chunk，就把 StringBuilder 里攒到当前的完整正文写一次 Room。同时，ViewModel 里有一个 Room 的 Flow，观察整段会话的消息列表，只要 messages 表有变化，Room 就会让这个查询失效、重新执行整段会话的查询，然后把结果映射成 UI 模型，驱动 Compose 刷新。

这就带来了几个问题：第一，回答越长，每次写入的字符串就越大，最坏情况下一轮对话要反复写入几 KB 甚至几十 KB 的文本几十上百次；第二，服务端的 chunk 来得越密，写入频率就越高，Room 全会话查询也跟着频繁重跑；第三，UI 的刷新频率完全被服务端的 chunk 速度绑架了，没有任何节流手段。

**优化后的思路，说白了就是三点：让 UI 走内存、让 Room 退出热路径、让刷新频率由我们控制而不是服务端决定。**

具体做法是这样的：

用户发完消息后，user 消息正常写入 Room 并直接塞进内存的消息列表，UI 立刻显示。紧接着我们停止 Room 消息流的观察——这是关键，Streaming 期间我们不需要 Room 回流。

SSE chunk 来了之后立刻追加到 StringBuilder，但入库和 UI 更新分开走两条路：

- UI 这条路，每 50 毫秒从 StringBuilder 取一次不可变快照，直接更新内存里的 `_messages`，Compose 直接消费这个 StateFlow，完全不走 Room。这样 UI 刷新被限制在每秒 20 次左右，人眼看起来完全流畅，而且不会因为 Room 查询产生额外开销。

- 入库这条路，每 500 毫秒做一次 checkpoint，把完整正文覆盖写入同一条 assistant 记录。这一步的目的纯粹是防止进程异常退出丢数据，频率降到每秒两次。

- 当 Done、Error 或者连接断开的时候，执行最终落库，同时恢复 Room Flow，用数据库的最终结果校验内存状态。

**数值上，提升很明显。** 在固定 1000 个 chunk、每 10 毫秒一个的极限测试里，持久化次数从 202 次降到了 21 次，减少了将近 90%。真实 Room 基准测试里，写入加全会话快照的中位耗时从 176 毫秒降到 18 毫秒，P95 从 224 毫秒降到 24 毫秒，基本是数量级级别的优化。

总结的话，就是在不改变数据模型和持久化语义的前提下，通过把 UI 刷新路径从"Room→Flow→Compose"改成"内存→StateFlow→Compose"，同时把入库降级为低频 checkpoint，让热路径上的瓶颈消失了。

---

#### （2）历史同步功能

我们做的是一个 AI 聊天应用。项目最开始只有 Room 保存本地聊天记录，后端并不持久化历史。这样单机使用没有问题，但清除数据或者换设备后历史就无法恢复；加入登录以后，不同账号的数据也需要从服务端重新同步下来。

**先说一下最直接的同步方案，以及问题在哪。**

最直观的做法，是每次进入页面都请求服务端的完整会话和消息，然后同时刷新 UI 和 Room。但这样有几个问题：第一，弱网时首屏需要一直等待，离线时也看不到已经缓存的历史；第二，记录越多，每次全量传输和写库的成本越高；第三，如果网络结果和 Room Flow 都能更新 UI，客户端就会出现两个数据来源；第四，cursor、账号和本地缓存一旦对应不上，错误的增量同步可能直接跳过一部分历史。

**最后的方案，说白了就是三点：Room 负责本地优先，cursor 负责增量进度，服务端负责最终正确性。**

具体做法是这样的：

页面打开后，Compose 先观察 Room Flow，所以本地有缓存时可以立即显示。Repository 随后发起同步，网络响应不会直接修改 UI，而是先写入 Room，最终仍然由 Room Flow 更新界面。进入会话列表时同步 conversations，进入聊天页时只同步当前会话的 messages。

同步之前还会处理一次旧数据迁移。因为早期版本已经在 Room 中产生了本地消息，这些 `syncVersion=0` 的数据不能直接丢掉。客户端会按照当前 `userId` 和 `serverKey` 认领这些旧记录，使用原来的稳定 UUID 分批上传；后端按照 ID 幂等处理。只有所有批次都成功后，DataStore 才会记录这个账号和后端已经完成 legacy import，之后不再重复迁移。

正式同步分为全量和增量。第一次同步、没有 cursor，或者 cursor 对应的 Room 缓存已经失效时，不传 `updatedAfter` 做全量对账；只有 cursor 和缓存都可信时，才携带 cursor 拉取后续变化。

`syncVersion` 是单条会话或者消息对应的服务端变化版本，cursor 则表示当前同步流已经完整处理到的位置。客户端不能从 Room 中取最大的 `syncVersion` 自己推进进度，只能保存服务端返回并校验通过的 `nextCursor`。

服务端的 `syncVersion` 也不是时间戳，而是在修改业务数据的同一个事务中，通过单行序列表原子加一得到。这样并发请求不会拿到重复的序列分配结果，业务写入失败时序列也会一起回滚。

版本可以有空洞，因为中间的版本可能属于其他用户或者其他会话。只要版本保持单调、不倒退，就不影响客户端使用 `sync_version > cursor` 做增量查询。

**cursor 本身也存放在 Room（`history_cursors` 表），和数据缓存同库同事务。** 早期实现把 cursor 放在 Preferences DataStore，Room 和 DataStore 组不成跨存储事务，只能靠"先 Room 合并、成功后才推进 cursor"的写序纪律保证安全：Room 失败时 cursor 不动可以重拉，但 Room 成功而 cursor 保存失败时会多一次重复同步。现在页面的合并和 `nextCursor` 的推进在同一个 Room 事务里完成，"cursor 领先于数据"从靠顺序保证变成结构性不可能，事务在中途任何一点失败都会整体回滚。绑定信息直接记录在 cursor 行上（`serverKey` 列）：行存在且后端匹配才可信，切换后端时按账号整行删除，下一次同步自然回退为全量。

最后，会话列表 cursor 按照 `userId` 和 `serverKey` 隔离，每个会话的消息 cursor 再额外绑定 `conversationId`。通过通知直接进入聊天页但父会话不存在时，会先全量同步 conversations，再同步 messages。

请求返回后还会再次确认账号和后端没有变化，不一致就丢弃旧响应；`HistorySyncCoordinator` 再使用 Mutex 串行化同步任务，避免多个任务交错写入 Room 和推进 cursor。

总结来说，就是用 Room 保证本地历史立即可见，用服务端单调版本和 cursor 降低后续同步成本，再通过 legacy import、账号隔离和"数据与 cursor 同事务提交"，让旧数据能够迁移，异常情况下事务回滚后重新拉取，而不会永久漏掉数据。

#### （3）文件上传功能

项目支持用户上传 Markdown 笔记，后端收到文件后会进行内容切分、Embedding，并写入 Milvus 知识库。

**旧版的问题主要是内存和磁盘开销。**

旧版先把系统文件 URI 复制到 cache 临时文件，再通过 `readBytes()` 将整个文件读入 Java 堆，最后构造 Multipart 请求：

```
Content URI
→ 临时文件
→ ByteArray
→ Multipart 上传
```

这意味着同一份文件既占用一份临时磁盘，又完整存在于内存中。实测上传 10 MiB Markdown 时，Java 堆峰值增量中位数达到 22.98 MiB。

**我的优化是改成直接流式上传。**

文件大小已知时，客户端不再创建临时文件和完整 ByteArray，而是通过自定义 `StreamingRequestBody`，从 ContentResolver 边读边写到 OkHttp：

```
Content URI
→ 固定大小缓冲区
→ OkHttp Multipart
```

这样，额外空间开销就从 O(文件大小) 降为近似 O(固定缓冲区)。

项目还有 Token 自动刷新机制，因此 RequestBody 每次写出时都会重新打开文件 URI。这样即使 401 后 OkHttp 重新发送请求，也可以再次从文件开头读取，而不是复用已经读完的输入流。

如果文件提供方没有返回大小，则使用固定缓冲区复制到临时文件，并在复制过程中限制最大 10 MiB，作为兼容性兜底。

**我没有选择分片和断点续传。**

因为当前上传的是不超过 10 MiB 的 Markdown 文本，失败后整文件重传成本较低，而真正的断点续传还需要后端增加 uploadId、分片状态和合并协议。

所以当前选择单次流式 Multipart：不修改后端接口，优先解决已经存在的内存和磁盘问题。

**最后通过 Android A/B 基准验证效果。**

我使用 1、5、10 MiB Markdown，对旧版和流式版分别进行测试，共采集 180 条正式样本。

10 MiB 文件结果如下：

| 指标                 | 旧版      | 流式版   |
| -------------------- | --------- | -------- |
| Java 堆峰值增量      | 22.98 MiB | 208 KiB  |
| 额外临时磁盘         | 10 MiB    | 0        |
| 客户端准备与写出耗时 | 48.48 ms  | 20.21 ms |

最终峰值 Java 堆增量降低 99.1%，已知大小文件不再占用额外临时磁盘。

这里的耗时只代表客户端文件准备和 RequestBody 写出，不包含网络、Embedding 和 Milvus，因此不会把它描述成整个上传接口加速 58.3%。

#### （3）文件上传功能（sup）

我们做的是一个带知识库的 AI 聊天应用。用户通过 Android 系统文件选择器选择 Markdown 笔记，客户端使用 Multipart 上传；后端收到文件后，会依次完成文件落盘、内容切分、Embedding 和 Milvus 写入，索引构建完成后才返回成功。

**先说一下旧版实现，以及它的核心问题。**

旧版上传链路是：

```
Content URI
→ 复制到 cache 临时文件
→ readBytes() 读成 ByteArray
→ 构造 RequestBody
→ Multipart 上传
```

这套实现虽然能正常工作，但同一份文件先完整写入临时磁盘，随后又通过 `readBytes()` 整体进入 Java 堆。文件越大，内存和磁盘开销越明显。

在 10 MiB Markdown 的实测中，旧链路带来的 Java 堆峰值增量中位数达到 22.98 MiB，说明问题不只是理论上的“大文件可能 OOM”，而是当前实现确实存在文件大小级的额外内存开销。

**优化的核心，就是不再把完整文件物化到内存，而是边读边写。**

Android 文件选择器返回的是 `content://` URI，不是真实文件路径，所以客户端通过 ContentResolver 获取文件名和大小。

文件大小已知时，使用自定义的 `StreamingRequestBody`。OkHttp 真正写请求体时，再打开 ContentResolver Source，通过固定大小的缓冲区直接写入 Multipart Sink：

```
Content URI
→ ContentResolver Source
→ 固定大小缓冲区
→ OkHttp Multipart Sink
```

这样不再创建完整 ByteArray，也不需要已知大小路径下的临时文件，额外空间开销由 O(文件大小) 降为近似 O(固定缓冲区)。

项目还配置了 OkHttp Authenticator。旧版 ByteArray RequestBody 天然可以重复写出，因此改成流式上传后不能丢掉这个能力。我的做法是每次执行 `writeTo()` 都重新通过 ContentResolver 打开 Source，而不是复用同一个已经读取过的 InputStream。这样遇到 401 刷新 Token 后，RequestBody 仍然可以重新发送完整文件。

如果文档提供方没有返回文件大小，则使用固定缓冲区复制到 cache 临时文件，并在复制过程中执行 10 MiB 上限，再通过文件构造 RequestBody。这是低频兼容路径，正常情况下仍然直接流式上传。

**为什么没有选择分片和断点续传？**

因为当前上传的是不超过 10 MiB 的 Markdown 文本，失败后重新上传整个文件的成本较低，而且现有后端只有单次 Multipart 接口。

真正的断点续传还需要服务端提供 uploadId、分片状态、offset 查询、校验和幂等合并协议。对于当前文件规模，这些复杂度带来的收益有限。

因此我选择保留单次 Multipart，只优化客户端的数据路径。这样不修改后端协议，也能直接解决当前最明确的内存和磁盘问题。

**性能是怎么验证的？**

我没有直接比较 `/api/upload` 的总响应时间，因为后端还会同步执行 Embedding 和 Milvus 写入，直接测接口会把服务端索引耗时混进客户端结果。

因此我编写了 Android Instrumentation A/B 基准，分别复刻旧版和流式版的客户端链路，测试 1、5、10 MiB Markdown。每组预热 3 次、正式执行 30 次，共得到 180 条样本。

10 MiB 文件的测试结果为：

| 指标                     | 旧版      | 流式版   | 变化   |
| ------------------------ | --------- | -------- | ------ |
| Java 堆峰值增量中位数    | 22.98 MiB | 208 KiB  | -99.1% |
| 已知大小路径临时磁盘     | 10 MiB    | 0        | -100%  |
| 客户端准备与写出中位耗时 | 48.48 ms  | 20.21 ms | -58.3% |

每次写出的字节数和 SHA-256 都与原文件一致。

这里的耗时只代表客户端文件准备和 RequestBody 写出，不包含网络、Embedding 和 Milvus，因此不能把 58.3% 直接说成整个上传接口的耗时提升。

**总结来说，**

这次优化解决的核心矛盾是：旧版为了上传 `content://` 文件，把同一份内容先落临时磁盘、再完整读入 Java 堆，产生了文件大小级的额外开销。

我将它改成了可重放的流式 RequestBody，在不修改现有 Multipart 接口的前提下，把 10 MiB 文件的峰值 Java 堆增量降低了 99.1%，并让已知大小文件不再占用额外临时磁盘。

### 1. RequestBody 是什么？

OkHttp 不关心文件来自哪里，它只会在需要发送数据时调用：

```
requestBody.writeTo(sink)
```

可以把它理解成：

> OkHttp 把一根“网络水管”交给 RequestBody，让 RequestBody 把文件内容倒进去。

`writeTo()` 不一定只执行一次。项目配置了 Authenticator，当 Token 过期时可能发生：

```
第一次请求携带旧 Token
→ 服务端返回 401
→ Authenticator 刷新 Token
→ OkHttp 重新发送原请求
→ 再次调用 RequestBody.writeTo()
```

Authenticator 主要负责替换 Token，请求体能不能重新发送，仍然取决于 RequestBody 自己。

### 2. 为什么旧版可以重新发送？

旧版先执行：

```
val bytes = tempFile.readBytes()
```

这相当于把文件完整复印一份，放进 Java 堆：

```
文件 → ByteArray
```

第一次上传时，OkHttp 从 ByteArray 的开头读到结尾。

收到 401 后，第二次上传仍然可以重新从 ByteArray 的第 0 个字节开始读取：

```
第一次：ByteArray[0] → ByteArray[最后]
第二次：ByteArray[0] → ByteArray[最后]
```

所以旧版虽然浪费内存，但它天然可以重复发送。

### 3. 为什么普通流式写法可能出问题？

假设优化时只打开一次 InputStream：

```
val inputStream = contentResolver.openInputStream(uri)

val body = object : RequestBody() {
    override fun writeTo(sink: BufferedSink) {
        inputStream!!.source().use { source ->
            sink.writeAll(source)
        }
    }
}
```

InputStream 可以理解成文件上的一个读取指针。

第一次上传：

```
文件开头 ──────────→ 文件结尾
                    ↑
                指针在这里
```

第一次 `writeTo()` 完成后，InputStream 已经读到了结尾，而且可能已经关闭。

401 刷新 Token 后，OkHttp 第二次调用 `writeTo()`，这个 InputStream 不会自动回到开头：

```
第二次 writeTo()
→ 输入流已经耗尽或关闭
→ 读不到内容
→ 可能上传空文件、残缺文件或者直接报错
```

也不能简单依赖 `reset()`，因为 ContentProvider 返回的 InputStream 不一定支持回退，文件还可能来自云盘。

### 4. 当前实现是怎么解决的？

当前 RequestBody 保存的不是某个已经打开的 Source，而是“如何打开一个新 Source”的函数：

```
StreamingRequestBody(
    length = fileSize,
    mediaType = mediaType
) {
    val input = contentResolver.openInputStream(uri)
        ?: throw FileNotFoundException()

    input.source()
}
```

可以把这个 Lambda 理解成一个“取新吸管的机器”：

```
不是保存：某一根已经使用过的吸管

而是保存：每次需要时，重新拿一根新吸管
```

`writeTo()` 内部每次都会调用它：

```
override fun writeTo(sink: BufferedSink) {
    openSource().use { source ->
        // 把这次新打开的 Source 写入网络
    }
}
```

因此执行过程是：

```
第一次 writeTo()
→ openInputStream(uri)
→ 得到 Source A
→ 从头读取完整文件
→ 关闭 Source A

收到 401
→ Authenticator 刷新 Token

第二次 writeTo()
→ 再次 openInputStream(uri)
→ 得到全新的 Source B
→ 再从头读取完整文件
→ 关闭 Source B
```

虽然两次访问的是同一个 `content://` URI，但每次打开的输入流都是新的，所以都能从文件开头开始读取。

### 5. 这个设计真正解决了什么？

它同时保留了旧、新两种方案的优点：

| 方案                  | 内存占用         | 能否重新发送 |
| --------------------- | ---------------- | ------------ |
| ByteArray RequestBody | 整个文件进入内存 | 可以         |
| 复用单个 InputStream  | 固定缓冲区       | 不可靠       |
| 每次重新打开 Source   | 固定缓冲区       | 可以         |

所以准确的说法不是：

> 旧版存在 401 无法重传的问题。

而是：

> 旧版 ByteArray 天然可重放，但内存开销大；改成流式上传时，我没有简单复用单个 InputStream，而是让每次 `writeTo()` 都重新打开 ContentResolver Source，在降低内存占用的同时保留了 401 鉴权重传能力。

面试时可以再压缩成：

> OkHttp 在 401 刷新 Token 后可能重新发送原请求。旧版 ByteArray 可以重复读取，但改成流式上传后，如果复用同一个 InputStream，第二次写出时流已经耗尽。因此我的 RequestBody 保存的是 Source 工厂，每次 `writeTo()` 都重新打开 URI，既避免整文件入堆，也保证请求体可以安全重放。

### 2、核心功能是怎么实现的？

界面层使用 Jetpack Compose，聊天、会话列表、文件管理和设置分别由对应的 ViewModel 管理。ViewModel 不直接操作 Retrofit 或 Room，而是通过 Repository 统一访问网络和本地数据库。

聊天状态使用 StateFlow 对外提供，流式过程使用密封类区分空闲、生成中、完成和失败。这样 Compose 只需要根据当前状态展示对应界面，不需要同时维护多个容易互相冲突的 Boolean。

本地存储使用 Room，主要是会话表和消息表。会话删除后，关联消息通过外键一起处理TODO：实际开发中真的会使用到外键吗？A：不会，所以这里可以说一开始使用外键+replace，后来出现bug后取消了外键的使用；聊天记录则通过 Room Flow 返回给 ViewModel，让数据库成为本地界面的主要数据来源。

REST 接口使用 Retrofit，登录状态和设置分别使用加密存储及 DataStore。

> JWT 属于敏感认证信息，所以没有明文写入 DataStore，而是使用 Android Keystore 生成 AES 密钥，通过 AES-GCM 加密完整 AuthSession，再把 IV 和密文保存到 SharedPreferences。这里 SharedPreferences 只是密文容器，真正的安全边界是 Keystore。

### 被追问“为什么不用 DataStore 存密文”

> 可以使用 Keystore 加密后再把密文放入 DataStore，安全性同样成立。但当前登录状态只在启动、登录、刷新和退出时读写，不需要持续的 Flow 订阅。使用 SharedPreferences 作为密文容器实现更直接，而并发一致性由 AuthSessionStore 的同步方法和条件更新控制。如果未来需要多处响应式订阅登录会话，可以迁移到加密后的 DataStore。

后端不需要保存access token，直接验证JWT；后端保存了refresh token的密文；

| Token         | 客户端                            | 服务端                                  |
| ------------- | --------------------------------- | --------------------------------------- |
| Access Token  | 保存原文并随业务请求携带          | 不保存，通过 JWT 签名和过期时间直接验证 |
| Refresh Token | 保存原文，Access Token 失效时提交 | 不保存原文，只保存 SHA-256 哈希和状态   |

```
客户端：Refresh Token 原文（加密）
服务端：SHA-256(Refresh Token)
```

## 2.1 请求相关

#### 1. 登录

```
客户端提交用户名和密码
        ↓
服务端验证密码
        ↓
签发 Access Token + Refresh Token
        ↓
服务端保存 Refresh Token 的哈希
        ↓
客户端使用 Keystore 加密保存两个 Token
```

Access Token 有效期 15 分钟，Refresh Token 有效期 30 天。

#### 2. 发起普通业务请求

```
客户端读取 Access Token
        ↓
添加 Authorization: Bearer <Access Token>
        ↓
请求聊天、会话、消息等接口
        ↓
服务端验证 JWT 签名、类型和过期时间
```

如果验证成功，服务端从 Access Token 中取得用户 ID，继续处理请求。

#### 3. 客户端预判 Access Token 是否过期

登录响应中包含：（服务端验证成功后返回）

```
accessExpiresAt
refreshExpiresAt
```

客户端可以比较：

```
accessExpiresAt > 当前时间
```

提前判断 Access Token 是否过期。

LabSage 的部分历史同步操作会在请求前检查 `accessExpiresAt`，如果发现 Access Token 已过期，就先刷新再同步。

但客户端时间可能不准确，而且 Token 也可能因签名密钥变化等原因提前失效。因此，本地时间只能用于提前判断，服务端返回的 401 才是最终权威结果。

#### 4. 普通请求收到 401

普通请求会先携带当前 Access Token。服务端确认它无效后返回 401，OkHttp Authenticator 随即介入：

```
业务请求返回 401
        ↓
Authenticator 取得失败的 Access Token
        ↓
使用 Refresh Token 请求 /api/auth/refresh
```

刷新操作由 Mutex 串行控制。如果多个请求同时收到 401：

```
请求 A ─┐
请求 B ─┼→ 只执行一次刷新
请求 C ─┘
```

第一个请求刷新成功后，其他请求发现本地 Access Token 已经更新，直接复用新 Token，不再重复刷新。

#### 5. 服务端处理刷新

客户端向服务端提交 Refresh Token 原文，服务端执行：

```
验证 Refresh Token 的 JWT 签名和过期时间
        ↓
计算 SHA-256 哈希
        ↓
查询数据库中的 Token 哈希
        ↓
检查 revoked_at 为空、expires_at 未过期
        ↓
撤销旧 Refresh Token
        ↓
生成新的 Access Token + Refresh Token
        ↓
保存新 Refresh Token 的哈希
```

这就是 Refresh Token rotation：旧 Refresh Token 刷新成功一次后立即失效。

#### 6. 客户端保存新 Token 并重试

```
客户端收到新的 Token 对
        ↓
加密覆盖旧登录状态
        ↓
使用新 Access Token 重试原请求
```

Authenticator 最多重试一次：

```
原业务请求携带旧 Access Token
        ↓
GET /api/conversations
        ↓
服务端返回 401
        ↓
使用 Refresh Token 调用 /api/auth/refresh
        ↓
获得新 Access Token 和新 Refresh Token
        ↓
保存新 Token
        ↓
使用新 Access Token 再发一次 /api/conversations
```

```
第一次业务请求 → 401
        ↓
刷新一次 Token
        ↓
重试原业务请求 → 仍然 401
        ↓
Authenticator 返回 null
        ↓
401 返回给上层，不再刷新
```

这样可以避免无限循环。

#### 7. Refresh Token 也失效

如果 Refresh Token 已过期、已撤销或验证失败：

```
刷新失败
  ↓
客户端清除本地登录状态
  ↓
AuthenticationState 变为 Unauthenticated
  ↓
界面返回登录页
```

#### 8. 退出登录

```
客户端向服务端提交 Refresh Token
        ↓
服务端将对应哈希记录标记为 revoked
        ↓
客户端清除本地 Access Token 和 Refresh Token
```

Access Token 不需要从数据库删除，因为服务端本来就没有保存它；它最多在剩余的 15 分钟内自然过期。若生产系统要求“退出后 Access Token 立即失效”，还需要增加 Token 黑名单或用户会话版本等机制。

#### JWT验证

服务端收到 JWT 后：

1. 解码 Header，检查 `alg` 是否为 HS256。
2. 使用自己的 `JWT_SECRET`，对 Header 和 Payload重新计算签名。
3. 比较重新计算的签名与 Token 中的 Signature。
4. 签名一致后，再检查 `iss`、`exp`、`tokenType` 等字段。

## 最终面试版本

> 登录成功后，服务端会签发一个短期 Access Token 和一个长期 Refresh Token，客户端使用 Android Keystore 加密保存两者。Access Token 的原文只保存在客户端，服务端不保存，而是通过 JWT 签名和过期时间直接验证；Refresh Token 原文同样保存在客户端，但服务端会保存它的 SHA-256 哈希、过期时间和撤销状态，以支持刷新和退出登录。客户端可以根据登录响应中的 `accessExpiresAt` 提前判断 Access Token 是否过期，但客户端时间不一定可靠，所以服务端返回的 401 才是最终权威。LabSage 的普通请求会先携带 Access Token，收到 401 后由 OkHttp Authenticator 使用 Refresh Token 获取一组（ Refresh Token + Access Token）新 Token，再重试原请求；部分历史同步操作则会根据 `accessExpiresAt` 提前刷新。刷新过程通过 Mutex 串行化，避免多个并发请求同时刷新，服务端还会撤销旧 Refresh Token 并保存新 Token 的哈希。客户端最多重试一次，防止 401 无限循环；如果 Refresh Token 也失效，就清除本地登录状态并返回登录页。

## 2.2 历史同步功能

​	LabSage 的历史同步采用服务端权威、Room 本地缓存的结构。页面打开后不会等待网络，而是先观察 Room Flow，让用户立即看到本地历史，然后再从服务端同步最新数据。网络响应不会直接更新列表，而是先写入 Room，最终仍由 Room Flow 更新界面。同步分为全量和增量：没有可信 cursor，或者 cursor 对应的本地缓存已经失效时，不传 `updatedAfter` 做全量同步；有可信 cursor 时，把它作为 `updatedAfter` 发送，只拉取后续变化，服务端通过 `nextCursor` 返回新的同步高水位。Room 保存业务数据、每条记录自己的 `syncVersion`，以及整体 cursor（`history_cursors` 表）；会话 cursor 绑定用户和后端，消息 cursor 还会绑定会话 ID，避免跨账号、跨后端或跨会话误用同步进度。提交时客户端会先校验响应，再在同一个 Room 事务里对当前同步范围做全量对账或增量合并，并推进 `nextCursor`：数据与 cursor 要么一起落库，要么一起回滚，"cursor 领先于数据"结构性不可能发生。这套设计的核心原则就是宁可重复同步，也不能提前推进进度造成数据遗漏。网络失败时继续展示 Room 缓存；如果同步开始前发现缓存缺失或归属不匹配，就放弃旧 cursor 并退回全量同步；如果网络请求期间用户或后端发生变化，则拒绝提交这次旧响应。消息同步缺少父会话时，也会先重建会话缓存，再同步消息。

## 2.3 应用被杀死后，恢复未完成对话

项目早期，正在生成的正文、消息 ID 和错误状态主要放在 ViewModel 内存里。普通页面旋转不会有太大问题，但如果进程被系统杀死，ViewModel 会被清空。

虽然部分回答已经保存在 Room 中，但重新进入页面后，界面只会把它当作普通消息展示，不知道这是一条可以继续生成的残缺回答。

后来我在消息表中保存回答状态、原始问题、请求 ID和是否残缺等信息。重新进入会话时，ViewModel 会检查最后一条助手消息。如果它处于未完成状态，就从 Room 恢复已经生成的正文、消息 ID 和原始问题，然后重新构造可恢复状态。

用户可以选择“继续生成”或者“重新生成”。继续生成会把已有正文作为上下文，让后端从当前位置继续；重新生成则删除原来的残缺回答，重新发送原问题。

这里没有使用 SavedStateHandle 保存完整回答，因为它更适合少量界面状态，而且不能代替长期持久化。Room 更适合保存可能较长的正文，也可以覆盖真正的进程重启场景。



## 2.4 回答完成通知是怎么实现的？（回答完成通知，按消息ID精确定位）

这个功能主要解决长回答生成期间，用户把应用切到后台或者暂时不再盯着聊天界面时，不知道回答何时完成的问题。客户端只会在收到合法的 SSE Done、状态为 `completed`，并且正文去除空白后不少于 300 个字符时发送通知。发送前，ViewModel 会先把最终回答写入 Room，再将本地临时消息 ID 收敛为服务端正式 `messageId`，然后让 PendingIntent 同时携带 `conversationId` 和 `messageId`。应用冷启动时由 `onCreate()` 解析通知目标，已有 Activity 被复用时则由 `onNewIntent()` 处理。进入聊天页后，页面不会只查询一次 Room，而是观察统一的 `messages StateFlow`：空闲或冷启动时它由 Room Flow 提供数据，SSE 期间由内存快照更新，终态后再恢复 Room Flow 对账。`LaunchedEffect` 会随着消息列表变化，持续使用服务端正式 `messageId` 查找目标；找到以后调用 `scrollToItem()` 精确定位，消费本次跳转目标并关闭 `autoFollow`，避免重复定位或者又被自动拉回底部。这样通知完成的是消息级跳转，而不只是打开一个很长的会话；通知权限缺失或发送失败也不会影响回答落库。需要说明的是，当前生成任务运行在 ChatViewModel 的 `viewModelScope` 中，没有前台服务，因此通知能够生效的前提是生成任务及应用进程仍然存活。

### 相关面试八股

#### 1. NotificationChannel 有什么作用？

Android 8.0 开始，所有通知都必须归属一个 NotificationChannel。Channel 用于统一管理通知名称、重要程度、声音和震动等行为，用户也可以在系统设置中单独关闭某个通知类别。

Channel 创建是幂等的，所以通常在 Application 启动时创建一次：

```
channelId = long_response_completed
importance = DEFAULT
```

#### 2. NotificationChannel 和通知权限是一回事吗？

不是。

- NotificationChannel 决定通知属于哪个类别以及怎样展示；
- `POST_NOTIFICATIONS` 决定应用是否有权发送通知。

Android 13 以下通常不需要运行时申请通知权限；Android 13 以上需要动态申请。LabSage 没有在冷启动时立即索取，而是在开始生成回答、通知功能真正相关时再申请。

#### 3. 为什么使用 NotificationCompat？

`NotificationCompat` 对不同 Android 版本提供统一兼容封装，可以配置图标、标题、摘要、BigTextStyle、点击行为和优先级，而不需要针对每个系统版本分别构造通知。

当前通知摘要会移除常见 Markdown 标记，并限制为约 180 个字符，避免通知栏出现代码围栏、链接语法或过长内容。

#### 4. PendingIntent 是什么？

PendingIntent 是应用交给系统、允许系统稍后代表应用执行的 Intent。

通知可能在应用不处于前台时被点击，因此不能直接调用页面跳转，而是把打开 MainActivity 的 Intent 包装成 PendingIntent，交给通知系统保存。

当前使用：

```
FLAG_UPDATE_CURRENT
FLAG_IMMUTABLE
```

`UPDATE_CURRENT` 允许同一 PendingIntent 更新 extras；`IMMUTABLE` 防止其他程序修改其中内容，也是较新 Android 版本推荐的安全设置。

#### 5. `onCreate()` 和 `onNewIntent()` 为什么都要处理？

两者对应不同的 Activity 状态：

```
应用没有启动
→ 创建 MainActivity
→ onCreate()

MainActivity 已经存在
→ 复用已有实例
→ onNewIntent()
```

通知 Intent 使用 `CLEAR_TOP | SINGLE_TOP`。如果只在 `onCreate()` 中解析，应用已经打开时点击通知可能没有反应；如果只处理 `onNewIntent()`，冷启动又无法定位。

#### 6. 为什么通知需要同时保存 conversationId 和 messageId？

两者定位层级不同：

```
conversationId：确定进入哪个会话
messageId：确定滚到哪一条回答
```

只携带 conversationId 只能打开会话。长会话中可能有很多消息，用户仍然需要自己寻找新回答。增加 messageId 后，导航才能从会话级提升到消息级。

#### 7. 为什么必须使用服务端正式 messageId？

流式过程中，客户端 assistant 占位消息可能使用本地临时 ID；服务端则有正式的 assistant ID。

如果通知保存临时 ID，应用进程被回收后，这个 ID 可能已经因为身份收敛而不存在。当前顺序是：

```
最终回答写入 Room
→ 本地 ID 收敛为服务端 messageId
→ 创建通知
```

所以 PendingIntent 保存的是能够被 Room 和历史同步共同识别的正式 ID。

#### 8. 为什么通知发送失败不能影响回答完成？

通知只是辅助提醒，回答落库才是核心业务。

用户可能没有授权通知，权限也可能在检查后、真正发送前被撤销。因此发送前会检查权限，并捕获可能出现的 `SecurityException`。即使通知失败，回答仍然保持 `completed`，不能因为提醒失败而把生成结果改成失败。

#### 9. ChatScreen 定位消息时为什么不直接查询一次 Room？

因为页面在不同阶段的数据来源不同：

```
空闲、冷启动：
Room Flow → messages StateFlow

SSE 生成期间：
内存快照 → messages StateFlow

回答结束后：
Room Flow 恢复 → 校准 messages StateFlow
```

如果只在进入页面时查询一次 Room，目标消息可能还没有加载完成，或者历史同步尚未补齐。页面统一观察 `messages StateFlow`，可以屏蔽 Room 与内存热路径的差异。

#### 10. 为什么使用 LaunchedEffect 定位？

`scrollToItem()` 是挂起函数，需要在协程中执行；同时，目标消息可能不会在页面第一次组合时立即出现。

使用：

```
LaunchedEffect(targetMessageId, messages)
```

可以在目标 ID 或消息列表发生变化时重新查找。找到目标之前不会错误滚动，找到后再执行定位，而且不会阻塞主线程。

#### 11. 为什么定位成功后要消费目标并关闭 autoFollow？

消费目标可以避免后续重组或 Room 更新反复滚到同一条消息。

关闭 `autoFollow` 则是为了保护本次定位结果。否则页面刚滚到目标回答，后续消息列表变化或布局更新又可能触发自动滚底，把用户从目标位置拉走。

等待目标期间，普通的“首次进入自动滚到底部”逻辑也会暂停，避免两个滚动副作用竞争。

#### 12. 当前通知功能还有什么边界？

主要有三个：

- 没有前台服务，ChatViewModel 被销毁或进程被杀后，正在进行的 SSE 任务无法保证继续执行；
- 目标 messageId 一直不存在时，目前没有超时或“消息不存在”提示；
- 通知没有绑定用户和后端，切换账号后点击旧通知，当前账号可能无法找到目标消息。

## 2.5离线/同步状态提示

同步横幅不观察任何持续状态,**只在每次显式同步(进页面、下拉刷新、点重试)前后置位**:开始显示进度条,成功隐藏,失败显示错误信息和重试按钮。

### 3、账号认证附录

LabSage 使用 Android Keystore 生成不可导出的 AES 密钥，然后将包含 Access Token、Refresh Token、过期时间、当前用户和后端地址的 AuthSession 序列化为 JSON，并通过 AES-GCM 整体加密。Keystore 只管理密钥，不直接保存 Token；加密后的 IV 和密文保存在 SharedPreferences。这里加密的是完整登录状态，不是聊天会话或消息历史。

加密流程：

AuthSession 对象
        ↓ Gson 序列化
JSON 字符串
        ↓
Android Keystore 获取或生成 AES 密钥
        ↓
AES/GCM/NoPadding 加密
        ↓
IV （向量）+ 密文
        ↓ Base64
保存到 SharedPreferences

- IV保证：

  > 即使使用同一个 AES 密钥加密完全相同的内容，每次产生的密文也不同。

这里还有一个并发问题：多个请求可能同时遇到 Token 过期。如果每个请求都单独刷新，就可能出现重复刷新，甚至用旧 Token 覆盖新 Token。因此我用 Mutex 串行处理登录态修改，并在写入前检查当前会话是否仍然是发起刷新时的会话。

请求 A 收到 401，等待 Mutex
请求 B 收到 401，等待 Mutex
请求 C 收到 401，等待 Mutex

请求 A 获得锁
    ↓
使用 R1 刷新
    ↓
保存 A2 + R2
    ↓
释放锁

请求 B 获得锁
    ↓
比较：导致自己 401 的 A1，是否仍是客户端当前的 Access Token
    ↓
发现当前 Token 已经是 A2
    ↓
说明其他请求已经完成刷新
    ↓
不再调用刷新接口，直接使用 A2 重试原请求

### 4、聊天界面的交互做了哪些处理？

我们做的是一个 AI 聊天应用，所以聊天界面的难点不只是把文字显示出来，还要让用户看得懂 AI 正在做什么，同时不能让长回答和流式更新干扰用户操作。

**先说一下最直接的做法，以及问题在哪。**

最简单的实现，是把每个 SSE chunk 都直接交给 Markdown 组件渲染，把后端 trace 原样显示在消息下面，再在内容更新时自动滚到列表底部。

这种方案功能上能跑，但体验并不好。

第一，流式阶段正文一直在变化，如果每次内存快照更新都重新解析 Markdown，会带来不必要的 UI 开销。

第二，原始 trace 对开发者有用，但对普通用户来说信息太杂，很难快速判断 AI 是正在调用工具、已经完成还是出现了中断。

第三，如果每次内容变化都自动滚到底部，用户向上查看历史消息时，界面仍然会把他拉回来，相当于抢走用户的滚动控制权。

**最后的处理思路主要是三点：流式阶段优先保证顺滑、工具过程做结构化展示、自动滚动服从用户意图。**

回答生成期间，正文来自 ViewModel 内存中的 `messages StateFlow`，assistant 消息先按照普通文本展示；流式状态结束后，再使用 Markdown 组件渲染标题、列表和代码块，随后由恢复的 Room Flow 对最终记录进行校准。

这样既保留了最终回答的可读性，也避免在每次流式快照更新时反复解析 Markdown。消息还支持长按复制和分享，用户消息可以重新放回输入框编辑。

**工具调用也没有把后端日志直接堆到页面上。**

ViewModel 会解析 SSE 中几种明确的 trace：

```
开始调用工具
调用完成
执行路线 direct / react
```

收到“开始调用”时，客户端提取调用序号、工具名称和参数，创建一条 `CALLING` 状态，同时记录开始时间。

收到“调用完成”时，按照工具名称找到最早一条仍未结束的同名调用，补充返回结果和执行耗时，再把状态切换为 `COMPLETED`。

这里采用同名调用的 FIFO 匹配，是因为后端完成事件里没有携带调用序号。如果一个工具在同一轮回答中被调用多次，每一次仍然会使用独立 key，不会互相覆盖。

正常收到 Done 时，如果还有工具停留在 `CALLING`，说明这次调用没有完整结束，界面会把它标记为中断。当前实现并没有单独解析所有类型的“工具失败事件”，所以不能把它描述成完整的后端错误日志映射。

界面默认只展示工具执行摘要，包括：

- 当前执行路线；
- 调用中、已完成和中断的数量；
- 工具时间线的整体状态。

用户展开面板后，才会看到每一步的工具名称、输入参数、结果摘要和本地统计的耗时。流式期间，消息附近只保留最近几条 trace 作为即时提示，不会把全部后端日志倾倒到聊天列表中。

这样用户能够知道 AI 当前在查询什么，但不需要阅读原始执行日志。

**滚动是聊天页面最容易踩坑的地方。**（用户滚动意图感知）

当前实现同时维护两个状态：

```
isAtBottom：用户当前是否位于列表底部
autoFollow：后续内容是否应该继续自动跟随
```

用户发送问题或者主动触发 GPU、Conda 等快捷操作时，会重新开启 `autoFollow`，让界面跟随新回答（下滑到最底部）。

如果用户拖动消息列表，**拖动结束后会再次检查他是否仍在底部：**

```
仍在底部 → 继续跟随
已经向上滚动 → 暂停跟随
```

暂停跟随后，界面会显示“返回底部”按钮。只有**用户主动点击，或者再次发送问题时**，才恢复自动跟随（下滑到最底部）。

列表末尾还专门放置了一个 `bottom anchor`。因为最后一条回答可能非常长，如果只调用“滚动到最后一条消息”，LazyColumn 定位到的可能只是这条消息的顶部，而不是整段回答真正的末尾。

滚动策略也根据更新类型进行了区分：

- 首次进入页面时，无动画定位到底部；
- 新消息或者工具面板出现时，可以使用动画平滑滚动；
- SSE 正文持续增长时，直接定位到 bottom anchor，不叠加滚动动画。

如果每个流式快照都调用 `animateScrollToItem()`，前一个动画还没有结束，下一个动画又会进入队列，最终出现回答已经生成完，但界面仍然在慢慢追赶的情况。流式阶段直接定位可以避免这个问题。

