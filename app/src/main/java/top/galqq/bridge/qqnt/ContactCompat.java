package top.galqq.bridge.qqnt;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import de.robv.android.xposed.XposedBridge;

/**
 * 完全模仿QAuxiliary的ContactCompat
 * @see io.github.qauxv.bridge.kernelcompat.ContactCompat
 */
public final class ContactCompat {
    private static final String TAG = "GalQQ-ContactCompat";
    
    int chatType;
    String guildId;
    String peerUid;
    long serialVersionUID;

    public ContactCompat() {
        this.serialVersionUID = 1L;
        this.peerUid = "";
        this.guildId = "";
    }

    public ContactCompat(int chatType, String peerUid, String guildId) {
        this.serialVersionUID = 1L;
        this.chatType = chatType;
        this.peerUid = peerUid;
        this.guildId = guildId;
    }

    public Object toKernelObject() {
        // 完全模仿QAuxiliary的实现，但使用反射避免编译时依赖
        // 使用目标应用的类加载器
        ClassLoader appClassLoader = Thread.currentThread().getContextClassLoader();
        if (appClassLoader == null) {
            XposedBridge.log(TAG + ": Context class loader is null");
            appClassLoader = this.getClass().getClassLoader();
        }
        
        try {
            Class<?> contactClass = Class.forName("com.tencent.qqnt.kernel.nativeinterface.Contact", false, appClassLoader);
            Constructor<?> constructor = contactClass.getConstructor(int.class, String.class, String.class);
            Object instance = constructor.newInstance(chatType, peerUid, guildId);
            XposedBridge.log(TAG + ": Successfully created kernel.nativeinterface.Contact instance");
            return instance;
        } catch (ClassNotFoundException e) {
            XposedBridge.log(TAG + ": kernel.nativeinterface.Contact not found: " + e.getMessage());
        } catch (NoSuchMethodException e) {
            XposedBridge.log(TAG + ": Constructor not found for kernel.nativeinterface.Contact: " + e.getMessage());
        } catch (InstantiationException e) {
            XposedBridge.log(TAG + ": Instantiation failed for kernel.nativeinterface.Contact: " + e.getMessage());
        } catch (IllegalAccessException e) {
            XposedBridge.log(TAG + ": Illegal access for kernel.nativeinterface.Contact: " + e.getMessage());
        } catch (InvocationTargetException e) {
            XposedBridge.log(TAG + ": Invocation target exception for kernel.nativeinterface.Contact: " + e.getMessage());
        }
        
        try {
            Class<?> contactClass = Class.forName("com.tencent.qqnt.kernelpublic.nativeinterface.Contact", false, appClassLoader);
            Constructor<?> constructor = contactClass.getConstructor(int.class, String.class, String.class);
            Object instance = constructor.newInstance(chatType, peerUid, guildId);
            XposedBridge.log(TAG + ": Successfully created kernelpublic.nativeinterface.Contact instance");
            return instance;
        } catch (ClassNotFoundException e) {
            XposedBridge.log(TAG + ": kernelpublic.nativeinterface.Contact not found: " + e.getMessage());
        } catch (NoSuchMethodException e) {
            XposedBridge.log(TAG + ": Constructor not found for kernelpublic.nativeinterface.Contact: " + e.getMessage());
        } catch (InstantiationException e) {
            XposedBridge.log(TAG + ": Instantiation failed for kernelpublic.nativeinterface.Contact: " + e.getMessage());
        } catch (IllegalAccessException e) {
            XposedBridge.log(TAG + ": Illegal access for kernelpublic.nativeinterface.Contact: " + e.getMessage());
        } catch (InvocationTargetException e) {
            XposedBridge.log(TAG + ": Invocation target exception for kernelpublic.nativeinterface.Contact: " + e.getMessage());
        }
        
        XposedBridge.log(TAG + ": Contact not supported, chatType=" + chatType + ", peerUid=" + peerUid + ", guildId=" + guildId);
        XposedBridge.log(TAG + ": ClassLoader: " + appClassLoader);
        throw new RuntimeException("ContactCompat.toKernelObject: Contact not supported");
    }

    public static ContactCompat fromKernelObject(Object kernelObject) {
        try {
            // 尝试两种可能的包名
            Class<?> contactClass;
            String className;
            
            try {
                contactClass = Class.forName("com.tencent.qqnt.kernel.nativeinterface.Contact");
                className = "com.tencent.qqnt.kernel.nativeinterface.Contact";
            } catch (ClassNotFoundException e) {
                contactClass = Class.forName("com.tencent.qqnt.kernelpublic.nativeinterface.Contact");
                className = "com.tencent.qqnt.kernelpublic.nativeinterface.Contact";
            }
            
            if (!contactClass.isInstance(kernelObject)) {
                throw new IllegalArgumentException("kernelObject is not an instance of Contact");
            }
            
            // 使用反射获取字段值
            Object peerUid = contactClass.getMethod("getPeerUid").invoke(kernelObject);
            int chatType = (Integer) contactClass.getMethod("getChatType").invoke(kernelObject);
            Object guildId = contactClass.getMethod("getGuildId").invoke(kernelObject);
            
            return new ContactCompat(chatType, String.valueOf(peerUid), String.valueOf(guildId));
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert kernel Contact to ContactCompat", e);
        }
    }

    public int getChatType() {
        return this.chatType;
    }

    public String getGuildId() {
        return this.guildId;
    }

    public String getPeerUid() {
        return this.peerUid;
    }

    public void setChatType(int chatType) {
        this.chatType = chatType;
    }

    public void setGuildId(String guildId) {
        this.guildId = guildId;
    }

    public void setPeerUid(String peerUid) {
        this.peerUid = peerUid;
    }

    public String toString() {
        return "Contact{chatType=" + this.chatType + ",peerUid=" + this.peerUid + ",guildId=" + this.guildId + ",}";
    }
}