package com.example.mascot

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.core.haptics.PmugHaptics
import com.example.core.theme.PmugMotionSpec
import com.example.core.theme.PmugTokens
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun PmugMug(
    modifier: Modifier = Modifier,
    mood: PmugMood = PmugMood.IDLE,
    fillLevel: Float = 0f, // 0.0 to 1.0 (reading progress)
    size: Dp = 64.dp,
    onTap: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val animationsEnabled = remember { PmugMotionSpec.areAnimationsEnabled(context) }

    // Tap reaction rotation: HOP -> CHEEKY_BLINK -> STEAM_PUFF
    var lastReaction by remember { mutableStateOf<PmugTapReaction?>(null) }
    var reactionHop by remember { mutableFloatStateOf(0f) }
    var reactionSteamBoost by remember { mutableFloatStateOf(0f) }
    var reactionBlink by remember { mutableStateOf(false) }

    // Blink timer (slow random blink every 3 to 6 seconds)
    var isBlinking by remember { mutableStateOf(false) }
    LaunchedEffect(mood) {
        if (!animationsEnabled || mood == PmugMood.READING) return@LaunchedEffect
        while (true) {
            val interval = Random.nextLong(3000, 6000)
            delay(interval)
            isBlinking = true
            delay(160)
            isBlinking = false
        }
    }

    // Infinite animation for steam wisps
    val infiniteTransition = rememberInfiniteTransition(label = "steam_flow")
    val steamPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (mood == PmugMood.PARSING) 1100 else 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "steam_phase"
    )

    // Expression target values based on mood
    val targetEyelid = when {
        reactionBlink -> 1.0f
        isBlinking -> 1.0f
        mood == PmugMood.LONG_IDLE -> 0.9f
        mood == PmugMood.PAUSED -> 0.5f
        mood == PmugMood.DOCUMENT_FINISHED -> 0.2f
        else -> 0.0f
    }

    val targetBrowTilt = when (mood) {
        PmugMood.PAGE_FINISHED -> -0.35f // One raised brow
        PmugMood.DOCUMENT_FINISHED -> -0.4f
        PmugMood.PARSING -> 0.15f
        else -> 0.0f
    }

    val targetMouthSmug = when (mood) {
        PmugMood.PAGE_FINISHED, PmugMood.DOCUMENT_FINISHED -> 0.8f // Big smug smirk
        PmugMood.IDLE, PmugMood.READING -> 0.35f // Quiet smug
        PmugMood.PAUSED -> 0.1f
        else -> 0.2f
    }

    val targetSteamAmp = when (mood) {
        PmugMood.READING -> 1.4f + reactionSteamBoost
        PmugMood.PARSING -> 1.8f
        PmugMood.PAUSED, PmugMood.LONG_IDLE -> 0.3f
        else -> 0.8f + reactionSteamBoost
    }

    val targetEyeGlance = when (mood) {
        PmugMood.PARSING -> 0.7f // Glance sideways
        PmugMood.READING -> 0.3f // Drift in reading direction
        else -> 0.0f
    }

    // Smooth physics transitions
    val animatedEyelid by animateFloatAsState(
        targetValue = targetEyelid,
        animationSpec = spring(stiffness = 500f),
        label = "eyelid"
    )
    val animatedBrowTilt by animateFloatAsState(
        targetValue = targetBrowTilt,
        animationSpec = PmugMotionSpec.BouncySpring,
        label = "brow"
    )
    val animatedMouth by animateFloatAsState(
        targetValue = targetMouthSmug,
        animationSpec = PmugMotionSpec.SnappySpring,
        label = "mouth"
    )
    val animatedSteamAmp by animateFloatAsState(
        targetValue = targetSteamAmp,
        animationSpec = spring(stiffness = 300f),
        label = "steam_amp"
    )
    val animatedGlance by animateFloatAsState(
        targetValue = targetEyeGlance,
        animationSpec = spring(stiffness = 400f),
        label = "glance"
    )
    val animatedFill by animateFloatAsState(
        targetValue = fillLevel.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = 150f),
        label = "fill"
    )

    // Handle tap reaction
    val handleTap: () -> Unit = {
        PmugHaptics.tick(context)
        val nextReaction = when (lastReaction) {
            PmugTapReaction.HOP -> PmugTapReaction.CHEEKY_BLINK
            PmugTapReaction.CHEEKY_BLINK -> PmugTapReaction.STEAM_PUFF
            else -> PmugTapReaction.HOP
        }
        lastReaction = nextReaction

        when (nextReaction) {
            PmugTapReaction.HOP -> {
                reactionHop = -12f
            }
            PmugTapReaction.CHEEKY_BLINK -> {
                reactionBlink = true
            }
            PmugTapReaction.STEAM_PUFF -> {
                reactionSteamBoost = 1.6f
            }
        }
        onTap?.invoke()
    }

    // Reset temporary reactions
    LaunchedEffect(reactionHop) {
        if (reactionHop != 0f) {
            delay(280)
            reactionHop = 0f
        }
    }
    LaunchedEffect(reactionBlink) {
        if (reactionBlink) {
            delay(320)
            reactionBlink = false
        }
    }
    LaunchedEffect(reactionSteamBoost) {
        if (reactionSteamBoost != 0f) {
            delay(500)
            reactionSteamBoost = 0f
        }
    }

    val animatedHopOffset by animateFloatAsState(
        targetValue = reactionHop,
        animationSpec = PmugMotionSpec.BouncySpring,
        label = "hop_offset"
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                translationY = animatedHopOffset
            }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { handleTap() }
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val canvasW = size.toPx()
            val canvasH = size.toPx()

            // 1. Draw 3 animated Bézier steam paths rising from mug
            drawSteamWisps(
                canvasW = canvasW,
                canvasH = canvasH,
                phase = steamPhase,
                amplitudeMultiplier = animatedSteamAmp
            )

            // 2. Draw Enamel Mug Body & Handle
            drawMugBodyAndHandle(
                canvasW = canvasW,
                canvasH = canvasH,
                fillProgress = animatedFill
            )

            // 3. Draw Smug Mug Face
            drawSmugFace(
                canvasW = canvasW,
                canvasH = canvasH,
                eyelidFraction = animatedEyelid,
                browTilt = animatedBrowTilt,
                mouthCurve = animatedMouth,
                glanceOffset = animatedGlance
            )
        }
    }
}

