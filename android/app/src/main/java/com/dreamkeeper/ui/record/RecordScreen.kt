package com.dreamkeeper.ui.record

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.service.RecordingPhase
import com.dreamkeeper.service.RecordingSession
import com.dreamkeeper.service.RecordingStarter
import com.dreamkeeper.ui.nav.RecordEntry
import com.dreamkeeper.ui.theme.DreamMotion
import com.dreamkeeper.ui.theme.Ember
import com.dreamkeeper.ui.theme.EmberDeep
import com.dreamkeeper.ui.theme.InkDim
import com.dreamkeeper.ui.theme.InkFaint
import com.dreamkeeper.ui.theme.Line
import com.dreamkeeper.ui.theme.Night
import com.dreamkeeper.ui.theme.NightRaised
import com.dreamkeeper.ui.theme.Paper
import java.util.Locale

private const val TAG = "RecordScreen"

private val CIRCLE_SIZE = 160.dp
private val RING_SIZE = 200.dp
private val CANCEL_SWIPE = 96.dp

/** 「正在保存…」的最短可见时长（真实 FINISHING 只有 ~15ms）。 */
private const val SAVE_PRESENTATION_MS = 900L

/**
 * Task 4 录音页：按住说梦。
 *
 * 手势的三条出口（brief 4.2/4.3/0.9）：
 * - 正常松手 → STOP，等 DONE 落库并跳 FollowUp；
 * - 系统抢焦（来电/锁屏/上滑 Home）→ `awaitEachGesture` 被打断：不冒充松手，走 interrupted 横幅；
 * - 录音中上滑 ≥96dp → CANCEL（同手可达，配合底部的「取消录音」按钮）。
 */
