package com.getcapacitor.util

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat

/**
 * A helper class for checking permissions.
 *
 * @since 3.0.0
 */
public object PermissionHelper {
    /**
     * Checks if a list of given permissions are all granted by the user
     *
     * @since 3.0.0
     * @param permissions Permissions to check.
     * @return True if all permissions are granted, false if at least one is not.
     */
    public fun hasPermissions(context: Context, permissions: Array<String>): Boolean {
        for (perm in permissions) {
            if (ActivityCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    /**
     * Check whether the given permission has been defined in the AndroidManifest.xml
     *
     * @since 3.0.0
     * @param permission A permission to check.
     * @return True if the permission has been defined in the Manifest, false if not.
     */
    public fun hasDefinedPermission(context: Context, permission: String?): Boolean {
        val requestedPermissions = getManifestPermissions(context)
        return !requestedPermissions.isNullOrEmpty() && requestedPermissions.contains(permission)
    }

    /**
     * Check whether all of the given permissions have been defined in the AndroidManifest.xml
     * @param context the app context
     * @param permissions a list of permissions
     * @return true only if all permissions are defined in the AndroidManifest.xml
     */
    public fun hasDefinedPermissions(context: Context, permissions: Array<String>): Boolean {
        for (permission in permissions) {
            if (!hasDefinedPermission(context, permission)) {
                return false
            }
        }

        return true
    }

    /**
     * Get the permissions defined in AndroidManifest.xml
     *
     * @since 3.0.0
     * @return The permissions defined in AndroidManifest.xml
     */
    public fun getManifestPermissions(context: Context): Array<String>? {
        var requestedPermissions: Array<String>? = null
        try {
            val pm = context.packageManager
            val packageInfo = InternalUtils.getPackageInfo(pm, context.packageName, PackageManager.GET_PERMISSIONS.toLong())

            if (packageInfo != null) {
                requestedPermissions = packageInfo.requestedPermissions
            }
        } catch (ex: Exception) {
        }
        return requestedPermissions
    }

    /**
     * Given a list of permissions, return a new list with the ones not present in AndroidManifest.xml
     *
     * @since 3.0.0
     * @param neededPermissions The permissions needed.
     * @return The permissions not present in AndroidManifest.xml
     */
    public fun getUndefinedPermissions(context: Context, neededPermissions: Array<String>): Array<String> {
        val requestedPermissions = getManifestPermissions(context)
        if (!requestedPermissions.isNullOrEmpty()) {
            val undefinedPermissions = ArrayList<String>()
            for (permission in neededPermissions) {
                if (!requestedPermissions.contains(permission)) {
                    undefinedPermissions.add(permission)
                }
            }
            return undefinedPermissions.toTypedArray()
        }
        return neededPermissions
    }
}
