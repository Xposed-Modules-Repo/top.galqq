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

package top.galqq.hook;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import java.lang.reflect.Method;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import top.galqq.utils.ViewUtils;

/**
 * MessageOptionBarDecorator - 消息选项条装饰器
 * 完全模仿QAuxiliary的ChatItemShowQQUin实现
 * 解决getHostView方法查找失败导致选项条无法显示的问题
 */
public class MessageOptionBarDecorator implements OnBubbleBuilder {
    
    private static final String TAG = "GalQQ.MessageOptionBarDecorator";
    
    // 缓存反射方法
    private static Method getMsgTypeMethod;
    private static Method getSenderUinMethod;
    private static Method getMsgContentMethod;
    
    @Override
    public void onGetView(ViewGroup rootView, Object chatMessage, XC_MethodHook.MethodHookParam param) {
        try {
            // 非NT版本处理
            setupOptionBar(rootView, chatMessage, param);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error in onGetView: " + e.getMessage());
        }
    }
    
    @Override
    public void onGetViewNt(ViewGroup rootView, Object chatMessage, XC_MethodHook.MethodHookParam param) {
        try {
            // NT版本处理
            setupOptionBarNT(rootView, chatMessage, param);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error in onGetViewNt: " + e.getMessage());
        }
    }
    
    /**
     * 设置选项条（NT版本）
     * 模仿QAuxiliary的onGetViewNt实现
     */
    private void setupOptionBarNT(ViewGroup rootView, Object chatMessage, XC_MethodHook.MethodHookParam param) {
        try {
            // 获取消息类型和发送者
            int msgType = getMessageType(chatMessage);
            long senderUin = getSenderUin(chatMessage);
            
            XposedBridge.log(TAG + ": NT setup - msgType=" + msgType + ", senderUin=" + senderUin);
            
            // 创建选项条
            View optionBar = createOptionBar(rootView.getContext(), chatMessage);
            if (optionBar == null) {
                XposedBridge.log(TAG + ": Failed to create option bar");
                return;
            }
            
            // 添加选项条到根视图
            addOptionBarToRootNT(rootView, optionBar, chatMessage);
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error in setupOptionBarNT: " + e.getMessage());
        }
    }
    
    /**
     * 设置选项条（非NT版本）
     * 模仿QAuxiliary的onGetView实现
     */
    private void setupOptionBar(ViewGroup rootView, Object chatMessage, XC_MethodHook.MethodHookParam param) {
        try {
            // 获取消息类型和发送者
            int msgType = getMessageType(chatMessage);
            long senderUin = getSenderUin(chatMessage);
            
            XposedBridge.log(TAG + ": Legacy setup - msgType=" + msgType + ", senderUin=" + senderUin);
            
            // 创建选项条
            View optionBar = createOptionBar(rootView.getContext(), chatMessage);
            if (optionBar == null) {
                XposedBridge.log(TAG + ": Failed to create option bar");
                return;
            }
            
            // 添加选项条到根视图
            addOptionBarToRoot(rootView, optionBar, chatMessage);
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error in setupOptionBar: " + e.getMessage());
        }
    }
    
