package dev.nicky.ember.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.nicky.ember.R
import java.util.Locale

class ColorPaletteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val saturationPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(70, 0, 0, 0)
    }
    private val markerOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(5f)
        color = Color.argb(150, 0, 0, 0)
    }
    private val markerInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.WHITE
    }
    private val palettePath = Path()
    private val hsv = floatArrayOf(20f, 0.65f, 1f)
    private val cornerRadius = dp(18f)
    private val markerRadius = dp(10f)

    var onColorChanged: ((Int) -> Unit)? = null

    val selectedColor: Int
        get() = Color.HSVToColor(hsv)

    val brightness: Float
        get() = hsv[2]

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateContentDescription()
    }

    fun setColor(red: Int, green: Int, blue: Int) {
        Color.RGBToHSV(red.coerceIn(0, 255), green.coerceIn(0, 255), blue.coerceIn(0, 255), hsv)
        hsv[2] = hsv[2].coerceIn(MIN_BRIGHTNESS, 1f)
        notifyColorChanged(notifyListener = false)
    }

    fun setBrightness(value: Float) {
        hsv[2] = value.coerceIn(MIN_BRIGHTNESS, 1f)
        notifyColorChanged()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return
        huePaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            0f,
            intArrayOf(
                Color.RED,
                Color.YELLOW,
                Color.GREEN,
                Color.CYAN,
                Color.BLUE,
                Color.MAGENTA,
                Color.RED,
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        saturationPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height.toFloat(),
            Color.WHITE,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        palettePath.rewind()
        palettePath.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), cornerRadius, cornerRadius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val saved = canvas.save()
        canvas.clipPath(palettePath)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), huePaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), saturationPaint)
        dimPaint.color = Color.argb(((1f - hsv[2]) * 255).toInt(), 0, 0, 0)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.restoreToCount(saved)

        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), cornerRadius, cornerRadius, borderPaint)
        val markerX = ((hsv[0] / 360f) * width).coerceIn(markerRadius, width - markerRadius)
        val markerY = (hsv[1] * height).coerceIn(markerRadius, height - markerRadius)
        canvas.drawCircle(markerX, markerY, markerRadius, markerOuterPaint)
        canvas.drawCircle(markerX, markerY, markerRadius, markerInnerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateFromTouch(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouch(x: Float, y: Float) {
        if (width <= 0 || height <= 0) return
        hsv[0] = (x.coerceIn(0f, width.toFloat()) / width) * 359.999f
        hsv[1] = y.coerceIn(0f, height.toFloat()) / height
        notifyColorChanged()
    }

    private fun notifyColorChanged(notifyListener: Boolean = true) {
        invalidate()
        updateContentDescription()
        if (notifyListener) onColorChanged?.invoke(selectedColor)
    }

    private fun updateContentDescription() {
        val color = selectedColor
        val hex = String.format(
            Locale.US,
            "#%02X%02X%02X",
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
        contentDescription = context.getString(R.string.selected_led_colour, hex)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val MIN_BRIGHTNESS = 0.1f
    }
}
