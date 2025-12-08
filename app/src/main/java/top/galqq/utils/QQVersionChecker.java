package top.galqq.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.util.HashMap;
import java.util.Map;

public class QQVersionChecker {

    private static String sVersion = null;
    private static int sVersionCode = 0;

    public static void init(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            sVersion = pi.versionName;
            sVersionCode = pi.versionCode;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String getVersion() {
        return sVersion;
    }

    public static int getVersionCode() {
        return sVersionCode;
    }

    // Example mapping table, to be populated with real data if needed
    // For now, we rely mostly on DexKit, but this structure is here as requested.
    private static final Map<String, String[]> ITEM_BUILDER_FACTORY_MAP = new HashMap<>();

    static {
        // Version -> {ClassName, MethodName}
        // ITEM_BUILDER_FACTORY_MAP.put("8.9.63", new String[]{"com.tencent.mobileqq.activity.aio.item.ItemBuilderFactory", "a"});
    }

    public static String[] getItemBuilderFactoryTarget(String version) {
        return ITEM_BUILDER_FACTORY_MAP.get(version);
    }
}
