package com.getcapacitor.plugin.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The resource name helpers local notifications use for their sound and icon options.
 */
class AssetUtilTest {
    @Test
    fun baseNameDropsTheExtension() {
        assertEquals("beep", AssetUtil.getResourceBaseName("beep.wav"))
        assertEquals("beep", AssetUtil.getResourceBaseName("beep"))
        assertNull(AssetUtil.getResourceBaseName(null))
    }

    @Test
    fun baseNameOfAPathIsItsLastSegmentAsItIs() {
        // Only the directories are dropped: the extension stays when there is a "/".
        assertEquals("beep.wav", AssetUtil.getResourceBaseName("public/assets/beep.wav"))
        assertEquals("ic_stat", AssetUtil.getResourceBaseName("res://ic_stat"))
    }
}
