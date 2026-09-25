package com.getcapacitor

import android.content.SharedPreferences
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission states plugins report: what Android grants, plus the denials remembered from earlier prompts.
 */
class PermissionStateStoreTest {
    /** Grants what is in [granted], shows a rationale for what is in [rationale], and declares all but [undeclared]. */
    class FakeChecker : PermissionChecker {
        val granted = HashSet<String>()
        val rationale = HashSet<String>()
        val undeclared = HashSet<String>()

        override fun isGranted(permission: String): Boolean = permission in granted

        override fun shouldShowRationale(permission: String): Boolean = permission in rationale

        override fun undeclared(permissions: Array<String>): List<String> = permissions.filter { it in undeclared }
    }

    /** Only the string values the store uses. */
    class FakePreferences : SharedPreferences {
        val values = HashMap<String, String>()

        override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue

        override fun contains(key: String?): Boolean = key in values

        override fun getAll(): MutableMap<String, *> = HashMap(values)

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues

        override fun getInt(key: String?, defValue: Int): Int = defValue

        override fun getLong(key: String?, defValue: Long): Long = defValue

        override fun getFloat(key: String?, defValue: Float): Float = defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = Editor()

        inner class Editor : SharedPreferences.Editor {
            private val changes = ArrayList<() -> Unit>()

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                changes.add { if (value == null) values.remove(key) else values[key] = value }
            }

            override fun remove(key: String): SharedPreferences.Editor = apply { changes.add { values.remove(key) } }

            override fun clear(): SharedPreferences.Editor = apply { changes.add { values.clear() } }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                changes.forEach { it() }
                changes.clear()
            }
        }
    }

    @CapacitorPlugin(
        permissions = [
            Permission(alias = "camera", strings = [CAMERA]),
            Permission(alias = "location", strings = [COARSE, FINE]),
            Permission(alias = "notifications", strings = []),
            Permission(alias = "legacy", strings = [""]),
            Permission(strings = [CONTACTS]),
            Permission(strings = [])
        ]
    )
    class Declarations

    private val checker = FakeChecker()
    private val prefs = FakePreferences()
    private var prefsOpened = 0
    private val store =
        PermissionStateStore(checker) {
            prefsOpened++
            prefs
        }
    private val declared = Declarations::class.java.getAnnotation(CapacitorPlugin::class.java)!!.permissions

    @Test
    fun neverAskedPermissionsArePrompt() {
        val states = store.states(declared)

        assertEquals(PermissionState.PROMPT, states["camera"])
        assertEquals(PermissionState.PROMPT, states["location"])
        // Without alias, a permission is reported under its permission string.
        assertEquals(PermissionState.PROMPT, states[CONTACTS])
    }

    @Test
    fun aliasesWithoutPermissionStringsAreGranted() {
        val states = store.states(declared)

        assertEquals(PermissionState.GRANTED, states["notifications"])
        assertEquals(PermissionState.GRANTED, states["legacy"])
        // Nothing to report for an auto-granted permission without alias.
        assertEquals(setOf("camera", "location", "notifications", "legacy", CONTACTS), states.keys)
    }

    @Test
    fun anAliasIsGrantedOnlyWhenAllItsPermissionsAre() {
        checker.granted.add(COARSE)
        assertEquals(PermissionState.PROMPT, store.states(declared)["location"])

        checker.granted.add(FINE)
        assertEquals(PermissionState.GRANTED, store.states(declared)["location"])
    }

    @Test
    fun aDenialIsRememberedWithOrWithoutRationale() {
        checker.rationale.add(CAMERA)

        store.recordResults(mapOf(CAMERA to false, CONTACTS to false))

        val states = store.states(declared)
        assertEquals(PermissionState.PROMPT_WITH_RATIONALE, states["camera"])
        assertEquals(PermissionState.DENIED, states[CONTACTS])
    }

    @Test
    fun aGrantClearsAnEarlierDenial() {
        store.recordResults(mapOf(CAMERA to false))
        checker.granted.add(CAMERA)

        store.recordResults(mapOf(CAMERA to true))

        assertFalse(prefs.contains(CAMERA))
        assertEquals(PermissionState.GRANTED, store.states(declared)["camera"])
    }

    @Test
    fun aGrantedPermissionIsGrantedWhateverWasStored() {
        store.recordResults(mapOf(CAMERA to false))
        checker.granted.add(CAMERA)

        assertEquals(PermissionState.GRANTED, store.states(declared)["camera"])
    }

    @Test
    fun anAutoGrantedDeclarationDoesNotOverrideAnEarlierOneOfTheSameAlias() {
        @CapacitorPlugin(permissions = [Permission(alias = "camera", strings = [CAMERA]), Permission(alias = "camera", strings = [])])
        class Twice

        val states = store.states(Twice::class.java.getAnnotation(CapacitorPlugin::class.java)!!.permissions)

        assertEquals(PermissionState.PROMPT, states["camera"])
    }

    @Test
    fun missingPermissionsAreListed() {
        assertNull(store.missingPermissionsMessage(arrayOf(CAMERA, FINE)))

        checker.undeclared.addAll(listOf(FINE, CONTACTS))

        assertEquals(
            "Missing the following permissions in AndroidManifest.xml:\n$FINE\n$CONTACTS\n",
            store.missingPermissionsMessage(arrayOf(CAMERA, FINE, CONTACTS))
        )
    }

    @Test
    fun preferencesAreOpenedOnFirstUseOnly() {
        assertEquals(0, prefsOpened)

        store.states(declared)
        store.recordResults(mapOf(CAMERA to false))

        assertEquals(1, prefsOpened)
    }

    @Test
    fun autoGrantedMeansNoPermissionString() {
        assertTrue(declared[2].isAutoGranted)
        assertTrue(declared[3].isAutoGranted)
        assertFalse(declared[0].isAutoGranted)
        assertFalse(declared[1].isAutoGranted)
    }

    private companion object {
        const val CAMERA = "android.permission.CAMERA"
        const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"
        const val FINE = "android.permission.ACCESS_FINE_LOCATION"
        const val CONTACTS = "android.permission.READ_CONTACTS"
    }
}
