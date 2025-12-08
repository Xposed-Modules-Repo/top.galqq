/*
 * GalQQ - An Xposed module for QQ
 * Copyright (C) 2024 GalQQ contributors
 * 
 * This software is opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */

package top.galqq.utils;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import de.robv.android.xposed.XposedBridge;

/**
 * ViewUtils - 视图查找工具类
 * 完全模仿QAuxiliary的ViewUtils实现
 * 提供多种视图查找策略，解决getHostView失败时的后备方案
 */
public class ViewUtils {
    
    private static final String TAG = "GalQQ.ViewUtils";
    
    // 反射缓存 - 避免重复查找
    private static final ConcurrentHashMap<String, Method> methodCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Field> fieldCache = new ConcurrentHashMap<>();
    
    /**
     * 通过资源ID查找视图
     * 模仿QAuxiliary的findHostView函数
     */
    @Nullable
    public static View findHostView(@NonNull ViewGroup rootView, @NonNull String idName) {
        try {
            Context context = rootView.getContext();
            Resources resources = context.getResources();
            String packageName = context.getPackageName();
            
            // 获取资源ID
            int id = resources.getIdentifier(idName, "id", packageName);
            if (id == 0) {
                XposedBridge.log(TAG + ": Resource ID not found: " + idName);
                return null;
            }
            
            // 使用findViewById查找
            return rootView.findViewById(id);
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error finding host view by ID: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 通过类名查找视图
     * 模仿QAuxiliary的RepeaterHook实现
     */
    @Nullable
    public static View findViewByClassName(@NonNull ViewGroup rootView, @NonNull String className) {
        try {
            Class<?> targetClass = Class.forName(className);
            return findViewByType(rootView, (Class<? extends View>) targetClass);
        } catch (ClassNotFoundException e) {
            XposedBridge.log(TAG + ": Class not found: " + className);
            return null;
        }
    }
    
    /**
     * 通过类型查找视图
     * 递归遍历查找指定类型的视图
     */
    @Nullable
    public static <T extends View> T findViewByType(@NonNull ViewGroup rootView, @NonNull Class<T> type) {
        try {
            // 检查根视图本身
            if (type.isInstance(rootView)) {
                return type.cast(rootView);
            }
            
            // 遍历子视图
            int childCount = rootView.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = rootView.getChildAt(i);
                if (type.isInstance(child)) {
                    return type.cast(child);
                }
                
                // 递归查找
                if (child instanceof ViewGroup) {
                    T result = findViewByType((ViewGroup) child, type);
                    if (result != null) {
                        return result;
                    }
                }
            }
            
            return null;
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error finding view by type: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 通过文本内容查找视图
     * 模仿QAuxiliary的findViewByText实现
     */
    @Nullable
    public static View findViewByText(@NonNull ViewGroup rootView, @NonNull String text) {
        try {
            // 遍历子视图
            int childCount = rootView.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = rootView.getChildAt(i);
                
                if (child instanceof TextView) {
                    TextView textView = (TextView) child;
                    if (text.equals(textView.getText().toString())) {
                        return child;
                    }
                }
                
                // 递归查找
                if (child instanceof ViewGroup) {
                    View result = findViewByText((ViewGroup) child, text);
                    if (result != null) {
                        return result;
                    }
                }
            }
            
            return null;
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error finding view by text: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 通过条件查找视图
     * 模仿QAuxiliary的findViewByCondition实现
     */
    @Nullable
    public static View findViewByCondition(@NonNull ViewGroup rootView, @NonNull ViewCondition condition) {
        try {
            // 检查根视图本身
            if (condition.matches(rootView)) {
                return rootView;
            }
            
            // 遍历子视图
            int childCount = rootView.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = rootView.getChildAt(i);
                
                if (condition.matches(child)) {
                    return child;
                }
                
                // 递归查找
                if (child instanceof ViewGroup) {
                    View result = findViewByCondition((ViewGroup) child, condition);
                    if (result != null) {
                        return result;
                    }
                }
            }
            
            return null;
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error finding view by condition: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 查找所有符合条件的视图
     */
    @NonNull
    public static List<View> findAllViewsByCondition(@NonNull ViewGroup rootView, @NonNull ViewCondition condition) {
        List<View> results = new ArrayList<>();
        try {
            findAllViewsByConditionInternal(rootView, condition, results);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error finding all views by condition: " + e.getMessage());
        }
        return results;
    }
    
    private static void findAllViewsByConditionInternal(@NonNull ViewGroup rootView, 
                                                      @NonNull ViewCondition condition, 
                                                      @NonNull List<View> results) {
        // 检查根视图本身
        if (condition.matches(rootView)) {
            results.add(rootView);
        }
        
        // 遍历子视图
        int childCount = rootView.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = rootView.getChildAt(i);
            
            if (condition.matches(child)) {
                results.add(child);
            }
            
            // 递归查找
            if (child instanceof ViewGroup) {
                findAllViewsByConditionInternal((ViewGroup) child, condition, results);
            }
        }
    }
    
    /**
     * 通过反射遍历字段查找视图
     * 模仿QAuxiliary的MultiActionHook实现
     */
     @Nullable
     public static View findViewByFieldTraversal(@NonNull Object object, @NonNull Class<?> targetType) {
         try {
             Class<?> clazz = object.getClass();
             Field[] fields = clazz.getDeclaredFields();
             
             for (Field field : fields) {
                 field.setAccessible(true);
                 
                 Object fieldValue = field.get(object);
                 if (fieldValue == null) continue;
                 
                 // 检查字段类型
                 if (targetType.isInstance(fieldValue)) {
                     return (View) fieldValue;
                 }
                 
                 // 如果是ViewGroup，递归查找
                 if (fieldValue instanceof ViewGroup) {
                     View result = findViewByType((ViewGroup) fieldValue, (Class<? extends View>) targetType);
                     if (result != null) {
                         return result;
                     }
                 }
             }
             
             return null;
             
         } catch (Exception e) {
             XposedBridge.log(TAG + ": Error finding view by field traversal: " + e.getMessage());
             return null;
         }
     }
    
    /**
     * 获取屏幕尺寸
     */
    @NonNull
    public static Point getScreenSize(@NonNull Context context) {
        Point point = new Point();
        if (context instanceof Activity) {
            ((Activity) context).getWindowManager().getDefaultDisplay().getSize(point);
        } else {
            point.x = context.getResources().getDisplayMetrics().widthPixels;
            point.y = context.getResources().getDisplayMetrics().heightPixels;
        }
        return point;
    }
    
    /**
     * dp转px
     */
    public static int dp2px(@NonNull Context context, float dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }
    
    /**
     * px转dp
     */
    public static int px2dp(@NonNull Context context, float px) {
        return (int) (px / context.getResources().getDisplayMetrics().density + 0.5f);
    }
    
    /**
     * 视图条件接口
     * 用于findViewByCondition的自定义条件判断
     */
    public interface ViewCondition {
        boolean matches(View view);
    }
    
    /**
     * 文本包含条件
     */
    public static class TextContainsCondition implements ViewCondition {
        private final String text;
        
        public TextContainsCondition(@NonNull String text) {
            this.text = text;
        }
        
        @Override
        public boolean matches(View view) {
            if (view instanceof TextView) {
                CharSequence content = ((TextView) view).getText();
                return content != null && content.toString().contains(text);
            }
            return false;
        }
    }
    
    /**
     * ID匹配条件
     */
    public static class IdCondition implements ViewCondition {
        private final String idName;
        
        public IdCondition(@NonNull String idName) {
            this.idName = idName;
        }
        
        @Override
        public boolean matches(View view) {
            try {
                Context context = view.getContext();
                Resources resources = context.getResources();
                String packageName = context.getPackageName();
                
                int id = resources.getIdentifier(idName, "id", packageName);
                return id != 0 && view.getId() == id;
            } catch (Exception e) {
                return false;
            }
        }
    }
    
    /**
     * 类型匹配条件
     */
    public static class TypeCondition implements ViewCondition {
        private final Class<?> type;
        
        public TypeCondition(@NonNull Class<?> type) {
            this.type = type;
        }
        
        @Override
        public boolean matches(View view) {
            return type.isInstance(view);
        }
    }
}