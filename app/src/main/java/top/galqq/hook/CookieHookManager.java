/*
 * GalQQ - An Xposed module for QQ
 * Copyright (C) 2024 GalQQ contributors
 */

package top.galqq.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * CookieHookManager - 通过Hook从QQ内存中获取Cookie
 */
public class CookieHookManager {
    
    private static final String TAG = "GalQQ.CookieHookManager";
    
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
     * 初始化阶段的日志（受配置开关控制）
     * 注意：在ConfigManager初始化之前调用时会静默忽略
     */
    private static void log(String message) {
        debugLog(message);
    }
    
    // 内存缓存
    private static volatile String sCachedSkey = null;
    private static volatile String sCachedPSkey = null;
    private static volatile String sCachedUin = null;
    private static volatile String sCachedPUid = null;  // p_uid字段
    private static volatile long sLastUpdateTime = 0;
    
    // 缓存过期时间（30分钟）
    private static final long CACHE_EXPIRE_MS = 30 * 60 * 1000;
    
    // Hook是否已初始化
    private static volatile boolean sHooksInitialized = false;
    
    // 用于防止重复日志
    private static volatile boolean sSkeyLogged = false;
    private static volatile boolean sPskeyLogged = false;
    private static volatile boolean sUinLogged = false;
    
    /**
     * 初始化Hook
     */
    public static void initHooks(XC_LoadPackage.LoadPackageParam lpparam) {
        if (sHooksInitialized) {
            return;
        }
        
        log(TAG + ": ========== 初始化Cookie Hook ==========");
        
        ClassLoader classLoader = lpparam.classLoader;
        
        int successCount = 0;
        
        // 方法1: Hook TicketManager.getSkey (旧版QQ)
        if (hookGetSkey(classLoader)) successCount++;
        
        // 方法2: Hook TicketManager.getPskey (旧版QQ)
        if (hookGetPskey(classLoader)) successCount++;
        
        // 方法3: Hook getCurrentAccountUin
        if (hookGetCurrentAccountUin(classLoader)) successCount++;
        
        // 方法4: Hook WtloginHelper回调 (QQNT 8.9.58+)
        // 新版QQNT把票据移到native层，需要通过回调获取
        if (hookWtloginHelperCallback(classLoader)) successCount++;
        
        // 方法5: Hook WtloginHelper的native方法返回 (QQNT 8.9.58+)
        if (hookWtloginHelperNative(classLoader)) successCount++;
        
        sHooksInitialized = true;
        log(TAG + ": Cookie Hook初始化完成, 成功: " + successCount + "/5");
        log(TAG + ": ==========================================");
    }
    
