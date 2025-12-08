package top.galqq.ui;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import androidx.appcompat.widget.Toolbar;
import top.galqq.R;
import top.galqq.utils.HostInfo;

public class SettingsUiFragmentHostActivity extends AppCompatTransferActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 应用主题管理器，检测QQ的夜间模式设置
        top.galqq.utils.ThemeManager.applyTheme(this);
        top.galqq.utils.ThemeManager.updateConfiguration(this);
        
        // 设置主题
        setTheme(top.galqq.utils.ThemeManager.getThemeResId(this));
        super.onCreate(savedInstanceState);
        
        // 确保窗口背景色正确设置
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        int bgColor = resolveBackgroundColor();
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bgColor));
        setContentView(R.layout.activity_settings_host);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.app_name);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        requestTranslucentStatusBar();

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, new GalSettingsFragment())
                .commit();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 重新应用状态栏设置，防止被系统或其他因素重置
        requestTranslucentStatusBar();
    }

    /**
     * 解析当前主题的背景色
     */
    private int resolveBackgroundColor() {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        // 尝试获取 android:windowBackground
        if (getTheme().resolveAttribute(android.R.attr.windowBackground, typedValue, true)) {
            if (typedValue.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT 
                && typedValue.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                return typedValue.data;
            }
        }
        // 尝试获取 android:colorBackground
        if (getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true)) {
            if (typedValue.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT 
                && typedValue.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                return typedValue.data;
            }
        }
        // 降级：根据QQ夜间模式返回默认颜色
        boolean isNightMode = top.galqq.utils.ThemeManager.isQQNightMode(this);
        return isNightMode ? 0xFF202124 : 0xFFFFFFFF;
    }

    protected void requestTranslucentStatusBar() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        
        View decorView = window.getDecorView();
        int option = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        
        // 检测QQ是否为深色模式
        boolean isNightMode = top.galqq.utils.ThemeManager.isQQNightMode(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isNightMode) {
                // 浅色模式：背景为白色，需要深色文字
                option |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                // 深色模式：背景为深色，需要浅色文字（默认），清除LIGHT_STATUS_BAR标志
                option &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
        }
        
        decorView.setSystemUiVisibility(option);
    }
}
