# Markdown 上传性能优化实施计划

## 目标

在不修改后端 API 的前提下，保持现有 `POST /api/upload`、`multipart/form-data` 和字段名 `file` 不变，优化 Android 客户端上传链路：

- 消除主线程上的 ContentProvider 查询和文件复制。
- 删除 `readBytes()` 造成的 O(文件大小) 堆内存。
- 已知文件大小时直接从 Content URI 流式写入 OkHttp。
- 文件大小未知时安全降级到 IO 线程临时文件，并在所有终态清理。
- 限制上传内容为 Markdown，限制单文件最大 10 MiB。
- 阻止同一 ViewModel 中重复并发上传，并让上传中/失败状态在界面可见。

## 当前链路

```text
Content URI
→ 主线程查询元数据
→ 主线程复制到 cacheDir
→ readBytes() 全量进入 Java Heap
→ Multipart RequestBody
→ Retrofit / OkHttp
```

## 目标链路

```text
文件大小已知：
Content URI
→ IO 线程查询元数据
→ 可重放 StreamingRequestBody
→ OkHttp sink

文件大小未知：
Content URI
→ IO 线程限额复制到 UUID 临时文件
→ File.asRequestBody()
→ 请求结束或取消后 finally 清理
```

## 实施步骤

1. 新增与 Android 无关、可单元测试的 `StreamingRequestBody`：
   - 每次 `writeTo()` 都重新打开 Source，兼容认证后请求重放。
   - 暴露准确的 `contentLength` 和 `contentType`。
2. 新增限额复制工具：
   - 固定大小缓冲区复制。
   - 超过 10 MiB 立即失败，不继续写入。
3. 重构 `ChatRepository.uploadFile()`：
   - 在 `Dispatchers.IO` 查询 `DISPLAY_NAME` 和 `SIZE`，请求类型固定为 `text/markdown`。
   - 校验 `.md` / `.markdown`、非空文件和大小上限。
   - 大小已知时直接构造流式 RequestBody。
   - 大小未知时创建 UUID 临时文件并使用 `File.asRequestBody()`。
   - 对协程取消重新抛出 `CancellationException`。
   - `finally` 在 `NonCancellable + Dispatchers.IO` 清理临时文件。
4. 调整 `ChatViewModel` 和 `ChatScreen`：
   - Uploading 状态下拒绝重复上传。
   - 页面收集 `UploadState`。
   - 上传中禁用附件按钮并显示进度提示。
   - 上传失败显示可关闭错误。
5. 测试：
   - 流式 RequestBody 内容、长度、类型正确。
   - 同一 RequestBody 可重复写出，且每次重新打开 Source。
   - 限额复制在边界内成功，超过上限失败。
   - 运行全部 JVM 单元测试和现有 Android 性能测试。

## 验收指标

- 上传准备阶段主线程文件 I/O：0。
- 已知大小路径临时文件字节数：N → 0。
- 完整文件 `ByteArray` 分配：N → 0。
- 额外空间复杂度：O(文件大小) → O(固定缓冲区)。
- RequestBody 重放两次时内容一致。
- 元数据声明超过 10 MiB 的 Markdown 在发送网络请求前被拒绝；流式路径同时校验实际长度与声明长度一致。
- 后端接口、Multipart 字段名和响应模型保持不变。

## 非目标

- 不修改 Go 后端。
- 不实现分片、断点续传、WorkManager 或前台服务。
- 不增加客户端 SHA-256 去重、gzip、并行上传或新的 OkHttpClient。
- 不声称真实总响应时间提升；后端 embedding/Milvus 耗时不属于本次客户端优化范围。

## 实验结果

实现后已在 Android 17 / API 37 Pixel 8 AVD 的 ART runtime 上完成 A/B 实验：

- 1、5、10 MiB Markdown，每种实现每种大小预热 3 次、正式测量 30 次，共 180 条数据。
- 客户端文件准备和 RequestBody 写出中位耗时降低 50.3%～59.1%。
- 峰值 Java 堆增量中位数降低 98.7%～99.1%。
- 已知大小文件额外临时磁盘占用由文件等量降低为 0。
- 每次输出均通过长度与原文件 SHA-256 校验。

原始数据见 `upload-benchmark-results.csv`，完整方法、边界和复现命令见
`docs/UPLOAD_BENCHMARK.md`。耗时数据不包含网络和后端 embedding/Milvus 阶段，
不得直接表述为线上接口端到端耗时提升。