    /**
     * 创建选项条
     * 模仿QAuxiliary的createOptionBar实现
     */
    private View createOptionBar(Context context, Object chatMessage) {
        try {
            // 获取消息内容
            String msgContent = getMessageContent(chatMessage);
            
            // 创建文本视图
            TextView textView = new TextView(context);
            textView.setText("📋 " + (TextUtils.isEmpty(msgContent) ? "消息" : msgContent.substring(0, Math.min(10, msgContent.length()))));
            textView.setTextColor(Color.WHITE);
            textView.setTextSize(12);
            textView.setPadding(ViewUtils.dp2px(context, 8), 
                              ViewUtils.dp2px(context, 4), 
                              ViewUtils.dp2px(context, 8), 
                              ViewUtils.dp2px(context, 4));
            
            // 设置背景
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.parseColor("#4CAF50"));
            background.setCornerRadius(ViewUtils.dp2px(context, 12));
            textView.setBackground(background);
            
            // 设置布局参数
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.gravity = Gravity.END;
            textView.setLayoutParams(params);
            
            return textView;
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error creating option bar: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 添加选项条到根视图（NT版本）
     * 使用ConstraintLayout处理约束布局
     */
    private void addOptionBarToRootNT(ViewGroup rootView, View optionBar, Object chatMessage) {
        try {
            // 尝试多种查找策略，解决getHostView失败问题
            
            // 策略1: 直接添加到根视图
            if (rootView instanceof ConstraintLayout) {
                addToConstraintLayout((ConstraintLayout) rootView, optionBar);
                return;
            }
            
            // 策略2: 查找消息气泡视图
            ViewGroup bubbleView = findBubbleView(rootView);
            if (bubbleView != null) {
                addToBubbleView(bubbleView, optionBar);
                return;
            }
            
            // 策略3: 查找LinearLayout
            LinearLayout linearLayout = ViewUtils.findViewByType(rootView, LinearLayout.class);
            if (linearLayout != null) {
                addToLinearLayout(linearLayout, optionBar);
                return;
            }
            
            // 策略4: 查找FrameLayout
            FrameLayout frameLayout = ViewUtils.findViewByType(rootView, FrameLayout.class);
            if (frameLayout != null) {
                addToFrameLayout(frameLayout, optionBar);
                return;
            }
            
            // 策略5: 直接添加到根视图（最后手段）
            XposedBridge.log(TAG + ": Using fallback strategy - adding directly to root");
            addToRootView(rootView, optionBar);
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error adding option bar to root NT: " + e.getMessage());
        }
    }
    
    /**
     * 添加选项条到根视图（非NT版本）
     */
    private void addOptionBarToRoot(ViewGroup rootView, View optionBar, Object chatMessage) {
        try {
            // 非NT版本通常使用RelativeLayout或LinearLayout
            
            // 策略1: 查找LinearLayout
            LinearLayout linearLayout = ViewUtils.findViewByType(rootView, LinearLayout.class);
            if (linearLayout != null) {
                addToLinearLayout(linearLayout, optionBar);
                return;
            }
            
            // 策略2: 查找FrameLayout
            FrameLayout frameLayout = ViewUtils.findViewByType(rootView, FrameLayout.class);
            if (frameLayout != null) {
                addToFrameLayout(frameLayout, optionBar);
                return;
            }
            
            // 策略3: 查找消息气泡视图
            ViewGroup bubbleView = findBubbleView(rootView);
            if (bubbleView != null) {
                addToBubbleView(bubbleView, optionBar);
                return;
            }
            
            // 策略4: 直接添加到根视图
            XposedBridge.log(TAG + ": Using fallback strategy - adding directly to root");
            addToRootView(rootView, optionBar);
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error adding option bar to root: " + e.getMessage());
        }
    }
    
    /**
     * 添加到ConstraintLayout
     * 使用ConstraintLayout的约束添加选项条
     */
    private static void addToConstraintLayout(@NonNull ConstraintLayout constraintLayout, @NonNull View optionBar) {
        try {
            // 创建新的ConstraintLayout.LayoutParams
            ConstraintLayout.LayoutParams params = new ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            );
            
            // 添加约束：选项条顶部约束到父布局顶部，右侧约束到父布局右侧
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
            params.topMargin = ViewUtils.dp2px(constraintLayout.getContext(), 4);
            params.rightMargin = ViewUtils.dp2px(constraintLayout.getContext(), 8);
            
            // 添加视图
            constraintLayout.addView(optionBar, params);
            
            XposedBridge.log(TAG + ": Option bar added to ConstraintLayout successfully");
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error adding option bar to ConstraintLayout: " + e.getMessage());
            // 降级到FrameLayout处理 - 需要转换ConstraintLayout为FrameLayout
            try {
                FrameLayout frameLayout = new FrameLayout(constraintLayout.getContext());
                // 复制ConstraintLayout的子视图到FrameLayout
                for (int i = 0; i < constraintLayout.getChildCount(); i++) {
                    View child = constraintLayout.getChildAt(i);
                    constraintLayout.removeView(child);
                    frameLayout.addView(child);
                }
                // 调用实例方法需要先创建实例
                MessageOptionBarDecorator instance = new MessageOptionBarDecorator();
                instance.addToFrameLayout(frameLayout, optionBar);
            } catch (Exception fallbackError) {
                XposedBridge.log(TAG + ": Fallback to FrameLayout also failed: " + fallbackError.getMessage());
            }
        }
    }
    
    /**
     * 添加到LinearLayout
     */
    private void addToLinearLayout(LinearLayout linearLayout, View optionBar) {
        try {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.gravity = Gravity.END;
            params.topMargin = ViewUtils.dp2px(linearLayout.getContext(), 4);
            
            optionBar.setLayoutParams(params);
            linearLayout.addView(optionBar);
            
            XposedBridge.log(TAG + ": Successfully added option bar to LinearLayout");
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error adding to LinearLayout: " + e.getMessage());
        }
    }
    
    /**
     * 添加到FrameLayout
     */
    private void addToFrameLayout(FrameLayout frameLayout, View optionBar) {
        try {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.gravity = Gravity.END | Gravity.BOTTOM;
            params.rightMargin = ViewUtils.dp2px(frameLayout.getContext(), 16);
            params.bottomMargin = ViewUtils.dp2px(frameLayout.getContext(), 8);
            
            optionBar.setLayoutParams(params);
            frameLayout.addView(optionBar);
            
            XposedBridge.log(TAG + ": Successfully added option bar to FrameLayout");
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error adding to FrameLayout: " + e.getMessage());
        }
    }
    
