# SSE 50 ms / 500 ms 参数网格与真实 trace 回放报告

本报告包含两个相互独立的实验：

1. **参数网格**（合成负载）：在 3 × 3 个 UI / Room 间隔组合里，证明 50/500 是产品取舍上的折中点。
2. **真实 trace 回放**（录制的真实 SSE 到达时序）：以上线前的旧「每 5 chunk 一写」策略为基线，量化 500 ms checkpoint 在真实到达节奏下减少了多少 Room 写入——直答 **−81.8%**，工具查询簇发 **−97.1%**。

## 结论

当前生产参数继续选择：

```text
UI_INTERVAL_MS = 50 ms
CHECKPOINT_INTERVAL_MS = 500 ms
```

它不是所有指标上的绝对最小值，而是本次网格中更合适的折中点：

- 50 ms：约 20 次 UI 发布/秒。相比 33 ms 少 20% 的发布次数、少 22.99% 的快照字符
  复制；相比 100 ms 则保留了两倍的更新频率，文字流式感更连续。
- 500 ms：约 2 次 Room checkpoint/秒。相比 250 ms 少 48.78% 的持久化操作，同时把
  进程异常退出时尚未保存的恢复窗口控制在约 500 ms；没有像 1,000 ms 方案一样把窗口放大
  到约 1 秒。
- 50 和 500 是整数倍关系。在本次每 10 ms 到达一个 chunk 的负载下，UI 发布与 Room
  checkpoint 可以复用同一份 `StringBuilder.toString()` 快照，避免为两个通道重复复制
  正文。

因此，50/500 的依据不是“人感觉这个数字差不多”，而是：

1. UI 连续感目标：文字流式输出保持约 20 Hz。
2. 容灾目标：进程死亡时最多损失约半秒未 checkpoint 的正文。
3. 本地工作量：相较更激进的 33/250，明显减少快照分配和 Room 持久化。
4. 调度协同：50 ms UI 窗口与 500 ms checkpoint 窗口容易共用快照。

## 测试对象

测试基于当前生产实现：

- SSE chunk 顺序追加到一个 `StringBuilder`。
- UI 到期时生成不可变 String 快照并发布。
- 首个 assistant chunk 执行真实 Room INSERT。
- checkpoint 到期时把完整正文 UPDATE 到同一条 assistant 记录。
- `Done` 执行一次最终 UPDATE。
- Streaming 期间 Room 消息 Flow 已暂停，因此 checkpoint 后不执行全会话查询。
- `Done` 后执行一次与生产 DAO 等价的全会话查询，模拟恢复 Flow 后的权威校准。

测试代码：

- 调度确定性测试：
  [SseUpdateCadenceTest.kt](../app/src/test/java/com/labsage/app/ui/chat/SseUpdateCadenceTest.kt)
- 真实 Room 网格基准：
  [SseCadenceGridBenchmarkTest.kt](../app/src/androidTest/java/com/labsage/app/ui/perf/SseCadenceGridBenchmarkTest.kt)
- 真实 trace 回放 A/B（见下文「真实 trace 回放」一节）：
  [SsePersistenceBenchmarkTest.kt](../app/src/androidTest/java/com/labsage/app/ui/perf/SsePersistenceBenchmarkTest.kt)

## 参数网格

UI 间隔：

```text
33 ms、50 ms、100 ms
```

Room checkpoint 间隔：

```text
250 ms、500 ms、1,000 ms
```

共 3 × 3 = 9 组。

三档 UI 参数分别代表：

- 33 ms：更偏流畅，理论上限约 30 Hz。
- 50 ms：当前方案，理论上限约 20 Hz。
- 100 ms：更偏省资源，理论上限约 10 Hz。

三档 Room 参数分别代表：

- 250 ms：更偏容灾，最多约 4 次 checkpoint/秒。
- 500 ms：当前方案，最多约 2 次 checkpoint/秒。
- 1,000 ms：更偏省写入，最多约 1 次 checkpoint/秒。

