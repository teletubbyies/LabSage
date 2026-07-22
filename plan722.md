# LabSage 登录、JWT 与端云会话同步实施计划

> 制定日期：2026-07-22  
> 涉及项目：
> - Android：`/Users/zzy/Desktop/Android/LabSage`
> - Go 后端：`/Users/zzy/Desktop/go_src/SuperBizAgent`
>
> 本文是本轮实现的范围基线、执行顺序与验收依据。未经用户确认，不擅自扩大或缩减核心功能。

## 1. 总体目标

将当前“Android Room 独有完整聊天历史、Go 后端仅持久化摘要/事实且短期消息驻内存”的架构，升级为：

```text
Compose UI
   ↓ 只读取
Room 本地缓存
   ↑↓ Repository / SyncCoordinator
REST + SSE（携带 JWT）
   ↑↓
Go 服务端
   ├── users
   ├── refresh_tokens
   ├── conversations
   └── messages
```

目标职责：

- 服务端数据库是用户、会话和消息的业务权威数据源。
- Room 是本地缓存、离线展示数据源和流式回答中间态存储。
- Compose 继续只观察 Room/StateFlow，不直接以网络响应作为列表数据源。
- SSE 负责回答生成和实时推送；REST 负责认证、同步和会话管理。
- 后端的 `SimpleMemory` 降级为模型热缓存，可从数据库历史、摘要和事实重建。

## 2. 已对齐的默认技术决策

### 2.1 服务端数据库

- 使用 SQLite + GORM。
- 数据库路径由环境变量 `APP_DATABASE_PATH` 配置，默认可使用 `data/labsage.db`。
- 不复用 Agent 工具中的 `mysql_crud`；业务持久化使用独立数据库层。
- 数据访问通过 repository/service 边界组织，为以后迁移 MySQL/PostgreSQL 保留空间。

### 2.2 登录形态

- 实现用户名 + 密码注册、登录。
- 暂不实现验证码、找回密码、第三方 OAuth。
- 密码只保存 bcrypt 哈希。

### 2.3 JWT

- Access Token 有效期 15 分钟。
- Refresh Token 有效期 30 天。
- Refresh Token 服务端只保存哈希，并支持撤销。
- JWT 包含 user ID、token type、签发时间和过期时间。
- JWT Secret 只从环境变量读取，不写入仓库。
- Android 普通 API 和 SSE 都必须携带 Bearer Token。
- Access Token 过期后串行刷新，避免并发 refresh 风暴。
- Refresh 失败后清理会话并导航至登录页。
- Android 使用 Keystore 保护持久化 Token，不把 JWT 明文写入普通 DataStore。

### 2.4 既有本地数据

- Room 升级时为已有会话、消息补充全局稳定 UUID。
- 用户首次登录后，执行一次 legacy import，将当前设备已有会话和消息导入当前账户。
- 导入以 UUID 幂等，成功后记录 `legacy_import_completed`。
- 不因加入登录功能而丢弃已有真机聊天历史。

### 2.5 同步原则

- 服务端权威、Room 缓存、UI 只读 Room。
- 首次完整同步，后续使用服务端增量游标。
- 不依赖设备时间作为唯一游标。
- 消息发送使用 `clientRequestId` 幂等；重试必须复用同一个 ID。
- 会话删除采用软删除/tombstone，使其他客户端能感知删除。
- 会话元数据更新携带 version；冲突返回 HTTP 409。
- 第一、二期不实现 CRDT。

## 3. 数据模型

### 3.1 users

```text
id                 UUID primary key
username           unique, normalized
password_hash
created_at
updated_at
```

### 3.2 refresh_tokens

```text
id                 UUID primary key
user_id            foreign key
token_hash         unique
expires_at
revoked_at         nullable
created_at
```

### 3.3 conversations

```text
id                 UUID primary key
user_id            foreign key + index
title
is_pinned
version
sync_version       monotonic server cursor
created_at
updated_at
deleted_at         nullable tombstone
```

### 3.4 messages

```text
id                 UUID/ULID primary key
conversation_id    foreign key + index
role               user / assistant
content
status             pending / streaming / completed / partial / failed
route               nullable
original_question  nullable
error_message       nullable
client_request_id   unique within user/request scope
client_created_at
sync_version        monotonic server cursor
created_at
updated_at
deleted_at          nullable tombstone
```

所有会话和消息操作必须依据认证后的 user ID 校验归属，禁止仅凭 conversation ID 访问。

