package top.galqq.utils;

import de.robv.android.xposed.XposedBridge;
import top.galqq.config.ConfigManager;

/**
 * 统一的日志工具类
 * 所有调试日志都应该通过这个类输出，以便统一控制
 */
public class LogHelper {
    
    /**
     * 输出调试日志（受配置开关控制）
     * 只有在启用详细日志时才会输出
     */
    public static void debug(String tag, String message) {
        try {
            if (ConfigManager.isVerboseLogEnabled()) {
                XposedBridge.log(tag + ": " + message);
            }
        } catch (Throwable ignored) {
            // ConfigManager 未初始化时忽略
        }
    }
    
    /**
     * 输出调试日志（受配置开关控制）
     */
    public static void debug(String message) {
        try {
            if (ConfigManager.isVerboseLogEnabled()) {
                XposedBridge.log(message);
            }
        } catch (Throwable ignored) {
            // ConfigManager 未初始化时忽略
        }
    }
    
    /**
     * 输出调试异常（受配置开关控制）
     */
    public static void debug(Throwable t) {
        try {
            if (ConfigManager.isVerboseLogEnabled()) {
                XposedBridge.log(t);
            }
        } catch (Throwable ignored) {
            // ConfigManager 未初始化时忽略
        }
    }
    
    /**
     * 输出重要日志（始终输出，不受配置开关控制）
     * 用于关键的初始化信息、错误信息等
     */
    public static void log(String tag, String message) {
        XposedBridge.log(tag + ": " + message);
    }
    
    /**
     * 输出重要日志（始终输出）
     */
    public static void log(String message) {
        XposedBridge.log(message);
    }
    
    /**
     * 输出异常（始终输出）
     */
    public static void log(Throwable t) {
        XposedBridge.log(t);
    }
    
    /**
     * 输出错误日志（始终输出）
     */
    public static void error(String tag, String message) {
        XposedBridge.log(tag + ": ❌ " + message);
    }
    
    /**
     * 输出错误日志（始终输出）
     */
    public static void error(String tag, String message, Throwable t) {
        XposedBridge.log(tag + ": ❌ " + message + ": " + t.getMessage());
        XposedBridge.log(t);
    }
}
