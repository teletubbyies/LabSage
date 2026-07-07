# LabSage 小白学习路线（技能维度 · 15 天 105h 版）

> **这份和 `STUDY_PLAN_15D.md` 的区别**：
> - **日程表**（`STUDY_PLAN_15D.md`）= 时间维度，告诉你 **Day N 干什么**。
> - **学习路线**（本文件）= 技能维度，告诉你 **先学什么后学什么、去哪学、学到什么程度就停**（防止钻牛角尖浪费 105 小时）。
>
> **怎么配合用**：先用本文件搞清楚"要掌握哪些技能、依赖关系、去哪学"，再用日程表把它们排进 15 天。两个文件指的是同一桶 105 小时，只是两种切片。

---

## 0. 先看技能依赖图（搞清先后顺序）

```
                        【前置地基 —— 不学这个，项目读不懂】
            ┌──────────────────┬──────────────────┐
            ▼                  ▼                  ▼
       ① Kotlin 进阶     ② 协程 + Flow      ③ Compose 基础
       (data class/       (这个最关键!        (@Composable/
        sealed/lazy)       小白最大短板)       状态/重组)
            │                  │                  │
            └──────────┬───────┴────────┬─────────┘
                       ▼                ▼
              【数据层 —— 存和取】   【UI 层 —— 显示】
            ┌──────────────────┐  ┌──────────────────┐
            ▼                  ▼  ▼                  ▼
       ④ Room           ⑤ OkHttp/Retrofit  ⑥ MVVM+ViewModel+StateFlow
       (双表/级联)       (网络基础)         (把数据和UI串起来)
            │                  │                  │
            └──────────┬───────┘                  │
                       ▼                           │
              【项目核心 —— 灵魂】                  │
                   ⑦ SSE 流式 ◄────────────────────┘
              (callbackFlow/背压)
                       │
                       ▼
              【进阶 —— 拔高】
            ⑧ Compose 性能优化 (conflate/@Immutable)
            ⑨ DataStore / 设置 / 工具面板
                       │
                       ▼
              【输出 —— 临门一脚】
            ⑩ Bug 故事 + 八股问答 + 陈述
```

**核心规律**：①②③ 是地基，**先学完再碰项目核心**。⑦ SSE 同时依赖 ②（Flow）和 ⑥（ViewModel），是整条路线的枢纽。⑩ 必须最后，前面的东西没学会，讲故事就是背的。

---

## 1. 阶段 A：前置地基（约 35h，Day 1~4）

> 你看过《第一行代码》，所以 Activity、基本 UI、SQLite、Intent 这些**默认你会**。这里只补项目用到但你没真掌握的。

### ① Kotlin 进阶（5h）

- **学什么**：`data class`、`sealed class`、扩展函数、作用域函数（`let`/`also`/`apply`）、属性委托（`by lazy`）、空安全。
- **去哪学**：`kotlinlang.org/docs/home.html`（有中文，左侧目录挑这几个）。
- **够用标准**：看到 `val x by lazy {}`、`sealed class State { object A; data class B(...) }` 不犯怵，能解释。
- **对应项目**：`StreamState`（sealed class）、`LabSageApplication` 里大量 `by lazy`。
- ⚠️ **别钻**：协程相关的 Kotlin 语法留到 ②，不在这学；泛型/反射不需要。

### ② 协程 + Flow（15h）⭐ 最关键，时间给足

> 这是整个项目的命脉。SSE 用 Flow 接，Room 用 Flow 观察，ViewModel 用 `viewModelScope.launch`。**这一关不过，项目 70% 读不懂。**

- **学什么**：
  - 协程：`suspend` 函数、`launch`、`CoroutineScope`、`Dispatchers`（IO/Main）、`viewModelScope`。
  - Flow：冷流、`collect`、`flowOn`、`catch`、操作符（`map`/`filter`/`flatMapLatest`/`conflate`/`buffer`）。
  - StateFlow（区别于普通 Flow，UI 状态专用）。
- **去哪学**（按顺序，都是 Google 官方、稳定免费）：
  1. `developer.android.com` → 搜 **"Kotlin Coroutines"**（文字教程 + 视频）。
  2. `developer.android.com/codelabs` → 做 **"Kotlin Coroutines and Room"** codelab（动手）。
  3. 同站 **"Advanced Coroutines with Kotlin Flow"** codelab（Flow 进阶）。
- **够用标准**：能回答——"协程和线程的区别""`launch` 和 `suspend` 的关系""`Flow.collect` 为什么不会阻塞""`conflate()` 和 `buffer()` 区别""StateFlow vs LiveData"。
- **对应项目**：第 3 章主线、第 4 章 SSE、第 6 章性能优化全是这的知识。
- ⚠️ **别钻**：`Channel` 的底层实现、`select`、自定义 `CoroutineInterceptor`——会用就行，别啃源码。

