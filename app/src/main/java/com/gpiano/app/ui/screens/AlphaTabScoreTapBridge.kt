package com.gpiano.app.ui.screens

import android.view.MotionEvent
import kotlin.math.abs

/** Observes activity touch dispatch without consuming events needed by alphaTab scrolling. */
internal object AlphaTabScoreTapBridge {
    private var owner: Any? = null
    private var touchSlop = 0
    private var onTap: ((Float, Float) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var tapCandidate = false

    fun register(owner: Any, touchSlop: Int, onTap: (Float, Float) -> Unit) {
        this.owner = owner
        this.touchSlop = touchSlop.coerceAtLeast(1)
        this.onTap = onTap
    }

    fun unregister(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        onTap = null
        tapCandidate = false
    }

    fun observe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                tapCandidate = onTap != null
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop) {
                    tapCandidate = false
                }
            }
            MotionEvent.ACTION_UP -> {
                if (tapCandidate) onTap?.invoke(event.rawX, event.rawY)
                tapCandidate = false
            }
            MotionEvent.ACTION_CANCEL -> tapCandidate = false
        }
    }
}
