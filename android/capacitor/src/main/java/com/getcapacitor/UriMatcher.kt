/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// package com.google.webviewlocalserver.third_party.android;
package com.getcapacitor

import android.net.Uri
import com.getcapacitor.util.HostMask
import java.util.regex.Pattern

internal class UriMatcher private constructor(private var mCode: Any?, private var mWhich: Int, private var mText: String?) {
    private val mChildren: ArrayList<UriMatcher> = ArrayList()

    /**
     * Creates the root node of the URI tree.
     *
     * @param code the code to match for the root URI
     */
    constructor(code: Any?) : this(code, -1, null)

    private constructor() : this(null, -1, null)

    /**
     * Add a URI to match, and the code to return when this URI is
     * matched. URI nodes may be exact match string, the token "*"
     * that matches any text, or the token "#" that matches only
     * numbers.
     *
     * Starting from API level [android.os.Build.VERSION_CODES.JELLY_BEAN_MR2],
     * this method will accept a leading slash in the path.
     *
     * @param authority the authority to match
     * @param path the path to match. * may be used as a wild card for
     * any text, and # may be used as a wild card for numbers.
     * @param code the code that is returned when a URI is matched
     * against the given components. Must be positive.
     */
    fun addURI(scheme: String, authority: String, path: String?, code: Any?) {
        requireNotNull(code) { "Code can't be null" }

        var tokens: Array<String>? = null
        if (path != null) {
            var newPath: String = path
            // Strip leading slash if present.
            if (path.isNotEmpty() && path[0] == '/') {
                newPath = path.substring(1)
            }
            tokens = PATH_SPLIT_PATTERN.split(newPath)
        }

        val numTokens = tokens?.size ?: 0
        var node = this
        for (i in -2 until numTokens) {
            val token: String =
                when (i) {
                    -2 -> scheme
                    -1 -> authority
                    else -> tokens!![i]
                }
            val existing = node.mChildren.firstOrNull { token == it.mText }
            if (existing != null) {
                node = existing
            } else {
                // Child not found, create it
                val child = UriMatcher()
                if (i == -1 && token.contains("*")) {
                    child.mWhich = MASK
                } else if (token == "**") {
                    child.mWhich = REST
                } else if (token == "*") {
                    child.mWhich = TEXT
                } else {
                    child.mWhich = EXACT
                }
                child.mText = token
                node.mChildren.add(child)
                node = child
            }
        }
        node.mCode = code
    }

    /**
     * Try to match against the path in a url.
     *
     * @param uri The url whose path we will match against.
     * @return The code for the matched node (added using addURI),
     * or null if there is no matched node.
     */
    fun match(uri: Uri): Any? {
        val pathSegments = uri.pathSegments
        val li = pathSegments.size

        var node: UriMatcher = this

        if (li == 0 && uri.authority == null) {
            return mCode
        }

        for (i in -2 until li) {
            val u: String? =
                when (i) {
                    -2 -> uri.scheme
                    -1 -> uri.authority
                    else -> pathSegments[i]
                }
            var next: UriMatcher? = null
            for (n in node.mChildren) {
                when (n.mWhich) {
                    MASK ->
                        if (HostMask.Parser.parse(n.mText).matches(u)) {
                            next = n
                        }

                    EXACT ->
                        if (n.mText == u) {
                            next = n
                        }

                    TEXT -> next = n

                    REST -> return n.mCode
                }
                if (next != null) {
                    break
                }
            }
            node = next ?: return null
        }

        return node.mCode
    }

    private companion object {
        val PATH_SPLIT_PATTERN: Pattern = Pattern.compile("/")

        const val EXACT = 0
        const val TEXT = 1
        const val REST = 2
        const val MASK = 3
    }
}
