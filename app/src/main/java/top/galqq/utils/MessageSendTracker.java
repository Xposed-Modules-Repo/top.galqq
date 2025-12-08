package top.galqq.utils;

import android.content.Context;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 消息发送追踪 - 深入分析消息对象
 */
public class MessageSendTracker {
    private static final String TAG = "GalQQ.MSG_DETAIL";
    private static boolean isTracking = false;
    
    /**
     * 调试日志输出（受 gal_debug_hook_log 配置开关控制）
     * 安全检查 ConfigManager 是否已初始化
     */
    private static void debugLog(String message) {
        try {
            if (top.galqq.config.ConfigManager.isDebugHookLogEnabled()) {
                de.robv.android.xposed.XposedBridge.log(message);
            }
        } catch (Throwable ignored) {
            // ConfigManager 未初始化时忽略
        }
    }
    
    /**
     * 调试日志输出异常（受 gal_debug_hook_log 配置开关控制）
     * 安全检查 ConfigManager 是否已初始化
     */
    private static void debugLog(Throwable t) {
        try {
            if (top.galqq.config.ConfigManager.isDebugHookLogEnabled()) {
                de.robv.android.xposed.XposedBridge.log(t);
            }
        } catch (Throwable ignored) {
            // ConfigManager 未初始化时忽略
        }
    }

    public static void startTracking(Context context) {
        if (isTracking) return;
        
        debugLog(TAG + ": ========== 启动消息内容追踪 ==========");
        isTracking = true;
        
        try {
            hookAIOSendMsgVMDelegate(context);
            hookToSaveVMDelegateInstance(context);  // Hook来保存实例
            debugLog(TAG + ": ========== 追踪已就绪 ==========");
        } catch (Throwable t) {
            debugLog(TAG + ": 启动失败");
            debugLog(t);
        }
    }
    
    /**
     * Hook AIOSendMsgVMDelegate.n0 来保存实例
     */
    /**
     * Hook AIOSendMsgVMDelegate.n0 和 构造函数 来保存实例
     */
    /**
     * Hook AIOSendMsgVMDelegate.n0 和 构造函数 来保存实例
     */
    private static void hookToSaveVMDelegateInstance(Context context) {
        try {
            Class<?> vmDelegateClass = XposedHelpers.findClass(
                "com.tencent.mobileqq.aio.input.sendmsg.AIOSendMsgVMDelegate", 
                context.getClassLoader()
            );
            
            // Hook 构造函数，在对象创建时就保存实例
            XposedBridge.hookAllConstructors(vmDelegateClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    //debugLog(TAG + ": AIOSendMsgVMDelegate构造函数被调用，保存实例");
                    SendMessageHelper.setAIOSendMsgVMDelegate(param.thisObject);
                }
            });
            
