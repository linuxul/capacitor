package com.getcapacitor.plugin.util

import java.io.IOException
import java.io.InputStream

/**
 * This interface was extracted from [CapacitorHttpUrlConnection] to enable mocking that class.
 */
public interface ICapacitorHttpUrlConnection {
    public fun getErrorStream(): InputStream?

    public fun getHeaderField(name: String?): String?

    public fun getInputStream(): InputStream
}
