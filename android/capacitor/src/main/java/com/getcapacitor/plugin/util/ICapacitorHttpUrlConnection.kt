package com.getcapacitor.plugin.util

import java.io.IOException
import java.io.InputStream

/**
 * This interface was extracted from [CapacitorHttpUrlConnection] to enable mocking that class.
 */
interface ICapacitorHttpUrlConnection {
    fun getErrorStream(): InputStream?

    fun getHeaderField(name: String?): String?

    @Throws(IOException::class)
    fun getInputStream(): InputStream
}
