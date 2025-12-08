package top.galqq.utils;

import android.content.Context;
import android.os.Bundle;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import top.galqq.config.ConfigManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * QQNT 消息发送助手 - 自动降级策略
 * 先尝试新版本方法，失败后自动回退到旧版本
 */
public class SendMessageHelper {
    private static final String TAG = "GalQQ.SendMessage";
    
    // 保存AIOSendMsgVMDelegate实例
    private static Object sAIOSendMsgVMDelegate = null;
    
    /**
     * 调试日志输出（受配置开关控制）
     * 安全检查 ConfigManager 是否已初始化
     */
    private static void debugLog(String message) {
        try {
            if (ConfigManager.isDebugHookLogEnabled()) {
                XposedBridge.log(TAG + ": " + message);
            }
        } catch (Throwable ignored) {
            // ConfigManager 未初始化时忽略
        }
    }
    
    /**
     * 调试日志输出异常（受配置开关控制）
     * 安全检查 ConfigManager 是否已初始化
     */
    private static void debugLog(Throwable t) {
        try {
            if (ConfigManager.isDebugHookLogEnabled()) {
                XposedBridge.log(t);
            }
        } catch (Throwable ignored) {
            // ConfigManager 未初始化时忽略
        }
    }
    
    public static void setAIOSendMsgVMDelegate(Object vmDelegate) {
        sAIOSendMsgVMDelegate = vmDelegate;
        debugLog("保存AIOSendMsgVMDelegate实例");
    }
    
