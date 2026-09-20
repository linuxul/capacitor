package com.getcapacitor.plugin.util

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.StrictMode
import androidx.core.content.FileProvider
import com.getcapacitor.Logger
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.util.UUID

/**
 * Manager for assets.
 *
 * @param context Application context, used to access the resources and app directory.
 */
class AssetUtil private constructor(private val context: Context) {
    /**
     * The URI for a path.
     *
     * @param path The given path.
     */
    fun parse(path: String?): Uri {
        if (path == null || path.isEmpty()) {
            return Uri.EMPTY
        } else if (path.startsWith("res:")) {
            return getUriForResourcePath(path)
        } else if (path.startsWith("file:///")) {
            return getUriFromPath(path)
        } else if (path.startsWith("file://")) {
            return getUriFromAsset(path)
        } else if (path.startsWith("http")) {
            return getUriFromRemote(path)
        } else if (path.startsWith("content://")) {
            return Uri.parse(path)
        }

        return Uri.EMPTY
    }

    /**
     * URI for a file.
     *
     * @param path Absolute path like file:///...
     *
     * @return URI pointing to the given path.
     */
    private fun getUriFromPath(path: String): Uri {
        val absPath = path.replaceFirst(FILE_SCHEME, "").replaceFirst(QUERY_STRING, "")
        val file = File(absPath)

        if (!file.exists()) {
            Logger.error("File not found: " + file.absolutePath)
            return Uri.EMPTY
        }

        return getUriFromFile(file)
    }

    /**
     * URI for an asset.
     *
     * @param path Asset path like file://...
     *
     * @return URI pointing to the given path.
     */
    private fun getUriFromAsset(path: String): Uri {
        val resPath = path.replaceFirst(FILE_PREFIX, "www").replaceFirst(QUERY_STRING, "")
        val fileName = resPath.substring(resPath.lastIndexOf('/') + 1)
        val file = getTmpFile(fileName) ?: return Uri.EMPTY

        try {
            val assets = context.assets
            val input = assets.open(resPath)
            val out = FileOutputStream(file)
            copyFile(input, out)
        } catch (e: Exception) {
            Logger.error("File not found: assets/$resPath")
            return Uri.EMPTY
        }

        return getUriFromFile(file)
    }

    /**
     * The URI for a resource.
     *
     * @param path The given relative path.
     *
     * @return URI pointing to the given path.
     */
    private fun getUriForResourcePath(path: String): Uri {
        val res = context.resources
        val resPath = path.replaceFirst(RES_SCHEME, "")
        val resId = getResId(resPath)

        if (resId == 0) {
            Logger.error("File not found: $resPath")
            return Uri.EMPTY
        }

        return Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(res.getResourcePackageName(resId))
            .appendPath(res.getResourceTypeName(resId))
            .appendPath(res.getResourceEntryName(resId))
            .build()
    }

    /**
     * Uri from remote located content.
     *
     * @param path Remote address.
     *
     * @return Uri of the downloaded file.
     */
    private fun getUriFromRemote(path: String): Uri {
        val file = getTmpFile() ?: return Uri.EMPTY

        try {
            val url = URL(path)
            val connection = url.openConnection() as HttpURLConnection

            val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()

            StrictMode.setThreadPolicy(policy)

            connection.setRequestProperty("Connection", "close")
            connection.connectTimeout = 5000
            connection.connect()

            val input = connection.inputStream
            val out = FileOutputStream(file)

            copyFile(input, out)
            return getUriFromFile(file)
        } catch (e: MalformedURLException) {
            Logger.error(Logger.tags("Asset"), "Incorrect URL", e)
        } catch (e: FileNotFoundException) {
            Logger.error(Logger.tags("Asset"), "Failed to create new File from HTTP Content", e)
        } catch (e: IOException) {
            Logger.error(Logger.tags("Asset"), "No Input can be created from http Stream", e)
        }

        return Uri.EMPTY
    }

    /**
     * Copy content from input stream into output stream.
     *
     * @param input The input stream.
     * @param out The output stream.
     */
    private fun copyFile(input: InputStream, out: FileOutputStream) {
        val buffer = ByteArray(1024)

        try {
            var read = input.read(buffer)
            while (read != -1) {
                out.write(buffer, 0, read)
                read = input.read(buffer)
            }
            out.flush()
            out.close()
        } catch (e: Exception) {
            Logger.error("Error copying", e)
        }
    }

