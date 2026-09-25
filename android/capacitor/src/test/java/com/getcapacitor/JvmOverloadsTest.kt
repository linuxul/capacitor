package com.getcapacitor

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.getcapacitor.util.InternalUtils
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Java plugins and apps cannot use Kotlin default arguments, so the short forms that BREAKING.md promises must
 * exist as JVM overloads (reached through `INSTANCE` for Kotlin objects). getMethod throws if one is missing.
 */
class JvmOverloadsTest {
    private fun returnTypeOf(owner: Class<*>, name: String, vararg parameterTypes: Class<*>): Class<*> =
        owner.getMethod(name, *parameterTypes).returnType

    @Test
    fun loggerErrorWithoutAThrowable() {
        assertEquals(Void.TYPE, returnTypeOf(Logger::class.java, "error", String::class.java))
    }

    @Test
    fun pluginConfigGettersWithoutADefault() {
        assertEquals(String::class.java, returnTypeOf(PluginConfig::class.java, "getString", String::class.java))
        assertEquals(Array<String>::class.java, returnTypeOf(PluginConfig::class.java, "getArray", String::class.java))
    }

    @Test
    fun getPackageInfoWithoutFlags() {
        assertEquals(
            PackageInfo::class.java,
            returnTypeOf(InternalUtils::class.java, "getPackageInfo", PackageManager::class.java, String::class.java)
        )
    }
}