## 固定负载

- 设备：Android Emulator `Google sdk_gphone16k_arm64`
- Android API：37
- 构建：Debug instrumented test
- 数据库：真实 Room + SQLite 文件数据库
- 历史消息：500 条
- SSE chunk：1,000 个
- chunk 到达模型：每 10 ms 一个，虚拟持续时间约 10 秒
- 每个 chunk：32 个字符
- 最终正文：32,000 字符
- 预热：5 轮
- 正式测量：30 轮
- 每轮按正序/逆序交替运行9组参数，降低固定顺序和温度偏差

基准不实际等待10秒。chunk 使用虚拟单调时间驱动调度器，毫秒结果只统计客户端本地的：

- `StringBuilder.toString()` 完整快照构建。
- Room INSERT、checkpoint UPDATE 和终态 UPDATE。
- 终态后一次500条历史消息的全会话查询、排序、Cursor遍历和正文消费。
- 调度和循环本身的少量开销。

## 确定性调度结果

| UI间隔 | 10秒内流式UI发布 | 实际发布频率 |
|---:|---:|---:|
| 33 ms | 250 次 | 25 次/秒 |
| 50 ms | 200 次 | 20 次/秒 |
| 100 ms | 100 次 | 10 次/秒 |

33 ms 在本负载下得到25 Hz而不是30.3 Hz，是因为当前策略是事件驱动：只有 chunk 到达时才
检查期限。chunk 每10 ms到达一次，所以33 ms期限实际会在下一个40 ms边界触发。这也是生产
行为，不是 ticker 定时器行为。

| Room间隔 | 中间checkpoint UPDATE | 包含首条INSERT和终态UPDATE |
|---:|---:|---:|
| 250 ms | 39 次 | 41 次 |
| 500 ms | 19 次 | 21 次 |
| 1,000 ms | 9 次 | 11 次 |

作为参照，旧的“每5个chunk”策略是202次总持久化。当前500 ms策略为21次，仍然是
89.60%的持久化次数下降。

## 9组真实Room网格

以下每个耗时均为30轮结果；`Room P50/P95`包含首条INSERT、中间checkpoint和终态UPDATE；
`总P50/P95`还包含快照构建、调度以及终态一次全会话校准查询。

| UI/Room (ms) | UI发布 | 中间UPDATE | 总持久化 | 唯一快照 | 快照复制字符 | 快照P50 | Room P50 | Room P95 | 校准查询P50 | 总P50 | 总P95 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 33/250 | 250 | 39 | 41 | 281 | 4,504,960 | 2.314 ms | 13.683 ms | 21.166 ms | 0.831 ms | 16.681 ms | 25.371 ms |
| 33/500 | 250 | 19 | 21 | 261 | 4,184,320 | 1.890 ms | 7.818 ms | 10.097 ms | 0.820 ms | 10.713 ms | 13.979 ms |
| 33/1000 | 250 | 9 | 11 | 251 | 4,024,000 | 2.015 ms | 4.091 ms | 5.581 ms | 1.042 ms | 6.889 ms | 8.649 ms |
| 50/250 | 200 | 39 | 41 | 201 | 3,222,400 | 1.770 ms | 12.846 ms | 21.180 ms | 1.055 ms | 15.574 ms | 24.390 ms |
| **50/500** | **200** | **19** | **21** | **201** | **3,222,400** | **1.367 ms** | **7.024 ms** | **9.392 ms** | **0.915 ms** | **9.368 ms** | **11.711 ms** |
| 50/1000 | 200 | 9 | 11 | 201 | 3,222,400 | 1.359 ms | 3.885 ms | 6.459 ms | 0.943 ms | 6.690 ms | 8.551 ms |
| 100/250 | 100 | 39 | 41 | 121 | 1,939,840 | 0.887 ms | 11.912 ms | 15.549 ms | 0.737 ms | 13.933 ms | 17.282 ms |
| 100/500 | 100 | 19 | 21 | 101 | 1,619,200 | 0.726 ms | 5.909 ms | 7.063 ms | 0.770 ms | 7.829 ms | 9.366 ms |
| 100/1000 | 100 | 9 | 11 | 101 | 1,619,200 | 0.685 ms | 3.791 ms | 5.589 ms | 0.725 ms | 5.486 ms | 7.262 ms |

