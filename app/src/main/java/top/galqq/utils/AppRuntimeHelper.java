package top.galqq.utils;

import android.content.Context;

import java.lang.reflect.Field;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * AppRuntime获取工具类，完全模仿QAuxiliary的实现方式
 * 提供多种获取AppRuntime实例的方法，确保在不同场景下都能正常工作
 */
public class AppRuntimeHelper {

    // 缓存反射结果，提高性能
    private static Field f_mAppRuntime = null;
    
    /**
     * 获取AppRuntime实例，模仿QAuxiliary的主要方式
     * @param context 上下文对象
     * @return AppRuntime实例，获取失败返回null
     */
    public static Object getAppRuntime(Context context) {
        try {
            // XposedBridge.log("GalQQ.AppRuntimeHelper: ===== 开始获取AppRuntime =====");
            
            // 确保Initiator已初始化
            if (Initiator.getHostClassLoader() == null) {
                // XposedBridge.log("GalQQ.AppRuntimeHelper: Initiator未初始化，开始初始化");
                Initiator.init(context.getClassLoader());
                // XposedBridge.log("GalQQ.AppRuntimeHelper: Initiator初始化完成，ClassLoader: " + context.getClassLoader().getClass().getName());
            } else {
                // XposedBridge.log("GalQQ.AppRuntimeHelper: Initiator已初始化，当前ClassLoader: " + Initiator.getHostClassLoader().getClass().getName());
            }
            
            // 检查必要的类是否已加载
            if (Initiator._MobileQQ == null) {
                // XposedBridge.log("GalQQ.AppRuntimeHelper: MobileQQ类未加载，尝试加载");
                Initiator._MobileQQ = Initiator.load("mqq.app.MobileQQ");
                // XposedBridge.log("GalQQ.AppRuntimeHelper: MobileQQ类加载结果: " + (Initiator._MobileQQ != null ? "成功" : "失败"));
            } else {
                // XposedBridge.log("GalQQ.AppRuntimeHelper: MobileQQ类已加载");
            }
            
            if (Initiator._MobileQQ == null) {
                // XposedBridge.log("GalQQ.AppRuntimeHelper: 错误: 无法加载MobileQQ类");
                return null;
            }
            
            // 尝试加载AppRuntime类，但不强制要求成功
            if (Initiator._AppRuntime == null) {
                // XposedBridge.log("GalQQ.AppRuntimeHelper: AppRuntime类未加载，尝试加载");
                Initiator._AppRuntime = Initiator.load("mqq.app.AppRuntime");
                if (Initiator._AppRuntime != null) {
                    // XposedBridge.log("GalQQ.AppRuntimeHelper: AppRuntime类加载成功");
                } else {
                    // XposedBridge.log("GalQQ.AppRuntimeHelper: 警告: AppRuntime类加载失败，将使用字符串方式获取字段");
                }
            } else {
                // XposedBridge.log("GalQQ.AppRuntimeHelper: AppRuntime类已加载");
            }
            
            // XposedBridge.log("GalQQ.AppRuntimeHelper: 尝试获取MobileQQ.sMobileQQ静态字段");
            
            // 获取MobileQQ.sMobileQQ静态实例
            Object mobileQQInstance = Initiator.getStaticObject("mqq.app.MobileQQ", "sMobileQQ");
            if (mobileQQInstance == null) {
                // XposedBridge.log("GalQQ.AppRuntimeHelper: 错误: 无法获取MobileQQ.sMobileQQ实例");
                return null;
            }
            
            // XposedBridge.log("GalQQ.AppRuntimeHelper: 成功获取MobileQQ.sMobileQQ实例: " + mobileQQInstance.getClass().getName());
            // XposedBridge.log("GalQQ.AppRuntimeHelper: MobileQQ实例类加载器: " + mobileQQInstance.getClass().getClassLoader().getClass().getName());
            
            // 模仿QAuxiliary的方式：通过MobileQQ.sMobileQQ获取mAppRuntime字段
            // 尝试使用反射获取字段，即使AppRuntime类加载失败也能工作
            Field mAppRuntimeField = null;
            if (f_mAppRuntime == null) {
                // XposedBridge.log("GalQQ.AppRuntimeHelper: 首次获取mAppRuntime字段，进行反射");
                
                try {
                    // 尝试使用已加载的AppRuntime类获取字段
                    if (Initiator._AppRuntime != null) {
                        f_mAppRuntime = Initiator._MobileQQ.getDeclaredField("mAppRuntime");
                        f_mAppRuntime.setAccessible(true);
                        // XposedBridge.log("GalQQ.AppRuntimeHelper: 成功获取mAppRuntime字段引用(使用AppRuntime类)");
                    } else {
                        // 如果AppRuntime类加载失败，尝试通过字段名获取
                        // XposedBridge.log("GalQQ.AppRuntimeHelper: AppRuntime类未加载，尝试通过字段名获取mAppRuntime字段");
                        Field[] fields = Initiator._MobileQQ.getDeclaredFields();
                        for (Field field : fields) {
                            if ("mAppRuntime".equals(field.getName())) {
                                f_mAppRuntime = field;
                                f_mAppRuntime.setAccessible(true);
                                // XposedBridge.log("GalQQ.AppRuntimeHelper: 成功获取mAppRuntime字段引用(通过字段名)");
                                break;
                            }
                        }
                        
                        if (f_mAppRuntime == null) {
                            // XposedBridge.log("GalQQ.AppRuntimeHelper: 错误: 无法找到mAppRuntime字段");
                            return null;
                        }
                    }
                } catch (NoSuchFieldException e) {
                    // XposedBridge.log("GalQQ.AppRuntimeHelper: 错误: 找不到mAppRuntime字段: " + e.getMessage());
                    return null;
                } catch (SecurityException e) {
                    // XposedBridge.log("GalQQ.AppRuntimeHelper: 安全错误: 无法访问mAppRuntime字段: " + e.getMessage());
                    return null;
                }
            } else {
                // XposedBridge.log("GalQQ.AppRuntimeHelper: 使用缓存的mAppRuntime字段引用");
            }
            
            try {
                // XposedBridge.log("GalQQ.AppRuntimeHelper: 尝试从MobileQQ实例获取mAppRuntime字段值");
                
                Object appRuntime = f_mAppRuntime.get(mobileQQInstance);
                if (appRuntime != null) {
                    // XposedBridge.log("GalQQ.AppRuntimeHelper: 成功获取AppRuntime实例: " + appRuntime.getClass().getName());
                    // XposedBridge.log("GalQQ.AppRuntimeHelper: AppRuntime实例类加载器: " + appRuntime.getClass().getClassLoader().getClass().getName());
                    return appRuntime;
                } else {
                    // XposedBridge.log("GalQQ.AppRuntimeHelper: 错误: MobileQQ.sMobileQQ.mAppRuntime为null");
                    return null;
                }
            } catch (IllegalAccessException e) {
                // XposedBridge.log("GalQQ.AppRuntimeHelper: 错误: 无法访问mAppRuntime字段: " + e.getMessage());
                return null;
            }
        } catch (Exception e) {
            // XposedBridge.log("GalQQ.AppRuntimeHelper: 未知错误: " + e.getMessage());
            // XposedBridge.log("GalQQ.AppRuntimeHelper: 错误类型: " + e.getClass().getName());
            
            // 打印详细的堆栈跟踪
            StackTraceElement[] stackTrace = e.getStackTrace();
            // XposedBridge.log("GalQQ.AppRuntimeHelper: 堆栈跟踪(前10个元素):");
            for (int i = 0; i < Math.min(10, stackTrace.length); i++) {
                // XposedBridge.log("GalQQ.AppRuntimeHelper:   " + stackTrace[i].toString());
            }
            
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 从对象中提取AppRuntime字段
     * 用于Hook场景中从被Hook的对象中提取AppRuntime
     * @param obj 包含AppRuntime字段的对象
     * @return AppRuntime实例
     */
    public static Object getAppRuntimeFromObject(Object obj) {
        if (obj == null) {
            return null;
        }
        
        try {
            // XposedBridge.log("GalQQ.AppRuntimeHelper: ===== 开始从对象提取AppRuntime =====");
            
            // 确保Initiator已初始化
            if (Initiator.getHostClassLoader() == null) {
                // XposedBridge.log("GalQQ.AppRuntimeHelper: Initiator未初始化，无法提取AppRuntime");
                return null;
            }
            
            // 不使用Reflex.getFirstNSFByType避免传递AppRuntime Class对象
            // 改为手动遍历字段，查找类型为"mqq.app.AppRuntime"的字段
            Class<?> objClass = obj.getClass();
            // XposedBridge.log("GalQQ.AppRuntimeHelper: 目标对象类型: " + objClass.getName());
            
            // 加载AppRuntime类用于类型比较
            Class<?> appRuntimeClass = Initiator.load("mqq.app.AppRuntime");
            if (appRuntimeClass == null) {
                // XposedBridge.log("GalQQ.AppRuntimeHelper: 无法加载AppRuntime类，尝试使用字符串比较");
                
                // 如果无法加载AppRuntime类，则使用字符串比较字段类型名
                while (objClass != null && !objClass.equals(Object.class)) {
                    // XposedBridge.log("GalQQ.AppRuntimeHelper: 检查类: " + objClass.getName());
                    
                    for (java.lang.reflect.Field f : objClass.getDeclaredFields()) {
                        int m = f.getModifiers();
                        if (java.lang.reflect.Modifier.isStatic(m) || java.lang.reflect.Modifier.isFinal(m)) {
                            continue;
                        }
                        
                        // 检查字段类型名是否匹配
                        String fieldTypeName = f.getType().getName();
                        // XposedBridge.log("GalQQ.AppRuntimeHelper: 检查字段: " + f.getName() + ", 类型: " + fieldTypeName);
                        
                        if ("mqq.app.AppRuntime".equals(fieldTypeName)) {
                            // XposedBridge.log("GalQQ.AppRuntimeHelper: 找到匹配的AppRuntime字段: " + f.getName());
                            f.setAccessible(true);
                            Object appRuntime = f.get(obj);
                            if (appRuntime != null) {
                                // XposedBridge.log("GalQQ.AppRuntimeHelper: 成功提取AppRuntime实例");
                                return appRuntime;
                            }
                        }
                    }
                    objClass = objClass.getSuperclass();
                }
            } else {
                // XposedBridge.log("GalQQ.AppRuntimeHelper: 成功加载AppRuntime类，使用类型比较");
                
                // 使用类型比较查找字段
                while (objClass != null && !objClass.equals(Object.class)) {
                    // XposedBridge.log("GalQQ.AppRuntimeHelper: 检查类: " + objClass.getName());
                    
                    for (java.lang.reflect.Field f : objClass.getDeclaredFields()) {
                        int m = f.getModifiers();
                        if (java.lang.reflect.Modifier.isStatic(m) || java.lang.reflect.Modifier.isFinal(m)) {
                            continue;
                        }
                        
                        // 检查字段类型是否匹配
                        if (f.getType().equals(appRuntimeClass)) {
                            // XposedBridge.log("GalQQ.AppRuntimeHelper: 找到匹配的AppRuntime字段: " + f.getName());
                            f.setAccessible(true);
                            Object appRuntime = f.get(obj);
                            if (appRuntime != null) {
                                // XposedBridge.log("GalQQ.AppRuntimeHelper: 成功提取AppRuntime实例");
                                return appRuntime;
                            }
                        }
                    }
                    objClass = objClass.getSuperclass();
                }
            }
            
            // XposedBridge.log("GalQQ.AppRuntimeHelper: 未找到AppRuntime字段");
            return null;
        } catch (Exception e) {
            // XposedBridge.log("GalQQ.AppRuntimeHelper: 提取AppRuntime时发生错误: " + e.getMessage());
            // XposedBridge.log("GalQQ.AppRuntimeHelper: 错误类型: " + e.getClass().getName());
            
            // 打印详细的堆栈跟踪
            StackTraceElement[] stackTrace = e.getStackTrace();
            // XposedBridge.log("GalQQ.AppRuntimeHelper: 堆栈跟踪(前10个元素):");
            for (int i = 0; i < Math.min(10, stackTrace.length); i++) {
                // XposedBridge.log("GalQQ.AppRuntimeHelper:   " + stackTrace[i].toString());
            }
            
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 获取QQAppInterface实例
     * @param context 上下文对象
     * @return QQAppInterface实例
     */
    public static Object getQQAppInterface(Context context) {
        return getAppRuntime(context);
    }
    
    /**
     * 获取当前账号Uin
     * @param context 上下文对象
     * @return 账号Uin
     */
    public static long getLongAccountUin(Context context) {
        try {
            Object appRuntime = getAppRuntime(context);
            if (appRuntime != null) {
                // 使用反射调用getCurrentAccountUin方法
                Object uin = XposedHelpers.callMethod(appRuntime, "getCurrentAccountUin");
                if (uin instanceof Long) {
                    return (Long) uin;
                } else if (uin instanceof String) {
                    return Long.parseLong((String) uin);
                }
            }
        } catch (Exception e) {
            // XposedBridge.log("GalQQ: Failed to get current account uin: " + e.getMessage());
        }
        return 0;
    }
    
    /**
     * 获取服务器时间
     * @return 服务器时间戳
     */
    public static long getServerTime() {
        try {
            // 模仿QAuxiliary的方式调用静态方法
            Object result = Initiator.callStaticMethod("mqq.app.MobileQQ", "getServerTime");
            if (result instanceof Long) {
                return (Long) result;
            }
        } catch (Exception e) {
            // XposedBridge.log("GalQQ: Failed to get server time: " + e.getMessage());
        }
        return System.currentTimeMillis();
    }
    
    /**
     * 获取Application实例
     * @return Application实例，获取失败返回null
     */
    public static android.app.Application getApplication() {
        try {
            // 方法1: 通过MobileQQ.sMobileQQ获取
            Object mobileQQInstance = Initiator.getStaticObject("mqq.app.MobileQQ", "sMobileQQ");
            if (mobileQQInstance != null && mobileQQInstance instanceof android.app.Application) {
                return (android.app.Application) mobileQQInstance;
            }
            
            // 方法2: 通过ActivityThread获取
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            if (activityThread != null) {
                Object app = activityThreadClass.getMethod("getApplication").invoke(activityThread);
                if (app instanceof android.app.Application) {
                    return (android.app.Application) app;
                }
            }
        } catch (Exception e) {
            // XposedBridge.log("GalQQ: Failed to get Application: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 获取Context实例
     * @return Context实例，获取失败返回null
     */
    public static Context getContext() {
        android.app.Application app = getApplication();
        if (app != null) {
            return app.getApplicationContext();
        }
        return null;
    }
}