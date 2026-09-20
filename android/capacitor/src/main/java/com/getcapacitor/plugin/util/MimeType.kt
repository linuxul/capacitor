package com.getcapacitor.plugin.util

internal enum class MimeType(val value: String) {
    APPLICATION_JSON("application/json"),
    APPLICATION_VND_API_JSON("application/vnd.api+json"), // https://jsonapi.org
    TEXT_HTML("text/html")
}