    /**
     * Hook WtloginHelper$OnDataGetListener 回调 (QQNT 8.9.58+)
     * 新版QQNT把票据移到native层(libbasic_share.so)，通过回调返回数据
     */
    private static boolean hookWtloginHelperCallback(ClassLoader classLoader) {
        boolean success = false;
        
        // 尝试Hook onSKeyGet回调
        String[] listenerClasses = {
            "com.tencent.qphone.base.remote.WtloginHelper$OnDataGetListener",
            "oicq.wlogin_sdk.request.WtloginHelper$OnDataGetListener",
            "com.tencent.mobileqq.msf.core.auth.WtloginHelper$OnDataGetListener"
        };
        
        for (String listenerClass : listenerClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClass(listenerClass, classLoader);
                
                // Hook onSKeyGet(int ret, String skey)
                try {
                    XposedBridge.hookAllMethods(clazz, "onSKeyGet", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.args.length >= 2 && param.args[1] instanceof String) {
                                String skey = (String) param.args[1];
                                if (skey != null && !skey.isEmpty()) {
                                    boolean isNew = sCachedSkey == null || !sCachedSkey.equals(skey);
                                    sCachedSkey = skey;
                                    sLastUpdateTime = System.currentTimeMillis();
                                    if (!sSkeyLogged || isNew) {
                                        sSkeyLogged = true;
                                        String preview = skey.length() > 10 ? skey.substring(0, 10) + "..." : skey;
                                        debugLog(TAG + ": ✓ [回调] 捕获skey: " + preview);
                                    }
                                }
                            }
                        }
                    });
                    debugLog(TAG + ": [WtloginCallback] onSKeyGet Hook成功: " + listenerClass);
                    success = true;
                } catch (Throwable t) {
                    // 继续
                }
                
                // Hook onPsKeyGet(int ret, String pskey) 或 onPsKeyGet(int ret, String domain, String pskey)
                try {
                    XposedBridge.hookAllMethods(clazz, "onPsKeyGet", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            String pskey = null;
                            String domain = null;
                            
                            // 处理不同的参数签名
                            if (param.args.length >= 3) {
                                // onPsKeyGet(int ret, String domain, String pskey)
                                if (param.args[1] instanceof String) domain = (String) param.args[1];
                                if (param.args[2] instanceof String) pskey = (String) param.args[2];
                            } else if (param.args.length >= 2) {
                                // onPsKeyGet(int ret, String pskey)
                                if (param.args[1] instanceof String) pskey = (String) param.args[1];
                            }
                            
                            if (pskey != null && !pskey.isEmpty()) {
                                // 如果有domain，检查是否是qzone
                                if (domain == null || domain.contains("qzone")) {
                                    boolean isNew = sCachedPSkey == null || !sCachedPSkey.equals(pskey);
                                    sCachedPSkey = pskey;
                                    sLastUpdateTime = System.currentTimeMillis();
                                    if (!sPskeyLogged || isNew) {
                                        sPskeyLogged = true;
                                        String preview = pskey.length() > 10 ? pskey.substring(0, 10) + "..." : pskey;
                                        debugLog(TAG + ": ✓ [回调] 捕获p_skey: " + preview);
                                    }
                                }
                            }
                        }
                    });
                    debugLog(TAG + ": [WtloginCallback] onPsKeyGet Hook成功: " + listenerClass);
                    success = true;
                } catch (Throwable t) {
                    // 继续
                }
                
                if (success) break;
            } catch (Throwable t) {
                // 继续尝试下一个类
            }
        }
        
        if (!success) {
            debugLog(TAG + ": [WtloginCallback] Hook失败");
        }
        return success;
    }
    
    /**
     * Hook WtloginHelper的native方法 (QQNT 8.9.58+)
     * 这些方法是native层的壳，但返回值就是最终的skey/pskey
     */
    private static boolean hookWtloginHelperNative(ClassLoader classLoader) {
        boolean success = false;
        
        String[] helperClasses = {
            "com.tencent.qphone.base.remote.WtloginHelper",
            "oicq.wlogin_sdk.request.WtloginHelper",
            "com.tencent.mobileqq.msf.core.auth.WtloginHelper"
        };
        
        for (String helperClass : helperClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClass(helperClass, classLoader);
                
                // Hook getSKey方法 (可能有多个重载)
                try {
                    XposedBridge.hookAllMethods(clazz, "getSKey", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object result = param.getResult();
                            if (result instanceof String) {
                                String skey = (String) result;
                                if (skey != null && !skey.isEmpty()) {
                                    boolean isNew = sCachedSkey == null || !sCachedSkey.equals(skey);
                                    sCachedSkey = skey;
                                    sLastUpdateTime = System.currentTimeMillis();
                                    if (!sSkeyLogged || isNew) {
                                        sSkeyLogged = true;
                                        String preview = skey.length() > 10 ? skey.substring(0, 10) + "..." : skey;
                                        debugLog(TAG + ": ✓ [WtloginHelper] 捕获skey: " + preview);
                                    }
                                }
                            }
                        }
                    });
                    debugLog(TAG + ": [WtloginHelper] getSKey Hook成功: " + helperClass);
                    success = true;
                } catch (Throwable t) {
                    // 继续
                }
                
                // Hook getPsKey方法
                try {
                    XposedBridge.hookAllMethods(clazz, "getPsKey", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object result = param.getResult();
                            if (result instanceof String) {
                                String pskey = (String) result;
                                if (pskey != null && !pskey.isEmpty()) {
                                    // 检查参数中是否有domain
                                    boolean isQzone = true;
                                    for (Object arg : param.args) {
                                        if (arg instanceof String) {
                                            String s = (String) arg;
                                            if (s.contains(".") && !s.contains("qzone")) {
                                                isQzone = false;
                                                break;
                                            }
                                        }
                                    }
                                    
                                    if (isQzone) {
                                        boolean isNew = sCachedPSkey == null || !sCachedPSkey.equals(pskey);
                                        sCachedPSkey = pskey;
                                        sLastUpdateTime = System.currentTimeMillis();
                                        if (!sPskeyLogged || isNew) {
                                            sPskeyLogged = true;
                                            String preview = pskey.length() > 10 ? pskey.substring(0, 10) + "..." : pskey;
                                            debugLog(TAG + ": ✓ [WtloginHelper] 捕获p_skey: " + preview);
                                        }
                                    }
                                }
                            }
                        }
                    });
                    debugLog(TAG + ": [WtloginHelper] getPsKey Hook成功: " + helperClass);
                    success = true;
                } catch (Throwable t) {
                    // 继续
                }
                
                if (success) break;
            } catch (Throwable t) {
                // 继续尝试下一个类
            }
        }
        
        if (!success) {
            debugLog(TAG + ": [WtloginHelper] Hook失败");
        }
        return success;
    }


    /**
     * Hook TicketManager.getSkey 方法
     */
    private static boolean hookGetSkey(ClassLoader classLoader) {
        // 扩展可能的类名列表，包括QQNT可能的混淆类名
        String[] possibleClasses = {
            "com.tencent.mobileqq.app.TicketManager",
            "com.tencent.mobileqq.manager.TicketManager",
            "com.tencent.mobileqq.msf.core.auth.TicketManager",
            "com.tencent.qphone.base.remote.TicketManager",
            "com.tencent.qqnt.kernel.api.impl.TicketManager",
            "com.tencent.qqnt.ticket.TicketManager"
        };
        
        for (String className : possibleClasses) {
            try {
                Class<?> ticketManagerClass = XposedHelpers.findClass(className, classLoader);
                
                // 尝试Hook getSkey方法
                if (tryHookGetSkey(ticketManagerClass)) {
                    debugLog(TAG + ": [getSkey] Hook成功: " + className);
                    return true;
                }
            } catch (Throwable t) {
                // 继续尝试下一个类
            }
        }
        
        // 尝试通过getManager(2)获取TicketManager并Hook
        if (tryHookSkeyViaManager(classLoader)) {
            return true;
        }
        
        debugLog(TAG + ": [getSkey] Hook失败");
        return false;
    }
    
    /**
     * 尝试Hook getSkey方法
     */
    private static boolean tryHookGetSkey(Class<?> ticketManagerClass) {
        try {
            XposedHelpers.findAndHookMethod(ticketManagerClass, "getSkey", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object result = param.getResult();
                        if (result instanceof String) {
                            String skey = (String) result;
                            if (skey != null && !skey.isEmpty()) {
                                boolean isNew = sCachedSkey == null || !sCachedSkey.equals(skey);
                                sCachedSkey = skey;
                                sLastUpdateTime = System.currentTimeMillis();
                                if (!sSkeyLogged || isNew) {
                                    sSkeyLogged = true;
                                    String preview = skey.length() > 10 ? skey.substring(0, 10) + "..." : skey;
                                    debugLog(TAG + ": ✓ 捕获skey: " + preview);
                                }
                            }
                        }
                    }
                });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
    
    /**
     * 通过getManager(2)获取TicketManager类并Hook
     */
    private static boolean tryHookSkeyViaManager(ClassLoader classLoader) {
        try {
            // 尝试找到QQAppInterface
            Class<?> qqAppInterface = XposedHelpers.findClass("com.tencent.mobileqq.app.QQAppInterface", classLoader);
            
            // Hook getManager方法来获取TicketManager的类
            XposedHelpers.findAndHookMethod(qqAppInterface, "getManager", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        int managerId = (int) param.args[0];
                        if (managerId == 2) { // TicketManager的ID是2
                            Object ticketManager = param.getResult();
                            if (ticketManager != null) {
                                // 缓存TicketManager实例
                                sCachedTicketManager = ticketManager;
                                debugLog(TAG + ": [getManager] 缓存TicketManager实例: " + ticketManager.getClass().getName());
                                
                                Class<?> tmClass = ticketManager.getClass();
                                // 动态Hook这个类的getSkey方法
                                hookTicketManagerMethods(tmClass);
                            }
                        }
                    }
                });
            
            debugLog(TAG + ": [getSkey] 通过getManager(2)动态Hook");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
    
    // 标记是否已经Hook了TicketManager的方法
    private static volatile boolean sTicketManagerHooked = false;
    
    // 缓存TicketManager实例，用于主动调用
    private static volatile Object sCachedTicketManager = null;
    private static volatile Class<?> sCachedTicketManagerClass = null;
    
    /**
     * 获取缓存的TicketManager实例
     */
    public static Object getCachedTicketManager() {
        return sCachedTicketManager;
    }
    
    /**
     * 主动调用TicketManager获取skey和pskey
     * @param uin 用户UIN
     * @return true如果成功获取
     */
    public static boolean fetchSkeyAndPskeyFromTicketManager(String uin) {
        if (sCachedTicketManager == null || uin == null || uin.isEmpty()) {
            debugLog(TAG + ": [主动获取] TicketManager未缓存或UIN为空");
            return false;
        }
        
        debugLog(TAG + ": [主动获取] 尝试从TicketManager获取skey/pskey, uin=" + uin);
        
        boolean success = false;
        
        // 获取skey
        try {
            Object skey = XposedHelpers.callMethod(sCachedTicketManager, "getSkey", uin);
            if (skey instanceof String && !((String) skey).isEmpty()) {
                sCachedSkey = (String) skey;
                sLastUpdateTime = System.currentTimeMillis();
                String preview = ((String) skey).length() > 10 ? ((String) skey).substring(0, 10) + "..." : (String) skey;
                debugLog(TAG + ": [主动获取] ✓ skey: " + preview);
                success = true;
            } else {
                debugLog(TAG + ": [主动获取] ✗ skey为空");
            }
        } catch (Throwable t) {
            debugLog(TAG + ": [主动获取] getSkey失败: " + t.getMessage());
        }
        
        // 获取pskey
        try {
            Object pskey = XposedHelpers.callMethod(sCachedTicketManager, "getPskey", uin, "qzone.qq.com");
            if (pskey instanceof String && !((String) pskey).isEmpty()) {
                sCachedPSkey = (String) pskey;
                sLastUpdateTime = System.currentTimeMillis();
                String preview = ((String) pskey).length() > 10 ? ((String) pskey).substring(0, 10) + "..." : (String) pskey;
                debugLog(TAG + ": [主动获取] ✓ p_skey: " + preview);
                success = true;
            } else {
                debugLog(TAG + ": [主动获取] ✗ p_skey为空");
            }
        } catch (Throwable t) {
            debugLog(TAG + ": [主动获取] getPskey失败: " + t.getMessage());
        }
        
        return success;
    }
    
    /**
     * 动态Hook TicketManager的方法
     */
    private static void hookTicketManagerMethods(Class<?> tmClass) {
        if (sTicketManagerHooked) return;
        sTicketManagerHooked = true;
        sCachedTicketManagerClass = tmClass;
        
        debugLog(TAG + ": 发现TicketManager类: " + tmClass.getName());
        
        // Hook getSkey
        try {
            XposedHelpers.findAndHookMethod(tmClass, "getSkey", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object result = param.getResult();
                        if (result instanceof String) {
                            String skey = (String) result;
                            if (skey != null && !skey.isEmpty()) {
                                boolean isNew = sCachedSkey == null || !sCachedSkey.equals(skey);
                                sCachedSkey = skey;
                                sLastUpdateTime = System.currentTimeMillis();
                                if (!sSkeyLogged || isNew) {
                                    sSkeyLogged = true;
                                    String preview = skey.length() > 10 ? skey.substring(0, 10) + "..." : skey;
                                    debugLog(TAG + ": ✓ 动态捕获skey: " + preview);
                                }
                            }
                        }
                    }
                });
            debugLog(TAG + ": [动态] getSkey Hook成功");
        } catch (Throwable t) {
            debugLog(TAG + ": [动态] getSkey Hook失败: " + t.getMessage());
        }
        
        // Hook getPskey
        try {
            XposedHelpers.findAndHookMethod(tmClass, "getPskey", String.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object result = param.getResult();
                        if (result instanceof String) {
                            String pskey = (String) result;
                            String domain = (String) param.args[1];
                            if (pskey != null && !pskey.isEmpty() && domain != null && domain.contains("qzone")) {
                                boolean isNew = sCachedPSkey == null || !sCachedPSkey.equals(pskey);
                                sCachedPSkey = pskey;
                                sLastUpdateTime = System.currentTimeMillis();
                                if (!sPskeyLogged || isNew) {
                                    sPskeyLogged = true;
                                    String preview = pskey.length() > 10 ? pskey.substring(0, 10) + "..." : pskey;
                                    debugLog(TAG + ": ✓ 动态捕获p_skey: " + preview);
                                }
                            }
                        }
                    }
                });
            debugLog(TAG + ": [动态] getPskey Hook成功");
        } catch (Throwable t) {
            debugLog(TAG + ": [动态] getPskey Hook失败: " + t.getMessage());
        }
    }
    
    /**
     * Hook TicketManager.getPskey 方法
     */
    private static boolean hookGetPskey(ClassLoader classLoader) {
        String[] possibleClasses = {
            "com.tencent.mobileqq.app.TicketManager",
            "com.tencent.mobileqq.manager.TicketManager",
            "com.tencent.mobileqq.msf.core.auth.TicketManager",
            "com.tencent.qphone.base.remote.TicketManager",
            "com.tencent.qqnt.kernel.api.impl.TicketManager",
            "com.tencent.qqnt.ticket.TicketManager"
        };
        
        for (String className : possibleClasses) {
            try {
                Class<?> ticketManagerClass = XposedHelpers.findClass(className, classLoader);
                
                XposedHelpers.findAndHookMethod(ticketManagerClass, "getPskey", 
                    String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object result = param.getResult();
                            if (result instanceof String) {
                                String pskey = (String) result;
                                String domain = (String) param.args[1];
                                if (pskey != null && !pskey.isEmpty() && domain != null && domain.contains("qzone")) {
                                    boolean isNew = sCachedPSkey == null || !sCachedPSkey.equals(pskey);
                                    sCachedPSkey = pskey;
                                    sLastUpdateTime = System.currentTimeMillis();
                                    if (!sPskeyLogged || isNew) {
                                        sPskeyLogged = true;
                                        String preview = pskey.length() > 10 ? pskey.substring(0, 10) + "..." : pskey;
                                        debugLog(TAG + ": ✓ 捕获p_skey: " + preview);
                                    }
                                }
                            }
                        }
                    });
                
                debugLog(TAG + ": [getPskey] Hook成功: " + className);
                return true;
            } catch (Throwable t) {
                // 继续尝试下一个类
            }
        }
        
        debugLog(TAG + ": [getPskey] Hook失败");
        return false;
    }
    
    /**
     * Hook getCurrentAccountUin 方法
     */
    private static boolean hookGetCurrentAccountUin(ClassLoader classLoader) {
        String[] possibleClasses = {
            "mqq.app.AppRuntime",
            "com.tencent.mobileqq.app.QQAppInterface",
            "com.tencent.common.app.AppInterface"
        };
        
        for (String className : possibleClasses) {
            try {
                Class<?> appClass = XposedHelpers.findClass(className, classLoader);
                
                XposedHelpers.findAndHookMethod(appClass, "getCurrentAccountUin",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object result = param.getResult();
                            if (result instanceof String) {
                                String uin = (String) result;
                                if (uin != null && !uin.isEmpty()) {
                                    boolean isNew = sCachedUin == null || !sCachedUin.equals(uin);
                                    sCachedUin = uin;
                                    sLastUpdateTime = System.currentTimeMillis();
                                    // 只在首次或值变化时记录日志（避免刷屏）
                                    if (!sUinLogged || isNew) {
                                        sUinLogged = true;
                                        debugLog(TAG + ": ✓ 捕获uin: " + uin);
                                    }
                                }
                            }
                        }
                    });
                
                debugLog(TAG + ": [getUin] Hook成功: " + className);
                return true;
            } catch (Throwable t) {
                // 继续尝试下一个类
            }
        }
        
        debugLog(TAG + ": [getUin] Hook失败");
        return false;
    }


    // ==================== 缓存读取方法 ====================
    
    public static String getCachedSkey() {
        return sCachedSkey;
    }
    
    public static String getCachedPSkey() {
        return sCachedPSkey;
    }
    
    public static String getCachedUin() {
        return sCachedUin;
    }
    
    /**
     * 获取缓存的p_uid
     */
    public static String getCachedPUid() {
        return sCachedPUid;
    }
    
    /**
     * 手动设置缓存的UIN（用于主动触发场景）
     */
    public static void setCachedUin(String uin) {
        if (uin != null && !uin.isEmpty()) {
            sCachedUin = uin;
            sLastUpdateTime = System.currentTimeMillis();
        }
    }
    
    /**
     * 手动设置缓存的p_uid
     */
    public static void setCachedPUid(String puid) {
        if (puid != null && !puid.isEmpty()) {
            sCachedPUid = puid;
            sLastUpdateTime = System.currentTimeMillis();
        }
    }
    
    /**
     * 手动设置缓存的skey（用于主动触发场景）
     */
    public static void setCachedSkey(String skey) {
        if (skey != null && !skey.isEmpty()) {
            sCachedSkey = skey;
            sLastUpdateTime = System.currentTimeMillis();
        }
    }
    
    /**
     * 手动设置缓存的p_skey（用于主动触发场景）
     */
    public static void setCachedPSkey(String pskey) {
        if (pskey != null && !pskey.isEmpty()) {
            sCachedPSkey = pskey;
            sLastUpdateTime = System.currentTimeMillis();
        }
    }
    
    public static boolean isCachePossiblyExpired() {
        if (sLastUpdateTime == 0) {
            return true;
        }
        return System.currentTimeMillis() - sLastUpdateTime > CACHE_EXPIRE_MS;
    }
    
    public static long getLastUpdateTime() {
        return sLastUpdateTime;
    }
    
    public static boolean isCacheValid() {
        return sCachedSkey != null && !sCachedSkey.isEmpty()
            && sCachedPSkey != null && !sCachedPSkey.isEmpty()
            && sCachedUin != null && !sCachedUin.isEmpty();
    }
    
    public static String getCookieSource() {
        StringBuilder sb = new StringBuilder();
        sb.append("Memory Cache Status:\n");
        sb.append("  skey: ").append(sCachedSkey != null ? "✓" : "✗").append("\n");
        sb.append("  p_skey: ").append(sCachedPSkey != null ? "✓" : "✗").append("\n");
        sb.append("  uin: ").append(sCachedUin != null ? "✓" : "✗").append("\n");
        sb.append("  p_uid: ").append(sCachedPUid != null ? "✓" : "✗").append("\n");
        
        if (sLastUpdateTime > 0) {
            long ageMinutes = (System.currentTimeMillis() - sLastUpdateTime) / 60000;
            sb.append("  Last update: ").append(ageMinutes).append(" minutes ago");
            if (isCachePossiblyExpired()) {
                sb.append(" (possibly expired)");
            }
        } else {
            sb.append("  Last update: never");
        }
        
        return sb.toString();
    }
    
    public static void clearCache() {
        sCachedSkey = null;
        sCachedPSkey = null;
        sCachedUin = null;
        sCachedPUid = null;
        sLastUpdateTime = 0;
        sSkeyLogged = false;
        sPskeyLogged = false;
        sUinLogged = false;
    }
}
