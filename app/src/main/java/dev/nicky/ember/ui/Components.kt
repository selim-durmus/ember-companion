package dev.nicky.ember.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

private const val DIAL_START_ANGLE = 135f
private const val DIAL_SWEEP = 270f
private const val DIAL_MIN_C = 20f
private const val DIAL_MAX_C = 70f

private fun dialFraction(celsius: Float): Float = ((celsius - DIAL_MIN_C) / (DIAL_MAX_C - DIAL_MIN_C)).coerceIn(0f, 1f)

/**
 * Circular gauge: the arc fills with the drink temperature, a dot marks the target and the
 * centre shows the reading.
 */
@Composable
fun TemperatureDial(
    currentCelsius: Float?,
    targetCelsius: Float?,
    valueText: String,
    unitText: String,
    caption: String,
    live: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 248.dp,
) {
    val colors = MaterialTheme.colorScheme
    val progress by animateFloatAsState(currentCelsius?.let(::dialFraction) ?: 0f, tween(700), label = "dialProgress")
    val target by animateFloatAsState(targetCelsius?.takeIf { it > 0f }?.let(::dialFraction) ?: -1f, tween(400), label = "dialTarget")
    Box(
        modifier = modifier
            .size(size)
            .semantics(mergeDescendants = true) { contentDescription = "$valueText $unitText, $caption" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize().padding(10.dp)) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = colors.surfaceContainerHighest,
                startAngle = DIAL_START_ANGLE,
                sweepAngle = DIAL_SWEEP,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            if (currentCelsius != null && progress > 0f) {
                // Rotate so the sweep gradient's seam sits in the open gap at the bottom.
                rotate(90f) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0.125f to colors.secondary,
                            0.5f to colors.tertiary,
                            0.875f to colors.primary,
                        ),
                        startAngle = DIAL_START_ANGLE - 90f,
                        sweepAngle = DIAL_SWEEP * progress,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                        alpha = if (live) 1f else 0.45f,
                    )
                }
            }
            if (target >= 0f) {
                val angle = Math.toRadians((DIAL_START_ANGLE + DIAL_SWEEP * target).toDouble())
                val radius = arcSize.width / 2
                val center = Offset(this.size.width / 2, this.size.height / 2)
                val point = Offset(center.x + radius * cos(angle).toFloat(), center.y + radius * sin(angle).toFloat())
                drawCircle(colors.surface, radius = stroke * 0.62f, center = point)
                drawCircle(colors.primary, radius = stroke * 0.62f, center = point, style = Stroke(3.dp.toPx()))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(if (live) 1f else 0.7f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(valueText, style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp), color = colors.onSurface)
                Text(
                    unitText,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, start = 2.dp),
                )
            }
            Text(
                caption,
                style = MaterialTheme.typography.titleSmall,
                color = colors.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(size * 0.6f),
            )
        }
    }
}

@Composable
fun InfoPill(icon: Painter, text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (emphasized) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (emphasized) colors.onPrimaryContainer else colors.onSurfaceVariant,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
fun ColorSwatch(color: Color, selected: Boolean, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            ),
    )
}

/** Hue runs left to right, saturation top (white) to bottom (full colour). */
@Composable
fun HueSaturationPicker(
    hue: Float,
    saturation: Float,
    onChange: (hue: Float, saturation: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val hues = remember { (0..6).map { Color.hsv((it * 60f) % 360f, 1f, 1f) } }
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.9f)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, outline, RoundedCornerShape(20.dp))
            .pointerInput(Unit) {
                fun update(offset: Offset) {
                    onChange(
                        (offset.x / size.width).coerceIn(0f, 1f) * 359.9f,
                        (offset.y / size.height).coerceIn(0f, 1f),
                    )
                }
                detectTapGestures(onPress = { update(it) })
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange(
                        (change.position.x / size.width).coerceIn(0f, 1f) * 359.9f,
                        (change.position.y / size.height).coerceIn(0f, 1f),
                    )
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(hues))
        drawRect(Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0f))))
        val marker = Offset(hue / 360f * size.width, saturation * size.height)
        drawCircle(Color.Black.copy(alpha = 0.45f), radius = 12.dp.toPx(), center = marker, style = Stroke(5.dp.toPx()))
        drawCircle(Color.White, radius = 12.dp.toPx(), center = marker, style = Stroke(2.5.dp.toPx()))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End)
    }
}

@Composable
fun VerticalGap(height: Dp) = Spacer(Modifier.height(height))
