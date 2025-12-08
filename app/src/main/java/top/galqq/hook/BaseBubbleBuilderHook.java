/*
 * GalQQ - An Xposed module for QQ
 * Copyright (C) 2024 GalQQ contributors
 * 
 * This software is opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */

package top.galqq.hook;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import top.galqq.utils.QQNTUtils;

/**
 * BaseBubbleBuilderHook - 聊天消息视图装饰器系统
 * 完全模仿QAuxiliary的实现模式
 * 
 * 核心特性：
 * - 装饰器模式支持功能模块热插拔
 * - 双架构支持（QQNT和传统QQ）
 * - 高频调用优化（每秒约68次调用）
 * - 反射缓存和异常隔离
 * - 延迟处理避免阻塞主线程
 */
public class BaseBubbleBuilderHook {
    
    private static final String TAG = "GalQQ.BaseBubbleBuilderHook";
    
    // 注册装饰器列表 - 这些Hook在UI线程中以极高频率调用
    // 峰值频率：每秒约68次调用
    // 注意：CACHE REFLECTION METHODS AND FIELDS FOR BETTER PERFORMANCE
    private static final OnBubbleBuilder[] decorators = new OnBubbleBuilder[]{
        new MessageOptionBarDecorator()  // 消息选项条装饰器
    };
    
    // 反射缓存 - 避免重复查找
    private static Method getHostViewMethod;
    private static Method getMsgRecordMethod;
    private static Method handleUIStateMethod;
    private static Method bindMethod;
    private static Method getViewMethod;
    
    /**
     * 初始化Hook - 主入口点
     */
    public static void init(ClassLoader classLoader) {
        try {
            XposedBridge.log(TAG + ": Starting BaseBubbleBuilderHook initialization");
            
            if (QQNTUtils.isQQNT(classLoader)) {
                XposedBridge.log(TAG + ": Detected QQNT architecture, using NT hook strategy");
                hookQQNT(classLoader);
            } else {
                XposedBridge.log(TAG + ": Detected legacy QQ architecture, using legacy hook strategy");
                hookLegacy(classLoader);
            }
            
            XposedBridge.log(TAG + ": BaseBubbleBuilderHook initialization completed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to initialize BaseBubbleBuilderHook: " + t.getMessage());
            XposedBridge.log(t);
        }
    }
    
    /**
     * QQNT架构Hook策略
     * 模仿QAuxiliary的NT版本处理
     */
    private static void hookQQNT(ClassLoader classLoader) {
        try {
            // 加载关键类
            Class<?> kAIOBubbleMsgItemVB = Class.forName(
                "com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB",
                false,
                classLoader
            );
            
            Class<?> kAIOMsgItem = Class.forName(
                "com.tencent.mobileqq.aio.msg.AIOMsgItem",
                false,
                classLoader
            );
            
            // 缓存反射方法
            getMsgRecordMethod = kAIOMsgItem.getMethod("getMsgRecord");
            getHostViewMethod = findGetHostViewMethod(kAIOBubbleMsgItemVB);
            
            if (getHostViewMethod == null) {
                XposedBridge.log(TAG + ": Failed to find getHostView method");
                return;
            }
            
            XposedBridge.log(TAG + ": Found getHostView method: " + getHostViewMethod.getName());
            
            // 尝试hook handleUIState方法（新版本QQNT）
            handleUIStateMethod = findHandleUIStateMethod(kAIOBubbleMsgItemVB);
            if (handleUIStateMethod != null) {
                XposedBridge.log(TAG + ": Found handleUIState method, using new NT strategy");
                hookHandleUIState(kAIOBubbleMsgItemVB);
                return;
            }
            
            // 回退到bind方法（旧版本QQNT）
            bindMethod = findBindMethod(kAIOBubbleMsgItemVB, kAIOMsgItem);
            if (bindMethod != null) {
                XposedBridge.log(TAG + ": Found bind method, using legacy NT strategy");
                hookBindMethod(kAIOBubbleMsgItemVB);
                return;
            }
            
            XposedBridge.log(TAG + ": Failed to find suitable hook point for QQNT");
            
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error in hookQQNT: " + t.getMessage());
            XposedBridge.log(t);
        }
    }
    
