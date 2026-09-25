package com.getcapacitor

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * What the library's AndroidManifest.xml merges into every app.
 */
class LibraryManifestTest {
    private fun queriedActions(): List<String> {
        // Unit tests run in the module directory.
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val queries = manifest.getElementsByTagName("queries")
        val actions = ArrayList<String>()
        for (i in 0 until queries.length) {
            val intentActions = (queries.item(i) as Element).getElementsByTagName("action")
            for (j in 0 until intentActions.length) {
                actions.add((intentActions.item(j) as Element).getAttribute("android:name"))
            }
        }
        return actions
    }

    @Test
    fun theCameraIntentsOfTheFileChooserAreQueried() {
        // Without these, resolveActivity finds no camera app on Android 11 and later, and <input capture> falls back to
        // the file picker.
        val actions = queriedActions()

        assertTrue(actions.toString(), "android.media.action.IMAGE_CAPTURE" in actions)
        assertTrue(actions.toString(), "android.media.action.VIDEO_CAPTURE" in actions)
    }
}
