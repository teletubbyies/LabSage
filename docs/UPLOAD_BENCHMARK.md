# Markdown 上传客户端 A/B 实验

## 结论

当前流式实现对“客户端准备文件并写出 RequestBody”有效，不是只为极端条件设置的装饰性优化。

在 Android ART 上，以 1、5、10 MiB Markdown 各执行 3 次预热和 30 次正式测量：

- 峰值 Java 堆增量中位数降低 98.7%～99.1%。
- 已知大小文件的额外临时磁盘占用从文件大小等量降为 0。
- 客户端管线耗时中位数降低 50.3%～59.1%。
- 180 条正式样本的输出长度均正确，同一文件的新旧输出 SHA-256 与原文件一致。

这里的耗时只代表客户端文件准备和 RequestBody 写出，不包含网络 RTT、服务器接收、embedding
或 Milvus 写入。因此不能把表中的耗时降幅直接表述成线上接口总耗时降幅；内存和临时磁盘结果
则直接对应本次实现消除 `readBytes()` 和已知大小临时文件的目标。

## 实验环境

- 日期：2026-07-23
- 设备：Pixel 8 AVD，arm64-v8a，16 KiB page size
- 系统：Android 17 / API 37，build `CP21.260330.005`
- 构建：Debug APK，Android instrumentation test，ART runtime
- 基准代码：`UploadPipelineBenchmarkTest`
- 正式样本：2 种实现 × 3 种大小 × 30 次 = 180 条
- 预热：每种大小、每种实现各 3 次
- 顺序：每轮交替 legacy/streaming，降低 JIT 和温度顺序偏差

## 测量边界

两种实现写入同一个 SHA-256 black-hole sink。这样会执行真实 `RequestBody.writeTo()`，但不会：

- 把接收数据重新保存在内存中，污染堆内存测量；
- 把真实后端同步 embedding 和 Milvus 耗时混进客户端结果；
- 因网络波动掩盖文件准备和序列化差异。

旧版完整复刻被替换的客户端链路：

```text
源文件 → cache 临时文件 → readBytes() → ByteArray RequestBody → sink
```

新版执行当前已知大小文件链路：

```text
源文件 → StreamingRequestBody（固定缓冲区）→ sink
```

每次正式测量前执行两轮 GC/Finalization 稳定堆；独立采样线程持续记录
`Runtime.totalMemory() - Runtime.freeMemory()`，表中“堆增量”为相对该次基线的峰值。

## 实测结果

### 客户端管线耗时

| 文件 | 每组样本 | 旧版中位数 / P95 | 流式中位数 / P95 | 中位数变化 |
|---:|---:|---:|---:|---:|
| 1 MiB | 30 | 9.990 / 13.899 ms | 4.091 / 7.735 ms | -59.1% |
| 5 MiB | 30 | 29.475 / 39.132 ms | 14.654 / 17.804 ms | -50.3% |
| 10 MiB | 30 | 48.477 / 61.389 ms | 20.207 / 24.766 ms | -58.3% |

### Java 堆增量和临时磁盘

| 文件 | 旧版堆增量中位数 / P95 | 流式堆增量中位数 / P95 | 中位数变化 | 额外临时磁盘 |
|---:|---:|---:|---:|---:|
| 1 MiB | 2.328 / 2.422 MiB | 32.0 / 64.0 KiB | -98.7% | 1 MiB → 0 |
| 5 MiB | 11.297 / 11.719 MiB | 128.0 / 160.0 KiB | -98.9% | 5 MiB → 0 |
| 10 MiB | 22.984 / 23.344 MiB | 208.0 / 224.0 KiB | -99.1% | 10 MiB → 0 |

新实现的堆增量保持在 KiB 量级；旧实现随文件大小近似线性增长。这与理论上的
“文件大小级 `ByteArray` + 复制过程分配”对比“固定大小流式缓冲”一致。

## 内容正确性

每次测量都会比较“sink 最终收到的字节数”和 fixture 长度，并把输出 SHA-256
与原始 fixture SHA-256 比较。三份 fixture 的校验值为：

| 文件 | SHA-256 |
|---:|---|
| 1 MiB | `41588b385f69c98ade4a8f9e6f81edf093587d958c1e51971a66b2d597872866` |
| 5 MiB | `6ad03e14e83ee19bdf6a2f465370db479494fdfac5840662f116a5679ac14b26` |
| 10 MiB | `140b9db5e7089c158d5b84f78c178d00f0df0172f1dd41be160062bdb15d54e1` |

## 完整复现流程

先启动一个 Android 模拟器或连接真机：

```bash
adb devices -l
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

运行显式 opt-in 基准：

```bash
adb shell am instrument -w \
  -e class com.labsage.app.data.remote.UploadPipelineBenchmarkTest \
  -e runUploadBenchmark true \
  com.labsage.app.test/androidx.test.runner.AndroidJUnitRunner
```

导出数据并重新生成汇总表：

```bash
adb pull \
  /sdcard/Android/data/com.labsage.app/files/upload-benchmark-results.csv \
  upload-benchmark-results.csv
node scripts/summarize_upload_benchmark.mjs upload-benchmark-results.csv
```

不传 `runUploadBenchmark=true` 时测试会跳过，因此不会拖慢常规 instrumentation test。

## 可用于简历的严谨表述

> 重构 Markdown 上传链路，以可重放流式 RequestBody 替代“临时文件 + readBytes()”
> 全量入堆；在 Android ART 上完成 1/5/10 MiB、共 180 次 A/B 测量，10 MiB 文件峰值
> Java 堆增量中位数由 22.98 MiB 降至 208 KiB（-99.1%），已知大小文件额外临时
> 磁盘占用由 10 MiB 降至 0，并以字节数和 SHA-256 校验传输一致性。
