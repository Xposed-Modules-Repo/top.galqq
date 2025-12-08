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

import android.app.Application;
import android.content.Context;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import top.galqq.utils.QQNTUtils;

/**
 * MainHook - 主Hook入口
 * 完全模仿QAuxiliary的架构和实现模式
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "GalQQ.MainHook";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        try {
            // 检查是否为QQ应用
            if (!lpparam.packageName.equals("com.tencent.mobileqq")) {
                return;
            }

            XposedBridge.log(TAG + ": GalQQ loaded in QQ package");

            // Hook Application的attach方法，获取Context
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "attach",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                Context context = (Context) param.args[0];
                                ClassLoader classLoader = context.getClassLoader();

                                XposedBridge.log(TAG + ": Application attached, initializing hooks");

                                // 检测架构
                                boolean isNT = QQNTUtils.isQQNT(classLoader);
                                XposedBridge.log(TAG + ": Detected architecture: " + (isNT ? "QQNT" : "Legacy"));

                                // 初始化BaseBubbleBuilderHook
                                BaseBubbleBuilderHook.init(classLoader);

                                XposedBridge.log(TAG + ": All hooks initialized successfully");

                            } catch (Exception e) {
                                XposedBridge.log(TAG + ": Error initializing hooks: " + e.getMessage());
                                XposedBridge.log(e);
                            }
                        }
                    }
            );

        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error in handleLoadPackage: " + e.getMessage());
            XposedBridge.log(e);
        }
    }
}