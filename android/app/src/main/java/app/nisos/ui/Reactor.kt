package app.nisos.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The reactor ring -- the whole visual identity, drawn rather than shipped.
 *
 * Everything here is a Canvas primitive: arcs, a radial gradient, and a ring
 * of ticks. No image assets, no animation library, no Lottie file. That is
 * deliberate and not just minimalism -- a drawn ring scales to any screen,
 * recolours per state for free, and can be driven directly by the microphone
 * amplitude, which is the one thing that makes it feel alive rather than
 * decorative.
 *
 * The states it has to express are the ones a user genuinely needs told apart:
 * idle, listening (is it hearing me?), thinking (did it freeze?), speaking,
 * and failed. A spinner answers none of those questions.
 */
enum class Mood { Idle, Listening, Thinking, Speaking, Failed }

private val CYAN = Color(0xFF35E0F0)
private val DEEP = Color(0xFF0A6E8C)
private val AMBER = Color(0xFFF0A500)
private val RED = Color(0xFFE0483C)

private fun Mood.tint(): Color = when (this) {
    Mood.Idle -> DEEP
    Mood.Listening -> CYAN
    Mood.Thinking -> AMBER
    Mood.Speaking -> CYAN
    Mood.Failed -> RED
}

/**
 * @param level 0..1 microphone amplitude. Drives the inner ring directly, so
 *   the thing on screen is reacting to your actual voice rather than playing a
 *   canned animation at you.
 */
@Composable
fun Reactor(mood: Mood, level: Float, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "reactor")

    // One slow rotation for the outer ticks, one faster counter-rotation for
    // the inner arc. Opposed directions read as machinery; same-direction
    // reads as a loading spinner, which is exactly the wrong association.
    val slow by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing)),
        label = "slow",
    )
    val fast by transition.animateFloat(
        initialValue = 360f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing)),
        label = "fast",
    )
    // Only breathes when it is doing something. A ring that pulses while idle
    // is a ring you stop seeing.
    val breathe by transition.animateFloat(
        initialValue = 0.94f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            tween(1_800, easing = LinearEasing), RepeatMode.Reverse
        ),
        label = "breathe",
    )

    val tint = mood.tint()
    val colour by animateColorAsStateCompat(tint)
    val voice by animateFloatAsState(
        targetValue = level,
        // Springy, not linear: the ring should snap out with your voice and
        // fall back gently, which is what makes it feel connected to sound.
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "voice",
    )

    val active = mood != Mood.Idle
    val pulse = if (active) breathe else 1f

    Box(modifier = modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f

            // Core glow. Soft, and the only filled shape on screen.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colour.copy(alpha = 0.34f), Color.Transparent),
                    center = centre,
                    radius = radius * (0.62f + voice * 0.30f),
                ),
                radius = radius * (0.62f + voice * 0.30f),
                center = centre,
            )

            // Outer tick ring, slowly turning.
            rotate(slow, centre) {
                ticks(centre, radius * 0.96f, colour.copy(alpha = 0.42f), count = 72)
            }

            // Static rail, so the moving parts have something to move against.
            drawCircle(
                color = colour.copy(alpha = 0.22f),
                radius = radius * 0.86f,
                center = centre,
                style = Stroke(width = 1.5f),
            )

            // The arc that carries the state, counter-rotating.
            rotate(fast, centre) {
                drawArc(
                    color = colour,
                    startAngle = 0f,
                    sweepAngle = if (active) 96f else 42f,
                    useCenter = false,
                    topLeft = Offset(centre.x - radius * 0.74f, centre.y - radius * 0.74f),
                    size = Size(radius * 1.48f, radius * 1.48f),
                    style = Stroke(width = 4f),
                )
            }

            // Inner ring, sized by your voice. This is the piece that makes it
            // feel like it is listening to *you*.
            drawCircle(
                color = colour.copy(alpha = 0.75f),
                radius = radius * (0.44f + voice * 0.16f) * pulse,
                center = centre,
                style = Stroke(width = 2.5f),
            )
            drawCircle(
                color = colour.copy(alpha = 0.30f),
                radius = radius * 0.34f * pulse,
                center = centre,
                style = Stroke(width = 1f),
            )
        }
    }
}

/** A ring of radial ticks, longer every fifth one. */
private fun DrawScope.ticks(centre: Offset, radius: Float, colour: Color, count: Int) {
    repeat(count) { index ->
        val angle = (index * 360f / count) * (Math.PI / 180f)
        val long = index % 5 == 0
        val inner = radius - if (long) 14f else 7f
        drawLine(
            color = colour,
            start = Offset(
                centre.x + (inner * cos(angle)).toFloat(),
                centre.y + (inner * sin(angle)).toFloat(),
            ),
            end = Offset(
                centre.x + (radius * cos(angle)).toFloat(),
                centre.y + (radius * sin(angle)).toFloat(),
            ),
            strokeWidth = if (long) 2f else 1f,
        )
    }
}

/**
 * Colour crossfade without pulling in the animation-graphics artifact.
 *
 * `animateColorAsState` lives in `androidx.compose.animation`, which is
 * already here, but interpolating four floats is three lines and keeps the
 * dependency list honest.
 */
@Composable
private fun animateColorAsStateCompat(target: Color): androidx.compose.runtime.State<Color> {
    val red by animateFloatAsState(target.red, tween(420), label = "r")
    val green by animateFloatAsState(target.green, tween(420), label = "g")
    val blue by animateFloatAsState(target.blue, tween(420), label = "b")
    return androidx.compose.runtime.rememberUpdatedState(Color(red, green, blue))
}
