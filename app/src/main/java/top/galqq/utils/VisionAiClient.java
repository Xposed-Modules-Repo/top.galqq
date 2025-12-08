package top.galqq.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;

import org.json.JSONArray;
import org.json.JSONObject;

import top.galqq.config.ConfigManager;

/**
 * 外挂AI客户端 - 用于图片识别
 * 支持OpenAI Vision API格式，兼容多种服务商
 */
public class VisionAiClient {

    private static final String TAG = "GalQQ.Vision";
    private static OkHttpClient client;
    private static OkHttpClient clientWithProxy;
    private static String lastProxyConfig = "";
    private static Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // 图片描述提示词
    private static final String IMAGE_DESCRIPTION_PROMPT = 
        "请简洁描述这张图片的内容，用于帮助AI理解聊天上下文。" +
        "描述应该简短、客观，包含图片中的主要元素、场景、人物表情或动作等关键信息。" +
        "不要添加主观评价，不超过100字。如果是表情包或梗图，请描述其含义。";

    /**
     * 图片分析回调接口
     */
    public interface VisionCallback {
        void onSuccess(String description);
        void onFailure(Exception e);
    }

    /**
     * 批量图片分析回调接口
     */
    public interface BatchVisionCallback {
        void onSuccess(List<String> descriptions);
        void onFailure(Exception e);
    }


    /**
     * 连接测试回调接口
     */
    public interface TestCallback {
        void onResult(boolean success, String message);
    }

    /**
     * 获取OkHttpClient实例
     * 根据外挂AI代理配置决定是否使用代理
     */
    private static synchronized OkHttpClient getClient() {
        // 检查外挂AI是否使用代理
        if (ConfigManager.isVisionUseProxy() && ConfigManager.isProxyEnabled() && ConfigManager.isProxyConfigValid()) {
            return getClientWithProxy();
        }
        
        // 不使用代理的客户端
        if (client == null) {
            int timeout = ConfigManager.getVisionTimeout();
            client = new OkHttpClient.Builder()
                    .connectTimeout(timeout, TimeUnit.SECONDS)
                    .readTimeout(timeout * 2, TimeUnit.SECONDS)
                    .writeTimeout(timeout, TimeUnit.SECONDS)
                    .build();
        }
        return client;
    }

    /**
     * 获取带代理的OkHttpClient实例
     */
    private static synchronized OkHttpClient getClientWithProxy() {
        String currentProxyConfig = buildProxyConfigKey();
        
        if (clientWithProxy != null && currentProxyConfig.equals(lastProxyConfig)) {
            return clientWithProxy;
        }
        
        String proxyType = ConfigManager.getProxyType();
        String proxyHost = ConfigManager.getProxyHost();
        int proxyPort = ConfigManager.getProxyPort();
        int timeout = ConfigManager.getVisionTimeout();
        
        Log.d(TAG, "创建Vision代理客户端: " + proxyType + "://" + proxyHost + ":" + proxyPort);
        
        Proxy.Type type = "SOCKS".equalsIgnoreCase(proxyType) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        Proxy proxy = new Proxy(type, new InetSocketAddress(proxyHost, proxyPort));
        
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(timeout + 5, TimeUnit.SECONDS)
                .readTimeout(timeout * 2 + 10, TimeUnit.SECONDS)
                .writeTimeout(timeout + 5, TimeUnit.SECONDS);
        
        if (ConfigManager.isProxyAuthEnabled()) {
            String username = ConfigManager.getProxyUsername();
            String password = ConfigManager.getProxyPassword();
            
            if (username != null && !username.isEmpty()) {
                builder.proxyAuthenticator(new Authenticator() {
                    @Override
                    public Request authenticate(Route route, Response response) throws IOException {
                        String credential = Credentials.basic(username, password);
                        return response.request().newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build();
                    }
                });
            }
        }
        
        clientWithProxy = builder.build();
        lastProxyConfig = currentProxyConfig;
        
        return clientWithProxy;
    }

    private static String buildProxyConfigKey() {
        return ConfigManager.getProxyType() + "://" +
               ConfigManager.getProxyHost() + ":" +
               ConfigManager.getProxyPort() + "@" +
               ConfigManager.isProxyAuthEnabled() + ":" +
               ConfigManager.getProxyUsername() + ":" +
               ConfigManager.getVisionTimeout();
    }

