package com.msp1974.vacompanion.utils

import android.view.MotionEvent
import timber.log.Timber
import kotlin.math.abs

class WebViewGestureDetector {
    private var startX = 0f
    private var startY = 0f
    private var pivotX = 0f
    private var pivotY = 0f
    private var maxPointers = 0
    private var firstLegDirection: Direction? = null
    private var isLGestureDetected = false
    private var isSwipeDetected = false

    private val SWIPE_THRESHOLD = 150f
    private val L_LEG_THRESHOLD = 100f
    private val BOTTOM_EDGE_THRESHOLD_PERCENT = 0.15f

    enum class Direction {
        LEFT, RIGHT, UP, DOWN, BOTTOM_UP, LEFT_UP, LEFT_DOWN, RIGHT_UP, RIGHT_DOWN, UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT
    }

    data class GestureEvent(
        val direction: Direction,
        val pointers: Int,
        val startX: Float,
        val startY: Float
    )


    interface OnGestureListener {
        fun onSwipe(event: GestureEvent)
        fun onLGesture(firstDir: Direction, secondDir: Direction)
    }

    private var listener: OnGestureListener? = null

    fun setOnGestureListener(listener: OnGestureListener) {
        this.listener = listener
    }

    private fun handleLGesture(firstDir: Direction, secondDir: Direction) {
        val finalDirection = when (firstDir) {
            Direction.LEFT -> if (secondDir == Direction.UP) Direction.LEFT_UP else Direction.LEFT_DOWN
            Direction.RIGHT -> if (secondDir == Direction.UP) Direction.RIGHT_UP else Direction.RIGHT_DOWN
            Direction.UP -> if (secondDir == Direction.LEFT) Direction.UP_LEFT else Direction.UP_RIGHT
            Direction.DOWN -> if (secondDir == Direction.LEFT) Direction.DOWN_LEFT else Direction.DOWN_RIGHT
            else -> null
        }

        finalDirection?.let {
            Timber.d("L-Shaped Gesture mapped to: $it")
            listener?.onSwipe(GestureEvent(it, 1, startX, startY))
        }
    }

    fun onTouchEvent(event: MotionEvent, viewHeight: Int): Boolean {
        val action = event.actionMasked
        val pointerCount = event.pointerCount
        var consumed = false

        if (pointerCount > maxPointers) {
            maxPointers = pointerCount
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                maxPointers = 1
                firstLegDirection = null
                isLGestureDetected = false
                isSwipeDetected = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (maxPointers == 1 && !isLGestureDetected) {
                    val dx = event.x - startX
                    val dy = event.y - startY

                    if (firstLegDirection == null) {
                        if (abs(dx) > L_LEG_THRESHOLD && abs(dx) > abs(dy) * 2) {
                            firstLegDirection = if (dx > 0) Direction.RIGHT else Direction.LEFT
                            pivotX = event.x
                            pivotY = event.y
                        } else if (abs(dy) > L_LEG_THRESHOLD && abs(dy) > abs(dx) * 2) {
                            firstLegDirection = if (dy > 0) Direction.DOWN else Direction.UP
                            pivotX = event.x
                            pivotY = event.y
                        }
                    } else {
                        val dpx = event.x - pivotX
                        val dpy = event.y - pivotY
                        
                        when (firstLegDirection) {
                            Direction.LEFT, Direction.RIGHT -> {
                                if (abs(dpy) > L_LEG_THRESHOLD && abs(dpy) > abs(dpx) * 2) {
                                    isLGestureDetected = true
                                    val secondDir = if (dpy > 0) Direction.DOWN else Direction.UP
                                    Timber.d("L-Shaped Gesture detected: $firstLegDirection then $secondDir")
                                    handleLGesture(firstLegDirection!!, secondDir)
                                    listener?.onLGesture(firstLegDirection!!, secondDir)
                                }
                            }
                            Direction.UP, Direction.DOWN -> {
                                if (abs(dpx) > L_LEG_THRESHOLD && abs(dpx) > abs(dpy) * 2) {
                                    isLGestureDetected = true
                                    val secondDir = if (dpx > 0) Direction.RIGHT else Direction.LEFT
                                    Timber.d("L-Shaped Gesture detected: $firstLegDirection then $secondDir")
                                    handleLGesture(firstLegDirection!!, secondDir)
                                    listener?.onLGesture(firstLegDirection!!, secondDir)
                                }
                            }
                            null -> {}
                            else -> {}
                        }
                    }
                }

                if (maxPointers == 2 && !isSwipeDetected && !isLGestureDetected) {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (abs(dy) > SWIPE_THRESHOLD && abs(dy) > abs(dx)) {
                        if (dy < 0 && startY > (viewHeight - (viewHeight * BOTTOM_EDGE_THRESHOLD_PERCENT))) {
                            isSwipeDetected = true
                            Timber.d("2-finger swipe up from bottom detected early")
                            listener?.onSwipe(GestureEvent(Direction.BOTTOM_UP, 2, startX, startY))
                            consumed = true
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isLGestureDetected && !isSwipeDetected) {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (maxPointers in 1..3) {
                        detectSwipe(dx, dy, maxPointers, startX, startY, viewHeight)
                    }
                }
                maxPointers = 0
            }
        }
        return isLGestureDetected || isSwipeDetected || consumed
    }

    private fun detectSwipe(dx: Float, dy: Float, pointers: Int, startX: Float, startY: Float, viewHeight: Int) {
        if (abs(dx) > SWIPE_THRESHOLD || abs(dy) > SWIPE_THRESHOLD) {
            var direction = if (abs(dx) > abs(dy)) {
                if (dx > 0) Direction.RIGHT else Direction.LEFT
            } else {
                if (dy > 0) Direction.DOWN else Direction.UP
            }
            
            val prefix = if (pointers == 1) "Single finger" else "$pointers fingers"
            Timber.d("$prefix swipe $direction")

            if (pointers == 2 && direction == Direction.UP && startY > (viewHeight - (viewHeight * BOTTOM_EDGE_THRESHOLD_PERCENT))) {
                direction = Direction.BOTTOM_UP
                Timber.d("2-finger swipe up from bottom detected")
            }
            
            listener?.onSwipe(GestureEvent(direction, pointers, startX, startY))
        }
    }
}
