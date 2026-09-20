package com.getcapacitor

/**
 * An data class used in conjunction with RouteProcessor.
 *
 * @see com.getcapacitor.RouteProcessor
 */
class ProcessedRoute {
    var path: String? = null

    // Java accessors stay isAsset()/setAsset(boolean).
    var isAsset: Boolean = false

    // Java accessors stay isIgnoreAssetPath()/setIgnoreAssetPath(boolean).
    @get:JvmName("isIgnoreAssetPath")
    var ignoreAssetPath: Boolean = false
}
