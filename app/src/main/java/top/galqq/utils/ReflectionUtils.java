package top.galqq.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;

import de.robv.android.xposed.XposedBridge;

public class ReflectionUtils {

    private static final String TAG = "GalQQ.ReflectionUtils";
    private static final HashMap<String, Class<?>> sClassCache = new HashMap<>(16);

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Find a class with synthetic class support for obfuscated code.
     * Tries the main class first, then tries synthetic classes like ClassName$1, ClassName$2, etc.
     * This is the same strategy used by QAuxiliary.
     *
     * @param className The class name (e.g., "com.tencent.mobileqq.activity.aio.item.TextItemBuilder")
     * @param classLoader The class loader to use
     * @param indices Synthetic class indices to try (e.g., 10, 7, 6, 3, 8)
     * @return The loaded class or null if not found
     */
    public static Class<?> findClassWithSynthetics(String className, ClassLoader classLoader, int... indices) {
        // Normalize className to use dots instead of slashes
        className = className.replace('/', '.');
        
        // Check cache first
        Class<?> cached = sClassCache.get(className);
        if (cached != null) {
            return cached;
        }

        // Try the main class first
        Class<?> clazz = findClass(className, classLoader);
        if (clazz != null) {
            sClassCache.put(className, clazz);
            XposedBridge.log(TAG + ": Found class directly: " + className);
            return clazz;
        }

        // Try synthetic classes - directly return the synthetic class if found
        // This matches QAuxiliary's implementation in Initiator.java
        if (indices != null && indices.length > 0) {
            for (int index : indices) {
                String syntheticClassName = className + "$" + index;
                Class<?> synthetic = findClass(syntheticClassName, classLoader);
                if (synthetic != null) {
                    // Cache and return the synthetic class directly
                    sClassCache.put(className, synthetic);
                    XposedBridge.log(TAG + ": Found " + className + " via synthetic class $" + index);
                    return synthetic;
                }
            }
        }

        // Log only once (not cached means first attempt)
        XposedBridge.log(TAG + ": Class not found: " + className);
        return null;
    }

    /**
     * Find a method by matching parameter types and return type.
     * Used when method name might be obfuscated.
     *
     * @param clazz The class to search in
     * @param returnType Expected return type
     * @param parameterTypes Expected parameter types
     * @return The matching method or null if not found
     */
    public static Method findMethodBySignature(Class<?> clazz, Class<?> returnType, Class<?>... parameterTypes) {
        if (clazz == null) return null;
        
        for (Method method : clazz.getDeclaredMethods()) {
            if (!returnType.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            
            Class<?>[] params = method.getParameterTypes();
            if (params.length != parameterTypes.length) {
                continue;
            }
            
            boolean matches = true;
            for (int i = 0; i < params.length; i++) {
                if (!parameterTypes[i].isAssignableFrom(params[i])) {
                    matches = false;
                    break;
                }
            }
            
            if (matches) {
                return method;
            }
        }
        
        return null;
    }

    public static Method findMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        try {
            Method method = clazz.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        try {
            Class<?>[] types = new Class[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i].getClass();
                // Primitive type handling is tricky here without helper, 
                // but for now we assume exact match or simple types.
                // In a real scenario, we'd need robust type matching.
            }
            // Simplified lookup
            for (Method m : obj.getClass().getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return m.invoke(obj, args);
                }
            }
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return m.invoke(null, args);
                }
            }
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            Field field = findField(obj.getClass(), fieldName);
            if (field != null) {
                return field.get(obj);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            Field field = findField(obj.getClass(), fieldName);
            if (field != null) {
                field.set(obj, value);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public static int getIntField(Object obj, String fieldName) {
        try {
            Field field = findField(obj.getClass(), fieldName);
            if (field != null) {
                return field.getInt(obj);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }
    
    public static void setIntField(Object obj, String fieldName, int value) {
        try {
            Field field = findField(obj.getClass(), fieldName);
            if (field != null) {
                field.setInt(obj, value);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
    
    public static <T> T newInstance(Class<T> clazz, Object... args) {
        try {
            // Simplified constructor lookup
            return clazz.newInstance(); // Deprecated but simple for 0 args
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
