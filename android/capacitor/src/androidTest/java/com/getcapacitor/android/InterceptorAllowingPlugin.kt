package com.getcapacitor.android

import android.net.Uri
import com.getcapacitor.Bridge
import com.getcapacitor.Plugin
import com.getcapacitor.annotation.CapacitorPlugin

/** A plugin that tries to allow the proxy path. The navigation guard must ignore it. */
@CapacitorPlugin(name = "InterceptorAllowingPlugin")
class InterceptorAllowingPlugin : Plugin() {
    override fun shouldOverrideLoad(url: Uri?): Boolean? {
        val path = url?.path
        if (path != null && path.startsWith(Bridge.CAPACITOR_HTTP_INTERCEPTOR_START)) {
            return false // "allow this navigation"
        }
        return null
    }
}
