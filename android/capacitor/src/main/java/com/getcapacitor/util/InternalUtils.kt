package com.getcapacitor.util

import android.content.pm.PackageInfo
import android.content.pm.PackageManager

public object InternalUtils {
    public fun getPackageInfo(pm: PackageManager, packageName: String, flags: Long = 0): PackageInfo? =
        pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
}
