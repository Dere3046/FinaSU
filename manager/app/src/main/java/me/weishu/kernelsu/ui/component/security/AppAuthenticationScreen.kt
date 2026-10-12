package me.weishu.kernelsu.ui.component.security

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.security.CredentialKind
import me.weishu.kernelsu.data.security.CredentialResult
import me.weishu.kernelsu.data.security.Credentials
import me.weishu.kernelsu.data.security.SecurityRepository

enum class AuthenticationVisualState { BIOMETRIC, PIN, PATTERN, BUSY, ERROR }

private val SwapSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
private val CellSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

@Composable
private fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) > 0f
        }.getOrDefault(true)
    }
}

@Composable
fun AppAuthenticationScreen(
    state: AuthenticationVisualState,
    error: String?,
    enabled: Boolean,
    actionEnabled: Boolean,
    onVerify: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    credentialInput: (@Composable () -> Unit)? = null,
    fallbackAction: (@Composable () -> Unit)? = null,
) {
    val motion = rememberMotionEnabled()
    val busy = state == AuthenticationVisualState.BUSY
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .safeDrawingPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (busy) {
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.secondaryContainer,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 440.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StateContent(state = state, error = error, motion = motion, credentialInput = credentialInput)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onVerify,
                    enabled = enabled && actionEnabled && !busy,
                    modifier = Modifier
                        .widthIn(max = 440.dp)
                        .fillMaxWidth()
                        .height(56.dp),
                    shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                ) {
                    BusySwap(
                        busy = busy,
                        motion = motion,
                        idle = { Text(stringResource(R.string.security_unlock_action)) },
                        busyContent = {
                            LoadingIndicator(
                                modifier = Modifier.size(20.dp),
                                color = LocalContentColor.current,
                            )
                        },
                    )
                }
                fallbackAction?.invoke()
                TextButton(onClick = onCancel, enabled = !busy) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun StateContent(
    state: AuthenticationVisualState,
    error: String?,
    motion: Boolean,
    credentialInput: (@Composable () -> Unit)?,
) {
    val direction = if (state == AuthenticationVisualState.BIOMETRIC) -1 else 1
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            if (!motion) {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            } else {
                (slideInHorizontally(tween(320)) { it / 6 * direction } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(220)) { -it / 6 * direction } + fadeOut(tween(140)))
            }
        },
        label = "authentication-state",
    ) { active ->
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (active != AuthenticationVisualState.BUSY) {
                val icon = when (active) {
                    AuthenticationVisualState.PIN -> Icons.Outlined.Lock
                    AuthenticationVisualState.PATTERN -> Icons.Outlined.Gesture
                    AuthenticationVisualState.ERROR -> Icons.Outlined.ErrorOutline
                    else -> Icons.Filled.Fingerprint
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = if (active == AuthenticationVisualState.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Text(
                text = stringResource(R.string.security_unlock_title),
                style = MaterialTheme.typography.headlineMediumEmphasized,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(
                    when (active) {
                        AuthenticationVisualState.PIN -> R.string.security_pin_subtitle
                        AuthenticationVisualState.PATTERN -> R.string.security_pattern_subtitle
                        else -> R.string.security_biometric_summary
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (active == AuthenticationVisualState.PIN || active == AuthenticationVisualState.PATTERN) {
                credentialInput?.invoke()
            }
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun BusySwap(
    busy: Boolean,
    motion: Boolean,
    idle: @Composable () -> Unit,
    busyContent: @Composable () -> Unit,
) {
    AnimatedContent(
        targetState = busy,
        transitionSpec = {
            if (!motion) {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            } else {
                (fadeIn(SwapSpring) + scaleIn(SwapSpring, initialScale = 0.92f)) togetherWith
                    (fadeOut(SwapSpring) + scaleOut(SwapSpring, targetScale = 0.96f))
            }
        },
        contentAlignment = Alignment.Center,
        label = "busy-swap",
    ) { isBusy ->
        if (isBusy) busyContent() else idle()
    }
}

/** One cell per digit, mirroring the reference OTP layout: equal weight, large shape, raised focus. */
@Composable
fun PinPad(
    value: String,
    length: Int,
    enabled: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
    onComplete: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = { input -> if (input.length <= length && input.all { it.isDigit() }) onValueChange(input) },
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus),
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onComplete() }),
        decorationBox = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(length) { index ->
                    PinCell(
                        digit = value.getOrNull(index)?.toString().orEmpty(),
                        index = index,
                        focused = value.length == index && enabled,
                        isError = isError,
                        modifier = Modifier.weight(1f),
                        onClick = { runCatching { focus.requestFocus() } },
                    )
                }
            }
        },
    )
}

@Composable
private fun PinCell(
    digit: String,
    index: Int,
    focused: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val filled = digit.isNotEmpty()
    // The filling cell morphs: a press-like scale and a container swap.
    val scale by animateFloatAsState(
        targetValue = if (filled) 1f else 0.94f,
        animationSpec = CellSpring,
        label = "pin-cell-scale",
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(56.dp)
            .scale(scale),
        shape = MaterialTheme.shapes.large,
        color = when {
            isError -> MaterialTheme.colorScheme.errorContainer
            filled -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = when {
            isError -> MaterialTheme.colorScheme.onErrorContainer
            filled -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        },
        tonalElevation = if (focused) 2.dp else 0.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (filled) {
                MaskToken(index = index)
            }
        }
    }
}

/**
 * The entered digits are never drawn; every filled cell paints one shape instead. The set and its
 * order follow the framework's own expressive loading rotation (LoadingIndicatorDefaults), so the
 * tokens read as one family and never fall back to a half-round outline.
 */
private val MaskShapes = listOf(
    MaterialShapes.SoftBurst,
    MaterialShapes.Cookie9Sided,
    MaterialShapes.Pentagon,
    MaterialShapes.Pill,
    MaterialShapes.Sunny,
    MaterialShapes.Cookie4Sided,
    MaterialShapes.Oval,
    MaterialShapes.Circle,
)

@Composable
private fun MaskToken(index: Int, modifier: Modifier = Modifier) {
    val polygon = MaskShapes[index % MaskShapes.size]
    Box(
        modifier = modifier
            .size(18.dp)
            .background(color = LocalContentColor.current, shape = polygon.toShape()),
    )
}

/** 3x3 drag pattern; a connected node morphs from a dot into the primary rounded square. */
@Composable
fun PatternGrid(
    nodes: List<Int>,
    enabled: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier,
    onNodesChange: (List<Int>) -> Unit,
    onComplete: (List<Int>) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val active = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.surfaceContainerHighest
    // A drag session outlives the composition it started in, so it must not read the callbacks and
    // the node list captured when the gesture began: it accumulates locally and reports the result.
    val latestNodesChange = rememberUpdatedState(onNodesChange)
    val latestComplete = rememberUpdatedState(onComplete)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var working: List<Int> = emptyList()
                detectDragGestures(
                    onDragStart = { position ->
                        hitTest(position, Size(size.width.toFloat(), size.height.toFloat()))?.let { node ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            working = listOf(node)
                            latestNodesChange.value(working)
                        }
                    },
                    onDrag = { change, _ ->
                        hitTest(change.position, Size(size.width.toFloat(), size.height.toFloat()))?.let { node ->
                            if (node !in working) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                working = working + node
                                latestNodesChange.value(working)
                            }
                        }
                    },
                    onDragEnd = { latestComplete.value(working) },
                    onDragCancel = { latestComplete.value(working) },
                )
            },
    ) {
        val step = gridStep(size)
        val lineWidth = 6.dp.toPx()
        val nodeRadius = step * 0.16f
        nodes.zipWithNext().forEach { (from, to) ->
            drawLine(
                color = active,
                start = centerOf(from, size, step),
                end = centerOf(to, size, step),
                strokeWidth = lineWidth,
                cap = StrokeCap.Round,
            )
        }
        repeat(Credentials.PATTERN_NODE_COUNT) { index ->
            val center = centerOf(index, size, step)
            if (index in nodes) {
                val half = nodeRadius * 1.6f
                drawRoundRect(
                    color = active,
                    topLeft = Offset(center.x - half, center.y - half),
                    size = Size(half * 2f, half * 2f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(half * 0.5f),
                )
            } else {
                drawCircle(color = idle, radius = nodeRadius, center = center)
            }
        }
    }
}