## 4. 执行纪律：逐功能子代理审查门禁

### 4.1 功能单元定义

本文第 5 节中的每个 `Fxx` 编号是一个独立功能单元。“每完成一个功能”指完成一个 `Fxx` 单元，而不是每写一个代码函数。

### 4.2 强制审查流程

每完成一个 `Fxx` 功能后，主代理必须：

1. 停止进入下一个功能。
2. 启动一个独立子代理作为计划一致性审查员。
3. 要求子代理完整读取本文件，并检查该功能涉及的实际 diff、测试和文档。
4. 子代理至少核对：范围、数据契约、安全边界、错误处理、测试覆盖、是否破坏既有功能。
5. 子代理给出 `PASS` 或 `FAIL`，并列出证据和缺口。
6. 若为 `FAIL`，主代理先修复，再启动新的独立子代理复审；不得自行宣告通过。
7. 只有获得 `PASS` 后，才将该功能标记为 `completed`。
8. 将该功能涉及的文件单独暂存并创建 git commit；不得夹带工作区中原有或与该功能无关的修改。
9. 若一个功能同时修改 Android 与 Go 两个独立仓库，则在两个仓库分别提交，并记录两个 commit hash。
10. commit message 使用 `feat(Fxx): ...`、`fix(Fxx): ...` 或 `test(Fxx): ...` 格式，明确关联功能编号。
11. 只有审查通过且 commit 成功后，才进入下一个功能；若提交失败，先解决提交问题，不得跳过。

每个审查子代理应尽量只负责一个功能审查，避免实现者与审查者角色混同。主代理在最终交付中汇总各功能的审查结果。

### 4.3 计划偏差处理

- 发现必须改变 API、数据模型、安全策略或迁移策略时，先停止实现并向用户说明。
- 用户确认后，先更新本文，再继续编码。
- 小型内部实现细节可以调整，但不得改变外部行为和验收标准。

## 5. 功能拆分与实施顺序

### F01：后端业务数据库基础

范围：

- 引入 SQLite GORM driver。
- 增加数据库配置、初始化、连接生命周期和自动迁移。
- 建立 users、refresh_tokens、conversations、messages 模型。
- 建立 repository 层和必要的唯一索引/外键/同步版本机制。
- 保证应用重启后数据仍存在。

验收：

- 临时数据库可自动建表。
- 重启/重新连接后数据可读。
- 唯一索引与外键约束有测试。
- 数据库初始化失败时服务明确失败或报告错误，不静默退化为内存存储。

完成状态：`completed`

完成记录：

- 独立复审：`PASS`（`review_f01_recheck`）。
- Go 后端 commit：`8f8f253 feat(F01): add persistent application database`。
- 专项验证：`go test ./internal/store -count=1`、`go test -race ./internal/store -count=1`、`go vet ./internal/store`、`git diff --check` 通过。
- 全仓测试存在 F01 之前的已知失败：`internal/ai/cmd/knowledge_cmd` 引用缺失的 `retriever.BuildRetrievalArtifacts`；审查确认与 F01 diff 无关。

### F02：后端注册、登录与 JWT

