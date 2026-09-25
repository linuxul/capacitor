package com.getcapacitor

import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.getcapacitor.annotation.Permission
import com.getcapacitor.util.PermissionHelper

/**
 * Whether a permission declared on a plugin names no Android permission string (`strings = []` or `[""]`). There is
 * nothing to ask the user for then: its alias is reported as granted and never requested.
 */
internal val Permission.isAutoGranted: Boolean
    get() = strings.isEmpty() || (strings.size == 1 && strings[0].isEmpty())

/**
 * What Android says about the app's permissions.
 */
internal interface PermissionChecker {
    fun isGranted(permission: String): Boolean

    /** Whether Android would show a rationale before asking for [permission] again. */
    fun shouldShowRationale(permission: String): Boolean

    /** The ones of [permissions] that AndroidManifest.xml does not declare. */
    fun undeclared(permissions: Array<String>): List<String>
}

internal class ActivityPermissionChecker(private val activity: Activity) : PermissionChecker {
    override fun isGranted(permission: String): Boolean =
        ActivityCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    override fun shouldShowRationale(permission: String): Boolean =
        ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)

    override fun undeclared(permissions: Array<String>): List<String> =
        PermissionHelper.getUndefinedPermissions(activity, permissions).toList()
}

/**
 * The permission states the plugins report, and the denials they are derived from.
 *
 * Android only says whether a permission is granted. After the user denies a prompt, the result is stored here
 * (in [preferences]) as `denied`, or as `prompt-with-rationale` when Android would show a rationale first, so that
 * later reads tell a permission that was never asked for from one that was refused.
 */
internal class PermissionStateStore(private val checker: PermissionChecker, preferences: () -> SharedPreferences) {
    private val prefs by lazy(preferences)

    /**
     * Stores the outcome of a permission prompt: a denial is remembered, a grant clears an earlier denial.
     */
    fun recordResults(results: Map<String, Boolean>) {
        for ((permission, isGranted) in results) {
            if (isGranted) {
                if (prefs.getString(permission, null) != null) {
                    prefs.edit().remove(permission).apply()
                }
            } else {
                val state = if (checker.shouldShowRationale(permission)) PermissionState.PROMPT_WITH_RATIONALE else PermissionState.DENIED
                prefs.edit().putString(permission, state.toString()).apply()
            }
        }
    }

    /**
     * The error for the permissions in [permissions] that AndroidManifest.xml does not declare, or null if it
     * declares them all.
     */
    fun missingPermissionsMessage(permissions: Array<String>): String? {
        val undeclared = checker.undeclared(permissions)
        if (undeclared.isEmpty()) return null

        return buildString {
            appendLine("Missing the following permissions in AndroidManifest.xml:")
            undeclared.forEach { appendLine(it) }
        }
    }

    /**
     * The state of each of [permissions], by alias (or by permission string, for a permission without alias).
     *
     * An alias that names several permission strings is granted only if all of them are. An auto-granted alias
     * ([isAutoGranted]) is granted, unless another declaration of the same alias came first.
     */
    fun states(permissions: Array<out Permission>): Map<String, PermissionState> {
        val results = HashMap<String, PermissionState>()
        for (perm in permissions) {
            if (perm.isAutoGranted) {
                if (perm.alias.isNotEmpty()) {
                    results.putIfAbsent(perm.alias, PermissionState.GRANTED)
                }
                continue
            }

            for (permission in perm.strings) {
                val key = perm.alias.ifEmpty { permission }
                val state = stateOf(permission)

                // multiple permissions with the same alias must all be true, otherwise all false.
                val existing = results[key]
                if (existing == null || existing == PermissionState.GRANTED) {
                    results[key] = state
                }
            }
        }

        return results
    }

    private fun stateOf(permission: String): PermissionState {
        if (checker.isGranted(permission)) return PermissionState.GRANTED

        // A denial stored by recordResults tells "never asked" (prompt) from "refused" (denied or with rationale).
        return prefs.getString(permission, null)?.let { PermissionState.byState(it) } ?: PermissionState.PROMPT
    }
}