    /**
     * 重置客户端（配置变化时调用）
     */
    public static synchronized void resetClient() {
        client = null;
        clientWithProxy = null;
        lastProxyConfig = "";
        Log.d(TAG, "Vision客户端已重置");
    }


    /**
     * 分析单张图片
     * @param context Android上下文
     * @param imageUrl 图片URL（优先使用）
     * @param imageBase64 图片Base64数据（URL为空时使用）
     * @param callback 回调
     */
    public static void analyzeImage(Context context, String imageUrl, String imageBase64, VisionCallback callback) {
        if (!ConfigManager.isVisionAiEnabled()) {
            callback.onFailure(new Exception("外挂AI未启用"));
            return;
        }
        
        String apiUrl = ConfigManager.getVisionApiUrl();
        String apiKey = ConfigManager.getVisionApiKey();
        String model = ConfigManager.getVisionAiModel();
        
        if (apiUrl == null || apiUrl.trim().isEmpty()) {
            callback.onFailure(new Exception("外挂AI API URL未配置"));
            return;
        }
        
        if (apiKey == null || apiKey.trim().isEmpty()) {
            callback.onFailure(new Exception("外挂AI API Key未配置"));
            return;
        }
        
        try {
            JSONObject jsonBody = buildVisionRequest(model, imageUrl, imageBase64, null);
            
            RequestBody body = RequestBody.create(
                    jsonBody.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );
            
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();
            
            if (ConfigManager.isVerboseLogEnabled()) {
                Log.d(TAG, "发送Vision请求: " + model + " -> " + apiUrl);
            }
            
            getClient().newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Vision请求失败: " + e.getMessage(), e);
                    mainHandler.post(() -> callback.onFailure(e));
                }
                
                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        if (!response.isSuccessful()) {
                            String error = "HTTP " + response.code() + ": " + response.message();
                            String responseBody = response.body() != null ? response.body().string() : "";
                            Log.e(TAG, "Vision响应错误: " + error + "\n" + responseBody);
                            mainHandler.post(() -> callback.onFailure(new Exception(error)));
                            return;
                        }
                        
                        String responseBody = response.body() != null ? response.body().string() : "";
                        String description = parseVisionResponse(responseBody);
                        
                        if (ConfigManager.isVerboseLogEnabled()) {
                            Log.d(TAG, "Vision响应: " + description);
                        }
                        
                        mainHandler.post(() -> callback.onSuccess(description));
                    } finally {
                        response.close();
                    }
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "构建Vision请求失败", e);
            callback.onFailure(e);
        }
    }


    /**
     * 批量分析多张图片
     * @param context Android上下文
     * @param imageUrls 图片URL列表
     * @param imageBase64List 图片Base64数据列表（与URL一一对应，URL为空时使用）
     * @param callback 回调
     */
    public static void analyzeImages(Context context, List<String> imageUrls, 
                                     List<String> imageBase64List, BatchVisionCallback callback) {
        if (!ConfigManager.isVisionAiEnabled()) {
            callback.onFailure(new Exception("外挂AI未启用"));
            return;
        }
        
        if (imageUrls == null || imageUrls.isEmpty()) {
            callback.onSuccess(new ArrayList<>());
            return;
        }
        
        String apiUrl = ConfigManager.getVisionApiUrl();
        String apiKey = ConfigManager.getVisionApiKey();
        String model = ConfigManager.getVisionAiModel();
        
        if (apiUrl == null || apiUrl.trim().isEmpty()) {
            callback.onFailure(new Exception("外挂AI API URL未配置"));
            return;
        }
        
        if (apiKey == null || apiKey.trim().isEmpty()) {
            callback.onFailure(new Exception("外挂AI API Key未配置"));
            return;
        }
        
        try {
            // 构建包含多张图片的请求
            JSONObject jsonBody = buildMultiImageVisionRequest(model, imageUrls, imageBase64List);
            
            RequestBody body = RequestBody.create(
                    jsonBody.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );
            
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();
            
            if (ConfigManager.isVerboseLogEnabled()) {
                Log.d(TAG, "发送批量Vision请求: " + model + ", 图片数: " + imageUrls.size());
            }
            
            getClient().newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "批量Vision请求失败: " + e.getMessage(), e);
                    mainHandler.post(() -> callback.onFailure(e));
                }
                
                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        if (!response.isSuccessful()) {
                            String error = "HTTP " + response.code() + ": " + response.message();
                            String responseBody = response.body() != null ? response.body().string() : "";
                            Log.e(TAG, "批量Vision响应错误: " + error + "\n" + responseBody);
                            mainHandler.post(() -> callback.onFailure(new Exception(error)));
                            return;
                        }
                        
                        String responseBody = response.body() != null ? response.body().string() : "";
                        String description = parseVisionResponse(responseBody);
                        
                        // 将单个描述包装为列表返回
                        List<String> descriptions = new ArrayList<>();
                        descriptions.add(description);
                        
                        if (ConfigManager.isVerboseLogEnabled()) {
                            Log.d(TAG, "批量Vision响应: " + description);
                        }
                        
                        mainHandler.post(() -> callback.onSuccess(descriptions));
                    } finally {
                        response.close();
                    }
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "构建批量Vision请求失败", e);
            callback.onFailure(e);
        }
    }


    /**
     * 测试外挂AI连接
     * 使用项目图标进行测试，更可靠
     * @param context Android上下文
     * @param callback 测试结果回调
     */
    public static void testConnection(Context context, TestCallback callback) {
        if (!ConfigManager.isVisionAiEnabled()) {
            callback.onResult(false, "外挂AI未启用");
            return;
        }
        
        String apiUrl = ConfigManager.getVisionApiUrl();
        String apiKey = ConfigManager.getVisionApiKey();
        String model = ConfigManager.getVisionAiModel();
        
        if (apiUrl == null || apiUrl.trim().isEmpty()) {
            callback.onResult(false, "API URL未配置");
            return;
        }
        
        if (apiKey == null || apiKey.trim().isEmpty()) {
            callback.onResult(false, "API Key未配置");
            return;
        }
        
        try {
            // 使用项目图标进行测试（从drawable资源获取）
            String testImageBase64 = getAppIconBase64(context);
            if (testImageBase64 == null) {
                callback.onResult(false, "无法获取测试图片");
                return;
            }
            
            JSONObject jsonBody = buildVisionRequest(model, null, testImageBase64, 
                "这是一个测试图片（应用图标），请简单描述你看到了什么。回复应该简短。");
            
            RequestBody body = RequestBody.create(
                    jsonBody.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );
            
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();
            
            Log.d(TAG, "测试Vision连接: " + model + " -> " + apiUrl);
            
            // 记录请求日志（截断base64）
            if (ConfigManager.isVerboseLogEnabled()) {
                String logBody = truncateBase64InJson(jsonBody.toString(), 200);
                Log.d(TAG, "Vision测试请求:\n" + logBody);
            }
            
            getClient().newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    String error = e.getMessage();
                    Log.e(TAG, "Vision测试失败: " + error, e);
                    mainHandler.post(() -> callback.onResult(false, "连接失败: " + error));
                }
                
                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        int code = response.code();
                        String responseBody = response.body() != null ? response.body().string() : "";
                        
                        if (response.isSuccessful()) {
                            String description = parseVisionResponse(responseBody);
                            Log.d(TAG, "Vision测试成功: " + description);
                            mainHandler.post(() -> callback.onResult(true, 
                                "连接成功!\n模型: " + model + "\n响应: " + truncate(description, 100)));
                        } else {
                            String error = "HTTP " + code + ": " + response.message();
                            Log.e(TAG, "Vision测试响应错误: " + error + "\n" + responseBody);
                            
                            // 尝试解析错误信息
                            String errorDetail = parseErrorMessage(responseBody);
                            mainHandler.post(() -> callback.onResult(false, 
                                error + (errorDetail != null ? "\n" + errorDetail : "")));
                        }
                    } finally {
                        response.close();
                    }
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "构建Vision测试请求失败", e);
            callback.onResult(false, "请求构建失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取应用图标的Base64编码
     * @param context Android上下文
     * @return Base64编码字符串（带前缀），失败返回null
     */
    private static String getAppIconBase64(Context context) {
        try {
            // 获取应用图标
            android.graphics.drawable.Drawable drawable = context.getApplicationInfo().loadIcon(context.getPackageManager());
            
            // 转换为Bitmap
            android.graphics.Bitmap bitmap;
            if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
                bitmap = ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
            } else {
                // 创建一个新的Bitmap
                int width = drawable.getIntrinsicWidth();
                int height = drawable.getIntrinsicHeight();
                if (width <= 0) width = 100;
                if (height <= 0) height = 100;
                
                bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                drawable.draw(canvas);
            }
            
            // 压缩为PNG并转Base64
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos);
            byte[] bytes = baos.toByteArray();
            
            String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            return "data:image/png;base64," + base64;
            
        } catch (Exception e) {
            Log.e(TAG, "获取应用图标失败", e);
            return null;
        }
    }


    /**
     * 构建Vision API请求体（OpenAI格式）
     * @param model 模型名称
     * @param imageBase64WithPrefix 带前缀的Base64编码(如 data:image/png;base64,xxx)
     * @param customPrompt 自定义提示词，为null时使用默认提示词
     */
    private static JSONObject buildVisionRequest(String model, String imageUrl, String imageBase64, String customPrompt) throws Exception {
        JSONObject jsonBody = new JSONObject();
        jsonBody.put("model", model);
        jsonBody.put("max_tokens", 500);
        
        JSONArray messages = new JSONArray();
        
        // 用户消息（包含图片）
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        
        JSONArray content = new JSONArray();
        
        // 文本提示
        JSONObject textContent = new JSONObject();
        textContent.put("type", "text");
        textContent.put("text", customPrompt != null ? customPrompt : IMAGE_DESCRIPTION_PROMPT);
        content.put(textContent);
        
        // 图片内容
        JSONObject imageContent = new JSONObject();
        imageContent.put("type", "image_url");
        
        JSONObject imageUrlObj = new JSONObject();
        if (imageBase64 != null && !imageBase64.isEmpty()) {
            // 优先使用base64数据（已带前缀）
            if (imageBase64.startsWith("data:image")) {
                imageUrlObj.put("url", imageBase64);
            } else {
                // 兼容不带前缀的情况
                imageUrlObj.put("url", "data:image/jpeg;base64," + imageBase64);
            }
        } else if (imageUrl != null && !imageUrl.isEmpty()) {
            imageUrlObj.put("url", imageUrl);
        } else {
            throw new Exception("图片URL和Base64数据都为空");
        }
        imageUrlObj.put("detail", "low"); // 使用低分辨率以节省token
        imageContent.put("image_url", imageUrlObj);
        content.put(imageContent);
        
        userMsg.put("content", content);
        messages.put(userMsg);
        
        jsonBody.put("messages", messages);
        
        return jsonBody;
    }
    
    /**
     * 使用Base64编码的图片获取描述（同步方法，用于内部调用）
     * @param imageBase64WithPrefix 带前缀的Base64编码
     * @return 图片描述，失败返回null
     */
    public static String analyzeImageSync(String imageBase64WithPrefix) {
        if (!ConfigManager.isVisionAiEnabled()) {
            return null;
        }
        
        String apiUrl = ConfigManager.getVisionApiUrl();
        String apiKey = ConfigManager.getVisionApiKey();
        String model = ConfigManager.getVisionAiModel();
        
        if (apiUrl == null || apiUrl.trim().isEmpty() || apiKey == null || apiKey.trim().isEmpty()) {
            Log.w(TAG, "外挂AI配置不完整");
            return null;
        }
        
        try {
            JSONObject jsonBody = buildVisionRequest(model, null, imageBase64WithPrefix, null);
            
            RequestBody body = RequestBody.create(
                    jsonBody.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );
            
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();
            
            // 记录请求日志（截断base64）
            if (ConfigManager.isVerboseLogEnabled()) {
                String logBody = truncateBase64InJson(jsonBody.toString(), 200);
                Log.d(TAG, "发送Vision请求: " + model + "\n" + logBody);
            }
            
            Response response = getClient().newCall(request).execute();
            
            try {
                if (!response.isSuccessful()) {
                    String error = "HTTP " + response.code() + ": " + response.message();
                    Log.e(TAG, "Vision响应错误: " + error);
                    return null;
                }
                
                String responseBody = response.body() != null ? response.body().string() : "";
                String description = parseVisionResponse(responseBody);
                
                if (ConfigManager.isVerboseLogEnabled()) {
                    Log.d(TAG, "Vision响应: " + description);
                }
                
                return description;
            } finally {
                response.close();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Vision请求失败", e);
            return null;
        }
    }
    
    /**
     * 截断JSON中的Base64内容用于日志
     */
    private static String truncateBase64InJson(String json, int maxBase64Length) {
        if (json == null) return "null";
        
        // 查找 data:image 开头的base64内容并截断
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < json.length()) {
            int dataStart = json.indexOf("data:image", i);
            if (dataStart == -1) {
                result.append(json.substring(i));
                break;
            }
            
            result.append(json.substring(i, dataStart));
            
            // 找到base64内容的结束位置（引号或逗号）
            int contentEnd = -1;
            for (int j = dataStart; j < json.length(); j++) {
                char c = json.charAt(j);
                if (c == '"' || c == ',' || c == '}') {
                    contentEnd = j;
                    break;
                }
            }
            
            if (contentEnd == -1) {
                contentEnd = json.length();
            }
            
            String base64Content = json.substring(dataStart, contentEnd);
            if (base64Content.length() > maxBase64Length) {
                result.append(base64Content.substring(0, maxBase64Length)).append("...[truncated]");
            } else {
                result.append(base64Content);
            }
            
            i = contentEnd;
        }
        
        return result.toString();
    }

    /**
     * 构建包含多张图片的Vision API请求体
     */
    private static JSONObject buildMultiImageVisionRequest(String model, List<String> imageUrls, 
                                                           List<String> imageBase64List) throws Exception {
        JSONObject jsonBody = new JSONObject();
        jsonBody.put("model", model);
        jsonBody.put("max_tokens", 1000);
        
        JSONArray messages = new JSONArray();
        
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        
        JSONArray content = new JSONArray();
        
        // 文本提示
        JSONObject textContent = new JSONObject();
        textContent.put("type", "text");
        textContent.put("text", "请简洁描述这些图片的内容，用于帮助理解聊天上下文。" +
            "对每张图片分别描述，格式为：[图片1] 描述内容 [图片2] 描述内容...");
        content.put(textContent);
        
        // 添加所有图片
        for (int i = 0; i < imageUrls.size(); i++) {
            String url = imageUrls.get(i);
            String base64 = (imageBase64List != null && i < imageBase64List.size()) ? 
                           imageBase64List.get(i) : null;
            
            JSONObject imageContent = new JSONObject();
            imageContent.put("type", "image_url");
            
            JSONObject imageUrlObj = new JSONObject();
            if (url != null && !url.isEmpty()) {
                imageUrlObj.put("url", url);
            } else if (base64 != null && !base64.isEmpty()) {
                String dataUrl = "data:image/jpeg;base64," + base64;
                imageUrlObj.put("url", dataUrl);
            } else {
                continue; // 跳过无效图片
            }
            imageUrlObj.put("detail", "low");
            imageContent.put("image_url", imageUrlObj);
            content.put(imageContent);
        }
        
        userMsg.put("content", content);
        messages.put(userMsg);
        
        jsonBody.put("messages", messages);
        
        return jsonBody;
    }


    /**
     * 解析Vision API响应（OpenAI格式）
     */
    private static String parseVisionResponse(String responseBody) {
        try {
            JSONObject json = new JSONObject(responseBody);
            
            // OpenAI格式: choices[0].message.content
            if (json.has("choices")) {
                JSONArray choices = json.getJSONArray("choices");
                if (choices.length() > 0) {
                    JSONObject choice = choices.getJSONObject(0);
                    if (choice.has("message")) {
                        JSONObject message = choice.getJSONObject("message");
                        return message.optString("content", "无法解析响应");
                    }
                }
            }
            
            // 尝试其他格式
            if (json.has("content")) {
                return json.getString("content");
            }
            
            if (json.has("text")) {
                return json.getString("text");
            }
            
            if (json.has("response")) {
                return json.getString("response");
            }
            
            Log.w(TAG, "无法解析Vision响应格式: " + responseBody);
            return "无法解析响应";
            
        } catch (Exception e) {
            Log.e(TAG, "解析Vision响应失败", e);
            return "解析响应失败: " + e.getMessage();
        }
    }

    /**
     * 解析错误响应中的错误信息
     */
    private static String parseErrorMessage(String responseBody) {
        try {
            JSONObject json = new JSONObject(responseBody);
            
            if (json.has("error")) {
                Object error = json.get("error");
                if (error instanceof JSONObject) {
                    JSONObject errorObj = (JSONObject) error;
                    return errorObj.optString("message", errorObj.toString());
                } else {
                    return error.toString();
                }
            }
            
            if (json.has("message")) {
                return json.getString("message");
            }
            
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 截断字符串
     */
    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}
