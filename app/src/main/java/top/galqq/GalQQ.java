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

package top.galqq;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import top.galqq.hook.MainHook;

/**
 * GalQQ - 主入口类
 * 完全模仿QAuxiliary的实现架构
 */
public class GalQQ implements IXposedHookLoadPackage {
    
    private static final String TAG = "GalQQ";
    
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        try {
            XposedBridge.log(TAG + ": GalQQ starting...");
            
            // 委托给MainHook处理
            MainHook mainHook = new MainHook();
            mainHook.handleLoadPackage(lpparam);
            
            XposedBridge.log(TAG + ": GalQQ started successfully");
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error starting GalQQ: " + e.getMessage());
            XposedBridge.log(e);
        }
    }
}