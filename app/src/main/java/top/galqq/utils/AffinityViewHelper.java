package top.galqq.utils;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/**
 * 好感度 UI 辅助类
 * 负责创建和配置好感度显示视图
 */
public class AffinityViewHelper {

    private static final String TAG = "GalQQ.AffinityViewHelper";
    
    // 好感度颜色常量
    public static final int COLOR_HIGH = 0xFFFF69B4;    // Hot Pink (>70) - 高好感度
    public static final int COLOR_MEDIUM = 0xFFFFA500;  // Orange (40-70) - 中等好感度
    public static final int COLOR_LOW = 0xFF87CEEB;     // Sky Blue (<40) - 低好感度
    public static final int COLOR_UNKNOWN = 0xFFAAAAAA; // Gray - 未知好感度
    
    // 好感度阈值
    public static final int THRESHOLD_HIGH = 70;
    public static final int THRESHOLD_MEDIUM = 40;
    
    // View ID（用于查找和移除）
    public static final int AFFINITY_VIEW_ID = 0x7F0A5678;

    /**
     * 创建好感度显示视图
     * @param context 上下文
     * @param affinity 好感度值 (0-100)，-1 表示未知
     * @return 配置好的 TextView
     */
    public static TextView createAffinityView(Context context, int affinity) {
        TextView tv = new TextView(context);
        tv.setId(AFFINITY_VIEW_ID);
        
        // 设置文本
        String text = formatAffinityText(affinity);
        tv.setText(text);
        
        // 设置文本样式
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        
        // 设置内边距
        int paddingH = dp2px(context, 4);
        int paddingV = dp2px(context, 2);
        tv.setPadding(paddingH, paddingV, paddingH, paddingV);
        
        // 设置背景（圆角矩形）
        int color = getAffinityColor(affinity);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp2px(context, 8));
        tv.setBackground(background);
        
        // 设置最小宽度
        tv.setMinWidth(dp2px(context, 20));
        
        return tv;
    }

    /**
     * 根据好感度值获取对应的颜色
     * @param affinity 好感度值 (0-100)，-1 表示未知
     * @return 颜色值
     */
    public static int getAffinityColor(int affinity) {
        if (affinity < 0) {
            return COLOR_UNKNOWN;
        } else if (affinity > THRESHOLD_HIGH) {
            return COLOR_HIGH;
        } else if (affinity >= THRESHOLD_MEDIUM) {
            return COLOR_MEDIUM;
        } else {
            return COLOR_LOW;
        }
    }

    /**
     * 格式化好感度显示文本
     * @param affinity 好感度值 (0-100)
     * @return 显示文本
     */
    public static String formatAffinityText(int affinity) {
        // 无效值返回空字符串（不应该被调用，因为无效值不会创建视图）
        if (affinity < 0) {
            return "";
        }
        return String.valueOf(affinity);
    }

    /**
     * 更新好感度视图
     * @param tv 要更新的 TextView
     * @param affinity 新的好感度值
     */
    public static void updateAffinityView(TextView tv, int affinity) {
        if (tv == null) {
            return;
        }
        
        // 更新文本
        tv.setText(formatAffinityText(affinity));
        
        // 更新背景颜色
        int color = getAffinityColor(affinity);
        if (tv.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) tv.getBackground()).setColor(color);
        }
    }

    /**
     * dp 转 px
     */
    private static int dp2px(Context context, float dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