所有9组均满足：

- 终态只执行1次权威全会话查询。
- 最终 Room 正文与32,000字符的 `StringBuilder` 正文完全一致。
- Streaming 期间没有模拟或强制执行checkpoint后的全会话查询。

## 关键对比

### 50/500 对比 33/500

Room恢复窗口相同，主要比较 UI 频率：

- UI发布：250 → 200，减少20.00%。
- 快照复制字符：4,184,320 → 3,222,400，减少22.99%。
- 快照构建P50：1.890 ms → 1.367 ms，减少27.67%。
- 总P50：10.713 ms → 9.368 ms，减少12.55%。
- 总P95：13.979 ms → 11.711 ms，减少16.22%。

33 ms在此chunk模型下提供25 Hz，而50 ms已经有20 Hz。对逐字增长文本而言，额外5次/秒
发布带来的观感收益有限，却增加约23%的快照字符复制，因此没有选择33 ms。

### 50/500 对比 100/500

- UI发布：200 → 100，100 ms方案减少50%。
- 快照复制字符：3,222,400 → 1,619,200，减少49.75%。
- 总P50：9.368 ms → 7.829 ms，减少16.43%。

100 ms确实更省本地工作，但只剩10 Hz。节省的是约1.54 ms/整段10秒虚拟回答的本地处理
中位数，却让相邻文字更新间隔翻倍。当前选择50 ms，优先保证流式连续感。

### 50/500 对比 50/250

UI频率相同，主要比较checkpoint：

- 总持久化：41 → 21，减少48.78%。
- Room P50：12.846 ms → 7.024 ms，减少45.32%。
- 总P50：15.574 ms → 9.368 ms，减少39.85%。
- 总P95：24.390 ms → 11.711 ms，减少51.98%。
- 代价：异常退出时未保存窗口从约250 ms增加到约500 ms。

250 ms把持久化工作接近翻倍，而改善的只是额外250 ms恢复窗口，因此没有选择250 ms。

### 50/500 对比 50/1000

- 1,000 ms把总持久化从21次进一步降到11次，减少47.62%。
- Room P50从7.024 ms降到3.885 ms，减少44.69%。
- 总P50从9.368 ms降到6.690 ms，减少28.59%。
- 代价：异常退出时未保存窗口从约500 ms增加到约1秒。

这组数据说明1,000 ms在纯性能上更好。但一段10秒回答只额外节省约2.68 ms本地处理中位
时间，却把可能丢失的未保存内容窗口翻倍。因此当前选择500 ms，而不是追求最低写入次数。

## 为什么50/500是折中点，而不是“最优解”？

如果评价函数只看本地耗时，100/1000是本次网格最低值；如果只看UI更新频率和恢复窗口，
33/250更激进。

实际产品同时有三个目标：

| 目标 | 过小的间隔 | 过大的间隔 |
|---|---|---|
| UI连续感 | 更新频繁，快照分配增加 | 文字跳动更明显 |
| Room成本 | UPDATE次数增加 | UPDATE次数减少 |
| 进程死亡恢复 | 未保存窗口更小 | 可能丢失更多尾部正文 |

50/500是“约20 Hz文字更新 + 约2 Hz容灾保存 + 约500 ms恢复窗口”的产品取舍。报告证明
了这个取舍在当前固定负载下的本地成本，但不宣称它是所有设备和所有回答上的全局最优值。

## 真实 trace 回放：旧「每 5 chunk 一写」 vs 500 ms checkpoint

