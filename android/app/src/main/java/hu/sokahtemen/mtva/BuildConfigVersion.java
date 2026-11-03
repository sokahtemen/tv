package hu.sokahtemen.mtva;

import android.content.Context;

/** Installed version name, so the app screen shows which build is running. */
final class BuildConfigVersion {
    private BuildConfigVersion() {
    }

    static String name(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
