package top.galqq.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import top.galqq.config.ConfigManager;

/**
 * 模型列表获取工具类
 * 通过调用 /v1/models API 获取可用模型列表
 * 支持按API URL缓存模型列表
 */
public class ModelListFetcher {
    private static final String TAG = "GalQQ_ModelListFetcher";
    private static final int TIMEOUT = 15000; // 15秒超时
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // 模型列表缓存：API URL -> 模型列表
    private static final Map<String, List<String>> modelCache = new HashMap<>();
    // 缓存时间戳：API URL -> 缓存时间
    private static final Map<String, Long> cacheTimestamps = new HashMap<>();
    // 缓存有效期：30分钟
    private static final long CACHE_DURATION = 30 * 60 * 1000;
    
    /**
     * 回调接口
     */
    public interface ModelListCallback {
        void onSuccess(List<String> models);
        void onFailure(String error);
    }
    
    /**
     * 获取模型列表（使用缓存）
     * @param context 上下文
     * @param callback 回调
     */
    public static void fetchModels(Context context, ModelListCallback callback) {
        fetchModels(context, false, callback);
    }
    
    /**
     * 获取模型列表
     * @param context 上下文
     * @param forceRefresh 是否强制刷新（忽略缓存）
     * @param callback 回调
     */
    public static void fetchModels(Context context, boolean forceRefresh, ModelListCallback callback) {
        executor.execute(() -> {
            try {
                ConfigManager.init(context);
                String apiUrl = ConfigManager.getApiUrl();
                
                // 检查缓存
                if (!forceRefresh && apiUrl != null && !apiUrl.isEmpty()) {
                    String cacheKey = getCacheKey(apiUrl);
                    List<String> cachedModels = getCachedModels(cacheKey);
                    if (cachedModels != null) {
                        Log.d(TAG, "Using cached models for: " + cacheKey);
                        mainHandler.post(() -> callback.onSuccess(cachedModels));
                        return;
                    }
                }
                
                List<String> models = fetchModelsSync(context);
                
                // 缓存结果
                if (apiUrl != null && !apiUrl.isEmpty() && !models.isEmpty()) {
                    String cacheKey = getCacheKey(apiUrl);
                    cacheModels(cacheKey, models);
                }
                
                mainHandler.post(() -> callback.onSuccess(models));
            } catch (Exception e) {
                Log.e(TAG, "Failed to fetch models: " + e.getMessage(), e);
                mainHandler.post(() -> callback.onFailure(e.getMessage()));
            }
        });
    }
    
    /**
     * 获取缓存键（从API URL提取基础URL）
     */
    private static String getCacheKey(String apiUrl) {
        if (apiUrl == null) return "";
        // 提取基础URL（去除路径部分）
        try {
            URL url = new URL(apiUrl);
            return url.getProtocol() + "://" + url.getHost() + (url.getPort() != -1 ? ":" + url.getPort() : "");
        } catch (Exception e) {
            return apiUrl;
        }
    }
    
    /**
     * 获取缓存的模型列表
     */
    private static synchronized List<String> getCachedModels(String cacheKey) {
        if (!modelCache.containsKey(cacheKey)) {
            return null;
        }
        Long timestamp = cacheTimestamps.get(cacheKey);
        if (timestamp == null || System.currentTimeMillis() - timestamp > CACHE_DURATION) {
            // 缓存过期
            modelCache.remove(cacheKey);
            cacheTimestamps.remove(cacheKey);
            return null;
        }
        return new ArrayList<>(modelCache.get(cacheKey));
    }
    
    /**
     * 缓存模型列表
     */
    private static synchronized void cacheModels(String cacheKey, List<String> models) {
        modelCache.put(cacheKey, new ArrayList<>(models));
        cacheTimestamps.put(cacheKey, System.currentTimeMillis());
        Log.d(TAG, "Cached " + models.size() + " models for: " + cacheKey);
    }
    
    /**
     * 清除所有缓存
     */
    public static synchronized void clearCache() {
        modelCache.clear();
        cacheTimestamps.clear();
        Log.d(TAG, "Model cache cleared");
    }
    
    /**
     * 清除指定API URL的缓存
     */
    public static synchronized void clearCache(String apiUrl) {
        String cacheKey = getCacheKey(apiUrl);
        modelCache.remove(cacheKey);
        cacheTimestamps.remove(cacheKey);
        Log.d(TAG, "Model cache cleared for: " + cacheKey);
    }
    
