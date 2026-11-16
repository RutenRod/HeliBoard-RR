// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.ColorUtils
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 *  Delete slider: holding the delete key for [ACTIVATION_DELAY_MS] turns the key into a slider that
 *  spans the keyboard width. The finger position controls what happens:
 *  - where the finger was when the slider appeared: delete [MIN_DELETE_SPEED] characters per second
 *  - further left: delete faster, up to [MAX_DELETE_SPEED] characters per second at the left edge
 *  - right of the starting point: put the deleted text back, at up to [MAX_RESTORE_SPEED] characters per second
 *  Lifting the finger ends the slider and keeps the text as it is at that moment.
 */
object DeleteSlider {
    const val ACTIVATION_DELAY_MS = 1000L
    private const val MIN_DELETE_SPEED = 3f
    private const val MAX_DELETE_SPEED = 50f
    private const val MIN_RESTORE_SPEED = 1f
    private const val MAX_RESTORE_SPEED = 5f
    private const val TICK_MS = 16L
    private const val EXPAND_ANIMATION_MS = 180f

    private val handler = Handler(Looper.getMainLooper())
    private var tracker: PointerTracker? = null // set while waiting for activation and while active
    private var listener: KeyboardActionListener? = null
    private var view: View? = null
    private var isActive = false

    // geometry in view coordinates
    private var keyLeft = 0f
    private var keyTop = 0f
    private var keyRight = 0f
    private var keyBottom = 0f
    private var anchorX = 0f // finger position when the slider appeared: slowest deletion
    private var restoreStartX = 0f // right of this the deleted text is put back
    private var fingerX = 0f