    /**
     * 传统QQ架构Hook策略
     * 模仿QAuxiliary的非NT版本处理
     */
    private static void hookLegacy(ClassLoader classLoader) {
        try {
            Class<?> kBaseBubbleBuilder = Class.forName(
                "com.tencent.mobileqq.activity.aio.BaseBubbleBuilder",
                false,
                classLoader
            );
            
            // 查找getView方法
            getViewMethod = findGetViewMethod(kBaseBubbleBuilder);
            if (getViewMethod == null) {
                XposedBridge.log(TAG + ": Failed to find getView method in BaseBubbleBuilder");
                return;
            }
            
            XposedBridge.log(TAG + ": Found getView method: " + getViewMethod.getName());
            
            // Hook getView方法
            Class<?>[] paramTypes = getViewMethod.getParameterTypes();
            Object[] hookArgs = new Object[paramTypes.length + 1];
            
            // 复制参数类型
            System.arraycopy(paramTypes, 0, hookArgs, 0, paramTypes.length);
            
            // 添加hook回调
            hookArgs[paramTypes.length] = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        // 获取返回值（根视图）
                        View result = (View) param.getResult();
                        if (result == null || !(result instanceof ViewGroup)) {
                            return;
                        }
                        
                        ViewGroup rootView = (ViewGroup) result;
                        
                        // 获取消息对象
                        Object chatMessage = param.args[2]; // 第三个参数通常是消息对象
                        
                        // 调用所有装饰器
                        for (OnBubbleBuilder decorator : decorators) {
                            try {
                                decorator.onGetView(rootView, chatMessage, param);
                            } catch (Exception e) {
                                // 异常隔离 - 一个装饰器出错不影响其他装饰器
                                XposedBridge.log(TAG + ": Decorator error in onGetView: " + e.getMessage());
                            }
                        }
                        
                    } catch (Exception e) {
                        XposedBridge.log(TAG + ": Error in legacy hook: " + e.getMessage());
                    }
                }
            };
            
            // 调用findAndHookMethod
            XposedHelpers.findAndHookMethod(kBaseBubbleBuilder, getViewMethod.getName(), hookArgs);
            
            XposedBridge.log(TAG + ": Successfully hooked legacy BaseBubbleBuilder");
            
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error in hookLegacy: " + t.getMessage());
            XposedBridge.log(t);
        }
    }
    
    /**
     * Hook handleUIState方法（QQNT新版本）
     */
    private static void hookHandleUIState(Class<?> kAIOBubbleMsgItemVB) {
        try {
            // 调试信息
            XposedBridge.log(TAG + ": handleUIStateMethod = " + handleUIStateMethod);
            if (handleUIStateMethod != null) {
                Class<?>[] paramTypes = handleUIStateMethod.getParameterTypes();
                XposedBridge.log(TAG + ": Parameter types length = " + paramTypes.length);
                for (int i = 0; i < paramTypes.length; i++) {
                    XposedBridge.log(TAG + ": Param " + i + " = " + paramTypes[i]);
                }
            }
            
            Class<?>[] paramTypes = handleUIStateMethod.getParameterTypes();
            Object[] hookArgs = new Object[paramTypes.length + 1];
            
            // 复制参数类型
            for (int i = 0; i < paramTypes.length; i++) {
                if (paramTypes[i] == null) {
                    XposedBridge.log(TAG + ": Warning: paramTypes[" + i + "] is null, using Object.class");
                    hookArgs[i] = Object.class;
                } else {
                    hookArgs[i] = paramTypes[i];
                }
            }
            
            // 添加hook回调
            hookArgs[paramTypes.length] = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object uiState = param.args[0];
                        if (uiState == null) return;
                        
                        // 获取消息项
                        Object msgItem = getMsgItemFromUIState(uiState);
                        if (msgItem == null) return;
                        
                        // 获取消息记录
                        Object msgRecord = getMsgRecordMethod.invoke(msgItem);
                        if (msgRecord == null) return;
                        
                        // 获取根视图
                        ViewGroup rootView = (ViewGroup) getHostViewMethod.invoke(param.thisObject);
                        if (rootView == null) return;
                        
                        // 调用所有装饰器
                        for (OnBubbleBuilder decorator : decorators) {
                            try {
                                decorator.onGetViewNt(rootView, msgRecord, param);
                            } catch (Exception e) {
                                // 异常隔离
                                XposedBridge.log(TAG + ": Decorator error in onGetViewNt: " + e.getMessage());
                            }
                        }
                        
                    } catch (Exception e) {
                        XposedBridge.log(TAG + ": Error in handleUIState hook: " + e.getMessage());
                    }
                }
            };
            
            // 调用findAndHookMethod
            XposedHelpers.findAndHookMethod(kAIOBubbleMsgItemVB, handleUIStateMethod.getName(), hookArgs);
            
            XposedBridge.log(TAG + ": Successfully hooked handleUIState method");
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error in hookHandleUIState: " + e.getMessage());
            XposedBridge.log(e);
        }
    }
    
    /**
     * Hook bind方法（QQNT旧版本）
     */
    private static void hookBindMethod(Class<?> kAIOBubbleMsgItemVB) {
        try {
            // 调试信息
            XposedBridge.log(TAG + ": bindMethod = " + bindMethod);
            if (bindMethod != null) {
                Class<?>[] paramTypes = bindMethod.getParameterTypes();
                XposedBridge.log(TAG + ": Bind parameter types length = " + paramTypes.length);
                for (int i = 0; i < paramTypes.length; i++) {
                    XposedBridge.log(TAG + ": Bind param " + i + " = " + paramTypes[i]);
                }
            }
            
            Class<?>[] paramTypes = bindMethod.getParameterTypes();
            Object[] hookArgs = new Object[paramTypes.length + 1];
            
            // 复制参数类型
            for (int i = 0; i < paramTypes.length; i++) {
                if (paramTypes[i] == null) {
                    XposedBridge.log(TAG + ": Warning: bind paramTypes[" + i + "] is null, using Object.class");
                    hookArgs[i] = Object.class;
                } else {
                    hookArgs[i] = paramTypes[i];
                }
            }
            
            // 添加hook回调
            hookArgs[paramTypes.length] = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        // 获取消息项（第二个参数）
                        Object msgItem = param.args[1];
                        if (msgItem == null) return;
                        
                        // 获取消息记录
                        Object msgRecord = getMsgRecordMethod.invoke(msgItem);
                        if (msgRecord == null) return;
                        
                        // 获取根视图
                        ViewGroup rootView = (ViewGroup) getHostViewMethod.invoke(param.thisObject);
                        if (rootView == null) return;
                        
                        // 调用所有装饰器
                        for (OnBubbleBuilder decorator : decorators) {
                            try {
                                decorator.onGetViewNt(rootView, msgRecord, param);
                            } catch (Exception e) {
                                // 异常隔离
                                XposedBridge.log(TAG + ": Decorator error in onGetViewNt: " + e.getMessage());
                            }
                        }
                        
                    } catch (Exception e) {
                        XposedBridge.log(TAG + ": Error in bind hook: " + e.getMessage());
                    }
                }
            };
            
            // 调用findAndHookMethod
            XposedHelpers.findAndHookMethod(kAIOBubbleMsgItemVB, bindMethod.getName(), hookArgs);
            
            XposedBridge.log(TAG + ": Successfully hooked bind method");
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error in hookBindMethod: " + e.getMessage());
            XposedBridge.log(e);
        }
    }
    
    /**
     * 从UIState获取消息项
     * 模仿QAuxiliary的uiState.invokeMethod(\"b\")实现
     */
    private static Object getMsgItemFromUIState(Object uiState) {
        try {
            // 首先尝试调用b()方法（QAuxiliary的方式）
            Method bMethod = null;
            for (Method method : uiState.getClass().getDeclaredMethods()) {
                if ("b".equals(method.getName()) && method.getParameterCount() == 0) {
                    bMethod = method;
                    bMethod.setAccessible(true);
                    break;
                }
            }
            
            if (bMethod != null) {
                return bMethod.invoke(uiState);
            }
            
            // 回退：查找返回AIOMsgItem的方法
            Class<?> kAIOMsgItem = Class.forName("com.tencent.mobileqq.aio.msg.AIOMsgItem");
            for (Method method : uiState.getClass().getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && 
                    method.getReturnType().isAssignableFrom(kAIOMsgItem)) {
                    method.setAccessible(true);
                    return method.invoke(uiState);
                }
            }
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to get msgItem from UIState: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 查找getHostView方法
     */
    private static Method findGetHostViewMethod(Class<?> clazz) {
        try {
            // 首先查找公共的无参方法，返回View类型
            for (Method method : clazz.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) &&
                    method.getParameterCount() == 0 &&
                    View.class.isAssignableFrom(method.getReturnType())) {
                    return method;
                }
            }
            
            // 如果没找到，尝试查找名为getHostView的方法
            return clazz.getMethod("getHostView");
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to find getHostView method: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 查找handleUIState方法
     */
    private static Method findHandleUIStateMethod(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if ("handleUIState".equals(method.getName())) {
                return method;
            }
        }
        return null;
    }
    
    /**
     * 查找bind方法
     */
    private static Method findBindMethod(Class<?> clazz, Class<?> kAIOMsgItem) {
        for (Method method : clazz.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (method.getReturnType() == Void.TYPE && params.length == 4) {
                // 检查参数类型：int, AIOMsgItem或其父类, List, Bundle
                if (params[0] == Integer.TYPE &&
                    params[1].isAssignableFrom(kAIOMsgItem) &&
                    params[2] == List.class &&
                    params[3] == android.os.Bundle.class) {
                    return method;
                }
            }
        }
        return null;
    }
    
    /**
     * 查找getView方法（传统QQ）
     */
    private static Method findGetViewMethod(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            // 查找参数为6个，返回View类型的公共方法
            if (method.getReturnType() == View.class &&
                Modifier.isPublic(method.getModifiers()) &&
                params.length == 6 &&
                params[0] == Integer.TYPE &&
                params[1] == Integer.TYPE) {
                return method;
            }
        }
        return null;
    }
}