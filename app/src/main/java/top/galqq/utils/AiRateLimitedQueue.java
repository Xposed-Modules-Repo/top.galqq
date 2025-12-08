package top.galqq.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XposedBridge;
import top.galqq.config.ConfigManager;
import top.galqq.hook.MessageInterceptor;

/**
 * AI请求限流队列管理器（完全重写）
 * 
 * 功能：
 * 1. 动态QPS限流（初始3 req/s，429时降速，成功后恢复）
 * 2. 优先级队列（可见消息优先处理）
 * 3. 线程池异步处理
 * 4. 失败重试 + 指数退避
 * 5. 持久化队列（进程重启恢复）
 */
public class AiRateLimitedQueue {
    
    private static final String TAG = "GalQQ.AiQueue";
    
    private static void debugLog(String message) {
        try {
            if (ConfigManager.isVerboseLogEnabled()) {
                XposedBridge.log(message);
            }
        } catch (Throwable ignored) {}
    }
    
    // 单例
    private static volatile AiRateLimitedQueue instance;
    
    // 优先级队列（自动排序）
    private final PriorityBlockingQueue<PrioritizedRequest> requestQueue;
    
    // 动态限流器
    private final DynamicRateLimiter rateLimiter;
    
    // 持久化管理器
    private final RequestPersistence persistence;
    
    // 异步执行线程池
    private final ExecutorService executorService;
    
    // 当前正在处理的请求描述（用于监控）
    private final List<String> activeRequests = java.util.Collections.synchronizedList(new ArrayList<>());
    
    // 工作线程
    private Thread workerThread;
    
    // UI Handler
    private final Handler mainHandler;
    
    private AiRateLimitedQueue(Context context) {
        // 初始化优先级队列（容量100）
        this.requestQueue = new PriorityBlockingQueue<>(100);
        
        // 初始化动态限流器（使用配置的QPS，默认3.0）
        float initialQps = ConfigManager.getAiQps();
        this.rateLimiter = new DynamicRateLimiter(initialQps, 0.5);
        
        // 初始化持久化管理器
        this.persistence = new RequestPersistence(context);
        
        // 初始化线程池（用于并发执行请求，避免阻塞队列）
        this.executorService = Executors.newCachedThreadPool();
        
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        // 恢复持久化的请求
        restoreRequests(context);
        
        // 启动工作线程
        startWorker();
        
        debugLog(TAG + ": 初始化完成，初始QPS=" + initialQps);
    }
    
    public static AiRateLimitedQueue getInstance(Context context) {
        if (instance == null) {
            synchronized (AiRateLimitedQueue.class) {
                if (instance == null) {
                    instance = new AiRateLimitedQueue(context);
                }
            }
        }
        return instance;
    }
    
    /**
     * 提交AI请求（带优先级和上下文）
     */
    public void submitRequest(Context context, String msgContent, String msgId, Priority priority, 
                              List<top.galqq.utils.MessageContextManager.ChatMessage> contextMessages,
                              String currentSenderName, long currentTimestamp,
                              HttpAiClient.AiCallback callback) {
        submitRequest(context, msgContent, msgId, priority, contextMessages, 
                     currentSenderName, currentTimestamp, null, null, callback);
    }
    
    /**
     * 提交AI请求（带优先级、上下文、发送者QQ和自定义提示词）
     * 
     * @param senderQQ 发送者QQ号，用于黑白名单过滤
     * @param customSystemPrompt 自定义系统提示词，如果为null则使用默认
     */
    public void submitRequest(Context context, String msgContent, String msgId, Priority priority, 
                              List<top.galqq.utils.MessageContextManager.ChatMessage> contextMessages,
                              String currentSenderName, long currentTimestamp,
                              String senderQQ, String customSystemPrompt,
                              HttpAiClient.AiCallback callback) {
        submitRequest(context, msgContent, msgId, priority, contextMessages, 
                     currentSenderName, currentTimestamp, senderQQ, customSystemPrompt, 
                     null, callback);
    }
    