### ③ Jetpack Compose 基础（15h）

> 如果你的《第一行代码》是 XML 版（很可能），Compose 是**全新东西**，不能跳。

- **学什么**：`@Composable`、`remember`/`mutableStateOf`、状态驱动 UI、重组（recomposition）、`Column`/`Row`/`LazyColumn`、`Modifier`、副作用（`LaunchedEffect`/`DisposableEffect`）、主题。
- **去哪学**（Google 官方，强烈推荐，专为入门设计）：
  1. `developer.android.com/courses/pathways/compose` —— **Jetpack Compose Pathway**（一整套，从头到尾，跟着做）。
  2. codelab **"Jetpack Compose Basics"**。
- **够用标准**：能用 Compose 从零写一个"输入框 + 按钮 + 列表显示输入历史"的小 Demo；能解释"状态变了 UI 为什么自动更新"。
- **对应项目**：整个 `ui/` 目录、第 6 章。
- ⚠️ **别钻**：自定义 Layout、动画 API 全家桶、`CompositionLocal` 深原理——项目用到的部分会就行。

---

## 2. 阶段 B：数据层（约 20h，Day 5~6 + Day 9）

### ④ Room（8h）

- **学什么**：`@Entity` / `@Dao` / `@Database`、`@Insert`/`@Update`/`@Query`、返回 `Flow<List<T>>` 响应式观察、外键 `ForeignKey` + `CASCADE`、`Migration`、`@ConflictStrategy` 的坑。
- **去哪学**：
  1. `developer.android.com/codelabs` → **"Room with a View"**（经典入门 codelab）。
  2. `developer.android.com/training/data-storage/room`（文档，挑"关系/迁移"看）。
- **够用标准**：能从零建一个双表（带外键）的 Room 数据库；能讲 `OnConflictStrategy.REPLACE` 为什么危险（见项目 Bug 9.2）。
- **对应项目**：第 5 章全部。

### ⑤ OkHttp + Retrofit（7h）

- **学什么**：OkHttp 请求/响应/拦截器/连接池；Retrofit 接口定义 + Converter（Gson）；`MultipartBody`（文件上传）。
- **去哪学**：
  1. `square.github.io/okhttp/`（官方，看"Interceptors""Connections"）。
  2. `square.github.io/retrofit/`（官方首页 + 用例）。
- **够用标准**：能写一个 Retrofit 接口调 GET/POST；能讲"为什么用 `newBuilder()` 派生客户端能共享连接池"。
- **对应项目**：第 7 章、文件上传（8.5）。

### ⑥ DataStore（5h）

- **学什么**：`Preferences DataStore` 读写、`preferencesDataStore` 委托、Flow 读取、为什么不用 SharedPreferences。
- **去哪学**：`developer.android.com/topic/libraries/architecture/datastore`（官方，一页讲清）。
- **够用标准**：能讲 DataStore vs SharedPreferences 三条理由（线程/ANR/Flow）。
- **对应项目**：设置页（8.4）。

---

## 3. 阶段 C：项目核心（约 30h，Day 3~4 + Day 7~9）

> 这一段开始**以项目为主，外部资源为辅**——地基打好后，重点啃 `INTERVIEW_GUIDE.md` 对应章节 + 项目源码。

### ⑦ SSE 流式 + MVVM（18h）⭐ 项目灵魂

- **学什么**（合并 MVVM，因为 SSE 强依赖 ViewModel）：
  - MVVM：ViewModel 生命周期、为什么活过屏幕旋转、`viewModelScope`、Repository 模式、`StateFlow`。
  - SSE：什么是 SSE、OkHttp `okhttp-sse` 库、`callbackFlow` 把回调变 Flow、`trySend`/`awaitClose`、背压、`Channel` 容量策略。
- **去哪学**：
  1. `developer.android.com/topic/architecture` —— **"Guide to app architecture"**（官方架构指南，必读，MVVM + Repository 的权威来源）。
  2. `developer.android.com/topic/libraries/architecture/viewmodel`（ViewModel 文档）。
  3. SSE 部分基本**只有项目文档**（`INTERVIEW_GUIDE.md` 第 4 章），网上 Android SSE 教程质量参差，以项目为准。
- **够用标准**：能讲"为什么用 SSE 不用 WebSocket""callbackFlow 怎么把回调变 Flow""trySend 为什么丢数据、你三层怎么解决""为什么手动 DI 不用 Hilt"。
- **对应项目**：第 2、3、4、7 章。

### ⑧ Compose 进阶 + 性能（12h）

