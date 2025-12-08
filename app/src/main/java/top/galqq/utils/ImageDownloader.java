package top.galqq.utils;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XposedBridge;

import top.galqq.config.ConfigManager;
import top.galqq.hook.RkeyHook;

/**
 * 图片下载器 - 通过URL下载QQ图片并转换为Base64
 * 
 * 图片URL格式：
 * - 私聊图片: https://multimedia.nt.qq.com.cn + originUrl + rkey_private (appid=1406)
 * - 群聊图片: https://multimedia.nt.qq.com.cn + originUrl + rkey_group (其他appid)
 * 
 * rkey获取方式：
 * 1. 优先从QQ内部获取（通过hook OidbSvcTrpcTcp.0x9067_202）
 * 2. 兜底从 llob.linyuchen.net/rkey 获取
 */
public class ImageDownloader {
    
    private static final String TAG = "GalQQ.ImageDownloader";
    
    // QQ图片服务器基础URL
    private static final String BASE_URL = "https://multimedia.nt.qq.com.cn";
    
    // 兜底rkey API（多个备用服务器）
    private static final String[] RKEY_API_URLS = {
        "https://llob.linyuchen.net/rkey",
        "http://ss.xingzhige.com/music_card/rkey",
        "https://secret-service.bietiaop.com/rkeys"
    };
    
    // 旧版图片服务器URL（不需要rkey）
    private static final String LEGACY_BASE_URL = "https://gchat.qpic.cn";
    
    // 缓存的rkey（来自API兜底，Hook获取的rkey直接从RkeyHook.rkey_group/rkey_private读取）
    private static volatile String cachedGroupRkey = null;
    private static volatile String cachedPrivateRkey = null;
    private static volatile long rkeyExpireTime = 0;
    
    // 下载超时设置
    private static final int CONNECT_TIMEOUT = 10000; // 10秒
    private static final int READ_TIMEOUT = 30000; // 30秒
    
    /**
     * 调试日志输出（受 gal_debug_hook_log 配置开关控制）
     */
    private static void debugLog(String message) {
        try {
            if (ConfigManager.isDebugHookLogEnabled()) {
                XposedBridge.log(TAG + ": " + message);
            }
        } catch (Throwable ignored) {
            // ConfigManager 未初始化时忽略
        }
    }
    
