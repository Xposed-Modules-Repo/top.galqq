package top.galqq.utils;

import android.util.Log;

import java.util.List;

import top.galqq.config.ConfigManager;

/**
 * 图片上下文管理器
 * 负责合并文字内容和图片描述,格式化输出
 */
public class ImageContextManager {

    private static final String TAG = "GalQQ.ImageContext";

    /**
     * 合并文字内容和图片描述
     * @param textContent 原始文字内容
     * @param imageDescriptions 图片描述列表
     * @param emojiDescriptions 表情包描述列表
     * @return 合并后的完整内容
     */
    public static String mergeImageContext(String textContent, 
                                           List<String> imageDescriptions,
                                           List<String> emojiDescriptions) {
        StringBuilder result = new StringBuilder();
        
        // 添加原始文字内容
        if (textContent != null && !textContent.trim().isEmpty()) {
            result.append(textContent.trim());
        }
        
        // 添加图片描述
        if (imageDescriptions != null && !imageDescriptions.isEmpty()) {
            if (result.length() > 0) {
                result.append("\n");
            }
            result.append(formatImageDescriptions(imageDescriptions));
        }
        
        // 添加表情包描述
        if (emojiDescriptions != null && !emojiDescriptions.isEmpty()) {
            if (result.length() > 0) {
                result.append("\n");
            }
            result.append(formatEmojiDescriptions(emojiDescriptions));
        }
        
        // 如果所有内容都为空,返回默认提示
        if (result.length() == 0) {
            return "[空消息]";
        }
        
        return result.toString();
    }


