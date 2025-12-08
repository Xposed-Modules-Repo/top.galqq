/*
 * GalQQ - An Xposed module for QQ
 * Copyright (C) 2024 GalQQ contributors
 *
 * Rkey Hook implementation - Copied from QAuxiliary
 * Reference: StickerPanelEntryHooker.java, PicMd5Hook.java
 */
package top.galqq.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.json.JSONObject;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import top.galqq.config.ConfigManager;
import top.galqq.utils.FunProtoData;
import top.galqq.utils.XField;
import top.galqq.utils.XMethod;

/**
 * RkeyHook - Hook QQ内部协议获取图片rkey
 * 
 * 完全按照QAuxiliary的实现：
 * - StickerPanelEntryHooker.java
 * - PicMd5Hook.java
 * 
 * 通过Hook MsgRespHandler.dispatchRespMsg 拦截 OidbSvcTrpcTcp.0x9067_202 响应
 * 从中解析出 group_rkey 和 private_rkey
 */
public class RkeyHook {

    private static final String TAG = "GalQQ.RkeyHook";
    
    // rkey 存储（与 QAuxiliary 一致，使用 public static）
    public static String rkey_group;
    public static String rkey_private;
    
    // 是否已初始化
    private static boolean sInitialized = false;
    
    // 已记录的命令（避免重复打印）
    private static final Set<String> sLoggedCommands = new HashSet<>();
    
    // 统计信息
    private static int sTotalMsgCount = 0;
    private static int sOidbMsgCount = 0;
    
    /**
     * 调试日志输出（受 gal_debug_hook_log 配置开关控制）
     */
    private static void debugLog(String message) {
        try {
            if (ConfigManager.isDebugHookLogEnabled()) {
                XposedBridge.log(TAG + ": " + message);
            }
        } catch (Throwable ignored) {
        }
    }
    
    /**
     * 强制日志输出（不受配置开关控制，用于关键信息）
     */
    private static void forceLog(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    // 已记录的方法调用（避免重复打印）
    private static final Set<String> sLoggedMethods = new HashSet<>();

    /**
     * 初始化 rkey Hook
     * @param classLoader QQ的ClassLoader
     */
    public static void init(ClassLoader classLoader) {
        forceLog("★★★ init() 被调用 ★★★");
        
        if (sInitialized) {
            debugLog("已经初始化过，跳过");
            return;
        }
        
        try {
            debugLog("开始初始化 RkeyHook...");
            
            // 【重要】先初始化 Initiator
            top.galqq.utils.Initiator.init(classLoader);
            debugLog("Initiator 初始化完成");
            
            // 直接使用 classLoader 加载类
            Class<?> msgRespHandlerClass = classLoader.loadClass("mqq.app.msghandle.MsgRespHandler");
            debugLog("加载 MsgRespHandler 成功: " + msgRespHandlerClass);
            
            // 【调试】打印所有方法
            Method[] allMethods = msgRespHandlerClass.getDeclaredMethods();
            debugLog("MsgRespHandler 共有 " + allMethods.length + " 个方法:");
            for (Method m : allMethods) {
                debugLog("  " + m.getName() + "(" + Arrays.toString(m.getParameterTypes()) + ")");
            }
            
            // 【调试】Hook 所有方法，看看哪些会被调用
            int hookedCount = 0;
            for (Method method : allMethods) {
                try {
                    final String methodName = method.getName();
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            // 只记录第一次调用
                            if (!sLoggedMethods.contains(methodName)) {
                                sLoggedMethods.add(methodName);
                                debugLog("★ 方法被调用: " + methodName);
                                debugLog("  参数数量: " + param.args.length);
                                for (int i = 0; i < param.args.length; i++) {
                                    Object arg = param.args[i];
                                    debugLog("  args[" + i + "] = " + 
                                        (arg != null ? arg.getClass().getName() : "null"));
                                }
                            }
                            
                            // 特别处理可能包含 serviceCmd 的方法
                            if (param.args.length >= 2) {
                                Object secondArg = param.args[1];
                                if (secondArg != null) {
                                    String className = secondArg.getClass().getName();
                                    if (className.contains("FromServiceMsg") || className.contains("ServiceMsg")) {
                                        processFromServiceMsg(secondArg, methodName);
                                    }
                                }
                            }
                            // 也检查第一个参数
                            if (param.args.length >= 1) {
                                Object firstArg = param.args[0];
                                if (firstArg != null) {
                                    String className = firstArg.getClass().getName();
                                    if (className.contains("FromServiceMsg") || className.contains("ServiceMsg")) {
                                        processFromServiceMsg(firstArg, methodName);
                                    }
                                }
                            }
                        }
                    });
                    hookedCount++;
                } catch (Throwable t) {
                    debugLog("Hook方法失败 " + method.getName() + ": " + t.getMessage());
                }
            }
            