    /**
     * 下载图片并转换为Base64
     * 
     * 优先级：
     * 1. sourcePath - 原图本地路径（最高优先级）
     * 2. 网络下载 - 通过 originUrl + rkey 下载
     * 3. thumbPath - 缩略图本地路径（兜底）
     * 
     * @param imageElement 图片元素
     * @param context Android上下文
     * @return Base64编码的图片数据，失败返回null
     */
    public static String downloadAndConvertToBase64(ImageExtractor.ImageElement imageElement, Context context) {
        if (imageElement == null) {
            debugLog("图片元素为空");
            return null;
        }
        
        // 【增强调试】打印完整的图片元素信息
        debugLog("========== 开始处理图片下载 ==========");
        debugLog("ImageElement 完整信息:");
        debugLog("  fileName: " + imageElement.fileName);
        debugLog("  fileSize: " + imageElement.fileSize);
        debugLog("  width: " + imageElement.width);
        debugLog("  height: " + imageElement.height);
        debugLog("  md5: " + imageElement.md5);
        debugLog("  fileUuid: " + imageElement.fileUuid);
        debugLog("  imageUrl (originImageUrl): " + imageElement.imageUrl);
        debugLog("  sourcePath: " + imageElement.sourcePath);
        debugLog("  thumbPath: " + imageElement.thumbPath);
        
        // ========== 优先级1: 尝试 sourcePath（原图本地路径）==========
        if (imageElement.sourcePath != null && !imageElement.sourcePath.isEmpty()) {
            debugLog("★ [优先级1] 尝试 sourcePath: " + imageElement.sourcePath);
            java.io.File sourceFile = new java.io.File(imageElement.sourcePath);
            if (sourceFile.exists() && sourceFile.canRead()) {
                debugLog("★ sourcePath 文件存在且可读，直接转换为Base64");
                String base64 = fileToBase64(sourceFile);
                if (base64 != null) {
                    debugLog("★ sourcePath 转Base64成功，长度: " + base64.length());
                    return base64;
                } else {
                    debugLog("sourcePath 转Base64失败，继续尝试其他方式");
                }
            } else {
                debugLog("sourcePath 文件不存在或不可读: exists=" + sourceFile.exists() + ", canRead=" + sourceFile.canRead());
            }
        } else {
            debugLog("[优先级1] sourcePath 为空，跳过");
        }
        
        // ========== 优先级2: 尝试网络下载 ==========
        debugLog("[优先级2] 尝试网络下载...");
        
        // 【打印 RkeyHook 状态】
        debugLog("RkeyHook 状态:");
        debugLog("  rkey_group: " + (top.galqq.hook.RkeyHook.rkey_group != null ? 
            top.galqq.hook.RkeyHook.rkey_group.substring(0, Math.min(50, top.galqq.hook.RkeyHook.rkey_group.length())) + "..." : "null"));
        debugLog("  rkey_private: " + (top.galqq.hook.RkeyHook.rkey_private != null ? 
            top.galqq.hook.RkeyHook.rkey_private.substring(0, Math.min(50, top.galqq.hook.RkeyHook.rkey_private.length())) + "..." : "null"));
        debugLog("  hasValidRkey: " + top.galqq.hook.RkeyHook.hasValidRkey());
        debugLog("  stats: " + top.galqq.hook.RkeyHook.getStats());
        
        String originUrl = imageElement.imageUrl;
        String md5 = imageElement.md5;
        
        // 尝试网络下载
        if ((originUrl != null && !originUrl.isEmpty()) || (md5 != null && !md5.isEmpty())) {
            debugLog("准备网络下载: originUrl=" + originUrl + ", md5=" + md5);
            
            try {
                // 构建完整URL（支持 originUrl 为空时使用 MD5 构建备用 URL）
                String fullUrl = buildFullUrl(originUrl, md5);
                if (fullUrl != null) {
                    debugLog("完整URL: " + fullUrl);
                    
                    // 下载图片到临时文件
                    File tempFile = downloadToTempFile(fullUrl, context);
                    if (tempFile != null) {
                        debugLog("图片下载成功，临时文件: " + tempFile.getAbsolutePath());
                        
                        // 转换为Base64
                        String base64 = fileToBase64(tempFile);
                        
                        // 删除临时文件
                        if (tempFile.exists()) {
                            boolean deleted = tempFile.delete();
                            debugLog("删除临时文件: " + (deleted ? "成功" : "失败"));
                        }
                        
                        if (base64 != null) {
                            debugLog("★ 网络下载转Base64成功，长度: " + base64.length());
                            return base64;
                        } else {
                            debugLog("网络下载转Base64失败，继续尝试兜底方式");
                        }
                    } else {
                        debugLog("下载图片失败，继续尝试兜底方式");
                    }
                } else {
                    debugLog("构建完整URL失败，继续尝试兜底方式");
                }
            } catch (Exception e) {
                debugLog("网络下载异常: " + e.getMessage() + "，继续尝试兜底方式");
            }
        } else {
            debugLog("[优先级2] originUrl 和 md5 都为空，跳过网络下载");
        }
        
        // ========== 优先级3: 尝试 thumbPath（缩略图兜底）==========
        if (imageElement.thumbPath != null && !imageElement.thumbPath.isEmpty()) {
            debugLog("★ [优先级3-兜底] 尝试 thumbPath: " + imageElement.thumbPath);
            java.io.File thumbFile = new java.io.File(imageElement.thumbPath);
            if (thumbFile.exists() && thumbFile.canRead()) {
                debugLog("★ thumbPath 文件存在且可读，直接转换为Base64");
                String base64 = fileToBase64(thumbFile);
                if (base64 != null) {
                    debugLog("★ thumbPath 转Base64成功（兜底），长度: " + base64.length());
                    return base64;
                } else {
                    debugLog("thumbPath 转Base64失败");
                }
            } else {
                debugLog("thumbPath 文件不存在或不可读: exists=" + thumbFile.exists() + ", canRead=" + thumbFile.canRead());
            }
        } else {
            debugLog("[优先级3] thumbPath 为空，无法兜底");
        }
        
        debugLog("========== 所有方式都失败，无法获取图片 ==========");
        return null;
    }