    /**
     * 同步获取模型列表
     */
    private static List<String> fetchModelsSync(Context context) throws Exception {
        ConfigManager.init(context);
        
        String apiUrl = ConfigManager.getApiUrl();
        String apiKey = ConfigManager.getApiKey();
        
        if (apiUrl == null || apiUrl.isEmpty()) {
            throw new Exception("API URL未配置");
        }
        
        if (apiKey == null || apiKey.isEmpty()) {
            throw new Exception("API Key未配置");
        }
        
        // 构建 /v1/models URL
        String modelsUrl = buildModelsUrl(apiUrl);
        Log.d(TAG, "Fetching models from: " + modelsUrl);
        
        URL url = new URL(modelsUrl);
        HttpURLConnection conn;
        
        // 检查是否启用代理
        if (ConfigManager.isProxyEnabled()) {
            String proxyHost = ConfigManager.getProxyHost();
            int proxyPort = ConfigManager.getProxyPort();
            String proxyType = ConfigManager.getProxyType();
            
            if (proxyHost != null && !proxyHost.isEmpty() && proxyPort > 0) {
                Proxy.Type type = "SOCKS".equalsIgnoreCase(proxyType) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
                Proxy proxy = new Proxy(type, new InetSocketAddress(proxyHost, proxyPort));
                conn = (HttpURLConnection) url.openConnection(proxy);
                Log.d(TAG, "Using proxy: " + proxyType + " " + proxyHost + ":" + proxyPort);
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }
        } else {
            conn = (HttpURLConnection) url.openConnection();
        }
        
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        
        int responseCode = conn.getResponseCode();
        Log.d(TAG, "Response code: " + responseCode);
        
        if (responseCode != 200) {
            // 读取错误信息
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
            StringBuilder errorResponse = new StringBuilder();
            String line;
            while ((line = errorReader.readLine()) != null) {
                errorResponse.append(line);
            }
            errorReader.close();
            throw new Exception("HTTP " + responseCode + ": " + errorResponse.toString());
        }
        
        // 读取响应
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        conn.disconnect();
        
        // 解析JSON
        return parseModelsResponse(response.toString());
    }
    
    /**
     * 构建 /v1/models URL
     */
    private static String buildModelsUrl(String apiUrl) {
        // 移除末尾的斜杠
        apiUrl = apiUrl.trim();
        while (apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }
        
        // 如果URL已经包含 /v1/chat/completions，替换为 /v1/models
        if (apiUrl.contains("/v1/chat/completions")) {
            return apiUrl.replace("/v1/chat/completions", "/v1/models");
        }
        
        // 如果URL已经包含 /v1，直接添加 /models
        if (apiUrl.endsWith("/v1")) {
            return apiUrl + "/models";
        }
        
        // 如果URL包含 /v1/ 但不是以它结尾，找到 /v1 的位置并替换后面的部分
        int v1Index = apiUrl.indexOf("/v1/");
        if (v1Index != -1) {
            return apiUrl.substring(0, v1Index) + "/v1/models";
        }
        
        // 否则直接在末尾添加 /v1/models
        return apiUrl + "/v1/models";
    }
    
    /**
     * 解析模型列表响应
     * 响应格式: {"data": [{"id": "model-id", ...}, ...], "object": "list"}
     */
    private static List<String> parseModelsResponse(String jsonResponse) throws Exception {
        List<String> models = new ArrayList<>();
        
        JSONObject root = new JSONObject(jsonResponse);
        
        if (!root.has("data")) {
            throw new Exception("响应格式错误：缺少data字段");
        }
        
        JSONArray dataArray = root.getJSONArray("data");
        
        for (int i = 0; i < dataArray.length(); i++) {
            JSONObject modelObj = dataArray.getJSONObject(i);
            if (modelObj.has("id")) {
                String modelId = modelObj.getString("id");
                if (modelId != null && !modelId.isEmpty()) {
                    models.add(modelId);
                }
            }
        }
        
        // 按字母顺序排序
        java.util.Collections.sort(models, String.CASE_INSENSITIVE_ORDER);
        
        Log.d(TAG, "Fetched " + models.size() + " models");
        return models;
    }
}
