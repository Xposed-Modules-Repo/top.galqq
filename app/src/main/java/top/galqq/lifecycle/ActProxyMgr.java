package top.galqq.lifecycle;

import androidx.annotation.NonNull;

public class ActProxyMgr {

    public static final String STUB_DEFAULT_ACTIVITY = "com.tencent.mobileqq.activity.photo.CameraPreviewActivity";
    
    private ActProxyMgr() {
        throw new AssertionError("No instance for you!");
    }

    public static boolean isModuleProxyActivity(@NonNull String className) {
        return className.startsWith("top.galqq.ui.");
    }
}
