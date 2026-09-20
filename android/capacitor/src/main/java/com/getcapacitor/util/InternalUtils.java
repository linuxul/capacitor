package com.getcapacitor.util;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

public class InternalUtils {

    public static PackageInfo getPackageInfo(PackageManager pm, String packageName) throws PackageManager.NameNotFoundException {
        return InternalUtils.getPackageInfo(pm, packageName, 0);
    }

    public static PackageInfo getPackageInfo(PackageManager pm, String packageName, long flags)
        throws PackageManager.NameNotFoundException {
        return pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags));
    }
}
