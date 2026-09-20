package com.getcapacitor.util

import java.util.Locale
import java.util.regex.Pattern

interface HostMask {
    fun matches(host: String?): Boolean

    object Parser {
        private val NOTHING: HostMask = Nothing()

        @JvmStatic
        fun parse(masks: Array<String>?): HostMask = if (masks == null) NOTHING else Any.parse(*masks)

        @JvmStatic
        fun parse(mask: String?): HostMask = if (mask == null) NOTHING else Simple.parse(mask)
    }

    class Simple private constructor(private val maskParts: List<String>) : HostMask {
        override fun matches(host: String?): Boolean {
            if (host == null) {
                return false
            }
            val hostParts = Util.splitAndReverse(host)
            val hostSize = hostParts.size
            val maskSize = maskParts.size
            if (maskSize > 1 && hostSize != maskSize) {
                return false
            }

            val minSize = minOf(hostSize, maskSize)

            for (i in 0 until minSize) {
                val maskPart = maskParts[i]
                val hostPart = hostParts[i]
                if (!Util.matches(maskPart, hostPart)) {
                    return false
                }
            }
            return true
        }

        companion object {
            @JvmStatic
            fun parse(mask: String?): Simple {
                val parts = Util.splitAndReverse(mask)
                return Simple(parts)
            }
        }
    }

    class Any(private val masks: List<HostMask>) : HostMask {
        override fun matches(host: String?): Boolean {
            for (mask in masks) {
                if (mask.matches(host)) {
                    return true
                }
            }
            return false
        }

        companion object {
            @JvmStatic
            fun parse(vararg rawMasks: String?): Any {
                val masks = ArrayList<Simple>()
                for (raw in rawMasks) {
                    masks.add(Simple.parse(raw))
                }
                return Any(masks)
            }
        }
    }

    class Nothing : HostMask {
        override fun matches(host: String?): Boolean = false
    }

    object Util {
        private val DOT: Pattern = Pattern.compile("\\.")

        @JvmStatic
        fun matches(mask: String?, string: String?): Boolean =
            if (mask == null) {
                false
            } else if ("*" == mask) {
                true
            } else if (string == null) {
                false
            } else {
                // Locale.getDefault() is what the no-argument toUpperCase() used implicitly.
                mask.uppercase(Locale.getDefault()) == string.uppercase(Locale.getDefault())
            }

        @JvmStatic
        fun splitAndReverse(string: String?): List<String> {
            requireNotNull(string) { "Can not split null argument" }
            // Pattern.split keeps java.lang.String.split semantics (trailing empty parts dropped).
            return DOT.split(string).reversed()
        }
    }
}