            // 动态查找并Hook发送方法 (替代硬编码的 "n0")
            Method sendMethod = null;
            for (Method method : vmDelegateClass.getDeclaredMethods()) {
                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length == 4 && 
                    paramTypes[0] == java.util.List.class && 
                    paramTypes[1] == android.os.Bundle.class && 
                    paramTypes[2] == Long.class && 
                    paramTypes[3] == String.class) {
                    sendMethod = method;
                    break;
                }
            }
            
            if (sendMethod != null) {
                XposedBridge.hookMethod(sendMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // 保存AIOSendMsgVMDelegate实例供SendMessageHelper使用
                        SendMessageHelper.setAIOSendMsgVMDelegate(param.thisObject);
                    }
                });
                //debugLog(TAG + ": 动态Hook发送方法成功: " + sendMethod.getName());
            } else {
                debugLog(TAG + ": 未找到符合特征的发送方法，仅依赖构造函数Hook");
            }
            
        } catch (Throwable t) {
            debugLog(TAG + ": Hook保存VM Delegate实例失败: " + t.getMessage());
        }
    }
    
    /**
     * Hook AIOSendMsgVMDelegate - 重点分析n0/l0方法
     */
    private static void hookAIOSendMsgVMDelegate(Context context) {
        try {
            Class<?> cls = XposedHelpers.findClass(
                "com.tencent.mobileqq.aio.input.sendmsg.AIOSendMsgVMDelegate", 
                context.getClassLoader()
            );
            
            for (Method method : cls.getDeclaredMethods()) {
                final String methodName = method.getName();
                final Class<?>[] paramTypes = method.getParameterTypes();
                
                // 只Hook n0, l0, f0 这些关键方法
                if (!methodName.equals("n0") && !methodName.equals("l0") && !methodName.equals("f0")) {
                    continue;
                }
                
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            debugLog(TAG + ": ═══════════════════════════════════");
                            debugLog(TAG + ": [AIOSendMsgVMDelegate." + methodName + "] (" + param.args.length + "参数)");
                            debugLog(TAG + ": 参数类型: " + Arrays.toString(paramTypes));
                            
                            for (int i = 0; i < param.args.length; i++) {
                                Object arg = param.args[i];
                                if (arg == null) {
                                    debugLog(TAG + ": [" + i + "] null");
                                    continue;
                                }
                                
                                String className = arg.getClass().getName();
                                debugLog(TAG + ": [" + i + "] " + className);
                                
                                // 如果是ArrayList，深入分析
                                if (arg instanceof java.util.List) {
                                    java.util.List<?> list = (java.util.List<?>) arg;
                                    debugLog(TAG + ":   → List大小: " + list.size());
                                    
                                    for (int j = 0; j < list.size(); j++) {
                                        Object item = list.get(j);
                                        if (item != null) {
                                            debugLog(TAG + ":   → [" + j + "] " + item.getClass().getName());
                                            
                                            // 如果是msg.data.a，深入分析其字段
                                            if (item.getClass().getName().contains("msg.data.a")) {
                                                analyzeMsgDataObject(item);
                                            }
                                        }
                                    }
                                }
                                // 如果是Bundle
                                else if (className.contains("Bundle")) {
                                    android.os.Bundle bundle = (android.os.Bundle) arg;
                                    String inputText = bundle.getString("input_text");
                                    if (inputText != null) {
                                        debugLog(TAG + ":   ★★★ 消息文本: " + inputText + " ★★★");
                                    }
                                }
                                // 其他对象
                                else {
                                    String str = arg.toString();
                                    if (str.length() > 200) str = str.substring(0, 200) + "...";
                                    debugLog(TAG + ":   = " + str);
                                }
                            }
                            debugLog(TAG + ": ═══════════════════════════════════");
                            
                            // 【提取发送的消息并添加到上下文】- 只在n0方法中执行
                            if (methodName.equals("n0")) {
                                try {
                                    // 从元素列表中提取文本内容和引用内容
                                    java.util.List<?> elementsList = (java.util.List<?>) param.args[0];
                                    if (elementsList != null && !elementsList.isEmpty()) {
                                        StringBuilder messageText = new StringBuilder();
                                        String replyContent = null;
                                        String replyNick = null;
                                        
                                        for (Object element : elementsList) {
                                            try {
                                                // 提取 TextElement (字段c)
                                                Object textElement = null;
                                                try {
                                                    textElement = XposedHelpers.getObjectField(element, "c");
                                                } catch (Throwable ignored) {}
                                                
                                                if (textElement != null) {
                                                    // 提取content字段
                                                    try {
                                                        Object contentObj = XposedHelpers.getObjectField(textElement, "content");
                                                        if (contentObj != null) {
                                                            String content = String.valueOf(contentObj);
                                                            if (!content.trim().isEmpty()) {
                                                                messageText.append(content);
                                                            }
                                                        }
                                                    } catch (Throwable ignored) {}
                                                }
                                                
                                                // 提取 ReplyElement (字段h) - 增强日志用于分析引用回复结构
                                                if (replyContent == null) {  // 只提取第一个
                                                    try {
                                                        Object replyElement = XposedHelpers.getObjectField(element, "h");
                                                        if (replyElement != null) {
                                                            debugLog(TAG + ": ★★★ 发现 ReplyElement ★★★");
                                                            debugLog(TAG + ": ReplyElement class: " + replyElement.getClass().getName());
                                                            
                                                            // 打印 ReplyElement 的所有字段（用于分析引用回复结构）
                                                            for (Field replyField : replyElement.getClass().getDeclaredFields()) {
                                                                try {
                                                                    replyField.setAccessible(true);
                                                                    Object fieldVal = replyField.get(replyElement);
                                                                    String fieldType = replyField.getType().getSimpleName();
                                                                    String valStr = fieldVal != null ? fieldVal.toString() : "null";
                                                                    if (valStr.length() > 150) valStr = valStr.substring(0, 150) + "...";
                                                                    debugLog(TAG + ":   ReplyElement." + replyField.getName() + " (" + fieldType + "): " + valStr);
                                                                } catch (Throwable ignored) {}
                                                            }
                                                            
                                                            // 提取引用的内容
                                                            try {
                                                                Object replyContentObj = XposedHelpers.getObjectField(replyElement, "replyContent");
                                                                if (replyContentObj != null) {
                                                                    replyContent = String.valueOf(replyContentObj);
                                                                }
                                                            } catch (Throwable ignored) {}
                                                            
                                                            // 提取引用的昵称
                                                            try {
                                                                Object replyNickObj = XposedHelpers.getObjectField(replyElement, "replyNick");
                                                                if (replyNickObj != null) {
                                                                    replyNick = String.valueOf(replyNickObj);
                                                                }
                                                            } catch (Throwable ignored) {}
                                                        }
                                                    } catch (Throwable ignored) {}
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                        
                                        String finalText = messageText.toString().trim();
                                        
                                        // 获取peerUin
                                        String peerUin = null;
                                        try {
                                            if (param.args.length > 2 && param.args[2] != null) {
                                                peerUin = String.valueOf(param.args[2]);
                                            }
                                        } catch (Throwable ignored) {}
                                        
                                        // 先添加引用的消息（如果有）
                                        if (peerUin != null && replyContent != null && !replyContent.trim().isEmpty()) {
                                            MessageContextManager.addMessage(
                                                peerUin,
                                                replyNick != null ? replyNick : "引用消息",
                                                "[引用] " + replyContent,
                                                false,  // 被引用的不是自己发的
                                                null,
                                                System.currentTimeMillis() - 1000  // 时间稍早
                                            );
                                            debugLog(TAG + ": ✓ 已将引用消息添加到上下文: " + replyContent.substring(0, Math.min(30, replyContent.length())));
                                        }
                                        
                                        // 再添加自己发送的消息
                                        if (peerUin != null && !finalText.isEmpty()) {
                                            MessageContextManager.addMessage(
                                                peerUin,
                                                "我",
                                                finalText,
                                                true,  // isSelf = true
                                                null,
                                                System.currentTimeMillis()
                                            );
                                            debugLog(TAG + ": ✓ 已将发送的消息添加到上下文: [" + peerUin + "] " + finalText);
                                        }
                                    }
                                } catch (Throwable t) {
                                    debugLog(TAG + ": 提取发送消息失败: " + t.getMessage());
                                    debugLog(t);
                                }
                            }
                        }
                    });
                    debugLog(TAG + ": Hook " + methodName + " 成功");
                } catch (Throwable t) {
                    debugLog(TAG + ": Hook " + methodName + " 失败: " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            debugLog(TAG + ": Hook AIOSendMsgVMDelegate失败: " + t.getMessage());
        }
    }
    
    /**
     * 深入分析 com.tencent.mobileqq.aio.msg.data.a 对象
     */
    private static void analyzeMsgDataObject(Object obj) {
        try {
            debugLog(TAG + ":   ┌─ 分析msg.data.a对象 ─┐");
            
            Class<?> cls = obj.getClass();
            
            // 列出所有字段
            for (Field field : cls.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    
                    String fieldName = field.getName();
                    String typeName = field.getType().getSimpleName();
                    
                    if (value != null) {
                        String valStr = value.toString();
                        if (valStr.length() > 150) valStr = valStr.substring(0, 150) + "...";
                        debugLog(TAG + ":   │ " + fieldName + " (" + typeName + ") = " + valStr);
                        
                        // 如果是List，展开
                        if (value instanceof java.util.List) {
                            java.util.List<?> list = (java.util.List<?>) value;
                            debugLog(TAG + ":   │   List大小: " + list.size());
                            for (int i = 0; i < Math.min(3, list.size()); i++) {
                                Object item = list.get(i);
                                if (item != null) {
                                    debugLog(TAG + ":   │   [" + i + "] " + item.getClass().getSimpleName());
                                }
                            }
                        }
                    } else {
                        debugLog(TAG + ":   │ " + fieldName + " (" + typeName + ") = null");
                    }
                } catch (Throwable ignored) {}
            }
            
            debugLog(TAG + ":   └─────────────────────┘");
        } catch (Throwable t) {
            debugLog(TAG + ":   分析失败: " + t.getMessage());
        }
    }
}
