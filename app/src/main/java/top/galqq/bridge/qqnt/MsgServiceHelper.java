package top.galqq.bridge.qqnt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.content.Context;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;

/**
 * 完全模仿QAuxiliary的MsgServiceHelper
 * 
 * 重要：stub类（AppRuntime、IRuntimeService）只用于编译时类型检查
 * 运行时不能进行类型转换，因为stub类不会打包到APK中
 * 
 * @see io.github.qauxv.bridge.ntapi.MsgServiceHelper
 */
public class MsgServiceHelper {

    private static final String TAG = "GalQQ.MsgServiceHelper";

    private MsgServiceHelper() {
    }

    /**
     * 获取MsgService
     * @param app AppRuntime实例（运行时实际类型，但参数声明为Object避免ClassLoader问题）
     */
    @NonNull
    public static Object getMsgService(@NonNull Object app, Context context) throws ReflectiveOperationException {
        // 验证app参数不为null
        if (app == null) {
            throw new IllegalArgumentException("AppRuntime instance is null");
        }
        
        // 记录app的实际类型
        XposedBridge.log("GalQQ.MsgServiceHelper: AppRuntime instance type: " + app.getClass().getName());
        
        // 使用反射调用app.getRuntimeService(IKernelService.class, "")
        Class<?> kIKernelService = XposedHelpers.findClass("com.tencent.qqnt.kernel.api.IKernelService", context.getClassLoader());
        Object kernelService = XposedHelpers.callMethod(app, "getRuntimeService", kIKernelService, "");
        Method getMsgService = kernelService.getClass().getMethod("getMsgService");
        return getMsgService.invoke(kernelService);
    }

    @Nullable
    public static Object getKernelMsgServiceRaw(@NonNull Object app, Context context) throws ReflectiveOperationException {
        Object msgService = getMsgService(app, context);
        Object service;
        try {
            // 8.9.78起
            service = msgService.getClass().getMethod("getService").invoke(msgService);
        } catch (Exception unused) {
            // 旧版本：查找返回IKernelMsgService的方法
            Method getKMsgSvc = null;
            Class<?> kIKernelMsgService = XposedHelpers.findClass("com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService", context.getClassLoader());
            for (Method m : msgService.getClass().getDeclaredMethods()) {
                if (m.getReturnType().equals(kIKernelMsgService) && m.getParameterTypes().length == 0) {
                    getKMsgSvc = m;
                    break;
                }
            }
            if (getKMsgSvc == null) {
                throw new NoSuchMethodException("Cannot find method returning IKernelMsgService");
            }
            service = getKMsgSvc.invoke(msgService);
        }
        return service;
    }

    @Nullable
    public static KernelMsgServiceCompat getKernelMsgService(@NonNull Object app, Context context) throws ReflectiveOperationException {
        Object service = getKernelMsgServiceRaw(app, context);
        if (service != null) {
            return new KernelMsgServiceCompat(service, context);
        } else {
            return null;
        }
    }
}
