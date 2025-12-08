package top.galqq.utils;

import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 图片提取器 - 从QQ消息中提取图片和表情包元素
 */
public class ImageExtractor {
    
    private static final String TAG = "GalQQ.ImageExtractor";
    
    /**
     * 从消息记录中提取图片元素
     * @param msgRecord QQNT MsgRecord对象
     * @return 图片元素列表
     */
    public static List<ImageElement> extractImages(Object msgRecord) {
        List<ImageElement> images = new ArrayList<>();
        
        try {
            // 获取elements列表
            List<?> elements = (List<?>) XposedHelpers.getObjectField(msgRecord, "elements");
            if (elements == null || elements.isEmpty()) {
                return images;
            }
            
            // 遍历每个element
            for (Object element : elements) {
                try {
                    // 检查elementType是否为2(图片)
                    int elementType = XposedHelpers.getIntField(element, "elementType");
                    if (elementType != 2) {
                        continue;
                    }
                    
                    // 获取picElement
                    Object picElement = XposedHelpers.getObjectField(element, "picElement");
                    if (picElement == null) {
                        continue;
                    }
                    
                    // 【详细调试】打印picElement的所有字段
                    if (top.galqq.config.ConfigManager.isDebugHookLogEnabled()) {
                        dumpAllFields(picElement, "picElement");
                    }
                    
                    // 提取图片信息
                    ImageElement img = new ImageElement();
                    
                    // 基本信息
                    img.fileName = getStringField(picElement, "fileName");
                    img.fileSize = getLongField(picElement, "fileSize");
                    img.width = getIntField(picElement, "picWidth");
                    img.height = getIntField(picElement, "picHeight");
                    
                    // 标识信息
                    img.md5 = getStringField(picElement, "md5HexStr");
                    img.fileUuid = getStringField(picElement, "fileUuid");
                    
                    // URL信息 - 尝试多个可能的字段
                    img.imageUrl = getStringField(picElement, "originImageUrl");
                    
                    // 尝试获取sourcePath(本地路径)
                    img.sourcePath = getStringField(picElement, "sourcePath");
                    
                    // 尝试获取thumbPath(缩略图路径) - 可能是Map<Integer, String>类型
                    img.thumbPath = getThumbPathFromMap(picElement);
                    
                    // 调试: 打印提取结果
                    if (top.galqq.config.ConfigManager.isDebugHookLogEnabled()) {
                        XposedBridge.log(TAG + ": ===== 图片提取结果 =====");
                        XposedBridge.log(TAG + ":   originImageUrl=" + img.imageUrl);
                        XposedBridge.log(TAG + ":   sourcePath=" + img.sourcePath);
                        XposedBridge.log(TAG + ":   thumbPath=" + img.thumbPath);
                        XposedBridge.log(TAG + ":   fileUuid=" + img.fileUuid);
                        XposedBridge.log(TAG + ":   md5=" + img.md5);
                    }
                    
                    // 只添加有效的图片(至少有文件名、URL或本地路径)
                    if (img.fileName != null || img.imageUrl != null || img.sourcePath != null || img.thumbPath != null) {
                        images.add(img);
                        if (top.galqq.config.ConfigManager.isDebugHookLogEnabled()) {
                            XposedBridge.log(TAG + ": 成功提取图片: " + img.toString());
                        }
                    } else {
                        if (top.galqq.config.ConfigManager.isDebugHookLogEnabled()) {
                            XposedBridge.log(TAG + ": 跳过无效图片元素(无有效路径)");
                        }
                    }
                    
                } catch (Throwable t) {
                    // 单个图片提取失败,记录日志并继续处理下一个
                    XposedBridge.log(TAG + ": Failed to extract single image: " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            // 整体提取失败,记录日志并返回空列表
            XposedBridge.log(TAG + ": Failed to extract images: " + t.getMessage());
            XposedBridge.log(t);
        }
        
        return images;
    }
    
    /**
     * 从消息记录中提取表情包元素
     * @param msgRecord QQNT MsgRecord对象
     * @return 表情包元素列表
     */
    public static List<EmojiElement> extractEmojis(Object msgRecord) {
        List<EmojiElement> emojis = new ArrayList<>();
        
        try {
            List<?> elements = (List<?>) XposedHelpers.getObjectField(msgRecord, "elements");
            if (elements == null || elements.isEmpty()) {
                return emojis;
            }
            
            for (Object element : elements) {
                try {
                    // 检查系统表情
                    Object faceElement = XposedHelpers.getObjectField(element, "faceElement");
                    if (faceElement != null) {
                        EmojiElement emoji = new EmojiElement();
                        // TODO: 等待表情包数据结构确认后实现
                        // emoji.emojiId = getStringField(faceElement, "faceId");
                        // emoji.emojiText = getStringField(faceElement, "faceText");
                        emoji.emojiType = EmojiElement.TYPE_SYSTEM;
                        emojis.add(emoji);
                        continue;
                    }
                    
                    // 检查商城表情包
                    Object marketFaceElement = XposedHelpers.getObjectField(element, "marketFaceElement");
                    if (marketFaceElement != null) {
                        EmojiElement emoji = new EmojiElement();
                        // TODO: 等待表情包数据结构确认后实现
                        emoji.emojiType = EmojiElement.TYPE_MARKET;
                        emojis.add(emoji);
                    }
                } catch (Throwable t) {
                    // 单个表情包提取失败,继续处理下一个
                    XposedBridge.log(TAG + ": Failed to extract single emoji: " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            // 整体提取失败,返回空列表
            XposedBridge.log(TAG + ": Failed to extract emojis: " + t.getMessage());
            XposedBridge.log(t);
        }
        
        return emojis;
    }
    
    // ========== 辅助方法 ==========
    
    /**
     * 【调试】打印对象的所有字段
     */
    private static void dumpAllFields(Object obj, String objName) {
        if (obj == null) {
            XposedBridge.log(TAG + ": [DUMP] " + objName + " = null");
            return;
        }
        
        XposedBridge.log(TAG + ": ========== [DUMP] " + objName + " 所有字段 ==========");
        XposedBridge.log(TAG + ": [DUMP] 类型: " + obj.getClass().getName());
        
        try {
            // 获取所有字段（包括父类）
            Class<?> clazz = obj.getClass();
            while (clazz != null && !clazz.equals(Object.class)) {
                java.lang.reflect.Field[] fields = clazz.getDeclaredFields();
                for (java.lang.reflect.Field field : fields) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(obj);
                        String valueStr;
                        
                        if (value == null) {
                            valueStr = "null";
                        } else if (value instanceof byte[]) {
                            byte[] bytes = (byte[]) value;
                            valueStr = "byte[" + bytes.length + "]";
                            // 如果是小数组，打印前几个字节
                            if (bytes.length > 0 && bytes.length <= 100) {
                                StringBuilder sb = new StringBuilder(" = [");
                                for (int i = 0; i < Math.min(bytes.length, 20); i++) {
                                    if (i > 0) sb.append(", ");
                                    sb.append(String.format("%02X", bytes[i] & 0xFF));
                                }
                                if (bytes.length > 20) sb.append("...");
                                sb.append("]");
                                valueStr += sb.toString();
                            }
                        } else if (value instanceof java.util.Map) {
                            java.util.Map<?, ?> map = (java.util.Map<?, ?>) value;
                            valueStr = "Map{size=" + map.size() + ", content=" + map + "}";
                        } else if (value instanceof java.util.List) {
                            java.util.List<?> list = (java.util.List<?>) value;
                            valueStr = "List{size=" + list.size() + "}";
                        } else {
                            String str = String.valueOf(value);
                            // 截断过长的字符串
                            if (str.length() > 200) {
                                valueStr = str.substring(0, 200) + "...[truncated]";
                            } else {
                                valueStr = str;
                            }
                        }
                        
                        XposedBridge.log(TAG + ": [DUMP]   ." + field.getName() + " = " + valueStr);
                        
                        // 特别关注可能包含路径的字段
                        if (field.getName().toLowerCase().contains("path") || 
                            field.getName().toLowerCase().contains("url") ||
                            field.getName().toLowerCase().contains("file") ||
                            field.getName().toLowerCase().contains("local") ||
                            field.getName().toLowerCase().contains("cache") ||
                            field.getName().toLowerCase().contains("download")) {
                            XposedBridge.log(TAG + ": [DUMP]     ⭐ 可能包含路径信息!");
                        }
                        
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": [DUMP]   ." + field.getName() + " = [获取失败: " + t.getMessage() + "]");
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [DUMP] 遍历字段失败: " + t.getMessage());
        }
        
        XposedBridge.log(TAG + ": ========== [DUMP] " + objName + " 结束 ==========");
    }
    
    /**
     * 安全获取String字段
     */
    private static String getStringField(Object obj, String fieldName) {
        try {
            Object value = XposedHelpers.getObjectField(obj, fieldName);
            return value != null ? String.valueOf(value) : null;
        } catch (Throwable t) {
            return null;
        }
    }
    
    /**
     * 安全获取int字段
     */
    private static int getIntField(Object obj, String fieldName) {
        try {
            return XposedHelpers.getIntField(obj, fieldName);
        } catch (Throwable t) {
            return 0;
        }
    }
    
    /**
     * 安全获取long字段
     */
    private static long getLongField(Object obj, String fieldName) {
        try {
            return XposedHelpers.getLongField(obj, fieldName);
        } catch (Throwable t) {
            return 0L;
        }
    }
    
    /**
     * 从thumbPath Map中提取缩略图路径
     * thumbPath字段是Map<Integer, String>类型，键是分辨率(如720)，值是路径
     */
    @SuppressWarnings("unchecked")
    private static String getThumbPathFromMap(Object picElement) {
        try {
            Object thumbPathObj = XposedHelpers.getObjectField(picElement, "thumbPath");
            if (thumbPathObj == null) {
                return null;
            }
            
            // 如果是Map类型
            if (thumbPathObj instanceof java.util.Map) {
                java.util.Map<?, ?> thumbPathMap = (java.util.Map<?, ?>) thumbPathObj;
                if (thumbPathMap.isEmpty()) {
                    return null;
                }
                
                // 优先获取720分辨率的缩略图
                Object path720 = thumbPathMap.get(720);
                if (path720 != null) {
                    return String.valueOf(path720);
                }
                
                // 如果没有720，尝试获取最大分辨率的
                int maxRes = 0;
                String bestPath = null;
                for (java.util.Map.Entry<?, ?> entry : thumbPathMap.entrySet()) {
                    Object key = entry.getKey();
                    int res = 0;
                    if (key instanceof Integer) {
                        res = (Integer) key;
                    } else if (key instanceof String) {
                        try {
                            res = Integer.parseInt((String) key);
                        } catch (NumberFormatException e) {
                            // 忽略非数字键
                        }
                    }
                    if (res > maxRes && entry.getValue() != null) {
                        maxRes = res;
                        bestPath = String.valueOf(entry.getValue());
                    }
                }
                
                if (bestPath != null) {
                    return bestPath;
                }
                
                // 最后尝试获取任意一个值
                for (Object value : thumbPathMap.values()) {
                    if (value != null) {
                        return String.valueOf(value);
                    }
                }
            }
            
            // 如果不是Map，直接转为String
            return String.valueOf(thumbPathObj);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to get thumbPath from map: " + t.getMessage());
            return null;
        }
    }
    
    /**
     * 将相对URL转换为完整URL
     * 注意：此方法不包含rkey，完整的带rkey的URL请使用ImageDownloader
     */
    public static String getFullImageUrl(String relativeUrl) {
        if (relativeUrl == null || relativeUrl.isEmpty()) {
            return null;
        }
        
        // 已经是完整URL
        if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
            return relativeUrl;
        }
        
        // QQ图片服务器地址
        String baseUrl = "https://multimedia.nt.qq.com.cn";
        
        // 确保URL格式正确
        if (!relativeUrl.startsWith("/")) {
            relativeUrl = "/" + relativeUrl;
        }
        
        return baseUrl + relativeUrl;
    }
    
    /**
     * 判断图片是否为私聊图片
     * appid=1406 表示私聊图片（注意：不是群聊！）
     * 参考 NapCatQQ: const rkey = appid === '1406' ? rkeyData.private_rkey : rkeyData.group_rkey;
     */
    public static boolean isPrivateImage(String originUrl) {
        return originUrl != null && originUrl.contains("appid=1406");
    }
    
    /**
     * 判断图片是否为群聊图片
     * 非 appid=1406 的都是群聊图片
     */
    public static boolean isGroupImage(String originUrl) {
        return originUrl != null && !originUrl.contains("appid=1406");
    }
    
    // ========== 数据类 ==========
    
    /**
     * 图片元素数据类
     */
    public static class ImageElement {
        public String fileName;      // 文件名
        public long fileSize;        // 文件大小(字节)
        public int width;            // 图片宽度
        public int height;           // 图片高度
        public String md5;           // MD5哈希
        public String imageUrl;      // 图片URL(相对路径)
        public String fileUuid;      // 文件UUID
        public String sourcePath;    // 本地源文件路径
        public String thumbPath;     // 缩略图路径
        
        /**
         * 获取完整的图片URL
         * 优先使用本地路径,其次使用网络URL
         */
        public String getFullUrl() {
            // 优先使用本地源文件路径(如果存在且可访问)
            if (sourcePath != null && !sourcePath.isEmpty()) {
                java.io.File file = new java.io.File(sourcePath);
                if (file.exists()) {
                    return "file://" + sourcePath;
                }
            }
            
            // 其次使用缩略图路径(如果存在且可访问)
            if (thumbPath != null && !thumbPath.isEmpty()) {
                java.io.File file = new java.io.File(thumbPath);
                if (file.exists()) {
                    return "file://" + thumbPath;
                }
            }
            
            // 最后使用网络URL
            return getFullImageUrl(imageUrl);
        }
        
        /**
         * 获取本地文件路径(用于读取文件转base64)
         * 优先sourcePath，其次thumbPath
         */
        public String getLocalFilePath() {
            // 优先使用本地源文件路径
            if (sourcePath != null && !sourcePath.isEmpty()) {
                java.io.File file = new java.io.File(sourcePath);
                if (file.exists()) {
                    return sourcePath;
                }
            }
            
            // 其次使用缩略图路径
            if (thumbPath != null && !thumbPath.isEmpty()) {
                java.io.File file = new java.io.File(thumbPath);
                if (file.exists()) {
                    return thumbPath;
                }
            }
            
            return null;
        }
        
        /**
         * 获取用于AI的图片描述
         * 包含URL和基本信息
         */
        public String getDescriptionForAi() {
            StringBuilder sb = new StringBuilder();
            
            String url = getFullUrl();
            if (url != null && !url.isEmpty()) {
                sb.append(url);
            } else {
                sb.append("[无法获取图片URL]");
            }
            
            // 添加图片尺寸信息
            if (width > 0 && height > 0) {
                sb.append(" (").append(width).append("x").append(height).append(")");
            }
            
            return sb.toString();
        }
        
        /**
         * 获取图片描述(用于日志)
         */
        @Override
        public String toString() {
            return "ImageElement{" +
                    "fileName='" + fileName + '\'' +
                    ", fileSize=" + fileSize +
                    ", width=" + width +
                    ", height=" + height +
                    ", md5='" + md5 + '\'' +
                    ", imageUrl='" + imageUrl + '\'' +
                    ", sourcePath='" + sourcePath + '\'' +
                    ", thumbPath='" + thumbPath + '\'' +
                    '}';
        }
    }
    
    /**
     * 表情包元素数据类
     */
    public static class EmojiElement {
        public static final int TYPE_SYSTEM = 1;  // 系统表情
        public static final int TYPE_MARKET = 2;  // 商城表情包
        
        public int emojiType;        // 表情包类型
        public String emojiId;       // 表情包ID
        public String emojiText;     // 表情包文字描述
        public String emojiUrl;      // 表情包URL(如果可用)
        
        /**
         * 获取表情包描述(用于日志)
         */
        @Override
        public String toString() {
            return "EmojiElement{" +
                    "emojiType=" + (emojiType == TYPE_SYSTEM ? "SYSTEM" : "MARKET") +
                    ", emojiId='" + emojiId + '\'' +
                    ", emojiText='" + emojiText + '\'' +
                    '}';
        }
    }
}
