# 文件 / 知识库管理 · 后端接口契约（已实现）

> 配套 Android 端「文件管理页」。后端代码在独立仓库 `/Users/zzy/Desktop/go_src/SuperBizAgent`（GoFrame v2.7.1 + Milvus + bleve + eino RAG）。
> 本文档是**已实现的契约**，与客户端 `ApiModels.kt` / `LabSageApi.kt` 对齐。

---

## 0. 通用约定

- 响应沿用 GoFrame `ResponseMiddleware` 信封：`{ "message": "...", "data": <payload> }`
  （客户端 `ApiResponse<T> { message, data }`，`app/.../data/remote/ApiModels.kt`）。
- 路由前缀 `/api`（`main.go` 的 `s.Group("/api", ...)` + `group.Bind(chat.NewV1())`）。
- 新增接口**无需改 main.go**：`Bind` 按反射自动发现 controller 方法 + 其 Req 结构体的 `g.Meta` 标签。

## id 的语义（关键决策）

**`id` = 文件名（`fileName`）**，不是 uuid。

后端知识库**没有 DB 表、没有内存注册表**，文件的向量块按"绝对路径"（`metadata._source`）入 Milvus；上传时 `uploadFile.Save(dir, false)` 保留原文件名、不做随机化。所以**文件名就是事实上的稳定唯一键**（同名再传会覆盖磁盘 + delete-then-insert，天然去重）。引入 uuid 反而要新增 uuid→path 持久化（目前无 ORM 落库），侵入性大且无收益。

客户端把 `id` 当**不透明字符串**处理（`KnowledgeFileDto.id`、`deleteFile(@Path("id"))`），Retrofit `@Path` 默认 URL-编码文件名（中文/空格/`.` 都能过），GoFrame 端 `r.GetRouter("id")` 解码回来。文件名不含 `/`（文件系统限制），路由匹配无歧义。

---

## 1. `GET /api/files` — 列出知识库文件 ✅

- 实现：`internal/controller/chat/chat_v1_files.go` `FileList`。
- 列 `common.FileDir`（上传目录，运行期被 `manifest/config/config.yaml` 的 `file_dir` 覆盖为 `…/data`）下的**顶层普通文件**，跳过子目录（`retrieval/`、`session_context/`、`execution_traces/` 等系统目录）和点文件；按 `mtime` 倒序。
- **列出全部用户上传文件**（不限 `.md`）。只有 `.md` 会被真正分块入索引，但非 `.md` 也是用户上传的、应展示，删除时按 best-effort 处理。

响应 `data`：

```json
{
  "files": [
    { "id": "report.md", "fileName": "report.md", "filePath": "/…/data/report.md",
      "fileSize": 1048576, "uploadedAt": 1718000000000 }
  ]
}
```

`uploadedAt` = 文件 `mtime` 的毫秒时间戳（与客户端 `MessageEntity.timestamp` 一致）。

## 2. `DELETE /api/files/{id}` — 删除文件 ✅

- 实现：同文件 `FileDelete`；`id` 为文件名。
- **路径穿越防护**：`id` 禁止含 `/`、`\`，禁止为 `.`/`..`，确保无法逃出上传目录。
- 删除分三步，**磁盘删除是权威**，索引清理是 best-effort：

| 步骤 | 操作 | 失败处理 |
| --- | --- | --- |
| ① 磁盘 | `os.Remove(absPath)`（已不存在则跳过，保证重试安全） | **失败即整体失败**（返回错误） |
| ② Milvus | `client.DeleteMilvusDocumentsBySource(ctx, cli, absPath)` 删该 `_source` 的稠密 + BM25 向量（幂等） | 记 warn，**不阻断** |
| ③ 词表 | `retriever.BuildRetrievalArtifacts(ctx, common.FileDir)` 从剩余文件全量重建 bleve/词表 JSON | 记 warn，**不阻断** |

响应 `data`：`{ "fileId": "<id>" }`

### ⚠️ 已知限制（重要）

1. **bleve 词表索引在进程启动时一次性加载，不热更新**。所以删除后重建的词表，**要到后端重启**才被运行中的检索器看到。**Milvus（稠密）删除是即时的**。也就是说：删除后立刻聊天，向量检索不会再命中该文件，但词表（BM25/bleve）那条路径在重启前可能仍命中。
2. 若删除时 **Milvus 不可达**，其残留向量块会留到 Milvus 恢复 + 重新删除（删除幂等，可重试）。
3. `BuildRetrievalArtifacts` 每次全量扫描目录，删多个文件时是 O(文件数)/次——少量文件无碍，大量时可改为增量。

权衡理由：把"用户删文件"的成败锚定在磁盘删除（最稳、可预测），索引一致性作为内部 hygiene 后台处理。若要更强一致，可把②③改为 fatal（与上传的 `buildIntoIndex` 一致），代价是 Milvus 宕机时无法删文件。

## 3. `POST /api/upload`（改造）✅

- 实现：`internal/controller/chat/chat_v1_file_upload.go` 的 `FileUpload` 在 `res` 里新增 `FileId: savedName`。
- `FileUploadRes`（`api/chat/v1/chat.go`）加 `FileId`：

```json
{ "fileId": "report.md", "fileName": "report.md",
  "filePath": "/…/data/report.md", "fileSize": 1048576 }
```

`fileId` == `fileName`。客户端 `UploadData.fileId` 设为可空，兼容旧后端。

---

## 4. 涉及文件（后端）

| 文件 | 改动 |
| --- | --- |
| `api/chat/v1/chat.go` | `FileUploadRes` 加 `FileId`；新增 `FileListReq/Res`、`KnowledgeFile`、`FileDeleteReq/Res` |
| `internal/controller/chat/chat_v1_file_upload.go` | `res` 填 `FileId` |
| `internal/controller/chat/chat_v1_files.go`（新） | `FileList` + `FileDelete` |
| 复用 | `utility/client` 的 `NewMilvusV2Client` + `DeleteMilvusDocumentsBySource`；`internal/ai/retriever` 的 `BuildRetrievalArtifacts`；`utility/common.FileDir` |

`go build ./...` 通过。**无需改 `main.go`**（路由由 `Bind` 反射注册）。

---

## 5. 验证

1. 重新编译/重启后端：`cd ~/Desktop/go_src/SuperBizAgent && go build -o /tmp/sba . && /tmp/sba`（或既有启动方式，端口默认 6872）。
2. Android 会话列表点 📁 → 文件页应列出 `data/` 下的文件（不再 404）。
3. 搜索过滤、删除（确认 Dialog → 列表移除）、刷新、下拉刷新均应工作。
4. 删除 `.md` 文件后重启后端 → 向量 + 词表都不再命中该文件（验证②③生效）。
