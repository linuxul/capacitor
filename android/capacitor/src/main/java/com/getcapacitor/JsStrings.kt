package com.getcapacitor

import org.json.JSONObject

/**
 * Turns native strings into JavaScript source for the WebView.
 */
internal object JsStrings {
    /**
     * [value] as a JavaScript string literal, or `null` for a null reference.
     *
     * Quotes, backslashes, line breaks and other control characters are escaped, so whatever the value holds it
     * stays one string and cannot end the literal and run as code. A JSON string is a JavaScript string literal,
     * except that JSON leaves U+2028 and U+2029 unescaped while engines before ES2019 end a line at them.
     */
    fun literal(value: String?): String {
        if (value == null) return "null"

        return JSONObject.quote(value).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    }
}