    public static void sendMessageNT(Context context, Object msgRecord, String textToSend) {
        try {
            Object peerUid = XposedHelpers.getObjectField(msgRecord, "peerUid");
            String peerUidStr = String.valueOf(peerUid);
            
            debugLog("sendMessageNT called - peerUid=" + peerUidStr + ", text=" + textToSend);
            
            boolean success = sendTextMessage(context, peerUidStr, textToSend);
            if (!success) {
                android.widget.Toast.makeText(context, "发送失败", android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            debugLog("sendMessageNT 失败");
            debugLog(t);
            android.widget.Toast.makeText(context, "发送失败: " + t.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }
    
    public static boolean sendTextMessage(Context context, String peerUid, String messageText) {
        try {
            debugLog("准备发送消息到 " + peerUid);
            debugLog("消息内容: " + messageText);
            
            ClassLoader classLoader = context.getClassLoader();
            
            // 1. 创建 TextElement 对象
            Object textElement = createTextElement(classLoader, messageText);
            if (textElement == null) {
                debugLog("创建 TextElement 失败");
                // 尝试 9.1.25 版本的降级方法
                return sendTextMessageV9125(context, classLoader, peerUid, messageText);
            }
            
            // 2. 创建 msg.data.a 对象（自动降级策略）
            Object msgData = createMsgDataWithTextAutoFallback(classLoader, textElement);
            if (msgData == null) {
                debugLog("创建 msgData 失败，尝试 9.1.25 版本降级");
                // 尝试 9.1.25 版本的降级方法
                return sendTextMessageV9125(context, classLoader, peerUid, messageText);
            }
            
            // 3. 创建消息列表
            List<Object> msgDataList = new ArrayList<>();
            msgDataList.add(msgData);
            
            // 4. 创建Bundle
            Bundle bundle = new Bundle();
            bundle.putString("input_text", messageText);
            bundle.putBoolean("from_send_btn", true);
            bundle.putInt("clearInputStatus", 1);
            
            // 5. 获取 AIOSendMsgVMDelegate 实例
            Object vmDelegate = getAIOSendMsgVMDelegate(context);
            if (vmDelegate == null) {
                debugLog("获取 vmDelegate 失败");
                return false;
            }
            
            // 6. 动态查找发送方法
            Method sendMethod = findSendMethod(vmDelegate.getClass());
            if (sendMethod == null) {
                debugLog("未找到符合特征的发送方法(List, Bundle, Long, String)，尝试 l0 方法");
                // 尝试 l0 方法 (9.1.25版本)
                Method l0Method = findMethodL0(vmDelegate.getClass());
                if (l0Method != null) {
                    l0Method.setAccessible(true);
                    l0Method.invoke(vmDelegate, msgDataList, bundle, null, null);
                    debugLog("✓ 消息发送成功！(Method: l0)");
                    return true;
                }
                return false;
            }
            
            sendMethod.setAccessible(true);
            sendMethod.invoke(vmDelegate, msgDataList, bundle, null, "");
            
            debugLog("✓ 消息发送成功！(Method: " + sendMethod.getName() + ")");
            return true;
            
        } catch (Throwable t) {
            debugLog("✗ 发送消息失败");
            debugLog(t);
            return false;
        }
    }
    
    /**
     * 9.1.25 版本专用的普通消息发送方法
     * 根据日志分析:
     * - f0方法: f0(List<c$e>, null, null, false, Bundle) - 输入元素列表，ReplyData为null
     * - l0方法: l0(List<msg.a.a>, Bundle, null, null) - 消息数据列表
     */
    private static boolean sendTextMessageV9125(Context context, ClassLoader classLoader, String peerUid, String messageText) {
        try {
            debugLog("[9.1.25普通发送] 开始");
            
            // 获取 vmDelegate
            Object vmDelegate = getAIOSendMsgVMDelegate(context);
            if (vmDelegate == null) {
                debugLog("[9.1.25普通发送] vmDelegate 为空");
                return false;
            }
            
            // 创建 Bundle
            Bundle bundle = new Bundle();
            bundle.putString("input_text", messageText);
            bundle.putBoolean("from_send_btn", true);
            bundle.putInt("clearInputStatus", 1);
            
            // 方案1: 使用 f0 方法 (输入元素列表 + null ReplyData)
            // 根据日志: f0(List<c$e>, null, null, false, Bundle)
            List<Object> inputElements = createInputElementListV9125(classLoader, messageText);
            if (inputElements != null && !inputElements.isEmpty()) {
                Method f0Method = findMethodF0V9125(vmDelegate.getClass());
                if (f0Method != null) {
                    f0Method.setAccessible(true);
                    debugLog("[9.1.25普通发送] 使用 f0 方法: " + Arrays.toString(f0Method.getParameterTypes()));
                    // f0(List<c$e>, null, null, false, Bundle)
                    f0Method.invoke(vmDelegate, inputElements, null, null, false, bundle);
                    debugLog("[9.1.25普通发送] f0 方法成功");
                    return true;
                }
            }
            
            // 方案2: 直接使用 l0 方法 (消息数据列表)
            // 根据日志: l0(List<msg.a.a>, Bundle, null, null)
            Object textMsgData = createMsgDataV9125(classLoader, false, 0, 0, null, messageText);
            if (textMsgData != null) {
                List<Object> msgList = new ArrayList<>();
                msgList.add(textMsgData);
                
                Method l0Method = findMethodL0(vmDelegate.getClass());
                if (l0Method != null) {
                    l0Method.setAccessible(true);
                    debugLog("[9.1.25普通发送] 使用 l0 方法");
                    l0Method.invoke(vmDelegate, msgList, bundle, null, null);
                    debugLog("[9.1.25普通发送] l0 方法成功");
                    return true;
                }
            }
            
            // 方案3: 尝试 n0 方法 (备用)
            if (inputElements != null && !inputElements.isEmpty()) {
                Method n0Method = findMethodN0(vmDelegate.getClass());
                if (n0Method != null) {
                    n0Method.setAccessible(true);
                    debugLog("[9.1.25普通发送] 使用 n0 方法: " + Arrays.toString(n0Method.getParameterTypes()));
                    n0Method.invoke(vmDelegate, inputElements, bundle, null, "");
                    debugLog("[9.1.25普通发送] n0 方法成功");
                    return true;
                }
            }
            
            debugLog("[9.1.25普通发送] 所有方法都失败");
            return false;
            
        } catch (Throwable t) {
            debugLog("[9.1.25普通发送] 异常: " + t.getMessage());
            XposedBridge.log(t);
            return false;
        }
    }
    
    /**
     * 查找 n0 方法 (普通发送方法)
     * 方法签名: n0(List, Bundle, Long?, String?)
     */
    private static Method findMethodN0(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals("n0")) {
                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length == 4 &&
                    paramTypes[0] == List.class &&
                    paramTypes[1] == Bundle.class) {
                    debugLog("找到 n0 方法: " + Arrays.toString(paramTypes));
                    return method;
                }
            }
        }
        return null;
    }
    
    /**
     * 根据参数特征动态查找发送方法
     * 特征: (List, Bundle, Long, String)
     */
    private static Method findSendMethod(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length == 4 && 
                paramTypes[0] == List.class && 
                paramTypes[1] == Bundle.class && 
                paramTypes[2] == Long.class && 
                paramTypes[3] == String.class) {
                return method;
            }
        }
        return null;
    }
    
    private static Object createTextElement(ClassLoader classLoader, String content) {
        try {
            Class<?> textElementClass = XposedHelpers.findClass(
                "com.tencent.qqnt.kernel.nativeinterface.TextElement", classLoader);
            
            Constructor<?> constructor = textElementClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object textElement = constructor.newInstance();
            
            XposedHelpers.setObjectField(textElement, "content", content);
            XposedHelpers.setIntField(textElement, "atType", 0);
            XposedHelpers.setLongField(textElement, "atUid", 0L);
            XposedHelpers.setLongField(textElement, "atTinyId", 0L);
            XposedHelpers.setObjectField(textElement, "atNtUid", "");
            
            debugLog("创建TextElement成功: " + textElement);
            return textElement;
            
        } catch (Throwable t) {
            debugLog("创建TextElement失败: " + t.getMessage());
            XposedBridge.log(t);
            return null;
        }
    }

    // ==================== 自动降级策略 ====================
    
    /**
     * 自动降级策略：先尝试新版本，失败后回退到旧版本
     */
    private static Object createMsgDataWithTextAutoFallback(ClassLoader classLoader, Object textElement) {
        debugLog("[自动降级] 开始创建消息数据");
        
        // 提取 TextElement 字段
        String content = (String) XposedHelpers.getObjectField(textElement, "content");
        int atType = XposedHelpers.getIntField(textElement, "atType");
        long atUid = XposedHelpers.getLongField(textElement, "atUid");
        long atTinyId = XposedHelpers.getLongField(textElement, "atTinyId");
        String atNtUid = (String) XposedHelpers.getObjectField(textElement, "atNtUid");
        
        debugLog("TextElement字段: content=" + content + ", atType=" + atType + 
            ", atUid=" + atUid + ", atTinyId=" + atTinyId + ", atNtUid=" + atNtUid);
        
        // 创建 msg.data.a 实例
        Object msgData = createMsgDataInstance(classLoader);
        if (msgData == null) {
            return null;
        }
        
        // 策略1: 尝试 AIOElementType$i (新版本)
        Object element = tryCreateAIOElementTypeI(classLoader, content, atType, atUid, atTinyId, atNtUid);
        if (element != null) {
            try {
                XposedHelpers.setIntField(msgData, "a", 1);
                XposedHelpers.setObjectField(msgData, "b", element);
                debugLog("[策略1-新版本$i] 成功设置 msgData.a=1, msgData.b=AIOElementType$i");
                return msgData;
            } catch (Throwable t) {
                debugLog("[策略1] 设置字段失败: " + t.getMessage());
            }
        }
        
        // 策略2: 尝试 AIOElementType$h (旧版本) 设置到字段 c
        element = tryCreateAIOElementTypeH(classLoader, content, atType, atUid, atTinyId, atNtUid);
        if (element != null) {
            try {
                XposedHelpers.setIntField(msgData, "a", 1);
                XposedHelpers.setIntField(msgData, "b", 0);
                XposedHelpers.setObjectField(msgData, "c", element);
                debugLog("[策略2-旧版本$h] 成功设置 msgData.a=1, msgData.b=0, msgData.c=AIOElementType$h");
                return msgData;
            } catch (Throwable t) {
                debugLog("[策略2] 设置字段失败: " + t.getMessage());
            }
        }
        
        // 策略3: 尝试 AIOElementType$i 设置到字段 c (QQ 9.2.25 版本)
        // 在这个版本中，msgData.b 是 int 类型，msgData.c 是 AIOElementType$i 类型
        element = tryCreateAIOElementTypeI(classLoader, content, atType, atUid, atTinyId, atNtUid);
        if (element != null) {
            try {
                XposedHelpers.setIntField(msgData, "a", 1);
                XposedHelpers.setObjectField(msgData, "c", element);
                debugLog("[策略3-9.2.25版本] 成功设置 msgData.a=1, msgData.c=AIOElementType$i");
                return msgData;
            } catch (Throwable t) {
                debugLog("[策略3] 设置字段失败: " + t.getMessage());
            }
        }
        
        debugLog("[自动降级] 所有策略都失败");
        return null;
    }
    
    /**
     * 尝试创建 AIOElementType$i (新版本)
     */
    private static Object tryCreateAIOElementTypeI(ClassLoader classLoader, String content, 
            int atType, long atUid, long atTinyId, String atNtUid) {
        try {
            Class<?> iClass = XposedHelpers.findClass(
                "com.tencent.qqnt.aio.msg.element.AIOElementType$i", classLoader);
            
            Constructor<?>[] constructors = iClass.getDeclaredConstructors();
            debugLog("AIOElementType$i 有 " + constructors.length + " 个构造函数");
            
            for (int i = 0; i < constructors.length; i++) {
                Class<?>[] paramTypes = constructors[i].getParameterTypes();
                debugLog("  构造函数[" + i + "]: " + Arrays.toString(paramTypes));
            }
            
            // 按优先级尝试不同构造函数
            for (Constructor<?> constructor : constructors) {
                constructor.setAccessible(true);
                Class<?>[] paramTypes = constructor.getParameterTypes();
                Object result = tryInvokeConstructor(constructor, paramTypes, content, atType, atUid, atTinyId, atNtUid);
                if (result != null) {
                    debugLog("创建AIOElementType$i成功: " + result);
                    return result;
                }
            }
        } catch (XposedHelpers.ClassNotFoundError e) {
            debugLog("AIOElementType$i 类不存在，跳过");
        } catch (Throwable t) {
            debugLog("创建AIOElementType$i异常: " + t.getMessage());
        }
        return null;
    }
    
    /**
     * 尝试创建 AIOElementType$h (旧版本)
     */
    private static Object tryCreateAIOElementTypeH(ClassLoader classLoader, String content, 
            int atType, long atUid, long atTinyId, String atNtUid) {
        try {
            Class<?> hClass = XposedHelpers.findClass(
                "com.tencent.qqnt.aio.msg.element.AIOElementType$h", classLoader);
            
            Constructor<?>[] constructors = hClass.getDeclaredConstructors();
            debugLog("AIOElementType$h 有 " + constructors.length + " 个构造函数");
            
            for (int i = 0; i < constructors.length; i++) {
                Class<?>[] paramTypes = constructors[i].getParameterTypes();
                debugLog("  构造函数[" + i + "]: " + Arrays.toString(paramTypes));
            }
            
            // 按优先级尝试不同构造函数
            for (Constructor<?> constructor : constructors) {
                constructor.setAccessible(true);
                Class<?>[] paramTypes = constructor.getParameterTypes();
                Object result = tryInvokeConstructor(constructor, paramTypes, content, atType, atUid, atTinyId, atNtUid);
                if (result != null) {
                    debugLog("创建AIOElementType$h成功: " + result);
                    return result;
                }
            }
        } catch (XposedHelpers.ClassNotFoundError e) {
            debugLog("AIOElementType$h 类不存在，跳过");
        } catch (Throwable t) {
            debugLog("创建AIOElementType$h异常: " + t.getMessage());
        }
        return null;
    }

    /**
     * 智能调用构造函数 - 根据参数类型自动匹配
     */
    private static Object tryInvokeConstructor(Constructor<?> constructor, Class<?>[] paramTypes,
            String content, int atType, long atUid, long atTinyId, String atNtUid) {
        try {
            int len = paramTypes.length;
            debugLog("尝试调用 " + len + " 参数构造函数");
            
            if (len == 0) {
                // 无参构造函数
                Object obj = constructor.newInstance();
                // 尝试设置字段
                trySetAllFields(obj, content, atType, atUid, atTinyId, atNtUid);
                debugLog("无参构造函数成功，已设置字段");
                return obj;
            }
            
            if (len == 1) {
                if (paramTypes[0] == String.class) {
                    return constructor.newInstance(content);
                }
            }
            
            if (len == 4) {
                // (long, long, String, String) - 根据日志
                if (paramTypes[0] == long.class && paramTypes[1] == long.class 
                    && paramTypes[2] == String.class && paramTypes[3] == String.class) {
                    debugLog("匹配4参数构造函数 (long, long, String, String)");
                    return constructor.newInstance(atUid, atTinyId, content, atNtUid);
                }
                // (String, int, long, long) - 另一种可能
                if (paramTypes[0] == String.class && paramTypes[1] == int.class) {
                    debugLog("匹配4参数构造函数 (String, int, long, long)");
                    return constructor.newInstance(content, atType, atUid, atTinyId);
                }
            }
            
            if (len == 5) {
                // (String, int, long, long, String)
                if (paramTypes[0] == String.class && paramTypes[1] == int.class) {
                    debugLog("匹配5参数构造函数 (String, int, long, long, String)");
                    return constructor.newInstance(content, atType, atUid, atTinyId, atNtUid);
                }
            }
            
            // 通用尝试：根据参数类型智能填充
            Object[] args = new Object[len];
            int stringIdx = 0;
            int longIdx = 0;
            
            for (int i = 0; i < len; i++) {
                Class<?> type = paramTypes[i];
                if (type == String.class) {
                    args[i] = (stringIdx == 0) ? content : atNtUid;
                    stringIdx++;
                } else if (type == int.class) {
                    args[i] = atType;
                } else if (type == long.class) {
                    args[i] = (longIdx == 0) ? atUid : atTinyId;
                    longIdx++;
                } else {
                    args[i] = null;
                }
            }
            
            debugLog("通用填充参数: " + Arrays.toString(args));
            return constructor.newInstance(args);
            
        } catch (Throwable t) {
            debugLog("构造函数调用失败: " + t.getMessage());
            return null;
        }
    }
    
    /**
     * 尝试设置所有可能的字段名
     */
    private static void trySetAllFields(Object obj, String content, int atType, 
            long atUid, long atTinyId, String atNtUid) {
        // content 字段
        trySetField(obj, "content", content);
        trySetField(obj, "a", content);
        
        // atType 字段
        trySetIntField(obj, "atType", atType);
        trySetIntField(obj, "b", atType);
        
        // atUid 字段
        trySetLongField(obj, "atUid", atUid);
        trySetLongField(obj, "c", atUid);
        
        // atTinyId 字段
        trySetLongField(obj, "atTinyId", atTinyId);
        trySetLongField(obj, "d", atTinyId);
        
        // atNtUid 字段
        trySetField(obj, "atNtUid", atNtUid);
        trySetField(obj, "e", atNtUid);
    }
    
    private static void trySetField(Object obj, String fieldName, Object value) {
        try {
            XposedHelpers.setObjectField(obj, fieldName, value);
        } catch (Throwable ignored) {}
    }
    
    private static void trySetIntField(Object obj, String fieldName, int value) {
        try {
            XposedHelpers.setIntField(obj, fieldName, value);
        } catch (Throwable ignored) {}
    }
    
    private static void trySetLongField(Object obj, String fieldName, long value) {
        try {
            XposedHelpers.setLongField(obj, fieldName, value);
        } catch (Throwable ignored) {}
    }
    
    /**
     * 创建 msg.data.a 实例 (支持多版本类名)
     * QQ 9.2.x: com.tencent.mobileqq.aio.msg.data.a
     * QQ 9.1.x: com.tencent.mobileqq.aio.msg.a.a
     */
    private static Object createMsgDataInstance(ClassLoader classLoader) {
        // 尝试多个可能的类名
        String[] possibleClassNames = {
            "com.tencent.mobileqq.aio.msg.data.a",  // 9.2.x 版本
            "com.tencent.mobileqq.aio.msg.a.a",     // 9.1.x 版本
            "com.tencent.mobileqq.aio.msg.b.a",     // 备用
        };
        
        for (String className : possibleClassNames) {
            try {
                Class<?> msgDataClass = XposedHelpers.findClass(className, classLoader);
                
                Constructor<?>[] constructors = msgDataClass.getDeclaredConstructors();
                debugLog("找到类 " + className + "，有 " + constructors.length + " 个构造函数");
                
                // 【DEBUG】打印类的所有字段
                debugLog("┌─ " + className + " 字段分析 ─┐");
                for (java.lang.reflect.Field field : msgDataClass.getDeclaredFields()) {
                    String fieldType = field.getType().getName();
                    debugLog("│ " + field.getName() + " (" + fieldType + ")");
                }
                debugLog("└─────────────────────┘");
                
                for (Constructor<?> constructor : constructors) {
                    constructor.setAccessible(true);
                    Class<?>[] paramTypes = constructor.getParameterTypes();
                    debugLog("  构造函数: " + Arrays.toString(paramTypes));
                    
                    try {
                        Object[] params = new Object[paramTypes.length];
                        for (int i = 0; i < paramTypes.length; i++) {
                            Class<?> type = paramTypes[i];
                            if (type == int.class) params[i] = 0;
                            else if (type == boolean.class) params[i] = false;
                            else if (type == long.class) params[i] = 0L;
                            else params[i] = null;
                        }
                        
                        Object msgData = constructor.newInstance(params);
                        debugLog("创建 " + className + " 成功 (" + paramTypes.length + "参数)");
                        return msgData;
                    } catch (Throwable t) {
                        debugLog("构造函数失败: " + t.getMessage());
                    }
                }
            } catch (XposedHelpers.ClassNotFoundError e) {
                debugLog("类 " + className + " 不存在，尝试下一个");
            } catch (Throwable t) {
                debugLog("创建 " + className + " 异常: " + t.getMessage());
            }
        }
        
        debugLog("无法创建消息数据对象，所有类名都失败");
        XposedBridge.log(TAG + ": 无法创建消息数据对象");
        return null;
    }
    
    /**
     * 【DEBUG】分析 AIOElementType 的所有子类，用于发现 ReplyElement 相关类型
     */
    public static void analyzeAIOElementTypes(ClassLoader classLoader) {
        // debugLog("========== 分析 AIOElementType 子类 ==========");
        
        // 尝试查找所有可能的 AIOElementType 内部类
        String[] possibleTypes = {
            "com.tencent.qqnt.aio.msg.element.AIOElementType$a",
            "com.tencent.qqnt.aio.msg.element.AIOElementType$b",
            "com.tencent.qqnt.aio.msg.element.AIOElementType$c",
            "com.tencent.qqnt.aio.msg.element.AIOElementType$d",
            "com.tencent.qqnt.aio.msg.element.AIOElementType$e",
            "com.tencent.qqnt.aio.msg.element.AIOElementType$f",
            "com.tencent.qqnt.aio.msg.element.AIOElementType$g",
            "com.tencent.qqnt.aio.msg.element.AIOElementType$h",
            "com.tencent.qqnt.aio.msg.element.AIOElementType$i",
            "com.tencent.qqnt.aio.msg.element.AIOElementType$j",
            "com.tencent.qqnt.aio.msg.element.AIOElementType$k",
            "com.tencent.qqnt.aio.msg.element.AIOElementType$l",
            "com.tencent.qqnt.aio.msg.element.AIOElementType$m",
            "com.tencent.qqnt.aio.msg.element.AIOElementType$n",
            "com.tencent.qqnt.aio.msg.element.AIOElementType$o",
            "com.tencent.qqnt.aio.msg.element.AIOElementType$p",
        };
        
        for (String className : possibleTypes) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, classLoader);
                // debugLog("✓ 找到类: " + className);
                
                // 打印所有字段
                // debugLog("  字段:");
                for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                    String fieldType = field.getType().getSimpleName();
                    // debugLog("    " + field.getName() + " (" + fieldType + ")");
                    
                    // 检查是否包含 Reply 相关字段
                    if (fieldType.toLowerCase().contains("reply") || 
                        field.getName().toLowerCase().contains("reply")) {
                        // debugLog("    ★★★ 可能是 ReplyElement 相关类型! ★★★");
                    }
                }
                
                // 打印所有构造函数
                // debugLog("  构造函数:");
                for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                    // debugLog("    " + Arrays.toString(constructor.getParameterTypes()));
                }
                
            } catch (XposedHelpers.ClassNotFoundError e) {
                // 类不存在，跳过
            } catch (Throwable t) {
                // debugLog("分析 " + className + " 失败: " + t.getMessage());
            }
        }
        
        // debugLog("========== 分析完成 ==========");
    }
    
    private static Object getAIOSendMsgVMDelegate(Context context) {
        if (sAIOSendMsgVMDelegate == null) {
            debugLog("AIOSendMsgVMDelegate实例为null，请确保已Hook");
        }
        return sAIOSendMsgVMDelegate;
    }
    
    /**
     * 发送引用回复消息
     * 基于日志分析：
     * - ReplyData 类: com.tencent.mobileqq.aio.input.h
     * - ReplyElement 类: AIOElementType$h (d=replyMsgId, e=replyMsgSeq, f=replyNick, h=replyContent)
     * - 消息结构: List[0]=ReplyElement(type=7), List[1]=TextElement(type=1)
     * 
     * @param context 上下文
     * @param msgRecord 原消息记录
     * @param textToSend 要发送的文本
     * @param replyMsgId 被引用消息的ID
     * @param replyMsgSeq 被引用消息的序列号
     * @param replyNick 被引用者昵称
     * @param replyContent 被引用的内容
     */
    public static void sendReplyMessageNT(Context context, Object msgRecord, String textToSend,
                                          long replyMsgId, long replyMsgSeq, String replyNick, String replyContent) {
        try {
            Object peerUid = XposedHelpers.getObjectField(msgRecord, "peerUid");
            String peerUidStr = String.valueOf(peerUid);
            
            debugLog("sendReplyMessageNT - peerUid=" + peerUidStr + ", text=" + textToSend);
            debugLog("Reply info - msgId=" + replyMsgId + ", seq=" + replyMsgSeq + ", nick=" + replyNick);
            
            boolean success = sendReplyMessage(context, peerUidStr, textToSend, replyMsgId, replyMsgSeq, replyNick, replyContent);
            if (!success) {
                // 引用回复失败，回退到普通发送
                debugLog("引用回复失败，回退到普通发送");
                sendTextMessage(context, peerUidStr, textToSend);
            }
        } catch (Throwable t) {
            debugLog("sendReplyMessageNT 失败: " + t.getMessage());
            XposedBridge.log(t);
            android.widget.Toast.makeText(context, "引用回复发送失败", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 发送引用回复消息的核心实现
     * 支持多版本自动降级策略
     */
    public static boolean sendReplyMessage(Context context, String peerUid, String messageText,
                                          long replyMsgId, long replyMsgSeq, String replyNick, String replyContent) {
        try {
            ClassLoader classLoader = context.getClassLoader();
            
            debugLog("========== 开始发送引用回复 ==========");
            debugLog("文本内容: " + messageText);
            debugLog("引用信息: msgId=" + replyMsgId + ", seq=" + replyMsgSeq);
            debugLog("引用昵称: " + replyNick + ", 内容: " + replyContent);
            
            // 获取 AIOSendMsgVMDelegate 实例
            Object vmDelegate = getAIOSendMsgVMDelegate(context);
            if (vmDelegate == null) {
                debugLog("获取 AIOSendMsgVMDelegate 失败");
                return false;
            }
            
            // 创建 Bundle
            Bundle bundle = new Bundle();
            bundle.putString("input_text", messageText);
            bundle.putBoolean("from_send_btn", true);
            bundle.putInt("clearInputStatus", 1);
            bundle.putInt("key_send_intercept_busi_key", 0);
            bundle.putBoolean("key_is_set_essence", false);
            
            // 策略1: 尝试使用 D 方法 (9.2.35+ 版本)
            // 方法签名: D(List<n$f>, ReplyData, null, boolean, Bundle, String)
            if (sendReplyViaMethodD(context, classLoader, vmDelegate, messageText, replyMsgId, replyMsgSeq, replyNick, replyContent, bundle)) {
                debugLog("✓ 策略1 (D方法) 成功");
                return true;
            }
            
            // 策略2: 尝试使用 H 方法 (旧版本)
            // 方法签名: H(List<j$f>, ReplyData) -> List<msg.data.a>
            if (sendReplyViaMethodH(context, classLoader, vmDelegate, messageText, replyMsgId, replyMsgSeq, replyNick, replyContent, bundle)) {
                debugLog("✓ 策略2 (H方法) 成功");
                return true;
            }
            
            // 策略3: 直接构造消息列表并发送
            debugLog("尝试策略3: 直接构造消息列表");
            if (sendReplyMessageDirect(context, classLoader, vmDelegate, messageText, replyMsgId, replyMsgSeq, replyNick, replyContent, bundle)) {
                debugLog("✓ 策略3 (直接构造) 成功");
                return true;
            }
            
            // 策略4: 9.2.35版本专用降级方法
            debugLog("尝试策略4: 9.2.35版本降级方法");
            if (sendReplyMessageV9235(context, classLoader, vmDelegate, messageText, replyMsgId, replyMsgSeq, replyNick, replyContent, bundle)) {
                debugLog("✓ 策略4 (9.2.35降级) 成功");
                return true;
            }
            
            // 策略5: 9.1.16版本专用降级方法
            // 基于日志分析: f$e输入元素, d类ReplyData, $g为ReplyElement, $h为TextElement
            debugLog("尝试策略5: 9.1.16版本降级方法");
            if (sendReplyMessageV9116(context, classLoader, vmDelegate, messageText, replyMsgId, replyMsgSeq, replyNick, replyContent, bundle)) {
                debugLog("✓ 策略5 (9.1.16降级) 成功");
                return true;
            }
            
            // 策略6: 9.1.25版本专用降级方法
            // 基于日志分析: c$e输入元素, a类ReplyData, msg.a.a消息数据
            // f0方法签名: (List<c$e>, ReplyData(a), null, boolean, Bundle)
            // l0方法签名: (List<msg.a.a>, Bundle, Long, String)
            debugLog("尝试策略6: 9.1.25版本降级方法");
            if (sendReplyMessageV9125(context, classLoader, vmDelegate, messageText, replyMsgId, replyMsgSeq, replyNick, replyContent, bundle)) {
                debugLog("✓ 策略6 (9.1.25降级) 成功");
                return true;
            }
            
            debugLog("所有引用回复策略都失败");
            return false;
            
        } catch (Throwable t) {
            debugLog("sendReplyMessage 异常: " + t.getMessage());
            XposedBridge.log(t);
            return false;
        }
    }
    
    /**
     * 策略1: 使用 D 方法发送引用回复 (9.2.35+ 版本)
     * 方法签名: D(List<n$f>, ReplyData, null, boolean, Bundle, String)
     */
    private static boolean sendReplyViaMethodD(Context context, ClassLoader classLoader, Object vmDelegate,
                                               String messageText, long replyMsgId, long replyMsgSeq,
                                               String replyNick, String replyContent, Bundle bundle) {
        try {
            // 1. 创建输入元素列表 (尝试 n$f 或 j$f)
            List<Object> inputElements = createInputElementListV9235(classLoader, messageText);
            if (inputElements == null || inputElements.isEmpty()) {
                inputElements = createInputElementList(classLoader, messageText);
            }
            if (inputElements == null || inputElements.isEmpty()) {
                debugLog("[D方法] 创建输入元素列表失败");
                return false;
            }
            
            // 2. 创建 ReplyData (尝试 l 或 h)
            Object replyData = createReplyDataV9235(classLoader, replyMsgId, replyMsgSeq, replyNick, replyContent);
            if (replyData == null) {
                replyData = createReplyData(classLoader, replyMsgId, replyMsgSeq, replyNick, replyContent);
            }
            if (replyData == null) {
                debugLog("[D方法] 创建 ReplyData 失败");
                return false;
            }
            
            // 3. 查找 D 方法
            Method dMethod = findMethodD(vmDelegate.getClass());
            if (dMethod == null) {
                debugLog("[D方法] 未找到 D 方法");
                return false;
            }
            
            // 4. 调用 D 方法
            dMethod.setAccessible(true);
            debugLog("[D方法] 调用 D 方法: " + Arrays.toString(dMethod.getParameterTypes()));
            dMethod.invoke(vmDelegate, inputElements, replyData, null, false, bundle, "");
            
            debugLog("[D方法] 调用成功");
            return true;
            
        } catch (Throwable t) {
            debugLog("[D方法] 失败: " + t.getMessage());
            return false;
        }
    }
    
    /**
     * 策略2: 使用 H 方法发送引用回复 (旧版本)
     */
    private static boolean sendReplyViaMethodH(Context context, ClassLoader classLoader, Object vmDelegate,
                                               String messageText, long replyMsgId, long replyMsgSeq,
                                               String replyNick, String replyContent, Bundle bundle) {
        try {
            // 1. 创建输入元素列表
            List<Object> inputElements = createInputElementList(classLoader, messageText);
            if (inputElements == null || inputElements.isEmpty()) {
                return false;
            }
            
            // 2. 创建 ReplyData
            Object replyData = createReplyData(classLoader, replyMsgId, replyMsgSeq, replyNick, replyContent);
            if (replyData == null) {
                return false;
            }
            
            // 3. 查找 H 方法
            Method hMethod = findHMethod(vmDelegate.getClass());
            if (hMethod == null) {
                return false;
            }
            
            // 4. 调用 H 方法获取消息列表
            hMethod.setAccessible(true);
            Object msgDataList = hMethod.invoke(vmDelegate, inputElements, replyData);
            
            if (msgDataList == null || !(msgDataList instanceof List) || ((List<?>) msgDataList).isEmpty()) {
                return false;
            }
            
            // 5. 调用发送方法
            Method sendMethod = findSendMethod(vmDelegate.getClass());
            if (sendMethod == null) {
                return false;
            }
            
            sendMethod.setAccessible(true);
            sendMethod.invoke(vmDelegate, msgDataList, bundle, null, "");
            return true;
            
        } catch (Throwable t) {
            debugLog("[H方法] 失败: " + t.getMessage());
            return false;
        }
    }
    
    /**
     * 策略4: 9.2.35版本专用降级方法
     * 基于日志分析的结构:
     * - 输入元素: com.tencent.mobileqq.aio.input.n$f
     * - ReplyData: com.tencent.mobileqq.aio.input.l
     * - msg.data.a 字段: g = ReplyElement, b = TextElement
     * - 发送方法: G(List, Bundle, null, String)
     */
    private static boolean sendReplyMessageV9235(Context context, ClassLoader classLoader, Object vmDelegate,
                                                  String messageText, long replyMsgId, long replyMsgSeq,
                                                  String replyNick, String replyContent, Bundle bundle) {
        try {
            debugLog("[9.2.35降级] 开始构造消息");
            
            // 1. 创建 ReplyElement (AIOElementType$h)
            Object replyElement = createReplyElement(classLoader, replyMsgId, replyMsgSeq, replyNick, replyContent);
            if (replyElement == null) {
                debugLog("[9.2.35降级] 创建 ReplyElement 失败");
                return false;
            }
            
            // 2. 创建 TextElement (AIOElementType$i)
            Object textElement = tryCreateAIOElementTypeI(classLoader, messageText, 0, 0L, 0L, "");
            if (textElement == null) {
                debugLog("[9.2.35降级] 创建 TextElement 失败");
                return false;
            }
            
            // 3. 创建第一个 msg.data.a (ReplyElement, type=7)
            Object replyMsgData = createMsgDataInstance(classLoader);
            if (replyMsgData == null) {
                debugLog("[9.2.35降级] 创建 replyMsgData 失败");
                return false;
            }
            
            // 设置 ReplyElement 到正确的字段
            XposedHelpers.setIntField(replyMsgData, "a", 7);
            // 尝试设置到 g 字段 (9.2.35版本)
            if (!trySetObjectField(replyMsgData, "g", replyElement)) {
                // 回退到 h 字段 (旧版本)
                trySetObjectField(replyMsgData, "h", replyElement);
            }
            debugLog("[9.2.35降级] ReplyMsgData 创建成功, a=7");
            
            // 4. 创建第二个 msg.data.a (TextElement, type=1)
            Object textMsgData = createMsgDataInstance(classLoader);
            if (textMsgData == null) {
                debugLog("[9.2.35降级] 创建 textMsgData 失败");
                return false;
            }
            
            // 设置 TextElement 到正确的字段
            XposedHelpers.setIntField(textMsgData, "a", 1);
            // 尝试设置到 b 字段 (9.2.35版本)
            if (!trySetObjectField(textMsgData, "b", textElement)) {
                // 回退到 c 字段 (旧版本)
                trySetObjectField(textMsgData, "c", textElement);
            }
            debugLog("[9.2.35降级] TextMsgData 创建成功, a=1");
            
            // 5. 创建消息列表 (ReplyElement 在前)
            List<Object> msgList = new ArrayList<>();
            msgList.add(replyMsgData);
            msgList.add(textMsgData);
            
            debugLog("[9.2.35降级] 消息列表创建完成，包含 " + msgList.size() + " 个元素");
            
            // 6. 尝试使用 G 方法发送 (9.2.35版本)
            Method gMethod = findMethodG(vmDelegate.getClass());
            if (gMethod != null) {
                gMethod.setAccessible(true);
                debugLog("[9.2.35降级] 使用 G 方法发送: " + Arrays.toString(gMethod.getParameterTypes()));
                gMethod.invoke(vmDelegate, msgList, bundle, null, "");
                debugLog("[9.2.35降级] G 方法调用成功");
                return true;
            }
            
            // 7. 回退到标准发送方法
            Method sendMethod = findSendMethod(vmDelegate.getClass());
            if (sendMethod != null) {
                sendMethod.setAccessible(true);
                sendMethod.invoke(vmDelegate, msgList, bundle, null, "");
                debugLog("[9.2.35降级] 标准发送方法调用成功");
                return true;
            }
            
            debugLog("[9.2.35降级] 未找到发送方法");
            return false;
            
        } catch (Throwable t) {
            debugLog("[9.2.35降级] 异常: " + t.getMessage());
            XposedBridge.log(t);
            return false;
        }
    }
    
    /**
     * 查找 D 方法 (9.2.35+ 版本)
     * 方法签名: D(List, ReplyData, ?, boolean, Bundle, String)
     */
    private static Method findMethodD(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals("D")) {
                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length == 6 &&
                    paramTypes[0] == List.class &&
                    paramTypes[3] == boolean.class &&
                    paramTypes[4] == Bundle.class &&
                    paramTypes[5] == String.class) {
                    // debugLog("找到 D 方法: " + Arrays.toString(paramTypes));
                    return method;
                }
            }
        }
        return null;
    }
    
    /**
     * 查找 G 方法 (9.2.35版本发送方法)
     * 方法签名: G(List, Bundle, ?, String)
     */
    private static Method findMethodG(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals("G")) {
                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length == 4 &&
                    paramTypes[0] == List.class &&
                    paramTypes[1] == Bundle.class &&
                    paramTypes[3] == String.class) {
                    // debugLog("找到 G 方法: " + Arrays.toString(paramTypes));
                    return method;
                }
            }
        }
        return null;
    }
    
    /**
     * 创建输入元素列表 (9.2.35版本: n$f)
     */
    private static List<Object> createInputElementListV9235(ClassLoader classLoader, String content) {
        // 尝试 n$f 类
        String[] classNames = {
            "com.tencent.mobileqq.aio.input.n$f",
            "com.tencent.mobileqq.aio.input.m$f",
            "com.tencent.mobileqq.aio.input.o$f"
        };
        
        for (String className : classNames) {
            try {
                Class<?> elementClass = XposedHelpers.findClass(className, classLoader);
                List<Object> result = createInputElementFromClass(elementClass, content);
                if (result != null && !result.isEmpty()) {
                    debugLog("使用 " + className + " 创建输入元素成功");
                    return result;
                }
            } catch (XposedHelpers.ClassNotFoundError ignored) {
            } catch (Throwable t) {
                debugLog("尝试 " + className + " 失败: " + t.getMessage());
            }
        }
        return null;
    }
    
    /**
     * 从指定类创建输入元素
     */
    private static List<Object> createInputElementFromClass(Class<?> elementClass, String content) {
        try {
            java.lang.reflect.Constructor<?>[] constructors = elementClass.getDeclaredConstructors();
            
            for (java.lang.reflect.Constructor<?> c : constructors) {
                c.setAccessible(true);
                Class<?>[] paramTypes = c.getParameterTypes();
                
                // 尝试找到合适的构造函数
                if (paramTypes.length >= 1 && paramTypes[0] == String.class) {
                    Object[] args = new Object[paramTypes.length];
                    args[0] = content;
                    for (int i = 1; i < paramTypes.length; i++) {
                        Class<?> type = paramTypes[i];
                        if (type == int.class) args[i] = 0;
                        else if (type == long.class) args[i] = 0L;
                        else if (type == boolean.class) args[i] = false;
                        else if (type == String.class) args[i] = "";
                        else args[i] = null;
                    }
                    
                    Object element = c.newInstance(args);
                    List<Object> list = new ArrayList<>();
                    list.add(element);
                    return list;
                }
            }
            
            // 尝试无参构造函数 + 设置字段
            for (java.lang.reflect.Constructor<?> c : constructors) {
                if (c.getParameterTypes().length == 0) {
                    c.setAccessible(true);
                    Object element = c.newInstance();
                    // 尝试设置 d 字段 (content)
                    trySetField(element, "d", content);
                    trySetField(element, "content", content);
                    List<Object> list = new ArrayList<>();
                    list.add(element);
                    return list;
                }
            }
        } catch (Throwable t) {
            debugLog("createInputElementFromClass 失败: " + t.getMessage());
        }
        return null;
    }
    
    /**
     * 创建 ReplyData (9.2.35版本: com.tencent.mobileqq.aio.input.l)
     */
    private static Object createReplyDataV9235(ClassLoader classLoader, long replyMsgId, long replyMsgSeq,
                                               String replyNick, String replyContent) {
        // 尝试多个可能的类名
        String[] classNames = {
            "com.tencent.mobileqq.aio.input.l",
            "com.tencent.mobileqq.aio.input.k",
            "com.tencent.mobileqq.aio.input.m"
        };
        
        for (String className : classNames) {
            try {
                Class<?> replyDataClass = XposedHelpers.findClass(className, classLoader);
                Object result = createReplyDataFromClass(replyDataClass, replyMsgId, replyMsgSeq, replyNick, replyContent);
                if (result != null) {
                    debugLog("使用 " + className + " 创建 ReplyData 成功");
                    return result;
                }
            } catch (XposedHelpers.ClassNotFoundError ignored) {
            } catch (Throwable t) {
                debugLog("尝试 " + className + " 失败: " + t.getMessage());
            }
        }
        return null;
    }
    
    /**
     * 从指定类创建 ReplyData
     */
    private static Object createReplyDataFromClass(Class<?> replyDataClass, long replyMsgId, long replyMsgSeq,
                                                   String replyNick, String replyContent) {
        try {
            java.lang.reflect.Constructor<?>[] constructors = replyDataClass.getDeclaredConstructors();
            
            for (java.lang.reflect.Constructor<?> c : constructors) {
                c.setAccessible(true);
                Class<?>[] paramTypes = c.getParameterTypes();
                
                // 尝试 4 参数构造函数 (String, String, long, long)
                if (paramTypes.length == 4) {
                    try {
                        // 尝试不同的参数顺序
                        Object result = c.newInstance(replyNick, replyContent, replyMsgSeq, replyMsgId);
                        debugLog("ReplyData 构造成功 (nick, content, seq, id)");
                        return result;
                    } catch (Throwable ignored) {}
                    
                    try {
                        Object result = c.newInstance(replyNick, replyContent, replyMsgId, replyMsgSeq);
                        debugLog("ReplyData 构造成功 (nick, content, id, seq)");
                        return result;
                    } catch (Throwable ignored) {}
                }
            }
            
            // 尝试无参构造函数 + 设置字段
            for (java.lang.reflect.Constructor<?> c : constructors) {
                if (c.getParameterTypes().length == 0) {
                    c.setAccessible(true);
                    Object replyData = c.newInstance();
                    // 根据日志中的 toString 格式设置字段
                    // ReplyData(nickname=, replyText=test, messageSequence=351, messageId=7578875519564058501)
                    trySetField(replyData, "nickname", replyNick);
                    trySetField(replyData, "replyText", replyContent);
                    trySetLongField(replyData, "messageSequence", replyMsgSeq);
                    trySetLongField(replyData, "messageId", replyMsgId);
                    return replyData;
                }
            }
        } catch (Throwable t) {
            debugLog("createReplyDataFromClass 失败: " + t.getMessage());
        }
        return null;
    }
    
    /**
     * 安全设置对象字段，返回是否成功
     */
    private static boolean trySetObjectField(Object obj, String fieldName, Object value) {
        try {
            XposedHelpers.setObjectField(obj, fieldName, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
    
    /**
     * 直接构造引用回复消息列表并发送
     * 支持多版本字段映射自动降级
     */
    private static boolean sendReplyMessageDirect(Context context, ClassLoader classLoader, Object vmDelegate,
                                                  String messageText, long replyMsgId, long replyMsgSeq, 
                                                  String replyNick, String replyContent, Bundle bundle) {
        try {
            // 1. 创建 ReplyElement (AIOElementType$h)
            Object replyElement = createReplyElement(classLoader, replyMsgId, replyMsgSeq, replyNick, replyContent);
            if (replyElement == null) {
                debugLog("创建 ReplyElement 失败");
                return false;
            }
            
            // 2. 创建 TextElement (优先使用 AIOElementType$i)
            Object textElement = tryCreateAIOElementTypeI(classLoader, messageText, 0, 0L, 0L, "");
            if (textElement == null) {
                textElement = createTextElement(classLoader, messageText);
            }
            if (textElement == null) {
                debugLog("创建 TextElement 失败");
                return false;
            }
            
            // 3. 创建第一个 msg.data.a 实例 (ReplyElement, type=7)
            Object replyMsgData = createMsgDataInstance(classLoader);
            if (replyMsgData == null) {
                debugLog("创建 replyMsgData 失败");
                return false;
            }
            XposedHelpers.setIntField(replyMsgData, "a", 7);  // ReplyElement 类型
            // 尝试多个字段名 (g=9.2.35, h=旧版本)
            boolean replyFieldSet = trySetObjectField(replyMsgData, "g", replyElement);
            if (!replyFieldSet) {
                replyFieldSet = trySetObjectField(replyMsgData, "h", replyElement);
            }
            if (!replyFieldSet) {
                debugLog("无法设置 ReplyElement 字段");
                return false;
            }
            
            // 4. 创建第二个 msg.data.a 实例 (TextElement, type=1)
            Object textMsgData = createMsgDataInstance(classLoader);
            if (textMsgData == null) {
                debugLog("创建 textMsgData 失败");
                return false;
            }
            XposedHelpers.setIntField(textMsgData, "a", 1);  // TextElement 类型
            // 尝试多个字段名 (b=9.2.35, c=旧版本)
            boolean textFieldSet = trySetObjectField(textMsgData, "b", textElement);
            if (!textFieldSet) {
                textFieldSet = trySetObjectField(textMsgData, "c", textElement);
            }
            if (!textFieldSet) {
                debugLog("无法设置 TextElement 字段");
                return false;
            }
            
            // 5. 创建消息列表 (ReplyElement 在前，TextElement 在后)
            List<Object> msgList = new ArrayList<>();
            msgList.add(replyMsgData);
            msgList.add(textMsgData);
            
            debugLog("消息列表创建完成，包含 " + msgList.size() + " 个元素");
            
            // 6. 尝试使用 G 方法发送 (9.2.35版本)
            Method gMethod = findMethodG(vmDelegate.getClass());
            if (gMethod != null) {
                gMethod.setAccessible(true);
                gMethod.invoke(vmDelegate, msgList, bundle, null, "");
                debugLog("✓ 引用回复发送成功 (G方法)");
                return true;
            }
            
            // 7. 回退到标准发送方法
            Method sendMethod = findSendMethod(vmDelegate.getClass());
            if (sendMethod != null) {
                sendMethod.setAccessible(true);
                sendMethod.invoke(vmDelegate, msgList, bundle, null, "");
                debugLog("✓ 引用回复发送成功 (直接构造)");
                return true;
            } else {
                debugLog("未找到发送方法");
                return false;
            }
            
        } catch (Throwable t) {
            debugLog("sendReplyMessageDirect 异常: " + t.getMessage());
            XposedBridge.log(t);
            return false;
        }
    }
    
    /**
     * 创建 ReplyData 对象 (com.tencent.mobileqq.aio.input.h)
     */
    private static Object createReplyData(ClassLoader classLoader, long replyMsgId, long replyMsgSeq, 
                                         String replyNick, String replyContent) {
        try {
            Class<?> replyDataClass = XposedHelpers.findClass(
                "com.tencent.mobileqq.aio.input.h", classLoader);
            
            debugLog("找到 ReplyData 类: " + replyDataClass.getName());
            
            // 查找构造函数
            Constructor<?>[] constructors = replyDataClass.getDeclaredConstructors();
            for (Constructor<?> c : constructors) {
                c.setAccessible(true);
                Class<?>[] paramTypes = c.getParameterTypes();
                debugLog("ReplyData 构造函数: " + Arrays.toString(paramTypes));
                
                // 尝试匹配 (String, String, long, long) 或类似签名
                if (paramTypes.length == 4) {
                    try {
                        Object replyData = c.newInstance(replyNick, replyContent, replyMsgSeq, replyMsgId);
                        debugLog("✓ 创建 ReplyData 成功: " + replyData);
                        return replyData;
                    } catch (Throwable t) {
                        // 尝试其他参数顺序
                        try {
                            Object replyData = c.newInstance(replyNick, replyContent, replyMsgId, replyMsgSeq);
                            debugLog("✓ 创建 ReplyData 成功 (备用顺序): " + replyData);
                            return replyData;
                        } catch (Throwable ignored) {}
                    }
                }
            }
            
            debugLog("未找到匹配的 ReplyData 构造函数");
            return null;
            
        } catch (Throwable t) {
            debugLog("创建 ReplyData 失败: " + t.getMessage());
            return null;
        }
    }
    
    /**
     * 创建 ReplyElement (AIOElementType$h)
     * 基于日志分析的结构：
     * - d (long): replyMsgId
     * - e (long): replyMsgSeq  
     * - f (String): replyNick
     * - h (String): replyContent
     * 构造函数: [long, long, String, String]
     */
    private static Object createReplyElement(ClassLoader classLoader, long replyMsgId, long replyMsgSeq, 
                                            String replyNick, String replyContent) {
        try {
            Class<?> replyElementClass = XposedHelpers.findClass(
                "com.tencent.qqnt.aio.msg.element.AIOElementType$h", classLoader);
            
            debugLog("找到 AIOElementType$h 类: " + replyElementClass.getName());
            
            // 查找构造函数 [long, long, String, String]
            Constructor<?> constructor = null;
            Constructor<?>[] constructors = replyElementClass.getDeclaredConstructors();
            
            for (Constructor<?> c : constructors) {
                Class<?>[] paramTypes = c.getParameterTypes();
                if (paramTypes.length == 4 && 
                    paramTypes[0] == long.class && 
                    paramTypes[1] == long.class && 
                    paramTypes[2] == String.class && 
                    paramTypes[3] == String.class) {
                    constructor = c;
                    break;
                }
            }
            
            if (constructor == null) {
                debugLog("未找到匹配的 ReplyElement 构造函数");
                // 打印所有可用的构造函数
                for (int i = 0; i < constructors.length; i++) {
                    debugLog("构造函数[" + i + "]: " + Arrays.toString(constructors[i].getParameterTypes()));
                }
                return null;
            }
            
            constructor.setAccessible(true);
            Object replyElement = constructor.newInstance(replyMsgId, replyMsgSeq, replyNick, replyContent);
            
            debugLog("✓ 创建 ReplyElement 成功: " + replyElement);
            return replyElement;
            
        } catch (Throwable t) {
            debugLog("创建 ReplyElement 失败: " + t.getMessage());
            XposedBridge.log(t);
            return null;
        }
    }
    
    /**
     * 创建输入元素列表 (j$f 类型)
     */
    private static List<Object> createInputElementList(ClassLoader classLoader, String content) {
        try {
            Class<?> jfClass = XposedHelpers.findClass(
                "com.tencent.mobileqq.aio.input.j$f", classLoader);
            
            Constructor<?>[] constructors = jfClass.getDeclaredConstructors();
            for (Constructor<?> c : constructors) {
                c.setAccessible(true);
                Class<?>[] paramTypes = c.getParameterTypes();
                
                // 尝试找到合适的构造函数
                if (paramTypes.length >= 1 && paramTypes[0] == String.class) {
                    Object[] args = new Object[paramTypes.length];
                    args[0] = content;
                    for (int i = 1; i < paramTypes.length; i++) {
                        Class<?> type = paramTypes[i];
                        if (type == int.class) args[i] = 0;
                        else if (type == long.class) args[i] = 0L;
                        else if (type == boolean.class) args[i] = false;
                        else if (type == String.class) args[i] = "";
                        else args[i] = null;
                    }
                    
                    Object element = c.newInstance(args);
                    List<Object> list = new ArrayList<>();
                    list.add(element);
                    debugLog("✓ 创建输入元素列表成功");
                    return list;
                }
            }
            
            debugLog("未找到合适的 j$f 构造函数");
            return null;
            
        } catch (Throwable t) {
            debugLog("创建输入元素列表失败: " + t.getMessage());
            return null;
        }
    }
    
    /**
     * 查找 H 方法 (处理引用回复)
     * 方法签名: H(List, ReplyData) -> List
     */
    private static Method findHMethod(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals("H")) {
                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length == 2 && 
                    paramTypes[0] == List.class &&
                    method.getReturnType() == List.class) {
                    debugLog("找到 H 方法: " + Arrays.toString(paramTypes));
                    return method;
                }
            }
        }
        return null;
    }
    
    // ==================== 9.1.16版本专用方法 ====================
    
    /**
     * 策略5: 9.1.16版本专用降级方法
     * 基于日志分析的结构:
     * - 输入元素: com.tencent.mobileqq.aio.input.f$e (字段: e=content, f=int, h=long, i=long, m=String)
     * - ReplyData: com.tencent.mobileqq.aio.input.d
     * - ReplyElement: AIOElementType$g (字段: e=msgId, f=seq, h=nick, i=content)
     * - TextElement: AIOElementType$h (字段: e=content, f=int, h=long, i=long, m=String)
     * - msg.data.a 字段: a=type, b=int(0), c=TextElement($h), h=ReplyElement($g)
     * - 发送方法: f0(6参数) 或 n0(4参数)
     */
    private static boolean sendReplyMessageV9116(Context context, ClassLoader classLoader, Object vmDelegate,
                                                  String messageText, long replyMsgId, long replyMsgSeq,
                                                  String replyNick, String replyContent, Bundle bundle) {
        try {
            debugLog("[9.1.16降级] 开始构造消息");
            
            // 方案A: 尝试使用 f0 方法 (6参数版本)
            if (sendReplyViaMethodF0(context, classLoader, vmDelegate, messageText, replyMsgId, replyMsgSeq, replyNick, replyContent, bundle)) {
                debugLog("[9.1.16降级] f0方法成功");
                return true;
            }
            
            // 方案B: 直接构造消息列表并使用 n0 方法发送
            debugLog("[9.1.16降级] 尝试直接构造消息列表");
            
            // 1. 创建 ReplyElement (AIOElementType$g)
            Object replyElement = createReplyElementV9116(classLoader, replyMsgId, replyMsgSeq, replyNick, replyContent);
            if (replyElement == null) {
                debugLog("[9.1.16降级] 创建 ReplyElement($g) 失败");
                return false;
            }
            
            // 2. 创建 TextElement (AIOElementType$h)
            Object textElement = createTextElementV9116(classLoader, messageText);
            if (textElement == null) {
                debugLog("[9.1.16降级] 创建 TextElement($h) 失败");
                return false;
            }
            
            // 3. 创建第一个 msg.data.a (ReplyElement, type=7)
            Object replyMsgData = createMsgDataInstance(classLoader);
            if (replyMsgData == null) {
                debugLog("[9.1.16降级] 创建 replyMsgData 失败");
                return false;
            }
            
            // 设置字段: a=7, b=0, h=ReplyElement
            XposedHelpers.setIntField(replyMsgData, "a", 7);
            trySetIntField(replyMsgData, "b", 0);
            // 9.1.16版本 ReplyElement 在 h 字段
            if (!trySetObjectField(replyMsgData, "h", replyElement)) {
                // 回退尝试 g 字段
                trySetObjectField(replyMsgData, "g", replyElement);
            }
            debugLog("[9.1.16降级] ReplyMsgData 创建成功, a=7");
            
            // 4. 创建第二个 msg.data.a (TextElement, type=1)
            Object textMsgData = createMsgDataInstance(classLoader);
            if (textMsgData == null) {
                debugLog("[9.1.16降级] 创建 textMsgData 失败");
                return false;
            }
            
            // 设置字段: a=1, b=0, c=TextElement
            XposedHelpers.setIntField(textMsgData, "a", 1);
            trySetIntField(textMsgData, "b", 0);
            // 9.1.16版本 TextElement 在 c 字段
            if (!trySetObjectField(textMsgData, "c", textElement)) {
                // 回退尝试 b 字段
                trySetObjectField(textMsgData, "b", textElement);
            }
            debugLog("[9.1.16降级] TextMsgData 创建成功, a=1");
            
            // 5. 创建消息列表 (ReplyElement 在前)
            List<Object> msgList = new ArrayList<>();
            msgList.add(replyMsgData);
            msgList.add(textMsgData);
            
            debugLog("[9.1.16降级] 消息列表创建完成，包含 " + msgList.size() + " 个元素");
            
            // 6. 尝试使用 n0 方法发送 (9.1.16版本)
            Method n0Method = findMethodN0(vmDelegate.getClass());
            if (n0Method != null) {
                n0Method.setAccessible(true);
                debugLog("[9.1.16降级] 使用 n0 方法发送: " + Arrays.toString(n0Method.getParameterTypes()));
                n0Method.invoke(vmDelegate, msgList, bundle, null, "");
                debugLog("[9.1.16降级] n0 方法调用成功");
                return true;
            }
            
            // 7. 回退到标准发送方法
            Method sendMethod = findSendMethod(vmDelegate.getClass());
            if (sendMethod != null) {
                sendMethod.setAccessible(true);
                sendMethod.invoke(vmDelegate, msgList, bundle, null, "");
                debugLog("[9.1.16降级] 标准发送方法调用成功");
                return true;
            }
            
            debugLog("[9.1.16降级] 未找到发送方法");
            return false;
            
        } catch (Throwable t) {
            debugLog("[9.1.16降级] 异常: " + t.getMessage());
            XposedBridge.log(t);
            return false;
        }
    }
    
    /**
     * 使用 f0 方法发送引用回复 (9.1.16版本)
     * 方法签名: f0(List<f$e>, ReplyData(d), null, boolean, Bundle, String)
     */
    private static boolean sendReplyViaMethodF0(Context context, ClassLoader classLoader, Object vmDelegate,
                                                String messageText, long replyMsgId, long replyMsgSeq,
                                                String replyNick, String replyContent, Bundle bundle) {
        try {
            // 1. 创建输入元素列表 (f$e)
            List<Object> inputElements = createInputElementListV9116(classLoader, messageText);
            if (inputElements == null || inputElements.isEmpty()) {
                debugLog("[f0方法] 创建输入元素列表失败");
                return false;
            }
            
            // 2. 创建 ReplyData (d类)
            Object replyData = createReplyDataV9116(classLoader, replyMsgId, replyMsgSeq, replyNick, replyContent);
            if (replyData == null) {
                debugLog("[f0方法] 创建 ReplyData 失败");
                return false;
            }
            
            // 3. 查找 f0 方法
            Method f0Method = findMethodF0(vmDelegate.getClass());
            if (f0Method == null) {
                debugLog("[f0方法] 未找到 f0 方法");
                return false;
            }
            
            // 4. 调用 f0 方法
            f0Method.setAccessible(true);
            debugLog("[f0方法] 调用 f0 方法: " + Arrays.toString(f0Method.getParameterTypes()));
            f0Method.invoke(vmDelegate, inputElements, replyData, null, false, bundle, "");
            
            debugLog("[f0方法] 调用成功");
            return true;
            
        } catch (Throwable t) {
            debugLog("[f0方法] 失败: " + t.getMessage());
            return false;
        }
    }
    
    /**
     * 查找 f0 方法 (9.1.16版本)
     * 方法签名: f0(List, ReplyData, ?, boolean, Bundle, String)
     */
    private static Method findMethodF0(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals("f0")) {
                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length == 6 &&
                    paramTypes[0] == List.class &&
                    paramTypes[3] == boolean.class &&
                    paramTypes[4] == Bundle.class &&
                    paramTypes[5] == String.class) {
                    debugLog("找到 f0 方法: " + Arrays.toString(paramTypes));
                    return method;
                }
            }
        }
        return null;
    }
    
    /**
     * 创建输入元素列表 (9.1.16版本: f$e)
     */
    private static List<Object> createInputElementListV9116(ClassLoader classLoader, String content) {
        // 尝试 f$e 类及其变体
        String[] classNames = {
            "com.tencent.mobileqq.aio.input.f$e",
            "com.tencent.mobileqq.aio.input.e$e",
            "com.tencent.mobileqq.aio.input.g$e"
        };
        
        for (String className : classNames) {
            try {
                Class<?> elementClass = XposedHelpers.findClass(className, classLoader);
                List<Object> result = createInputElementFromClassV9116(elementClass, content);
                if (result != null && !result.isEmpty()) {
                    debugLog("使用 " + className + " 创建输入元素成功");
                    return result;
                }
            } catch (XposedHelpers.ClassNotFoundError ignored) {
            } catch (Throwable t) {
                debugLog("尝试 " + className + " 失败: " + t.getMessage());
            }
        }
        return null;
    }
    
    /**
     * 从指定类创建输入元素 (9.1.16版本)
     * 字段结构: e=content(String), f=0(int), h=0(long), i=0(long), m=""(String)
     */
    private static List<Object> createInputElementFromClassV9116(Class<?> elementClass, String content) {
        try {
            java.lang.reflect.Constructor<?>[] constructors = elementClass.getDeclaredConstructors();
            
            // 优先尝试带参数的构造函数
            for (java.lang.reflect.Constructor<?> c : constructors) {
                c.setAccessible(true);
                Class<?>[] paramTypes = c.getParameterTypes();
                
                // 尝试找到合适的构造函数
                if (paramTypes.length >= 1 && paramTypes[0] == String.class) {
                    Object[] args = new Object[paramTypes.length];
                    args[0] = content;
                    for (int i = 1; i < paramTypes.length; i++) {
                        Class<?> type = paramTypes[i];
                        if (type == int.class) args[i] = 0;
                        else if (type == long.class) args[i] = 0L;
                        else if (type == boolean.class) args[i] = false;
                        else if (type == String.class) args[i] = "";
                        else args[i] = null;
                    }
                    
                    Object element = c.newInstance(args);
                    List<Object> list = new ArrayList<>();
                    list.add(element);
                    return list;
                }
            }
            
            // 尝试无参构造函数 + 设置字段
            for (java.lang.reflect.Constructor<?> c : constructors) {
                if (c.getParameterTypes().length == 0) {
                    c.setAccessible(true);
                    Object element = c.newInstance();
                    // 9.1.16版本字段: e=content
                    trySetField(element, "e", content);
                    trySetField(element, "d", content);
                    trySetField(element, "content", content);
                    trySetIntField(element, "f", 0);
                    trySetLongField(element, "h", 0L);
                    trySetLongField(element, "i", 0L);
                    trySetField(element, "m", "");
                    List<Object> list = new ArrayList<>();
                    list.add(element);
                    return list;
                }
            }
        } catch (Throwable t) {
            debugLog("createInputElementFromClassV9116 失败: " + t.getMessage());
        }
        return null;
    }
    
    /**
     * 创建 ReplyData (9.1.16版本: com.tencent.mobileqq.aio.input.d)
     */
    private static Object createReplyDataV9116(ClassLoader classLoader, long replyMsgId, long replyMsgSeq,
                                               String replyNick, String replyContent) {
        // 尝试多个可能的类名
        String[] classNames = {
            "com.tencent.mobileqq.aio.input.d",
            "com.tencent.mobileqq.aio.input.c",
            "com.tencent.mobileqq.aio.input.e"
        };
        
        for (String className : classNames) {
            try {
                Class<?> replyDataClass = XposedHelpers.findClass(className, classLoader);
                Object result = createReplyDataFromClassV9116(replyDataClass, replyMsgId, replyMsgSeq, replyNick, replyContent);
                if (result != null) {
                    // debugLog("使用 " + className + " 创建 ReplyData 成功");
                    return result;
                }
            } catch (XposedHelpers.ClassNotFoundError ignored) {
            } catch (Throwable t) {
                // debugLog("尝试 " + className + " 失败: " + t.getMessage());
            }
        }
        return null;
    }
    
    /**
     * 从指定类创建 ReplyData (9.1.16版本)
     * 日志格式: ReplyData(nickname=, replyText=test, messageSequence=357, messageId=7578879487695094747)
     */
    private static Object createReplyDataFromClassV9116(Class<?> replyDataClass, long replyMsgId, long replyMsgSeq,
                                                        String replyNick, String replyContent) {
        try {
            java.lang.reflect.Constructor<?>[] constructors = replyDataClass.getDeclaredConstructors();
            
            for (java.lang.reflect.Constructor<?> c : constructors) {
                c.setAccessible(true);
                Class<?>[] paramTypes = c.getParameterTypes();
                
                // 尝试 4 参数构造函数 (String, String, long, long)
                if (paramTypes.length == 4) {
                    try {
                        // 尝试不同的参数顺序
                        Object result = c.newInstance(replyNick, replyContent, replyMsgSeq, replyMsgId);
                        // debugLog("ReplyData(9.1.16) 构造成功 (nick, content, seq, id)");
                        return result;
                    } catch (Throwable ignored) {}
                    
                    try {
                        Object result = c.newInstance(replyNick, replyContent, replyMsgId, replyMsgSeq);
                        // debugLog("ReplyData(9.1.16) 构造成功 (nick, content, id, seq)");
                        return result;
                    } catch (Throwable ignored) {}
                }
            }
            
            // 尝试无参构造函数 + 设置字段
            for (java.lang.reflect.Constructor<?> c : constructors) {
                if (c.getParameterTypes().length == 0) {
                    c.setAccessible(true);
                    Object replyData = c.newInstance();
                    // 根据日志中的 toString 格式设置字段
                    trySetField(replyData, "nickname", replyNick);
                    trySetField(replyData, "replyText", replyContent);
                    trySetLongField(replyData, "messageSequence", replyMsgSeq);
                    trySetLongField(replyData, "messageId", replyMsgId);
                    return replyData;
                }
            }
        } catch (Throwable t) {
            // debugLog("createReplyDataFromClassV9116 失败: " + t.getMessage());
        }
        return null;
    }
    
    /**
     * 创建 ReplyElement (9.1.16版本: AIOElementType$g)
     * 字段结构: e=msgId(long), f=seq(long), h=nick(String), i=content(String)
     */
    private static Object createReplyElementV9116(ClassLoader classLoader, long replyMsgId, long replyMsgSeq,
                                                  String replyNick, String replyContent) {
        try {
            Class<?> replyElementClass = XposedHelpers.findClass(
                "com.tencent.qqnt.aio.msg.element.AIOElementType$g", classLoader);
            
            debugLog("找到 AIOElementType$g 类: " + replyElementClass.getName());
            
            Constructor<?>[] constructors = replyElementClass.getDeclaredConstructors();
            
            // 查找构造函数 [long, long, String, String]
            for (Constructor<?> c : constructors) {
                c.setAccessible(true);
                Class<?>[] paramTypes = c.getParameterTypes();
                
                if (paramTypes.length == 4 && 
                    paramTypes[0] == long.class && 
                    paramTypes[1] == long.class && 
                    paramTypes[2] == String.class && 
                    paramTypes[3] == String.class) {
                    Object replyElement = c.newInstance(replyMsgId, replyMsgSeq, replyNick, replyContent);
                    debugLog("✓ 创建 ReplyElement($g) 成功: " + replyElement);
                    return replyElement;
                }
            }
            
            // 尝试无参构造函数 + 设置字段
            for (Constructor<?> c : constructors) {
                if (c.getParameterTypes().length == 0) {
                    c.setAccessible(true);
                    Object replyElement = c.newInstance();
                    // 9.1.16版本字段: e=msgId, f=seq, h=nick, i=content
                    trySetLongField(replyElement, "e", replyMsgId);
                    trySetLongField(replyElement, "f", replyMsgSeq);
                    trySetField(replyElement, "h", replyNick);
                    trySetField(replyElement, "i", replyContent);
                    debugLog("✓ 创建 ReplyElement($g) 成功 (无参+字段)");
                    return replyElement;
                }
            }
            
            debugLog("未找到匹配的 ReplyElement($g) 构造函数");
            for (int i = 0; i < constructors.length; i++) {
                debugLog("构造函数[" + i + "]: " + Arrays.toString(constructors[i].getParameterTypes()));
            }
            return null;
            
        } catch (XposedHelpers.ClassNotFoundError e) {
            debugLog("AIOElementType$g 类不存在");
            return null;
        } catch (Throwable t) {
            debugLog("创建 ReplyElement($g) 失败: " + t.getMessage());
            return null;
        }
    }
    
    /**
     * 创建 TextElement (9.1.16版本: AIOElementType$h)
     * 字段结构: e=content(String), f=0(int), h=0(long), i=0(long), m=""(String)
     */
    private static Object createTextElementV9116(ClassLoader classLoader, String content) {
        try {
            Class<?> textElementClass = XposedHelpers.findClass(
                "com.tencent.qqnt.aio.msg.element.AIOElementType$h", classLoader);
            
            debugLog("找到 AIOElementType$h 类: " + textElementClass.getName());
            
            Constructor<?>[] constructors = textElementClass.getDeclaredConstructors();
            
            // 尝试带参数的构造函数
            for (Constructor<?> c : constructors) {
                c.setAccessible(true);
                Class<?>[] paramTypes = c.getParameterTypes();
                
                // 尝试找到合适的构造函数
                if (paramTypes.length >= 1 && paramTypes[0] == String.class) {
                    Object[] args = new Object[paramTypes.length];
                    args[0] = content;
                    for (int i = 1; i < paramTypes.length; i++) {
                        Class<?> type = paramTypes[i];
                        if (type == int.class) args[i] = 0;
                        else if (type == long.class) args[i] = 0L;
                        else if (type == boolean.class) args[i] = false;
                        else if (type == String.class) args[i] = "";
                        else args[i] = null;
                    }
                    
                    Object textElement = c.newInstance(args);
                    debugLog("✓ 创建 TextElement($h) 成功");
                    return textElement;
                }
            }
            
            // 尝试无参构造函数 + 设置字段
            for (Constructor<?> c : constructors) {
                if (c.getParameterTypes().length == 0) {
                    c.setAccessible(true);
                    Object textElement = c.newInstance();
                    // 9.1.16版本字段: e=content
                    trySetField(textElement, "e", content);
                    trySetField(textElement, "content", content);
                    trySetIntField(textElement, "f", 0);
                    trySetLongField(textElement, "h", 0L);
                    trySetLongField(textElement, "i", 0L);
                    trySetField(textElement, "m", "");
                    debugLog("✓ 创建 TextElement($h) 成功 (无参+字段)");
                    return textElement;
                }
            }
            
            debugLog("未找到匹配的 TextElement($h) 构造函数");
            return null;
            
        } catch (XposedHelpers.ClassNotFoundError e) {
            debugLog("AIOElementType$h 类不存在");
            return null;
        } catch (Throwable t) {
            debugLog("创建 TextElement($h) 失败: " + t.getMessage());
            return null;
        }
    }
    
    // ==================== 9.1.25版本专用方法 ====================
    
    /**
     * 策略6: 9.1.25版本专用降级方法
     * 基于日志分析的结构:
     * - 输入元素: com.tencent.mobileqq.aio.input.c$e (字段: e=content, f=int, g=long, h=long, i=String)
     * - ReplyData: com.tencent.mobileqq.aio.input.a
     *   格式: ReplyData(nickname=惑灵, replyText=惑灵:test, messageSequence=3524, messageId=7653411262500573649)
     * - 消息数据: com.tencent.mobileqq.aio.msg.a.a
     * - f0方法签名: (List<c$e>, ReplyData(a), List?, boolean, Bundle)
     * - l0方法签名: (List<msg.a.a>, Bundle, Long?, String?)
     */
    private static boolean sendReplyMessageV9125(Context context, ClassLoader classLoader, Object vmDelegate,
                                                  String messageText, long replyMsgId, long replyMsgSeq,
                                                  String replyNick, String replyContent, Bundle bundle) {
        try {
            debugLog("[9.1.25降级] 开始构造消息");
            
            // 方案A: 尝试使用 f0 方法 (5参数版本)
            if (sendReplyViaMethodF0V9125(context, classLoader, vmDelegate, messageText, replyMsgId, replyMsgSeq, replyNick, replyContent, bundle)) {
                debugLog("[9.1.25降级] f0方法成功");
                return true;
            }
            
            // 方案B: 直接构造消息列表并使用 l0 方法发送
            debugLog("[9.1.25降级] 尝试直接构造消息列表");
            
            // 1. 创建 ReplyElement (使用 msg.a.a 的内部结构)
            Object replyMsgData = createMsgDataV9125(classLoader, true, replyMsgId, replyMsgSeq, replyNick, replyContent);
            if (replyMsgData == null) {
                debugLog("[9.1.25降级] 创建 replyMsgData 失败");
                return false;
            }
            debugLog("[9.1.25降级] ReplyMsgData 创建成功");
            
            // 2. 创建 TextElement (使用 msg.a.a 的内部结构)
            Object textMsgData = createMsgDataV9125(classLoader, false, 0, 0, null, messageText);
            if (textMsgData == null) {
                debugLog("[9.1.25降级] 创建 textMsgData 失败");
                return false;
            }
            debugLog("[9.1.25降级] TextMsgData 创建成功");
            
            // 3. 创建消息列表 (ReplyElement 在前)
            List<Object> msgList = new ArrayList<>();
            msgList.add(replyMsgData);
            msgList.add(textMsgData);
            
            debugLog("[9.1.25降级] 消息列表创建完成，包含 " + msgList.size() + " 个元素");
            
            // 4. 尝试使用 l0 方法发送 (9.1.25版本)
            Method l0Method = findMethodL0(vmDelegate.getClass());
            if (l0Method != null) {
                l0Method.setAccessible(true);
                debugLog("[9.1.25降级] 使用 l0 方法发送: " + Arrays.toString(l0Method.getParameterTypes()));
                l0Method.invoke(vmDelegate, msgList, bundle, null, null);
                debugLog("[9.1.25降级] l0 方法调用成功");
                return true;
            }
            
            // 5. 回退到标准发送方法
            Method sendMethod = findSendMethod(vmDelegate.getClass());
            if (sendMethod != null) {
                sendMethod.setAccessible(true);
                sendMethod.invoke(vmDelegate, msgList, bundle, null, "");
                debugLog("[9.1.25降级] 标准发送方法调用成功");
                return true;
            }
            
            debugLog("[9.1.25降级] 未找到发送方法");
            return false;
            
        } catch (Throwable t) {
            debugLog("[9.1.25降级] 异常: " + t.getMessage());
            XposedBridge.log(t);
            return false;
        }
    }
    
    /**
     * 使用 f0 方法发送引用回复 (9.1.25版本)
     * 方法签名: f0(List<c$e>, ReplyData(a), List?, boolean, Bundle)
     */
    private static boolean sendReplyViaMethodF0V9125(Context context, ClassLoader classLoader, Object vmDelegate,
                                                String messageText, long replyMsgId, long replyMsgSeq,
                                                String replyNick, String replyContent, Bundle bundle) {
        try {
            // 1. 创建输入元素列表 (c$e)
            List<Object> inputElements = createInputElementListV9125(classLoader, messageText);
            if (inputElements == null || inputElements.isEmpty()) {
                debugLog("[f0方法-9.1.25] 创建输入元素列表失败");
                return false;
            }
            
            // 2. 创建 ReplyData (a类)
            Object replyData = createReplyDataV9125(classLoader, replyMsgId, replyMsgSeq, replyNick, replyContent);
            if (replyData == null) {
                debugLog("[f0方法-9.1.25] 创建 ReplyData 失败");
                return false;
            }
            
            // 3. 查找 f0 方法 (5参数版本)
            Method f0Method = findMethodF0V9125(vmDelegate.getClass());
            if (f0Method == null) {
                debugLog("[f0方法-9.1.25] 未找到 f0 方法");
                return false;
            }
            
            // 4. 调用 f0 方法
            f0Method.setAccessible(true);
            debugLog("[f0方法-9.1.25] 调用 f0 方法: " + Arrays.toString(f0Method.getParameterTypes()));
            f0Method.invoke(vmDelegate, inputElements, replyData, null, false, bundle);
            
            debugLog("[f0方法-9.1.25] 调用成功");
            return true;
            
        } catch (Throwable t) {
            debugLog("[f0方法-9.1.25] 失败: " + t.getMessage());
            return false;
        }
    }
    
    /**
     * 查找 f0 方法 (9.1.25版本)
     * 方法签名: f0(List, ReplyData(a), List?, boolean, Bundle)
     */
    private static Method findMethodF0V9125(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals("f0")) {
                Class<?>[] paramTypes = method.getParameterTypes();
                // 5参数版本: (List, a, List, boolean, Bundle)
                if (paramTypes.length == 5 &&
                    paramTypes[0] == List.class &&
                    paramTypes[3] == boolean.class &&
                    paramTypes[4] == Bundle.class) {
                    debugLog("找到 f0 方法(9.1.25): " + Arrays.toString(paramTypes));
                    return method;
                }
            }
        }
        return null;
    }
    
    /**
     * 查找 l0 方法 (9.1.25版本发送方法)
     * 方法签名: l0(List, Bundle, Long?, String?)
     */
    private static Method findMethodL0(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals("l0")) {
                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length == 4 &&
                    paramTypes[0] == List.class &&
                    paramTypes[1] == Bundle.class) {
                    debugLog("找到 l0 方法: " + Arrays.toString(paramTypes));
                    return method;
                }
            }
        }
        return null;
    }
    
    /**
     * 创建输入元素列表 (9.1.25版本: c$e)
     * 字段结构: e=content(String), f=0(int), g=0(long), h=0(long), i=""(String)
     */
    private static List<Object> createInputElementListV9125(ClassLoader classLoader, String content) {
        // 尝试 c$e 类及其变体
        String[] classNames = {
            "com.tencent.mobileqq.aio.input.c$e",
            "com.tencent.mobileqq.aio.input.b$e",
            "com.tencent.mobileqq.aio.input.d$e"
        };
        
        for (String className : classNames) {
            try {
                Class<?> elementClass = XposedHelpers.findClass(className, classLoader);
                List<Object> result = createInputElementFromClassV9125(elementClass, content);
                if (result != null && !result.isEmpty()) {
                    debugLog("使用 " + className + " 创建输入元素成功");
                    return result;
                }
            } catch (XposedHelpers.ClassNotFoundError ignored) {
            } catch (Throwable t) {
                debugLog("尝试 " + className + " 失败: " + t.getMessage());
            }
        }
        return null;
    }
    
    /**
     * 从指定类创建输入元素 (9.1.25版本)
     * 字段结构: e=content(String), f=0(int), g=0(long), h=0(long), i=""(String)
     */
    private static List<Object> createInputElementFromClassV9125(Class<?> elementClass, String content) {
        try {
            java.lang.reflect.Constructor<?>[] constructors = elementClass.getDeclaredConstructors();
            
            // 优先尝试带参数的构造函数
            for (java.lang.reflect.Constructor<?> c : constructors) {
                c.setAccessible(true);
                Class<?>[] paramTypes = c.getParameterTypes();
                
                // 尝试找到合适的构造函数
                if (paramTypes.length >= 1 && paramTypes[0] == String.class) {
                    Object[] args = new Object[paramTypes.length];
                    args[0] = content;
                    for (int i = 1; i < paramTypes.length; i++) {
                        Class<?> type = paramTypes[i];
                        if (type == int.class) args[i] = 0;
                        else if (type == long.class) args[i] = 0L;
                        else if (type == boolean.class) args[i] = false;
                        else if (type == String.class) args[i] = "";
                        else args[i] = null;
                    }
                    
                    Object element = c.newInstance(args);
                    List<Object> list = new ArrayList<>();
                    list.add(element);
                    return list;
                }
            }
            
            // 尝试无参构造函数 + 设置字段
            for (java.lang.reflect.Constructor<?> c : constructors) {
                if (c.getParameterTypes().length == 0) {
                    c.setAccessible(true);
                    Object element = c.newInstance();
                    // 9.1.25版本字段: e=content, f=0, g=0, h=0, i=""
                    trySetField(element, "e", content);
                    trySetIntField(element, "f", 0);
                    trySetLongField(element, "g", 0L);
                    trySetLongField(element, "h", 0L);
                    trySetField(element, "i", "");
                    List<Object> list = new ArrayList<>();
                    list.add(element);
                    return list;
                }
            }
        } catch (Throwable t) {
            debugLog("createInputElementFromClassV9125 失败: " + t.getMessage());
        }
        return null;
    }
    
    /**
     * 创建 ReplyData (9.1.25版本: com.tencent.mobileqq.aio.input.a)
     * 日志格式: ReplyData(nickname=惑灵, replyText=惑灵:test, messageSequence=3524, messageId=7653411262500573649)
     */
    private static Object createReplyDataV9125(ClassLoader classLoader, long replyMsgId, long replyMsgSeq,
                                               String replyNick, String replyContent) {
        // 尝试多个可能的类名
        String[] classNames = {
            "com.tencent.mobileqq.aio.input.a",
            "com.tencent.mobileqq.aio.input.b"
        };
        
        for (String className : classNames) {
            try {
                Class<?> replyDataClass = XposedHelpers.findClass(className, classLoader);
                Object result = createReplyDataFromClassV9125(replyDataClass, replyMsgId, replyMsgSeq, replyNick, replyContent);
                if (result != null) {
                    debugLog("使用 " + className + " 创建 ReplyData 成功");
                    return result;
                }
            } catch (XposedHelpers.ClassNotFoundError ignored) {
            } catch (Throwable t) {
                debugLog("尝试 " + className + " 失败: " + t.getMessage());
            }
        }
        return null;
    }
    
    /**
     * 从指定类创建 ReplyData (9.1.25版本)
     * 日志格式: ReplyData(nickname=惑灵, replyText=惑灵:test, messageSequence=3524, messageId=7653411262500573649)
     */
    private static Object createReplyDataFromClassV9125(Class<?> replyDataClass, long replyMsgId, long replyMsgSeq,
                                                        String replyNick, String replyContent) {
        try {
            java.lang.reflect.Constructor<?>[] constructors = replyDataClass.getDeclaredConstructors();
            
            // 打印所有构造函数用于调试
            for (int i = 0; i < constructors.length; i++) {
                debugLog("ReplyData(9.1.25) 构造函数[" + i + "]: " + Arrays.toString(constructors[i].getParameterTypes()));
            }
            
            for (java.lang.reflect.Constructor<?> c : constructors) {
                c.setAccessible(true);
                Class<?>[] paramTypes = c.getParameterTypes();
                
                // 尝试 4 参数构造函数 (String, String, long, long)
                if (paramTypes.length == 4) {
                    try {
                        // 尝试不同的参数顺序
                        Object result = c.newInstance(replyNick, replyContent, replyMsgSeq, replyMsgId);
                        debugLog("ReplyData(9.1.25) 构造成功 (nick, content, seq, id)");
                        return result;
                    } catch (Throwable ignored) {}
                    
                    try {
                        Object result = c.newInstance(replyNick, replyContent, replyMsgId, replyMsgSeq);
                        debugLog("ReplyData(9.1.25) 构造成功 (nick, content, id, seq)");
                        return result;
                    } catch (Throwable ignored) {}
                }
            }
            
            // 尝试无参构造函数 + 设置字段
            for (java.lang.reflect.Constructor<?> c : constructors) {
                if (c.getParameterTypes().length == 0) {
                    c.setAccessible(true);
                    Object replyData = c.newInstance();
                    // 根据日志中的 toString 格式设置字段
                    trySetField(replyData, "nickname", replyNick);
                    trySetField(replyData, "replyText", replyContent);
                    trySetLongField(replyData, "messageSequence", replyMsgSeq);
                    trySetLongField(replyData, "messageId", replyMsgId);
                    debugLog("ReplyData(9.1.25) 通过无参构造+字段设置成功");
                    return replyData;
                }
            }
        } catch (Throwable t) {
            debugLog("createReplyDataFromClassV9125 失败: " + t.getMessage());
        }
        return null;
    }
    
    /**
     * 创建消息数据 (9.1.25版本: com.tencent.mobileqq.aio.msg.a.a)
     * 根据日志分析:
     * - ReplyElement 字段: e=msgId(long), f=seq(long), g=nick(String), h=content(String)
     * - TextElement 字段: e=content(String), f=0(int), g=0(long), h=0(long), i=""(String)
     */
    private static Object createMsgDataV9125(ClassLoader classLoader, boolean isReply, 
                                              long replyMsgId, long replyMsgSeq, 
                                              String replyNick, String content) {
        try {
            Class<?> msgDataClass = XposedHelpers.findClass(
                "com.tencent.mobileqq.aio.msg.a.a", classLoader);
            
            debugLog("[9.1.25] 找到 msg.a.a 类: " + msgDataClass.getName());
            
            Constructor<?>[] constructors = msgDataClass.getDeclaredConstructors();
            
            // 打印所有构造函数和字段用于调试
            debugLog("[9.1.25] msg.a.a 有 " + constructors.length + " 个构造函数");
            debugLog("[9.1.25] ┌─ msg.a.a 字段分析 ─┐");
            for (java.lang.reflect.Field field : msgDataClass.getDeclaredFields()) {
                String fieldType = field.getType().getName();
                debugLog("[9.1.25] │ " + field.getName() + " (" + fieldType + ")");
            }
            debugLog("[9.1.25] └─────────────────────┘");
            
            // 尝试创建实例
            for (Constructor<?> constructor : constructors) {
                constructor.setAccessible(true);
                Class<?>[] paramTypes = constructor.getParameterTypes();
                debugLog("[9.1.25] 尝试构造函数: " + Arrays.toString(paramTypes));
                
                try {
                    Object[] params = new Object[paramTypes.length];
                    for (int i = 0; i < paramTypes.length; i++) {
                        Class<?> type = paramTypes[i];
                        if (type == int.class) params[i] = 0;
                        else if (type == boolean.class) params[i] = false;
                        else if (type == long.class) params[i] = 0L;
                        else params[i] = null;
                    }
                    
                    Object msgData = constructor.newInstance(params);
                    
                    if (isReply) {
                        // 设置为 ReplyElement 类型
                        // 根据日志: e=msgId, f=seq, g=nick, h=content
                        trySetLongField(msgData, "e", replyMsgId);
                        trySetLongField(msgData, "f", replyMsgSeq);
                        trySetField(msgData, "g", replyNick);
                        trySetField(msgData, "h", content);  // replyContent
                        debugLog("[9.1.25] 创建 ReplyElement msg.a.a 成功");
                    } else {
                        // 设置为 TextElement 类型
                        // 根据日志: e=content, f=0, g=0, h=0, i=""
                        trySetField(msgData, "e", content);
                        trySetIntField(msgData, "f", 0);
                        trySetLongField(msgData, "g", 0L);
                        trySetLongField(msgData, "h", 0L);
                        trySetField(msgData, "i", "");
                        debugLog("[9.1.25] 创建 TextElement msg.a.a 成功");
                    }
                    
                    return msgData;
                } catch (Throwable t) {
                    debugLog("[9.1.25] 构造函数失败: " + t.getMessage());
                }
            }
            
            debugLog("[9.1.25] 无法创建 msg.a.a 对象");
            return null;
            
        } catch (Throwable t) {
            debugLog("[9.1.25] 创建 msg.a.a 异常: " + t.getMessage());
            return null;
        }
    }
}