    /**
     * 构建完整的图片URL
     * 参考 QAuxiliary 的 StickerPanelEntryHooker 和 PicMd5Hook 实现
     * 
     * @param originUrl 原始URL路径
     * @param md5 图片MD5（用于构建备用URL）
     * @return 完整URL
     */
    public static String buildFullUrl(String originUrl, String md5) {
        debugLog("---------- buildFullUrl 开始 ----------");
        debugLog("输入参数: originUrl=" + originUrl + ", md5=" + md5);
        
        // 如果已经是完整URL，直接返回
        if (originUrl != null && (originUrl.startsWith("http://") || originUrl.startsWith("https://"))) {
            debugLog("情况0: 已是完整URL，直接返回");
            return originUrl;
        }
        
        // 情况1：originUrl 为空，使用 MD5 构建旧版 URL（不需要 rkey）
        if (originUrl == null || originUrl.isEmpty()) {
            if (md5 != null && !md5.isEmpty()) {
                String url = LEGACY_BASE_URL + "/gchatpic_new/0/0-0-" + md5.toUpperCase() + "/0";
                debugLog("情况1: originUrl为空，使用MD5构建旧版URL（不需要rkey）");
                debugLog("构建的URL: " + url);
                return url;
            }
            debugLog("情况1失败: originUrl和MD5都为空，无法构建URL");
            return null;
        }
        
        // 情况2：originUrl 以 /download 开头，需要 rkey（QQNT 新版）
        if (originUrl.startsWith("/download")) {
            debugLog("情况2: QQNT新版URL格式（以/download开头）");
            
            // 判断是群聊还是私聊图片
            // 注意：appid=1406 表示私聊图片，需要使用 private_rkey
            // 参考 NapCatQQ: const rkey = appid === '1406' ? rkeyData.private_rkey : rkeyData.group_rkey;
            boolean useGroupRkey = !originUrl.contains("appid=1406");
            debugLog("  appid检测: " + (useGroupRkey ? "群聊图片(非1406)" : "私聊图片(appid=1406)"));
            
            // 获取对应的 rkey
            debugLog("  开始获取rkey...");
            String rkey = getRkey(useGroupRkey);
            debugLog("  获取到的rkey: " + (rkey != null ? rkey.substring(0, Math.min(80, rkey.length())) + "..." : "null"));
            
            String url = BASE_URL + originUrl;
            debugLog("  基础URL: " + url.substring(0, Math.min(100, url.length())) + "...");
            
            if (rkey != null && !rkey.isEmpty()) {
                // QAuxiliary 的 rkey 已经包含 &rkey= 前缀，直接拼接
                url += rkey;
                debugLog("  最终URL（带rkey）: " + url.substring(0, Math.min(150, url.length())) + "...");
            } else {
                debugLog("  ⚠ 获取rkey失败，URL不带rkey（可能无法访问）");
                debugLog("  最终URL（无rkey）: " + url.substring(0, Math.min(150, url.length())) + "...");
            }
            return url;
        }
        
        // 情况3：旧版 URL（不以 /download 开头），使用 gchat.qpic.cn，不需要 rkey
        String url = LEGACY_BASE_URL + originUrl;
        debugLog("情况3: 旧版URL格式（不以/download开头），不需要rkey");
        debugLog("构建的URL: " + url);
        return url;
    }
    
