package top.galqq.utils;

import android.app.Application;
import androidx.annotation.NonNull;

public class HostInfo {

    private static Application sApp;
    private static String sPackageName;
    private static final String MODULE_PACKAGE_NAME = "top.galqq";

    public static void init(@NonNull Application app) {
        if (sApp != null) return;
        sApp = app;
        sPackageName = app.getPackageName();
    }

    public static boolean isInModuleProcess() {
        return MODULE_PACKAGE_NAME.equals(sPackageName);
    }

    public static boolean isInHostProcess() {
        return !isInModuleProcess();
    }

    public static Application getApplication() {
        return sApp;
    }

    public static String getPackageName() {
        return sPackageName;
    }
}