    private var activationTime = 0L
    private var lastTickTime = 0L
    private var pendingChars = 0f // fractional characters carried over to the next tick
    private var lastDirection = 0
    private var currentSpeed = 0f // > 0 deleting, < 0 restoring
    private var restorableCount = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val activateRunnable = Runnable { activate() }
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isActive) return
            tick()
            if (isActive) handler.postDelayed(this, TICK_MS)
        }
    }

    @JvmStatic
    fun isEnabled(): Boolean = Settings.getValues().mDeleteSlider

    @JvmStatic
    fun isActiveFor(pointerTracker: PointerTracker): Boolean = isActive && tracker === pointerTracker

    /** called when the delete key is pressed, starts waiting for the slider activation */
    @JvmStatic
    fun onDown(pointerTracker: PointerTracker, key: Key, x: Int, keyboardView: View?, actionListener: KeyboardActionListener) {
        cancel()
        if (keyboardView == null) return
        tracker = pointerTracker
        listener = actionListener
        view = keyboardView
        fingerX = x.toFloat()
        keyLeft = (key.x + keyboardView.paddingLeft).toFloat()
        keyTop = (key.y + keyboardView.paddingTop).toFloat()
        keyRight = keyLeft + key.width
        keyBottom = keyTop + key.height
        handler.postDelayed(activateRunnable, ACTIVATION_DELAY_MS)
    }

    /** @return whether the slider is active and has consumed the move */
    @JvmStatic
    fun onMove(pointerTracker: PointerTracker, x: Int): Boolean {
        if (tracker !== pointerTracker) return false
        fingerX = x.toFloat()
        if (isActive) view?.invalidate()
        return isActive
    }

    /** @return whether the slider was active */
    @JvmStatic
    fun onUp(pointerTracker: PointerTracker): Boolean {
        if (tracker !== pointerTracker) return false
        val wasActive = isActive
        cancel()
        return wasActive
    }

    /** stops waiting for activation, or ends the active slider (text stays as it currently is) */
    @JvmStatic
    fun cancel() {
        handler.removeCallbacks(activateRunnable)
        handler.removeCallbacks(tickRunnable)
        val wasActive = isActive
        val oldListener = listener
        val oldView = view
        isActive = false
        tracker = null
        listener = null
        view = null
        if (wasActive) {
            oldListener?.onDeleteSliderEnd()
            oldView?.invalidate()
        }
    }

    private fun activate() {
        val pointerTracker = tracker ?: return
        val keyboardView = view ?: return
        if (!keyboardView.isShown || !pointerTracker.onDeleteSliderActivated()) {
            cancel()
            return
        }
        val density = keyboardView.resources.displayMetrics.density
        val width = keyboardView.width.toFloat()
        anchorX = fingerX.coerceIn(width * 0.25f, width)
        // The delete key usually sits at the right edge, so there is little room right of the finger.
        // Restoring starts after a small dead zone that scales with the available room.
        val roomRight = max(width - anchorX, 0f)
        val deadZone = (roomRight * 0.4f).coerceIn(4f * density, 12f * density)
        restoreStartX = anchorX + deadZone
        isActive = true
        activationTime = SystemClock.uptimeMillis()
        lastTickTime = activationTime
        pendingChars = 1f // delete the first character right away as feedback
        lastDirection = 1
        currentSpeed = MIN_DELETE_SPEED
        restorableCount = listener?.onDeleteSliderStart() ?: 0
        keyboardView.invalidate()
        handler.post(tickRunnable)
    }

    /** characters per second for the current finger position, negative when restoring */
    private fun speedAt(x: Float): Float {
        val width = view?.width?.toFloat() ?: return 0f
        if (x >= restoreStartX) {
            val range = max(width - restoreStartX, 1f)
            val t = ((x - restoreStartX) / range).coerceIn(0f, 1f)
            return -(MIN_RESTORE_SPEED + (MAX_RESTORE_SPEED - MIN_RESTORE_SPEED) * t)
        }
        if (x >= anchorX) return MIN_DELETE_SPEED
        // exponential scale: fine control near the start, top speed only close to the left edge
        val leftEnd = width * 0.04f
        val t = ((anchorX - x) / max(anchorX - leftEnd, 1f)).coerceIn(0f, 1f)
        return MIN_DELETE_SPEED * (MAX_DELETE_SPEED / MIN_DELETE_SPEED).pow(t)
    }

    private fun tick() {
        val keyboardView = view
        val actionListener = listener
        if (keyboardView == null || actionListener == null || !keyboardView.isShown) {
            cancel()
            return
        }
        val now = SystemClock.uptimeMillis()
        val dt = min(now - lastTickTime, 100L) / 1000f
        lastTickTime = now
        currentSpeed = speedAt(fingerX)
        val direction = if (currentSpeed < 0) -1 else 1
        if (direction != lastDirection) {
            pendingChars = 0f
            lastDirection = direction
        }
        pendingChars += kotlin.math.abs(currentSpeed) * dt
        val count = pendingChars.toInt()
        if (count > 0) {
            pendingChars -= count
            restorableCount = actionListener.onDeleteSliderStep(direction * count)
        }
        keyboardView.invalidate()
    }

    /** draws the slider over the keyboard, to be called at the end of the keyboard view's onDraw */
    @JvmStatic
    fun draw(canvas: Canvas, keyboardView: View) {
        if (!isActive || keyboardView !== view) return
        val colors = Settings.getValues().mColors
        val density = keyboardView.resources.displayMetrics.density
        val width = keyboardView.width.toFloat()
        val background = ColorUtils.setAlphaComponent(colors.get(ColorType.MAIN_BACKGROUND), 255)
        val textColor = colors.get(ColorType.KEY_TEXT)
        val accent = colors.get(ColorType.GESTURE_TRAIL)

        // the key grows to the left until it spans the keyboard
        val progress = ((SystemClock.uptimeMillis() - activationTime) / EXPAND_ANIMATION_MS).coerceIn(0f, 1f)
        val margin = 4f * density
        val left = keyLeft + (margin - keyLeft) * progress
        val right = max(keyRight, width - margin)
        val top = keyTop + 2f * density
        val bottom = keyBottom - 2f * density
        val radius = (bottom - top) / 2f
        rect.set(left, top, right, bottom)

        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.blendARGB(background, textColor, 0.12f)
        canvas.drawRoundRect(rect, radius, radius, paint)

        // restore zone
        val restoring = currentSpeed < 0
        if (restoreStartX < right) {
            canvas.save()
            canvas.clipRect(restoreStartX, top, right, bottom)
            paint.color = ColorUtils.blendARGB(background, textColor, if (restoring) 0.4f else 0.25f)
            canvas.drawRoundRect(rect, radius, radius, paint)
            canvas.restore()
        }

        // filled part between the starting point and the finger shows the deletion speed
        val thumbX = fingerX.coerceIn(left + radius, right - radius)
        if (!restoring && thumbX < anchorX) {
            canvas.save()
            canvas.clipRect(thumbX, top, min(anchorX, right), bottom)
            paint.color = ColorUtils.setAlphaComponent(accent, 110)
            canvas.drawRoundRect(rect, radius, radius, paint)
            canvas.restore()
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = ColorUtils.setAlphaComponent(textColor, 90)
        canvas.drawRoundRect(rect, radius, radius, paint)
        // marker for the starting point
        val anchorMarkX = anchorX.coerceIn(left + radius, right - radius)
        canvas.drawLine(anchorMarkX, top + radius * 0.5f, anchorMarkX, bottom - radius * 0.5f, paint)

        // thumb
        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.setAlphaComponent(accent, 255)
        canvas.drawCircle(thumbX, (top + bottom) / 2f, radius * 0.8f, paint)

        // label: speed, and how much can still be restored
        if (progress >= 1f) {
            val speed = kotlin.math.abs(currentSpeed).roundToInt()
            val label = if (restoring) "↶ $speed/s  ($restorableCount)" else "⌫ $speed/s"
            paint.color = textColor
            paint.textSize = (bottom - top) * 0.36f
            paint.textAlign = Paint.Align.CENTER
            val textWidth = paint.measureText(label)
            // keep the label away from the finger
            val center = (left + right) / 2f
            val textX = if (kotlin.math.abs(thumbX - center) < textWidth / 2f + radius * 1.5f) {
                if (thumbX > center) left + radius + textWidth / 2f else right - radius - textWidth / 2f
            } else center
            val textY = (top + bottom) / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(label, textX, textY, paint)
        } else {
            keyboardView.invalidate() // continue the expand animation
        }
    }
}