private fun gridStep(size: Size): Float = minOf(size.width, size.height) / 3f

private fun centerOf(index: Int, size: Size, step: Float): Offset {
    val originX = (size.width - step * 3f) / 2f
    val originY = (size.height - step * 3f) / 2f
    return Offset(
        x = originX + step * (index % 3 + 0.5f),
        y = originY + step * (index / 3 + 0.5f),
    )
}

private fun hitTest(position: Offset, size: Size): Int? {
    val step = gridStep(size)
    val radius = step * 0.42f
    return (0 until Credentials.PATTERN_NODE_COUNT).firstOrNull { index ->
        (centerOf(index, size, step) - position).getDistance() <= radius
    }
}

/** Startup credential prompt: full-screen, inside the lock gate. */
@Composable
fun StartupCredentialScreen(
    kind: CredentialKind,
    repository: SecurityRepository,
    onVerified: () -> Unit,
    onCancel: () -> Unit,
) {
    CredentialChallenge(kind = kind, repository = repository, onVerified = onVerified, onCancel = onCancel)
}

@Composable
private fun CredentialChallenge(
    kind: CredentialKind,
    repository: SecurityRepository,
    onVerified: () -> Unit,
    onCancel: () -> Unit,
) {
    key(kind) {
        var pin by remember { mutableStateOf("") }
        var nodes by remember { mutableStateOf(emptyList<Int>()) }
        var busy by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<CredentialResult?>(null) }
        val scope = rememberCoroutineScope()
        val ready = when (kind) {
            CredentialKind.PIN -> Credentials.isValidPin(pin.toCharArray())
            CredentialKind.PATTERN -> nodes.size >= Credentials.PATTERN_NODES_MIN
        }

        fun submit() {
            if (busy) return
            val secret = when (kind) {
                CredentialKind.PIN -> pin.toCharArray().takeIf { Credentials.isValidPin(it) }
                CredentialKind.PATTERN -> Credentials.patternSecret(nodes)
            } ?: return
            busy = true
            result = null
            scope.launch {
                try {
                    val outcome = try {
                        repository.verify(kind, secret)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        CredentialResult.StorageError
                    }
                    if (outcome == CredentialResult.Success) {
                        pin = ""
                        nodes = emptyList()
                    }
                    result = outcome
                } finally {
                    secret.fill('\u0000')
                    busy = false
                }
            }
        }

        LaunchedEffect(result) {
            if (result == CredentialResult.Success) {
                result = null
                onVerified()
            }
        }

        val state = when {
            busy -> AuthenticationVisualState.BUSY
            result != null && result != CredentialResult.Success -> AuthenticationVisualState.ERROR
            kind == CredentialKind.PIN -> AuthenticationVisualState.PIN
            else -> AuthenticationVisualState.PATTERN
        }
        val error = when (result) {
            CredentialResult.Incorrect, CredentialResult.InvalidFormat -> stringResource(R.string.security_password_incorrect)
            is CredentialResult.RetryAfter ->
                stringResource(R.string.security_password_retry, ((result as CredentialResult.RetryAfter).millis + 999) / 1000)
            CredentialResult.StorageError -> stringResource(R.string.security_storage_error)
            CredentialResult.Unavailable, CredentialResult.AlreadySet -> stringResource(R.string.security_password_unavailable)
            else -> null
        }

        AppAuthenticationScreen(
            state = state,
            error = error,
            enabled = !busy,
            actionEnabled = ready,
            onVerify = ::submit,
            onCancel = onCancel,
            credentialInput = {
                when (kind) {
                    CredentialKind.PIN -> PinPad(
                        value = pin,
                        length = Credentials.PIN_LENGTH_LONG,
                        enabled = !busy,
                        isError = error != null,
                        onValueChange = { pin = it; result = null },
                        onComplete = ::submit,
                    )

                    CredentialKind.PATTERN -> PatternGrid(
                        nodes = nodes,
                        enabled = !busy,
                        isError = error != null,
                        onNodesChange = { nodes = it; result = null },
                        onComplete = { final -> nodes = final; result = null; submit() },
                    )
                }
            },
        )
    }
}
