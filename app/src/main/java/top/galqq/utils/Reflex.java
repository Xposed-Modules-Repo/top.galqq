package top.galqq.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * 反射工具类，提供getFirstNSFByType方法
 * 
 * @see cc.ioctl.util.Reflex
 */
public class Reflex {
    
    private static final String TAG = "GalQQ.Reflex";
    
    /**
     * NSF: Neither Static nor Final
     * 从对象中获取第一个非静态非final的指定类型字段
     * 
     * @param obj 目标对象
     * @param type 字段类型
     * @return 第一个匹配的字段值
     */
    public static <T> T getFirstNSFByType(Object obj, Class<T> type) {
        if (obj == null) {
            throw new NullPointerException("obj == null");
        }
        if (type == null) {
            throw new NullPointerException("type == null");
        }
        
        Class<?> clz = obj.getClass();
        while (clz != null && !clz.equals(Object.class)) {
            for (Field f : clz.getDeclaredFields()) {
                if (!f.getType().equals(type)) {
                    continue;
                }
                int m = f.getModifiers();
                if (Modifier.isStatic(m) || Modifier.isFinal(m)) {
                    continue;
                }
                f.setAccessible(true);
                try {
                    return (T) f.get(obj);
                } catch (IllegalAccessException ignored) {
                    //should not happen
                }
            }
            clz = clz.getSuperclass();
        }
        return null;
    }
    
    /**
     * NSF: Neither Static nor Final
     * 从类中获取第一个非静态非final的指定类型字段
     * 
     * @param clz 目标类
     * @param type 字段类型
     * @return 第一个匹配的字段
     */
    public static Field getFirstNSFFieldByType(Class<?> clz, Class<?> type) {
        if (clz == null) {
            throw new NullPointerException("clz == null");
        }
        if (type == null) {
            throw new NullPointerException("type == null");
        }
        
        while (clz != null && !clz.equals(Object.class)) {
            for (Field f : clz.getDeclaredFields()) {
                if (!f.getType().equals(type)) {
                    continue;
                }
                int m = f.getModifiers();
                if (Modifier.isStatic(m) || Modifier.isFinal(m)) {
                    continue;
                }
                f.setAccessible(true);
                return f;
            }
            clz = clz.getSuperclass();
        }
        return null;
    }
}