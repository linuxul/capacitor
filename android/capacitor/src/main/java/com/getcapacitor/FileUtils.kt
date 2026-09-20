/*
 * Portions adopted from react-native-image-crop-picker
 *
 * MIT License

 * Copyright (c) 2017 Ivan Pusic

 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.getcapacitor

import android.content.ContentUris
import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.regex.Pattern

/**
 * Common File utilities, such as resolve content URIs and
 * creating portable web paths from low-level files
 */
public object FileUtils {
    public enum class Type(private val type: String) {
        IMAGE("image")
    }

    // Pattern.split keeps java.lang.String.split semantics (trailing empty parts dropped).
    private val COLON: Pattern = Pattern.compile(":")
    private val SLASH: Pattern = Pattern.compile("/")

    public fun getPortablePath(c: Context, host: String?, u: Uri): String {
        // Same as the Java original: throws if the uri cannot be resolved to a path.
        var path = getFileUrlForUri(c, u)!!
        if (path.startsWith("file://")) {
            path = path.replace("file://", "")
        }
        return host + Bridge.CAPACITOR_FILE_START + path
    }

    public fun getFileUrlForUri(context: Context, uri: Uri): String? {
        // DocumentProvider
        if (DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = COLON.split(docId)
                val type = split[0]

                if ("primary".equals(type, ignoreCase = true)) {
                    return legacyPrimaryPath(split[1])
                } else {
                    val splitIndex = docId.indexOf(':', 1)
                    val tag = docId.substring(0, splitIndex)
                    val path = docId.substring(splitIndex + 1)

                    val nonPrimaryVolume = getPathToNonPrimaryVolume(context, tag)
                    if (nonPrimaryVolume != null) {
                        val result = "$nonPrimaryVolume/$path"
                        val file = File(result)
                        if (file.exists() && file.canRead()) {
                            return result
                        }
                        return null
                    }
                }
            } else if (isDownloadsDocument(uri)) {
                // DownloadsProvider
                val id = DocumentsContract.getDocumentId(uri)
                val contentUri = ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), id.toLong())

                return getDataColumn(context, contentUri, null, null)
            } else if (isMediaDocument(uri)) {
                // MediaProvider
                val docId = DocumentsContract.getDocumentId(uri)
                val split = COLON.split(docId)
                val type = split[0]

                var contentUri: Uri? = null
                if ("image" == type) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                } else if ("video" == type) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else if ("audio" == type) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }

                val selection = "_id=?"
                val selectionArgs = arrayOf(split[1])

                // Same as the Java original: an unknown media type throws here.
                return getDataColumn(context, contentUri!!, selection, selectionArgs)
            }
        } else if ("content".equals(uri.scheme, ignoreCase = true)) {
            // MediaStore (and general)
            // Return the remote address
            if (isGooglePhotosUri(uri)) return uri.lastPathSegment
            return getDataColumn(context, uri, null, null)
        } else if ("file".equals(uri.scheme, ignoreCase = true)) {
            // File
            return uri.path
        }

        return null
    }

    @Suppress("DEPRECATION")
    private fun legacyPrimaryPath(pathPart: String): String = Environment.getExternalStorageDirectory().toString() + "/" + pathPart

    /**
     * Read a plaintext file from the assets directory.
     *
     * @param assetManager Used to open the file.
     * @param fileName The path of the file to read.
     * @return The contents of the file path.
     * @throws IOException Thrown if any issues reading the provided file path.
     */
    internal fun readFileFromAssets(assetManager: AssetManager, fileName: String): String =
        BufferedReader(InputStreamReader(assetManager.open(fileName))).use { reader -> readLines(reader) }

    /**
     * Read a plaintext file from within the app disk space.
     *
     * @param file The file to read.
     * @return The contents of the file path.
     * @throws IOException Thrown if any issues reading the provided file path.
     */
    internal fun readFileFromDisk(file: File): String = BufferedReader(FileReader(file)).use { reader -> readLines(reader) }

    private fun readLines(reader: BufferedReader): String {
        val buffer = StringBuilder()
        var line = reader.readLine()
        while (line != null) {
            buffer.append(line).append("\n")
            line = reader.readLine()
        }
        return buffer.toString()
    }

    /**
     * Get the value of the data column for this Uri. This is useful for
     * MediaStore Uris, and other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri The Uri to query.
     * @param selection (Optional) Filter used in the query.
     * @param selectionArgs (Optional) Selection arguments used in the query.
     * @return The value of the _data column, which is typically a file path.
     */
    private fun getDataColumn(context: Context, uri: Uri, selection: String?, selectionArgs: Array<String>?): String? {
        var path: String? = null
        val column = "_data"
        val projection = arrayOf(column)

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndexOrThrow(column)
                    path = cursor.getString(index)
                }
            }
        } catch (ex: IllegalArgumentException) {
            return getCopyFilePath(uri, context)
        }
        return path ?: getCopyFilePath(uri, context)
    }

    private fun getCopyFilePath(uri: Uri, context: Context): String? {
        // Same as the Java original: a null cursor throws here.
        val cursor = context.contentResolver.query(uri, null, null, null, null)!!
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        cursor.moveToFirst()
        val name: String = cursor.getString(nameIndex)
        val fileName = sanitizeFilename(name)
        val file = File(context.filesDir, fileName)
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val outputStream = FileOutputStream(file)
            // The Java original hit a NullPointerException here, caught below as "return null".
            if (inputStream == null) return null
            val maxBufferSize = 1024 * 1024
            val bufferSize = minOf(inputStream.available(), maxBufferSize)
            val buffers = ByteArray(bufferSize)
            var read = inputStream.read(buffers)
            while (read != -1) {
                outputStream.write(buffers, 0, read)
                read = inputStream.read(buffers)
            }
            inputStream.close()
            outputStream.close()
        } catch (e: Exception) {
            return null
        } finally {
            cursor.close()
        }
        return file.path
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    private fun isExternalStorageDocument(uri: Uri): Boolean = "com.android.externalstorage.documents" == uri.authority

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    private fun isDownloadsDocument(uri: Uri): Boolean = "com.android.providers.downloads.documents" == uri.authority

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    private fun isMediaDocument(uri: Uri): Boolean = "com.android.providers.media.documents" == uri.authority

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is Google Photos.
     */
    private fun isGooglePhotosUri(uri: Uri): Boolean = "com.google.android.apps.photos.content" == uri.authority

    private fun getPathToNonPrimaryVolume(context: Context, tag: String): String? {
        val volumes: Array<File?>? = context.externalCacheDirs
        if (volumes != null) {
            for (volume in volumes) {
                if (volume != null) {
                    val path: String? = volume.absolutePath
                    if (path != null) {
                        val index = path.indexOf(tag)
                        if (index != -1) {
                            return path.substring(0, index) + tag
                        }
                    }
                }
            }
        }
        return null
    }

    private fun sanitizeFilename(displayName: String): String {
        val badCharacters = arrayOf("..", "/")
        val segments = SLASH.split(displayName)
        var fileName = segments[segments.size - 1]
        for (suspString in badCharacters) {
            fileName = fileName.replace(suspString, "_")
        }
        return fileName
    }
}
