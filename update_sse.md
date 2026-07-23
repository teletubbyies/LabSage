# SSE 流式链路性能优化

## 目标

当前聊天正文按 SSE chunk 顺序消费，并且每 5 个 chunk 将截至当前的完整回答写入 Room。Room 更新会触发表失效通知、当前会话消息重新查询、实体到 UI 模型映射和 Compose 状态更新。与此同时，`OkHttpSseDataSource` 使用无限 Channel 缓冲；当数据库消费变慢时，事件积压没有内存上限。

本次优化不修改后端协议，采用“双速率更新”：

- SSE chunk 到达后立即追加到内存 `StringBuilder`。
- 最多每 50 ms 将最新正文快照直接发布到内存 UI State，保持约 20 FPS 的流式观感。
- 最多每 500 ms 将正文 checkpoint 写入 Room，保留进程异常退出后的恢复能力。
- `Done`、`Error` 或连接异常时绕过节流，强制发布并落盘最终内容。
- Room 发射数据库快照时，将正在生成的内存正文合并回 UI，避免旧 checkpoint 短暂覆盖更新的正文。
- SSE Channel 改为固定容量，并检查 `trySend` 结果，消除无限积压。

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
- 新策略：每 500 ms checkpoint，加首条和终态强制落盘，目标不超过 22 次。
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

待实现与测试完成后填写，禁止使用理论值冒充实测值。