    /**
     * 提交AI请求（带优先级、上下文、发送者QQ、自定义提示词和图片元素）
     * 
     * @param senderQQ 发送者QQ号，用于黑白名单过滤
     * @param customSystemPrompt 自定义系统提示词，如果为null则使用默认
     * @param imageElements 图片元素列表，用于图片识别
     */
    public void submitRequest(Context context, String msgContent, String msgId, Priority priority, 
                              List<top.galqq.utils.MessageContextManager.ChatMessage> contextMessages,
                              String currentSenderName, long currentTimestamp,
                              String senderQQ, String customSystemPrompt,
                              List<ImageExtractor.ImageElement> imageElements,
                              HttpAiClient.AiCallback callback) {
        submitRequest(context, msgContent, msgId, priority, contextMessages,
                     currentSenderName, currentTimestamp, senderQQ, customSystemPrompt,
                     imageElements, null, callback);
    }
    
    /**
     * 提交AI请求（带优先级、上下文、发送者QQ、自定义提示词、图片元素和会话ID）
     * 
     * @param senderQQ 发送者QQ号，用于黑白名单过滤
     * @param customSystemPrompt 自定义系统提示词，如果为null则使用默认
     * @param imageElements 图片元素列表，用于图片识别
     * @param conversationId 会话ID，用于图片描述缓存
     */
    public void submitRequest(Context context, String msgContent, String msgId, Priority priority, 
                              List<top.galqq.utils.MessageContextManager.ChatMessage> contextMessages,
                              String currentSenderName, long currentTimestamp,
                              String senderQQ, String customSystemPrompt,
                              List<ImageExtractor.ImageElement> imageElements,
                              String conversationId,
                              HttpAiClient.AiCallback callback) {
        PrioritizedRequest request = new PrioritizedRequest(
            context, msgContent, msgId, priority, contextMessages, 
            currentSenderName, currentTimestamp, senderQQ, customSystemPrompt, 
            imageElements, conversationId, callback, System.currentTimeMillis()
        );
        
        boolean added = requestQueue.offer(request);
        if (added) {
            // XposedBridge.log(TAG + ": 请求入队 [" + priority + "] 队列大小=" + requestQueue.size());
            // 只有HIGH优先级且有msgId的任务才持久化，避免IO过于频繁
            if (priority == Priority.HIGH && msgId != null) {
                persistence.saveQueueAsync(requestQueue);
            }
        } else {
            debugLog(TAG + ": ⚠️ 队列已满，丢弃请求");
            callback.onFailure(new Exception("队列已满"));
        }
    }
    
    /**
     * 恢复持久化的请求
     */
    private void restoreRequests(Context context) {
        List<PrioritizedRequest> restored = persistence.loadQueue(context);
        if (!restored.isEmpty()) {
            debugLog(TAG + ": 恢复了 " + restored.size() + " 个持久化请求");
            for (PrioritizedRequest req : restored) {
                requestQueue.offer(req);
            }
        }
    }
    
