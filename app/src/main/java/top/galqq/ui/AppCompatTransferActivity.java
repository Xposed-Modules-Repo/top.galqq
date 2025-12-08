package top.galqq.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import top.galqq.lifecycle.Parasitics;
import top.galqq.utils.SavedInstanceStatePatchedClassReferencer;
import top.galqq.R;

public abstract class AppCompatTransferActivity extends AppCompatActivity {

    private ClassLoader mXref = null;

    @Override
    public ClassLoader getClassLoader() {
        if (mXref == null) {
            mXref = new SavedInstanceStatePatchedClassReferencer(
                AppCompatTransferActivity.class.getClassLoader());
        }
        return mXref;
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
    }
    
    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        Bundle windowState = savedInstanceState.getBundle("android:viewHierarchyState");
        if (windowState != null) {
            windowState.setClassLoader(AppCompatTransferActivity.class.getClassLoader());
        }
        super.onRestoreInstanceState(savedInstanceState);
    }

    @Override
    public Resources getResources() {
        Resources res = super.getResources();
        try {
            res.getString(R.string.res_inject_success);
        } catch (Resources.NotFoundException e) {
            Parasitics.injectModuleResources(res);
        }
        return res;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        try {
            getString(R.string.res_inject_success);
        } catch (Resources.NotFoundException e) {
            Parasitics.injectModuleResources(getResources());
        }
        super.onConfigurationChanged(newConfig);
    }
}
