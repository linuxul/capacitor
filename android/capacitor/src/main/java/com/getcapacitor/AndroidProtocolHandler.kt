// Copyright 2012 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.getcapacitor

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.util.TypedValue
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.regex.Pattern

class AndroidProtocolHandler(private var context: Context) {
    @Throws(IOException::class)
    fun openAsset(path: String): InputStream = context.assets.open(path, AssetManager.ACCESS_STREAMING)

    fun openResource(uri: Uri): InputStream? {
        assert(uri.path != null)
        // The path must be of the form ".../asset_type/asset_name.ext".
        val pathSegments = uri.pathSegments
        val assetType = pathSegments[pathSegments.size - 2]
        var assetName = pathSegments[pathSegments.size - 1]

        // Drop the file extension.
        // Pattern.split keeps java.lang.String.split semantics.
        assetName = Pattern.compile("\\.").split(assetName)[0]
        try {
            // Use the application context for resolving the resource package name so that we do
            // not use the browser's own resources. Note that if 'context' here belongs to the
            // test suite, it does not have a separate application context. In that case we use
            // the original context object directly.
            context.applicationContext?.let { context = it }
            val fieldId = getFieldId(context, assetType, assetName)
            val valueType = getValueType(context, fieldId)
            if (valueType == TypedValue.TYPE_STRING) {
                return context.resources.openRawResource(fieldId)
            } else {
                Logger.error("Asset not of type string: $uri")
            }
        } catch (e: ClassNotFoundException) {
            Logger.error("Unable to open resource URL: $uri", e)
        } catch (e: IllegalAccessException) {
            Logger.error("Unable to open resource URL: $uri", e)
        } catch (e: NoSuchFieldException) {
            Logger.error("Unable to open resource URL: $uri", e)
        }
        return null
    }

    @Throws(IOException::class)
    fun openFile(filePath: String): InputStream {
        val realPath = filePath.replace(Bridge.CAPACITOR_FILE_START, "")
        val localFile = File(realPath)
        return FileInputStream(localFile)
    }

    @Throws(IOException::class)
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

    private companion object {
        @Throws(ClassNotFoundException::class, NoSuchFieldException::class, IllegalAccessException::class)
        fun getFieldId(context: Context, assetType: String, assetName: String): Int {
            val d = context.classLoader.loadClass(context.packageName + ".R$" + assetType)
            val field = d.getField(assetName)
            return field.getInt(null)
        }

        fun getValueType(context: Context, fieldId: Int): Int {
            val value = TypedValue()
            context.resources.getValue(fieldId, value, true)
            return value.type
        }
    }
}