            debugLog("成功Hook " + hookedCount + " 个方法");
            
            sInitialized = true;
            forceLog("★★★ RkeyHook 初始化成功 ★★★");
            
        } catch (Throwable t) {
            forceLog("★★★ 初始化失败: " + t.getMessage() + " ★★★");
            XposedBridge.log(t);
        }
    }
    
    /**
     * 处理 FromServiceMsg 对象
     */
    private static void processFromServiceMsg(Object fromServiceMsg, String methodName) {
        try {
            sTotalMsgCount++;
            
            // 获取 serviceCmd
            String serviceCmd = null;
            try {
                Field serviceCmdField = fromServiceMsg.getClass().getDeclaredField("mServiceCmd");
                serviceCmdField.setAccessible(true);
                serviceCmd = (String) serviceCmdField.get(fromServiceMsg);
            } catch (Throwable t) {
                try {
                    Method m = fromServiceMsg.getClass().getMethod("getServiceCmd");
                    serviceCmd = (String) m.invoke(fromServiceMsg);
                } catch (Throwable ignored) {}
            }
            
            if (serviceCmd == null) {
                return;
            }
            
            // 判断是否是有趣的命令
            boolean isInteresting = serviceCmd.startsWith("OidbSvcTrpcTcp") || 
                                   serviceCmd.contains("Pic") || 
                                   serviceCmd.contains("pic") ||
                                   serviceCmd.contains("Image") ||
                                   serviceCmd.contains("image") ||
                                   serviceCmd.contains("rkey") ||
                                   serviceCmd.contains("Rkey") ||
                                   serviceCmd.contains("9067");
            
            if (serviceCmd.startsWith("OidbSvcTrpcTcp")) {
                sOidbMsgCount++;
            }
            
            // 记录有趣的命令
            String cmdKey = methodName + ":" + serviceCmd;
            if (isInteresting && !sLoggedCommands.contains(cmdKey)) {
                sLoggedCommands.add(cmdKey);
                debugLog("========== 发现命令 ==========");
                debugLog("方法: " + methodName);
                debugLog("命令: " + serviceCmd);
                debugLog("总消息: " + sTotalMsgCount + ", Oidb: " + sOidbMsgCount);
                debugLog("================================");
            }
            
            // 每100次打印统计
            if (sTotalMsgCount % 100 == 0) {
                debugLog("统计 - 总消息: " + sTotalMsgCount + 
                    ", 已发现方法: " + sLoggedMethods.size() + 
                    ", 已发现命令: " + sLoggedCommands.size());
            }
            
            // 处理 rkey 响应
            if ("OidbSvcTrpcTcp.0x9067_202".equals(serviceCmd)) {
                debugLog("★★★ 捕获到 rkey 响应 ★★★");
                processRkeyResponse(fromServiceMsg);
            }
            
        } catch (Throwable t) {
            // 忽略
        }
    }
    
    /**
     * 处理 rkey 响应
     */
    private static void processRkeyResponse(Object fromServiceMsg) {
        try {
            debugLog("========== 处理 rkey 响应 ==========");
            debugLog("FromServiceMsg 类型: " + fromServiceMsg.getClass().getName());
            
            // 先打印所有字段，找到正确的数据字段
            byte[] wupBuffer = null;
            Field[] fields = fromServiceMsg.getClass().getDeclaredFields();
            debugLog("FromServiceMsg 共有 " + fields.length + " 个字段:");
            
            for (Field field : fields) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(fromServiceMsg);
                    String valueStr;
                    
                    if (value == null) {
                        valueStr = "null";
                    } else if (value instanceof byte[]) {
                        byte[] bytes = (byte[]) value;
                        valueStr = "byte[" + bytes.length + "]";
                        // 如果是 byte[] 且长度大于 0，可能是我们要的数据
                        if (bytes.length > 0 && wupBuffer == null) {
                            wupBuffer = bytes;
                            debugLog("  ★ " + field.getName() + " = " + valueStr + " (可能是数据)");
                        } else {
                            debugLog("  " + field.getName() + " = " + valueStr);
                        }
                    } else {
                        valueStr = String.valueOf(value);
                        if (valueStr.length() > 100) {
                            valueStr = valueStr.substring(0, 100) + "...";
                        }
                        debugLog("  " + field.getName() + " = " + valueStr);
                    }
                } catch (Throwable t) {
                    debugLog("  " + field.getName() + " = [访问失败]");
                }
            }
            
            // 尝试通过方法获取数据
            if (wupBuffer == null) {
                debugLog("尝试通过方法获取数据...");
                Method[] methods = fromServiceMsg.getClass().getDeclaredMethods();
                for (Method method : methods) {
                    if (method.getParameterCount() == 0 && method.getReturnType() == byte[].class) {
                        try {
                            method.setAccessible(true);
                            byte[] result = (byte[]) method.invoke(fromServiceMsg);
                            if (result != null && result.length > 0) {
                                debugLog("  ★ " + method.getName() + "() 返回 byte[" + result.length + "]");
                                if (wupBuffer == null) {
                                    wupBuffer = result;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
            
            if (wupBuffer == null || wupBuffer.length == 0) {
                debugLog("未找到有效的数据字段");
                return;
            }
            
            debugLog("数据长度: " + wupBuffer.length);
            debugLog("数据前50字节: " + bytesToHex(wupBuffer, 50));
            
            // 尝试解析 protobuf
            try {
                FunProtoData data = new FunProtoData();
                byte[] unpacked = getUnpPackage(wupBuffer);
                debugLog("解包后长度: " + (unpacked != null ? unpacked.length : 0));
                
                data.fromBytes(unpacked);
                
                JSONObject obj = data.toJSON();
                String jsonStr = obj.toString(2);
                if (jsonStr.length() > 3000) {
                    jsonStr = jsonStr.substring(0, 3000) + "\n... [截断]";
                }
                debugLog("解析结果:\n" + jsonStr);
                
                // 根据 NapCatQQ 的 proto 结构解析
                // 路径: 4 (body) -> 4 (data) -> 1 (rkeyList array)
                // 每个 rkey 项: 1=rkey, 2=ttl, 4=time, 5=type (10=private, 20=group)
                try {
                    JSONObject body = obj.optJSONObject("4");
                    if (body == null) {
                        debugLog("未找到 field 4 (body)");
                        findRkeyInJson(obj, "");
                        return;
                    }
                    
                    JSONObject rkeyData = body.optJSONObject("4");
                    if (rkeyData == null) {
                        debugLog("未找到 field 4.4 (data)");
                        findRkeyInJson(obj, "");
                        return;
                    }
                    
                    // rkeyList 可能是数组或单个对象
                    Object rkeyListObj = rkeyData.opt("1");
                    if (rkeyListObj == null) {
                        debugLog("未找到 field 4.4.1 (rkeyList)");
                        findRkeyInJson(obj, "");
                        return;
                    }
                    
                    org.json.JSONArray rkeyList;
                    if (rkeyListObj instanceof org.json.JSONArray) {
                        rkeyList = (org.json.JSONArray) rkeyListObj;
                    } else if (rkeyListObj instanceof JSONObject) {
                        rkeyList = new org.json.JSONArray();
                        rkeyList.put(rkeyListObj);
                    } else {
                        debugLog("rkeyList 类型不正确: " + rkeyListObj.getClass().getName());
                        findRkeyInJson(obj, "");
                        return;
                    }
                    
                    debugLog("找到 " + rkeyList.length() + " 个 rkey 项");
                    
                    for (int i = 0; i < rkeyList.length(); i++) {
                        JSONObject rkeyItem = rkeyList.getJSONObject(i);
                        String rkey = rkeyItem.optString("1", null);
                        int type = rkeyItem.optInt("5", 0);
                        long ttl = rkeyItem.optLong("2", 0);
                        long time = rkeyItem.optLong("4", 0);
                        
                        debugLog("rkey[" + i + "]: type=" + type + ", ttl=" + ttl + ", time=" + time);
                        debugLog("  rkey=" + (rkey != null ? rkey.substring(0, Math.min(80, rkey.length())) + "..." : "null"));
                        
                        if (type == 10) {
                            // private
                            rkey_private = rkey;
                            debugLog("✓ 设置 rkey_private");
                        } else if (type == 20) {
                            // group
                            rkey_group = rkey;
                            debugLog("✓ 设置 rkey_group");
                        } else {
                            // 未知类型，尝试按顺序分配
                            if (rkey_group == null) {
                                rkey_group = rkey;
                                debugLog("✓ 设置 rkey_group (按顺序)");
                            } else if (rkey_private == null) {
                                rkey_private = rkey;
                                debugLog("✓ 设置 rkey_private (按顺序)");
                            }
                        }
                    }
                    
                    if (rkey_group != null || rkey_private != null) {
                        debugLog("★★★ rkey 获取成功 ★★★");
                        debugLog("rkey_group: " + (rkey_group != null ? "有效" : "无"));
                        debugLog("rkey_private: " + (rkey_private != null ? "有效" : "无"));
                    } else {
                        debugLog("未能提取有效的 rkey");
                        findRkeyInJson(obj, "");
                    }
                } catch (Throwable t) {
                    debugLog("解析rkey失败: " + t.getMessage());
                    debugLog("尝试遍历JSON查找rkey...");
                    findRkeyInJson(obj, "");
                }
            } catch (Throwable t) {
                debugLog("解析protobuf失败: " + t.getMessage());
            }
            
            debugLog("========================================");
            
        } catch (Throwable t) {
            debugLog("处理rkey响应失败: " + t.getMessage());
        }
    }
    
    /**
     * 递归查找JSON中可能的rkey
     */
    private static void findRkeyInJson(JSONObject json, String path) {
        try {
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = json.get(key);
                String currentPath = path.isEmpty() ? key : path + "." + key;
                
                if (value instanceof String) {
                    String strValue = (String) value;
                    // 检查是否像rkey（通常以&rkey=开头或包含特定格式）
                    if (strValue.contains("rkey") || strValue.contains("&") || strValue.length() > 50) {
                        debugLog("  [" + currentPath + "] = " + strValue.substring(0, Math.min(100, strValue.length())) + (strValue.length() > 100 ? "..." : ""));
                    }
                } else if (value instanceof JSONObject) {
                    findRkeyInJson((JSONObject) value, currentPath);
                } else if (value instanceof org.json.JSONArray) {
                    org.json.JSONArray arr = (org.json.JSONArray) value;
                    for (int i = 0; i < arr.length(); i++) {
                        Object item = arr.get(i);
                        if (item instanceof JSONObject) {
                            findRkeyInJson((JSONObject) item, currentPath + "[" + i + "]");
                        } else if (item instanceof String) {
                            String strItem = (String) item;
                            if (strItem.contains("rkey") || strItem.contains("&") || strItem.length() > 50) {
                                debugLog("  [" + currentPath + "[" + i + "]] = " + strItem.substring(0, Math.min(100, strItem.length())) + (strItem.length() > 100 ? "..." : ""));
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            debugLog("遍历JSON失败: " + t.getMessage());
        }
    }
    
    /**
     * 完整dump FromServiceMsg对象
     */
    private static void dumpFromServiceMsg(Object fromServiceMsg, String serviceCmd) {
        try {
            debugLog("========== Dump FromServiceMsg: " + serviceCmd + " ==========");
            debugLog("类型: " + fromServiceMsg.getClass().getName());
            
            // 遍历所有字段
            Class<?> clazz = fromServiceMsg.getClass();
            while (clazz != null && !clazz.equals(Object.class)) {
                Field[] fields = clazz.getDeclaredFields();
                for (Field field : fields) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(fromServiceMsg);
                        String valueStr;
                        
                        if (value == null) {
                            valueStr = "null";
                        } else if (value instanceof byte[]) {
                            byte[] bytes = (byte[]) value;
                            valueStr = "byte[" + bytes.length + "] = " + bytesToHex(bytes, 50);
                        } else {
                            valueStr = String.valueOf(value);
                            if (valueStr.length() > 200) {
                                valueStr = valueStr.substring(0, 200) + "...";
                            }
                        }
                        
                        debugLog("  ." + field.getName() + " = " + valueStr);
                    } catch (Throwable t) {
                        // 忽略
                    }
                }
                clazz = clazz.getSuperclass();
            }
            
            // 尝试获取并解析 WupBuffer
            try {
                Method getWupBufferMethod = fromServiceMsg.getClass().getMethod("getWupBuffer");
                byte[] wupBuffer = (byte[]) getWupBufferMethod.invoke(fromServiceMsg);
                
                if (wupBuffer != null && wupBuffer.length > 0) {
                    debugLog("WupBuffer 长度: " + wupBuffer.length);
                    
                    try {
                        FunProtoData data = new FunProtoData();
                        data.fromBytes(getUnpPackage(wupBuffer));
                        JSONObject json = data.toJSON();
                        String jsonStr = json.toString(2);
                        if (jsonStr.length() > 3000) {
                            jsonStr = jsonStr.substring(0, 3000) + "\n... [截断]";
                        }
                        debugLog("Protobuf解析结果:\n" + jsonStr);
                    } catch (Throwable t) {
                        debugLog("Protobuf解析失败: " + t.getMessage());
                    }
                }
            } catch (Throwable t) {
                debugLog("获取WupBuffer失败: " + t.getMessage());
            }
            
            debugLog("==============================================");
        } catch (Throwable t) {
            debugLog("Dump失败: " + t.getMessage());
        }
    }
    
    /**
     * 解包数据（去除前4字节头）
     * 完全按照 QAuxiliary 的 getUnpPackage 方法
     */
    private static byte[] getUnpPackage(byte[] b) {
        if (b == null) {
            return null;
        }
        if (b.length < 4) {
            return b;
        }
        if (b[0] == 0) {
            return Arrays.copyOfRange(b, 4, b.length);
        } else {
            return b;
        }
    }
    
    /**
     * 字节数组转十六进制字符串
     */
    private static String bytesToHex(byte[] bytes, int maxLen) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        int len = Math.min(bytes.length, maxLen);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X ", bytes[i] & 0xFF));
        }
        if (bytes.length > maxLen) {
            sb.append("...");
        }
        return sb.toString();
    }
    
    /**
     * 检查是否有有效的 rkey
     */
    public static boolean hasValidRkey() {
        return rkey_group != null && !rkey_group.isEmpty() 
            && rkey_private != null && !rkey_private.isEmpty();
    }
    
    /**
     * 获取统计信息
     */
    public static String getStats() {
        return "总消息: " + sTotalMsgCount + ", Oidb消息: " + sOidbMsgCount + 
               ", 已发现命令数: " + sLoggedCommands.size() +
               ", rkey_group: " + (rkey_group != null ? "有" : "无") +
               ", rkey_private: " + (rkey_private != null ? "有" : "无");
    }
    
    /**
     * 获取所有已发现的 OidbSvcTrpcTcp 命令
     */
    public static Set<String> getDiscoveredCommands() {
        return new HashSet<>(sLoggedCommands);
    }
}