    /**
     * 构建完整的图片URL（不带MD5参数的重载方法）
     * @param originUrl 原始URL路径
     * @return 完整URL
     */
    private static String buildFullUrl(String originUrl) {
        return buildFullUrl(originUrl, null);
    }
    
    /**
     * 获取rkey
     * 完全按照 QAuxiliary 的方式：优先使用 RkeyHook 的静态变量，如果没有则使用 API 兜底
     * 
     * @param isGroup 是否是群聊图片
     * @return rkey字符串
     */
    private static String getRkey(boolean isGroup) {
        debugLog("    [getRkey] 开始获取rkey, isGroup=" + isGroup);
        
        // 优先使用 RkeyHook 获取的 rkey（和 QAuxiliary 一样直接访问静态变量）
        debugLog("    [getRkey] 检查RkeyHook静态变量...");
        debugLog("    [getRkey]   RkeyHook.rkey_group = " + (RkeyHook.rkey_group != null ? 
            "'" + RkeyHook.rkey_group.substring(0, Math.min(50, RkeyHook.rkey_group.length())) + "...'" : "null"));
        debugLog("    [getRkey]   RkeyHook.rkey_private = " + (RkeyHook.rkey_private != null ? 
            "'" + RkeyHook.rkey_private.substring(0, Math.min(50, RkeyHook.rkey_private.length())) + "...'" : "null"));
        
        String hookedRkey = isGroup ? RkeyHook.rkey_group : RkeyHook.rkey_private;
        if (hookedRkey != null && !hookedRkey.isEmpty()) {
            debugLog("    [getRkey] ✓ 使用RkeyHook获取的rkey: " + (isGroup ? "group" : "private"));
            return hookedRkey;
        }
        debugLog("    [getRkey] RkeyHook中没有有效的rkey");
        
        // 检查 API 缓存是否有效
        debugLog("    [getRkey] 检查API缓存...");
        debugLog("    [getRkey]   当前时间: " + System.currentTimeMillis());
        debugLog("    [getRkey]   缓存过期时间: " + rkeyExpireTime);
        debugLog("    [getRkey]   缓存是否有效: " + (System.currentTimeMillis() < rkeyExpireTime));
        debugLog("    [getRkey]   cachedGroupRkey = " + (cachedGroupRkey != null ? 
            "'" + cachedGroupRkey.substring(0, Math.min(50, cachedGroupRkey.length())) + "...'" : "null"));
        debugLog("    [getRkey]   cachedPrivateRkey = " + (cachedPrivateRkey != null ? 
            "'" + cachedPrivateRkey.substring(0, Math.min(50, cachedPrivateRkey.length())) + "...'" : "null"));
        
        if (System.currentTimeMillis() < rkeyExpireTime) {
            String cached = isGroup ? cachedGroupRkey : cachedPrivateRkey;
            if (cached != null && !cached.isEmpty()) {
                debugLog("    [getRkey] ✓ 使用API缓存的rkey: " + (isGroup ? "group" : "private"));
                return cached;
            }
        }
        debugLog("    [getRkey] API缓存无效或为空");
        
        // 从兜底API获取rkey
        debugLog("    [getRkey] 尝试从兜底API获取rkey...");
        fetchRkeyFromApi();
        
        String result = isGroup ? cachedGroupRkey : cachedPrivateRkey;
        debugLog("    [getRkey] API获取结果: " + (result != null ? 
            "'" + result.substring(0, Math.min(50, result.length())) + "...'" : "null"));
        
        return result;
    }
    
    /**
     * 检查是否有 Hook 获取的 rkey
     * @return true 如果有 Hook 获取的 rkey
     */
    public static boolean hasHookedRkey() {
        return RkeyHook.hasValidRkey();
    }
    
