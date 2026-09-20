package com.getcapacitor

/**
 * An interface used in the processing of routes
 */
public fun interface RouteProcessor {
    public fun process(basePath: String?, path: String?): ProcessedRoute?
}
