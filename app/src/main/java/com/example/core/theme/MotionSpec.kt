package com.example.core.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

object PmugMotionSpec {
    // Standard springs
    val BouncySpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )

    val SnappySpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    // Standard durations (Section 6: feedback 200ms or less, transitions 250 to 350ms)
    const val FeedbackDuration = 180
    const val TransitionDuration = 300

    val TransitionTween: AnimationSpec<Float> = tween(
        durationMillis = TransitionDuration,
        easing = FastOutSlowInEasing
    )

    fun areAnimationsEnabled(context: Context): Boolean {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        )
        return scale > 0.05f
    }

    @Composable
    fun Modifier.pmugPressScale(
        onClick: () -> Unit,
        enabled: Boolean = true,
        scaleTarget: Float = 0.96f
    ): Modifier {
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()

        val context = LocalContext.current
        val animationsEnabled = areAnimationsEnabled(context)

        val scale = if (animationsEnabled && isPressed) scaleTarget else 1.0f

        return this
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
    }
}

@Composable
fun Modifier.pmugPressScale(
    onClick: () -> Unit,
    enabled: Boolean = true,
    scaleTarget: Float = 0.96f
): Modifier = with(PmugMotionSpec) {
    this@pmugPressScale.pmugPressScale(onClick, enabled, scaleTarget)
}
