package com.getcapacitor

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

open class CapacitorWebView(context: Context, attrs: AttributeSet?) : WebView(context, attrs) {
    private var capInputConnection: BaseInputConnection? = null
    private var bridge: Bridge? = null

    fun setBridge(bridge: Bridge?) {
        this.bridge = bridge
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection? {
        val config = bridge?.config ?: CapConfig.loadDefault(context)

        val captureInput = config.isInputCaptured()
        if (captureInput) {
            val connection = capInputConnection ?: BaseInputConnection(this, false).also { capInputConnection = it }
            return connection
        }
        return super.onCreateInputConnection(outAttrs)
    }

    @Suppress("DEPRECATION")
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && event.action == KeyEvent.ACTION_MULTIPLE) {
            evaluateJavascript("document.activeElement.value = document.activeElement.value + '" + event.characters + "';", null)
            return false
        }
        return super.dispatchKeyEvent(event)
    }
}
