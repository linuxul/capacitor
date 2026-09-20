// Copyright 2012 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.getcapacitor

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

internal class AndroidProtocolHandler(private val context: Context) {
    fun openAsset(path: String): InputStream = context.assets.open(path, AssetManager.ACCESS_STREAMING)

    fun openFile(filePath: String): InputStream {
        val realPath = filePath.replace(Bridge.CAPACITOR_FILE_START, "")
        val localFile = File(realPath)
        return FileInputStream(localFile)
    }

    fun openContentUrl(uri: Uri): InputStream? {
        val port = uri.port
        var baseUrl = uri.scheme + "://" + uri.host
        if (port != -1) {
            baseUrl += ":$port"
        }
        val realPath = uri.toString().replace(baseUrl + Bridge.CAPACITOR_CONTENT_START, "content:/")

        var stream: InputStream? = null
        try {
            stream = context.contentResolver.openInputStream(Uri.parse(realPath))
        } catch (e: SecurityException) {
            Logger.error("Unable to open content URL: $uri", e)
        }
        return stream
    }
}
