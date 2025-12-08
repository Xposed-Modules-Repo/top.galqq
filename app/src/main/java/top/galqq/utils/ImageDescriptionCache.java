package top.galqq.utils;

import android.util.LruCache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;
import top.galqq.config.ConfigManager;

/**
 * 图片描述缓存管理器
 * 
 * 功能：
 * 1. 缓存外挂AI返回的图片描述，避免重复识别
 * 2. 按会话ID分组管理缓存
 * 3. 使用 msgId + 图片索引 作为唯一标识
 * 4. 自动清理过期缓存
 */
public class ImageDescriptionCache {
    
    private static final String TAG = "GalQQ.ImageCache";
    
    // 最大缓存条目数
    private static final int MAX_CACHE_SIZE = 500;
    
    // 缓存过期时间（毫秒）- 1小时
    private static final long CACHE_EXPIRE_TIME = 60 * 60 * 1000;
    
    // 全局缓存：key = conversationId:msgId:imageIndex, value = CacheEntry
    private static final LruCache<String, CacheEntry> globalCache = new LruCache<>(MAX_CACHE_SIZE);
    
    // 会话级缓存映射：conversationId -> (msgId:imageIndex -> description)
    // 用于快速获取某个会话的所有缓存
    private static final Map<String, Map<String, String>> conversationCacheMap = new ConcurrentHashMap<>();
    
    // 图片元素缓存：key = conversationId:msgId, value = ImageElement列表
    // 用于存储上下文消息的图片元素，以便后续识别
    private static final LruCache<String, java.util.List<ImageExtractor.ImageElement>> imageElementCache = new LruCache<>(200);
    
    /**
     * 缓存条目
     */
    private static class CacheEntry {
        final String description;
        final long timestamp;
        
        CacheEntry(String description) {
            this.description = description;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_EXPIRE_TIME;
        }
    }
    
    /**
     * 生成缓存键
     * @param conversationId 会话ID
     * @param msgId 消息ID
     * @param imageIndex 图片索引（从0开始）
     * @return 缓存键
     */
    private static String buildCacheKey(String conversationId, String msgId, int imageIndex) {
        return conversationId + ":" + msgId + ":" + imageIndex;
    }
    
    /**
     * 生成消息级缓存键（不含会话ID）
     */
    private static String buildMsgCacheKey(String msgId, int imageIndex) {
        return msgId + ":" + imageIndex;
    }
    
    /**
     * 缓存图片描述
     * @param conversationId 会话ID
     * @param msgId 消息ID
     * @param imageIndex 图片索引（从0开始）
     * @param description 图片描述
     */
    public static void put(String conversationId, String msgId, int imageIndex, String description) {
        if (conversationId == null || msgId == null || description == null) {
            return;
        }
        
        String globalKey = buildCacheKey(conversationId, msgId, imageIndex);
        String msgKey = buildMsgCacheKey(msgId, imageIndex);
        
        // 存入全局缓存
        synchronized (globalCache) {
            globalCache.put(globalKey, new CacheEntry(description));
        }
        
        // 存入会话级缓存
        Map<String, String> conversationCache = conversationCacheMap.get(conversationId);
        if (conversationCache == null) {
            conversationCache = new ConcurrentHashMap<>();
            conversationCacheMap.put(conversationId, conversationCache);
        }
        conversationCache.put(msgKey, description);
        
        debugLog("缓存图片描述: " + globalKey + " -> " + truncate(description, 50));
    }
    
    /**
     * 获取缓存的图片描述
     * @param conversationId 会话ID
     * @param msgId 消息ID
     * @param imageIndex 图片索引（从0开始）
     * @return 图片描述，未找到或已过期返回null
     */
    public static String get(String conversationId, String msgId, int imageIndex) {
        if (conversationId == null || msgId == null) {
            return null;
        }
        
        String globalKey = buildCacheKey(conversationId, msgId, imageIndex);
        
        synchronized (globalCache) {
            CacheEntry entry = globalCache.get(globalKey);
            if (entry != null) {
                if (entry.isExpired()) {
                    // 过期，移除缓存
                    globalCache.remove(globalKey);
                    removeFromConversationCache(conversationId, msgId, imageIndex);
                    debugLog("缓存已过期: " + globalKey);
                    return null;
                }
                debugLog("命中缓存: " + globalKey);
                return entry.description;
            }
        }
        
        return null;
    }
    
    /**
     * 检查是否有缓存
     * @param conversationId 会话ID
     * @param msgId 消息ID
     * @param imageIndex 图片索引
     * @return true 如果有有效缓存
     */
    public static boolean has(String conversationId, String msgId, int imageIndex) {
        return get(conversationId, msgId, imageIndex) != null;
    }
    
