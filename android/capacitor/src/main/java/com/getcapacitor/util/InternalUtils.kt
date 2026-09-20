package com.getcapacitor.util

import android.content.pm.PackageInfo
import android.content.pm.PackageManager

object InternalUtils {
    @JvmStatic
    @Throws(PackageManager.NameNotFoundException::class)
    fun getPackageInfo(pm: PackageManager, packageName: String): PackageInfo? = getPackageInfo(pm, packageName, 0)

    @JvmStatic
    @Throws(PackageManager.NameNotFoundException::class)
    fun getPackageInfo(pm: PackageManager, packageName: String, flags: Long): PackageInfo? =
        pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
}
