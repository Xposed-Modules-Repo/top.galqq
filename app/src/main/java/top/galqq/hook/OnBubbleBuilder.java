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

import android.view.ViewGroup;
import de.robv.android.xposed.XC_MethodHook;

/**
 * 装饰器接口 - 模仿QAuxiliary的OnBubbleBuilder
 * 用于在聊天消息视图中注入自定义功能
 */
public interface OnBubbleBuilder {
    
    /**
     * 非NT版本消息视图处理
     * 适用于传统QQ架构
     */
    void onGetView(
        ViewGroup rootView,
        Object chatMessage,
        XC_MethodHook.MethodHookParam param
    ) throws Exception;

    /**
     * NT版本消息视图处理
     * 适用于QQNT新架构
     */
    void onGetViewNt(
        ViewGroup rootView,
        Object msgRecord,
        XC_MethodHook.MethodHookParam param
    ) throws Exception;
}