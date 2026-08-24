package com.gpiano.app.ui.screens

import java.util.concurrent.atomic.AtomicBoolean

internal class OperationGate {
    private val entered = AtomicBoolean(false)

    fun tryEnter(): Boolean = entered.compareAndSet(false, true)

    fun leave() {
        check(entered.compareAndSet(true, false)) { "operation gate was not entered" }
    }

    val isEntered: Boolean get() = entered.get()
}