    /**
     * Resource ID for drawable.
     *
     * @param resPath Resource path as string.
     *
     * @return The resource ID or 0 if not found.
     */
    fun getResId(resPath: String): Int {
        var resId = getResId(context.resources, resPath)

        if (resId == 0) {
            resId = getResId(Resources.getSystem(), resPath)
        }

        return resId
    }

    /**
     * Get resource ID.
     *
     * @param res The resources where to look for.
     * @param resPath The name of the resource.
     *
     * @return The resource ID or 0 if not found.
     */
    @SuppressLint("DiscouragedApi")
    private fun getResId(res: Resources, resPath: String): Int {
        val pkgName = getPkgName(res)
        val resName = getBaseName(resPath)

        var resId = res.getIdentifier(resName, "mipmap", pkgName)

        if (resId == 0) {
            resId = res.getIdentifier(resName, "drawable", pkgName)
        }

        if (resId == 0) {
            resId = res.getIdentifier(resName, "raw", pkgName)
        }

        return resId
    }

    /**
     * Convert URI to Bitmap.
     *
     * @param uri Internal image URI
     */
    @Throws(IOException::class)
    fun getIconFromUri(uri: Uri): Bitmap? {
        val input = context.contentResolver.openInputStream(uri)
        return BitmapFactory.decodeStream(input)
    }

    /**
     * Extract name of drawable resource from path.
     *
     * @param resPath Resource path as string.
     */
    private fun getBaseName(resPath: String): String {
        var drawable = resPath

        if (drawable.contains("/")) {
            drawable = drawable.substring(drawable.lastIndexOf('/') + 1)
        }

        if (resPath.contains(".")) {
            drawable = drawable.substring(0, drawable.lastIndexOf('.'))
        }

        return drawable
    }

    /**
     * Returns a file located under the external cache dir of that app.
     *
     * @param name The name of the file. Defaults to a random UUID.
     *
     * @return File with the provided name.
     */
    private fun getTmpFile(name: String = UUID.randomUUID().toString()): File? {
        val dir = context.externalCacheDir ?: context.cacheDir

        if (dir == null) {
            Logger.error(Logger.tags("Asset"), "Missing cache dir", null)
            return null
        }

        val storage = dir.toString() + STORAGE_FOLDER

        File(storage).mkdir()

        return File(storage, name)
    }

    /**
     * Get content URI for the specified file.
     *
     * @param file The file to get the URI.
     *
     * @return content://...
     */
    private fun getUriFromFile(file: File): Uri =
        try {
            val authority = context.packageName + ".provider"
            FileProvider.getUriForFile(context, authority, file)
        } catch (e: IllegalArgumentException) {
            Logger.error("File not supported by provider", e)
            Uri.EMPTY
        }

    /**
     * Package name specified by the resource bundle.
     */
    private fun getPkgName(res: Resources): String = if (res === Resources.getSystem()) "android" else context.packageName

    companion object {
        const val RESOURCE_ID_ZERO_VALUE = 0

        // Name of the storage folder
        private const val STORAGE_FOLDER = "/capacitorassets"

        // java.lang.String.replaceFirst takes a regex; Kotlin's String overload would be a literal match.
        private val FILE_SCHEME = Regex("file://")
        private val FILE_PREFIX = Regex("file:/")
        private val RES_SCHEME = Regex("res://")
        private val QUERY_STRING = Regex("\\?.*$")

        /**
         * Static method to retrieve class instance.
         *
         * @param context Application context.
         */
        @JvmStatic
        fun getInstance(context: Context): AssetUtil = AssetUtil(context)

        @JvmStatic
        @SuppressLint("DiscouragedApi")
        fun getResourceID(context: Context, resourceName: String?, dir: String?): Int =
            context.resources.getIdentifier(resourceName, dir, context.packageName)

        @JvmStatic
        fun getResourceBaseName(resPath: String?): String? {
            if (resPath == null) return null

            if (resPath.contains("/")) {
                return resPath.substring(resPath.lastIndexOf('/') + 1)
            }

            if (resPath.contains(".")) {
                return resPath.substring(0, resPath.lastIndexOf('.'))
            }

            return resPath
        }
    }
}
