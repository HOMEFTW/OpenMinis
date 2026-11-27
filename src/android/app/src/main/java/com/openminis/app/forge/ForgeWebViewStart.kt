package com.openminis.app.forge

import android.view.View

/** A Compose AndroidView has no viewport in its factory; wait for attach and layout. */
internal class ForgeWebViewStart(
    private val view: View,
    private val onStart: () -> Unit,
) : View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
    private var started = false
    private var disposed = false

    init {
        view.addOnAttachStateChangeListener(this)
        view.addOnLayoutChangeListener(this)
        startIfReady()
    }

    private fun startIfReady() {
        if (disposed || started || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return
        started = true
        removeListeners()
        onStart()
    }

    override fun onViewAttachedToWindow(v: View) = startIfReady()
    override fun onViewDetachedFromWindow(v: View) = Unit
    override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int,
                                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) = startIfReady()

    fun dispose() {
        disposed = true
        removeListeners()
    }

    private fun removeListeners() {
        view.removeOnAttachStateChangeListener(this)
        view.removeOnLayoutChangeListener(this)
    }
}
