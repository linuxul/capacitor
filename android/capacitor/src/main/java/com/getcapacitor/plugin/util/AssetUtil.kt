package com.getcapacitor.plugin.util

import android.annotation.SuppressLint
import android.content.Context

/**
 * Looks up app resources by name, for plugins that take resource names from their options or configuration.
 */
public object AssetUtil {
    public const val RESOURCE_ID_ZERO_VALUE: Int = 0

    /**
     * The id of the resource named [resourceName] in the resource type [dir] ("drawable", "raw", ...) of the app,
     * or [RESOURCE_ID_ZERO_VALUE] when there is none.
     */
    @SuppressLint("DiscouragedApi")
    public fun getResourceID(context: Context, resourceName: String?, dir: String?): Int =
        context.resources.getIdentifier(resourceName, dir, context.packageName)

    /**
     * The resource name in [resPath]: the part after the last "/" if there is one, otherwise the name without its
     * extension.
     */
    public fun getResourceBaseName(resPath: String?): String? {
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