    /**
     * 从兜底API获取rkey（尝试多个服务器）
     */
    private static synchronized void fetchRkeyFromApi() {
        // 再次检查缓存（双重检查锁定）
        if (System.currentTimeMillis() < rkeyExpireTime) {
            if (cachedGroupRkey != null && cachedPrivateRkey != null) {
                return;
            }
        }
        
        // 尝试多个服务器
        for (String apiUrl : RKEY_API_URLS) {
            if (tryFetchRkeyFromUrl(apiUrl)) {
                debugLog("成功从 " + apiUrl + " 获取rkey");
                return;
            }
        }
        
        debugLog("所有rkey API都失败了");
    }
    
    /**
     * 尝试从指定URL获取rkey
     */
    private static boolean tryFetchRkeyFromUrl(String apiUrl) {
        HttpURLConnection conn = null;
        try {
            debugLog("请求rkey API: " + apiUrl);
            
            URL url = new URL(apiUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "GalQQ/1.0");
            
            int responseCode = conn.getResponseCode();
            debugLog("rkey API响应码: " + responseCode);
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                is.close();
                
                String response = baos.toString("UTF-8");
                debugLog("rkey API响应: " + response);
                
                // 解析JSON响应
                return parseRkeyResponse(response);
            } else {
                debugLog("rkey API请求失败: " + responseCode);
                return false;
            }
            
        } catch (Exception e) {
            debugLog("获取rkey异常: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
    
    /**
     * 解析rkey API响应
     * 响应格式1: {"private_rkey":"&rkey=xxx","group_rkey":"&rkey=xxx","expired_time":1764784979}
     * 响应格式2 (NapCatQQ): {"group_rkey":"xxx","private_rkey":"xxx","expired_time":xxx}
     * 注意：NapCatQQ 的 rkey 需要去掉前6个字符（"&rkey="前缀）
     * @return true 如果解析成功
     */
    private static boolean parseRkeyResponse(String response) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(response);
            
            // 支持 OneBot 风格的响应（有 data 字段）
            if (json.has("data") && json.get("data") instanceof org.json.JSONObject) {
                json = json.getJSONObject("data");
            }
            
            String privateRkey = null;
            String groupRkey = null;
            
            // 获取private_rkey
            if (json.has("private_rkey")) {
                privateRkey = json.getString("private_rkey");
            }
            
            // 获取group_rkey
            if (json.has("group_rkey")) {
                groupRkey = json.getString("group_rkey");
            }
            
            // 检查是否获取到了 rkey
            if (privateRkey == null && groupRkey == null) {
                debugLog("响应中没有找到 rkey");
                return false;
            }
            
            // 处理 rkey 格式（确保以 &rkey= 开头）
            if (privateRkey != null) {
                if (!privateRkey.startsWith("&rkey=")) {
                    // NapCatQQ 格式，需要添加前缀
                    privateRkey = "&rkey=" + privateRkey;
                }
                cachedPrivateRkey = privateRkey;
                debugLog("获取到private_rkey: " + cachedPrivateRkey.substring(0, Math.min(50, cachedPrivateRkey.length())) + "...");
            }
            
            if (groupRkey != null) {
                if (!groupRkey.startsWith("&rkey=")) {
                    // NapCatQQ 格式，需要添加前缀
                    groupRkey = "&rkey=" + groupRkey;
                }
                cachedGroupRkey = groupRkey;
                debugLog("获取到group_rkey: " + cachedGroupRkey.substring(0, Math.min(50, cachedGroupRkey.length())) + "...");
            }
            
            // 获取过期时间
            if (json.has("expired_time")) {
                long expiredTime = json.getLong("expired_time");
                // 转换为毫秒，并提前5分钟过期以确保安全
                rkeyExpireTime = expiredTime * 1000 - TimeUnit.MINUTES.toMillis(5);
                debugLog("rkey过期时间: " + expiredTime);
            } else {
                // 默认缓存1小时
                rkeyExpireTime = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1);
            }
            
            return cachedPrivateRkey != null || cachedGroupRkey != null;
            
        } catch (Exception e) {
            debugLog("解析rkey响应失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 下载图片到临时文件
     * @param imageUrl 图片URL
     * @param context Android上下文
     * @return 临时文件，失败返回null
     */
    private static File downloadToTempFile(String imageUrl, Context context) {
        HttpURLConnection conn = null;
        FileOutputStream fos = null;
        InputStream is = null;
        File tempFile = null;
        
        try {
            // 创建临时文件目录
            // 优先使用 QQ 的外部存储目录（/storage/emulated/0/Android/data/com.tencent.mobileqq/）
            File galqqCacheDir = null;
            
            // 尝试使用外部存储目录
            File externalDir = context.getExternalFilesDir(null);
            if (externalDir != null && externalDir.canWrite()) {
                galqqCacheDir = new File(externalDir, "galqq_images");
                debugLog("使用外部存储目录: " + galqqCacheDir.getAbsolutePath());
            }
            
            // 如果外部存储不可用，使用内部缓存目录
            if (galqqCacheDir == null || (!galqqCacheDir.exists() && !galqqCacheDir.mkdirs())) {
                File cacheDir = context.getCacheDir();
                galqqCacheDir = new File(cacheDir, "galqq_images");
                debugLog("使用内部缓存目录: " + galqqCacheDir.getAbsolutePath());
            }
            
            if (!galqqCacheDir.exists()) {
                galqqCacheDir.mkdirs();
            }
            
            tempFile = new File(galqqCacheDir, "img_" + System.currentTimeMillis() + ".tmp");
            debugLog("临时文件路径: " + tempFile.getAbsolutePath());
            
            // 建立连接
            URL url = new URL(imageUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "image/*");
            conn.setInstanceFollowRedirects(true);
            
            int responseCode = conn.getResponseCode();
            debugLog("图片下载响应码: " + responseCode);
            
            if (responseCode != HttpURLConnection.HTTP_OK) {
                debugLog("图片下载失败，HTTP状态码: " + responseCode);
                return null;
            }
            
            // 获取内容长度
            int contentLength = conn.getContentLength();
            debugLog("图片大小: " + contentLength + " bytes");
            
            // 检查文件大小限制（默认2MB）
            int maxSize = ConfigManager.getInt(ConfigManager.KEY_IMAGE_MAX_SIZE, ConfigManager.DEFAULT_IMAGE_MAX_SIZE) * 1024;
            if (contentLength > maxSize) {
                debugLog("图片太大，跳过下载: " + contentLength + " > " + maxSize);
                return null;
            }
            
            // 下载文件
            is = conn.getInputStream();
            fos = new FileOutputStream(tempFile);
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            int totalRead = 0;
            
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
            }
            
            fos.flush();
            debugLog("图片下载完成，实际大小: " + totalRead + " bytes");
            
            return tempFile;
            
        } catch (Exception e) {
            debugLog("下载图片异常: " + e.getMessage());
            // 清理临时文件
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
            return null;
            
        } finally {
            try {
                if (is != null) is.close();
                if (fos != null) fos.close();
                if (conn != null) conn.disconnect();
            } catch (Exception e) {
                // 忽略关闭异常
            }
        }
    }
    
    /**
     * 将文件转换为Base64字符串
     * @param file 文件
     * @return Base64字符串
     */
    private static String fileToBase64(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            byte[] bytes = new byte[(int) file.length()];
            int read = fis.read(bytes);
            
            if (read != bytes.length) {
                debugLog("文件读取不完整: " + read + "/" + bytes.length);
            }
            
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
            
        } catch (Exception e) {
            debugLog("文件转Base64异常: " + e.getMessage());
            return null;
        } finally {
            try {
                if (fis != null) fis.close();
            } catch (Exception e) {
                // 忽略
            }
        }
    }
    