参数网格回答的是「50/500 相对其他组合如何」；这一节回答另一个问题：**相对上线前的旧策略，
500 ms checkpoint 在真实到达节奏下到底省了多少 Room 写入**。合成负载按每 10 ms 一个 chunk
均匀到达，而真实 SSE 远非均匀：直答在首 token 之前有数秒空等、正文内部有 200 ms 级停顿；
工具查询的正文则在工具执行完毕后几乎一次性簇发到达。

### 录制方式

`scripts/record_sse_trace.py` 直连本地真实后端 `POST /api/chat_stream`，记录每个 `message`
事件相对首事件的到达时刻（ms）与文本、`trace` 事件数与 `done` 时刻，写成 JSON 放入
`app/src/androidTest/assets/`，由仪器测试直接读取：

```bash
python3 scripts/record_sse_trace.py "你的问题" app/src/androidTest/assets/sse_trace_xxx.json
```

| trace | 问题 | 正文 chunk | 正文字符 | trace 事件 | 首个正文 chunk | 末个正文 chunk | `done` |
|---|---|---:|---:|---:|---:|---:|---:|
| `sse_trace_direct.json` | 用一句话介绍你自己，并说明你可以查询哪些实验室信息 | 54 | 93 | 10 | 3,407 ms | 4,166 ms | 4,169 ms |
| `sse_trace_react.json` | 查一下现在的 GPU 使用情况并总结 | 166 | 265 | 12 | 7,998 ms | 7,999 ms | 8,002 ms |

两条 trace 均录制于 2026-11-01 18:55，同一后端。到达特征：

- **direct**：前 3.4 s 没有任何正文（路由与首 token 之前的等待），随后 54 个 chunk 在 759 ms
  内到达，其间最大停顿 216 ms，另有 6 次 50–100 ms 的停顿。
- **react**：前 8 s 只有 `trace` 事件（路由 → 调用 GPU 查询工具 → 工具返回），随后 166 个正文
  chunk 在 **1 ms** 内全部到达——相邻 chunk 间隔全部为 0。

### 回放方式

[SsePersistenceBenchmarkTest.kt](../app/src/androidTest/java/com/labsage/app/ui/perf/SsePersistenceBenchmarkTest.kt)
把同一份 trace 分别喂给两种策略，生成各自的写入序列：

- **legacy**：每 5 个 chunk 执行一次 `UPDATE`（`index > 0 && (index + 1) % 5 == 0`），即被替换的旧实现。
- **optimized**：生产 `SseUpdateCadence`，距上次 checkpoint ≥ 500 ms 才 `UPDATE`。

首个 chunk 在两种策略下都走 INSERT 并作为 checkpoint 起点，不计入写入；终态 `done` 的最终
UPDATE 两边各计 1 次。两条写入序列在同一个含 500 条历史消息的真实 Room 文件数据库上执行，
每次 UPDATE 后紧跟一次与生产 DAO 等价的全会话查询（模拟 Room 失效驱动的快照重建），2 轮预热
后正式测量 30 轮，轮内交替 legacy / optimized 先后顺序以抵消 JIT 和温度偏差。每个场景结束后
断言 Room 中的最终正文与 SSE 累积正文逐字一致。

同时保留一组与网格报告等价的合成负载（1,000 chunk × 10 ms × 32 字符），用于和上面 9 组
网格对表。

### 写入次数

写入次数只由 trace 的到达时刻决定，与设备无关——下表可以只用 trace 文件和上面两条规则离线
复算：

| 场景 | 来源 | chunk | 时长 | legacy 写入 | 500 ms 写入 | 降幅 |
|---|---|---:|---:|---:|---:|---:|
| direct | 真实 trace | 54 | 4,166 ms | 11 | 2 | **−81.8%** |
| react | 真实 trace | 166 | 7,999 ms | 34 | 1 | **−97.1%** |
| synthetic-1000@10ms | 合成 | 1,000 | 10,000 ms | 201 | 20 | **−90.05%** |

三点解释：

