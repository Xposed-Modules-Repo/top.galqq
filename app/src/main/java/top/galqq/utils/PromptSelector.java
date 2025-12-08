package top.galqq.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import top.galqq.config.ConfigManager;
import top.galqq.config.ConfigManager.PromptItem;

/**
 * 提示词选择器
 * 根据发送者QQ号和优先级决策链选择合适的提示词
 */
public class PromptSelector {
    
    private static final String TAG = "GalQQ.PromptSelector";
    
    private static void debugLog(String message) {
        try {
            if (top.galqq.config.ConfigManager.isVerboseLogEnabled()) {
                de.robv.android.xposed.XposedBridge.log(message);
            }
        } catch (Throwable ignored) {}
    }
    
    /**
     * 计算单个提示词对指定QQ和群的状态
     * 
     * 优先级决策链（从高到低）：
     * 1. 提示词禁用 -> FORCE_OFF
     * 2. 用户白名单 -> FORCE_ON
     * 3. 群白名单 -> FORCE_ON
     * 4. 用户黑名单 -> FORCE_OFF
     * 5. 群黑名单 -> FORCE_OFF
     * 6. 默认状态 -> 根据aiEnabled返回DEFAULT或FORCE_OFF
     * 
     * @param prompt 提示词
     * @param senderQQ 发送者QQ号
     * @param groupId 群号（私聊时为null或对方QQ号）
     * @param aiEnabled AI是否启用
     * @return 提示词状态
     */
    public static PromptStatus calculateStatus(PromptItem prompt, String senderQQ, String groupId, boolean aiEnabled) {
        // 1. 如果提示词被禁用，直接返回 FORCE_OFF（黑白名单都不触发）
        if (!prompt.enabled) {
            return PromptStatus.FORCE_OFF;
        }
        // 2. 用户白名单（最高优先级）
        if (prompt.isInWhitelist(senderQQ)) {
            return PromptStatus.FORCE_ON;
        }
        // 3. 群白名单
        if (prompt.isInGroupWhitelist(groupId)) {
            return PromptStatus.FORCE_ON;
        }
        // 4. 用户黑名单
        if (prompt.isInBlacklist(senderQQ)) {
            return PromptStatus.FORCE_OFF;
        }
        // 5. 群黑名单
        if (prompt.isInGroupBlacklist(groupId)) {
            return PromptStatus.FORCE_OFF;
        }
        // 6. 默认状态取决于AI是否启用
        return aiEnabled ? PromptStatus.DEFAULT : PromptStatus.FORCE_OFF;
    }
    
    /**
     * 选择适用的提示词列表
     * @param allPrompts 所有提示词
     * @param senderQQ 发送者QQ号
     * @param groupId 群号（私聊时为null或对方QQ号）
     * @param aiEnabled AI是否启用
     * @return 空列表表示全部被屏蔽，单元素表示白名单命中，多元素表示默认选择
     */
    public static List<PromptItem> selectPrompts(List<PromptItem> allPrompts, String senderQQ, String groupId, boolean aiEnabled) {
        if (allPrompts == null || allPrompts.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<PromptItem> forceOnPrompts = new ArrayList<>();
        List<PromptItem> defaultPrompts = new ArrayList<>();
        boolean hasForceOff = false;
        
        boolean verboseLog = ConfigManager.isVerboseLogEnabled();
        
        for (PromptItem prompt : allPrompts) {
            PromptStatus status = calculateStatus(prompt, senderQQ, groupId, aiEnabled);
            
            if (verboseLog) {
                debugLog(TAG + ": [" + prompt.name + "] status=" + status.name() + " for QQ: " + senderQQ + ", Group: " + groupId);
            }
            
            if (status == PromptStatus.FORCE_ON) {
                forceOnPrompts.add(prompt);
            } else if (status == PromptStatus.DEFAULT) {
                defaultPrompts.add(prompt);
            } else {
                hasForceOff = true;
            }
        }
        
        // 优先级决策链
        if (!forceOnPrompts.isEmpty()) {
            // 返回最高优先级的白名单提示词（第一个）
            PromptItem selected = forceOnPrompts.get(0);
            debugLog(TAG + ": 白名单命中: " + selected.name + " for QQ: " + senderQQ + ", Group: " + groupId);
            return Collections.singletonList(selected);
        }
        
        if (!defaultPrompts.isEmpty()) {
            debugLog(TAG + ": 使用默认提示词列表 for QQ: " + senderQQ + ", Group: " + groupId);
            return defaultPrompts;
        }
        
        // 全部被屏蔽
        debugLog(TAG + ": 所有提示词被黑名单屏蔽 for QQ: " + senderQQ + ", Group: " + groupId);
        return Collections.emptyList();
    }
    
    /**
     * 获取选中的单个提示词（用于自动选择场景）
     * 优先级规则：
     * 1. 白名单命中的提示词（按列表顺序，第一个优先）
     * 2. 默认状态的提示词（按列表顺序，第一个优先）
     * 
     * @param allPrompts 所有提示词
     * @param senderQQ 发送者QQ号
     * @param groupId 群号（私聊时为null或对方QQ号）
     * @param aiEnabled AI是否启用
     * @return 选中的提示词，如果全部被屏蔽则返回null
     */
    public static PromptItem getSelectedPrompt(List<PromptItem> allPrompts, String senderQQ, String groupId, boolean aiEnabled) {
        List<PromptItem> selected = selectPrompts(allPrompts, senderQQ, groupId, aiEnabled);
        if (selected.isEmpty()) {
            return null;
        }
        
        // 返回第一个可用的提示词（顺序决定优先级）
        return selected.get(0);
    }
}
