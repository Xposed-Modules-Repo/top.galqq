package top.galqq.utils;

import android.content.Context;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;

/**
 * QAppUtils工具类，提供第二种获取AppRuntime的方式
 * 通过BaseApplicationImpl.getApplication()获取实例后调用getRuntime()方法
 * 
 * @see io.github.qauxv.util.QAppUtils
 */
public class QAppUtils {
    
    private static final String TAG = "GalQQ.QAppUtils";
    
    /**
     * 获取AppRuntime实例 - 第二种方式
     * 通过BaseApplicationImpl.getApplication()获取应用实例后调用getRuntime()方法
     * 
     * @param context 上下文
     * @return AppRuntime实例
     */
    public static Object getAppRuntime(Context context) {
        try {
            // 获取BaseApplicationImpl实例
            Object application = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("com.tencent.mobileqq.startup.step.StepUnit", context.getClassLoader()),
                "getApplication"
            );
            
            if (application != null) {
                // 调用getRuntime()方法
                Method getRuntimeMethod = application.getClass().getDeclaredMethod("getRuntime");
                getRuntimeMethod.setAccessible(true);
                Object appRuntime = getRuntimeMethod.invoke(application);
                XposedBridge.log(TAG + ": Successfully got AppRuntime via BaseApplicationImpl.getRuntime()");
                return appRuntime;
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to get AppRuntime via BaseApplicationImpl: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 获取当前账号Uin
     * @param context 上下文
     * @return 账号Uin
     */
    public static String getCurrentUin(Context context) {
        try {
            Object appRuntime = getAppRuntime(context);
            if (appRuntime != null) {
                Method getCurrentAccountUin = appRuntime.getClass().getDeclaredMethod("getCurrentAccountUin");
                getCurrentAccountUin.setAccessible(true);
                Object uin = getCurrentAccountUin.invoke(appRuntime);
                if (uin != null) {
                    return uin.toString();
                }
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to get current account uin: " + e.getMessage());
        }
        return "";
    }
}