- **react 只剩 1 次写入，即终态 UPDATE**：正文在 1 ms 内簇发，没有任何 chunk 与首个 chunk
  相隔 ≥ 500 ms，于是整个流式期间 0 次中间 checkpoint。旧策略对簇发毫无抵抗，166 个 chunk
  机械地产生 33 次中间 UPDATE，每次都带动一次全会话查询。这就是优化在工具查询上收益最大的原因，
  也是 README 里「工具查询簇发 −97.1%」的出处。
- **direct 有 2 次**：正文跨度 759 ms，只跨过一次 500 ms 边界，得到 1 次中间 checkpoint + 1 次
  终态。旧策略同样不看时间，54 个 chunk 产生 10 次中间 UPDATE。
- **合成负载 201 → 20 与网格一节的 202 → 21 相差的 1 次**，是口径差异：网格把首条 INSERT 计入
  「总持久化」，本节 A/B 两边共用 INSERT 而不计。两种口径的降幅分别为 90.05% 与 89.60%，指向同一
  件事。

### 口径说明

- 与网格一样，只测客户端本地写入与随之而来的查询，不含网络等待与服务端生成时间。
- 30 轮的 Room 耗时中位数 / P95 随设备和模拟器状态变化，本报告只把**写入次数**作为可跨设备复现
  的结论；耗时以本机运行时 logcat（tag `SsePerf`）输出为准，不在此固化。
- 降幅的方向性由测试内断言保证：`check(optimizedWrites <= legacyWrites)`。定时 checkpoint 的
  写入次数是 O(时长 / 500 ms)，按 chunk 计数的旧策略是 O(chunk 数)，前者永远不会多于后者。
- 该测试不依赖真实后端（trace 已录制为资源文件），可在任何模拟器上复测：

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  -e class com.labsage.app.ui.perf.SsePersistenceBenchmarkTest \
  com.labsage.app.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s SsePerf
```

## 复现流程

### 1. 确认设备

```bash
adb devices -l
```

本次使用：

```text
Google sdk_gphone16k_arm64，API 37
```

### 2. 运行调度和回归测试

```bash
./gradlew testDebugUnitTest --no-daemon
```

### 3. 编译并运行9组真实Room网格

```bash
./gradlew connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.labsage.app.ui.perf.SseCadenceGridBenchmarkTest
```

### 4. 提取网格结果

```bash
rg -n "SseCadenceGrid:" \
  app/build/outputs/androidTest-results/connected/debug \
  -g '*.txt'
```

日志中以 `grid` 开头的CSV字段顺序为：

```text
uiMs, checkpointMs, uiPublishes, checkpointUpdates, persistenceOps,
reconcileQueries, uniqueSnapshots, copiedChars, snapshotMedianMs,
roomMedianMs, roomP95Ms, reconcileMedianMs, totalMedianMs, totalP95Ms
```

### 5. 控制变量

复测时应保持：

- 同一设备或同一模拟器配置。
- 同一构建类型。
- 模拟器没有同时执行其他高负载任务。
- 5轮预热和30轮正式测量不变。
- 500条历史消息、1,000个chunk和32字符/chunk不变。
- 比较中位数和P95，不选择单轮最好成绩。

## 测试边界

本报告测量的是客户端本地调度、String快照、真实Room文件数据库和最终查询，不包含：

- 真实网络等待或服务端生成时间。
- OkHttp线程调度和真实SSE解析耗时。
- Compose测量、布局、绘制和Markdown解析。
- 页面FPS、慢帧率或主线程CPU时间。
- 真机闪存与模拟器SQLite之间的差异。

因此可以写成“在固定长回答和500条历史消息下，50/500将UI发布限制为20次/秒，将总
持久化限制为21次，并在终态只做一次全会话校准查询”。不能把本报告的9.368 ms直接表述为
用户看到回答快了多少，也不能表述成FPS提升比例。

如果需要进一步验证用户感知，应增加 Macrobenchmark/Perfetto，对9组参数测量：

- P95帧耗时。
- 慢帧率和冻结帧率。
- 主线程CPU时间。
- Markdown长文本重组和布局耗时。

