package com.getcapacitor

/**
 * An data class used in conjunction with RouteProcessor.
 *
 * @see com.getcapacitor.RouteProcessor
 */
public class ProcessedRoute {
    public var path: String? = null

    public var isAsset: Boolean = false

    public var ignoreAssetPath: Boolean = false
}
