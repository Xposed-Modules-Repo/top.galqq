package top.galqq.utils;

import android.content.Context;
import java.util.Objects;

/**
 * ClassLoader桥接器，用于解决模块和宿主应用之间的ClassLoader冲突
 * 优先从宿主ClassLoader加载AndroidX类，避免类型转换异常
 */
public class SavedInstanceStatePatchedClassReferencer extends ClassLoader {

    private static final ClassLoader mBootstrap = Context.class.getClassLoader();
    private final ClassLoader mBaseReferencer;
    private final ClassLoader mHostReferencer;

    public SavedInstanceStatePatchedClassReferencer(ClassLoader referencer) {
        super(mBootstrap);
        mBaseReferencer = Objects.requireNonNull(referencer);
        mHostReferencer = Context.class.getClassLoader();
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // 1. 首先尝试从bootstrap加载（Android系统类）
        try {
            return mBootstrap.loadClass(name);
        } catch (ClassNotFoundException ignored) {
        }
        
        // 2. QQ的类（com.tencent.mobileqq）必须从宿主ClassLoader加载
        //    这是关键！防止Parcelable反序列化时的ClassNotFoundException
        if (mHostReferencer != null && name.startsWith("com.tencent.")) {
            try {
                return mHostReferencer.loadClass(name);
            } catch (ClassNotFoundException ignored) {
            }
        }
        
        // 3. AndroidX类优先从宿主加载，避免ClassCastException
        if (mHostReferencer != null && name.startsWith("androidx.")) {
            try {
                return mHostReferencer.loadClass(name);
            } catch (ClassNotFoundException ignored) {
            }
        }
        
        // 4. 特殊处理：androidx.lifecycle.ReportFragment
        if (mHostReferencer != null) {
            try {
                if ("androidx.lifecycle.ReportFragment".equals(name)) {
                    return mHostReferencer.loadClass(name);
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        
        // 5. 最后从模块ClassLoader加载（模块自己的类）
        return mBaseReferencer.loadClass(name);
    }
}
