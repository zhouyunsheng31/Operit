package com.ai.assistance.operit.ui.features.websession.browser

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun WebSessionMinimizedIndicator(
    contentDescription: String,
    onToggleFullscreen: () -> Unit,
    onDragBy: (dx: Int, dy: Int) -> Unit
) {
    val transition = rememberInfiniteTransition(label = "web-session-indicator")
    val primaryColor = MaterialTheme.colorScheme.primary

    val bobbingDp by
        transition.animateFloat(
            initialValue = -2f,
            targetValue = 2f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
            label = "bobbing"
        )

    val wiggleDeg by
        transition.animateFloat(
            initialValue = -8f,
            targetValue = 8f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
            label = "wiggle"
        )

    val pulse by
        transition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.04f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 1100, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
            label = "pulse"
        )

    Surface(
        shape = CircleShape,
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier =
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures { _, dragAmount ->
                        onDragBy(dragAmount.x.roundToInt(), dragAmount.y.roundToInt())
                    }
                }
                .semantics {
                    this.contentDescription = contentDescription
                }
                .clickable { onToggleFullscreen() }
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .scale(pulse)
                    .drawBehind {
                        val radius = size.minDimension / 2f
                        drawCircle(
                            brush =
                                Brush.radialGradient(
                                    colors =
                                        listOf(
                                            Color.White.copy(alpha = 0.40f),
                                            primaryColor.copy(alpha = 0.16f),
                                            Color.Transparent
                                        ),
                                    center = Offset(size.width * 0.30f, size.height * 0.28f),
                                    radius = radius * 1.15f
                                ),
                            radius = radius
                        )

                        drawCircle(
                            color = primaryColor.copy(alpha = 0.12f),
                            radius = radius * 0.76f,
                            center = Offset(size.width * 0.5f, size.height * 0.54f)
                        )
                    }
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.35f),
                        shape = CircleShape
                    ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Language,
                contentDescription = null,
                tint = primaryColor.copy(alpha = 0.76f),
                modifier =
                    Modifier
                        .offset(y = bobbingDp.dp)
                        .rotate(wiggleDeg)
            )
        }
    }
}
