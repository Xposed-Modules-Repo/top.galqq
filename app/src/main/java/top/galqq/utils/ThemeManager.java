package top.galqq.utils;

import android.content.Context;
import android.content.res.Configuration;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedHelpers;

/**
 * 主题管理器 - 检测QQ的夜间模式设置并应用到GalQQ界面
 * 模仿QAuxiliary的ResUtils.isInNightMode()实现
 */
public class ThemeManager {
    private static final String TAG = "GalQQ_ThemeManager";
    
    // QQ夜间模式主题ID（来自QAuxiliary）
    private static final String THEME_ID_NIGHT_1 = "1103";
    private static final String THEME_ID_NIGHT_2 = "2920";
    private static final String THEME_ID_NIGHT_3 = "2963";
    
    /**
     * 检测QQ是否处于夜间模式
     * 模仿QAuxiliary的ResUtils.isInNightMode()实现
     * @param context 上下文
     * @return true表示夜间模式，false表示日间模式
     */
    public static boolean isQQNightMode(Context context) {
        try {
            // 方法1：通过QQ的ThemeUtil获取当前主题ID（QAuxiliary的方式）
            String themeId = getQQCurrentThemeId(context);
            if (themeId != null) {
                boolean isNight = themeId.endsWith(THEME_ID_NIGHT_1) 
                    || themeId.endsWith(THEME_ID_NIGHT_2) 
                    || themeId.endsWith(THEME_ID_NIGHT_3);
                Log.d(TAG, "QQ theme ID: " + themeId + ", isNight: " + isNight);
                return isNight;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get QQ theme via ThemeUtil: " + e.getMessage());
        }
        
        // 降级：使用系统夜间模式
        return isSystemNightMode(context);
    }
    
    /**
     * 获取QQ当前主题ID
     * 模仿QAuxiliary的方式调用ThemeUtil.getUserCurrentThemeId()
     */
    private static String getQQCurrentThemeId(Context context) {
        try {
            // 获取AppRuntime实例
            Object appRuntime = AppRuntimeHelper.getAppRuntime(context);
            if (appRuntime == null) {
                Log.w(TAG, "AppRuntime is null");
                return null;
            }
            
            // 加载ThemeUtil类（尝试多个可能的类名）
            Class<?> themeUtilClass = Initiator.load("com.tencent.mobileqq.theme.ThemeUtil");
            if (themeUtilClass == null) {
                themeUtilClass = Initiator.load("com.tencent.mobileqq.vas.theme.api.ThemeUtil");
            }
            if (themeUtilClass == null) {
                Log.w(TAG, "ThemeUtil class not found");
                return null;
            }
            
            // 加载AppRuntime类
            Class<?> appRuntimeClass = Initiator.load("mqq.app.AppRuntime");
            if (appRuntimeClass == null) {
                Log.w(TAG, "AppRuntime class not found");
                return null;
            }
            
            // 调用ThemeUtil.getUserCurrentThemeId(appRuntime)
            try {
                Method method = themeUtilClass.getMethod("getUserCurrentThemeId", appRuntimeClass);
                Object result = method.invoke(null, appRuntime);
                if (result instanceof String) {
                    return (String) result;
                }
            } catch (NoSuchMethodException e) {
                // 尝试其他方法签名
                Log.w(TAG, "getUserCurrentThemeId method not found, trying alternatives");
                
                // 尝试无参数版本
                try {
                    Method method = themeUtilClass.getMethod("getUserCurrentThemeId");
                    Object result = method.invoke(null);
                    if (result instanceof String) {
                        return (String) result;
                    }
                } catch (Exception e2) {
                    Log.w(TAG, "Alternative method also failed: " + e2.getMessage());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get QQ theme ID: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 检测系统是否处于夜间模式
     */
    public static boolean isSystemNightMode(Context context) {
        int uiMode = context.getResources().getConfiguration().uiMode;
        return (uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
    
    /**
     * 应用主题到Activity
     * @param context Activity上下文
     */
    public static void applyTheme(Context context) {
        try {
            boolean isNightMode = isQQNightMode(context);
            Log.d(TAG, "Applying theme, night mode: " + isNightMode);
            
            // 设置AppCompatDelegate的夜间模式
            if (isNightMode) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error applying theme: " + e.getMessage());
        }
    }
    
    /**
     * 获取当前应该使用的主题资源ID
     * @param context 上下文
     * @return 主题资源ID
     */
    public static int getThemeResId(Context context) {
        // 始终返回DayNight主题，让系统自动选择
        return top.galqq.R.style.Theme_GalQQ_DayNight;
    }
    
    /**
     * 强制更新Configuration以反映夜间模式变化
     * @param context 上下文
     */
    public static void updateConfiguration(Context context) {
        try {
            boolean isNightMode = isQQNightMode(context);
            Configuration config = new Configuration(context.getResources().getConfiguration());
            
            if (isNightMode) {
                config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
            } else {
                config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
            }
            
            // 更新资源配置
            context.getResources().updateConfiguration(config, context.getResources().getDisplayMetrics());
            Log.d(TAG, "Updated configuration, night mode: " + isNightMode);
        } catch (Exception e) {
            Log.e(TAG, "Error updating configuration: " + e.getMessage());
        }
    }
    
    /**
     * 获取夜间模式掩码值（用于Configuration）
     */
    public static int getNightModeMasked(Context context) {
        return isQQNightMode(context) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
    }
}
