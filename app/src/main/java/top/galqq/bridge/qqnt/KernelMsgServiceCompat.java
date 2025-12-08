package top.galqq.bridge.qqnt;

import android.content.Context;
import android.widget.Toast;
import androidx.annotation.NonNull;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * 完全模仿QAuxiliary的KernelMsgServiceCompat
 * @see io.github.qauxv.bridge.kernelcompat.KernelMsgServiceCompat
 */
public class KernelMsgServiceCompat {

    private static final String TAG = "GalQQ.KernelMsgServiceCompat";
    private Object msgService; // IKernelMsgService实例
    private Context context;

    public KernelMsgServiceCompat(@NonNull Object service, Context context) {
        this.msgService = service;
        this.context = context;
    }

    public void sendMsg(long msgId, Object contact, ArrayList<?> msgElements, HashMap<Integer, ?> msgAttrs, Object callback) throws Exception {
        // 完全模仿QAuxiliary的实现，但使用反射避免编译时依赖
        try {
            Class.forName("com.tencent.qqnt.kernel.nativeinterface.Contact");
            // 使用反射调用sendMsg，避免编译时类型检查
            msgService = getMsgService();
            XposedHelpers.callMethod(msgService, "sendMsg", msgId, contact, msgElements, msgAttrs, callback);
            return;
        } catch (ClassNotFoundException ignored) {
        }
        try {
            Class.forName("com.tencent.qqnt.kernelpublic.nativeinterface.Contact");
            // 使用反射调用sendMsg，避免编译时类型检查
            msgService = getMsgService();
            XposedHelpers.callMethod(msgService, "sendMsg", msgId, contact, msgElements, msgAttrs, callback);
            return;
        } catch (ClassNotFoundException ignored) {
        }
        throw new RuntimeException("IKernelMsgService.sendMsg,Contact not supported");
    }
    
    private Object getMsgService() {
        // 如果msgService是Proxy实例，获取其包装的实际服务
        if (msgService instanceof Proxy) {
            try {
                return XposedHelpers.callMethod(msgService, "getService");
            } catch (Exception e) {
                // XposedBridge.log(TAG + ": Failed to get service from proxy: " + e.getMessage());
            }
        }
        return msgService;
    }

