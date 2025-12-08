package top.galqq.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import top.galqq.config.ConfigManager;

/**
 * 图片Base64编码工具类
 * 负责将图片文件或URL转换为Base64编码
 */
public class ImageBase64Helper {

    private static final String TAG = "GalQQ.ImageBase64";
    
    // Base64前缀
    public static final String PREFIX_PNG = "data:image/png;base64,";
    public static final String PREFIX_JPEG = "data:image/jpeg;base64,";
    public static final String PREFIX_GIF = "data:image/gif;base64,";
    public static final String PREFIX_WEBP = "data:image/webp;base64,";
    
    /**
     * 从本地文件路径读取图片并转换为Base64
     * @param filePath 本地文件路径
     * @return Base64编码字符串(带data:image前缀)，失败返回null
     */
    public static String fileToBase64(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return null;
        }
        
        try {
            File file = new File(filePath);
            if (!file.exists() || !file.canRead()) {
                Log.w(TAG, "文件不存在或无法读取: " + filePath);
                return null;
            }
            
            // 检查文件大小
            long fileSizeKB = file.length() / 1024;
            int maxSizeKB = ConfigManager.getImageMaxSize();
            if (fileSizeKB > maxSizeKB) {
                Log.w(TAG, "文件过大: " + fileSizeKB + "KB > " + maxSizeKB + "KB，尝试压缩");
                return compressAndEncode(filePath, maxSizeKB);
            }
            
            // 读取文件内容
            FileInputStream fis = new FileInputStream(file);
            byte[] bytes = readAllBytes(fis);
            fis.close();
            
            // 检测图片类型并添加前缀
            String prefix = detectImagePrefix(filePath, bytes);
            String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            
            if (ConfigManager.isVerboseLogEnabled()) {
                Log.d(TAG, "文件转Base64成功: " + filePath + ", 大小: " + base64.length() + "字符");
            }
            
            return prefix + base64;
            
        } catch (Exception e) {
            Log.e(TAG, "文件转Base64失败: " + filePath, e);
            return null;
        }
    }
    
    /**
     * 从URL下载图片并转换为Base64
     * @param imageUrl 图片URL
     * @return Base64编码字符串(带data:image前缀)，失败返回null
     */
    public static String urlToBase64(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return null;
        }
        
        // 如果是本地文件URL，转换为文件路径处理
        if (imageUrl.startsWith("file://")) {
            return fileToBase64(imageUrl.substring(7));
        }
        
        try {
            URL url = new URL(imageUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            
            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "下载图片失败, HTTP " + responseCode + ": " + imageUrl);
                conn.disconnect();
                return null;
            }
            
            // 检查内容大小
            int contentLength = conn.getContentLength();
            int maxSizeKB = ConfigManager.getImageMaxSize();
            if (contentLength > 0 && contentLength / 1024 > maxSizeKB) {
                Log.w(TAG, "图片过大: " + (contentLength / 1024) + "KB > " + maxSizeKB + "KB");
                conn.disconnect();
                return null;
            }
            
            // 读取图片数据
            InputStream is = conn.getInputStream();
            byte[] bytes = readAllBytes(is);
            is.close();
            conn.disconnect();
            
            // 检测图片类型
            String contentType = conn.getContentType();
            String prefix = detectPrefixFromContentType(contentType, bytes);
            String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            
            if (ConfigManager.isVerboseLogEnabled()) {
                Log.d(TAG, "URL转Base64成功: " + imageUrl + ", 大小: " + base64.length() + "字符");
            }
            
            return prefix + base64;
            
        } catch (Exception e) {
            Log.e(TAG, "URL转Base64失败: " + imageUrl, e);
            return null;
        }
    }
    
    /**
     * 压缩图片并编码为Base64
     * @param filePath 文件路径
     * @param maxSizeKB 目标最大大小(KB)
     * @return Base64编码字符串
     */
    private static String compressAndEncode(String filePath, int maxSizeKB) {
        try {
            // 加载图片
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(filePath, options);
            
            // 计算采样率
            int sampleSize = 1;
            while (options.outWidth / sampleSize > 1024 || options.outHeight / sampleSize > 1024) {
                sampleSize *= 2;
            }
            
            options.inJustDecodeBounds = false;
            options.inSampleSize = sampleSize;
            Bitmap bitmap = BitmapFactory.decodeFile(filePath, options);
            
            if (bitmap == null) {
                Log.w(TAG, "无法解码图片: " + filePath);
                return null;
            }
            
            // 压缩为JPEG
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int quality = 85;
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            
            // 如果还是太大，继续降低质量
            while (baos.size() / 1024 > maxSizeKB && quality > 20) {
                baos.reset();
                quality -= 10;
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            }
            
            bitmap.recycle();
            
            byte[] bytes = baos.toByteArray();
            String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            
            Log.d(TAG, "图片压缩成功: 质量=" + quality + "%, 大小=" + (bytes.length / 1024) + "KB");
            
            return PREFIX_JPEG + base64;
            
        } catch (Exception e) {
            Log.e(TAG, "图片压缩失败: " + filePath, e);
            return null;
        }
    }
    
    /**
     * 根据文件扩展名和魔数检测图片类型前缀
     */
    private static String detectImagePrefix(String filePath, byte[] bytes) {
        // 先检查魔数
        if (bytes != null && bytes.length >= 4) {
            // PNG: 89 50 4E 47
            if (bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
                return PREFIX_PNG;
            }
            // JPEG: FF D8 FF
            if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) {
                return PREFIX_JPEG;
            }
            // GIF: 47 49 46 38
            if (bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x38) {
                return PREFIX_GIF;
            }
            // WebP: 52 49 46 46 ... 57 45 42 50
            if (bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46) {
                if (bytes.length >= 12 && bytes[8] == 0x57 && bytes[9] == 0x45 && bytes[10] == 0x42 && bytes[11] == 0x50) {
                    return PREFIX_WEBP;
                }
            }
        }
        
        // 根据扩展名判断
        String lowerPath = filePath.toLowerCase();
        if (lowerPath.endsWith(".png")) {
            return PREFIX_PNG;
        } else if (lowerPath.endsWith(".gif")) {
            return PREFIX_GIF;
        } else if (lowerPath.endsWith(".webp")) {
            return PREFIX_WEBP;
        }
        
        // 默认JPEG
        return PREFIX_JPEG;
    }
    
    /**
     * 根据Content-Type检测图片类型前缀
     */
    private static String detectPrefixFromContentType(String contentType, byte[] bytes) {
        if (contentType != null) {
            String lower = contentType.toLowerCase();
            if (lower.contains("png")) {
                return PREFIX_PNG;
            } else if (lower.contains("gif")) {
                return PREFIX_GIF;
            } else if (lower.contains("webp")) {
                return PREFIX_WEBP;
            } else if (lower.contains("jpeg") || lower.contains("jpg")) {
                return PREFIX_JPEG;
            }
        }
        
        // 尝试从魔数检测
        return detectImagePrefix("", bytes);
    }
    
    /**
     * 读取InputStream的所有字节
     */
    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }
    
    /**
     * 截断Base64字符串用于日志显示
     * @param base64WithPrefix 带前缀的Base64字符串
     * @param maxLength 最大显示长度
     * @return 截断后的字符串
     */
    public static String truncateForLog(String base64WithPrefix, int maxLength) {
        if (base64WithPrefix == null) {
            return "null";
        }
        if (base64WithPrefix.length() <= maxLength) {
            return base64WithPrefix;
        }
        
        // 保留前缀和部分内容
        int prefixEnd = base64WithPrefix.indexOf(",");
        if (prefixEnd > 0 && prefixEnd < maxLength - 20) {
            // 保留前缀 + 部分base64内容
            String prefix = base64WithPrefix.substring(0, prefixEnd + 1);
            int remainingLength = maxLength - prefix.length() - 15; // 15 for "...[truncated]"
            if (remainingLength > 20) {
                return prefix + base64WithPrefix.substring(prefixEnd + 1, prefixEnd + 1 + remainingLength) + "...[truncated]";
            }
        }
        
        return base64WithPrefix.substring(0, maxLength) + "...[truncated]";
    }
    
    /**
     * 从ImageExtractor.ImageElement获取Base64编码
     * 
     * 优先级（由 ImageDownloader 处理）：
     * 1. sourcePath - 原图本地路径
     * 2. 网络下载 - 通过 originUrl + rkey
     * 3. thumbPath - 缩略图兜底
     * 
     * @param imageElement 图片元素
     * @return Base64编码字符串(带data:image前缀)，失败返回null
     */
    public static String fromImageElement(ImageExtractor.ImageElement imageElement) {
        if (imageElement == null) {
            debugLog("imageElement为null");
            return null;
        }
        
        debugLog("尝试获取图片Base64: fileName=" + imageElement.fileName);
        
        // 获取Context
        android.content.Context context = null;
        try {
            context = top.galqq.utils.AppRuntimeHelper.getApplication();
        } catch (Exception e) {
            debugLog("获取Context失败: " + e.getMessage());
        }
        
        if (context == null) {
            debugLog("Context为null，尝试直接读取本地文件");
            // 降级：直接尝试本地文件
            if (imageElement.sourcePath != null && !imageElement.sourcePath.isEmpty()) {
                String base64 = fileToBase64(imageElement.sourcePath);
                if (base64 != null) {
                    debugLog("sourcePath成功（无Context），Base64长度: " + base64.length());
                    return base64;
                }
            }
            if (imageElement.thumbPath != null && !imageElement.thumbPath.isEmpty()) {
                String base64 = fileToBase64(imageElement.thumbPath);
                if (base64 != null) {
                    debugLog("thumbPath成功（无Context），Base64长度: " + base64.length());
                    return base64;
                }
            }
            debugLog("无Context且本地文件都失败");
            return null;
        }
        
        // 使用 ImageDownloader 处理（它会按优先级尝试 sourcePath -> 网络下载 -> thumbPath）
        String base64Raw = ImageDownloader.downloadAndConvertToBase64(imageElement, context);
        
        if (base64Raw != null && !base64Raw.isEmpty()) {
            // ImageDownloader 返回的是不带前缀的 base64，需要添加前缀
            String mimeType = ImageDownloader.getMimeType(imageElement.fileName);
            String prefix = "data:" + mimeType + ";base64,";
            debugLog("获取图片成功，Base64长度: " + base64Raw.length());
            return prefix + base64Raw;
        }
        
        debugLog("无法获取图片Base64，所有方式都失败");
        return null;
    }
    
    /**
     * 通过URL下载图片并转换为Base64
     * 使用ImageDownloader处理rkey和下载
     * @param imageElement 图片元素
     * @return Base64编码字符串，失败返回null
     */
    public static String fromImageElementByUrl(ImageExtractor.ImageElement imageElement) {
        return fromImageElementByUrl(imageElement, null);
    }
    
    /**
     * 通过URL下载图片并转换为Base64
     * 使用ImageDownloader处理rkey和下载
     * @param imageElement 图片元素
     * @param context Android上下文（可选，如果为null则尝试获取）
     * @return Base64编码字符串，失败返回null
     */
    public static String fromImageElementByUrl(ImageExtractor.ImageElement imageElement, android.content.Context context) {
        if (imageElement == null || imageElement.imageUrl == null || imageElement.imageUrl.isEmpty()) {
            debugLog("图片元素或URL为空");
            return null;
        }
        
        // 获取Context
        if (context == null) {
            try {
                context = top.galqq.utils.AppRuntimeHelper.getApplication();
            } catch (Exception e) {
                debugLog("获取Context失败: " + e.getMessage());
                return null;
            }
        }
        
        if (context == null) {
            debugLog("Context为null，无法下载图片");
            return null;
        }
        
        // 使用ImageDownloader下载并转换
        String base64 = ImageDownloader.downloadAndConvertToBase64(imageElement, context);
        
        if (base64 != null && !base64.isEmpty()) {
            // 添加MIME类型前缀
            String mimeType = ImageDownloader.getMimeType(imageElement.fileName);
            String prefix = "data:" + mimeType + ";base64,";
            debugLog("URL下载成功，Base64长度: " + base64.length());
            return prefix + base64;
        }
        
        debugLog("URL下载失败");
        return null;
    }
    
    /**
     * 调试日志输出
     */
    private static void debugLog(String message) {
        if (ConfigManager.isDebugHookLogEnabled()) {
            de.robv.android.xposed.XposedBridge.log(TAG + ": " + message);
        }
    }
}