private fun DrawScope.drawSteamWisps(
    canvasW: Float,
    canvasH: Float,
    phase: Float,
    amplitudeMultiplier: Float
) {
    if (amplitudeMultiplier <= 0.05f) return

    val steamColor = PmugTokens.Ink.copy(alpha = 0.45f)
    val steamStroke = Stroke(
        width = (canvasW * 0.035f).coerceAtLeast(1.5f),
        cap = StrokeCap.Round
    )

    val mugTopY = canvasH * 0.48f
    val steamHeight = canvasH * 0.38f

    // 3 Bézier wisps: Left, Center, Right
    val wispOffsets = listOf(-0.15f, 0.0f, 0.15f)
    for ((idx, xRatio) in wispOffsets.withIndex()) {
        val startX = canvasW * (0.42f + xRatio)
        val path = Path()
        path.moveTo(startX, mugTopY)

        val localPhase = phase + (idx * 1.8f)
        val sway = (canvasW * 0.06f * amplitudeMultiplier) * sin(localPhase)

        val cp1X = startX + sway
        val cp1Y = mugTopY - (steamHeight * 0.4f)
        val cp2X = startX - sway
        val cp2Y = mugTopY - (steamHeight * 0.75f)
        val endX = startX + (sway * 0.5f)
        val endY = mugTopY - steamHeight

        path.cubicTo(cp1X, cp1Y, cp2X, cp2Y, endX, endY)
        drawPath(path, color = steamColor, style = steamStroke)
    }
}

private fun DrawScope.drawMugBodyAndHandle(
    canvasW: Float,
    canvasH: Float,
    fillProgress: Float
) {
    val enamelColor = PmugTokens.Ink
    val strokeColor = PmugTokens.Ground
    val accentColor = PmugTokens.Accent

    val mugLeft = canvasW * 0.22f
    val mugTop = canvasH * 0.48f
    val mugWidth = canvasW * 0.48f
    val mugHeight = canvasH * 0.44f
    val cornerR = mugWidth * 0.22f

    // Handle on the right side
    val handleLeft = mugLeft + mugWidth - (canvasW * 0.04f)
    val handleTop = mugTop + (mugHeight * 0.18f)
    val handleWidth = canvasW * 0.18f
    val handleHeight = mugHeight * 0.60f
    val handleThickness = canvasW * 0.065f

    val handlePath = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(handleLeft, handleTop, handleLeft + handleWidth, handleTop + handleHeight),
                topRight = CornerRadius(handleHeight * 0.45f, handleHeight * 0.45f),
                bottomRight = CornerRadius(handleHeight * 0.45f, handleHeight * 0.45f)
            )
        )
    }
    // Outer handle
    drawPath(handlePath, color = enamelColor)
    drawPath(handlePath, color = strokeColor, style = Stroke(width = canvasW * 0.025f))

    // Handle inner cutout
    val innerHandle = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(
                    handleLeft + (handleThickness * 0.5f),
                    handleTop + handleThickness,
                    handleLeft + handleWidth - handleThickness,
                    handleTop + handleHeight - handleThickness
                ),
                topRight = CornerRadius(handleHeight * 0.25f, handleHeight * 0.25f),
                bottomRight = CornerRadius(handleHeight * 0.25f, handleHeight * 0.25f)
            )
        )
    }
    drawPath(innerHandle, color = PmugTokens.Ground)
    drawPath(innerHandle, color = strokeColor, style = Stroke(width = canvasW * 0.02f))

    // Mug Body
    val bodyPath = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(mugLeft, mugTop, mugLeft + mugWidth, mugTop + mugHeight),
                topLeft = CornerRadius(canvasW * 0.04f, canvasW * 0.04f),
                topRight = CornerRadius(canvasW * 0.04f, canvasW * 0.04f),
                bottomLeft = CornerRadius(cornerR, cornerR),
                bottomRight = CornerRadius(cornerR, cornerR)
            )
        )
    }
    drawPath(bodyPath, color = enamelColor)

    // Fill level: drawn from bottom up in accent colour #D6F25C
    if (fillProgress > 0.02f) {
        val fillHeight = (mugHeight - (canvasW * 0.04f)) * fillProgress
        val fillTop = (mugTop + mugHeight) - fillHeight

        val fillPath = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(mugLeft + (canvasW * 0.02f), fillTop, mugLeft + mugWidth - (canvasW * 0.02f), mugTop + mugHeight - (canvasW * 0.02f)),
                    topLeft = CornerRadius(4f, 4f),
                    topRight = CornerRadius(4f, 4f),
                    bottomLeft = CornerRadius(cornerR * 0.85f, cornerR * 0.85f),
                    bottomRight = CornerRadius(cornerR * 0.85f, cornerR * 0.85f)
                )
            )
        }
        drawPath(fillPath, color = accentColor)
    }

    // Outer rim stroke
    drawPath(bodyPath, color = strokeColor, style = Stroke(width = canvasW * 0.03f))

    // Subtle enamel top rim band
    drawLine(
        color = strokeColor,
        start = Offset(mugLeft, mugTop + (canvasW * 0.04f)),
        end = Offset(mugLeft + mugWidth, mugTop + (canvasW * 0.04f)),
        strokeWidth = canvasW * 0.025f,
        cap = StrokeCap.Round
    )
}