    /**
     * 格式化图片描述列表
     * @param descriptions 图片描述列表
     * @return 格式化后的字符串
     */
    private static String formatImageDescriptions(List<String> descriptions) {
        if (descriptions == null || descriptions.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        int maxLength = ConfigManager.getImageDescriptionMaxLength();
        
        if (descriptions.size() == 1) {
            // 单张图片
            String desc = formatImageDescription(descriptions.get(0), 1, maxLength);
            sb.append("[图片: ").append(desc).append("]");
        } else {
            // 多张图片
            sb.append("[图片内容:");
            for (int i = 0; i < descriptions.size(); i++) {
                String desc = formatImageDescription(descriptions.get(i), i + 1, maxLength);
                sb.append("\n  图").append(i + 1).append(": ").append(desc);
            }
            sb.append("]");
        }
        
        return sb.toString();
    }

    /**
     * 格式化表情包描述列表
     * @param descriptions 表情包描述列表
     * @return 格式化后的字符串
     */
    private static String formatEmojiDescriptions(List<String> descriptions) {
        if (descriptions == null || descriptions.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        
        if (descriptions.size() == 1) {
            sb.append("[表情: ").append(descriptions.get(0)).append("]");
        } else {
            sb.append("[表情: ");
            for (int i = 0; i < descriptions.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(descriptions.get(i));
            }
            sb.append("]");
        }
        
        return sb.toString();
    }

    /**
     * 格式化单个图片描述
     * @param description 原始描述
     * @param index 图片索引(从1开始)
     * @param maxLength 最大长度
     * @return 格式化后的描述
     */
    public static String formatImageDescription(String description, int index, int maxLength) {
        if (description == null || description.isEmpty()) {
            return "无法识别";
        }
        
        // 清理描述文本
        String cleaned = description.trim()
                .replaceAll("\\s+", " ")  // 合并多个空白字符
                .replaceAll("[\\r\\n]+", " ");  // 移除换行
        
        // 截断过长描述
        if (maxLength > 0 && cleaned.length() > maxLength) {
            cleaned = cleaned.substring(0, maxLength) + "...";
            if (ConfigManager.isVerboseLogEnabled()) {
                Log.d(TAG, "图片" + index + "描述被截断: " + cleaned.length() + " -> " + maxLength);
            }
        }
        
        return cleaned;
    }


    /**
     * 合并纯图片消息的上下文
     * 当消息只有图片没有文字时使用
     * @param imageDescriptions 图片描述列表
     * @return 格式化后的内容
     */
    public static String mergeImageOnlyContext(List<String> imageDescriptions) {
        return mergeImageContext(null, imageDescriptions, null);
    }

    /**
     * 合并纯表情包消息的上下文
     * @param emojiDescriptions 表情包描述列表
     * @return 格式化后的内容
     */
    public static String mergeEmojiOnlyContext(List<String> emojiDescriptions) {
        return mergeImageContext(null, null, emojiDescriptions);
    }

    /**
     * 检查内容是否包含图片描述标记
     * @param content 内容
     * @return 是否包含图片描述
     */
    public static boolean hasImageDescription(String content) {
        if (content == null) return false;
        return content.contains("[图片:") || content.contains("[图片内容:");
    }

    /**
     * 检查内容是否包含表情包描述标记
     * @param content 内容
     * @return 是否包含表情包描述
     */
    public static boolean hasEmojiDescription(String content) {
        if (content == null) return false;
        return content.contains("[表情:");
    }

    /**
     * 从ImageExtractor的结果创建图片描述列表
     * @param imageElements 图片元素列表
     * @return 图片描述列表(需要后续通过VisionAI填充)
     */
    public static java.util.List<String> createPlaceholderDescriptions(
            List<ImageExtractor.ImageElement> imageElements) {
        return createPlaceholderDescriptions(imageElements, null);
    }
    
    /**
     * 从ImageExtractor的结果创建图片描述列表（带msgId标识）
     * @param imageElements 图片元素列表
     * @param msgId 消息ID，用于唯一标识图片
     * @return 图片描述列表(需要后续通过VisionAI填充)
     */
    public static java.util.List<String> createPlaceholderDescriptions(
            List<ImageExtractor.ImageElement> imageElements, String msgId) {
        java.util.List<String> descriptions = new java.util.ArrayList<>();
        if (imageElements != null) {
            for (int i = 0; i < imageElements.size(); i++) {
                if (msgId != null && !msgId.isEmpty()) {
                    // 使用 msgId 的后6位作为简短标识
                    String shortId = msgId.length() > 6 ? msgId.substring(msgId.length() - 6) : msgId;
                    descriptions.add("[待识别图片#" + shortId + "-" + (i + 1) + "]");
                } else {
                    descriptions.add("[待识别图片" + (i + 1) + "]");
                }
            }
        }
        return descriptions;
    }
    
    /**
     * 从缓存获取图片描述，如果没有缓存则返回占位符
     * @param conversationId 会话ID
     * @param msgId 消息ID
     * @param imageElements 图片元素列表
     * @return 图片描述列表（已缓存的返回描述，未缓存的返回占位符）
     */
    public static java.util.List<String> getDescriptionsWithCache(
            String conversationId, String msgId, List<ImageExtractor.ImageElement> imageElements) {
        java.util.List<String> descriptions = new java.util.ArrayList<>();
        if (imageElements == null || imageElements.isEmpty()) {
            return descriptions;
        }
        
        for (int i = 0; i < imageElements.size(); i++) {
            // 尝试从缓存获取
            String cached = ImageDescriptionCache.get(conversationId, msgId, i);
            if (cached != null) {
                descriptions.add(cached);
            } else {
                // 未缓存，使用带msgId的占位符
                String shortId = (msgId != null && msgId.length() > 6) ? 
                    msgId.substring(msgId.length() - 6) : (msgId != null ? msgId : "unknown");
                descriptions.add("[待识别图片#" + shortId + "-" + (i + 1) + "]");
            }
        }
        return descriptions;
    }

    /**
     * 从ImageExtractor的表情包结果创建描述列表
     * @param emojiElements 表情包元素列表
     * @return 表情包描述列表
     */
    public static java.util.List<String> createEmojiDescriptions(
            List<ImageExtractor.EmojiElement> emojiElements) {
        java.util.List<String> descriptions = new java.util.ArrayList<>();
        if (emojiElements != null) {
            for (ImageExtractor.EmojiElement emoji : emojiElements) {
                if (emoji.emojiText != null && !emoji.emojiText.isEmpty()) {
                    descriptions.add(emoji.emojiText);
                } else if (emoji.emojiId != null && !emoji.emojiId.isEmpty()) {
                    descriptions.add("表情#" + emoji.emojiId);
                } else {
                    descriptions.add("未知表情");
                }
            }
        }
        return descriptions;
    }
}
