# LabSage Android — 新增功能文档

本文件记录在初始版本之上迭代新增的功能模块（按提交顺序）。每个功能包含：实现概述、架构决策、关键代码点、面试考点。Bug 修复记录于 `BUGFIX_LOG.md`。

---

## 一、语音输入（Voice Input）

### 功能概述

在 `ChatInputBar` 中加入一个独立的语音输入按钮，使用 Android 内置 `SpeechRecognizer` 将用户语音识别为文本并追加到输入框。**不改动任何数据流**——识别结果通过回调 `onResult` 投递，由输入框决定如何处理（当前实现为追加到已输入文本末尾）。

| 属性 | 值 |
|------|-----|
| 组件 | `VoiceInputButton`（`ui/chat/components/VoiceInputButton.kt`） |
| API | `android.speech.SpeechRecognizer`（直接 API，非 Intent 弹窗） |
| 权限 | `RECORD_AUDIO`（dangerous，运行时申请） |
| 语言模型 | `LANGUAGE_MODEL_FREE_FORM`，语言 `zh-CN` |
| 集成点 | `ChatInputBar` → `ChatScreen.onVoiceResult` |

### 架构决策：直接 SpeechRecognizer API vs Intent 弹窗

| 方案 | 评估 |
|------|------|
| `ACTION_RECOGNIZE_SPEECH` Intent | 弹出系统/Google 语音搜索对话框，调用方一般不需要 `RECORD_AUDIO`（由接收方 App 录音）。简单，但交互脱离应用 UI |
| **直接 `SpeechRecognizer` API** ✅ | 在本应用内渲染"正在聆听"状态、可显示 partial 结果，交互连贯；需自行管理 `RECORD_AUDIO` 权限与生命周期 |

选择直接 API 以获得与应用一致的视觉反馈（麦克风按钮脉冲动画），与既有"Thinking..."点动画同一视觉语言，而非突兀的系统弹窗或 `CircularProgressIndicator`。

### 关键实现点

1. **权限运行时申请**：`RECORD_AUDIO` 自 API 23 起为 dangerous 权限。通过 `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission)` 申请，授予后才创建识别器。
2. **RecognitionListener 稳定化**：listener 用 `remember` 创建一次（避免每次重组重建），对最新回调 `onResult` 通过 `rememberUpdatedState` 引用，防止捕获过期闭包。
3. **生命周期管理**：
   - `SpeechRecognizer.createSpeechRecognizer()` 必须在主线程调用。
   - `DisposableEffect.onDispose` 中 `destroy()`，释放识别服务连接，否则连接会绑定到进程结束。
   - 每次开始前 `destroy()` 旧实例再新建（已结束的识别器绑定旧 listener/状态，无法可靠重启）。
4. **优雅降级**：模拟器或无 Google 服务的设备可能 `isRecognitionAvailable` 返回 true 但实际无法绑定服务。所有错误路径（`onError`）都向用户展示 Toast，避免 UI 永久停留在"聆听中"。`NO_MATCH`/`SPEECH_TIMEOUT`（用户没说话）不弹 Toast，避免噪音。
5. **状态语义**：`isListening` 单独驱动按钮视觉（脉冲 + 红色 + Stop 图标），与输入框 `enabled` 解耦——流式输出进行时按钮禁用，但仍可正确反映聆听态。

### 数据流

```
用户长按/点击麦克风
  → 检查 RECORD_AUDIO 权限
    → 未授予: permissionLauncher.launch(RECORD_AUDIO)
    → 已授予: SpeechRecognizer.createSpeechRecognizer() + startListening(intent)
  → RecognitionListener.onResults(matches)
    → currentOnResult(matches[0])     ← rememberUpdatedState 保持最新
      → ChatScreen.onVoiceResult { inputText += recognized }
        → OutlinedTextField 重组显示
```

### 面试考点

- `SpeechRecognizer` 直接 API 与 `ACTION_RECOGNIZE_SPEECH` Intent 的区别？何时选哪个？（控制力 vs 实现成本、权限归属）
- 为什么 `RECORD_AUDIO` 是 dangerous 权限？运行时申请的标准流程？
- `remember` 创建的 listener 中引用 Compose state setter 为什么安全？（MutableState 引用稳定；回调通过 `rememberUpdatedState` 保持最新）
- 为什么必须 `destroy()`？识别服务连接的生命周期与 Compose `DisposableEffect` 的关系。
- 模拟器上"识别器不可用"如何优雅降级？（`isRecognitionAvailable` + `onError` 全覆盖 + Toast 反馈）

### 影响文件

- `app/src/main/java/com/labsage/app/ui/chat/components/VoiceInputButton.kt`（新增）
- `app/src/main/java/com/labsage/app/ui/chat/components/ChatInputBar.kt`（加入按钮 + `onVoiceResult` 参数）
- `app/src/main/java/com/labsage/app/ui/chat/ChatScreen.kt`（传入 `onVoiceResult`，追加识别文本）
- `app/src/main/AndroidManifest.xml`（声明 `RECORD_AUDIO`）