范围：

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`
- `GET /api/auth/me`
- bcrypt 密码哈希。
- Access/Refresh Token 签发、校验、轮换、撤销。
- JWT 认证中间件和上下文 user ID 注入。

验收：

- 正常注册/登录/刷新/注销链路通过。
- 重复用户名、错误密码、缺失/过期/篡改 Token 返回明确错误。
- Refresh Token 数据库只保存哈希。
- Refresh Token 轮换后旧 Token 不可继续使用。
- Secret 缺失时不使用硬编码默认密钥启动生产式认证。

完成状态：`completed`

完成记录：

- 独立审查：`PASS`（`review_f02`）。
- Go 后端 commit：`b9ae7c5 feat(F02): add JWT authentication lifecycle`。
- 验证：认证/Store/Controller 专项测试、HTTP 生命周期集成测试、race 检测、`go vet` 与 `git diff --check` 通过。
- 全仓仅保留计划前已存在的 `knowledge_cmd` 编译失败，审查确认与 F02 无关。

### F03：Android 登录 UI 与安全 Token 管理

范围：

- 增加登录页、注册页和认证状态路由。
- 增加 Auth API、AuthRepository、AuthViewModel。
- 使用 Android Keystore 保护 Token 持久化。
- Retrofit 请求自动添加 Access Token。
- SSE 请求添加 Access Token。
- 实现串行 Token refresh；refresh 失败回到登录页。

验收：

- 冷启动能恢复有效登录状态。
- 普通 REST 与 SSE 都带 Authorization header。
- 多个并发 401 不会触发 refresh 风暴。
- Token 不以明文出现在普通 DataStore/SharedPreferences。
- 登录错误、加载态、退出登录 UI 完整。

完成状态：`completed`

完成记录：

- 独立终审：`PASS`（`review_f03_final`；此前两轮 FAIL 均已修复并复审）。
- Android commit：`3093b65 feat(F03): add secure Android authentication`。
- 验证：单元测试、双 origin MockWebServer、并发 refresh、Debug APK、Lint、AndroidTest 编译通过。
- 在线模拟器 Keystore instrumentation 通过，确认 Access Token、Refresh Token 与绑定后端地址均未明文落盘，并可由新 Store 实例解密恢复。
- 提交通过交互式 hunk 暂存排除了 F03 开始前已有的文件管理及其他用户修改。

### F04：后端会话与消息查询 API

范围：

- `GET /api/conversations`
- `GET /api/conversations/{id}/messages`
- 第一阶段允许完整返回，为后续增量参数保留契约。
- 所有读取按 JWT user ID 隔离。

验收：

- 用户只能读取自己的会话和消息。
- 会话排序、消息顺序稳定。
- 不返回其他用户数据和已删除数据（增量 tombstone 模式启用前）。
- 空列表行为明确。

完成状态：`completed`

完成记录：

- 独立审查：`PASS`（`review_f04_recheck`；首轮 FAIL 的消息层 user ID 边界和验收测试缺口均已修复）。
- Go 后端 commit：`49405d4 feat(F04): add scoped conversation history APIs`。
- 验证：Controller/Store 专项测试、race 检测、`go vet`、`gofmt` 与 `git diff --check` 通过。
- 会话与消息在 Controller 和 SQL 层均按 JWT user ID 隔离；空列表、软删除、跨用户脏数据、404 一致性和稳定排序均有集成测试。

### F05：聊天请求服务端持久化与幂等

范围：

- 扩展 `/api/chat` 与 `/api/chat_stream` 请求：conversation ID、client message ID、client request ID。
- 后端校验会话归属；已存在会话必须属于当前 JWT 用户。
- 若客户端提交的 conversation UUID 不存在，则在首次聊天事务中原子创建归属当前用户的会话，再预留用户/助手消息；默认标题取规范化后的首条问题前 80 个字符。
- 若同一 conversation UUID 已属于其他用户或已软删除，统一返回 404，不允许接管或恢复。
- 聊天业务流内保存用户消息，不要求 Android 额外上传一次。
- 正常完成后保存助手完整消息。
- 中断时保存 partial/failed 状态和已产生内容。
- `done` SSE 事件返回服务端消息 ID、状态和更新时间。
- `clientRequestId` 唯一约束和幂等重放。
- 数据库历史参与 `SimpleMemory` 冷启动重建。

验收：

- 同一 client request 重试不会产生重复用户消息或助手消息。
- 全新会话的首条消息可直接完成“创建会话 + 写入首轮消息”，不依赖 legacy import 或额外创建 API。
- 服务重启后历史可查询，模型最近上下文可重建。
- SSE 不按每个 token 写服务端数据库。
- 正常结束和中断状态一致、有测试。

完成状态：`completed`

完成记录：

- 用户确认采用“首条聊天原子创建不存在会话”契约；计划契约 commit：`048cf98 feat(F05): define first-message conversation creation`。
- 独立终审：`PASS`（`review_f05_gate4`；前三轮 FAIL 的新会话入口、Continue 并发、重启证据与测试隔离问题均已修复）。
- Go 后端 commit：`6ada196 feat(F05): persist idempotent chat turns`。
- 验证：相关 HTTP/Store/Memory 测试连续 10 轮通过，race 检测、`go vet`、`gofmt` 与 `git diff --check` 通过。
- 覆盖 JWT 归属、原子创建与回滚、幂等重放、completed/partial/failed/continue、结构化 SSE `done`、取消上下文终态落库、数据库关闭/重开后冷内存恢复及不完整轮次过滤。

### F06：Android Room 全局 ID 迁移

范围：

- Conversation ID 统一为 UUID。
- Message 主键从本机自增 Long 迁移为 String UUID。
- 保留已有会话和消息及其外键关系。
- 通知 message ID、Navigation 参数和所有相关调用改为 String。
- 增加同步所需字段：server/sync version、status、deleted、client request ID 等。

验收：

- Room migration 测试验证已有数据不丢失。
- 迁移后消息仍属于正确会话。
- 通知精确跳转仍工作。
- 新旧数据均可正常展示。

完成状态：`completed`

完成记录：

- 独立终审：`PASS`（`review_f05_recheck` 的全新 F06 审查回合；此前四轮 F06 审查为 FAIL，所列异常终态、进程恢复、clean EOF、done 校验及 stale partial 问题均修复后重新审查）。
- Android commit：`146895d feat(F06): migrate Room data to global UUIDs`。
- Go 后端兼容 commit：`3bf7834 fix(F06): reconcile stale continuation prefixes`。
- Room v2→v3 迁移保留旧会话/消息及外键关系，将 legacy ID 确定性映射为 UUID，并原样保留已有 UUID；通知、Navigation 与 UI 消息 ID 全链路改为 String。
- SSE 严格校验结构化 done；clean EOF、畸形/串线终态、网络异常统一落为 partial/failed。进程死亡遗留 streaming 可恢复并回写 partial。
- Continue 允许客户端持有服务端内容的旧前缀，服务端先补发权威 partial 的缺失后缀再继续生成，避免批量落盘窗口导致 409 或回答缺段。
- 验证：Android 单元测试、Debug APK、Lint、AndroidTest 编译通过；Pixel_8 模拟器 instrumentation 12/12 通过；Go store/controller race 测试、`go vet` 与专项 diff-check 通过。
- 提交按 `/tmp/labsage-f06-baseline.FZNy6I` 对六个重叠文件做 hunk 隔离，未夹带文件管理、文档、`.gradle`、AGENTS.md 等既有用户修改。

### F07：Android 首次全量同步与 Room 回填

范围：

- 登录成功/冷启动后同步会话。
- 进入会话时同步消息。
- 使用 Room transaction 批量 upsert。
- UI 立即显示本地缓存，网络同步后台刷新。
- 同步失败保留缓存并显示非阻断错误/重试入口。

验收：

- 清空本地数据库后登录可以恢复服务端历史。
- 有缓存时冷启动无需等待网络即可展示。
- 重复全量同步不会重复插入。
- Room 仍然是列表和消息 UI 的唯一数据读取源。

完成状态：`completed`

完成记录（2026-07-22）：

- Android commit：`d833737 feat(F07): sync server history into Room cache`。
- 登录/冷启动后台全量同步会话，进入聊天后台同步消息；Compose 始终只消费账号隔离后的 Room Flow，缓存可立即展示，同步失败保留缓存并提供非阻断重试。
- Room v4 增加 `owner_user_id` 并保留 v2→v3→v4 迁移链；legacy 缓存在联网前事务认领，跨账号会话/消息 UUID 冲突会整批回滚，避免主键覆盖导致串号。
- 会话与消息使用事务批量 upsert 和安全分批清理；空库深链先回填父会话；重复同步幂等，本地未同步记录不会被全量清理误删。
- SSE 与历史同步按 `clientRequestId` 归并身份，覆盖 streaming/partial/failed、同步先后顺序与进程恢复竞态；权威 completed/较新 syncVersion 不会被迟到 chunk、EOF、Done 或恢复回写降级。
- 最终独立门禁：`review_f07_account_gate` PASS；此前各轮 FAIL 均修复后由新的独立代理复审。
- 验证：Android 单元测试、AndroidTest 编译、Debug APK、Lint 通过；Pixel_8 模拟器 instrumentation 25/25 通过；专项 cached diff-check 确认未夹带文件管理、文档、`.gradle`、`MainActivity.kt` 等既有用户修改。

### F08：既有 Room 历史一次性导入

范围：

- `POST /api/sync/import`。
- 为迁移后的既有本地记录补齐稳定 ID。
- 首次登录后将 legacy 数据批量导入当前账户。
- 服务端按 ID 幂等导入。
- 成功后记录 `legacy_import_completed`。

验收：

- 导入不会丢失用户/助手消息或会话关系。
- 导入中断后可安全重试。
- 重复导入不会重复数据。
- 只有服务端确认成功后才记录本地完成标志。

完成状态：`completed`

完成记录（2026-07-23）：

- 后端 commit：`d6cac68 feat(F08): import legacy conversation history`。
- Android commit：`a1a5dd3 feat(F08): import legacy Room history once`。
- 新增 JWT 保护的 `POST /api/sync/import`；服务端按稳定 ID 与同一逻辑 turn 幂等导入，在事务内分配单调 syncVersion，跨账号/跨会话冲突整批回滚。
- Android 在历史同步前认领并快照 `syncVersion=0` 的既有 Room 数据，按“会话优先、消息随后”分批上传；任一批失败均不写完成标志，重试时依赖服务端幂等安全重放。
- `legacy_import_completed` 按用户与服务器隔离，仅全部批次成功后写入 DataStore；导入绑定固定用户/服务器，同身份 token 可安全刷新，会话存储 CAS 防止切号竞态覆盖或误清新账号。
- 遗留 user 消息统一为 completed；assistant pending/streaming 有内容规范化为 partial、无内容规范化为 failed，避免导入后 Continue 永久 409。
- 最终独立门禁：`review_f08_status_gate` PASS；此前逻辑 turn 冲突、账号切换、JWT 过期、CAS TOCTOU 和 streaming 恢复问题均在各轮 FAIL 后修复并由新代理复审。
- 验证：Android 单元测试、Debug APK、Lint 通过，Pixel_8 instrumentation 27/27；Go store/controller 测试、race 与 scoped vet 通过。全仓 Go 测试另受既有 `knowledge_cmd` 缺失函数及 AI tools 沙箱写目录问题影响，均与 F08 无关。

### F09：服务端增量同步游标

范围：

- `GET /api/conversations?updatedAfter=<cursor>`。
- `GET /api/conversations/{id}/messages?updatedAfter=<cursor>`。
- 响应包含 items、nextCursor、serverTime。
- 使用服务端单调 sync version，避免设备时钟问题。
- Android 持久化游标并在 transaction 成功后推进。

验收：

- 同一时间发生多条更新不会遗漏。
- 同步失败不会错误推进游标。
- 重放同一游标结果幂等。
- 首次无游标完整同步，后续只返回增量。

完成状态：`completed`

完成记录（2026-07-23）：

- 后端 commit：`883ee80 feat(F09): add incremental history cursors`。
- Android commit：`bfe0eb7 feat(F09): sync history with persistent cursors`。
- 会话与消息查询支持 `updatedAfter`，统一返回 `items / nextCursor / serverTime`；后端使用全局单调 `syncVersion` 与 `sync_version, id` 稳定顺序，不依赖设备时钟。
- Android 首次无游标执行全量事务同步，后续只合并增量；Room 事务与 DataStore 的来源绑定/游标提交成功后才发布，失败保留旧游标并安全重放。
- Room 升级至 v5，为会话缓存增加服务器归属；列表、搜索、消息和 legacy import 均按“用户 + 服务器”隔离，F08 旧行在网络前本地认领，离线升级仍可见且本地草稿不会跨后端上传。
- 同一 userId 的 A→B→A 切换会强制按来源全量，跨服请求失败不泄露旧缓存；Room/DataStore 提交与登录、注销、切服共享认证互斥区，消除身份切换 TOCTOU。
- 最终独立门禁：`review_f09_room_scope_gate` PASS；此前缓存来源混淆、跨服失败可见、DataStore 失败窗口、认证 TOCTOU、local-only 泄露和首次升级离线隐藏问题均在各轮 FAIL 后修复并由新代理复审。
- 验证：Android 单元测试、Debug build、Lint 通过，Pixel_8 instrumentation 34/34；Go store/controller 测试、race 与定向 vet 通过。

### F10：会话重命名、置顶、软删除同步

范围：

- `PATCH /api/conversations/{id}` 支持 title、isPinned、version。
- `DELETE /api/conversations/{id}` 执行软删除并生成 tombstone。
- Android 重命名、置顶、删除同步到服务端。
- 其他客户端通过增量同步获得修改和删除。
- version 冲突返回 409，客户端拉取最新版并处理。

验收：

- 重命名、置顶在重新登录/另一客户端可恢复。
- 删除后其他客户端能移除本地记录。
- 用户不能修改或删除他人的会话。
- 旧 version 更新不会静默覆盖新数据。
- 当前延迟删除/撤销体验不被破坏；撤销窗口结束后才提交服务端删除。

完成状态：`pending`

### F11：端到端联调、回归测试与文档

范围：

- 执行 Go 测试、Android 单元测试、Room migration 测试和 Android 编译。
- 条件允许时进行模拟器/真机登录、聊天、重启恢复、同步、删除联调。
- 更新 API、架构、功能、面试讲解和安全配置文档。
- 删除或替换仓库中的真实明文密钥，并记录环境变量配置方式；若无法确认密钥状态，明确要求用户轮换。
- 按实际问答和掌握程度更新 `AGENTS.md`，不虚高标注。

验收：

- 所有新增自动化测试通过。
- Android debug build 成功。
- 后端在新数据库上可启动并完成核心链路。
- 文档与最终实现一致。
- 已知限制有明确记录。

完成状态：`pending`

## 6. API 契约草案

### 6.1 Auth

```http
POST /api/auth/register
POST /api/auth/login
POST /api/auth/refresh
POST /api/auth/logout
GET  /api/auth/me
```

### 6.2 Conversations / Messages

```http
GET    /api/conversations?updatedAfter=<cursor>
GET    /api/conversations/{id}/messages?updatedAfter=<cursor>
PATCH  /api/conversations/{id}
DELETE /api/conversations/{id}
POST   /api/sync/import
```

### 6.3 Chat stream request

`/api/chat` 与 `/api/chat_stream` 共享会话首次写入语义：不存在的 conversation UUID 由当前 JWT 用户在首轮事务中创建；已存在的外用户或软删除 UUID 返回 404。

```json
{
  "Id": "conversation-uuid",
  "Question": "用户问题",
  "ClientMessageId": "message-uuid",
  "ClientRequestId": "request-uuid",
  "Continue": false,
  "PartialAnswer": ""
}
```

SSE `done` 事件应返回结构化 JSON，而不是仅返回文本：

```json
{
  "conversationId": "conversation-uuid",
  "userMessageId": "message-uuid",
  "assistantMessageId": "message-uuid",
  "status": "completed",
  "updatedAt": 0,
  "syncVersion": 0
}
```

## 7. 测试总表

### 7.1 后端

- 注册成功、重复用户名、弱/空密码校验。
- 登录成功、错误密码。
- JWT 缺失、过期、篡改、token type 错误。
- Refresh Token 轮换、注销、过期和重放。
- 用户 A 无法读取/修改/删除用户 B 的数据。
- client request 幂等。
- SSE 完成/中断持久化。
- 服务重启后历史与上下文恢复。
- 增量游标不遗漏且可重放。
- tombstone 跨客户端同步。
- version 冲突返回 409。

### 7.2 Android

- 登录状态恢复。
- Access Token 自动刷新及并发刷新互斥。
- Refresh 失败退出登录。
- Room migration 保留既有数据和关系。
- 冷启动缓存优先。
- 全量/增量同步幂等。
- legacy import 可重试且不重复。
- SSE 正常完成与中断状态。
- 通知按 String message ID 精确跳转。
- 重命名、置顶、删除端云一致。

## 8. 明确不在本轮范围

- 邮箱验证码、找回密码、OAuth/微信登录。
- 多租户组织/角色权限系统。
- CRDT 或多人实时编辑。
- WebSocket 双向同步。
- 完整离线发送 Outbox/WorkManager 自动重试体系。
- 服务端多实例分布式锁与 Redis 会话缓存。
- 生产级登录限流、风控和设备管理界面。
- MySQL/PostgreSQL 正式部署。

这些内容可以在面试中作为生产化演进方向说明，但不应阻塞第一、二期交付。

## 9. 权限与工作区说明

- Android 项目位于当前可写工作区。
- Go 后端位于 `/Users/zzy/Desktop/go_src/SuperBizAgent`；实际修改前需要获得对应写权限。
- 两个仓库可能已有用户未提交修改，实施时必须只修改任务相关内容，不覆盖或回退用户变更。

## 10. 最终完成定义

只有同时满足以下条件，本计划才算完成：

1. F01—F11 全部实现并标记为 `completed`。
2. 每个功能都有独立子代理给出的最终 `PASS` 审查。
3. 每个功能在所有受影响仓库中都有独立、可追踪且不夹带无关修改的 git commit。
4. 核心测试与构建通过，或不可执行项有可验证的外部阻塞说明。
5. Android 与 Go 后端完成实际端到端联动。
6. 文档、API 契约和代码行为一致。
7. 旧 Room 历史没有因迁移或登录功能被静默丢弃。
