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
    public fun hasPermissions(context: Context, permissions: Array<String>): Boolean =
        permissions.all { ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

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
    public fun hasDefinedPermissions(context: Context, permissions: Array<String>): Boolean =
        getUndefinedPermissions(context, permissions).isEmpty()

    /**
     * Get the permissions defined in AndroidManifest.xml
     *
     * @since 3.0.0
     * @return The permissions defined in AndroidManifest.xml
     */
    public fun getManifestPermissions(context: Context): Array<String>? = try {
        InternalUtils
            .getPackageInfo(context.packageManager, context.packageName, PackageManager.GET_PERMISSIONS.toLong())
            ?.requestedPermissions
    } catch (ex: Exception) {
        null
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
        if (requestedPermissions.isNullOrEmpty()) {
            return neededPermissions
        }
        return neededPermissions.filterNot { it in requestedPermissions }.toTypedArray()
    }
}
