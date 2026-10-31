package com.dreamkeeper.ui.followup

import com.dreamkeeper.ui.theme.DreamColors
import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.service.RecordingSession
import com.dreamkeeper.service.RecordingStarter
import com.dreamkeeper.ui.theme.DreamMotion
import com.dreamkeeper.ui.theme.NightVoid
import kotlinx.coroutines.delay

/**
 * §0-11 的正文色 `#B8C1CF`（11.11:1）。
 *
 * 不在 `Color.kt` 里定义是**故意**的：Task 5 的允许改动清单不含 `ui/theme/Color.kt`，
 * 而这个颜色目前只有这一屏用，先就地放这里，别为了一个色值去改共享调色板。
 */
private val FollowUpBody = Color(0xFFB8C1CF)

/**
 * 松手后的 30 秒追问窗口。
 *
 * 这一屏只做展示与手势，所有状态与落库都在 [FollowUpViewModel]：
 * 倒计时、补录、收尾写 `rawAudioMs` 都只有 VM 这一个源，页面**不许**再跑一个
 * `LaunchedEffect + delay` 的倒计时副本（§0-9）。
 */
@Composable
fun FollowUpScreen(
    dreamId: String,
    repository: DreamRepository,
    session: RecordingSession,
    starter: RecordingStarter,
    audioStore: WavStore,
    onDismiss: () -> Unit,
) {
    val vm: FollowUpViewModel = viewModel {
        FollowUpViewModel(
            repository = repository,
            session = session,
            starter = starter,
            audioStore = audioStore,
            clock = System::currentTimeMillis,
            dreamId = dreamId,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val hint by vm.hint.collectAsStateWithLifecycle()

    // ------------------------------------------------------------ 屏幕亮度（§0-12）
    // 应用没有 DEVICE_POWER 权限，做不到真熄屏；进页把窗口亮度压到 0.01，
    // 离开恢复原值（BRIGHTNESS_OVERRIDE_NONE = -1f 表示跟随系统）。
    // 刻意**不**加 KEEP_SCREEN_ON：那会把全屏点亮住，和「极暗」相反。
    val activity = LocalContext.current as? Activity
    val window = activity?.window
    DisposableEffect(window) {
        val params = window?.attributes
        val previous = params?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (params != null) {
            params.screenBrightness = 0.01f
            window.attributes = params
        }
        onDispose {
            val current = window?.attributes
            if (current != null) {
                current.screenBrightness = previous
                window.attributes = current
            }
        }
    }

    // ------------------------------------------------------------ 进页：一次轻震 + 一次播报（§0-11 / §0-13）
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.loaded) {
        if (!state.loaded) return@LaunchedEffect
        // 只轻震一次，收起时不震。
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        // announce 一次；倒计时本身不加 liveRegion（每秒播报会把人吵醒）。
        view.announceForAccessibility("追问窗口已打开，还剩 ${state.remainingSeconds} 秒")
    }

    // ------------------------------------------------------------ 收起：等淡出播完再离开路由（§0-11）
    LaunchedEffect(state.visible, state.loaded) {
        if (state.loaded && !state.visible) {
            delay(DreamMotion.Quick.toLong())
            onDismiss()
        }
    }

    // record 路由已被 popUpTo(inclusive) 清掉，没有返回键会退到桌面（§0-10）。
    BackHandler(enabled = true) { vm.onDoneNow() }

    Box(modifier = Modifier.fillMaxSize().background(NightVoid)) {
        AnimatedVisibility(
            visible = state.visible,
            enter = fadeIn(tween(DreamMotion.Quick)),
            exit = fadeOut(tween(DreamMotion.Quick)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 系统栏内边距由 DreamKeeperRoot 的 Scaffold 统一给（NavHost 上已 padding），
                    // 这里再加 statusBarsPadding 会重复垫一次。
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "追问",
                    color = DreamColors.inkFaint,
                    fontSize = 12.sp,
                    letterSpacing = 6.sp,
                )
                Text(
                    text = if (state.recording) "在听，松手就接上" else "还想补充点什么吗？",
                    color = FollowUpBody,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                )

                // 倒计时：30..0，无动画、不变色（§0-9）。
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "还剩 ${state.remainingSeconds} 秒"
                    },
                ) {
                    Text(
                        text = "${state.remainingSeconds}",
                        color = DreamColors.ember,
                        fontSize = 54.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Text(
                        text = " 秒",
                        color = DreamColors.ember,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }

                // 大号文字显示刚转出来的话（§0-11）；点进去可以改字，但 Task 5 不落文本（§0-5）。
                TranscriptField(
                    transcript = state.transcript,
                    onEditText = vm::onEditText,
                )

                Text(
                    text = if (state.recording) {
                        "补录会接在这段录音后面，松手就保存。"
                    } else {
                        "补录的话接在刚才那段录音后面；中间的空档不补静音。"
                    },
                    color = DreamColors.inkFaint,
                    fontSize = 13.sp,
                )
                hint?.let { message ->
                    Text(text = message, color = DreamColors.ember, fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.weight(1f))

                MicArea(
                    recording = state.recording,
                    onSpeakStart = vm::onSpeakStart,
                    onSpeakStop = vm::onSpeakStop,
                )

                Button(
                    onClick = { vm.onDoneNow() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .semantics { contentDescription = "就这样，保存并收起" },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DreamColors.emberDeep,
                        contentColor = DreamColors.paper,
                    ),
                ) {
                    Text(text = "就这样", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/**
 * 转写文字区：用 [BasicTextField] 当「大号文字」，不套 `OutlinedTextField`——
 * 输入框的描边和填充会把整屏最亮面积做大，和 §0-11「极暗」冲突。
 */
@Composable
private fun TranscriptField(
    transcript: String,
    onEditText: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    BasicTextField(
        value = transcript,
        onValueChange = onEditText,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .focusRequester(focusRequester)
            .semantics {
                contentDescription = "刚转出来的话，点一下可以改"
                // 显式补一个 click action：Compile 的输入框在 a11y 树上不一定是 clickable，
                // 而 §0-13 要求三个可点节点都带 contentDescription（uiautomator 会数）。
                onClick(label = "编辑刚转出来的话") {
                    runCatching { focusRequester.requestFocus() }
                    true
                }
            },
        textStyle = TextStyle(
            color = FollowUpBody,
            fontSize = 20.sp,
            lineHeight = 30.sp,
        ),
        cursorBrush = SolidColor(DreamColors.ember),
        decorationBox = { innerTextField ->
            // 这里**不能**用 fillMaxSize：decorationBox 的约束是整列剩余高度，
            // fillMaxSize 会把输入框撑到占满整屏，把麦克风与主钮顶出屏幕。
            Box(modifier = Modifier.fillMaxWidth()) {
                if (transcript.isEmpty()) {
                    Text(
                        text = "刚转出来的话会显示在这里…",
                        color = DreamColors.inkFaint,
                        fontSize = 20.sp,
                    )
                }
                innerTextField()
            }
        },
    )
}

/**
 * 麦克风点区：按住补录、松手结束。
 *
 * 用 [androidx.compose.foundation.clickable] 的 `PressInteraction` 而不是裸 `pointerInput`：
 * `clickable` 会给节点挂上 `ACTION_CLICK`，uiautomator 才能看到 `clickable="true"`（§0-14），
 * 而按住/松手仍由 Press/Release 两个交互事件表达。
 */
@Composable
private fun MicArea(
    recording: Boolean,
    onSpeakStart: () -> Unit,
    onSpeakStop: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            // 这里刻意不读 recording：LaunchedEffect 只随 interactionSource 启动一次，
            // 闭包里捕获的是首次组合时的值（false），松手时判断会永远不成立，
            // 于是服务永远收不到 STOP（真机实测录音一直跑）。幂等门闩留给 ViewModel。
            when (interaction) {
                is PressInteraction.Press -> onSpeakStart()
                is PressInteraction.Release -> onSpeakStop()
                is PressInteraction.Cancel -> onSpeakStop()
                else -> Unit
            }
        }
    }
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp) // §0-13：在 48dp 下限之上
            .clip(shape)
            .background(if (recording) DreamColors.emberDeep.copy(alpha = 0.45f) else DreamColors.nightRaised)
            .border(1.dp, DreamColors.inkDim, shape) // §0-11：边界用 DreamColors.inkDim，禁用 DreamColors.line
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
            )
            .semantics {
                contentDescription = if (recording) "正在补录，松手结束" else "按住补录，松手结束"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (recording) "松手结束补录" else "按住补录",
            color = DreamColors.paper,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
