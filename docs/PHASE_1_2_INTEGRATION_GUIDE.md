# LabSage 第一、二期端云联动说明

本文对应 `plan722.md` 的 F01–F11 最终实现，用于本地联调、代码走查和面试讲解。Go 服务端是业务权威数据源，Android Room 是离线缓存与 UI 唯一读取源。

## 1. 最终架构

```text
Compose UI
  ↓ collect StateFlow
ViewModel
  ↓
Repository / HistorySyncCoordinator
  ├── Room v5：会话、消息、同步游标、所属服务端
  ├── Retrofit REST：认证、历史同步、会话变更、legacy import
  └── OkHttp SSE：流式聊天事件
           ↓ Authorization: Bearer <access token>
Go controller → auth middleware → repositories → SQLite/GORM
```

- Compose 不直接把网络响应当列表状态；服务端数据先事务性落入 Room，再由 Room Flow 推动界面更新。
- Access Token 过期时由认证层串行刷新，多个并发请求共享一次 refresh 结果；refresh 失败才清除登录态。
- Token 使用 Android Keystore 保护后持久化，不写入普通明文 DataStore。
- SSE chunk 是即时显示数据；完成、部分完成或失败事件携带服务端消息身份与同步版本，最终状态回写 Room。
- 服务端 `SimpleMemory` 只是模型热缓存。缓存丢失或进程重启后，可由持久化消息恢复上下文。

## 2. HTTP API

除注册、登录和刷新外，业务请求均携带 `Authorization: Bearer <accessToken>`。接口响应使用服务端统一 envelope；Android 模型读取其中的 `data`。

### 认证

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/register` | 用户名/密码注册并返回用户与 token pair |
| POST | `/api/auth/login` | 登录并返回 token pair |
| POST | `/api/auth/refresh` | 轮换 refresh token；旧 token 随即失效 |
| POST | `/api/auth/logout` | 撤销 refresh token |
| GET | `/api/auth/me` | 校验 access token 并返回当前用户 |

Access Token 有效期 15 分钟，Refresh Token 有效期 30 天。密码由后端使用 bcrypt 哈希，refresh token 在数据库中只保存哈希。

### 历史与变更

| 方法 | 路径 | 核心语义 |
| --- | --- | --- |
| GET | `/api/conversations?updatedAfter=<cursor>` | 完整或增量会话同步，返回 `items` 与 `nextCursor` |
| GET | `/api/conversations/{id}/messages?updatedAfter=<cursor>` | 当前用户指定会话的完整或增量消息同步 |
| PATCH | `/api/conversations/{id}` | 修改标题/置顶；请求携带期望 `version` |
| DELETE | `/api/conversations/{id}` | 软删除；请求携带期望 `version` |
| POST | `/api/sync/import` | 首次登录幂等导入本机 legacy 会话与消息 |

增量游标是服务端单调递增的 `syncVersion`，不依赖设备时钟。删除项仍以 `deletedAt` tombstone 出现在增量结果中，使其他设备能隐藏已删除记录。PATCH/DELETE 版本落后时返回 409；Android 放弃本次变更并触发完整同步。

### 聊天

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/chat` | 非流式回答 |
| POST | `/api/chat_stream` | SSE 流式回答 |

请求包含稳定的 `conversationId`、`clientMessageId` 和 `clientRequestId`。同一次重试复用 `clientRequestId`，后端以它保证聊天轮次幂等。新会话在首轮事务内建立；同 UUID 若属于其他用户或已被删除，对当前用户表现为不存在。

## 3. 首次登录与后续同步

1. App 启动时读取 Keystore 保护的登录态；无会话则显示登录/注册页。
2. 登录成功后，若当前本地数据尚未导入，客户端上传 legacy 会话与消息；UUID 唯一约束保证重复调用安全。
3. 导入成功才记录完成标记，失败不会错误跳过下次导入。
4. 客户端先用持久化游标拉增量；游标为空、所属用户或服务端变化、或检测到冲突时执行完整同步。
5. 网络结果在 Room 事务中 upsert；只有事务成功后才推进游标。
6. UI 始终可以先展示 Room 缓存。同步失败显示状态与重试入口，不清空可用历史。

Room v5 为会话和消息保存稳定 UUID、服务端 version/syncVersion，并把游标绑定到当前用户和规范化后的服务端地址，防止切换账号或后端时错误复用游标。

## 4. 会话操作与离线体验