@Composable
fun RecordScreen(
    entry: RecordEntry,
    repository: DreamRepository,
    session: RecordingSession,
    audioStore: WavStore,
    starter: RecordingStarter,
    hasPermission: () -> Boolean,
    hasAskedOnce: () -> Boolean,
    permissionEpoch: Int,
    onRequestPermission: () -> Unit,
    onBackHome: () -> Unit,
    onNavigateFollowUp: (String) -> Unit,
    debugError: String? = null,
) {
    val vm: RecordViewModel = viewModel {
        RecordViewModel(
            repository = repository,
            session = session,
            audioStore = audioStore,
            starter = starter,
            hasPermission = hasPermission,
            clock = System::currentTimeMillis,
            hasAskedOnce = hasAskedOnce,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val permissionBlocked by vm.permissionBlocked.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val notificationsEnabled = remember(context) {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    var pressed by remember { mutableStateOf(false) }
    var interrupted by remember { mutableStateOf(false) }
    var interruptedAtMs by remember { mutableLongStateOf(0L) }
    var debugDismissed by remember { mutableStateOf(false) }
    // FINISHING 只有 ~15ms（服务写得很快），直接跟随 phase 会让「正在保存…」一帧都看不见。
    // 这里给保存态一个最短呈现时间：计时停在 DONE 的快照上，主钮保持禁用，再跳 FollowUp。
    var savePresentation by remember { mutableStateOf(false) }

    val error = (if (debugDismissed) null else debugError) ?: state.error
    val enospc = error?.contains("ENOSPC", ignoreCase = true) == true ||
        error?.contains("空间不足") == true
    val saving = state.phase == RecordingPhase.FINISHING || savePresentation
    val recording = state.phase == RecordingPhase.RECORDING
    val starting = pressed && state.phase == RecordingPhase.IDLE &&
        state.permissionState == PermissionState.GRANTED && !enospc

    // 权限从设置页回来 / 系统弹框关闭后，Activity 每次 RESUMED 都会递增 epoch。
    LaunchedEffect(permissionEpoch) { vm.refreshPermission() }

    // 相位日志是 p2-ux-latency.txt 的真源：START/STOP 的端到端时差从这里量。
    LaunchedEffect(state.phase) {
        Log.d(TAG, "phase=${state.phase} elapsedMs=${state.elapsedMs} dreamId=${state.dreamId}")
    }

    // DONE 后把保存态多留一会儿，避免「正在保存…」在 60Hz 上连一帧都不到（偏差 D147）。
    // 只有「这次真的落了库」才演：重入页面时 session 可能还停在 DONE，此时 savedDreamId 为空。
    LaunchedEffect(state.phase, state.dreamId, state.savedDreamId) {
        if (state.phase == RecordingPhase.DONE && state.elapsedMs > 0L && state.savedDreamId != null) {
            savePresentation = true
            delay(SAVE_PRESENTATION_MS)
            savePresentation = false
        }
    }

    // savedDreamId 一次性事件。interrupted 期间先压住，等用户在横幅里选「就这样」；
    // savePresentation 期间先让「正在保存…」可见，再跳 FollowUp。
    LaunchedEffect(state.savedDreamId, interrupted, savePresentation) {
        val id = state.savedDreamId ?: return@LaunchedEffect
        if (interrupted || savePresentation) return@LaunchedEffect
        Log.d(TAG, "navigate followup dreamId=$id")
        onNavigateFollowUp(id)
        vm.consumeSavedDream()
    }

    // 返回键：RECORDING / FINISHING 都等于「放弃这段」；FINISHING 时 service 会删文件。
    BackHandler(enabled = recording || saving) { vm.onCancel() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Night)) {
        val circleTop = maxHeight * 0.65f - CIRCLE_SIZE / 2

        // 计时：圆上方 32dp，Paper 40sp 等宽
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = circleTop - 32.dp - 54.dp)
                .height(54.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Text(
                text = formatElapsed(state.elapsedMs),
                color = Paper,
                fontSize = 40.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        // 振幅环 r≈88–96dp：只有在录/按住时才出现；静音输入时也停在 88dp 的最小圈。
        val ringRadius = (88.dp + 8.dp * state.amplitude.coerceIn(0f, 1f)).value
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = circleTop - (RING_SIZE - CIRCLE_SIZE) / 2)
                .size(RING_SIZE),
            contentAlignment = Alignment.Center,
        ) {
            if (recording || pressed) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Ember.copy(alpha = 0.55f),
                        radius = ringRadius.dp.toPx(),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }

            val scale by animateFloatAsState(
                targetValue = if (pressed) 0.96f else 1f,
                animationSpec = tween(DreamMotion.Quick),
                label = "recordButtonScale",
            )

            Box(
                modifier = Modifier
                    .size(CIRCLE_SIZE)
                    .scale(scale)
                    .semantics {
                        contentDescription = when {
                            enospc -> "空间不足，先去清理"
                            saving -> "正在保存"
                            else -> "按住说梦"
                        }
                    }
                    .draggableRecordGesture(
                        enabled = !saving && !enospc,
                        onPressedChange = { pressed = it },
                        onStart = {
                            if (vm.state.value.permissionState == PermissionState.GRANTED) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            Log.d(TAG, "press entry=$entry")
                            vm.onPressStart(entry)
                        },
                        onStop = {
                            Log.d(TAG, "release phase=${vm.state.value.phase}")
                            vm.onRelease()
                        },
                        onSwipeCancel = { vm.onCancel() },
                        onInterrupted = {
                            val snapshot = vm.state.value
                            Log.d(TAG, "interrupted phase=${snapshot.phase} elapsedMs=${snapshot.elapsedMs}")
                            if (snapshot.phase == RecordingPhase.RECORDING ||
                                snapshot.phase == RecordingPhase.FINISHING
                            ) {
                                if (snapshot.elapsedMs < 200L) {
                                    vm.onCancel()
                                } else {
                                    interruptedAtMs = snapshot.elapsedMs
                                    interrupted = true
                                    vm.onRelease()
                                }
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(color = if (saving) NightRaised else EmberDeep)
                    drawCircle(
                        color = if (saving) InkFaint else Ember,
                        style = Stroke(width = if (pressed) 3.dp.toPx() else 1.5.dp.toPx()),
                    )
                }
                Text(
                    text = when {
                        enospc -> "空间不足，\n先去清理"
                        saving -> "正在保存"
                        else -> "按住 说梦"
                    },
                    color = if (saving) InkFaint else Paper,
                    fontSize = 18.sp,
                )
            }
        }

        // 启动 / 保存窗口
        val statusY = circleTop + CIRCLE_SIZE + 20.dp
        when {
            starting -> Text(
                text = "正在启动麦克风…",
                color = InkDim,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = statusY),
            )
            saving -> Text(
                text = "正在保存…",
                color = Paper,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = statusY),
            )
            state.permissionState != PermissionState.GRANTED && !permissionBlocked -> Text(
                text = "需要麦克风权限才能录梦",
                color = InkDim,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = statusY),
            )
        }

        // 中断横幅：顶部一行 Paper + 两个动作
        if (interrupted) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(NightRaised)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "录音被系统打断（${formatElapsed(interruptedAtMs)}）",
                    color = Paper,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    interrupted = false
                    vm.continueAfterInterrupt()
                }) { Text("继续录", color = Ember) }
                TextButton(onClick = { interrupted = false }) { Text("就这样", color = Paper) }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!notificationsEnabled) {
                Text(
                    text = "通知栏不会显示，录音照常",
                    color = InkDim,
                    fontSize = 12.sp,
                )
            }

            if (permissionBlocked && state.permissionState != PermissionState.GRANTED) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NightRaised, RoundedCornerShape(12.dp))
                        .border(1.dp, Line, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("需要麦克风权限才能录梦", color = Paper, fontSize = 15.sp)
                    Button(
                        onClick = {
                            if (state.permissionState == PermissionState.ASKABLE) {
                                onRequestPermission()
                            } else {
                                openAppSettings(context)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmberDeep,
                            contentColor = Paper,
                        ),
                    ) {
                        Text(
                            if (state.permissionState == PermissionState.ASKABLE) "允许麦克风" else "去设置",
                        )
                    }
                }
            }

            if (error != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NightRaised, RoundedCornerShape(12.dp))
                        .border(1.dp, Ember, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (enospc) "空间不足" else "录音没有保存成功",
                        color = Paper,
                        fontSize = 16.sp,
                    )
                    Text(
                        text = error,
                        color = InkDim,
                        fontSize = 13.sp,
                        maxLines = 2,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                debugDismissed = true
                                vm.retry()
                            },
                            enabled = !enospc,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmberDeep,
                                contentColor = Paper,
                            ),
                        ) { Text("再试一次") }
                        TextButton(onClick = {
                            vm.consumeSavedDream()
                            onBackHome()
                        }) {
                            Text("返回首页（这段录音留着）", color = Paper, fontSize = 13.sp)
                        }
                    }
                }
            }

            if (recording || saving) {
                OutlinedButton(
                    onClick = { vm.onCancel() },
                    modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp),
                    border = BorderStroke(1.dp, Line),
                ) {
                    Text("取消录音", color = InkDim)
                }
            }
        }
    }
}