private fun DrawScope.drawSmugFace(
    canvasW: Float,
    canvasH: Float,
    eyelidFraction: Float,
    browTilt: Float,
    mouthCurve: Float,
    glanceOffset: Float
) {
    val faceColor = PmugTokens.Ground
    val mugCenterX = canvasW * 0.46f
    val faceCenterY = canvasH * 0.70f

    val eyeSpacing = canvasW * 0.12f
    val eyeRadius = canvasW * 0.028f
    val glanceShift = glanceOffset * (eyeRadius * 0.8f)

    // Left Eye
    val leftEyeX = mugCenterX - (eyeSpacing * 0.5f) + glanceShift
    val rightEyeX = mugCenterX + (eyeSpacing * 0.5f) + glanceShift
    val eyeY = faceCenterY - (canvasH * 0.035f)

    // Draw eye dots
    drawCircle(color = faceColor, radius = eyeRadius, center = Offset(leftEyeX, eyeY))
    drawCircle(color = faceColor, radius = eyeRadius, center = Offset(rightEyeX, eyeY))

    // Draw eyelids (if fraction > 0)
    if (eyelidFraction > 0.05f) {
        val lidCover = (eyeRadius * 2.2f) * eyelidFraction
        drawRect(
            color = PmugTokens.Ink,
            topLeft = Offset(leftEyeX - eyeRadius - 1f, eyeY - eyeRadius - 1f),
            size = Size(eyeRadius * 2.5f, lidCover)
        )
        drawRect(
            color = PmugTokens.Ink,
            topLeft = Offset(rightEyeX - eyeRadius - 1f, eyeY - eyeRadius - 1f),
            size = Size(eyeRadius * 2.5f, lidCover)
        )
    }

    // Smug Brows: Right brow raised when smug
    val browWidth = canvasW * 0.06f
    val leftBrowY = eyeY - eyeRadius - (canvasW * 0.02f)
    val rightBrowY = eyeY - eyeRadius - (canvasW * 0.02f) + (browTilt * canvasW * 0.05f)

    // Left brow
    drawLine(
        color = faceColor,
        start = Offset(leftEyeX - (browWidth * 0.5f), leftBrowY),
        end = Offset(leftEyeX + (browWidth * 0.5f), leftBrowY - (canvasW * 0.005f)),
        strokeWidth = canvasW * 0.016f,
        cap = StrokeCap.Round
    )

    // Right brow (raised and tilted smugly)
    drawLine(
        color = faceColor,
        start = Offset(rightEyeX - (browWidth * 0.5f), rightBrowY + (browTilt * canvasW * 0.02f)),
        end = Offset(rightEyeX + (browWidth * 0.5f), rightBrowY - (browTilt * canvasW * 0.04f)),
        strokeWidth = canvasW * 0.018f,
        cap = StrokeCap.Round
    )

    // Smug Smirk Mouth
    val mouthY = faceCenterY + (canvasH * 0.045f)
    val mouthWidth = canvasW * 0.10f
    val mouthPath = Path().apply {
        moveTo(mugCenterX - (mouthWidth * 0.4f), mouthY)
        // Upward right smirk curve
        val smirkLift = mouthCurve * (canvasH * 0.03f)
        quadraticTo(
            mugCenterX, mouthY + (canvasH * 0.01f),
            mugCenterX + (mouthWidth * 0.5f), mouthY - smirkLift
        )
    }
    drawPath(
        mouthPath,
        color = faceColor,
        style = Stroke(width = canvasW * 0.02f, cap = StrokeCap.Round)
    )
}