- 标题/置顶先请求服务端，成功后把服务端返回的权威 version/syncVersion 合并进 Room；409 时执行完整同步并以服务端结果为准。
- 删除沿用延迟提交：Snackbar 撤销窗口内仅由内存状态从 UI 隐藏，不修改 Room；超时后请求服务端软删除，再把返回的 tombstone 合并进 Room。撤销时因尚未提交删除，无需重建消息。
- Room 是缓存而非另一套业务权威，因此第一、二期不做 CRDT，也不承诺多端离线同时编辑自动合并。
- 流式回答中断会保存 `partial`；模型或持久化错误保存 `failed`，继续生成复用原消息身份，避免重复消息。

## 5. 安全配置

Android 仓库不保存后端 JWT secret 或模型供应商 key。服务端环境变量、启动方式和轮换要求见后端 `docs/SECURITY_AND_RUNBOOK.md`。

历史版本曾出现明文供应商凭据，必须在 Ark、DashScope 和 Tencent 控制台撤销或轮换；仅从当前文件删除并不能清除 Git 历史或已有克隆中的值。新的凭据只能由本地环境或部署平台注入。

客户端调试服务端地址仍属于非秘密配置。生产环境还应使用 HTTPS、限制 cleartext traffic，并为服务端增加速率限制、审计日志和密钥托管。

## 6. 验证命令与联调清单

后端自动化：

```shell
go test -count=1 -race ./internal/store ./internal/controller/chat
go vet ./internal/store ./internal/controller/chat ./internal/auth
```

Android 自动化：

```shell
./gradlew clean testDebugUnitTest assembleDebug lintDebug
./gradlew connectedDebugAndroidTest
```

真机/模拟器联调至少覆盖：注册、登录、首次导入、发送消息、流式中断、重启恢复、增量同步、重命名/置顶、删除撤销、另一客户端收到 tombstone、access token 过期后刷新、refresh 失效后回登录页。模型聊天联调需要已经轮换的新供应商密钥；没有新密钥时不要复用仓库历史中的旧值。

真实后端烟测可在本机启动 Go 服务后，通过 instrumentation 参数显式启用：

```shell
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.labsage.app.integration.RealBackendSmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.realBackendBaseUrl=http://10.0.2.2:16872/
```

该测试从 Android 模拟器进程使用生产 Retrofit API 完成注册、legacy import、全量历史拉取、PATCH、DELETE 与增量 tombstone 拉取；没有传入地址时会跳过，避免普通 CI 意外访问外部服务。

全仓已知旧问题：`go test -count=1 ./...` 仍因 `internal/ai/cmd/knowledge_cmd` 引用缺失的 `retriever.BuildRetrievalArtifacts` 而失败；全包 `go vet ./...` 仍会报告 `internal/ai/tools/query_metrics_alerts.go` 的不可达代码。两项均早于 F11 且不在本轮 diff，核心 `store/controller/auth`、本次修改的 AI 包和 Android 回归已独立通过；在宣称全仓绿色前仍应修复这两项。

## 7. 面试讲解建议

可以按“问题—决策—一致性—安全—取舍”展开：

1. 原项目只有 Room 保存完整历史，换设备或清数据即丢失；因此把 Go SQLite 设为业务权威，Room 降为可离线展示的缓存。
2. UI 继续只读 Room，避免 REST、SSE、数据库三套状态直接竞争；同步协调器负责把网络变化收敛到单一数据流。
3. 用服务端单调游标解决设备时钟漂移，用 UUID/clientRequestId 解决重试重复，用 tombstone 传播删除，用 version + 409 暴露写冲突。
4. JWT 使用短 access + 可轮换 refresh；refresh token 哈希存储，Android 用 Keystore，后端所有数据访问从认证上下文取得 user ID。
5. 第一、二期明确不做 CRDT：会话元数据冲突以服务端为准，复杂度与项目阶段匹配；未来可迁移 PostgreSQL、增加 outbox/后台同步与更细粒度冲突合并。

这个项目的核心亮点不是“接了登录接口”，而是认证、归属隔离、幂等写入、增量同步、Room 缓存、SSE 最终落盘和冲突恢复形成了一条闭环。

## 8. 已知限制

- SQLite 适合单实例面试项目；多实例部署需迁移共享数据库并重新设计全局同步序列。
- 当前同步在应用触发时执行，尚无 WorkManager 保证进程退出后的后台重试。
- 多端同时编辑只检测冲突并回源刷新，不做字段级自动合并。
- 模型供应商和 MCP 的真实端到端测试依赖外部凭据与网络，自动化测试使用确定性替身验证持久化及协议边界。
- 全仓 Go 测试/静态检查仍有上一节记录的两个既有阻断，当前只保证本轮核心链路及改动包绿色。
- 仓库历史中的旧密钥必须由项目所有者完成供应商侧轮换。