/**
 * 按住手势：正常松手 = onStop；系统抢焦 = onInterrupted；录音中上滑 ≥96dp = onSwipeCancel。
 *
 * 不复用 `detectTapGestures`：它在被祖先/系统抢走时会静默返回，无法区分「松手」和「打断」。
 */
@Composable
private fun Modifier.draggableRecordGesture(
    enabled: Boolean,
    onPressedChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSwipeCancel: () -> Unit,
    onInterrupted: () -> Unit,
): Modifier {
    val enabledState = rememberUpdatedState(enabled)
    val pressedChange = rememberUpdatedState(onPressedChange)
    val start = rememberUpdatedState(onStart)
    val stop = rememberUpdatedState(onStop)
    val swipeCancel = rememberUpdatedState(onSwipeCancel)
    val interrupted = rememberUpdatedState(onInterrupted)
    return this
        // 同手可达的取消（§0.13）：录音中上滑 96dp。这一层只**观察**、不消费事件，
        // 否则下面按住手势的 waitForUpOrCancellation 会把上滑误判成「被系统打断」。
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var draggedBy = 0f
                val threshold = CANCEL_SWIPE.toPx()
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUp() || change.isConsumed) break
                    draggedBy += change.positionChange().y
                    if (draggedBy < -threshold) {
                        swipeCancel.value()
                        Log.d(TAG, "swipe-up cancel")
                        break
                    }
                }
            }
        }
        // 按住说梦（§0.9）：up == null 是系统抢焦 → onInterrupted；
        // try/finally 保证 pointerInput 被取消时也不会留下「服务还在录、界面已松手」。
        .pointerInput(Unit) {
            var held = false
            try {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (!enabledState.value) {
                        // 禁用态（FINISHING / ENOSPC）只把这次按压吃掉，不做任何状态迁移。
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    held = true
                    pressedChange.value(true)
                    start.value()
                    val up = waitForUpOrCancellation()
                    held = false
                    pressedChange.value(false)
                    if (up == null) interrupted.value() else stop.value()
                }
            } finally {
                if (held) {
                    held = false
                    pressedChange.value(false)
                    stop.value()
                }
            }
        }
}

private fun openAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