    /**
     * 手动设置rkey（供外部hook调用）
     * @param groupRkey 群聊rkey
     * @param privateRkey 私聊rkey
     */
    public static void setRkey(String groupRkey, String privateRkey) {
        debugLog("手动设置rkey - group: " + groupRkey + ", private: " + privateRkey);
        cachedGroupRkey = groupRkey;
        cachedPrivateRkey = privateRkey;
        // 设置1小时过期
        rkeyExpireTime = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1);
    }
    
    /**
     * 清除rkey缓存
     */
    public static void clearRkeyCache() {
        debugLog("清除rkey缓存");
        cachedGroupRkey = null;
        cachedPrivateRkey = null;
        rkeyExpireTime = 0;
    }
    
    /**
     * 检查rkey是否有效
     * @return true如果rkey有效
     */
    public static boolean isRkeyValid() {
        return System.currentTimeMillis() < rkeyExpireTime 
            && cachedGroupRkey != null 
            && cachedPrivateRkey != null;
    }
    
    /**
     * 获取图片的MIME类型
     * @param fileName 文件名
     * @return MIME类型
     */
    public static String getMimeType(String fileName) {
        if (fileName == null) {
            return "image/jpeg";
        }
        
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        } else if (lower.endsWith(".gif")) {
            return "image/gif";
        } else if (lower.endsWith(".webp")) {
            return "image/webp";
        } else if (lower.endsWith(".bmp")) {
            return "image/bmp";
        } else {
            return "image/jpeg";
        }
    }
    
    /**
     * 清理过期的临时文件
     * @param context Android上下文
     */
    public static void cleanupTempFiles(Context context) {
        try {
            // 清理两个可能的目录
            cleanupDirectory(new File(context.getCacheDir(), "galqq_images"));
            
            File externalDir = context.getExternalFilesDir(null);
            if (externalDir != null) {
                cleanupDirectory(new File(externalDir, "galqq_images"));
            }
            
        } catch (Exception e) {
            debugLog("清理临时文件异常: " + e.getMessage());
        }
    }
    
    /**
     * 清理指定目录中的过期文件
     */
    private static void cleanupDirectory(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        
        long now = System.currentTimeMillis();
        int deleted = 0;
        
        for (File file : files) {
            // 删除超过1小时的临时文件
            if (now - file.lastModified() > TimeUnit.HOURS.toMillis(1)) {
                if (file.delete()) {
                    deleted++;
                }
            }
        }
        
        if (deleted > 0) {
            debugLog("清理了 " + deleted + " 个过期临时文件: " + dir.getAbsolutePath());
        }
    }
    
    /**
     * 通过URL下载图片并转换为base64
     * @param imageUrl 图片URL
     * @param context Android上下文
     * @return base64字符串（不带前缀），失败返回null
     */
    public static String downloadAndConvertToBase64ByUrl(String imageUrl, Context context) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return null;
        }
        
        debugLog("开始通过URL下载图片: " + imageUrl);
        
        try {
            // 创建临时文件
            File tempFile = downloadToTempFile(imageUrl, context);
            if (tempFile == null || !tempFile.exists()) {
                debugLog("下载失败或文件不存在");
                return null;
            }
            
            // 转换为base64
            String base64 = fileToBase64(tempFile);
            
            // 清理临时文件
            try {
                if (tempFile.delete()) {
                    debugLog("临时文件已删除: " + tempFile.getName());
                }
            } catch (Exception e) {
                debugLog("删除临时文件失败: " + e.getMessage());
            }
            
            return base64;
            
        } catch (Exception e) {
            debugLog("下载图片异常: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 根据URL获取MIME类型
     * @param url 图片URL
     * @return MIME类型
     */
    public static String getMimeTypeFromUrl(String url) {
        if (url == null) {
            return "image/jpeg";
        }
        
        String lowerUrl = url.toLowerCase();
        if (lowerUrl.contains(".png")) {
            return "image/png";
        } else if (lowerUrl.contains(".gif")) {
            return "image/gif";
        } else if (lowerUrl.contains(".webp")) {
            return "image/webp";
        } else {
            return "image/jpeg"; // 默认
        }
    }
}
