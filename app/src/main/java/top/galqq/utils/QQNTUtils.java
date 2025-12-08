package top.galqq.utils;

import de.robv.android.xposed.XposedBridge;

/**
 * Utility class for detecting and handling QQNT architecture
 * Implementation exactly matches QAuxiliary's QAppUtils.isQQnt()
 */
public class QQNTUtils {
    
    private static final String TAG = "GalQQ.QQNTUtils";
    private static Boolean sIsQQNT = null;
    
    /**
     * 调试日志输出（受配置开关控制）
     */
    private static void debugLog(String message) {
        try {
            if (top.galqq.config.ConfigManager.isVerboseLogEnabled()) {
                XposedBridge.log(message);
            }
        } catch (Throwable ignored) {
            // ConfigManager 未初始化时忽略
        }
    }
    
    /**
     * Check if current QQ uses QQNT architecture
     * QQNT is the new QQ architecture starting from version 9.x
     * This implementation exactly matches QAuxiliary's Initiator.load() and QAppUtils.isQQnt()
     */
    public static boolean isQQNT(ClassLoader classLoader) {
        if (sIsQQNT != null) {
            return sIsQQNT;
        }
        
        try {
            // Exactly match QAuxiliary's implementation:
            // return Initiator.load("com.tencent.qqnt.base.BaseActivity") != null;
            Class<?> baseActivity = classLoader.loadClass("com.tencent.qqnt.base.BaseActivity");
            sIsQQNT = (baseActivity != null);
            debugLog(TAG + ": Detected QQNT architecture");
            return sIsQQNT;
        } catch (ClassNotFoundException e) {
            sIsQQNT = false;
            debugLog(TAG + ": Detected legacy QQ architecture");
            return false;
        }
    }
}
