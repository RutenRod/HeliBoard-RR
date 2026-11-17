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
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 *  Delete slider: holding the delete key for the time set in the settings turns the key into a slider that
 *  spans the keys of the keyboard. The finger position controls what happens:
 *  - where the finger was when the slider appeared, and right of it: delete [START_SPEED] characters per second
 *  - moving left, the speed slowly rises to [THRESHOLD_SPEED] at the threshold, [THRESHOLD_DISTANCE] of the way to the left end
 *  - left of the threshold: delete faster, up to [MAX_DELETE_SPEED] characters per second at the left end
 *  - once the threshold was reached, the part right of it changes: the speed goes down evenly from
 *    deleting [THRESHOLD_SPEED] at the threshold to restoring [MAX_RESTORE_SPEED] characters per second at the
 *    right end, with a pause zone where deleting turns into restoring. Text can't be restored before the
 *    threshold was reached.
 *  Lifting the finger ends the slider and keeps the text as it is at that moment.
 */
object DeleteSlider {
    private const val START_SPEED = 2f
    private const val THRESHOLD_SPEED = 3f
    private const val MAX_DELETE_SPEED = 50f
    private const val MAX_RESTORE_SPEED = 4f
    /** part of the way from the starting point to the left end where [THRESHOLD_SPEED] is reached */
    private const val THRESHOLD_DISTANCE = 0.18f
    /** how much slower than evenly the speed rises left of the threshold, 1 would be an even rise */
    private const val SPEED_CURVE_EXPONENT = 1.8f
    /** part of the way from the threshold to the right end where nothing happens, so there is a place to rest between deleting and restoring */
    private const val PAUSE_ZONE_WIDTH = 0.233f
    /** where on the way from the threshold to the right end deleting turns into restoring */
    private const val TURN_POSITION = THRESHOLD_SPEED / (THRESHOLD_SPEED + MAX_RESTORE_SPEED)
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
    private var sliderLeft = 0f // the slider spans the keys of the keyboard
    private var sliderRight = 0f
    private var anchorX = 0f // finger position when the slider appeared: slowest deletion
    private var thresholdX = 0f // THRESHOLD_SPEED is reached here
    private var leftEndX = 0f // fastest deletion
    private var rightEndX = 0f // fastest restoring
    private var thresholdReached = false
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
        // the keys may not use the whole width of the view when the keyboard width is reduced
        val keys = (keyboardView as? KeyboardView)?.keyboard?.sortedKeys
        sliderLeft = (keys?.minOfOrNull { it.x } ?: 0) + keyboardView.paddingLeft.toFloat()
        sliderRight = (keys?.maxOfOrNull { it.x + it.width } ?: keyboardView.width) + keyboardView.paddingLeft.toFloat()
        handler.postDelayed(activateRunnable, Settings.getValues().mDeleteSliderDelay.toLong())
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
        val width = max(sliderRight - sliderLeft, 1f)
        val endInset = 14f * density // the finger can't comfortably reach the very ends
        leftEndX = sliderLeft + endInset
        rightEndX = sliderRight - endInset
        anchorX = fingerX.coerceIn(sliderLeft + width * 0.25f, sliderRight)
        thresholdX = anchorX - (anchorX - leftEndX) * THRESHOLD_DISTANCE
        thresholdReached = false
        isActive = true
        activationTime = SystemClock.uptimeMillis()
        lastTickTime = activationTime
        pendingChars = 1f // delete the first character right away as feedback
        lastDirection = 1
        currentSpeed = START_SPEED
        restorableCount = listener?.onDeleteSliderStart() ?: 0
        keyboardView.invalidate()
        handler.post(tickRunnable)
    }

    /** characters per second for the current finger position, negative when restoring */
    private fun speedAt(x: Float): Float {
        if (x <= thresholdX) {
            thresholdReached = true
            // rises slower at first, so there is fine control at low speeds
            val t = ((thresholdX - x) / max(thresholdX - leftEndX, 1f)).coerceIn(0f, 1f)
            return THRESHOLD_SPEED + (MAX_DELETE_SPEED - THRESHOLD_SPEED) * t.pow(SPEED_CURVE_EXPONENT)
        }
        if (!thresholdReached) {
            if (x >= anchorX) return START_SPEED
            val t = (anchorX - x) / max(anchorX - thresholdX, 1f)
            return START_SPEED + (THRESHOLD_SPEED - START_SPEED) * t
        }
        // evenly from deleting at the threshold to restoring at the right end
        val t = ((x - thresholdX) / max(rightEndX - thresholdX, 1f)).coerceIn(0f, 1f)
        if (kotlin.math.abs(t - TURN_POSITION) < PAUSE_ZONE_WIDTH / 2f) return 0f
        return THRESHOLD_SPEED - (THRESHOLD_SPEED + MAX_RESTORE_SPEED) * t
    }

    /** x position of the given part of the way from the threshold to the right end */
    private fun afterThresholdX(t: Float) = thresholdX + (rightEndX - thresholdX) * t

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
        val direction = if (currentSpeed < 0) -1 else if (currentSpeed > 0) 1 else 0
        if (direction != lastDirection) {
            pendingChars = 0f
            lastDirection = direction
        }
        pendingChars += kotlin.math.abs(currentSpeed) * dt
        val count = pendingChars.toInt()
        if (count > 0 && direction != 0) {
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
        val background = ColorUtils.setAlphaComponent(colors.get(ColorType.MAIN_BACKGROUND), 255)
        val textColor = colors.get(ColorType.KEY_TEXT)
        val accent = colors.get(ColorType.GESTURE_TRAIL)

        // the key grows to the left until it spans the keys of the keyboard
        val progress = ((SystemClock.uptimeMillis() - activationTime) / EXPAND_ANIMATION_MS).coerceIn(0f, 1f)
        val margin = 4f * density
        val left = keyLeft + (sliderLeft + margin - keyLeft) * progress
        val right = max(keyRight, sliderRight - margin)
        val top = keyTop + 2f * density
        val bottom = keyBottom - 2f * density
        val radius = (bottom - top) / 2f
        rect.set(left, top, right, bottom)

        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.blendARGB(background, textColor, 0.12f)
        canvas.drawRoundRect(rect, radius, radius, paint)

        val restoring = currentSpeed < 0
        val thumbX = fingerX.coerceIn(left + radius, right - radius)
        // pause zone and restore zone, only exist once the threshold was reached
        val turnX = afterThresholdX(TURN_POSITION)
        val pauseStartX = afterThresholdX(TURN_POSITION - PAUSE_ZONE_WIDTH / 2f)
        val restoreStartX = afterThresholdX(TURN_POSITION + PAUSE_ZONE_WIDTH / 2f)
        if (thresholdReached && pauseStartX < right) {
            canvas.save()
            canvas.clipRect(pauseStartX, top, restoreStartX, bottom)
            paint.color = ColorUtils.blendARGB(background, textColor, if (currentSpeed == 0f) 0.3f else 0.18f)
            canvas.drawRoundRect(rect, radius, radius, paint)
            canvas.restore()
            canvas.save()
            canvas.clipRect(restoreStartX, top, right, bottom)
            paint.color = ColorUtils.blendARGB(background, textColor, if (restoring) 0.4f else 0.25f)
            canvas.drawRoundRect(rect, radius, radius, paint)
            canvas.restore()
        }

        // filled part shows how fast text is deleted
        val fillRight = if (thresholdReached) pauseStartX else anchorX
        if (!restoring && thumbX < fillRight) {
            canvas.save()
            canvas.clipRect(thumbX, top, min(fillRight, right), bottom)
            paint.color = ColorUtils.setAlphaComponent(accent, 110)
            canvas.drawRoundRect(rect, radius, radius, paint)
            canvas.restore()
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = ColorUtils.setAlphaComponent(textColor, 90)
        canvas.drawRoundRect(rect, radius, radius, paint)
        // markers: the threshold, and the starting point or the turning point
        if (progress >= 1f) {
            val markerX = (if (thresholdReached) turnX else anchorX).coerceIn(left + radius, right - radius)
            canvas.drawLine(markerX, top + radius * 0.5f, markerX, bottom - radius * 0.5f, paint)
            if (thresholdX > left + radius)
                canvas.drawLine(thresholdX, top + radius * 0.75f, thresholdX, bottom - radius * 0.75f, paint)
        }

        // thumb
        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.setAlphaComponent(accent, 255)
        canvas.drawCircle(thumbX, (top + bottom) / 2f, radius * 0.8f, paint)

        // label: speed, and how much can still be restored
        if (progress >= 1f) {
            val absoluteSpeed = kotlin.math.abs(currentSpeed)
            val speed = if (absoluteSpeed < 9.95f) String.format(Locale.getDefault(), "%.1f", absoluteSpeed)
                else absoluteSpeed.roundToInt().toString()
            val label = when {
                restoring -> "\u21B6 $speed/s  ($restorableCount)"
                currentSpeed == 0f -> "\u2016  ($restorableCount)"
                else -> "\u232B $speed/s"
            }
            paint.color = textColor
            paint.textSize = (bottom - top) * 0.36f
            // always at the left end of the slider
            paint.textAlign = Paint.Align.LEFT
            val textX = left + radius
            val textY = (top + bottom) / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(label, textX, textY, paint)
        } else {
            keyboardView.invalidate() // continue the expand animation
        }
    }
}