    /**
     * 启动工作线程（持续从队列取任务）
     */
    private void startWorker() {
        workerThread = new Thread(() -> {
            debugLog(TAG + ": 工作线程启动");
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 阻塞获取下一个请求
                    PrioritizedRequest request = requestQueue.take();
                    
                    // 限流：等待直到可以发送
                    rateLimiter.acquire();
                    
                    // 异步提交到线程池执行，不阻塞工作线程
                    executorService.submit(() -> {
                        String reqInfo = "[" + request.priority + "] " + 
                                       (request.msgContent.length() > 10 ? request.msgContent.substring(0, 10) + "..." : request.msgContent);
                        activeRequests.add(reqInfo);
                        try {
                            // 处理请求（带重试）
                            processRequest(request);
                            
                            // 处理完后更新持久化（移除已完成的）
                            if (request.priority == Priority.HIGH && request.msgId != null) {
                                persistence.saveQueueAsync(requestQueue);
                            }
                        } catch (Throwable t) {
                            debugLog(TAG + ": 异步任务执行异常: " + t.getMessage());
                        } finally {
                            activeRequests.remove(reqInfo);
                        }
                    });
                    
                } catch (InterruptedException e) {
                    debugLog(TAG + ": 工作线程被中断");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": 工作线程异常: " + t.getMessage());
                }
            }
        }, "AiQueueWorker");
        workerThread.start();
    }
    
    /**
     * 处理单个请求（带重试）
     * 支持两种重试：
     * 1. 429速率限制：最多重试3次，指数退避
     * 2. 格式错误：最多重试5次，静默重试
     */
    private void processRequest(PrioritizedRequest request) {
        final int MAX_RATE_LIMIT_RETRIES = 3;
        final int MAX_FORMAT_ERROR_RETRIES = 5;
        final int[] BACKOFF_MS = {1000, 2000, 4000};  // 1s, 2s, 4s
        
        int rateLimitAttempt = 0;
        int formatErrorAttempt = 0;
        Exception lastException = null;
        
        while (rateLimitAttempt <= MAX_RATE_LIMIT_RETRIES && formatErrorAttempt < MAX_FORMAT_ERROR_RETRIES) {
            try {
                // 调用AI接口（同步）
                final List<String> options = fetchOptionsSync(request);
                
                // 成功：通知限流器
                rateLimiter.onSuccess();
                
                // 回调成功（切换到UI线程）
                mainHandler.post(() -> request.callback.onSuccess(options));
                
                return;
                
            } catch (RateLimitException e) {
                // 429错误：动态降速
                rateLimiter.on429Error();
                lastException = e;
                
                if (rateLimitAttempt < MAX_RATE_LIMIT_RETRIES) {
                    long delay = BACKOFF_MS[rateLimitAttempt];
                    XposedBridge.log(TAG + ": ⚠️ 触发429限流，等待 " + delay + "ms 后重试");
                    SystemClock.sleep(delay);
                    rateLimitAttempt++;
                } else {
                    break;  // 429重试次数用尽
                }
                
            } catch (FormatErrorException e) {
                // 格式错误：静默重试
                formatErrorAttempt++;
                lastException = e;
                
                if (formatErrorAttempt < MAX_FORMAT_ERROR_RETRIES) {
                    XposedBridge.log(TAG + ": ⚠️ AI返回格式错误，自动重试 (" + formatErrorAttempt + "/" + MAX_FORMAT_ERROR_RETRIES + ")");
                    // 短暂延迟后重试
                    SystemClock.sleep(500);
                } else {
                    // 达到最大重试次数
                    XposedBridge.log(TAG + ": ❌ AI返回格式错误，已重试 " + MAX_FORMAT_ERROR_RETRIES + " 次仍失败");
                    break;
                }
                
            } catch (Exception e) {
                // 其他错误：直接失败，不重试
                XposedBridge.log(TAG + ": ❌ 请求失败: " + e.getMessage());
                mainHandler.post(() -> request.callback.onFailure(e));
                return;
            }
        }
        
        // 重试次数用尽，最终失败
        final Exception finalException = lastException;
        final boolean isFormatError = finalException instanceof FormatErrorException;
        
        if (isFormatError) {
            XposedBridge.log(TAG + ": ❌ 格式错误重试 " + MAX_FORMAT_ERROR_RETRIES + " 次后仍失败，通知显示重新加载按钮");
            // 格式错误：通知显示重新加载按钮
            if (request.callback instanceof HttpAiClient.AiCallbackWithRetry) {
                HttpAiClient.AiCallbackWithRetry retryCallback = (HttpAiClient.AiCallbackWithRetry) request.callback;
                // 创建重试动作
                Runnable retryAction = () -> {
                    XposedBridge.log(TAG + ": 用户点击重新加载");
                    // 重新提交请求（保留senderQQ和customSystemPrompt）
                    submitRequest(request.context, request.msgContent, request.msgId, request.priority,
                                 request.contextMessages, request.currentSenderName, request.currentTimestamp,
                                 request.senderQQ, request.customSystemPrompt, request.callback);
                };
                mainHandler.post(() -> retryCallback.onAllRetriesFailed(retryAction));
            } else {
                // 普通回调：直接失败
                mainHandler.post(() -> request.callback.onFailure(finalException));
            }
        } else {
            mainHandler.post(() -> request.callback.onFailure(finalException));
        }
    }
    
    /**
     * 同步调用AI接口（供内部使用）
     */
    private List<String> fetchOptionsSync(PrioritizedRequest request) throws Exception {
        final Object lock = new Object();
        final List<String>[] resultHolder = new List[1];
        final Exception[] errorHolder = new Exception[1];
        
        HttpAiClient.AiCallback syncCallback = new HttpAiClient.AiCallback() {
            @Override
            public void onSuccess(List<String> options) {
                synchronized (lock) {
                    resultHolder[0] = options;
                    lock.notify();
                }
            }
            
            @Override
            public void onFailure(Exception e) {
                synchronized (lock) {
                    errorHolder[0] = e;
                    lock.notify();
                }
            }
        };
        
        synchronized (lock) {
            // 检查是否有图片需要处理
            boolean hasImages = request.imageElements != null && !request.imageElements.isEmpty() 
                               && ConfigManager.isImageRecognitionEnabled();
            
            if (hasImages) {
                // 使用带图片的方法（传递conversationId和msgId用于缓存）
                debugLog(TAG + ": 检测到图片，使用fetchOptionsWithImages处理");
                HttpAiClient.fetchOptionsWithImages(request.context, request.msgContent,
                                         request.currentSenderName, request.currentTimestamp,
                                         request.contextMessages, request.customSystemPrompt,
                                         request.imageElements, request.conversationId, request.msgId,
                                         syncCallback);
            } else if (request.customSystemPrompt != null && !request.customSystemPrompt.isEmpty()) {
                // 使用自定义提示词（静默模式）
                HttpAiClient.fetchOptionsWithPromptSilent(request.context, request.msgContent,
                                         request.currentSenderName, request.currentTimestamp,
                                         request.contextMessages, request.customSystemPrompt,
                                         syncCallback);
            } else {
                // 使用默认提示词（静默模式）
                HttpAiClient.fetchOptionsSilent(request.context, request.msgContent,
                                         request.currentSenderName, request.currentTimestamp,
                                         request.contextMessages, syncCallback);
            }
            
            // 等待结果（最多60秒，图片处理可能需要更长时间）
            lock.wait(60000);
        }
        
        if (errorHolder[0] != null) {
            String errorMsg = errorHolder[0].getMessage();
            if (errorMsg != null) {
                // 检查是否是429错误
                if (errorMsg.contains("Rate limit")) {
                    throw new RateLimitException(errorHolder[0]);
                }
                // 检查是否是格式错误（可重试）
                if (errorMsg.contains("格式") || errorMsg.contains("选项不足")) {
                    throw new FormatErrorException(errorHolder[0]);
                }
            }
            throw errorHolder[0];
        }
        
        if (resultHolder[0] == null) {
            throw new Exception("请求超时");
        }
        
        return resultHolder[0];
    }
    
    /**
     * 获取当前队列大小
     */
    public int getQueueSize() {
        return requestQueue.size();
    }
    
    /**
     * 获取当前QPS
     */
    public double getCurrentQPS() {
        return rateLimiter.getCurrentQPS();
    }
    
    /**
     * 获取当前正在处理的请求列表
     */
    public List<String> getActiveRequests() {
        return new ArrayList<>(activeRequests);
    }
    
    /**
     * 获取线程池状态信息
     */
    public String getThreadPoolInfo() {
        if (executorService instanceof java.util.concurrent.ThreadPoolExecutor) {
            java.util.concurrent.ThreadPoolExecutor pool = (java.util.concurrent.ThreadPoolExecutor) executorService;
            return "Active: " + pool.getActiveCount() + 
                   ", Pool: " + pool.getPoolSize() + 
                   ", Core: " + pool.getCorePoolSize() + 
                   ", Max: " + pool.getMaximumPoolSize();
        }
        return "Unknown Executor Type";
    }
    
    // ========== 内部类 ==========
    
    /**
     * 优先级枚举
     */
    public enum Priority {
        HIGH(0),    // 高优先级（屏幕可见消息）
        NORMAL(1);  // 普通优先级
        
        final int value;
        
        Priority(int value) {
            this.value = value;
        }
        
        public static Priority fromInt(int value) {
            for (Priority p : values()) {
                if (p.value == value) return p;
            }
            return NORMAL;
        }
    }
    
    /**
     * 带优先级的请求对象
     */
    private static class PrioritizedRequest implements Comparable<PrioritizedRequest> {
        final Context context;
        final String msgContent;
        final String msgId; // 用于持久化和缓存
        final Priority priority;
        final List<top.galqq.utils.MessageContextManager.ChatMessage> contextMessages; // 上下文消息
        final String currentSenderName; // 当前消息发送人昵称
        final long currentTimestamp; // 当前消息时间戳
        final String senderQQ; // 发送者QQ号（用于黑白名单过滤）
        final String customSystemPrompt; // 自定义系统提示词
        final List<ImageExtractor.ImageElement> imageElements; // 图片元素列表
        final String conversationId; // 会话ID（用于图片描述缓存）
        final HttpAiClient.AiCallback callback;
        final long timestamp;  // 同优先级按时间排序
        
        PrioritizedRequest(Context context, String msgContent, String msgId, Priority priority, 
                          List<top.galqq.utils.MessageContextManager.ChatMessage> contextMessages,
                          String currentSenderName, long currentTimestamp,
                          HttpAiClient.AiCallback callback, long timestamp) {
            this(context, msgContent, msgId, priority, contextMessages, currentSenderName, 
                 currentTimestamp, null, null, null, null, callback, timestamp);
        }
        
        PrioritizedRequest(Context context, String msgContent, String msgId, Priority priority, 
                          List<top.galqq.utils.MessageContextManager.ChatMessage> contextMessages,
                          String currentSenderName, long currentTimestamp,
                          String senderQQ, String customSystemPrompt,
                          List<ImageExtractor.ImageElement> imageElements,
                          HttpAiClient.AiCallback callback, long timestamp) {
            this(context, msgContent, msgId, priority, contextMessages, currentSenderName,
                 currentTimestamp, senderQQ, customSystemPrompt, imageElements, null, callback, timestamp);
        }
        
        PrioritizedRequest(Context context, String msgContent, String msgId, Priority priority, 
                          List<top.galqq.utils.MessageContextManager.ChatMessage> contextMessages,
                          String currentSenderName, long currentTimestamp,
                          String senderQQ, String customSystemPrompt,
                          List<ImageExtractor.ImageElement> imageElements,
                          String conversationId,
                          HttpAiClient.AiCallback callback, long timestamp) {
            this.context = context;
            this.msgContent = msgContent;
            this.msgId = msgId;
            this.priority = priority;
            this.contextMessages = contextMessages;
            this.currentSenderName = currentSenderName;
            this.currentTimestamp = currentTimestamp;
            this.senderQQ = senderQQ;
            this.customSystemPrompt = customSystemPrompt;
            this.imageElements = imageElements;
            this.conversationId = conversationId;
            this.callback = callback;
            this.timestamp = timestamp;
        }
        
        @Override
        public int compareTo(PrioritizedRequest other) {
            // 先按优先级排序
            int p = Integer.compare(this.priority.value, other.priority.value);
            if (p != 0) return p;
            
            // 同优先级按时间排序（FIFO）
            return Long.compare(this.timestamp, other.timestamp);
        }
        
        // 序列化为JSON
        JSONObject toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("msgContent", msgContent);
                json.put("msgId", msgId);
                json.put("priority", priority.value);
                json.put("timestamp", timestamp);
                return json;
            } catch (Exception e) {
                return null;
            }
        }
        
        // 从JSON反序列化（创建恢复用的请求）
        static PrioritizedRequest fromJson(Context context, JSONObject json) {
            try {
                String msgContent = json.getString("msgContent");
                String msgId = json.optString("msgId", null);
                int priorityVal = json.getInt("priority");
                long timestamp = json.getLong("timestamp");
                
                // 创建一个特殊的Callback，只负责更新缓存
                HttpAiClient.AiCallback restoreCallback = new HttpAiClient.AiCallback() {
                    @Override
                    public void onSuccess(List<String> options) {
                        // 恢复的任务成功后，只更新缓存
                        if (msgId != null) {
                            MessageInterceptor.cacheOptions(msgId, options);
                            XposedBridge.log(TAG + ": 恢复的任务已完成并缓存: " + msgId);
                        }
                    }
                    
                    @Override
                    public void onFailure(Exception e) {
                        // 失败忽略
                    }
                };
                
                // 恢复的请求不包含上下文（传null）
                return new PrioritizedRequest(context, msgContent, msgId, 
                    Priority.fromInt(priorityVal), null, null, 0, restoreCallback, timestamp);
            } catch (Exception e) {
                return null;
            }
        }
    }
    
    /**
     * 动态QPS限流器
     */
    /**
     * 动态QPS限流器
     */
    private static class DynamicRateLimiter {
        private volatile double currentQPS;        // 当前QPS
        private volatile double targetQPS;         // 目标QPS（配置值）
        private final double minQPS;               // 最小QPS
        private final AtomicInteger successCount = new AtomicInteger(0);
        private volatile long lastAdjustTime = System.currentTimeMillis();
        private volatile long lastTokenTime = System.currentTimeMillis();
        
        DynamicRateLimiter(double initialQPS, double minQPS) {
            this.targetQPS = initialQPS;
            this.minQPS = minQPS;
            this.currentQPS = initialQPS;
        }
        
        /**
         * 更新目标QPS
         */
        void updateTargetQps(double newQps) {
            if (Math.abs(this.targetQPS - newQps) > 0.1) {
                XposedBridge.log(TAG + ": 更新目标QPS: " + this.targetQPS + " -> " + newQps);
                this.targetQPS = newQps;
                // 如果当前QPS高于新目标，立即降低
                if (this.currentQPS > newQps) {
                    this.currentQPS = newQps;
                }
            }
        }
        
        /**
         * 获取令牌（阻塞直到可用）
         */
        synchronized void acquire() {
            // 每次获取令牌前，检查配置是否有更新（MMKV读取很快）
            float configQps = ConfigManager.getAiQps();
            updateTargetQps(configQps);
            
            long intervalMs = (long) (1000.0 / currentQPS);
            long now = System.currentTimeMillis();
            long waitTime = lastTokenTime + intervalMs - now;
            
            if (waitTime > 0) {
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            lastTokenTime = System.currentTimeMillis();
        }
        
        /**
         * 429错误：降速50%
         */
        synchronized void on429Error() {
            double oldQPS = currentQPS;
            currentQPS = Math.max(minQPS, currentQPS * 0.5);
            successCount.set(0);
            
            XposedBridge.log(TAG + ": 🔽 触发429，QPS降速: " + 
                           String.format("%.2f", oldQPS) + " → " + 
                           String.format("%.2f", currentQPS) + " req/s");
        }
        
        /**
         * 成功回调：连续成功后恢复QPS
         */
        void onSuccess() {
            int count = successCount.incrementAndGet();
            long now = System.currentTimeMillis();
            
            // 每30秒最多恢复一次，且需要连续成功10次
            if (now - lastAdjustTime > 30000 && count >= 10 && currentQPS < targetQPS) {
                synchronized (this) {
                    double oldQPS = currentQPS;
                    // 恢复时不超过目标QPS
                    currentQPS = Math.min(targetQPS, currentQPS * 1.2);
                    successCount.set(0);
                    lastAdjustTime = now;
                    
                    XposedBridge.log(TAG + ": 🔼 连续成功，QPS恢复: " + 
                                   String.format("%.2f", oldQPS) + " → " + 
                                   String.format("%.2f", currentQPS) + " req/s");
                }
            }
        }
        
        double getCurrentQPS() {
            return currentQPS;
        }
    }
    
    /**
     * 持久化管理器
     */
    private static class RequestPersistence {
        private static final String PREF_NAME = "galqq_ai_queue";
        private static final String KEY_PENDING = "pending_requests";
        private final SharedPreferences prefs;
        private final Handler bgHandler;
        private long lastSaveTime = 0;
        
        RequestPersistence(Context context) {
            this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            // 使用后台线程处理IO
            android.os.HandlerThread thread = new android.os.HandlerThread("AiPersistence");
            thread.start();
            this.bgHandler = new Handler(thread.getLooper());
        }
        
        // 异步保存队列（节流：最多1秒1次）
        void saveQueueAsync(PriorityBlockingQueue<PrioritizedRequest> queue) {
            long now = System.currentTimeMillis();
            if (now - lastSaveTime < 1000) {
                return; // 节流
            }
            lastSaveTime = now;
            
            bgHandler.post(() -> {
                try {
                    // 复制当前队列快照
                    List<PrioritizedRequest> snapshot = new ArrayList<>(queue);
                    JSONArray array = new JSONArray();
                    
                    // 只保存HIGH优先级且有msgId的任务，最多50条
                    int count = 0;
                    for (PrioritizedRequest req : snapshot) {
                        if (req.priority == Priority.HIGH && req.msgId != null) {
                            JSONObject json = req.toJson();
                            if (json != null) {
                                array.put(json);
                                count++;
                            }
                        }
                        if (count >= 50) break;
                    }
                    
                    prefs.edit().putString(KEY_PENDING, array.toString()).apply();
                    // XposedBridge.log(TAG + ": 已持久化 " + count + " 个请求");
                } catch (Exception e) {
                    XposedBridge.log(TAG + ": 持久化失败: " + e.getMessage());
                }
            });
        }
        
        // 加载队列（同步）
        List<PrioritizedRequest> loadQueue(Context context) {
            List<PrioritizedRequest> result = new ArrayList<>();
            try {
                String jsonStr = prefs.getString(KEY_PENDING, "[]");
                JSONArray array = new JSONArray(jsonStr);
                
                for (int i = 0; i < array.length(); i++) {
                    JSONObject json = array.getJSONObject(i);
                    PrioritizedRequest req = PrioritizedRequest.fromJson(context, json);
                    if (req != null) {
                        result.add(req);
                    }
                }
                
                // 加载后清空，避免重复处理
                prefs.edit().remove(KEY_PENDING).apply();
                
            } catch (Exception e) {
                XposedBridge.log(TAG + ": 加载持久化请求失败: " + e.getMessage());
            }
            return result;
        }
    }
    
    /**
     * 速率限制异常（429错误）
     */
    private static class RateLimitException extends Exception {
        RateLimitException(Exception cause) {
            super("Rate limit exceeded", cause);
        }
    }
    
    /**
     * 格式错误异常（AI返回格式无法解析）
     */
    private static class FormatErrorException extends Exception {
        FormatErrorException(Exception cause) {
            super("AI返回格式错误", cause);
        }
        
        FormatErrorException(String message) {
            super(message);
        }
    }
}