    /**
     * 尝试使用所有可用的类加载器加载TextElement类
     */
    private static Class<?> tryLoadTextElementWithAllClassLoaders(Context context, String content) throws Exception {
        // XposedBridge.log(TAG + ": 开始尝试使用所有可用的类加载器加载TextElement");
        
        // 尝试列出所有可能的类名
        String[] possibleClassNames = {
            "com.tencent.qqnt.kernel.nativeinterface.TextElement",
            "com.tencent.qqnt.kernelpublic.nativeinterface.TextElement",
            "com.tencent.mobileqq.data.TextElement",
            "com.tencent.mobileqq.message.TextElement",
            "com.tencent.qqnt.msg.api.TextElement",
            "com.tencent.qqnt.kernel.msg.TextElement",
            "com.tencent.qqnt.kernel.message.TextElement",
            "com.tencent.qqnt.api.msg.TextElement",
            "com.tencent.qqnt.msgservice.TextElement",
            "com.tencent.qqnt.kernel.nativeinterface.element.TextElement",
            "com.tencent.qqnt.kernelpublic.nativeinterface.element.TextElement"
        };
        
        // 获取当前线程的上下文类加载器
        try {
            ClassLoader threadCL = Thread.currentThread().getContextClassLoader();
            // XposedBridge.log(TAG + ": 尝试使用线程上下文类加载器: " + threadCL.getClass().getName());
            for (String className : possibleClassNames) {
                try {
                    Class<?> clazz = threadCL.loadClass(className);
                    // XposedBridge.log(TAG + ": 使用线程上下文类加载器成功加载类: " + className);
                    if (className.contains("TextElement")) {
                        return clazz;
                    }
                } catch (ClassNotFoundException e) {
                    // XposedBridge.log(TAG + ": 线程上下文类加载器加载类失败: " + className);
                }
            }
        } catch (Exception e) {
            // XposedBridge.log(TAG + ": 获取线程上下文类加载器失败: " + e.getMessage());
        }
        
        // 获取所有已加载的类并查找它们的类加载器
        try {
            // XposedBridge.log(TAG + ": 尝试从已加载的类中查找类加载器");
            ClassLoader[] loaders = findClassLoadersFromLoadedClasses(context);
            for (ClassLoader loader : loaders) {
                // XposedBridge.log(TAG + ": 尝试使用找到的类加载器: " + loader.getClass().getName());
                for (String className : possibleClassNames) {
                    try {
                        Class<?> clazz = loader.loadClass(className);
                        // XposedBridge.log(TAG + ": 使用找到的类加载器成功加载类: " + className);
                        if (className.contains("TextElement")) {
                            return clazz;
                        }
                    } catch (ClassNotFoundException e) {
                        // XposedBridge.log(TAG + ": 该类加载器加载类失败: " + className);
                    }
                }
            }
        } catch (Exception e) {
            // XposedBridge.log(TAG + ": 从已加载的类中查找类加载器失败: " + e.getMessage());
        }
        
        // 尝试使用Parent ClassLoader
        try {
            ClassLoader contextCL = context.getClassLoader();
            ClassLoader parentCL = contextCL.getParent();
            while (parentCL != null) {
                // XposedBridge.log(TAG + ": 尝试使用父类加载器: " + parentCL.getClass().getName());
                for (String className : possibleClassNames) {
                    try {
                        Class<?> clazz = parentCL.loadClass(className);
                        // XposedBridge.log(TAG + ": 使用父类加载器成功加载类: " + className);
                        if (className.contains("TextElement")) {
                            return clazz;
                        }
                    } catch (ClassNotFoundException e) {
                        // XposedBridge.log(TAG + ": 父类加载器加载类失败: " + className);
                    }
                }
                parentCL = parentCL.getParent();
            }
        } catch (Exception e) {
            // XposedBridge.log(TAG + ": 尝试父类加载器失败: " + e.getMessage());
        }
        
        // 尝试使用反射获取QQ应用中的所有类
        try {
            // XposedBridge.log(TAG + ": 尝试使用反射获取QQ应用中的所有类");
            ClassLoader[] loaders = new ClassLoader[] {
                context.getClassLoader(),
                top.galqq.utils.Initiator.getHostClassLoader(),
                ClassLoader.getSystemClassLoader(),
                KernelMsgServiceCompat.class.getClassLoader()
            };
            
            for (ClassLoader loader : loaders) {
                if (loader == null) continue;
                
                // XposedBridge.log(TAG + ": 尝试从类加载器获取所有类: " + loader.getClass().getName());
                
                // 尝试获取类加载器的父类
                try {
                    Class<?> loaderClass = loader.getClass();
                    // XposedBridge.log(TAG + ": 类加载器类: " + loaderClass.getName());
                    
                    // 尝试获取类加载器的方法
                    java.lang.reflect.Method[] methods = loaderClass.getDeclaredMethods();
                    for (java.lang.reflect.Method method : methods) {
                        if (method.getName().contains("loadClass") || 
                            method.getName().contains("findClass") || 
                            method.getName().contains("getPackage")) {
                            // XposedBridge.log(TAG + ": 找到可能的方法: " + method.getName());
                        }
                    }
                } catch (Exception e) {
                    // XposedBridge.log(TAG + ": 获取类加载器信息失败: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            // XposedBridge.log(TAG + ": 反射获取类失败: " + e.getMessage());
        }
        
        throw new ClassNotFoundException("无法使用任何类加载器加载TextElement类");
    }
    
    /**
     * 从已加载的类中查找类加载器
     */
    private static ClassLoader[] findClassLoadersFromLoadedClasses(Context context) {
        try {
            // 尝试获取一些已知的QQ类，然后获取它们的类加载器
            ClassLoader[] loaders = new ClassLoader[10];
            int index = 0;
            
            // 尝试获取一些QQ相关的类
            String[] classNames = {
                "com.tencent.mobileqq.app.QQAppInterface",
                "com.tencent.mobileqq.activity.BaseActivity",
                "com.tencent.common.app.AppInterface",
                "com.tencent.qphone.base.util.BaseApplication"
            };
            
            for (String className : classNames) {
                try {
                    Class<?> clazz = Class.forName(className);
                    ClassLoader loader = clazz.getClassLoader();
                    if (loader != null && !containsLoader(loaders, index, loader)) {
                        // XposedBridge.log(TAG + ": 找到类加载器来自类 " + className + ": " + loader.getClass().getName());
                        loaders[index++] = loader;
                        if (index >= loaders.length) break;
                    }
                } catch (ClassNotFoundException e) {
                    // XposedBridge.log(TAG + ": 类 " + className + " 未找到");
                }
            }
            
            // 如果没有找到足够的类加载器，添加一些默认的
            if (index < loaders.length) {
                loaders[index++] = context.getClassLoader();
                if (top.galqq.utils.Initiator.getHostClassLoader() != null) {
                    loaders[index++] = top.galqq.utils.Initiator.getHostClassLoader();
                }
                loaders[index++] = ClassLoader.getSystemClassLoader();
                loaders[index++] = KernelMsgServiceCompat.class.getClassLoader();
            }
            
            // 返回非空的类加载器数组
            ClassLoader[] result = new ClassLoader[index];
            System.arraycopy(loaders, 0, result, 0, index);
            return result;
        } catch (Exception e) {
            // XposedBridge.log(TAG + ": 查找类加载器时出错: " + e.getMessage());
            // 返回一些默认的类加载器
            return new ClassLoader[] {
                context.getClassLoader(),
                top.galqq.utils.Initiator.getHostClassLoader(),
                ClassLoader.getSystemClassLoader(),
                KernelMsgServiceCompat.class.getClassLoader()
            };
        }
    }
    
    /**
     * 检查类加载器数组中是否已包含指定的类加载器
     */
    private static boolean containsLoader(ClassLoader[] loaders, int size, ClassLoader loader) {
        for (int i = 0; i < size; i++) {
            if (loaders[i] == loader) {
                return true;
            }
        }
        return false;
    }

    /**
     * 创建TextElement
     */
    public static Object createTextElement(Context context, String content) throws Exception {
        // XposedBridge.log(TAG + ": 尝试创建TextElement，内容: " + content);
        
        // 首先尝试使用所有可用的类加载器
        try {
            Class<?> textElementClass = tryLoadTextElementWithAllClassLoaders(context, content);
            Object textElement = createTextElementInstance(textElementClass, content);
            // XposedBridge.log(TAG + ": 成功创建TextElement实例，类型: " + textElement.getClass().getName());
            return textElement;
        } catch (Exception e) {
            // XposedBridge.log(TAG + ": 使用所有可用类加载器创建TextElement失败: " + e.getMessage());
        }
        
        // 如果所有类加载器都失败，尝试原来的方法
        // XposedBridge.log(TAG + ": 回退到原来的方法");
        
        // 尝试两个可能的包名
        try {
            // XposedBridge.log(TAG + ": 尝试加载类: com.tencent.qqnt.kernel.nativeinterface.TextElement");
            ClassLoader cl = context.getClassLoader();
            // XposedBridge.log(TAG + ": 使用Context类加载器: " + cl.getClass().getName());
            Class<?> kTextElement = XposedHelpers.findClass("com.tencent.qqnt.kernel.nativeinterface.TextElement", cl);
            Object instance = createTextElementInstance(kTextElement, content);
            // XposedBridge.log(TAG + ": 成功创建TextElement实例，类型: " + instance.getClass().getName());
            return instance;
        } catch (Throwable t) {
            // XposedBridge.log(TAG + ": 加载kernel.nativeinterface.TextElement失败: " + t.getMessage());
            // XposedBridge.log(TAG + ": 失败原因: " + t.getClass().getName());
        }
        
        try {
            // XposedBridge.log(TAG + ": 尝试加载类: com.tencent.qqnt.kernelpublic.nativeinterface.TextElement");
            ClassLoader cl = context.getClassLoader();
            // XposedBridge.log(TAG + ": 使用Context类加载器: " + cl.getClass().getName());
            Class<?> kTextElement = XposedHelpers.findClass("com.tencent.qqnt.kernelpublic.nativeinterface.TextElement", cl);
            Object instance = createTextElementInstance(kTextElement, content);
            // XposedBridge.log(TAG + ": 成功创建TextElement实例，类型: " + instance.getClass().getName());
            return instance;
        } catch (Throwable t) {
            // XposedBridge.log(TAG + ": 加载kernelpublic.nativeinterface.TextElement失败: " + t.getMessage());
            // XposedBridge.log(TAG + ": 失败原因: " + t.getClass().getName());
        }
        
        // 尝试使用Initiator的类加载器
        try {
            // XposedBridge.log(TAG + ": 尝试使用Initiator的类加载器加载TextElement");
            ClassLoader cl = top.galqq.utils.Initiator.getHostClassLoader();
            if (cl != null) {
                // XposedBridge.log(TAG + ": 使用Initiator类加载器: " + cl.getClass().getName());
                Class<?> kTextElement = XposedHelpers.findClass("com.tencent.qqnt.kernel.nativeinterface.TextElement", cl);
                Object instance = createTextElementInstance(kTextElement, content);
                // XposedBridge.log(TAG + ": 成功创建TextElement实例，类型: " + instance.getClass().getName());
                return instance;
            }
        } catch (Throwable t) {
            // XposedBridge.log(TAG + ": 使用Initiator类加载器加载TextElement失败: " + t.getMessage());
            // XposedBridge.log(TAG + ": 失败原因: " + t.getClass().getName());
        }
        
        // 尝试使用Initiator的类加载器加载kernelpublic包
        try {
            // XposedBridge.log(TAG + ": 尝试使用Initiator的类加载器加载kernelpublic.TextElement");
            ClassLoader cl = top.galqq.utils.Initiator.getHostClassLoader();
            if (cl != null) {
                // XposedBridge.log(TAG + ": 使用Initiator类加载器: " + cl.getClass().getName());
                Class<?> kTextElement = XposedHelpers.findClass("com.tencent.qqnt.kernelpublic.nativeinterface.TextElement", cl);
                Object instance = createTextElementInstance(kTextElement, content);
                // XposedBridge.log(TAG + ": 成功创建TextElement实例，类型: " + instance.getClass().getName());
                return instance;
            }
        } catch (Throwable t) {
            // XposedBridge.log(TAG + ": 使用Initiator类加载器加载kernelpublic.TextElement失败: " + t.getMessage());
            // XposedBridge.log(TAG + ": 失败原因: " + t.getClass().getName());
        }
        
        // 尝试使用系统类加载器
        try {
            // XposedBridge.log(TAG + ": 尝试使用系统类加载器加载TextElement");
            ClassLoader cl = ClassLoader.getSystemClassLoader();
            // XposedBridge.log(TAG + ": 使用系统类加载器: " + cl.getClass().getName());
            Class<?> kTextElement = XposedHelpers.findClass("com.tencent.qqnt.kernel.nativeinterface.TextElement", cl);
            Object instance = createTextElementInstance(kTextElement, content);
            // XposedBridge.log(TAG + ": 成功创建TextElement实例，类型: " + instance.getClass().getName());
            return instance;
        } catch (Throwable t) {
            // XposedBridge.log(TAG + ": 使用系统类加载器加载TextElement失败: " + t.getMessage());
            // XposedBridge.log(TAG + ": 失败原因: " + t.getClass().getName());
        }
        
        // 尝试使用当前类的类加载器
        try {
            // XposedBridge.log(TAG + ": 尝试使用当前类的类加载器加载TextElement");
            ClassLoader cl = KernelMsgServiceCompat.class.getClassLoader();
            // XposedBridge.log(TAG + ": 使用当前类的类加载器: " + cl.getClass().getName());
            Class<?> kTextElement = XposedHelpers.findClass("com.tencent.qqnt.kernel.nativeinterface.TextElement", cl);
            Object instance = createTextElementInstance(kTextElement, content);
            // XposedBridge.log(TAG + ": 成功创建TextElement实例，类型: " + instance.getClass().getName());
            return instance;
        } catch (Throwable t) {
            // XposedBridge.log(TAG + ": 使用当前类的类加载器加载TextElement失败: " + t.getMessage());
            // XposedBridge.log(TAG + ": 失败原因: " + t.getClass().getName());
        }
        
        // 记录所有尝试过的类加载器信息
        // XposedBridge.log(TAG + ": 所有类加载器尝试失败，记录类加载器信息:");
        // XposedBridge.log(TAG + ":   Context ClassLoader: " + context.getClassLoader().getClass().getName());
        // XposedBridge.log(TAG + ":   Initiator HostClassLoader: " + (top.galqq.utils.Initiator.getHostClassLoader() != null ? top.galqq.utils.Initiator.getHostClassLoader().getClass().getName() : "null"));
        // XposedBridge.log(TAG + ":   System ClassLoader: " + ClassLoader.getSystemClassLoader().getClass().getName());
        // XposedBridge.log(TAG + ":   Current Class ClassLoader: " + KernelMsgServiceCompat.class.getClassLoader().getClass().getName());
        
        // 记录完整的堆栈跟踪
        // XposedBridge.log(TAG + ": 完整异常堆栈跟踪:");
        Exception e = new RuntimeException("TextElement class not found");
        StackTraceElement[] stackTrace = e.getStackTrace();
        for (StackTraceElement element : stackTrace) {
            // XposedBridge.log(TAG + ":   " + element.toString());
        }
        
        throw new RuntimeException("TextElement class not found");
    }
    
    /**
     * 尝试多种方式创建TextElement实例
     */
    private static Object createTextElementInstance(Class<?> textElementClass, String content) throws Exception {
        // XposedBridge.log(TAG + ": 尝试创建TextElement实例，类: " + textElementClass.getName());
        
        // 首先检查类的所有字段和方法
        try {
            // XposedBridge.log(TAG + ": 检查TextElement类的字段:");
            java.lang.reflect.Field[] fields = textElementClass.getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                // XposedBridge.log(TAG + ":  字段: " + field.getName() + ", 类型: " + field.getType().getName());
            }
            
            // XposedBridge.log(TAG + ": 检查TextElement类的方法:");
            java.lang.reflect.Method[] methods = textElementClass.getDeclaredMethods();
            for (java.lang.reflect.Method method : methods) {
                // XposedBridge.log(TAG + ":  方法: " + method.getName() + ", 参数: " + java.util.Arrays.toString(method.getParameterTypes()));
            }
        } catch (Exception e) {
            // XposedBridge.log(TAG + ": 检查字段和方法失败: " + e.getMessage());
        }
        
        // 尝试所有可能的构造方法
        java.lang.reflect.Constructor<?>[] constructors = textElementClass.getConstructors();
        // XposedBridge.log(TAG + ": 找到 " + constructors.length + " 个构造方法");
        
        for (java.lang.reflect.Constructor<?> constructor : constructors) {
            Class<?>[] paramTypes = constructor.getParameterTypes();
            // XposedBridge.log(TAG + ": 构造方法参数类型: " + java.util.Arrays.toString(paramTypes));
            
            // 尝试匹配不同的参数组合
            try {
                if (paramTypes.length == 0) {
                    // 无参构造方法
                    Object instance = constructor.newInstance();
                    // XposedBridge.log(TAG + ": 成功创建无参构造方法实例");
                    
                    // 优先使用content字段，因为日志显示这是正确的字段名
                    try {
                        XposedHelpers.setObjectField(instance, "content", content);
                        // XposedBridge.log(TAG + ": 成功设置content字段");
                        return instance;
                    } catch (Exception e) {
                        // XposedBridge.log(TAG + ": 设置content字段失败: " + e.getMessage());
                    }
                    
                    // 如果content字段失败，尝试setContent方法
                    try {
                        XposedHelpers.callMethod(instance, "setContent", content);
                        // XposedBridge.log(TAG + ": 成功调用setContent方法");
                        return instance;
                    } catch (Exception e) {
                        // XposedBridge.log(TAG + ": 调用setContent方法失败: " + e.getMessage());
                    }
                    
                    // 尝试其他可能的字段名
                    String[] possibleFieldNames = {"text", "mText", "mContent", "str", "mStr"};
                    for (String fieldName : possibleFieldNames) {
                        try {
                            XposedHelpers.setObjectField(instance, fieldName, content);
                            // XposedBridge.log(TAG + ": 成功设置字段 " + fieldName);
                            return instance;
                        } catch (Exception e) {
                            // XposedBridge.log(TAG + ": 设置字段 " + fieldName + " 失败: " + e.getMessage());
                        }
                    }
                    
                    // 尝试其他可能的方法名
                    String[] possibleMethodNames = {"setText", "setTextContent", "setStr"};
                    for (String methodName : possibleMethodNames) {
                        try {
                            XposedHelpers.callMethod(instance, methodName, content);
                            // XposedBridge.log(TAG + ": 成功调用方法 " + methodName);
                            return instance;
                        } catch (Exception e) {
                            // XposedBridge.log(TAG + ": 调用方法 " + methodName + " 失败: " + e.getMessage());
                        }
                    }
                    
                    // 如果都失败了，返回实例，让后续代码处理
                    // XposedBridge.log(TAG + ": 无法设置文本内容，但返回实例");
                    return instance;
                } else if (paramTypes.length == 1) {
                    if (paramTypes[0] == String.class) {
                        // String参数构造方法
                        Object instance = constructor.newInstance(content);
                        // XposedBridge.log(TAG + ": 使用String参数构造方法创建TextElement实例");
                        return instance;
                    }
                }
            } catch (Exception e) {
                // XposedBridge.log(TAG + ": 尝试构造方法失败: " + e.getMessage());
            }
        }
        
        // 尝试使用XposedHelpers创建实例
        try {
            Object instance = XposedHelpers.newInstance(textElementClass);
            // XposedBridge.log(TAG + ": 使用XposedHelpers创建TextElement实例");
            
            // 优先使用content字段
            try {
                XposedHelpers.setObjectField(instance, "content", content);
                // XposedBridge.log(TAG + ": 成功设置content字段");
                return instance;
            } catch (Exception e) {
                // XposedBridge.log(TAG + ": 设置content字段失败: " + e.getMessage());
            }
            
            // 如果content字段失败，尝试setContent方法
            try {
                XposedHelpers.callMethod(instance, "setContent", content);
                // XposedBridge.log(TAG + ": 成功调用setContent方法");
                return instance;
            } catch (Exception e) {
                // XposedBridge.log(TAG + ": 调用setContent方法失败: " + e.getMessage());
            }
            
            // 尝试其他可能的字段名
            String[] possibleFieldNames = {"text", "mText", "mContent", "str", "mStr"};
            for (String fieldName : possibleFieldNames) {
                try {
                    XposedHelpers.setObjectField(instance, fieldName, content);
                    // XposedBridge.log(TAG + ": 成功设置字段 " + fieldName);
                    return instance;
                } catch (Exception e) {
                    // XposedBridge.log(TAG + ": 设置字段 " + fieldName + " 失败: " + e.getMessage());
                }
            }
            
            // 尝试其他可能的方法名
            String[] possibleMethodNames = {"setText", "setTextContent", "setStr"};
            for (String methodName : possibleMethodNames) {
                try {
                    XposedHelpers.callMethod(instance, methodName, content);
                    // XposedBridge.log(TAG + ": 成功调用方法 " + methodName);
                    return instance;
                } catch (Exception e) {
                    // XposedBridge.log(TAG + ": 调用方法 " + methodName + " 失败: " + e.getMessage());
                }
            }
            
            // 如果都失败了，返回实例，让后续代码处理
            // XposedBridge.log(TAG + ": 无法设置文本内容，但返回实例");
            return instance;
        } catch (Exception e) {
            // XposedBridge.log(TAG + ": 使用XposedHelpers创建实例失败: " + e.getMessage());
        }
        
        throw new RuntimeException("无法创建TextElement实例");
    }

    /**
     * 创建MsgElement包含TextElement
     */
    public static Object createMsgElementWithText(Context context, String content) throws Exception {
        // XposedBridge.log(TAG + ": 尝试创建MsgElementWithText，内容: " + content);
        
        Object textElement = createTextElement(context, content);
        // XposedBridge.log(TAG + ": 成功获取TextElement: " + textElement.getClass().getName());
        
        // 尝试两个可能的包名
        try {
            // XposedBridge.log(TAG + ": 尝试加载类: com.tencent.qqnt.kernel.nativeinterface.MsgElement");
            ClassLoader cl = context.getClassLoader();
            // XposedBridge.log(TAG + ": 使用Context类加载器: " + cl.getClass().getName());
            Class<?> kMsgElement = XposedHelpers.findClass("com.tencent.qqnt.kernel.nativeinterface.MsgElement", cl);
            Object msgElement = kMsgElement.newInstance();
            XposedHelpers.callMethod(msgElement, "setTextElement", textElement);
            // XposedBridge.log(TAG + ": 成功创建MsgElement实例，类型: " + msgElement.getClass().getName());
            return msgElement;
        } catch (Throwable t) {
            // XposedBridge.log(TAG + ": 加载kernel.nativeinterface.MsgElement失败: " + t.getMessage());
            // XposedBridge.log(TAG + ": 失败原因: " + t.getClass().getName());
        }
        
        try {
            // XposedBridge.log(TAG + ": 尝试加载类: com.tencent.qqnt.kernelpublic.nativeinterface.MsgElement");
            ClassLoader cl = context.getClassLoader();
            // XposedBridge.log(TAG + ": 使用Context类加载器: " + cl.getClass().getName());
            Class<?> kMsgElement = XposedHelpers.findClass("com.tencent.qqnt.kernelpublic.nativeinterface.MsgElement", cl);
            Object msgElement = kMsgElement.newInstance();
            XposedHelpers.callMethod(msgElement, "setTextElement", textElement);
            // XposedBridge.log(TAG + ": 成功创建MsgElement实例，类型: " + msgElement.getClass().getName());
            return msgElement;
        } catch (Throwable t) {
            // XposedBridge.log(TAG + ": 加载kernelpublic.nativeinterface.MsgElement失败: " + t.getMessage());
            // XposedBridge.log(TAG + ": 失败原因: " + t.getClass().getName());
        }
        
        // 尝试使用Initiator的类加载器
        try {
            // XposedBridge.log(TAG + ": 尝试使用Initiator的类加载器加载MsgElement");
            ClassLoader cl = top.galqq.utils.Initiator.getHostClassLoader();
            if (cl != null) {
                // XposedBridge.log(TAG + ": 使用Initiator类加载器: " + cl.getClass().getName());
                Class<?> kMsgElement = XposedHelpers.findClass("com.tencent.qqnt.kernel.nativeinterface.MsgElement", cl);
                Object msgElement = kMsgElement.newInstance();
                XposedHelpers.callMethod(msgElement, "setTextElement", textElement);
                // XposedBridge.log(TAG + ": 成功创建MsgElement实例，类型: " + msgElement.getClass().getName());
                return msgElement;
            }
        } catch (Throwable t) {
            // XposedBridge.log(TAG + ": 使用Initiator类加载器加载MsgElement失败: " + t.getMessage());
            // XposedBridge.log(TAG + ": 失败原因: " + t.getClass().getName());
        }
        
        // 尝试使用Initiator的类加载器加载kernelpublic包
        try {
            // XposedBridge.log(TAG + ": 尝试使用Initiator的类加载器加载kernelpublic.MsgElement");
            ClassLoader cl = top.galqq.utils.Initiator.getHostClassLoader();
            if (cl != null) {
                // XposedBridge.log(TAG + ": 使用Initiator类加载器: " + cl.getClass().getName());
                Class<?> kMsgElement = XposedHelpers.findClass("com.tencent.qqnt.kernelpublic.nativeinterface.MsgElement", cl);
                Object msgElement = kMsgElement.newInstance();
                XposedHelpers.callMethod(msgElement, "setTextElement", textElement);
                // XposedBridge.log(TAG + ": 成功创建MsgElement实例，类型: " + msgElement.getClass().getName());
                return msgElement;
            }
        } catch (Throwable t) {
            // XposedBridge.log(TAG + ": 使用Initiator类加载器加载kernelpublic.MsgElement失败: " + t.getMessage());
            // XposedBridge.log(TAG + ": 失败原因: " + t.getClass().getName());
        }
        
        // 尝试使用系统类加载器
        try {
            // XposedBridge.log(TAG + ": 尝试使用系统类加载器加载MsgElement");
            ClassLoader cl = ClassLoader.getSystemClassLoader();
            // XposedBridge.log(TAG + ": 使用系统类加载器: " + cl.getClass().getName());
            Class<?> kMsgElement = XposedHelpers.findClass("com.tencent.qqnt.kernel.nativeinterface.MsgElement", cl);
            Object msgElement = kMsgElement.newInstance();
            XposedHelpers.callMethod(msgElement, "setTextElement", textElement);
            // XposedBridge.log(TAG + ": 成功创建MsgElement实例，类型: " + msgElement.getClass().getName());
            return msgElement;
        } catch (Throwable t) {
            // XposedBridge.log(TAG + ": 使用系统类加载器加载MsgElement失败: " + t.getMessage());
            // XposedBridge.log(TAG + ": 失败原因: " + t.getClass().getName());
        }
        
        // 尝试使用当前类的类加载器
        try {
            // XposedBridge.log(TAG + ": 尝试使用当前类的类加载器加载MsgElement");
            ClassLoader cl = KernelMsgServiceCompat.class.getClassLoader();
            // XposedBridge.log(TAG + ": 使用当前类的类加载器: " + cl.getClass().getName());
            Class<?> kMsgElement = XposedHelpers.findClass("com.tencent.qqnt.kernel.nativeinterface.MsgElement", cl);
            Object msgElement = kMsgElement.newInstance();
            XposedHelpers.callMethod(msgElement, "setTextElement", textElement);
            // XposedBridge.log(TAG + ": 成功创建MsgElement实例，类型: " + msgElement.getClass().getName());
            return msgElement;
        } catch (Throwable t) {
            // XposedBridge.log(TAG + ": 使用当前类的类加载器加载MsgElement失败: " + t.getMessage());
            // XposedBridge.log(TAG + ": 失败原因: " + t.getClass().getName());
        }
        
        // 记录所有尝试过的类加载器信息
        // XposedBridge.log(TAG + ": 所有类加载器尝试失败，记录类加载器信息:");
        // XposedBridge.log(TAG + ":   Context ClassLoader: " + context.getClassLoader().getClass().getName());
        // XposedBridge.log(TAG + ":   Initiator HostClassLoader: " + (top.galqq.utils.Initiator.getHostClassLoader() != null ? top.galqq.utils.Initiator.getHostClassLoader().getClass().getName() : "null"));
        // XposedBridge.log(TAG + ":   System ClassLoader: " + ClassLoader.getSystemClassLoader().getClass().getName());
        // XposedBridge.log(TAG + ":   Current Class ClassLoader: " + KernelMsgServiceCompat.class.getClassLoader().getClass().getName());
        
        throw new RuntimeException("MsgElement class not found");
    }

    /**
     * 创建一个空的IOperateCallback代理
     */
    public static Object createDummyOperateCallback(Context context) {
        try {
            Class<?> kIOperateCallback = XposedHelpers.findClass("com.tencent.qqnt.kernel.nativeinterface.IOperateCallback", context.getClassLoader());
            return Proxy.newProxyInstance(
                context.getClassLoader(),
                new Class[]{kIOperateCallback},
                (proxy, method, args) -> {
                    // XposedBridge.log("GalQQ.OperateCallback: " + method.getName() + " called");
                    
                    // 处理onResult回调
                    if ("onResult".equals(method.getName()) && args != null && args.length >= 2) {
                        int code = (Integer) args[0];
                        String result = args[1] != null ? args[1].toString() : "null";
                        // XposedBridge.log("GalQQ.OperateCallback: onResult - code=" + code + ", result=" + result);
                        
                        // 检查发送结果
                        if (code == 0) {
                            // XposedBridge.log("GalQQ.OperateCallback: 消息发送成功");
                        } else {
                            // XposedBridge.log("GalQQ.OperateCallback: 消息发送失败，错误码: " + code);
                        }
                    }
                    
                    return null;
                }
            );
        } catch (Throwable t1) {
            try {
                Class<?> kIOperateCallback = XposedHelpers.findClass("com.tencent.qqnt.kernelpublic.nativeinterface.IOperateCallback", context.getClassLoader());
                return Proxy.newProxyInstance(
                    context.getClassLoader(),
                    new Class[]{kIOperateCallback},
                    (proxy, method, args) -> {
                        // XposedBridge.log("GalQQ.OperateCallback: " + method.getName() + " called");
                        
                        // 处理onResult回调
                        if ("onResult".equals(method.getName()) && args != null && args.length >= 2) {
                            int code = (Integer) args[0];
                            String result = args[1] != null ? args[1].toString() : "null";
                            // XposedBridge.log("GalQQ.OperateCallback: onResult - code=" + code + ", result=" + result);
                            
                            // 检查发送结果
                            if (code == 0) {
                                // XposedBridge.log("GalQQ.OperateCallback: 消息发送成功");
                            } else {
                                // XposedBridge.log("GalQQ.OperateCallback: 消息发送失败，错误码: " + code);
                            }
                        }
                        
                        return null;
                    }
                );
            } catch (Throwable t2) {
                throw new RuntimeException("IOperateCallback interface not found");
            }
        }
    }

    /**
     * 创建一个IOperateCallback代理，带有UI反馈
     */
    public static Object createOperateCallbackWithUI(Context context, String messageText) {
        try {
            Class<?> kIOperateCallback = XposedHelpers.findClass("com.tencent.qqnt.kernel.nativeinterface.IOperateCallback", context.getClassLoader());
            return Proxy.newProxyInstance(
                context.getClassLoader(),
                new Class[]{kIOperateCallback},
                (proxy, method, args) -> {
                    // XposedBridge.log("GalQQ.OperateCallback: " + method.getName() + " called");
                    
                    // 处理onResult回调
                    if ("onResult".equals(method.getName()) && args != null && args.length >= 2) {
                        int code = (Integer) args[0];
                        String result = args[1] != null ? args[1].toString() : "null";
                        // XposedBridge.log("GalQQ.OperateCallback: onResult - code=" + code + ", result=" + result);
                        
                        // 检查发送结果并显示相应的Toast
                        if (code == 0) {
                            // XposedBridge.log("GalQQ.OperateCallback: 消息发送成功");
                            // 在主线程显示成功Toast
                            try {
                                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                                mainHandler.post(() -> {
                                    Toast.makeText(context, "已发送: " + messageText, Toast.LENGTH_SHORT).show();
                                });
                            } catch (Exception e) {
                                // XposedBridge.log("GalQQ.OperateCallback: 显示成功Toast失败: " + e.getMessage());
                            }
                        } else {
                            // XposedBridge.log("GalQQ.OperateCallback: 消息发送失败，错误码: " + code);
                            // 在主线程显示失败Toast
                            try {
                                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                                mainHandler.post(() -> {
                                    Toast.makeText(context, "发送失败，错误码: " + code, Toast.LENGTH_LONG).show();
                                });
                            } catch (Exception e) {
                                // XposedBridge.log("GalQQ.OperateCallback: 显示失败Toast失败: " + e.getMessage());
                            }
                        }
                    }
                    
                    return null;
                }
            );
        } catch (Throwable t1) {
            try {
                Class<?> kIOperateCallback = XposedHelpers.findClass("com.tencent.qqnt.kernelpublic.nativeinterface.IOperateCallback", context.getClassLoader());
                return Proxy.newProxyInstance(
                    context.getClassLoader(),
                    new Class[]{kIOperateCallback},
                    (proxy, method, args) -> {
                        // XposedBridge.log("GalQQ.OperateCallback: " + method.getName() + " called");
                        
                        // 处理onResult回调
                        if ("onResult".equals(method.getName()) && args != null && args.length >= 2) {
                            int code = (Integer) args[0];
                            String result = args[1] != null ? args[1].toString() : "null";
                            // XposedBridge.log("GalQQ.OperateCallback: onResult - code=" + code + ", result=" + result);
                            
                            // 检查发送结果并显示相应的Toast
                            if (code == 0) {
                                // XposedBridge.log("GalQQ.OperateCallback: 消息发送成功");
                                // 在主线程显示成功Toast
                                try {
                                    android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                                    mainHandler.post(() -> {
                                        Toast.makeText(context, "已发送: " + messageText, Toast.LENGTH_SHORT).show();
                                    });
                                } catch (Exception e) {
                                    // XposedBridge.log("GalQQ.OperateCallback: 显示成功Toast失败: " + e.getMessage());
                                }
                            } else {
                                // XposedBridge.log("GalQQ.OperateCallback: 消息发送失败，错误码: " + code);
                                // 在主线程显示失败Toast
                                try {
                                    android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                                    mainHandler.post(() -> {
                                        Toast.makeText(context, "发送失败，错误码: " + code, Toast.LENGTH_LONG).show();
                                    });
                                } catch (Exception e) {
                                    // XposedBridge.log("GalQQ.OperateCallback: 显示失败Toast失败: " + e.getMessage());
                                }
                            }
                        }
                        
                        return null;
                    }
                );
            } catch (Throwable t2) {
                throw new RuntimeException("IOperateCallback interface not found");
            }
        }
    }
}








