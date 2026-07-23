# SSE 流式链路性能优化

## 目标

当前聊天正文按 SSE chunk 顺序消费，并且每 5 个 chunk 将截至当前的完整回答写入 Room。Room 更新会触发表失效通知、当前会话消息重新查询、实体到 UI 模型映射和 Compose 状态更新。与此同时，`OkHttpSseDataSource` 使用无限 Channel 缓冲；当数据库消费变慢时，事件积压没有内存上限。

本次优化不修改后端协议，采用“双速率更新”：

- SSE chunk 到达后立即追加到内存 `StringBuilder`。
- 最多每 50 ms 将最新正文快照直接发布到内存 UI State，保持约 20 FPS 的流式观感。
- 最多每 500 ms 将正文 checkpoint 写入 Room，保留进程异常退出后的恢复能力。
- `Done`、`Error` 或连接异常时绕过节流，强制发布并落盘最终内容。
- Room 发射数据库快照时，将正在生成的内存正文合并回 UI，避免旧 checkpoint 短暂覆盖更新的正文。
- SSE Channel 改为固定 512 个事件，并检查 `trySend` 结果，消除无限积压。

## 选型理由

不对整个 `Flow<SseEvent>` 使用 `conflate()`，因为正文、工具状态和 `Done/Error` 是有顺序的事件，任意丢弃会破坏语义。也不通过增大 OkHttp 连接池或缓存 `EventSource.Factory` 制造微小优化；单条聊天通常只有一个 SSE 连接，主要成本在高频持久化与 Room 回流。

双速率方案让三个频率彼此独立：

| 链路 | 上限 | 目的 |
|---|---:|---|
| SSE 接收 | 服务端实际速率 | 不延迟网络数据 |
| UI 快照 | 20 次/秒 | 保持连续流式显示 |
| Room checkpoint | 2 次/秒 | 限制磁盘写入与全消息查询 |

## 正确性约束

1. 首个正文 chunk 立即显示并创建可恢复的 Room 占位消息。
2. UI 中的正文不得因为 Room 的旧 checkpoint 发射而倒退。
3. `Done/Error` 后 UI、Room 和 `accumulatedAnswer` 必须完全一致。
4. 继续生成时必须从已持久化的 partial 内容追加，不能覆盖原内容。
5. Channel 满或已关闭时不得静默丢事件；应取消连接并暴露可重试错误。
6. Trace 仍在进入 Channel 前过滤，终止事件不得被合并或丢弃。

## 性能基线与验收

固定负载为 1,000 个正文 chunk、每 10 ms 一个、总持续时间约 10 秒：

- 旧策略：每 5 chunk checkpoint，理论 Room 更新 200 次。
- 新策略：每 500 ms checkpoint，加首条和终态强制落盘，目标不超过 21 次。
- 目标数据库更新次数下降至少 85%。
- UI 快照不超过约 202 次，且最终正文逐字一致。
- SSE Channel 固定容量，峰值内存不再随未消费事件数无限增长。

除确定性的调度测试外，还需在 Android 模拟器上使用真实 Room 执行相同数量、相同正文增长曲线的 `UPDATE`，记录旧/新策略的总耗时和写入次数。耗时结果只用于同设备、同构建、同轮次的前后对比。

## 可复现命令

实现完成后，运行：

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.labsage.app.ui.perf.SsePersistenceBenchmarkTest
```

测试日志使用 `SsePerf` 标签。结果汇总到本文末尾，包含设备、Android 版本、chunk 数、正文大小、预热轮数、测量轮数、中位数和 P95。

## 实际结果

### 测试环境

- 设备：Android Emulator `Google sdk_gphone16k_arm64`
- Android API：37
- 构建：Debug instrumented test
- 数据库：真实 Room + SQLite 文件数据库（测试结束后删除）
- 历史消息：500 条
- SSE 正文：1,000 chunk × 32 字符 = 32,000 字符
- 发送模型：每 10 ms 一个 chunk，总时长约 10 秒
- 预热：5 轮
- 测量：30 轮；旧策略和新策略交替执行，降低顺序与温度偏差

每次 checkpoint 都执行真实 `MessageDao.updateContent()`，随后强制执行并遍历一次与生产
`getMessagesByConversation()` 等价的全会话查询，确保结果包含 UPDATE、排序、Cursor 读取和
500 条历史消息内容消费，而不只是调度器计数。

### 实测数据

| 指标 | 旧策略：每 5 chunk | 新策略：500 ms checkpoint | 改善 |
|---|---:|---:|---:|
| 中间 checkpoint + 终态 UPDATE | 201 次 | 20 次 | **90.05%** |
| 包含首条 INSERT 的总持久化次数 | 202 次 | 21 次 | **89.60%** |
| Room 写入 + 全会话快照中位耗时 | 176.13 ms | 18.42 ms | **89.54%** |
| Room 写入 + 全会话快照 P95 | 224.79 ms | 23.83 ms | **89.40%** |
| 最终正文一致性 | 32,000 字符 | 32,000 字符 | 完全一致 |
| UI 快照调度次数 | 依赖 Room 回流 | 200 次上限 | 约 20 次/秒 |
| SSE 缓冲 | `Channel.UNLIMITED` | 固定 512 事件 | 内存上限固定 |

调度单元测试得到旧策略总持久化 202 次、新策略 21 次，与真实 Room 基准中的
201/20 次 UPDATE 差异正好是双方共同的首条 INSERT。最终正文逐字一致。

### 结果边界

以上耗时是同一进程、同一模拟器中的“Room 持久化 + 强制全会话快照”对比，适合证明本次
改动减少了多少客户端本地工作量。它不是网络 TTFT、服务端生成速度或整页 FPS 数据，不应
扩写成这些指标。不同设备的绝对毫秒值会变化，写入次数和最终一致性则是确定性结果。

### 完整复现方法

1. 启动一个可用的 Android 模拟器或连接真机。
2. 确认设备：

   ```bash
   adb devices
   ```

3. 运行调度与全部 JVM 回归测试：

   ```bash
   ./gradlew testDebugUnitTest
   ```

4. 运行真实 Room 对比基准：

   ```bash
   ./gradlew connectedDebugAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.class=com.labsage.app.ui.perf.SsePersistenceBenchmarkTest
   ```

5. 查看本轮结果：

   ```bash
   rg -n "SsePerf" app/build/outputs/androidTest-results/connected/debug -g '*.txt'
   ```

6. 为减少设备波动，保留测试内的5轮预热和30轮交替测量；比较中位数和P95，不使用单轮最优值。