    /**
     * 获取某个消息的所有图片描述缓存
     * @param conversationId 会话ID
     * @param msgId 消息ID
     * @param imageCount 图片数量
     * @return 图片描述列表，未缓存的位置为null
     */
    public static java.util.List<String> getAll(String conversationId, String msgId, int imageCount) {
        java.util.List<String> descriptions = new java.util.ArrayList<>();
        for (int i = 0; i < imageCount; i++) {
            descriptions.add(get(conversationId, msgId, i));
        }
        return descriptions;
    }
    
    /**
     * 检查某个消息的所有图片是否都已缓存
     * @param conversationId 会话ID
     * @param msgId 消息ID
     * @param imageCount 图片数量
     * @return true 如果所有图片都已缓存
     */
    public static boolean hasAll(String conversationId, String msgId, int imageCount) {
        for (int i = 0; i < imageCount; i++) {
            if (get(conversationId, msgId, i) == null) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * 从会话级缓存中移除
     */
    private static void removeFromConversationCache(String conversationId, String msgId, int imageIndex) {
        Map<String, String> conversationCache = conversationCacheMap.get(conversationId);
        if (conversationCache != null) {
            String msgKey = buildMsgCacheKey(msgId, imageIndex);
            conversationCache.remove(msgKey);
        }
    }
    
    /**
     * 清除指定会话的所有缓存
     * @param conversationId 会话ID
     */
    public static void clearConversation(String conversationId) {
        if (conversationId == null) {
            return;
        }
        
        // 清除会话级缓存
        Map<String, String> conversationCache = conversationCacheMap.remove(conversationId);
        
        // 清除全局缓存中该会话的条目
        if (conversationCache != null) {
            synchronized (globalCache) {
                for (String msgKey : conversationCache.keySet()) {
                    String globalKey = conversationId + ":" + msgKey;
                    globalCache.remove(globalKey);
                }
            }
        }
        
        debugLog("清除会话缓存: " + conversationId);
    }
    
    /**
     * 清除所有缓存
     */
    public static void clearAll() {
        synchronized (globalCache) {
            globalCache.evictAll();
        }
        conversationCacheMap.clear();
        debugLog("清除所有图片描述缓存");
    }
    
    /**
     * 获取缓存统计信息
     * @return 统计信息字符串
     */
    public static String getStats() {
        int globalSize;
        synchronized (globalCache) {
            globalSize = globalCache.size();
        }
        int conversationCount = conversationCacheMap.size();
        return "全局缓存: " + globalSize + " 条, 会话数: " + conversationCount;
    }
    
    /**
     * 调试日志
     */
    private static void debugLog(String message) {
        try {
            if (ConfigManager.isDebugHookLogEnabled()) {
                XposedBridge.log(TAG + ": " + message);
            }
        } catch (Throwable ignored) {}
    }
    
    /**
     * 截断字符串
     */
    private static String truncate(String text, int maxLength) {
        if (text == null) return "null";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
    
    // ========== 图片元素缓存方法 ==========
    
    /**
     * 生成图片元素缓存键
     */
    private static String buildElementCacheKey(String conversationId, String msgId) {
        return conversationId + ":" + msgId;
    }
    
    /**
     * 缓存图片元素列表
     * @param conversationId 会话ID
     * @param msgId 消息ID
     * @param imageElements 图片元素列表
     */
    public static void putImageElements(String conversationId, String msgId, 
                                        java.util.List<ImageExtractor.ImageElement> imageElements) {
        if (conversationId == null || msgId == null || imageElements == null || imageElements.isEmpty()) {
            return;
        }
        
        String key = buildElementCacheKey(conversationId, msgId);
        synchronized (imageElementCache) {
            imageElementCache.put(key, new java.util.ArrayList<>(imageElements));
        }
        debugLog("缓存图片元素: " + key + ", 数量=" + imageElements.size());
    }
    
    /**
     * 获取缓存的图片元素列表
     * @param conversationId 会话ID
     * @param msgId 消息ID
     * @return 图片元素列表，未找到返回null
     */
    public static java.util.List<ImageExtractor.ImageElement> getImageElements(String conversationId, String msgId) {
        if (conversationId == null || msgId == null) {
            return null;
        }
        
        String key = buildElementCacheKey(conversationId, msgId);
        synchronized (imageElementCache) {
            return imageElementCache.get(key);
        }
    }
    
    /**
     * 检查是否有缓存的图片元素
     * @param conversationId 会话ID
     * @param msgId 消息ID
     * @return true 如果有缓存
     */
    public static boolean hasImageElements(String conversationId, String msgId) {
        return getImageElements(conversationId, msgId) != null;
    }
}
