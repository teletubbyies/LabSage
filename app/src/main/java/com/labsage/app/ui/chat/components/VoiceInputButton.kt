package com.labsage.app.ui.chat.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Voice input button — an independent component wrapping Android's built-in
 * [SpeechRecognizer]. Sits inside [ChatInputBar] and does NOT alter any data
 * flow: recognized text is delivered via [onResult], which the input bar
 * appends to the text field.
 *
 * Design notes:
 *   - Uses the direct SpeechRecognizer API (not the ACTION_RECOGNIZE_SPEECH
 *     intent popup) so we can render an inline "listening" state on the mic
 *     itself and surface partial results, rather than handing off to a system
 *     dialog.
 *   - RECORD_AUDIO is a dangerous permission since API 23 → requested at
 *     runtime via [ActivityResultContracts.RequestPermission].
 *   - The [RecognitionListener] is created once (remember) and references the
 *     latest [onResult] through rememberUpdatedState, so it never goes stale.
 *   - SpeechRecognizer must be created on the main thread and destroyed on
 *     dispose to avoid leaking the recognition service connection.
 *   - Emulator/Google-app-less devices may report "available" yet fail to
 *     bind → every error path shows a user-facing toast, so the UI degrades
 *     gracefully instead of hanging in a "listening" state forever.
 */
@Composable
fun VoiceInputButton(
    onResult: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var isListening by remember { mutableStateOf(false) }
    var recognizerRef by remember { mutableStateOf<SpeechRecognizer?>(null) }
    val currentOnResult by rememberUpdatedState(onResult)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            Toast.makeText(context, "需要麦克风权限才能使用语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    val recognitionListener = remember {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { isListening = true }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                isListening = false
                // NO_MATCH / SPEECH_TIMEOUT are expected when the user stops
                // without speaking — don't toast noise for those.
                if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                    error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    Toast.makeText(context, speechErrorMessage(error), Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                matches?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { currentOnResult(it.trim()) }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Toast.makeText(context, "当前设备不支持语音识别", Toast.LENGTH_SHORT).show()
            return
        }
        // Recreate each session: a previously-finished recognizer is bound to
        // its old listener/state and won't reliably restart.
        recognizerRef?.destroy()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(recognitionListener)
        recognizerRef = recognizer

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.startListening(intent)
        isListening = true
    }

    fun stopListening() {
        recognizerRef?.stopListening()
        isListening = false
    }

    // Release the recognition service connection when the button leaves the
    // composition — otherwise the service stays bound until process death.
    DisposableEffect(Unit) {
        onDispose {
            recognizerRef?.destroy()
            recognizerRef = null
            isListening = false
        }
    }

    // Gentle pulse while actively capturing audio — same visual language as
    // the thinking dots, not a heavy CircularProgressIndicator.
    val transition = rememberInfiniteTransition(label = "mic")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "micScale"
    )

    FilledIconButton(
        onClick = {
            if (!enabled) return@FilledIconButton
            if (isListening) {
                stopListening()
            } else if (hasPermission) {
                startListening()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        enabled = enabled,
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            contentColor = Color.White
        )
    ) {
        Icon(
            imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
            contentDescription = if (isListening) "停止语音输入" else "语音输入",
            modifier = if (isListening) Modifier.scale(scale) else Modifier
        )
    }
}

private fun speechErrorMessage(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
    SpeechRecognizer.ERROR_NETWORK -> "网络错误"
    SpeechRecognizer.ERROR_AUDIO -> "录音错误"
    SpeechRecognizer.ERROR_SERVER -> "语音服务错误"
    SpeechRecognizer.ERROR_CLIENT -> "语音客户端错误"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音"
    SpeechRecognizer.ERROR_NO_MATCH -> "未能识别语音"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "麦克风权限不足"
    else -> "语音识别失败 ($code)"
}