- **学什么**：`LazyColumn` 高级用法、`collectAsStateWithLifecycle`、`@Immutable`/`@Stable`/`derivedStateOf`、重组性能、暗黑模式、`Layout Inspector` 调试。
- **去哪学**：
  1. `developer.android.com/jetpack/compose/performance`（官方性能文档）。
  2. `developer.android.com/jetpack/compose/lifecycle`（`collectAsStateWithLifecycle`）。
- **够用标准**：能用 Layout Inspector 看到 `conflate()` 前后重组次数变化；能讲四层性能优化。
- **对应项目**：第 6 章。

---

## 4. 阶段 D：面试输出（约 20h，Day 12~15）

> 不学新技术，**只学怎么把学过的讲出来**。这部分没有"知识"要学，全是练习。

### ⑨ Bug 故事讲述（6h）

- **学什么**：把 `INTERVIEW_GUIDE.md` 第 9 章 7 个 Bug，每个练成"现象→根因→修复→收获"四段话术，**脱稿能讲**。
- **够用标准**：随便点一个 Bug，对着空气讲 2 分钟不卡。
- ⚠️ **必须亲手复现过**（见日程表 5 个必做实验），否则一问细节就崩。

### ⑩ 八股问答内化（8h）

- **学什么**：第 11 章 25 题，**遮答案、口头答、录音回放**。
- **够用标准**：25 题答上 20 题。

### ⑪ 自我陈述（6h）

- **学什么**：第 12 章 30 秒/2 分钟/5 分钟三版，背到自然不背书感。
- **够用标准**：2 分钟陈述脱口而出，被追问能切到深挖话术。

---

## 5. 小白避坑：这些别花时间钻（省下来的时间给 ② 协程）

| 别钻 | 为什么 | 够用就行 |
|------|--------|---------|
| Hilt / Dagger 2 全套 | 你项目是手动 DI，用不到 | 知道"它是什么、你为什么不用"即可 |
| Compose 自定义 Layout / 复杂动画 | 项目没用到 | 会用 Material 组件就行 |
| Kotlin 泛型、反射、内联类 | 项目几乎不用 | 跳过 |
| Room 的 `@Relation`/`@Embedded` 复杂关系 | 项目是简单双表 | 双表 + 外键够了 |
| OkHttp/Retrofit 源码 | 八股问到知道即可 | 会用 + 知道连接池概念 |
| 协程 `Channel` 源码、`select` | 理解背压策略即可 | 会用 `buffer`/`conflate` |
| 后端的 Go / Milvus / ReAct 细节 | 那是后端 | 知道"后端这么干，我这么接" |

---

## 6. 学习资源汇总清单（全是官方免费稳定资源）

| 资源 | 学什么 | 在哪 |
|------|--------|------|
| Kotlin 官方文档 | Kotlin 进阶语法 | `kotlinlang.org/docs`（有中文） |
| Android 开发者 —— Coroutines | 协程 | `developer.android.com` 搜 "Kotlin Coroutines" |
| Codelab: Kotlin Coroutines and Room | 协程动手 | `developer.android.com/codelabs` |
| Codelab: Advanced Coroutines with Flow | Flow 进阶 | 同上 |
| **Compose Pathway** | Compose 系统入门 | `developer.android.com/courses/pathways/compose` ⭐ |
| Codelab: Jetpack Compose Basics | Compose 动手 | `developer.android.com/codelabs` |
| Codelab: Room with a View | Room 入门 | 同上 |
| Android 文档: DataStore | 设置存储 | `developer.android.com/topic/libraries/architecture/datastore` |
| OkHttp / Retrofit 官网 | 网络 | `square.github.io/okhttp`、`square.github.io/retrofit` |
| **Guide to app architecture** | MVVM/Repository 权威 | `developer.android.com/topic/architecture` ⭐ |
| Compose 性能文档 | 性能优化 | `developer.android.com/jetpack/compose/performance` |

> **找法**：上面带域名的直接进；codelab 在 `developer.android.com/codelabs` 里按名字搜。都是 Google 长期维护的，稳定可靠，不用翻墙也能看（中文版部分有）。

---

## 7. 一句话总结

**地基（①②③）扎实了，项目（④⑤⑥⑦⑧）就是把这些积木搭起来，最后（⑨⑩⑪）练怎么讲。** 你是小白，最大的风险不是项目难，而是**地基（尤其协程）没补够就去啃项目，结果每个字都认识、连起来不懂**。所以：**前 4 天宁可慢，也要把协程和 Compose 这两个地基浇透**，后面 11 天会顺很多。

配合 `STUDY_PLAN_15D.md` 的逐日安排，每天对照"今天在路线图的哪个模块"，心里就有底了。🎯
