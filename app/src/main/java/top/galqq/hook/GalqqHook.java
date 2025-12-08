package top.galqq.hook;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import top.galqq.lifecycle.Parasitics;
import top.galqq.utils.HostInfo;
import top.galqq.utils.MessageSendTracker;

import de.robv.android.xposed.IXposedHookZygoteInit;

public class GalqqHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    
    private static final String TAG = "GalQQ";
    
    /**
     * 调试日志输出（受配置开关控制）
     * 在ConfigManager初始化之前调用时会静默忽略
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
     * 错误日志（始终输出，用于关键错误）
     */
    private static void errorLog(String message) {
        XposedBridge.log(message);
    }
    
    private static void errorLog(Throwable t) {
        XposedBridge.log(t);
    }
    
    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        Parasitics.setModulePath(startupParam.modulePath);
    }
    
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.tencent.mobileqq".equals(lpparam.packageName)) {
            return;
        }
        
        debugLog(TAG + ": Hooking QQ " + lpparam.packageName);
        
        try {
            // 初始化 CookieHookManager（需要在其他Hook之前）
            debugLog(TAG + ": 正在初始化CookieHookManager...");
            try {
                CookieHookManager.initHooks(lpparam);
                debugLog(TAG + ": CookieHookManager初始化完成");
            } catch (Throwable t) {
                errorLog(TAG + ": CookieHookManager初始化失败: " + t.getMessage());
                errorLog(t);
            }
            
            // 初始化 Hooks
            MessageInterceptor.init(lpparam.classLoader);
            SettingsInterceptor.init(lpparam.classLoader);
            
            XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate", Application.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Application app = (Application) param.args[0];
                    HostInfo.init(app);
                    Parasitics.initForStubActivity(app);
                    
                    // 初始化 ConfigManager（必须在 MessageSendTracker 之前）
                    try {
                        top.galqq.config.ConfigManager.init(app);
                        debugLog(TAG + ": ConfigManager 初始化完成");
                    } catch (Throwable t) {
                        errorLog(TAG + ": ConfigManager 初始化失败: " + t.getMessage());
                        // 不抛出异常，继续执行
                    }
                    
                    // 启动消息发送追踪
                    debugLog(TAG + ": 正在启动消息追踪...");
                    try {
                        MessageSendTracker.startTracking(app);
                        debugLog(TAG + ": 消息追踪启动完成");
                    } catch (Throwable t) {
                        errorLog(TAG + ": 消息追踪启动失败: " + t.getMessage());
                        errorLog(t);
                    }
                    
                    // 【DEBUG】分析 AIOElementType 子类，用于发现引用回复相关类型
                    debugLog(TAG + ": 正在分析 AIOElementType 子类...");
                    try {
                        top.galqq.utils.SendMessageHelper.analyzeAIOElementTypes(app.getClassLoader());
                        debugLog(TAG + ": AIOElementType 分析完成");
                    } catch (Throwable t) {
                        errorLog(TAG + ": AIOElementType 分析失败: " + t.getMessage());
                        errorLog(t);
                    }
                    
                    // 初始化 RkeyHook（用于获取图片下载的rkey）
                    errorLog(TAG + ": 正在初始化 RkeyHook...");
                    try {
                        RkeyHook.init(app.getClassLoader());
                        errorLog(TAG + ": RkeyHook 初始化完成");
                    } catch (Throwable t) {
                        errorLog(TAG + ": RkeyHook 初始化失败: " + t.getMessage());
                        errorLog(t);
                    }
                }
            });

            debugLog(TAG + ": Hooks initialized successfully");
        } catch (Throwable t) {
            errorLog(TAG + ": Failed to initialize hooks: " + t.getMessage());
            errorLog(t);
        }
    }
}
