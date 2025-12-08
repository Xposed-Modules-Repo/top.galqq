package top.galqq.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import top.galqq.R;

public class SettingsInterceptor {

    private static final String TAG = "GalQQ.SettingsInterceptor";
    
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
     * 错误日志（始终输出）
     */
    private static void errorLog(String message) {
        XposedBridge.log(message);
    }
    
    private static void errorLog(Throwable t) {
        XposedBridge.log(t);
    }

    public static void init(ClassLoader classLoader) {
        try {
            boolean newVersionHooked = hookMainSettingConfigProvider(classLoader);
            
            if (!newVersionHooked) {
                hookOldVersionSettings(classLoader);
            }
            
        } catch (Throwable t) {
            errorLog(TAG + ": Failed to initialize: " + t.getMessage());
            errorLog(t);
        }
    }

    private static boolean hookMainSettingConfigProvider(ClassLoader classLoader) {
        try {
            Class<?> kMainSettingFragment = XposedHelpers.findClassIfExists(
                "com.tencent.mobileqq.setting.main.MainSettingFragment", 
                classLoader
            );
            
            if (kMainSettingFragment == null) {
                return false;
            }
            
            Class<?> kMainSettingConfigProvider = XposedHelpers.findClassIfExists(
                "com.tencent.mobileqq.setting.main.MainSettingConfigProvider", 
                classLoader
            );
            
            Class<?> kNewSettingConfigProvider = XposedHelpers.findClassIfExists(
                "com.tencent.mobileqq.setting.main.NewSettingConfigProvider", 
                classLoader
            );
            
            // 9.2.30+, NewSettingConfigProvider was obfuscated to b
            Class<?> kNewSettingConfigProviderObf = XposedHelpers.findClassIfExists(
                "com.tencent.mobileqq.setting.main.b",
                classLoader
            );
            
            debugLog(TAG + ": MainSettingConfigProvider found: " + (kMainSettingConfigProvider != null));
            debugLog(TAG + ": NewSettingConfigProvider found: " + (kNewSettingConfigProvider != null));
            debugLog(TAG + ": NewSettingConfigProviderObf found: " + (kNewSettingConfigProviderObf != null));
            
            if (kMainSettingConfigProvider == null && kNewSettingConfigProvider == null && kNewSettingConfigProviderObf == null) {
                debugLog(TAG + ": No ConfigProvider found");
                return false;
            }
            
            Method getItemProcessListOld = null;
            if (kMainSettingConfigProvider != null) {
                getItemProcessListOld = findGetItemProcessListMethod(kMainSettingConfigProvider);
                if (getItemProcessListOld != null) {
                    debugLog(TAG + ": Found method in MainSettingConfigProvider: " + getItemProcessListOld.getName());
                }
            }
            
            Method getItemProcessListNew = null;
            if (kNewSettingConfigProvider != null) {
                getItemProcessListNew = findGetItemProcessListMethod(kNewSettingConfigProvider);
                if (getItemProcessListNew != null) {
                    debugLog(TAG + ": Found method in NewSettingConfigProvider: " + getItemProcessListNew.getName());
                }
            }
            
            Method getItemProcessListNewObf = null;
            if (kNewSettingConfigProviderObf != null) {
                getItemProcessListNewObf = findGetItemProcessListMethod(kNewSettingConfigProviderObf);
                if (getItemProcessListNewObf != null) {
                    debugLog(TAG + ": Found method in NewSettingConfigProviderObf: " + getItemProcessListNewObf.getName());
                }
            }
            
            if (getItemProcessListOld == null && getItemProcessListNew == null && getItemProcessListNewObf == null) {
                debugLog(TAG + ": getItemProcessList method not found in any ConfigProvider");
                return false;
            }
            
            Class<?> kAbstractItemProcessor = findAbstractItemProcessor(classLoader);
            if (kAbstractItemProcessor == null) {
                debugLog(TAG + ": AbstractItemProcessor not found");
                return false;
            }
            
            Class<?> kSimpleItemProcessor = findSimpleItemProcessor(classLoader, kAbstractItemProcessor);
            if (kSimpleItemProcessor == null) {
                debugLog(TAG + ": SimpleItemProcessor not found");
                return false;
            }
            
            // 查找 setOnClickListener 方法
            final Method setOnClickListenerMethod = findSetOnClickListenerMethod(kSimpleItemProcessor);
            if (setOnClickListenerMethod == null) {
                debugLog(TAG + ": setOnClickListener method not found");
                return false;
            }
            
            // 查找构造函数
            final Constructor<?> ctorSimpleItemProcessor;
            final int ctorArgc;
            Constructor<?> ctor5 = null;
            try {
                ctor5 = kSimpleItemProcessor.getDeclaredConstructor(
                    Context.class, int.class, CharSequence.class, int.class, String.class);
            } catch (NoSuchMethodException ignored) {}
            
            if (ctor5 != null) {
                ctorSimpleItemProcessor = ctor5;
                ctorArgc = 5;
            } else {
                ctorSimpleItemProcessor = kSimpleItemProcessor.getDeclaredConstructor(
                    Context.class, int.class, CharSequence.class, int.class);
                ctorArgc = 4;
            }
            
            debugLog(TAG + ": Preparing to hook getItemProcessList methods...");
            
            // 创建共享的 Hook 回调
            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        debugLog(TAG + ": === getItemProcessList callback triggered ===");
                        
                        List<Object> result = (List<Object>) param.getResult();
                        Context ctx = (Context) param.args[0];
                        
                        debugLog(TAG + ": Result list size: " + result.size());
                        
                        Class<?> kItemProcessorGroup = result.get(0).getClass();
                        debugLog(TAG + ": ItemProcessorGroup class: " + kItemProcessorGroup.getName());
                        
                        // 创建设置项
                        ctorSimpleItemProcessor.setAccessible(true);
                        Object entryItem;
                        if (ctorArgc == 5) {
                            debugLog(TAG + ": Creating entryItem with 5-arg constructor");
                            entryItem = ctorSimpleItemProcessor.newInstance(
                                ctx, R.id.setting2Activity_settingEntryItem, "GalQQ", 0, null);
                        } else {
                            debugLog(TAG + ": Creating entryItem with 4-arg constructor");
                            entryItem = ctorSimpleItemProcessor.newInstance(
                                ctx, R.id.setting2Activity_settingEntryItem, "GalQQ", 0);
                        }
                        debugLog(TAG + ": Entry item created: " + entryItem);
                        
                        // 设置点击监听器
                        Class<?> function0Class = setOnClickListenerMethod.getParameterTypes()[0];
                        Object unitInstance = function0Class.getClassLoader()
                            .loadClass("kotlin.Unit")
                            .getField("INSTANCE")
                            .get(null);
                        
                        Object clickListener = Proxy.newProxyInstance(
                            function0Class.getClassLoader(),
                            new Class<?>[]{function0Class},
                            (proxy, method, args) -> {
                                if ("invoke".equals(method.getName())) {
                                    debugLog(TAG + ": *** Setting item clicked! ***");
                                    onSettingEntryClick(ctx);
                                    return unitInstance;
                                }
                                if (method.getDeclaringClass() == Object.class) {
                                    return method.invoke(this, args);
                                }
                                return null;
                            }
                        );
                        
                        setOnClickListenerMethod.setAccessible(true);
                        setOnClickListenerMethod.invoke(entryItem, clickListener);
                        
                        debugLog(TAG + ": Click listener set");
                        
                        // 创建设置组
                        ArrayList<Object> list = new ArrayList<>();
                        list.add(entryItem);
                        
                        Constructor<?> groupCtor;
                        Object group;
                        try {
                            groupCtor = kItemProcessorGroup.getDeclaredConstructor(
                                List.class, CharSequence.class, CharSequence.class);
                            groupCtor.setAccessible(true);
                            group = groupCtor.newInstance(list, "", "");
                        } catch (NoSuchMethodException e) {
                            Class<?> defCtorMarker = XposedHelpers.findClass(
                                "kotlin.jvm.internal.DefaultConstructorMarker", classLoader);
                            groupCtor = kItemProcessorGroup.getDeclaredConstructor(
                                List.class, CharSequence.class, CharSequence.class, int.class, defCtorMarker);
                            groupCtor.setAccessible(true);
                            group = groupCtor.newInstance(list, "", "", 6, null);
                        }
                        
                        // 插入到列表
                        debugLog(TAG + ": Adding group to result list at index 1");
                        debugLog(TAG + ": Group object: " + group);
                        debugLog(TAG + ": List size before add: " + result.size());
                        
                        result.add(1, group);
                        
                        debugLog(TAG + ": List size after add: " + result.size());
                        debugLog(TAG + ": ✓ Successfully injected setting entry!");
                    } catch (Throwable t) {
                        errorLog(TAG + ": Error in hook callback: " + t.getMessage());
                        errorLog(t);
                    }
                }
            };
            
            // Hook 所有找到的方法
            int hookedCount = 0;
            if (getItemProcessListOld != null) {
                XposedBridge.hookMethod(getItemProcessListOld, callback);
                debugLog(TAG + ": Hooked MainSettingConfigProvider." + getItemProcessListOld.getName());
                hookedCount++;
            }
            if (getItemProcessListNew != null) {
                XposedBridge.hookMethod(getItemProcessListNew, callback);
                debugLog(TAG + ": Hooked NewSettingConfigProvider." + getItemProcessListNew.getName());
                hookedCount++;
            }
            if (getItemProcessListNewObf != null) {
                XposedBridge.hookMethod(getItemProcessListNewObf, callback);
                debugLog(TAG + ": Hooked NewSettingConfigProviderObf." + getItemProcessListNewObf.getName());
                hookedCount++;
            }
            
            debugLog(TAG + ": ✓✓✓ Successfully hooked " + hookedCount + " ConfigProvider(s) ✓✓✓");
            return true;
            
        } catch (Throwable t) {
            errorLog(TAG + ": Failed to hook new version: " + t.getMessage());
            errorLog(t);
            return false;
        }
    }

    private static Method findGetItemProcessListMethod(Class<?> configProviderClass) {
        for (Method m : configProviderClass.getDeclaredMethods()) {
            if (m.getReturnType() == List.class 
                && m.getParameterTypes().length == 1 
                && m.getParameterTypes()[0] == Context.class
                && !Modifier.isStatic(m.getModifiers())) {
                return m;
            }
        }
        return null;
    }

    private static Class<?> findAbstractItemProcessor(ClassLoader classLoader) {
        String[] possibleParents = {
            "com.tencent.mobileqq.setting.main.processor.AccountSecurityItemProcessor",
            "com.tencent.mobileqq.setting.main.processor.AboutItemProcessor"
        };
        
        for (String name : possibleParents) {
            Class<?> k = XposedHelpers.findClassIfExists(name, classLoader);
            if (k != null && k.getSuperclass() != null) {
                return k.getSuperclass();
            }
        }
        return null;
    }

    private static Class<?> findSimpleItemProcessor(ClassLoader classLoader, Class<?> abstractItemProcessor) {
        String[] possibleNames = {
            "com.tencent.mobileqq.setting.processor.g",
            "com.tencent.mobileqq.setting.processor.h",
            "com.tencent.mobileqq.setting.processor.i",
            "com.tencent.mobileqq.setting.processor.j",
            "as3.i"
        };
        
        for (String name : possibleNames) {
            Class<?> k = XposedHelpers.findClassIfExists(name, classLoader);
            if (k != null && k.getSuperclass() == abstractItemProcessor) {
                return k;
            }
        }
        return null;
    }

    private static Method findSetOnClickListenerMethod(Class<?> kSimpleItemProcessor) {
        List<Method> candidates = new ArrayList<>();
        for (Method m : kSimpleItemProcessor.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (m.getReturnType() == void.class 
                && params.length == 1 
                && "kotlin.jvm.functions.Function0".equals(params[0].getName())) {
                candidates.add(m);
            }
        }
        
        if (candidates.isEmpty()) {
            return null;
        }
        
        candidates.sort(Comparator.comparing(Method::getName));
        return candidates.get(0);
    }

    private static void onSettingEntryClick(Context ctx) {
        try {
            debugLog(TAG + ": Opening settings activity...");
            Intent intent = new Intent();
            intent.setClassName(ctx, "top.galqq.ui.SettingsUiFragmentHostActivity");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            debugLog(TAG + ": Settings activity started successfully");
        } catch (Exception e) {
            errorLog(TAG + ": Failed to start activity: " + e.getMessage());
            errorLog(e);
        }
    }

    private static void hookOldVersionSettings(ClassLoader classLoader) {
        try {
            Class<?> activityClass = XposedHelpers.findClassIfExists(
                "com.tencent.mobileqq.activity.QQSettingSettingActivity", 
                classLoader
            );
            
            if (activityClass != null) {
                XposedHelpers.findAndHookMethod(activityClass, "doOnCreate", Bundle.class, 
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            injectSettingEntryOldVersion((Activity) param.thisObject);
                        }
                    });
                debugLog(TAG + ": Successfully hooked QQSettingSettingActivity");
            }
            
        } catch (Throwable t) {
            errorLog(TAG + ": Failed to hook old version: " + t.getMessage());
        }
    }

    private static void injectSettingEntryOldVersion(Activity activity) {
        try {
            Class<?> formItemClass = XposedHelpers.findClassIfExists(
                "com.tencent.mobileqq.widget.FormSimpleItem",
                activity.getClassLoader()
            );
            
            if (formItemClass == null) {
                formItemClass = XposedHelpers.findClassIfExists(
                    "com.tencent.mobileqq.widget.FormCommonSingleLineItem",
                    activity.getClassLoader()
                );
            }
            
            if (formItemClass == null) {
                return;
            }
            
            View item = (View) XposedHelpers.newInstance(formItemClass, activity);
            item.setId(R.id.setting2Activity_settingEntryItem);
            
            XposedHelpers.callMethod(item, "setLeftText", "Galgame 选项");
            XposedHelpers.callMethod(item, "setRightText", "v1.0");
            XposedHelpers.callMethod(item, "setBgType", 2);
            
            item.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent();
                    intent.setClassName(activity, "top.galqq.ui.SettingsUiFragmentHostActivity");
                    activity.startActivity(intent);
                } catch (Exception e) {
                    errorLog(TAG + ": Failed to start activity: " + e.getMessage());
                }
            });
            
            View itemRef = findFormItemReference(activity, formItemClass);
            
            if (itemRef != null && itemRef.getParent() != null) {
                ViewGroup list = (ViewGroup) itemRef.getParent();
                if (list.getChildCount() == 1) {
                    list = (ViewGroup) list.getParent();
                }
                
                ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                );
                
                list.addView(item, 0, lp);
                debugLog(TAG + ": Successfully injected (old version)");
            }
            
        } catch (Throwable t) {
            errorLog(TAG + ": Failed to inject (old version): " + t.getMessage());
        }
    }

    private static View findFormItemReference(Activity activity, Class<?> formItemClass) {
        try {
            Class<?> clz = activity.getClass();
            while (clz != null && !clz.equals(Object.class)) {
                for (Field f : clz.getDeclaredFields()) {
                    if (!f.getType().equals(formItemClass)) {
                        continue;
                    }
                    int m = f.getModifiers();
                    if (Modifier.isStatic(m) || Modifier.isFinal(m)) {
                        continue;
                    }
                    f.setAccessible(true);
                    try {
                        View v = (View) f.get(activity);
                        if (v != null && v.getParent() != null) {
                            return v;
                        }
                    } catch (IllegalAccessException ignored) {}
                }
                clz = clz.getSuperclass();
            }
        } catch (Throwable ignored) {}
        
        return null;
    }
}