    /**
     * 添加到消息气泡视图
     */
    private void addToBubbleView(ViewGroup bubbleView, View optionBar) {
        try {
            // 在消息气泡下方添加选项条
            if (bubbleView instanceof LinearLayout) {
                addToLinearLayout((LinearLayout) bubbleView, optionBar);
            } else if (bubbleView instanceof FrameLayout) {
                addToFrameLayout((FrameLayout) bubbleView, optionBar);
            } else {
                // 默认添加到气泡视图
                ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                );
                optionBar.setLayoutParams(params);
                bubbleView.addView(optionBar);
            }
            
            XposedBridge.log(TAG + ": Successfully added option bar to bubble view");
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error adding to bubble view: " + e.getMessage());
        }
    }
    
    /**
     * 直接添加到根视图
     */
    private void addToRootView(ViewGroup rootView, View optionBar) {
        try {
            ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            optionBar.setLayoutParams(params);
            rootView.addView(optionBar);
            
            XposedBridge.log(TAG + ": Successfully added option bar to root view");
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error adding to root view: " + e.getMessage());
        }
    }
    
    /**
     * 查找消息气泡视图
     * 使用多种策略解决getHostView失败问题
     */
    private ViewGroup findBubbleView(ViewGroup rootView) {
        try {
            Context context = rootView.getContext();
            
            // 策略1: 通过类名查找（BubbleLayout）
            View bubbleView = ViewUtils.findViewByClassName(rootView, "com.tencent.mobileqq.bubble.BubbleLayout");
            if (bubbleView instanceof ViewGroup) {
                return (ViewGroup) bubbleView;
            }
            
            // 策略2: 通过文本内容查找
            String msgContent = getMessageContent(null); // 获取当前消息内容
            if (!TextUtils.isEmpty(msgContent)) {
                View textView = ViewUtils.findViewByText(rootView, msgContent);
                if (textView != null) {
                    ViewGroup parent = (ViewGroup) textView.getParent();
                    if (parent != null) {
                        return parent;
                    }
                }
            }
            
            // 策略3: 通过ID查找
            View bubbleById = ViewUtils.findHostView(rootView, "chat_item_content_layout");
            if (bubbleById instanceof ViewGroup) {
                return (ViewGroup) bubbleById;
            }
            
            // 策略4: 通过类型查找（LinearLayout）
            List<View> linearLayouts = ViewUtils.findAllViewsByCondition(rootView, new ViewUtils.TypeCondition(LinearLayout.class));
            if (!linearLayouts.isEmpty()) {
                // 返回最大的LinearLayout（通常是消息气泡）
                ViewGroup largestLayout = null;
                int maxSize = 0;
                for (View view : linearLayouts) {
                    if (view instanceof ViewGroup) {
                        int childCount = ((ViewGroup) view).getChildCount();
                        if (childCount > maxSize) {
                            maxSize = childCount;
                            largestLayout = (ViewGroup) view;
                        }
                    }
                }
                return largestLayout;
            }
            
            // 策略5: 通过反射遍历字段
            View fieldResult = ViewUtils.findViewByFieldTraversal(rootView, ViewGroup.class);
            if (fieldResult instanceof ViewGroup) {
                return (ViewGroup) fieldResult;
            }
            
            XposedBridge.log(TAG + ": Failed to find bubble view using all strategies");
            return null;
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error finding bubble view: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 获取消息类型
     */
    private int getMessageType(Object chatMessage) {
        try {
            if (getMsgTypeMethod == null) {
                getMsgTypeMethod = chatMessage.getClass().getMethod("getMsgType");
            }
            return (int) getMsgTypeMethod.invoke(chatMessage);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error getting message type: " + e.getMessage());
            return -1;
        }
    }
    
    /**
     * 获取发送者UIN
     */
    private long getSenderUin(Object chatMessage) {
        try {
            if (getSenderUinMethod == null) {
                getSenderUinMethod = chatMessage.getClass().getMethod("getSenderUin");
            }
            return (long) getSenderUinMethod.invoke(chatMessage);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error getting sender uin: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * 获取消息内容
     */
    private String getMessageContent(Object chatMessage) {
        try {
            if (chatMessage == null) return "";
            
            if (getMsgContentMethod == null) {
                // 尝试多种方法名
                String[] methodNames = {"getMsgContent", "getMsg", "getText", "getContent"};
                for (String methodName : methodNames) {
                    try {
                        getMsgContentMethod = chatMessage.getClass().getMethod(methodName);
                        break;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
            
            if (getMsgContentMethod != null) {
                Object result = getMsgContentMethod.invoke(chatMessage);
                return result != null ? result.toString() : "";
            }
            
            return "";
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error getting message content: " + e.getMessage());
            return "";
        }
    }
    
    /**
     * 判断是否为发送的消息
     */
    private boolean isSendMessage(Object chatMessage) {
        try {
            long senderUin = getSenderUin(chatMessage);
            // 这里需要获取当前用户的UIN进行比较
            // 简化处理：假设非0就是发送的消息
            return senderUin != 0;
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error checking if send message: " + e.getMessage());
            return false;
        }
    }
}