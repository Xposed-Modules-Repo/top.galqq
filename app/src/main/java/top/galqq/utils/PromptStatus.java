package top.galqq.utils;

/**
 * 提示词状态枚举
 * 用于表示提示词对特定QQ号的生效状态
 */
public enum PromptStatus {
    /**
     * 强制关闭 - 黑名单命中或AI未启用
     */
    FORCE_OFF(0),
    
    /**
     * 默认状态 - 无黑白名单限制且AI启用
     */
    DEFAULT(1),
    
    /**
     * 强制开启 - 白名单命中
     */
    FORCE_ON(2);
    
    public final int value;
    
    PromptStatus(int value) {
        this.value = value;
    }
}
