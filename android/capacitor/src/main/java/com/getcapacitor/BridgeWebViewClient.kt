package com.getcapacitor

import android.graphics.Bitmap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

// The framework does not annotate these callbacks, so every reference parameter is taken as nullable.
public open class BridgeWebViewClient(private val bridge: Bridge) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (request == null) return null
        return bridge.localServer.shouldInterceptRequest(request)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url ?: return false
        return bridge.launchIntent(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (view?.progress == 100) {
            for (listener in bridge.webViewListeners) {
                listener.onPageLoaded(view)
            }
        }
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        super.onReceivedError(view, request, error)

        for (listener in bridge.webViewListeners) {
            listener.onReceivedError(view)
        }

        val errorPath = bridge.errorUrl
        if (errorPath != null && request?.isForMainFrame == true) {
            view?.loadUrl(errorPath)
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        bridge.reset()
        for (listener in bridge.webViewListeners) {
            listener.onPageStarted(view)
        }
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
        super.onReceivedHttpError(view, request, errorResponse)

        for (listener in bridge.webViewListeners) {
            listener.onReceivedHttpError(view)
        }

        val errorPath = bridge.errorUrl
        if (errorPath != null && request?.isForMainFrame == true) {
            view?.loadUrl(errorPath)
        }
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        super.onRenderProcessGone(view, detail)
        var result = false

        for (listener in bridge.webViewListeners) {
            result = listener.onRenderProcessGone(view, detail) || result
        }

        return result
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)

        for (listener in bridge.webViewListeners) {
            listener.onPageCommitVisible(view, url)
        }
    }
}
