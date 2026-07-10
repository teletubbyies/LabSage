# BUGFIX_LOG

## #1 流式中途关闭应用后，"继续生成"按钮不出现

**日期：** 2026-07-10
**模块：** chat / ChatViewModel
**现象：** 在 SSE 流式回答中途直接关闭应用（close tab / 杀进程），重开后**消息没丢**（partial 内容已落盘 Room），但底部的"继续生成 / 重新生成"横幅（`PartialMessageBanner`）不再出现，无法续写或重生成。

**根因：**
`_streamState`、`accumulatedAnswer`、`streamingMessageId` 都是 **ViewModel 内存状态**。中途关闭应用 = 进程被杀，三者全部清零。但 partial 回答在每个第 5 个 chunk 时已写入 Room（`isPartial=true` + `originalQuestion` 已存），所以**数据层没丢**。

问题在于 `ChatViewModel.init` 只把 Room 消息灌进 `_messages`，**没有从残留的 partial 消息重建 `_streamState`**。重开后 `_streamState` 停在 `Idle`，而 `ChatScreen` 只在 `streamState is StreamState.Error && retryable` 时才渲染 `PartialMessageBanner` → 按钮不出现。

**关键约束（决定修复方式）：**
`continueGeneration()` 不清空 `accumulatedAnswer`、不重置 `streamingMessageId`——它依赖这两个累加器跨调用存活，后端只发续写 delta，客户端在内存里拼 `partial + 续写`。所以重建时不能只设 `_streamState`，必须**同时把 `accumulatedAnswer` 和 `streamingMessageId` 也从 Room 灌回来**，否则：
- 点 Continue → `accumulatedAnswer` 为空 → 续写 delta 直接覆盖 partial 行，丢前半段；
- 点 Regenerate → `streamingMessageId` 为 null → 删不掉旧 partial 行，产生重复。

**修复：**
新增 `restorePartialStateIfAny(entities)`，在 `init` 的 Room collect 里调用（用 `Idle` 守卫，实际只跑一次）：
- 仅当最后一条是 `assistant` 且 `isPartial=true` 且 `content` 非空时触发；
- `accumulatedAnswer` ← partial 内容、`streamingMessageId` ← partial 行 id、`chunkCount = 0`；
- `_streamState` ← `StreamState.Error(partial=true, retryable=true, partialAnswer=内容, lastQuestion=originalQuestion)`。

这样横幅重新出现，且 Continue / Regenerate 行为与内存中未杀进程时完全一致。

**涉及文件：** `app/src/main/java/com/labsage/app/ui/chat/ChatViewModel.kt`

**附注（未修复，待定）：** `regenerate()` 末尾调 `sendMessage(state.lastQuestion)` → `doSend()` → `saveUserMessage()`，会把**已存在的用户消息再插一条**（重复）。此问题在内存中断场景就已存在，与本次修复无关，单独记录待处理。
